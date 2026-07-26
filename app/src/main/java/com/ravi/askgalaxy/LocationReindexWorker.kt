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
 * Rebuilds only photo GPS and readable place names.
 *
 * No MediaStore scan, OCR, visual embedding, face, cluster, or episode table
 * is touched. Images remain pending when precise-photo-location consent is
 * unavailable; redacted EXIF is never committed as "no GPS".
 */
class LocationReindexWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val progressStore = IndexProgressStore(applicationContext)
        if (!hasImagePermission() || !MediaLocationAccess.hasPermission(applicationContext)) {
            return@withContext Result.success(
                workDataOf(KEY_PERMISSION_REQUIRED to true),
            )
        }
        GalleryDatabase(applicationContext).use { database ->
            val initialPending = database.pendingLocationCount()
            if (initialPending == 0) return@withContext Result.success()

            setForeground(foregroundInfo(0, initialPending, "Refreshing photo locations…"))
            var lastPublishedAtMs = 0L
            val progress = LocationIndexer(applicationContext, database).indexBlocking(
                onProgress = { update ->
                    val now = SystemClock.elapsedRealtime()
                    if (
                        update.completed >= update.total ||
                        now - lastPublishedAtMs >= PROGRESS_PUBLICATION_INTERVAL_MS
                    ) {
                        progressStore.update(
                            IndexProgressStage.LOCATION,
                            update.completed.toLong(),
                            update.total.toLong(),
                            completed = update.total > 0 && update.completed >= update.total,
                        )
                        val message =
                            "Reading photo locations: ${update.completed}/${update.total}"
                        setProgressAsync(
                            workDataOf(
                                KEY_COMPLETED to update.completed,
                                KEY_TOTAL to update.total,
                                KEY_WITH_GPS to update.withGps,
                                KEY_RESOLVED to update.resolved,
                                KEY_RETRYABLE to update.retryable,
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
            val remaining = database.pendingLocationCount()
            if (remaining == 0) {
                progressStore.update(
                    IndexProgressStage.LOCATION,
                    progress.completed.toLong(),
                    progress.total.toLong(),
                    completed = true,
                )
            }
            when {
                remaining == 0 -> Result.success(
                    workDataOf(
                        KEY_COMPLETED to progress.completed,
                        KEY_TOTAL to progress.total,
                        KEY_WITH_GPS to progress.withGps,
                        KEY_RESOLVED to progress.resolved,
                    ),
                )
                isStopped -> Result.failure()
                runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                else -> Result.failure(
                    workDataOf(
                        KEY_TOTAL to initialPending,
                        KEY_RETRYABLE to remaining,
                    ),
                )
            }
        }
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
        const val KEY_COMPLETED = "location_completed"
        const val KEY_TOTAL = "location_total"
        const val KEY_WITH_GPS = "location_with_gps"
        const val KEY_RESOLVED = "location_resolved"
        const val KEY_RETRYABLE = "location_retryable"
        const val KEY_PERMISSION_REQUIRED = "location_permission_required"
        private const val PROGRESS_PUBLICATION_INTERVAL_MS = 750L
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
