package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

data class FaceIndexProgress(
    val completed: Int,
    val total: Int,
    val facesFound: Int,
    val skipped: Int,
)

/** Detects and embeds every pending gallery item before clustering. */
class FaceIndexer(
    context: Context,
    private val database: GalleryDatabase,
) : Closeable {
    private val appContext = context.applicationContext

    fun indexBlocking(onProgress: (FaceIndexProgress) -> Unit = {}): FaceIndexProgress {
        check(ModelCatalog.faceDetector.isInstalled(appContext)) {
            "Install ${ModelCatalog.faceDetector.relativePath} before face indexing"
        }
        check(ModelCatalog.faceEmbedder.isInstalled(appContext)) {
            "Install ${ModelCatalog.faceEmbedder.relativePath} before face indexing"
        }
        val pending = database.pendingFaceEmbeddings()
        if (pending.isEmpty()) return FaceIndexProgress(0, 0, 0, 0)

        val completed = AtomicInteger(0)
        val facesFound = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val next = AtomicInteger(0)
        val progressLock = Any()
        val databaseLock = Any()
        var lastReported = 0
        val workers = Executors.newFixedThreadPool(InferenceParallelism.workerCount())
        val futures = ArrayList<Future<*>>()
        try {
            repeat(InferenceParallelism.workerCount()) {
                futures += workers.submit {
                    YuNetFaceDetector.open(appContext).use { detector ->
                        FaceNetEncoder.open(appContext).use { encoder ->
                            MediaBitmapLoader(appContext).use { loader ->
                                while (true) {
                                    val index = next.getAndIncrement()
                                    if (index >= pending.size) break
                                    val media = pending[index]
                                    val bitmap = runCatching {
                                        loader.load(media, maxDimension = 1_024)
                                    }.getOrNull()
                                    val embeddings = ArrayList<FaceEmbeddingInput>()
                                    if (bitmap == null) {
                                        skipped.incrementAndGet()
                                    } else {
                                        try {
                                            val inputs = runCatching { detector.detect(bitmap) }.getOrElse {
                                                skipped.incrementAndGet()
                                                emptyList()
                                            }
                                            inputs.forEachIndexed { faceIndex, detection ->
                                                val crop = try {
                                                    FaceCropper.crop(bitmap, detection)
                                                } catch (error: Exception) {
                                                    Log.w(
                                                        TAG,
                                                        "Skipping face crop for ${media.mediaStoreId} face $faceIndex",
                                                        error,
                                                    )
                                                    null
                                                } ?: return@forEachIndexed
                                                try {
                                                    val embedding = try {
                                                        encoder.encode(crop)
                                                    } catch (error: Exception) {
                                                        Log.w(
                                                            TAG,
                                                            "Skipping face embedding for ${media.mediaStoreId} face $faceIndex",
                                                            error,
                                                        )
                                                        null
                                                    }
                                                    if (embedding == null || !embedding.isValid()) {
                                                        if (embedding != null) {
                                                            Log.w(
                                                                TAG,
                                                                "Skipping invalid face embedding for ${media.mediaStoreId} face $faceIndex",
                                                            )
                                                        }
                                                        return@forEachIndexed
                                                    }
                                                    embeddings += FaceEmbeddingInput(
                                                        faceIndex = faceIndex,
                                                        embedding = embedding,
                                                        detectionScore = detection.score,
                                                        box = FaceBox(
                                                            left = detection.box.left / bitmap.width.toFloat(),
                                                            top = detection.box.top / bitmap.height.toFloat(),
                                                            right = detection.box.right / bitmap.width.toFloat(),
                                                            bottom = detection.box.bottom / bitmap.height.toFloat(),
                                                        ),
                                                    )
                                                } finally {
                                                    crop.recycle()
                                                }
                                            }
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                    synchronized(databaseLock) {
                                        database.replaceFaceEmbeddings(media.mediaStoreId, embeddings)
                                        database.markFaceEmbeddingIndexed(media.mediaStoreId)
                                    }
                                    facesFound.addAndGet(embeddings.size)
                                    val done = completed.incrementAndGet()
                                    synchronized(progressLock) {
                                        lastReported = maxOf(lastReported, done)
                                        onProgress(
                                            FaceIndexProgress(
                                                lastReported,
                                                pending.size,
                                                facesFound.get(),
                                                skipped.get(),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            futures.forEach(Future<*>::get)
        } finally {
            workers.shutdownNow()
        }
        return FaceIndexProgress(completed.get(), pending.size, facesFound.get(), skipped.get())
    }

    override fun close() = Unit

    companion object {
        private const val TAG = "AskGalaxyFaceIndex"

        private fun FloatArray.isValid(): Boolean =
            size == FaceNetEncoder.EMBEDDING_DIMENSION && all { it.isFinite() }
    }
}
