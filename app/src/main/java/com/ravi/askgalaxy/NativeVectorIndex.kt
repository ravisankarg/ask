package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin boundary for the Rust turbovec IdMapIndex.
 *
 * The model produces a float embedding; Rust performs the 4-bit TurboQuant
 * storage and native SIMD search. No Python, Torch, or vector database runs in
 * the APK.
 */
class NativeVectorIndex private constructor(
    private var nativeHandle: Long,
) : Closeable {

    val isOpen: Boolean
        get() = nativeHandle != 0L

    val size: Long
        get() = checkHandle().let(::nativeSize)

    val dimension: Int
        get() = checkHandle().let(::nativeDim)

    val bitWidth: Int
        get() = checkHandle().let(::nativeBitWidth)

    fun upsert(id: Long, embedding: FloatArray) {
        checkHandle()
        require(embedding.size == dimension) {
            "Expected $dimension coordinates, got ${embedding.size}"
        }
        check(nativeUpsert(nativeHandle, id, embedding)) {
            "Native TurboQuant upsert failed"
        }
    }

    /** Upserts multiple flattened vectors and persists the index once. */
    fun upsertBatch(ids: LongArray, flattenedEmbeddings: FloatArray) {
        checkHandle()
        require(ids.isNotEmpty()) { "Batch must contain at least one id" }
        require(flattenedEmbeddings.size == ids.size * dimension) {
            "Expected ${ids.size * dimension} coordinates, got ${flattenedEmbeddings.size}"
        }
        check(nativeUpsertBatch(nativeHandle, ids, flattenedEmbeddings)) {
            "Native TurboQuant batch upsert failed"
        }
    }

    fun search(query: FloatArray, k: Int, allowlist: LongArray? = null): NativeSearchResult {
        checkHandle()
        require(query.size == dimension) {
            "Expected $dimension query coordinates, got ${query.size}"
        }
        require(k > 0) { "k must be positive" }
        return nativeSearch(nativeHandle, query, k, allowlist)
            ?: error("Native TurboQuant search failed")
    }

    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeClose(handle)
        }
    }

    private fun checkHandle(): Long {
        check(nativeHandle != 0L) { "Native index is closed" }
        return nativeHandle
    }

    companion object {
        init {
            System.loadLibrary("askgalaxy_native")
        }

        fun open(
            context: Context,
            fileName: String = "siglip2-768-4bit.tvim",
            dimension: Int = SIGLIP_DIMENSION,
            bitWidth: Int = TURBOQUANT_BIT_WIDTH,
        ): NativeVectorIndex {
            require(fileName.matches(Regex("[A-Za-z0-9._-]+"))) {
                "Index file name must be a simple file name"
            }
            val directory = File(context.filesDir, "indexes").apply { mkdirs() }
            val handle = nativeCreate(
                File(directory, fileName).absolutePath,
                dimension,
                bitWidth,
            )
            check(handle != 0L) { "Could not open native vector index" }
            return NativeVectorIndex(handle)
        }

        /**
         * Keeps the already-built TurboQuant search cache resident while the
         * search field is visible. Opening a .tvim file and preparing its
         * SIMD layout is setup work, not query work, so it must not happen on
         * every search.
         */
        fun preloadAsync(context: Context) {
            val appContext = context.applicationContext
            val file = indexFile(appContext)
            if (!file.isFile || file.length() == 0L ||
                !preloadRequested.compareAndSet(false, true)
            ) return
            preloadExecutor.execute {
                runCatching { shared(appContext) }
                    .onSuccess { index ->
                        Log.i(TAG, "TurboQuant gallery index warmed: ${index.size} vectors")
                    }
                    .onFailure { error ->
                        preloadRequested.set(false)
                        Log.w(TAG, "TurboQuant gallery index preload failed", error)
                    }
            }
        }

        fun shared(context: Context): NativeVectorIndex {
            resident?.let { return it }
            return synchronized(residentLock) {
                resident ?: open(context).also { resident = it }
            }
        }

        /** Releases only the resident native handle; the persisted index is retained. */
        fun releaseResident() {
            synchronized(residentLock) {
                val current = resident
                resident = null
                preloadRequested.set(false)
                current?.close()
            }
        }

        private fun indexFile(context: Context): File =
            File(context.filesDir, "indexes/siglip2-768-4bit.tvim")

        private const val SIGLIP_DIMENSION = 768
        private const val TURBOQUANT_BIT_WIDTH = 4
        private const val TAG = "AskGalaxySearch"
        private val residentLock = Any()
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        private val preloadRequested = AtomicBoolean(false)

        @Volatile
        private var resident: NativeVectorIndex? = null

        @JvmStatic
        private external fun nativeCreate(path: String, dimension: Int, bitWidth: Int): Long

        @JvmStatic
        private external fun nativeClose(handle: Long)

        @JvmStatic
        private external fun nativeSize(handle: Long): Long

        @JvmStatic
        private external fun nativeDim(handle: Long): Int

        @JvmStatic
        private external fun nativeBitWidth(handle: Long): Int

        @JvmStatic
        private external fun nativeUpsert(handle: Long, id: Long, values: FloatArray): Boolean

        @JvmStatic
        private external fun nativeUpsertBatch(
            handle: Long,
            ids: LongArray,
            flattenedValues: FloatArray,
        ): Boolean

        @JvmStatic
        private external fun nativeSearch(
            handle: Long,
            query: FloatArray,
            k: Int,
            allowlist: LongArray?,
        ): NativeSearchResult?

        @JvmStatic
        private external fun nativeSelectDiverse(
            handle: Long,
            candidateIds: LongArray,
            maxCount: Int,
        ): LongArray?
    }

    /** Selects representatives using the quantized vectors already in the index. */
    fun selectDiverse(candidateIds: LongArray, maxCount: Int): LongArray {
        checkHandle()
        require(maxCount > 0) { "maxCount must be positive" }
        return nativeSelectDiverse(nativeHandle, candidateIds, maxCount)
            ?: error("Native TurboQuant diversity selection failed")
    }
}
