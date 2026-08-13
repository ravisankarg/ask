package com.ravi.askgalaxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Owns preparation after the Activity disappears. WorkManager persists and
 * restarts this foreground worker; model .part files and SQLite flags make each
 * phase resumable after process death, screen-off, or a network interruption.
 */
class PreparationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = PreparationStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val wakeLock = acquireWakeLock()
        try {
            runPreparation()
        } catch (error: IOException) {
            val snapshot = store.read().copy(
                phase = PreparationPhase.RETRYING,
                message = "Model download paused: ${error.message ?: "I/O error"}. Ask Galaxy will retry automatically.",
                error = error.message.orEmpty(),
            )
            store.write(snapshot)
            setProgress(workDataOf("phase" to snapshot.phase.wire, "message" to snapshot.message))
            Result.retry()
        } catch (error: Throwable) {
            val snapshot = store.read().copy(
                phase = PreparationPhase.FAILED,
                message = "Preparation paused: ${error.message ?: error.javaClass.simpleName}",
                error = error.stackTraceToString().take(2_000),
            )
            store.write(snapshot)
            setProgress(workDataOf("phase" to snapshot.phase.wire, "message" to snapshot.message))
            Result.failure()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun runPreparation(): Result {
        val indexProgressStore = IndexProgressStore(applicationContext)
        val previousPreparation = store.read()
        val wasAlreadyPrepared = previousPreparation.isPrepared
        var snapshot = previousPreparation.copy(
            phase = PreparationPhase.DOWNLOADING,
            message = "Installing on-device models in the background…",
            error = "",
        )
        store.write(snapshot)
        setForeground(PreparationNotifier.foregroundInfo(applicationContext, snapshot))

        val selectedGemma = ModelCatalog.gemma(applicationContext)
        val indexingModels = ModelCatalog.all(applicationContext)
            .filter { it.required && it != selectedGemma }
        val installer = ModelInstaller(applicationContext)
        val report = installer.installArtifacts(indexingModels, HuggingFaceSession.cookie()) { progress ->
            snapshot = snapshot.copy(
                phase = PreparationPhase.DOWNLOADING,
                message = "Installing indexing model ${progress.artifact.name}…",
                current = progress.bytesDownloaded,
                total = progress.bytesTotal,
                modelCurrent = progress.artifactIndex.toLong() - 1L,
                modelTotal = progress.artifactTotal.toLong(),
                bytesCurrent = progress.bytesDownloaded,
                bytesTotal = progress.bytesTotal,
            )
            store.write(snapshot)
            setForegroundAsync(PreparationNotifier.foregroundInfo(applicationContext, snapshot))
        }
        HuggingFaceSession.clear()

        if (report.missingSources.isNotEmpty()) {
            val names = report.missingSources.joinToString { it.name }
            snapshot = snapshot.copy(
                phase = PreparationPhase.WAITING_FOR_MODELS,
                message = "Waiting for model packages: $names",
                current = 0L,
                total = 0L,
            )
            store.write(snapshot)
            setForegroundAsync(PreparationNotifier.foregroundInfo(applicationContext, snapshot))
            return Result.success()
        }

        if (!hasGalleryPermission()) {
            DocumentIndexScheduler.enqueue(applicationContext)
            snapshot = snapshot.copy(
                phase = PreparationPhase.WAITING_FOR_PERMISSION,
                message = "Gallery permission is pending; document indexing will continue for enabled sources.",
                current = 0L,
                total = 0L,
            )
            store.write(snapshot)
            return Result.success()
        }

        val indexer = GalleryIndexer(applicationContext)
        try {
            snapshot = snapshot.copy(
                phase = PreparationPhase.SCANNING,
                message = "Scanning your gallery privately on this phone…",
                current = 0L,
                total = 0L,
            )
            store.write(snapshot)
            setForeground(PreparationNotifier.foregroundInfo(applicationContext, snapshot))
            val scanned = indexer.scanBlocking()
            snapshot = snapshot.copy(
                phase = PreparationPhase.INDEXING,
                message = "Indexing $scanned gallery items…",
                current = 0L,
                total = 0L,
                indexCurrent = 0L,
                indexTotal = 0L,
            )
            store.write(snapshot)

            var lastPublishedStage: EmbeddingStage? = null
            var lastPublishedAtMs = 0L
            val progress = indexer.indexEmbeddingsBlocking { update ->
                when (update.stage) {
                    EmbeddingStage.LOCATION -> indexProgressStore.update(
                        IndexProgressStage.LOCATION,
                        update.locationCompleted.toLong(),
                        update.locationTotal.toLong(),
                    )
                    EmbeddingStage.IMAGE -> indexProgressStore.update(
                        IndexProgressStage.VISUAL,
                        update.completed.toLong(),
                        update.total.toLong(),
                    )
                    EmbeddingStage.OCR -> indexProgressStore.update(
                        IndexProgressStage.OCR,
                        update.ocrCompleted.toLong(),
                        update.ocrTotal.toLong(),
                    )
                    EmbeddingStage.FACE -> indexProgressStore.update(
                        IndexProgressStage.FACE,
                        update.faceCompleted.toLong(),
                        update.faceTotal.toLong(),
                    )
                    EmbeddingStage.CLUSTERING -> if (update.clusteringComplete) {
                        indexProgressStore.update(
                            IndexProgressStage.FACE,
                            update.faceTotal.toLong(),
                            update.faceTotal.toLong(),
                            completed = true,
                        )
                    }
                    EmbeddingStage.EPISODES -> indexProgressStore.update(
                        IndexProgressStage.EPISODE,
                        update.episodeCount.toLong(),
                        update.episodeCount.toLong(),
                        completed = update.episodeIndexComplete,
                    )
                }
                val message = when (update.stage) {
                    EmbeddingStage.LOCATION ->
                        "Resolving photo locations: ${update.locationCompleted}/${update.locationTotal}"
                    EmbeddingStage.IMAGE -> "Indexing gallery visuals: ${update.completed}/${update.total}"
                    EmbeddingStage.OCR -> "Reading text in photos: ${update.ocrCompleted}/${update.ocrTotal}"
                    EmbeddingStage.FACE -> "Finding faces: ${update.faceCompleted}/${update.faceTotal}"
                    EmbeddingStage.CLUSTERING -> if (update.clusteringComplete) {
                        "Found ${update.clusterCount} private face groups. Preparing the complete set for review."
                    } else {
                        "Clustering face embeddings into anonymous groups…"
                    }
                    EmbeddingStage.EPISODES -> if (update.episodeIndexComplete) {
                        "Built ${update.episodeCount} private photo episodes for faster context selection."
                    } else {
                        "Grouping photos into private episodes…"
                    }
                }
                val progressCurrent = when (update.stage) {
                    EmbeddingStage.LOCATION -> update.locationCompleted.toLong()
                    EmbeddingStage.IMAGE -> update.completed.toLong()
                    EmbeddingStage.OCR -> update.ocrCompleted.toLong()
                    EmbeddingStage.FACE -> update.faceCompleted.toLong()
                    EmbeddingStage.CLUSTERING -> if (update.clusteringComplete) {
                        update.faceTotal.toLong()
                    } else {
                        0L
                    }
                    EmbeddingStage.EPISODES -> if (update.episodeIndexComplete) {
                        update.episodeCount.toLong()
                    } else {
                        0L
                    }
                }
                val progressTotal = when (update.stage) {
                    EmbeddingStage.LOCATION -> update.locationTotal.toLong()
                    EmbeddingStage.IMAGE -> update.total.toLong()
                    EmbeddingStage.OCR -> update.ocrTotal.toLong()
                    EmbeddingStage.FACE -> update.faceTotal.toLong()
                    EmbeddingStage.CLUSTERING -> if (update.clusteringComplete) {
                        update.faceTotal.toLong()
                    } else {
                        0L
                    }
                    EmbeddingStage.EPISODES -> if (update.episodeIndexComplete) {
                        update.episodeCount.toLong()
                    } else {
                        0L
                    }
                }
                val nowMs = SystemClock.elapsedRealtime()
                val stageChanged = update.stage != lastPublishedStage
                val stageComplete = progressTotal > 0L && progressCurrent >= progressTotal
                if (
                    stageChanged ||
                    stageComplete ||
                    nowMs - lastPublishedAtMs >= PROGRESS_PUBLICATION_INTERVAL_MS
                ) {
                    snapshot = snapshot.copy(
                        phase = PreparationPhase.INDEXING,
                        message = message,
                        current = progressCurrent,
                        total = progressTotal,
                        indexCurrent = progressCurrent,
                        indexTotal = progressTotal,
                    )
                    store.write(snapshot)
                    setForegroundAsync(
                        PreparationNotifier.foregroundInfo(applicationContext, snapshot),
                    )
                    lastPublishedStage = update.stage
                    lastPublishedAtMs = nowMs
                }
            }
            val finalPhase = if (progress.needsFaceTags) {
                PreparationPhase.WAITING_FOR_FACE_TAGS
            } else {
                PreparationPhase.READY
            }
            indexProgressStore.update(
                IndexProgressStage.VISUAL,
                progress.completed.toLong(),
                progress.total.toLong(),
                completed = true,
            )
            indexProgressStore.update(
                IndexProgressStage.OCR,
                progress.ocrCompleted.toLong(),
                progress.ocrTotal.toLong(),
                completed = true,
            )
            indexProgressStore.update(
                IndexProgressStage.FACE,
                progress.faceTotal.toLong(),
                progress.faceTotal.toLong(),
                completed = true,
            )
            indexProgressStore.update(
                IndexProgressStage.EPISODE,
                progress.episodeCount.toLong(),
                progress.episodeCount.toLong(),
                completed = true,
            )
            snapshot = snapshot.copy(
                phase = finalPhase,
                message = if (progress.needsFaceTags) {
                    "Face groups are ready. Review and merge the complete set, then name the people you know."
                } else {
                    "Ready for private gallery search."
                },
                current = progress.completed.toLong(),
                total = progress.total.toLong(),
                indexCurrent = progress.completed.toLong(),
                indexTotal = progress.total.toLong(),
            )
            store.write(snapshot)
            setForeground(PreparationNotifier.foregroundInfo(applicationContext, snapshot))
            DocumentIndexScheduler.enqueue(applicationContext)
            return Result.success()
        } finally {
            indexer.close()
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock {
        val manager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AskGalaxy:preparation",
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun hasGalleryPermission(): Boolean {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all {
            applicationContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private companion object {
        const val PROGRESS_PUBLICATION_INTERVAL_MS = 750L
        const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
    }
}
