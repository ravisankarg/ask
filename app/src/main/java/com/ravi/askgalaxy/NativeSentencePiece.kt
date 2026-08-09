package com.ravi.askgalaxy

import java.io.Closeable
import java.io.File

/** SentencePiece bridge used by the fixed-shape EmbeddingGemma LiteRT graph. */
class NativeSentencePiece private constructor(
    private var nativeHandle: Long,
) : Closeable {
    fun encode(text: String, maxLength: Int = 512): IntArray {
        check(nativeHandle != 0L) { "SentencePiece tokenizer is closed" }
        return nativeEncode(nativeHandle, text, maxLength)
            ?: error("SentencePiece tokenizer returned no IDs")
    }

    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeClose(handle)
        }
    }

    companion object {
        init { System.loadLibrary("askgalaxy_native") }

        fun open(file: File): NativeSentencePiece {
            check(file.isFile) { "SentencePiece model is not installed: ${file.absolutePath}" }
            val handle = nativeOpen(file.absolutePath)
            check(handle != 0L) { "Could not open EmbeddingGemma SentencePiece model" }
            return NativeSentencePiece(handle)
        }

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeEncode(handle: Long, text: String, maxLength: Int): IntArray?
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
