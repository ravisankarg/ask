package com.ravi.askgalaxy

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Resumably downloads Gemma while the independent gallery index is built. */
class GemmaDownloadWorker(
    appContext: android.content.Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val gemma = ModelCatalog.gemma(applicationContext)
        val progressStore = IndexProgressStore(applicationContext)
        try {
            var lastSnapshot = PreparationSnapshot(
                phase = PreparationPhase.DOWNLOADING,
                message = "Installing Gemma 4 in the background…",
                modelCurrent = 0L,
                modelTotal = 1L,
            )
            setForeground(PreparationNotifier.foregroundInfo(applicationContext, lastSnapshot))
            ModelInstaller(applicationContext).installArtifacts(listOf(gemma)) { progress ->
                progressStore.update(
                    stage = IndexProgressStage.MODELS,
                    current = progress.bytesDownloaded,
                    total = progress.bytesTotal,
                )
                lastSnapshot = PreparationSnapshot(
                    phase = PreparationPhase.DOWNLOADING,
                    message = "Installing ${progress.artifact.name}…",
                    current = progress.bytesDownloaded,
                    total = progress.bytesTotal,
                    modelCurrent = 1L,
                    modelTotal = 1L,
                    bytesCurrent = progress.bytesDownloaded,
                    bytesTotal = progress.bytesTotal,
                )
                setProgressAsync(
                    workDataOf(
                        "phase" to lastSnapshot.phase.wire,
                        "current" to progress.bytesDownloaded,
                        "total" to progress.bytesTotal,
                    ),
                )
                setForegroundAsync(PreparationNotifier.foregroundInfo(applicationContext, lastSnapshot))
            }
            progressStore.update(
                IndexProgressStage.MODELS,
                gemma.expectedBytes,
                gemma.expectedBytes,
                completed = true,
            )
            Result.success()
        } catch (error: IOException) {
            progressStore.update(
                IndexProgressStage.MODELS,
                gemma.partFile(applicationContext).length(),
                gemma.expectedBytes,
                error = error.message.orEmpty(),
            )
            setProgressAsync(
                workDataOf(
                    "phase" to PreparationPhase.RETRYING.wire,
                    "message" to (error.message ?: "I/O error"),
                ),
            )
            Result.retry()
        } catch (error: Throwable) {
            progressStore.update(
                IndexProgressStage.MODELS,
                gemma.partFile(applicationContext).length(),
                gemma.expectedBytes,
                error = error.message.orEmpty(),
            )
            setProgressAsync(
                workDataOf(
                    "phase" to PreparationPhase.FAILED.wire,
                    "message" to (error.message ?: error.javaClass.simpleName),
                ),
            )
            Result.failure()
        }
    }
}
