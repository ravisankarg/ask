package com.ravi.askgalaxy

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Sequential all-source pass with independent, user-visible progress rows. */
class DocumentIndexWorker(
    appContext: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val progress = IndexProgressStore(applicationContext)
        DocumentIndexRuntimeGate.begin()
        var activeSource = DocumentSource.MESSAGES
        try {
            setForeground(
                PreparationNotifier.foregroundInfo(
                    applicationContext,
                    PreparationSnapshot(
                        phase = PreparationPhase.DOWNLOADING,
                        message = "Preparing personal sources…",
                    ),
                ),
            )
            runCatching {
                val reader = DocumentSourceReader(applicationContext)
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

            val embeddingArtifacts = listOf(ModelCatalog.embeddingGemma, ModelCatalog.embeddingGemmaTokenizer)
            if (embeddingArtifacts.any { !it.isInstalled(applicationContext) }) {
                val hfCookie = HuggingFaceSession.cookie()
                progress.update(
                    IndexProgressStage.EMBEDDING_GEMMA,
                    embeddingArtifacts.sumOf { it.partFile(applicationContext).length() },
                    embeddingArtifacts.sumOf { it.expectedBytes },
                    phase = "starting download",
                )
                var lastDownloadSnapshot = PreparationSnapshot(
                    phase = PreparationPhase.DOWNLOADING,
                    message = "Downloading EmbeddingGemma…",
                    bytesTotal = embeddingArtifacts.sumOf { it.expectedBytes },
                )
                setForeground(PreparationNotifier.foregroundInfo(applicationContext, lastDownloadSnapshot))
                ModelInstaller(applicationContext).installArtifacts(embeddingArtifacts, hfCookie) { install ->
                    val completedBeforeArtifact = embeddingArtifacts
                        .take((install.artifactIndex - 1).coerceAtLeast(0))
                        .sumOf { it.expectedBytes }
                    val aggregateCurrent = completedBeforeArtifact + install.bytesDownloaded
                    val aggregateTotal = embeddingArtifacts.sumOf { it.expectedBytes }
                    lastDownloadSnapshot = lastDownloadSnapshot.copy(
                        message = "Downloading ${install.artifact.name}…",
                        bytesCurrent = aggregateCurrent,
                        bytesTotal = aggregateTotal,
                    )
                    progress.update(
                        IndexProgressStage.EMBEDDING_GEMMA,
                        aggregateCurrent,
                        aggregateTotal,
                        phase = "Downloading ${install.artifact.name}",
                    )
                    setForegroundAsync(PreparationNotifier.foregroundInfo(applicationContext, lastDownloadSnapshot))
                }
                HuggingFaceSession.clear()
                progress.update(
                    IndexProgressStage.EMBEDDING_GEMMA,
                    embeddingArtifacts.sumOf { it.expectedBytes },
                    embeddingArtifacts.sumOf { it.expectedBytes },
                    completed = true,
                    phase = "Installed",
                )
            }
            check(embeddingArtifacts.all { it.isInstalled(applicationContext) }) {
                "LiteRT EmbeddingGemma download did not complete"
            }
            val sourceReaders: List<Pair<DocumentSource, suspend () -> Sequence<DocumentChunk>>> = listOf(
                DocumentSource.FILES to { reader.allFiles() },
                DocumentSource.MESSAGES to { reader.messages() },
                DocumentSource.CALENDAR to { reader.calendar() },
                DocumentSource.CONTACTS to { reader.contacts() },
                DocumentSource.CALL_LOGS to { reader.callLogs() },
            )
            var documentCurrent = 0L
            var documentTotal = 0L
            progress.update(IndexProgressStage.DOCUMENT, 0L, 0L, completed = false, phase = "starting")
            // Clear stale errors from an interrupted/older worker before the
            // current source pass starts. In particular, an old LiteRT setup
            // failure must not keep forcing a restart on every app launch.
            DocumentSource.entries.forEach { source ->
                progress.update(source.progressStage(), 0L, 0L, completed = false, error = "", phase = "queued")
            }
            val index = DocumentVectorIndex(applicationContext)
            try {
                sourceReaders.forEach { (source, read) ->
                    activeSource = source
                    val stage = source.progressStage()
                    if (!reader.isAvailable(source)) {
                        progress.update(stage, 0L, 0L, completed = false, error = "Permission not granted", phase = "permission needed")
                    } else {
                        val records = timedRead("${source.wire}_read") { read() }
                        val chunks = timedRead("${source.wire}_materialize") { records.toList() }
                        val fileCount = if (source == DocumentSource.FILES) reader.supportedFileCount else null
                        val queuedPhase = fileCount?.let { "queued ($it files)" } ?: "queued"
                        val indexingPhase = fileCount?.let { "indexing $it files" } ?: "indexing"
                        documentTotal += chunks.size
                        progress.update(stage, 0L, chunks.size.toLong(), completed = chunks.isEmpty(), error = "", phase = queuedPhase)
                        progress.update(IndexProgressStage.DOCUMENT, documentCurrent, documentTotal, phase = source.wire)
                        var lastReportedAt = System.currentTimeMillis()
                        index.index(source, chunks.asSequence()) { current, sourceTotal ->
                            val now = System.currentTimeMillis()
                            val isFinal = current >= sourceTotal
                            // Avoid a SharedPreferences write and UI refresh
                            // for every embedding. The screen already polls
                            // once per second, so one persisted report per
                            // second is sufficient and keeps indexing cooler.
                            if (isFinal || now - lastReportedAt >= PROGRESS_REPORT_INTERVAL_MS) {
                                progress.update(stage, current.toLong(), sourceTotal.toLong(), phase = indexingPhase)
                                progress.update(IndexProgressStage.DOCUMENT, documentCurrent + current, documentTotal, phase = source.wire)
                                lastReportedAt = now
                            }
                        }
                        documentCurrent += chunks.size
                    }
                }
            } finally {
                index.close()
            }
            progress.update(IndexProgressStage.DOCUMENT, documentCurrent, documentTotal, completed = true, phase = "complete")
            Result.success()
            }.getOrElse { error ->
                Log.e(TAG, "document indexing failed", error)
                val modelFailure = error is ModelAuthorizationRequiredException
                progress.update(
                    if (modelFailure) IndexProgressStage.EMBEDDING_GEMMA else activeSource.progressStage(),
                    0L,
                    0L,
                    completed = false,
                    error = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    phase = if (modelFailure) "authorization required" else "extraction failed",
                )
                // Authorization needs an explicit user action; resumable I/O
                // can be retried by WorkManager without losing the .part file.
                if (error is IOException && !modelFailure) Result.retry() else Result.failure()
            }
        } finally {
            DocumentIndexRuntimeGate.end()
        }
    }

    private suspend fun <T> timedRead(name: String, read: suspend () -> T): T {
        val started = System.currentTimeMillis()
        Log.i(TAG, "source_read_start=$name")
        return read().also { Log.i(TAG, "source_read_done=$name ms=${System.currentTimeMillis() - started}") }
    }

    private companion object {
        const val TAG = "AskGalaxyDocumentIndex"
        const val PROGRESS_REPORT_INTERVAL_MS = 1_000L
    }
}

internal fun DocumentSource.progressStage(): IndexProgressStage = when (this) {
    DocumentSource.MESSAGES -> IndexProgressStage.DOCUMENT_MESSAGES
    DocumentSource.CALENDAR -> IndexProgressStage.DOCUMENT_CALENDAR
    DocumentSource.FILES -> IndexProgressStage.DOCUMENT_FILES
    DocumentSource.CONTACTS -> IndexProgressStage.DOCUMENT_CONTACTS
    DocumentSource.CALL_LOGS -> IndexProgressStage.DOCUMENT_CALL_LOGS
}
