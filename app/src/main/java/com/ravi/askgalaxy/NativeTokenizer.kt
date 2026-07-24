package com.ravi.askgalaxy

import java.io.Closeable
import java.io.File

/** Exact BPE tokenizer bridge for the SigLIP2 256k-vocabulary tokenizer.json. */
class NativeTokenizer private constructor(
    private var nativeHandle: Long,
) : Closeable {

    fun encode(text: String): IntArray {
        check(nativeHandle != 0L) { "Tokenizer is closed" }
        return nativeEncode(nativeHandle, text)
            ?: error("Native SigLIP2 tokenizer returned no IDs")
    }

    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeClose(handle)
        }
    }

    companion object {
        init {
            System.loadLibrary("askgalaxy_native")
        }

        fun open(file: File): NativeTokenizer {
            check(file.isFile) { "Tokenizer is not installed: ${file.absolutePath}" }
            val handle = nativeOpen(file.absolutePath)
            check(handle != 0L) { "Could not open SigLIP2 tokenizer" }
            return NativeTokenizer(handle)
        }

        @JvmStatic
        private external fun nativeOpen(path: String): Long

        @JvmStatic
        private external fun nativeEncode(handle: Long, text: String): IntArray?

        @JvmStatic
        private external fun nativeClose(handle: Long)
    }
}
