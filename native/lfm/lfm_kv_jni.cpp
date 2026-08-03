#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
constexpr const char * TAG = "AskGalaxyLfm";

std::once_flag backend_once;
std::mutex inference_mutex;

struct LfmSession {
    llama_model * model = nullptr;
    mtmd_context * vision = nullptr;
    int threads = 4;
};

LfmSession session;

void init_backend() {
    llama_backend_init();
}

void close_session() {
    if (session.vision) {
        mtmd_free(session.vision);
        session.vision = nullptr;
    }
    if (session.model) {
        llama_model_free(session.model);
        session.model = nullptr;
    }
}

bool open_session(const char * model_path, const char * projector_path) {
    std::call_once(backend_once, init_backend);
    close_session();

    llama_model_params model_params = llama_model_default_params();
    // LFM KV extraction is deliberately CPU-only on this device. Its Adreno
    // driver creates a Vulkan device but rejects LFM's compute pipeline.
    model_params.n_gpu_layers = 0;
    session.model = llama_model_load_from_file(model_path, model_params);
    if (!session.model) return false;

    session.threads = std::clamp<int>(sysconf(_SC_NPROCESSORS_ONLN), 2, 4);
    mtmd_context_params vision_params = mtmd_context_params_default();
    vision_params.use_gpu = false;
    vision_params.n_threads = session.threads;
    vision_params.image_min_tokens = 32;
    vision_params.image_max_tokens = 256;
    session.vision = mtmd_init_from_file(projector_path, session.model, vision_params);
    if (!session.vision) {
        close_session();
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "LFM KV model loaded once for CPU extraction (%d threads)", session.threads);
    return true;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    auto written = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, false);
    }
    return written > 0 ? std::string(buffer.data(), static_cast<size_t>(written)) : std::string();
}

std::string extract(const unsigned char * bytes, size_t size, const char * instruction) {
    if (!session.model || !session.vision) return {};

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 128;
    context_params.n_ubatch = 128;
    context_params.n_threads = session.threads;
    context_params.n_threads_batch = session.threads;
    context_params.offload_kqv = false;
    llama_context * context = llama_init_from_model(session.model, context_params);
    if (!context) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "LFM KV failed: could not create inference context");
        return {};
    }

    auto image = mtmd_helper_bitmap_init_from_buf(session.vision, bytes, size, false);
    if (!image.bitmap) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "LFM KV failed: native decoder rejected %zu-byte JPEG", size);
        llama_free(context);
        return {};
    }

    const char * marker = mtmd_get_marker(session.vision);
    std::string prompt = "<|startoftext|><|im_start|>system\nYou extract factual document key-value pairs. Return strict JSON only.<|im_end|>\n<|im_start|>user\n";
    prompt += marker ? marker : "<image>";
    prompt += "\n";
    prompt += instruction;
    prompt += "<|im_end|>\n<|im_start|>assistant\n";

    mtmd_input_text input { prompt.data(), prompt.size(), true, true };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { image.bitmap };
    llama_pos n_past = 0;
    int result = mtmd_tokenize(session.vision, chunks, &input, bitmaps, 1);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "LFM KV failed: multimodal tokenization returned %d", result);
    }
    if (result == 0) {
        result = mtmd_helper_eval_chunks(
            session.vision,
            context,
            chunks,
            n_past,
            0,
            context_params.n_batch,
            true,
            &n_past
        );
        if (result != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "LFM KV failed: image/prompt evaluation returned %d", result);
        }
    }

    std::string output;
    const llama_vocab * vocab = llama_model_get_vocab(session.model);
    llama_sampler * sampler = llama_sampler_init_greedy();
    for (int step = 0; result == 0 && step < 128; ++step) {
        llama_token token = llama_sampler_sample(sampler, context, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += token_piece(vocab, token);
        llama_batch batch = llama_batch_get_one(&token, 1);
        result = llama_decode(context, batch);
    }

    llama_sampler_free(sampler);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(image.bitmap);
    if (image.video_ctx) mtmd_helper_video_free(image.video_ctx);
    llama_free(context);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "LFM KV failed: generation returned %d", result);
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, "LFM KV generated %zu characters", output.size());
    }
    return result == 0 ? output : std::string();
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravi_askgalaxy_LfmKvNative_open(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jstring projector_path) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    const char * model = env->GetStringUTFChars(model_path, nullptr);
    const char * projector = env->GetStringUTFChars(projector_path, nullptr);
    const bool opened = open_session(model, projector);
    env->ReleaseStringUTFChars(model_path, model);
    env->ReleaseStringUTFChars(projector_path, projector);
    return opened ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ravi_askgalaxy_LfmKvNative_extract(
    JNIEnv * env,
    jobject,
    jbyteArray image_bytes,
    jstring instruction) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    const char * prompt = env->GetStringUTFChars(instruction, nullptr);
    const auto length = static_cast<size_t>(env->GetArrayLength(image_bytes));
    auto * raw = env->GetByteArrayElements(image_bytes, nullptr);
    const auto output = extract(reinterpret_cast<const unsigned char *>(raw), length, prompt);
    env->ReleaseByteArrayElements(image_bytes, raw, JNI_ABORT);
    env->ReleaseStringUTFChars(instruction, prompt);
    return output.empty() ? nullptr : env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravi_askgalaxy_LfmKvNative_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    close_session();
    __android_log_print(ANDROID_LOG_INFO, TAG, "LFM KV model released before SigLIP vector indexing");
}
