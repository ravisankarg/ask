package com.ravi.askgalaxy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Downloads LFM only after the explicit opt-in, then indexes OCR-bearing images only. */
class KvIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!KvIndexPreferences.isEnabled(applicationContext)) return@withContext Result.success()
        val progress = IndexProgressStore(applicationContext)
        // KV extraction owns the large-model budget. In particular, never
        // let a speculative Gemma 4 prefill compete during document work.
        KvIndexPreferences.setIndexing(applicationContext, true)
        GemmaRuntime.cancelWarmupsForKvIndex()
        // A completed Gemma warmup leaves its multi-gigabyte engine resident.
        // Releasing it here is essential before opening CPU LFM. This worker
        // already runs off the UI thread.
        GemmaRuntime.releaseResident()
        try {
            ModelInstaller(applicationContext).installArtifacts(ModelCatalog.lfmKvArtifacts()) { update ->
                progress.update(
                    IndexProgressStage.KV,
                    update.bytesDownloaded,
                    update.bytesTotal,
                )
            }
            if (!ModelCatalog.lfmKvInstalled(applicationContext)) return@withContext Result.retry()
            var retryNeeded = false
            GalleryDatabase(applicationContext).use { database ->
                val alreadyExtracted = database.indexedKvDocuments().size.toLong()
                val pendingFacts = database.pendingKvDocuments()
                val totalFacts = alreadyExtracted + pendingFacts.size.toLong()
                if (pendingFacts.isNotEmpty()) {
                    // Phase 1: keep only LFM resident until every document has
                    // committed its textual facts. Do not load SigLIP here.
                    LfmKvExtractor(applicationContext).use { extractor ->
                        progress.update(IndexProgressStage.KV, alreadyExtracted, totalFacts, phase = "facts")
                        var completedFacts = alreadyExtracted
                        for (media in pendingFacts) {
                            if (isStopped || !KvIndexPreferences.isEnabled(applicationContext)) {
                                retryNeeded = true
                                break
                            }
                            val facts = extractor.extract(media)
                            if (facts != null) {
                                database.replaceKvDocument(media.mediaStoreId, facts)
                                completedFacts += 1L
                                progress.update(
                                    IndexProgressStage.KV,
                                    completedFacts,
                                    totalFacts,
                                    phase = "facts",
                                )
                            } else {
                                // Keep the record pending and do not advance the
                                // UI. A failed decode or generation is not work
                                // that has been indexed.
                                retryNeeded = true
                                break
                            }
                        }
                    }
                }

                // The use block above deterministically releases LFM before
                // Phase 2 starts. SigLIP is then the only large model resident.
                if (retryNeeded) return@use
                if (!ModelCatalog.siglipText.isInstalled(applicationContext) ||
                    !ModelCatalog.siglipTokenizer.isInstalled(applicationContext)
                ) {
                    retryNeeded = true
                    return@use
                }
                val documents = database.indexedKvDocuments()
                if (documents.isEmpty()) {
                    progress.update(IndexProgressStage.KV, 0L, 0L, completed = true, phase = "vectors")
                    return@use
                }
                KvDocumentVectorIndex.clearPersisted(applicationContext)
                KvDocumentVectorIndex.open(applicationContext).use { vectors ->
                    val textEncoder = SigLipTextEncoder.shared(applicationContext)
                    progress.update(IndexProgressStage.KV, 0L, documents.size.toLong(), phase = "vectors")
                    for ((index, media) in documents.withIndex()) {
                        if (isStopped || !KvIndexPreferences.isEnabled(applicationContext)) {
                            retryNeeded = true
                            break
                        }
                        vectors.upsert(media.mediaStoreId, textEncoder.encode(media.kvText))
                        progress.update(
                            IndexProgressStage.KV,
                            (index + 1).toLong(),
                            documents.size.toLong(),
                            completed = index + 1 == documents.size,
                            phase = "vectors",
                        )
                    }
                }
            }
            if (retryNeeded) Result.retry() else Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Throwable) {
            progress.update(IndexProgressStage.KV, 0L, 0L, error = error.message.orEmpty())
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            SigLipTextEncoder.releaseResident()
            KvIndexPreferences.setIndexing(applicationContext, false)
        }
    }
}
