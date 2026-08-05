package com.ravi.askgalaxy

import android.content.Context
import java.io.File

data class ModelArtifact(
    val name: String,
    val relativePath: String,
    val runtime: String,
    val required: Boolean,
    val downloadUrl: String? = null,
    val packagedAssetPath: String? = null,
    val expectedBytes: Long = 0L,
    val sha256: String? = null,
    val sourceLabel: String = "Automatic background download",
) {
    fun file(context: Context): File = File(context.filesDir, relativePath)
    fun partFile(context: Context): File = File(context.filesDir, "$relativePath.part")

    fun isInstalled(context: Context): Boolean {
        val target = file(context)
        return target.isFile && (expectedBytes <= 0L || target.length() == expectedBytes)
    }

    fun hasDownloadSource(): Boolean = !downloadUrl.isNullOrBlank()
}

enum class GemmaModelVariant(
    val preferenceValue: String,
    val displayName: String,
) {
    E4B("e4b", "Gemma 4 E4B"),
    ;

    companion object {
        fun fromPreference(value: String?): GemmaModelVariant =
            entries.firstOrNull { it.preferenceValue == value } ?: E4B
    }
}

/** The selected model is persistent. */
object GemmaModelSelection {
    private const val PREFERENCES = "ask_galaxy_model_selection"
    private const val VARIANT_KEY = "gemma_variant"

    fun selected(context: Context): GemmaModelVariant = GemmaModelVariant.fromPreference(
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(VARIANT_KEY, GemmaModelVariant.E4B.preferenceValue),
    )

    fun select(context: Context, variant: GemmaModelVariant): Boolean {
        val appContext = context.applicationContext
        if (selected(appContext) == variant) return false
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(VARIANT_KEY, variant.preferenceValue)
            .apply()
        // The resident engine and its KV cache belong to the previous model.
        // Engine.close() can block while unmapping a multi-GB model, so never
        // perform it on the Settings UI thread.
        GemmaRuntime.releaseResidentAsync()
        GemmaDownloadScheduler.enqueueSelected(appContext, replaceExisting = true)
        return true
    }
}

object ModelCatalog {
    val siglipVision = ModelArtifact(
        name = "SigLIP2 ViT-B/16 224 image encoder",
        relativePath = "models/siglip2/siglip2_base_224_fp16.tflite",
        runtime = "LiteRT FP16",
        required = true,
        downloadUrl = "https://huggingface.co/litert-community/SigLIP2-base-patch16-224/resolve/8444633b1c0570814ea6074ae09fcb8734ff79f0/siglip2_base_224_fp16.tflite?download=true",
        packagedAssetPath = "siglip2_base_224_fp16.tflite",
        expectedBytes = 185_437_744L,
        sha256 = "a30ebb7b3ee15eaa68a18f9ab6a2ed740c15c343d25d898dc482317473320854",
        sourceLabel = "LiteRT Community direct SigLIP2 FP16 artifact, pinned revision",
    )

    val siglipText = ModelArtifact(
        name = "SigLIP2 ViT-B/16 224 text encoder",
        relativePath = "models/siglip2/siglip2_text_224_wi8.tflite",
        runtime = "LiteRT weight-only INT8",
        required = true,
        packagedAssetPath = "siglip2_text_224_wi8.tflite",
        expectedBytes = 485_673_120L,
        sha256 = "e8f2ab209cb23df590565ae4c39cd89c97124d52fca767c705bb3fc2038fcc45",
        sourceLabel = "Generated from the pinned SigLIP2 text-only checkpoint; weight-only INT8",
    )

    val siglipTokenizer = ModelArtifact(
        name = "SigLIP2 text tokenizer",
        relativePath = "models/siglip2/tokenizer.json",
        runtime = "Rust tokenizers BPE",
        required = true,
        downloadUrl = "https://huggingface.co/m-toman/siglip2-base-patch16-224-text/resolve/7deedba28e0edb4fa9c22c889509447ddce7b23c/tokenizer.json?download=true",
        packagedAssetPath = "tokenizer.json",
        expectedBytes = 34_363_039L,
        sha256 = "cb9140fae3ac5122c972d37adf83e1248471a38147ad76f8215c8872c6fd8322",
        sourceLabel = "Same pinned SigLIP2 text-only repository revision",
    )

    val faceDetector = ModelArtifact(
        name = "YuNet face detector",
        relativePath = "models/face/yunet_fp16.tflite",
        runtime = "LiteRT",
        required = true,
        downloadUrl = "https://huggingface.co/litert-community/YuNet-Face-LiteRT/resolve/1931986764a60851e641956294b569bea5dd818d/yunet_fp16.tflite?download=true",
        expectedBytes = 256_228L,
        sha256 = "ced5f52bef6e76ad4a66d1055b2b404336ceafbae8eeac8fed6aa9c7b2e4d776",
        sourceLabel = "LiteRT Community pinned model revision",
    )

    val faceEmbedder = ModelArtifact(
        name = "FaceNet 512-D face embedding model",
        relativePath = "models/face/facenet_512.tflite",
        runtime = "LiteRT FLOAT32",
        required = true,
        downloadUrl = "https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/48493131e12c4c171c7ea531da476428ba5959c8/app/src/main/assets/facenet_512.tflite",
        packagedAssetPath = "facenet_512.tflite",
        expectedBytes = 24_394_880L,
        sha256 = "4ff97d406893bc4aae2d922c28287045d58fa9fbcbfeb731b975fd2debbf1a80",
        sourceLabel = "Pinned Apache-2.0 Android FaceNet TFLite artifact",
    )

    val gemmaE4B = ModelArtifact(
        name = "Gemma 4 E4B instruction",
        relativePath = "models/gemma-4-E4B-it.litertlm",
        runtime = "LiteRT-LM",
        required = true,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/f7ad3343bd6ebc9607f4dc3bc4f2398bd5749bc5/gemma-4-E4B-it.litertlm?download=true",
        expectedBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        sourceLabel = "LiteRT Community pinned model revision",
    )

    fun gemma(context: Context): ModelArtifact = gemmaE4B

    /** Removes model files from versions that offered the retired E2B option. */
    fun removeRetiredModels(context: Context) {
        val modelsDir = File(context.filesDir, "models")
        modelsDir.walkBottomUp()
            .filter { it.name.contains("E2B", ignoreCase = true) }
            .forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        File(modelsDir, "lfm2.5-vl").deleteRecursively()
        context.deleteDatabase("personal_context.db")
        context.deleteSharedPreferences("ask_galaxy_kv_index")
        context.deleteSharedPreferences("personal_context_settings")
        context.getSharedPreferences("ask_galaxy_model_selection", Context.MODE_PRIVATE)
            .edit().putString("gemma_variant", GemmaModelVariant.E4B.preferenceValue).apply()
    }

    fun all(context: Context): List<ModelArtifact> = listOf(
        siglipVision,
        siglipText,
        siglipTokenizer,
        faceDetector,
        faceEmbedder,
        gemma(context),
    )

    fun installedCount(context: Context): Int = all(context).count { it.isInstalled(context) }

    fun missingRequired(context: Context): List<ModelArtifact> =
        all(context).filter { it.required && !it.isInstalled(context) }

    fun missingSources(context: Context): List<ModelArtifact> =
        missingRequired(context).filterNot { it.hasDownloadSource() }
}
