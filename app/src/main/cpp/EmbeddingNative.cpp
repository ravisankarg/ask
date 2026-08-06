#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "AskGalaxyEmbedding";
std::once_flag backend_once;
std::mutex mutex;
llama_model * model = nullptr;
llama_context * context = nullptr;

void close_model() {
    if (context) { llama_free(context); context = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}

bool open_model(const char * path) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    close_model();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    model = llama_model_load_from_file(path, params);
    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not load %s", path);
        return false;
    }
    auto context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_seq_max = 8;
    context_params.n_batch = 2048;
    context_params.n_ubatch = 2048;
    const auto online_cores = sysconf(_SC_NPROCESSORS_ONLN);
    context_params.n_threads = std::max<int>(2, static_cast<int>(online_cores));
    context_params.n_threads_batch = context_params.n_threads;
    context_params.embeddings = true;
    context_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    context_params.offload_kqv = false;
    context = llama_init_from_model(model, context_params);
    if (!context) close_model();
    return context != nullptr;
}

std::vector<float> embed(const char * text) {
    if (!model || !context || !text || !*text) return {};
    const auto * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(1024);
    int count = llama_tokenize(vocab, text, static_cast<int32_t>(strlen(text)), tokens.data(), tokens.size(), true, false);
    if (count < 0) {
        tokens.resize(-count);
        count = llama_tokenize(vocab, text, static_cast<int32_t>(strlen(text)), tokens.data(), tokens.size(), true, false);
    }
    if (count <= 0) return {};
    count = std::min(count, 2048);
    tokens.resize(count);
    llama_memory_clear(llama_get_memory(context), false);
    auto batch = llama_batch_get_one(tokens.data(), count);
    const int result = llama_encode(context, batch);
    float * values = result == 0 ? llama_get_embeddings_seq(context, 0) : nullptr;
    const int dimension = llama_model_n_embd(model);
    if (!values || dimension <= 0) return {};
    return std::vector<float>(values, values + dimension);
}

std::vector<float> embed_batch(JNIEnv * env, jobjectArray texts) {
    if (!model || !context || !texts) return {};
    const auto * vocab = llama_model_get_vocab(model);
    const jsize count = env->GetArrayLength(texts);
    if (count <= 0 || count > 8) return {};

    std::vector<std::vector<llama_token>> tokenized;
    tokenized.reserve(count);
    int total_tokens = 0;
    for (jsize i = 0; i < count; ++i) {
        auto text = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
        const char * raw = env->GetStringUTFChars(text, nullptr);
        std::vector<llama_token> tokens(1024);
        int token_count = llama_tokenize(vocab, raw, static_cast<int32_t>(strlen(raw)),
                                         tokens.data(), tokens.size(), true, false);
        env->ReleaseStringUTFChars(text, raw);
        env->DeleteLocalRef(text);
        if (token_count < 0) {
            tokens.resize(-token_count);
            text = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
            raw = env->GetStringUTFChars(text, nullptr);
            token_count = llama_tokenize(vocab, raw, static_cast<int32_t>(strlen(raw)),
                                         tokens.data(), tokens.size(), true, false);
            env->ReleaseStringUTFChars(text, raw);
            env->DeleteLocalRef(text);
        }
        if (token_count <= 0) return {};
        token_count = std::min(token_count, 2048);
        tokens.resize(token_count);
        total_tokens += token_count;
        tokenized.push_back(std::move(tokens));
    }
    if (total_tokens <= 0 || total_tokens > 2048) return {};

    auto batch = llama_batch_init(total_tokens, 0, count);
    int cursor = 0;
    for (jsize sequence = 0; sequence < count; ++sequence) {
        const auto & tokens = tokenized[sequence];
        for (int position = 0; position < static_cast<int>(tokens.size()); ++position) {
            batch.token[cursor] = tokens[position];
            batch.pos[cursor] = position;
            batch.n_seq_id[cursor] = 1;
            batch.seq_id[cursor][0] = sequence;
            batch.logits[cursor] = 0;
            ++cursor;
        }
    }
    llama_memory_clear(llama_get_memory(context), false);
    const int result = llama_encode(context, batch);
    const int dimension = llama_model_n_embd(model);
    std::vector<float> output;
    if (result == 0 && dimension > 0) {
        output.resize(static_cast<size_t>(count) * dimension);
        for (jsize sequence = 0; sequence < count; ++sequence) {
            const float * values = llama_get_embeddings_seq(context, sequence);
            if (!values) { output.clear(); break; }
            std::memcpy(output.data() + static_cast<size_t>(sequence) * dimension,
                        values, static_cast<size_t>(dimension) * sizeof(float));
        }
    }
    llama_batch_free(batch);
    return output;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravi_askgalaxy_EmbeddingNative_open(JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(mutex);
    const char * raw = env->GetStringUTFChars(path, nullptr);
    const bool success = open_model(raw);
    env->ReleaseStringUTFChars(path, raw);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ravi_askgalaxy_EmbeddingNative_embed(JNIEnv * env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(mutex);
    const char * raw = env->GetStringUTFChars(text, nullptr);
    const auto values = embed(raw);
    env->ReleaseStringUTFChars(text, raw);
    if (values.empty()) return nullptr;
    auto result = env->NewFloatArray(static_cast<jsize>(values.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ravi_askgalaxy_EmbeddingNative_embedBatch(JNIEnv * env, jobject, jobjectArray texts) {
    std::lock_guard<std::mutex> lock(mutex);
    const auto values = embed_batch(env, texts);
    if (values.empty()) return nullptr;
    auto result = env->NewFloatArray(static_cast<jsize>(values.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravi_askgalaxy_EmbeddingNative_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(mutex);
    close_model();
}
