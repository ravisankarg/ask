package com.ravi.askgalaxy

import android.content.Context
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

data class OcrIndexProgress(
    val completed: Int,
    val total: Int,
    val textFound: Int,
    val skipped: Int,
)

/** Extracts searchable text from every pending gallery item. */
class OcrIndexer(
    context: Context,
    private val database: GalleryDatabase,
) : Closeable {
    private val appContext = context.applicationContext

    fun indexBlocking(onProgress: (OcrIndexProgress) -> Unit = {}): OcrIndexProgress {
        val pending = database.pendingOcr()
        if (pending.isEmpty()) return OcrIndexProgress(0, 0, 0, 0)

        val completed = AtomicInteger(0)
        val textFound = AtomicInteger(0)
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
                    PpOcrEngine.open(appContext).use { ocr ->
                        MediaBitmapLoader(appContext).use { loader ->
                            while (true) {
                                val index = next.getAndIncrement()
                                if (index >= pending.size) break
                                val media = pending[index]
                                val bitmap = runCatching {
                                    loader.load(media, maxDimension = 1_024)
                                }.getOrNull()
                                var text = ""
                                if (bitmap == null) {
                                    skipped.incrementAndGet()
                                } else {
                                    try {
                                        text = runCatching { ocr.read(bitmap).text }.getOrElse {
                                            skipped.incrementAndGet()
                                            ""
                                        }
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                                if (text.isNotBlank()) textFound.incrementAndGet()
                                synchronized(databaseLock) {
                                    database.replaceOcrText(media.mediaStoreId, text)
                                    database.markOcrIndexed(media.mediaStoreId)
                                }
                                val done = completed.incrementAndGet()
                                synchronized(progressLock) {
                                    lastReported = maxOf(lastReported, done)
                                    onProgress(
                                        OcrIndexProgress(
                                            lastReported,
                                            pending.size,
                                            textFound.get(),
                                            skipped.get(),
                                        ),
                                    )
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
        return OcrIndexProgress(completed.get(), pending.size, textFound.get(), skipped.get())
    }

    override fun close() = Unit
}
