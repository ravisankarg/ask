package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    fun indexBlocking(
        shouldContinue: () -> Boolean = { true },
        onProgress: (OcrIndexProgress) -> Unit = {},
    ): OcrIndexProgress = RUN_LOCK.withLock {
        indexLocked(onProgress, shouldContinue)
    }

    private fun indexLocked(
        onProgress: (OcrIndexProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): OcrIndexProgress {
        val pending = database.pendingOcr(OcrIndexContract.SIGNATURE)
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
                    MlKitOcrEngine.open().use { ocr ->
                        OcrMediaReader(appContext).use { reader ->
                            while (true) {
                                if (
                                    Thread.currentThread().isInterrupted ||
                                    !shouldContinue()
                                ) {
                                    break
                                }
                                val index = next.getAndIncrement()
                                if (index >= pending.size) break
                                val media = pending[index]
                                val result = runCatching {
                                    reader.read(media, ocr)
                                }
                                val text = result.getOrNull()?.text
                                if (text == null) {
                                    // Do not turn a transient recognizer or
                                    // decoder failure into a permanently empty
                                    // OCR row. It remains pending for retry.
                                    skipped.incrementAndGet()
                                    Log.w(
                                        TAG,
                                        "Leaving OCR pending for media ${media.mediaStoreId}",
                                        result.exceptionOrNull(),
                                    )
                                } else {
                                    if (text.isNotBlank()) textFound.incrementAndGet()
                                    synchronized(databaseLock) {
                                        database.replaceOcrResult(
                                            mediaStoreId = media.mediaStoreId,
                                            mimeType = media.mimeType,
                                            text = text,
                                            signature = OcrIndexContract.SIGNATURE,
                                        )
                                    }
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

    private companion object {
        /** Prevents full preparation and OCR-only work from racing in-process. */
        val RUN_LOCK = ReentrantLock()
        const val TAG = "AskGalaxyOcrIndex"
    }
}
