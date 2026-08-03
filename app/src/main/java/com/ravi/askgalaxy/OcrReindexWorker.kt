package com.ravi.askgalaxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rebuilds only OCR text/content classification. It deliberately does not
 * scan MediaStore or invoke location, visual, face, cluster, or episode work.
 */
class OcrReindexWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val progressStore = IndexProgressStore(applicationContext)
        if (!hasImagePermission()) return@withContext Result.success()
        GalleryDatabase(applicationContext).use { database ->
            val initialPending = database.pendingOcrCount()
            if (initialPending == 0) return@withContext Result.success()

            publishForeground(0, initialPending, "Refreshing text search…")
            var lastPublishedAtMs = 0L
            OcrIndexer(applicationContext, database).use { indexer ->
                indexer.indexBlocking(
                    onProgress = { update ->
                        val now = SystemClock.elapsedRealtime()
                        if (
                            update.completed >= update.total ||
                            now - lastPublishedAtMs >= PROGRESS_PUBLICATION_INTERVAL_MS
                        ) {
                            progressStore.update(
                                IndexProgressStage.OCR,
                                update.completed.toLong(),
                                update.total.toLong(),
                                completed = update.total > 0 && update.completed >= update.total,
                            )
                            val message =
                                "Reading text in photos: ${update.completed}/${update.total}"
                            setProgressAsync(
                                workDataOf(
                                    KEY_COMPLETED to update.completed,
                                    KEY_TOTAL to update.total,
                                    KEY_TEXT_FOUND to update.textFound,
                                    KEY_SKIPPED to update.skipped,
                                    KEY_SIGNATURE to OcrIndexContract.SIGNATURE,
                                ),
                            )
                            setForegroundAsync(
                                foregroundInfo(update.completed, update.total, message),
                            )
                            lastPublishedAtMs = now
                        }
                    },
                    shouldContinue = { !isStopped },
                )
            }
            val remaining = database.pendingOcrCount()
            if (remaining == 0 && KvIndexPreferences.isEnabled(applicationContext)) {
                KvIndexScheduler.enqueue(applicationContext, replaceExisting = false)
            }
            if (remaining == 0) {
                progressStore.update(
                    IndexProgressStage.OCR,
                    initialPending.toLong(),
                    initialPending.toLong(),
                    completed = true,
                )
            }
            when {
                remaining == 0 -> Result.success(
                    workDataOf(
                        KEY_COMPLETED to initialPending,
                        KEY_TOTAL to initialPending,
                        KEY_SIGNATURE to OcrIndexContract.SIGNATURE,
                    ),
                )
                runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                else -> Result.failure(
                    workDataOf(
                        KEY_TOTAL to initialPending,
                        KEY_SKIPPED to remaining,
                        KEY_SIGNATURE to OcrIndexContract.SIGNATURE,
                    ),
                )
            }
        }
    }

    private suspend fun publishForeground(completed: Int, total: Int, message: String) {
        setForeground(foregroundInfo(completed, total, message))
    }

    private fun foregroundInfo(
        completed: Int,
        total: Int,
        message: String,
    ) = PreparationNotifier.foregroundInfo(
        applicationContext,
        PreparationSnapshot(
            phase = PreparationPhase.INDEXING,
            message = message,
            current = completed.toLong(),
            total = total.toLong(),
        ),
    )

    private fun hasImagePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return applicationContext.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val KEY_COMPLETED = "ocr_completed"
        const val KEY_TOTAL = "ocr_total"
        const val KEY_TEXT_FOUND = "ocr_text_found"
        const val KEY_SKIPPED = "ocr_skipped"
        const val KEY_SIGNATURE = "ocr_signature"
        private const val PROGRESS_PUBLICATION_INTERVAL_MS = 750L
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
