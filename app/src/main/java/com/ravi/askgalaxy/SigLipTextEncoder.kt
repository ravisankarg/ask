package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Executes the aligned SigLIP2 text tower and returns a normalized 768-D vector. */
class SigLipTextEncoder private constructor(
    private val model: LiteRtModel,
    private val tokenizer: NativeTokenizer,
) : Closeable {

    private val inputTensor = model.inputTensor()
    private val outputTensor = model.outputTensor()
    private val inputBuffer = ByteBuffer
        .allocateDirect(inputTensor.numBytes())
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer
        .allocateDirect(outputTensor.numBytes())
        .order(ByteOrder.nativeOrder())
    private val queryCacheLock = Any()
    private val queryCache = LinkedHashMap<String, FloatArray>(QUERY_CACHE_CAPACITY, 0.75f, true)

    init {
        require(model.inputCount == 1) { "SigLIP text model must have one input" }
        require(model.outputCount >= 1) { "SigLIP text model has no output" }
        require(inputTensor.dataType() == DataType.INT32) {
            "SigLIP text IDs must be INT32, got ${inputTensor.dataType()}"
        }
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "SigLIP text output must be FLOAT32, got ${outputTensor.dataType()}"
        }
        require(inputTensor.shape().contentEquals(intArrayOf(1, TEXT_LENGTH))) {
            "Expected SigLIP text input [1,64], got ${inputTensor.shape().contentToString()}"
        }
        require(outputTensor.numElements() == EMBEDDING_DIMENSION) {
            "Expected SigLIP text output of 768 values, got ${outputTensor.numElements()}"
        }
    }

    fun encode(text: String): FloatArray {
        val cacheKey = text.trim()
        synchronized(queryCacheLock) {
            queryCache[cacheKey]?.let { return it }
        }
        val tokenIds = tokenizer.encode(text)
        require(tokenIds.size == TEXT_LENGTH) {
            "SigLIP tokenizer must return exactly $TEXT_LENGTH IDs, got ${tokenIds.size}"
        }
        inputBuffer.clear()
        tokenIds.forEach(inputBuffer::putInt)
        model.run(inputBuffer, outputBuffer)
        val embedding = FloatArray(EMBEDDING_DIMENSION) { outputBuffer.float }
        l2Normalize(embedding)
        synchronized(queryCacheLock) {
            queryCache[cacheKey] = embedding
            while (queryCache.size > QUERY_CACHE_CAPACITY) {
                queryCache.remove(queryCache.entries.first().key)
            }
        }
        return embedding
    }

    override fun close() {
        tokenizer.close()
        model.close()
    }

    companion object {
        const val TEXT_LENGTH = 64
        const val EMBEDDING_DIMENSION = 768
        private const val QUERY_CACHE_CAPACITY = 32

        fun open(context: Context): SigLipTextEncoder {
            val installer = ModelInstaller(context)
            check(installer.verifyInstalledArtifact(ModelCatalog.siglipText)) {
                "SigLIP text model failed integrity verification"
            }
            check(installer.verifyInstalledArtifact(ModelCatalog.siglipTokenizer)) {
                "SigLIP tokenizer failed integrity verification"
            }
            return SigLipTextEncoder(
                LiteRtModel.open(ModelCatalog.siglipText.file(context)),
                NativeTokenizer.open(ModelCatalog.siglipTokenizer.file(context)),
            )
        }

        /** Keeps the text tower warm while the search field is available. */
        fun preloadAsync(context: Context) {
            val appContext = context.applicationContext
            if (!ModelCatalog.siglipText.isInstalled(appContext) ||
                !ModelCatalog.siglipTokenizer.isInstalled(appContext) ||
                !preloadRequested.compareAndSet(false, true)
            ) return
            preloadExecutor.execute {
                runCatching { shared(appContext) }
                    .onSuccess { Log.i(TAG, "SigLIP text encoder warmed for search") }
                    .onFailure { error ->
                        preloadRequested.set(false)
                        Log.w(TAG, "SigLIP text encoder preload failed", error)
                    }
            }
        }

        fun shared(context: Context): SigLipTextEncoder {
            resident?.let { return it }
            return synchronized(residentLock) {
                resident ?: open(context).also { resident = it }
            }
        }

        /** Releases only the resident query text tower; the persisted index is untouched. */
        fun releaseResident() {
            synchronized(residentLock) {
                val current = resident
                resident = null
                preloadRequested.set(false)
                current?.close()
            }
        }

        private fun l2Normalize(values: FloatArray) {
            var sum = 0.0
            for (value in values) sum += value.toDouble() * value.toDouble()
            val norm = sqrt(sum).toFloat().coerceAtLeast(1.0e-12f)
            for (index in values.indices) values[index] /= norm
        }

        private const val TAG = "AskGalaxySearch"
        private val residentLock = Any()
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        private val preloadRequested = AtomicBoolean(false)

        @Volatile
        private var resident: SigLipTextEncoder? = null
    }
}
