package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Manual, additive-only gallery and My Files update started from Settings. */
class IncrementalIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val progress = IndexProgressStore(applicationContext)
        DocumentIndexRuntimeGate.begin()
        var indexer: GalleryIndexer? = null
        try {
            progress.update(
                IndexProgressStage.INCREMENTAL_UPDATE,
                0L,
                0L,
                completed = false,
                error = "",
                phase = "Scanning for additions",
            )
            setForeground(
                PreparationNotifier.foregroundInfo(
                    applicationContext,
                    PreparationSnapshot(
                        phase = PreparationPhase.INDEXING,
                        message = "Checking for new photos, videos, and files…",
                    ),
                ),
            )

            indexer = GalleryIndexer(applicationContext)
            val newGalleryItems = indexer.scanNewMediaBlocking()
            val pendingGalleryItems = indexer.pendingIncrementalMediaCount()

            val reader = DocumentSourceReader(applicationContext)
            val allFileChunks = if (reader.isAvailable(DocumentSource.FILES)) {
                reader.allFiles().toList()
            } else {
                emptyList()
            }
            val pendingFileChunks = DocumentDatabase(applicationContext).use { database ->
                allFileChunks.filterNot(database::containsCurrentChunk)
            }
            val totalAdditions = newGalleryItems + pendingFileChunks.size.toLong()
            progress.update(
                IndexProgressStage.INCREMENTAL_UPDATE,
                0L,
                totalAdditions,
                completed = false,
                phase = if (pendingGalleryItems > 0) "Adding gallery records" else "Checking file chunks",
            )

            if (pendingGalleryItems > 0) {
                indexer.indexAdditionsBlocking { update ->
                    publishGalleryStage(progress, update)
                }
            }
            var completed = newGalleryItems
            progress.update(
                IndexProgressStage.INCREMENTAL_UPDATE,
                completed,
                totalAdditions,
                completed = false,
                phase = "Adding My Files chunks",
            )

            if (pendingFileChunks.isNotEmpty()) {
                check(ModelCatalog.embeddingGemma.isInstalled(applicationContext) &&
                    ModelCatalog.embeddingGemmaTokenizer.isInstalled(applicationContext)
                ) { "EmbeddingGemma must be installed before updating My Files" }
                DocumentVectorIndex(applicationContext).use { documents ->
                    documents.index(DocumentSource.FILES, pendingFileChunks.asSequence()) { current, _ ->
                        completed = newGalleryItems + current.toLong()
                        progress.update(
                            IndexProgressStage.INCREMENTAL_UPDATE,
                            completed,
                            totalAdditions,
                            completed = false,
                            phase = "Adding My Files chunks",
                        )
                        progress.update(
                            IndexProgressStage.DOCUMENT_FILES,
                            current.toLong(),
                            pendingFileChunks.size.toLong(),
                            completed = current >= pendingFileChunks.size,
                            phase = "incremental update",
                        )
                    }
                }
            }

            val fileAccessNote = if (reader.isAvailable(DocumentSource.FILES)) {
                "${pendingFileChunks.size} file chunks"
            } else {
                "files skipped: storage access not granted"
            }
            progress.update(
                IndexProgressStage.INCREMENTAL_UPDATE,
                totalAdditions,
                totalAdditions,
                completed = true,
                error = "",
                phase = "Added $newGalleryItems gallery items and $fileAccessNote; removed 0",
            )
            Result.success()
        } catch (error: Throwable) {
            Log.e(TAG, "Incremental index update paused", error)
            val previous = progress.read(IndexProgressStage.INCREMENTAL_UPDATE)
            progress.update(
                IndexProgressStage.INCREMENTAL_UPDATE,
                previous.current,
                previous.total,
                completed = false,
                error = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                phase = "Paused safely; existing indexes preserved",
            )
            Result.failure()
        } finally {
            indexer?.close()
            DocumentIndexRuntimeGate.end()
        }
    }

    private fun publishGalleryStage(store: IndexProgressStore, update: EmbeddingProgress) {
        val stage = when (update.stage) {
            EmbeddingStage.LOCATION -> IndexProgressStage.LOCATION
            EmbeddingStage.IMAGE -> IndexProgressStage.VISUAL
            EmbeddingStage.OCR -> IndexProgressStage.OCR
            EmbeddingStage.FACE, EmbeddingStage.CLUSTERING -> IndexProgressStage.FACE
            EmbeddingStage.EPISODES -> IndexProgressStage.EPISODE
        }
        val current: Long
        val total: Long
        when (update.stage) {
            EmbeddingStage.LOCATION -> {
                current = update.locationCompleted.toLong()
                total = update.locationTotal.toLong()
            }
            EmbeddingStage.IMAGE -> {
                current = update.completed.toLong()
                total = update.total.toLong()
            }
            EmbeddingStage.OCR -> {
                current = update.ocrCompleted.toLong()
                total = update.ocrTotal.toLong()
            }
            EmbeddingStage.FACE, EmbeddingStage.CLUSTERING -> {
                current = update.faceCompleted.toLong()
                total = update.faceTotal.toLong()
            }
            EmbeddingStage.EPISODES -> {
                current = update.episodeCount.toLong()
                total = update.episodeCount.toLong()
            }
        }
        store.update(
            stage,
            current,
            total,
            completed = when (update.stage) {
                EmbeddingStage.CLUSTERING -> update.clusteringComplete
                EmbeddingStage.EPISODES -> update.episodeIndexComplete
                else -> total == 0L || current >= total
            },
            phase = "incremental update",
        )
    }

    private companion object {
        const val TAG = "AskGalaxyIncremental"
    }
}

object IncrementalIndexScheduler {
    private const val WORK = "ask_galaxy_incremental_index_update"

    fun enqueue(context: Context) {
        val appContext = context.applicationContext
        PreparationNotifier.createChannel(appContext)
        IndexProgressStore(appContext).reset(IndexProgressStage.INCREMENTAL_UPDATE)
        val request = OneTimeWorkRequestBuilder<IncrementalIndexWorker>()
            .addTag(WORK)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
