package com.ravi.askgalaxy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/** Warm, GPU-backed 512-token EmbeddingGemma encoder for document retrieval. */
class EmbeddingGemmaLiteRt private constructor(
    private val model: LiteRtCompiledEmbedding,
    private val tokenizer: NativeSentencePiece,
) : Closeable {
    private val runLock = ReentrantLock()

    fun encode(text: String): FloatArray {
        return runLock.withLock {
            val values = model.run(tokenizer.encode(text, MAX_TOKENS))
            require(values.size == DIMENSION) { "Expected 768-D EmbeddingGemma output, got ${values.size}" }
            var norm = 0.0
            values.forEach { norm += it.toDouble() * it.toDouble() }
            val scale = sqrt(norm).toFloat().coerceAtLeast(1.0e-12f)
            values.indices.forEach { values[it] /= scale }
            val normalizedNorm = sqrt(values.fold(0.0) { sum, value -> sum + value.toDouble() * value.toDouble() })
            check(normalizedNorm.isFinite() && kotlin.math.abs(normalizedNorm - 1.0) < 0.001) {
                "EmbeddingGemma normalization failed: norm=$normalizedNorm"
            }
            values
        }
    }

    override fun close() {
        tokenizer.close()
        model.close()
    }

    companion object {
        const val MAX_TOKENS = 512
        const val DIMENSION = 768
        private const val TAG = "AskGalaxyEmbedding"

        // Keep exactly one compiled GPU graph and one set of tensor buffers in
        // the app process. ThreadLocal allowed WorkManager/search threads to
        // create competing GPU models and intermittently fail buffer creation.
        private val residentLock = Any()
        @Volatile private var residentEncoder: EmbeddingGemmaLiteRt? = null

        fun open(context: Context): EmbeddingGemmaLiteRt {
            val appContext = context.applicationContext
            return EmbeddingGemmaLiteRt(
                LiteRtCompiledEmbedding.open(ModelCatalog.embeddingGemma.file(appContext)),
                NativeSentencePiece.open(ModelCatalog.embeddingGemmaTokenizer.file(appContext)),
            )
        }

        fun resident(context: Context): EmbeddingGemmaLiteRt =
            residentEncoder ?: synchronized(residentLock) {
                residentEncoder ?: open(context).also { residentEncoder = it }
            }

        fun releaseResident() {
            synchronized(residentLock) {
                residentEncoder?.close()
                residentEncoder = null
            }
        }
    }
}
