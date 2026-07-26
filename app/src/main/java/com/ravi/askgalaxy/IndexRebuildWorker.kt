package com.ravi.askgalaxy

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Foreground, resumable worker for one isolated derived index. */
class IndexRebuildWorker(
    appContext: android.content.Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val index = IsolatedIndex.entries.firstOrNull {
            it.wire == inputData.getString(IndexRebuildScheduler.KEY_INDEX)
        } ?: return@withContext Result.failure()
        if (!hasImagePermission()) return@withContext Result.success()

        try {
            val progressStore = IndexProgressStore(applicationContext)
            setForeground(
                PreparationNotifier.foregroundInfo(
                    applicationContext,
                    PreparationSnapshot(
                        phase = PreparationPhase.INDEXING,
                        message = "Rebuilding ${index.displayName} index…",
                    ),
                ),
            )
            GalleryDatabase(applicationContext).use { database ->
                GallerySemanticIndexer(applicationContext, database).use { semantic ->
                    when (index) {
                        IsolatedIndex.VISUAL -> {
                            check(ModelCatalog.siglipVision.isInstalled(applicationContext)) {
                                "Install ${ModelCatalog.siglipVision.relativePath} before visual indexing"
                            }
                            semantic.reindexVisualsBlocking { update ->
                                progressStore.update(
                                    IndexProgressStage.VISUAL,
                                    update.completed.toLong(),
                                    update.total.toLong(),
                                    completed = update.total > 0 && update.completed + update.skipped >= update.total,
                                )
                                publish(update.completed, update.total, "Rebuilding visual search: ${update.completed}/${update.total}")
                            }
                        }
                        IsolatedIndex.FACE -> {
                            check(ModelCatalog.faceDetector.isInstalled(applicationContext)) {
                                "Install ${ModelCatalog.faceDetector.relativePath} before face indexing"
                            }
                            check(ModelCatalog.faceEmbedder.isInstalled(applicationContext)) {
                                "Install ${ModelCatalog.faceEmbedder.relativePath} before face indexing"
                            }
                            semantic.reindexFacesBlocking { update ->
                                val current = if (update.stage == EmbeddingStage.FACE) update.faceCompleted else update.episodeCount
                                val total = if (update.stage == EmbeddingStage.FACE) update.faceTotal else update.episodeCount
                                if (update.stage == EmbeddingStage.FACE) {
                                    progressStore.update(
                                        IndexProgressStage.FACE,
                                        current.toLong(),
                                        total.toLong(),
                                    )
                                } else {
                                    progressStore.update(
                                        IndexProgressStage.FACE,
                                        update.faceTotal.toLong(),
                                        update.faceTotal.toLong(),
                                        completed = true,
                                    )
                                    progressStore.update(
                                        IndexProgressStage.EPISODE,
                                        current.toLong(),
                                        total.toLong(),
                                        completed = true,
                                    )
                                }
                                publish(current, total, "Rebuilding faces and dependent episodes…")
                            }
                        }
                        IsolatedIndex.EPISODE -> {
                            val count = semantic.reindexEpisodesBlocking()
                            progressStore.update(
                                IndexProgressStage.EPISODE,
                                count.toLong(),
                                count.toLong(),
                                completed = true,
                            )
                            publish(count, count, "Rebuilt $count photo episodes")
                        }
                    }
                }
            }
            Result.success()
        } catch (error: Throwable) {
            setProgressAsync(
                workDataOf(
                    "index" to index.wire,
                    "error" to (error.message ?: error.javaClass.simpleName),
                ),
            )
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun publish(completed: Int, total: Int, message: String) {
        val safeTotal = total.coerceAtLeast(1)
        setProgressAsync(
            workDataOf(
                "index" to inputData.getString(IndexRebuildScheduler.KEY_INDEX).orEmpty(),
                "completed" to completed,
                "total" to total,
            ),
        )
        setForegroundAsync(
            PreparationNotifier.foregroundInfo(
                applicationContext,
                PreparationSnapshot(
                    phase = PreparationPhase.INDEXING,
                    message = message,
                    current = completed.toLong().coerceAtLeast(0L),
                    total = safeTotal.toLong(),
                ),
            ),
        )
    }

    private fun hasImagePermission(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return applicationContext.checkSelfPermission(permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
