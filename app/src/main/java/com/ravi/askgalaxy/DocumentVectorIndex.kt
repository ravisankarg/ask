package com.ravi.askgalaxy

import android.content.Context
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** EmbeddingGemma + one TurboQuant file per non-gallery source. */
class DocumentVectorIndex(private val context: Context) : Closeable {
    private val database = DocumentDatabase(context)
    private val indexes = DocumentSource.entries.associateWith {
        NativeVectorIndex.open(context, it.indexFile, DocumentDatabase.EMBEDDING_DIMENSION)
    }
    private val model = ModelCatalog.embeddingGemma.file(context)
    private val lock = ReentrantLock()

    fun index(source: DocumentSource, records: Sequence<DocumentChunk>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        indexAll(listOf(source to records)) { _, current, total -> onProgress(current, total) }
    }

    fun indexAll(
        sources: List<Pair<DocumentSource, Sequence<DocumentChunk>>>,
        onProgress: (DocumentSource, Int, Int) -> Unit = { _, _, _ -> },
    ) {
        require(model.isFile) { "EmbeddingGemma is not installed" }
        lock.withLock {
            // Keep one initialized encoder resident for the complete
            // cross-source pass; reopening per file would destroy throughput.
            check(EmbeddingNative.open(model.absolutePath)) { "Could not open EmbeddingGemma" }
            try {
                sources.forEach { (source, records) ->
                    val all = records.toList()
                    val vectorIndex = indexes.getValue(source)
                    all.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                        val flattened = EmbeddingNative.embedBatch(batch.map { it.text }.toTypedArray())
                        if (flattened == null || flattened.size != batch.size * DocumentDatabase.EMBEDDING_DIMENSION) {
                            batch.forEach { chunk ->
                                val vector = EmbeddingNative.embed(chunk.text) ?: return@forEach
                                require(vector.size == DocumentDatabase.EMBEDDING_DIMENSION) {
                                    "EmbeddingGemma dimension changed: ${vector.size}"
                                }
                                vectorIndex.upsert(chunk.stableId, vector)
                                database.upsertChunk(chunk)
                            }
                        } else {
                            batch.forEachIndexed { index, chunk ->
                                val start = index * DocumentDatabase.EMBEDDING_DIMENSION
                                val vector = flattened.copyOfRange(start, start + DocumentDatabase.EMBEDDING_DIMENSION)
                                vectorIndex.upsert(chunk.stableId, vector)
                                database.upsertChunk(chunk)
                            }
                        }
                        val completedBefore = batchIndex * BATCH_SIZE
                        batch.indices.forEach { index ->
                            onProgress(source, completedBefore + index + 1, all.size)
                        }
                    }
                }
            } finally {
                EmbeddingNative.close()
            }
        }
    }

    fun search(query: String, sources: Set<DocumentSource> = DocumentSource.entries.toSet(), limitPerSource: Int = 16): List<DocumentMatch> {
        if (!model.isFile || query.isBlank()) return emptyList()
        return lock.withLock {
            if (!EmbeddingNative.open(model.absolutePath)) return@withLock emptyList()
            try {
                val vector = EmbeddingNative.embed(query) ?: return@withLock emptyList()
                sources.flatMap { source ->
                    val result = indexes.getValue(source).search(vector, limitPerSource)
                    val chunks = database.chunks(result.ids)
                    chunks.mapIndexed { index, chunk ->
                        val rank = index + 1
                        DocumentMatch(
                            chunk = chunk,
                            score = result.scores.getOrElse(index) { 0f },
                            rank = rank,
                            fusionScore = 1f / (RRF_K + rank.toFloat()),
                        )
                    }
                }.sortedByDescending { it.fusionScore }.take(limitPerSource * sources.size)
            } finally {
                EmbeddingNative.close()
            }
        }
    }

    override fun close() {
        indexes.values.forEach(Closeable::close)
        database.close()
    }

    private companion object {
        const val RRF_K = 60f
        const val BATCH_SIZE = 4
    }
}
