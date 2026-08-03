package com.ravi.askgalaxy

/** JNI boundary for local LFM2.5-VL GGUF extraction. */
object LfmKvNative {
    init { System.loadLibrary("askgalaxy_lfm") }

    external fun open(modelPath: String, projectorPath: String): Boolean

    external fun extract(
        imageBytes: ByteArray,
        instruction: String,
    ): String?

    external fun close()
}
