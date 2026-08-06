package com.ravi.askgalaxy

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sequential all-source pass with independent, user-visible progress rows. */
class DocumentIndexWorker(
    appContext: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val reader = DocumentSourceReader(applicationContext)
            val progress = IndexProgressStore(applicationContext)
            if (DocumentSource.entries.none(reader::isAvailable)) {
                DocumentSource.entries.forEach { source ->
                    progress.update(
                        source.progressStage(),
                        0L,
                        0L,
                        completed = false,
                        error = "Permission not granted",
                        phase = "permission needed",
                    )
                }
                return@runCatching Result.success()
            }

            val embeddingGemma = ModelCatalog.embeddingGemma
            if (!embeddingGemma.isInstalled(applicationContext)) {
                progress.update(
                    IndexProgressStage.EMBEDDING_GEMMA,
                    embeddingGemma.partFile(applicationContext).length(),
                    embeddingGemma.expectedBytes,
                    phase = "starting download",
                )
                ModelInstaller(applicationContext).installArtifacts(listOf(embeddingGemma)) { install ->
                    progress.update(
                        IndexProgressStage.EMBEDDING_GEMMA,
                        install.bytesDownloaded,
                        install.bytesTotal,
                        phase = "Downloading ${install.artifact.name}",
                    )
                }
            }
            check(embeddingGemma.isInstalled(applicationContext)) {
                "EmbeddingGemma download did not complete"
            }
            val batches = listOf(
                DocumentSource.MESSAGES to reader.messages(),
                DocumentSource.CALENDAR to reader.calendar(),
                DocumentSource.FILES to reader.allFiles(),
                DocumentSource.CONTACTS to reader.contacts(),
                DocumentSource.CALL_LOGS to reader.callLogs(),
            ).mapNotNull { (source, records) ->
                val stage = source.progressStage()
                if (!reader.isAvailable(source)) {
                    progress.update(stage, 0L, 0L, completed = false, error = "Permission not granted", phase = "permission needed")
                    return@mapNotNull null
                }
                val chunks = records.toList()
                progress.update(stage, 0L, chunks.size.toLong(), completed = chunks.isEmpty(), error = "", phase = "queued")
                source to chunks
            }
            val total = batches.sumOf { it.second.size }.toLong()
            val completedBySource = batches.associate { it.first to 0 }.toMutableMap()
            progress.update(IndexProgressStage.DOCUMENT, 0L, total, completed = total == 0L, phase = "starting")
            val index = DocumentVectorIndex(applicationContext)
            try {
                index.indexAll(batches.map { (source, chunks) -> source to chunks.asSequence() }) { source, current, sourceTotal ->
                    completedBySource[source] = current
                    progress.update(source.progressStage(), current.toLong(), sourceTotal.toLong(), phase = "indexing")
                    progress.update(
                        IndexProgressStage.DOCUMENT,
                        completedBySource.values.sum().toLong(),
                        total,
                        phase = source.wire,
                    )
                }
            } finally {
                index.close()
            }
            progress.update(IndexProgressStage.DOCUMENT, total, total, completed = true, phase = "complete")
            batches.forEach { (source, chunks) ->
                progress.update(source.progressStage(), chunks.size.toLong(), chunks.size.toLong(), completed = true, phase = "complete")
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

private fun DocumentSource.progressStage(): IndexProgressStage = when (this) {
    DocumentSource.MESSAGES -> IndexProgressStage.DOCUMENT_MESSAGES
    DocumentSource.CALENDAR -> IndexProgressStage.DOCUMENT_CALENDAR
    DocumentSource.FILES -> IndexProgressStage.DOCUMENT_FILES
    DocumentSource.CONTACTS -> IndexProgressStage.DOCUMENT_CONTACTS
    DocumentSource.CALL_LOGS -> IndexProgressStage.DOCUMENT_CALL_LOGS
}
