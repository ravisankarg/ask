package com.ravi.askgalaxy

/** CPU-only JNI bridge for the public EmbeddingGemma GGUF document encoder. */
object EmbeddingNative {
    init { System.loadLibrary("askgalaxy_embedding") }

    external fun open(modelPath: String): Boolean
    external fun embed(text: String): FloatArray?
    external fun embedBatch(texts: Array<String>): FloatArray?
    external fun close()
}
