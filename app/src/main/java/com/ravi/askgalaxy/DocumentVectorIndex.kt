package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.EnumMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** EmbeddingGemma + one TurboQuant file per non-gallery source. */
class DocumentVectorIndex(private val context: Context) : Closeable {
    private val database = DocumentDatabase(context)
    // Open only the source indexes requested by this query. Opening all five
    // TurboQuant files on every search adds avoidable startup latency for
    // explicit PDF/messages/calendar searches.
    private val indexes = EnumMap<DocumentSource, NativeVectorIndex>(DocumentSource::class.java)
    private val model = ModelCatalog.embeddingGemma.file(context)
    private val tokenizer = ModelCatalog.embeddingGemmaTokenizer.file(context)
    private val encoder = EmbeddingGemmaLiteRt.resident(context)
    private val lock = ReentrantLock()
    private val sourceSearchExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_SOURCES)

    /** Opens only already-built private vector files and keeps them warm. */
    private fun preloadBuiltIndexes() {
        lock.withLock {
            DocumentSource.entries
                .filter { source ->
                    File(context.filesDir, "indexes/${source.indexFile}").run {
                        isFile && length() > 0L
                    }
                }
                .forEach { source -> indexFor(source) }
            Log.i(TAG, "Private search indexes warmed: ${indexes.keys.joinToString { it.wire }}")
        }
    }

    fun index(source: DocumentSource, records: Sequence<DocumentChunk>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        indexAll(listOf(source to records)) { _, current, total -> onProgress(current, total) }
    }

    fun indexAll(
        sources: List<Pair<DocumentSource, Sequence<DocumentChunk>>>,
        onProgress: (DocumentSource, Int, Int) -> Unit = { _, _, _ -> },
    ) {
        require(model.isFile && tokenizer.isFile) { "LiteRT EmbeddingGemma and SentencePiece are not installed" }
        lock.withLock {
            sources.forEach { (source, records) ->
                    val all = records.toList()
                    val vectorIndex = indexFor(source)
                    all.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                        val pending = batch.filterNot(database::containsCurrentChunk)
                        if (pending.isNotEmpty()) {
                            val vectors = pending.map { encoder.encode(it.text) }
                            val ids = pending.map { it.stableId }.toLongArray()
                            val flattened = FloatArray(vectors.size * DocumentDatabase.EMBEDDING_DIMENSION)
                            vectors.forEachIndexed { vectorIndexInBatch, vector ->
                                require(vector.size == DocumentDatabase.EMBEDDING_DIMENSION) {
                                    "EmbeddingGemma dimension changed: ${vector.size}"
                                }
                                vector.copyInto(
                                    flattened,
                                    destinationOffset = vectorIndexInBatch * DocumentDatabase.EMBEDDING_DIMENSION,
                                )
                            }
                            vectorIndex.upsertBatch(ids, flattened)
                            pending.forEach(database::upsertChunk)
                        }
                        val completedBefore = batchIndex * BATCH_SIZE
                        batch.indices.forEach { index ->
                            onProgress(source, completedBefore + index + 1, all.size)
                        }
                    }
            }
        }
    }

    fun search(
        query: String,
        keywordGroups: List<List<String>> = emptyList(),
        sources: Set<DocumentSource> = DocumentSource.entries.toSet(),
        limitPerSource: Int = 16,
        timeHint: String = "",
        fromDate: String = "",
        toDate: String = "",
        mediaType: QueryMediaType? = null,
        senderNeedles: List<String> = emptyList(),
    ): List<DocumentMatch> {
        if (!model.isFile || !tokenizer.isFile || query.isBlank()) return emptyList()
        return lock.withLock {
            val vector = encoder.encode(query)
            val timeScope = QueryScopeParser.parse(
                timeHint = timeHint,
                locationHint = "",
                fromDate = fromDate,
                toDate = toDate,
            ).time
            fun inTimeScope(chunk: DocumentChunk): Boolean =
                timeScope == null || chunk.timestampMs == null || timeScope.matches(chunk.timestampMs)
            fun inMediaScope(chunk: DocumentChunk): Boolean = mediaType?.matchesDocumentChunk(chunk) ?: true
            val hasHardScope = timeScope != null || mediaType != null || senderNeedles.isNotEmpty()
            val scopedIdsBySource = if (hasHardScope) {
                database.stableIdsForSearchScope(
                    sources = sources,
                    timeScope = timeScope,
                    mediaType = mediaType,
                    senderNeedles = senderNeedles,
                )
            } else {
                emptyMap()
            }
            val hasKeywordContract = keywordGroups.isNotEmpty()
            val keywordMatches = database.searchKeywordChunks(
                keywordGroups,
                sources,
                limitPerSource,
                requireCompleteGroup = hasKeywordContract,
                timeScope = timeScope,
                mediaType = mediaType,
                senderNeedles = senderNeedles,
            )
                .filter { inTimeScope(it.first) && inMediaScope(it.first) }
            val keywordIds = keywordMatches.mapTo(HashSet()) { it.first.stableId }
            // Each private source owns an independent TurboQuant file. Search
            // those files concurrently after the single query embedding is
            // ready; the encoder itself remains shared and lock-protected.
            val sourceIndexes = sources.associateWith { indexFor(it) }
            val rawSemanticMatches = sourceSearchExecutor.invokeAll(
                sources.map { source ->
                    Callable {
                        val scopedIds: LongArray? = if (hasHardScope) {
                            scopedIdsBySource[source] ?: LongArray(0)
                        } else {
                            null
                        }
                        if (hasHardScope && scopedIds?.isEmpty() == true) {
                            return@Callable emptyList<DocumentMatch>()
                        }
                        val result = sourceIndexes.getValue(source)
                            .search(vector, limitPerSource, scopedIds)
                        val chunks = database.chunks(result.ids)
                        chunks.filter { inTimeScope(it) && inMediaScope(it) }.mapIndexed { index, chunk ->
                            val rank = index + 1
                            DocumentMatch(
                                chunk = chunk,
                                score = result.scores.getOrElse(index) { 0f },
                                rank = rank,
                                fusionScore = 1f / (RRF_K + rank.toFloat()),
                                cosineScore = result.scores.getOrElse(index) { 0f },
                            )
                        }
                    }
                },
            ).flatMap { it.get() }
            // A keyword group is an AND contract. Semantic similarity cannot
            // bypass it: otherwise a calendar row containing only "ticket"
            // can enter an "Odyssey movie ticket" search.
            val semanticMatches = rawSemanticMatches.filter {
                !hasKeywordContract || it.chunk.stableId in keywordIds
            }
            val byId = LinkedHashMap<Long, DocumentMatch>()
            semanticMatches.forEach { match -> byId[match.chunk.stableId] = match }
            val highestCosine = semanticMatches.maxOfOrNull { it.cosineScore ?: 0f }?.coerceIn(0f, 1f) ?: 1f
            keywordMatches.forEach { (chunk, coverage) ->
                val existing = byId[chunk.stableId]
                val keywordScore = highestCosine * coverage
                byId[chunk.stableId] = if (existing == null) {
                    DocumentMatch(chunk, keywordScore, rank = 0, fusionScore = keywordScore)
                } else {
                    val cosine = existing.cosineScore ?: 0f
                    existing.copy(
                        score = cosine + keywordScore,
                        fusionScore = cosine + keywordScore,
                    )
                }
            }
            // Per-source limits are only the candidate budget. The user-facing
            // contract is one ranked window across all private apps.
            val merged = byId.values
                .groupBy { it.chunk.source }
                .values
                .flatMap { it.sortedByDescending(DocumentMatch::fusionScore).take(limitPerSource) }
                .sortedByDescending(DocumentMatch::fusionScore)
                .take(limitPerSource)
            Log.i(
                TAG,
                "private search semantic=1 keywordGroups=${keywordGroups.size} " +
                    "keywordMatches=${keywordMatches.size} " +
                        "sources=${sources.size} results=${merged.size} " +
                    "semanticDropped=${rawSemanticMatches.size - semanticMatches.size} " +
                    "semanticTop=" + semanticMatches.take(5).joinToString(" | ") {
                        "${it.chunk.source.wire}:${"%.4f".format(it.score)}"
                    } + " " +
                    "top=" + merged.take(5).joinToString(" | ") {
                        "${it.chunk.source.wire}:${it.chunk.stableId}:${"%.4f".format(it.score)}"
                    } +
                    " idCheck=matched/${rawSemanticMatches.size}",
            )
            merged
        }
    }

    fun knownCallLogNames(): List<String> = lock.withLock {
        database.distinctTitles(DocumentSource.CALL_LOGS)
    }

    fun knownContactNames(): List<String> = lock.withLock {
        database.distinctTitles(DocumentSource.CONTACTS)
    }

    /** Loads the small persisted candidate-fact set without touching vectors. */
    fun answerabilityFacts(matches: List<DocumentMatch>): Map<String, List<AnswerFactGrounding.IndexedFact>> =
        lock.withLock {
            database.ensureAnswerabilityFacts(matches.map { it.chunk.stableId }.distinct().toLongArray())
                .mapKeys { (stableId, _) -> AnswerFactGrounding.documentKey(stableId) }
        }

    /** Direct newest-first path for conversational call/message questions. */
    fun latestCommunicationMatches(
        source: DocumentSource,
        needles: List<String>,
        limit: Int,
        timeHint: String = "",
        fromDate: String = "",
        toDate: String = "",
        senderOnly: Boolean = false,
    ): List<DocumentMatch> = lock.withLock {
        database.latestChunksMatching(
            source = source,
            needles = needles,
            limit = limit,
            timeHint = timeHint,
            fromDate = fromDate,
            toDate = toDate,
            senderOnly = senderOnly,
        ).mapIndexed { index, chunk ->
            val score = 1f / (index + 1f)
            DocumentMatch(
                chunk = chunk,
                score = score,
                rank = index + 1,
                fusionScore = score,
                cosineScore = null,
            )
        }
    }

    fun contactNumbersForName(name: String): List<String> = lock.withLock {
        database.contactNumbersForName(name)
    }

    override fun close() {
        sourceSearchExecutor.shutdown()
        lock.withLock {
            indexes.values.forEach(Closeable::close)
            indexes.clear()
        }
        database.close()
    }

    private fun indexFor(source: DocumentSource): NativeVectorIndex =
        indexes[source] ?: NativeVectorIndex.open(
            context,
            source.indexFile,
            DocumentDatabase.EMBEDDING_DIMENSION,
        ).also { indexes[source] = it }

    companion object {
        const val TAG = "AskGalaxyDocumentSearch"
        const val RRF_K = 60f
        const val BATCH_SIZE = 4
        const val MAX_PARALLEL_SOURCES = 4

        private val residentLock = Any()
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        @Volatile private var resident: DocumentVectorIndex? = null

        /** Prewarms existing private indexes without creating empty files. */
        fun preloadAsync(context: Context) {
            val appContext = context.applicationContext
            if (!ModelCatalog.embeddingGemma.isInstalled(appContext) ||
                !ModelCatalog.embeddingGemmaTokenizer.isInstalled(appContext)
            ) return
            synchronized(residentLock) {
                if (resident != null) return
                preloadExecutor.execute {
                    synchronized(residentLock) {
                        if (resident != null) return@synchronized
                        val warm = DocumentVectorIndex(appContext)
                        runCatching { warm.preloadBuiltIndexes() }
                            .onSuccess { resident = warm }
                            .onFailure { error ->
                                warm.close()
                                Log.w(TAG, "Private index preload failed", error)
                            }
                    }
                }
            }
        }

        fun shared(context: Context): DocumentVectorIndex {
            synchronized(residentLock) {
                return resident ?: DocumentVectorIndex(context.applicationContext).also {
                    resident = it
                }
            }
        }

        fun releaseResident() {
            synchronized(residentLock) {
                resident?.close()
                resident = null
            }
        }
    }
}
