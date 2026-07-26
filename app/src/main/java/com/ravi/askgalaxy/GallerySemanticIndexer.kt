package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

enum class EmbeddingStage {
    LOCATION,
    IMAGE,
    OCR,
    FACE,
    CLUSTERING,
    EPISODES,
}

data class EmbeddingProgress(
    val completed: Int,
    val total: Int,
    val skipped: Int,
    val stage: EmbeddingStage = EmbeddingStage.IMAGE,
    val ocrCompleted: Int = 0,
    val ocrTotal: Int = 0,
    val ocrFound: Int = 0,
    val faceCompleted: Int = 0,
    val faceTotal: Int = 0,
    val facesFound: Int = 0,
    val clusterCount: Int = 0,
    val needsFaceTags: Boolean = false,
    val clusteringComplete: Boolean = false,
    val locationCompleted: Int = 0,
    val locationTotal: Int = 0,
    val locationsWithGps: Int = 0,
    val locationsResolved: Int = 0,
    val locationRetryable: Int = 0,
    val episodeCount: Int = 0,
    val episodeIndexComplete: Boolean = false,
)

data class SemanticMatch(
    val mediaStoreId: Long,
    val score: Float,
)

/** Incrementally extracts image/frame embeddings and persists them in TurboQuant. */
class GallerySemanticIndexer(
    context: Context,
    private val database: GalleryDatabase,
) : Closeable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val locationIndexer = LocationIndexer(appContext, database)
    private val ocrIndexer = OcrIndexer(appContext, database)
    private val faceIndexer = FaceIndexer(appContext, database)
    private val faceClusterer = FaceClusterer(database)
    private val episodeIndexer = EpisodeIndexer(database)

    fun indexAsync(onFinished: (Result<EmbeddingProgress>) -> Unit) {
        executor.execute {
            val result = runCatching { indexBlocking() }
            onFinished(result)
        }
    }

    fun indexBlocking(onProgress: (EmbeddingProgress) -> Unit = {}): EmbeddingProgress {
        val locationProgress = locationIndexer.indexBlocking { update ->
            onProgress(
                EmbeddingProgress(
                    completed = 0,
                    total = 0,
                    skipped = 0,
                    stage = EmbeddingStage.LOCATION,
                    locationCompleted = update.completed,
                    locationTotal = update.total,
                    locationsWithGps = update.withGps,
                    locationsResolved = update.resolved,
                    locationRetryable = update.retryable,
                ),
            )
        }
        val imageProgress = indexImagesBlocking(onProgress)
        val ocrProgress = ocrIndexer.indexBlocking { update ->
            onProgress(
                EmbeddingProgress(
                    completed = imageProgress.completed,
                    total = imageProgress.total,
                    skipped = imageProgress.skipped,
                    stage = EmbeddingStage.OCR,
                    ocrCompleted = update.completed,
                    ocrTotal = update.total,
                    ocrFound = update.textFound,
                    locationCompleted = locationProgress.completed,
                    locationTotal = locationProgress.total,
                    locationsWithGps = locationProgress.withGps,
                    locationsResolved = locationProgress.resolved,
                    locationRetryable = locationProgress.retryable,
                ),
            )
        }
        val faceProgress = faceIndexer.indexBlocking { update ->
            onProgress(
                EmbeddingProgress(
                    completed = imageProgress.completed,
                    total = imageProgress.total,
                    skipped = imageProgress.skipped + ocrProgress.skipped,
                    stage = EmbeddingStage.FACE,
                    ocrCompleted = ocrProgress.completed,
                    ocrTotal = ocrProgress.total,
                    ocrFound = ocrProgress.textFound,
                    faceCompleted = update.completed,
                    faceTotal = update.total,
                    facesFound = update.facesFound,
                    locationCompleted = locationProgress.completed,
                    locationTotal = locationProgress.total,
                    locationsWithGps = locationProgress.withGps,
                    locationsResolved = locationProgress.resolved,
                    locationRetryable = locationProgress.retryable,
                ),
            )
        }
        onProgress(
            EmbeddingProgress(
                completed = imageProgress.completed,
                total = imageProgress.total,
                skipped = imageProgress.skipped + ocrProgress.skipped,
                stage = EmbeddingStage.CLUSTERING,
                ocrCompleted = ocrProgress.completed,
                ocrTotal = ocrProgress.total,
                ocrFound = ocrProgress.textFound,
                faceCompleted = faceProgress.completed,
                faceTotal = faceProgress.total,
                facesFound = faceProgress.facesFound,
                clusterCount = 0,
                clusteringComplete = false,
                locationCompleted = locationProgress.completed,
                locationTotal = locationProgress.total,
                locationsWithGps = locationProgress.withGps,
                locationsResolved = locationProgress.resolved,
                locationRetryable = locationProgress.retryable,
            ),
        )
        val clusterCount = if (database.faceClustersAreCurrent()) {
            database.faceClusterCount()
        } else {
            faceClusterer.rebuildBlocking()
        }
        onProgress(
            EmbeddingProgress(
                completed = imageProgress.completed,
                total = imageProgress.total,
                skipped = imageProgress.skipped + ocrProgress.skipped + faceProgress.skipped,
                stage = EmbeddingStage.EPISODES,
                ocrCompleted = ocrProgress.completed,
                ocrTotal = ocrProgress.total,
                ocrFound = ocrProgress.textFound,
                faceCompleted = faceProgress.completed,
                faceTotal = faceProgress.total,
                facesFound = faceProgress.facesFound,
                clusterCount = clusterCount,
                clusteringComplete = true,
                locationCompleted = locationProgress.completed,
                locationTotal = locationProgress.total,
                locationsWithGps = locationProgress.withGps,
                locationsResolved = locationProgress.resolved,
                locationRetryable = locationProgress.retryable,
            ),
        )
        val episodeCount = episodeIndexer.rebuildBlocking()
        val needsFaceTags = database.hasUnnamedFaceClusters()
        val finalProgress = EmbeddingProgress(
            completed = imageProgress.completed,
            total = imageProgress.total,
            skipped = imageProgress.skipped + ocrProgress.skipped + faceProgress.skipped,
            stage = EmbeddingStage.EPISODES,
            ocrCompleted = ocrProgress.completed,
            ocrTotal = ocrProgress.total,
            ocrFound = ocrProgress.textFound,
            faceCompleted = faceProgress.completed,
            faceTotal = faceProgress.total,
            facesFound = faceProgress.facesFound,
            clusterCount = clusterCount,
            needsFaceTags = needsFaceTags,
            clusteringComplete = true,
            locationCompleted = locationProgress.completed,
            locationTotal = locationProgress.total,
            locationsWithGps = locationProgress.withGps,
            locationsResolved = locationProgress.resolved,
            locationRetryable = locationProgress.retryable,
            episodeCount = episodeCount,
            episodeIndexComplete = true,
        )
        onProgress(finalProgress)
        return finalProgress
    }

    /** Rebuilds only the SigLIP image-vector index. */
    fun reindexVisualsBlocking(onProgress: (EmbeddingProgress) -> Unit = {}): EmbeddingProgress =
        indexImagesBlocking(onProgress)

    /** Rebuilds faces, preserves recognizable labels where possible, and refreshes dependent episodes. */
    fun reindexFacesBlocking(onProgress: (EmbeddingProgress) -> Unit = {}): EmbeddingProgress {
        val faceProgress = faceIndexer.indexBlocking { update ->
            onProgress(
                EmbeddingProgress(
                    completed = update.completed,
                    total = update.total,
                    skipped = update.skipped,
                    stage = EmbeddingStage.FACE,
                    faceCompleted = update.completed,
                    faceTotal = update.total,
                    facesFound = update.facesFound,
                ),
            )
        }
        val clusterCount = faceClusterer.rebuildBlocking()
        val episodeCount = episodeIndexer.rebuildBlocking()
        return EmbeddingProgress(
            completed = faceProgress.completed,
            total = faceProgress.total,
            skipped = faceProgress.skipped,
            stage = EmbeddingStage.EPISODES,
            faceCompleted = faceProgress.completed,
            faceTotal = faceProgress.total,
            facesFound = faceProgress.facesFound,
            clusterCount = clusterCount,
            clusteringComplete = true,
            episodeCount = episodeCount,
            episodeIndexComplete = true,
            needsFaceTags = database.hasUnnamedFaceClusters(),
        ).also(onProgress)
    }

    /** Rebuilds only the derived episode membership tables. */
    fun reindexEpisodesBlocking(): Int = episodeIndexer.rebuildBlocking()

    fun searchBlocking(query: String, limit: Int = 8): LongArray {
        return searchScoredBlocking(query, limit).map { it.mediaStoreId }.toLongArray()
    }

    fun searchScoredBlocking(query: String, limit: Int = 16): List<SemanticMatch> =
        searchScoredBlocking(listOf(query), limit)

    /** Encodes several planner variants with one text encoder and keeps each row's best score. */
    fun searchScoredBlocking(
        queries: List<String>,
        limit: Int = 16,
        allowlist: LongArray? = null,
    ): List<SemanticMatch> =
        searchScoredBlocking(
            queries = queries,
            limit = limit,
            allowlist = allowlist,
            acceptScore = ::isAcceptedSemanticScore,
        )

    /**
     * Returns the nearest finite neighbors without applying the public 0.10
     * confidence cutoff. Retrieval uses this once so it can keep confident
     * matches when present and fall back to ranked neighbors only when the
     * strict result set is empty.
     */
    fun searchNearestScoredBlocking(
        queries: List<String>,
        limit: Int = 16,
        allowlist: LongArray? = null,
    ): List<SemanticMatch> =
        searchScoredBlocking(
            queries = queries,
            limit = limit,
            allowlist = allowlist,
            acceptScore = Float::isFinite,
        )

    private fun searchScoredBlocking(
        queries: List<String>,
        limit: Int,
        allowlist: LongArray?,
        acceptScore: (Float) -> Boolean,
    ): List<SemanticMatch> {
        check(ModelCatalog.siglipText.isInstalled(appContext)) {
            "Install ${ModelCatalog.siglipText.relativePath} before semantic search"
        }
        check(ModelCatalog.siglipTokenizer.isInstalled(appContext)) {
            "Install ${ModelCatalog.siglipTokenizer.relativePath} before semantic search"
        }
        val index = NativeVectorIndex.shared(appContext)
        if (index.size == 0L) return emptyList()
        val encoder = SigLipTextEncoder.shared(appContext)
        val bestByMediaId = HashMap<Long, SemanticMatch>()
        queries.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(MAX_QUERY_VARIANTS)
            .forEach { query ->
            val result = index.search(
                encoder.encode(query),
                limit.coerceIn(1, MAX_SEARCH_RESULTS),
                allowlist,
            )
            result.ids.forEachIndexed { indexInResult, id ->
                val match = SemanticMatch(id, result.scores.getOrElse(indexInResult) { 0f })
                if (!acceptScore(match.score)) return@forEachIndexed
                val previous = bestByMediaId[id]
                if (previous == null || match.score > previous.score) {
                    bestByMediaId[id] = match
                }
            }
        }
        return bestByMediaId.values.sortedByDescending { it.score }
            .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
    }

    /** Chooses representatives from the same persisted quantized space as retrieval. */
    fun selectDiverseCandidatesBlocking(
        candidates: List<GalleryMedia>,
        maxCount: Int,
    ): List<GalleryMedia> {
        val limited = candidates.take(DIVERSITY_CANDIDATE_LIMIT)
        if (limited.isEmpty() || limited.size <= maxCount) return limited
        val ids = limited.map { it.mediaStoreId }.toLongArray()
        val visualOrderCount = minOf(limited.size - 1, (maxCount * 2).coerceAtLeast(maxCount))
        val visualOrder = runCatching {
            NativeVectorIndex.shared(appContext).selectDiverse(ids, visualOrderCount)
        }.getOrDefault(ids)
        return MultimodalDiversitySelector.select(limited, visualOrder, maxCount)
    }

    override fun close() {
        executor.shutdown()
        ocrIndexer.close()
        faceIndexer.close()
        SigLipTextEncoder.releaseResident()
        NativeVectorIndex.releaseResident()
    }

    private fun indexImagesBlocking(onProgress: (EmbeddingProgress) -> Unit): EmbeddingProgress {
        check(ModelCatalog.siglipVision.isInstalled(appContext)) {
            "Install ${ModelCatalog.siglipVision.relativePath} before building the image index"
        }
        // An indexing pass can replace codes and ids. Drop a search resident
        // opened by an earlier query so the next query observes the new file.
        NativeVectorIndex.releaseResident()
        val pending = database.pendingEmbeddings()
        if (pending.isEmpty()) return EmbeddingProgress(0, 0, 0, EmbeddingStage.IMAGE)

        val completed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val next = AtomicInteger(0)
        val indexLock = Any()
        val progressLock = Any()
        val index = NativeVectorIndex.open(appContext)
        val workerCount = InferenceParallelism.visualWorkerCount()
        val workers = Executors.newFixedThreadPool(workerCount)
        val futures = ArrayList<Future<*>>()
        fun publish() {
            synchronized(progressLock) {
                onProgress(
                    EmbeddingProgress(
                        completed = completed.get(),
                        total = pending.size,
                        skipped = skipped.get(),
                        stage = EmbeddingStage.IMAGE,
                    ),
                )
            }
        }
        try {
            repeat(workerCount) {
                futures += workers.submit {
                    SigLipImageEncoder.open(appContext).use { encoder ->
                        MediaBitmapLoader(appContext).use { loader ->
                            val batchIds = ArrayList<Long>(BATCH_SIZE)
                            val batchValues = ArrayList<Float>(BATCH_SIZE * SigLipImageEncoder.EMBEDDING_DIMENSION)
                            fun flushBatch() {
                                if (batchIds.isEmpty()) return
                                synchronized(indexLock) {
                                    commitBatch(index, batchIds, batchValues)
                                    batchIds.forEach { database.markEmbeddingIndexed(it) }
                                }
                                completed.addAndGet(batchIds.size)
                                batchIds.clear()
                                batchValues.clear()
                                publish()
                            }
                            while (true) {
                                val media = pending.getOrNull(next.getAndIncrement()) ?: break
                                val bitmap = runCatching { loader.load(media) }.getOrNull()
                                if (bitmap == null) {
                                    synchronized(indexLock) { database.markEmbeddingIndexed(media.mediaStoreId) }
                                    skipped.incrementAndGet()
                                    publish()
                                    continue
                                }
                                try {
                                    val embedding = try {
                                        encoder.encode(bitmap)
                                    } catch (error: Exception) {
                                        Log.w(
                                            TAG,
                                            "Skipping visual embedding for ${media.mediaStoreId} (${media.displayName})",
                                            error,
                                        )
                                        null
                                    }
                                    if (embedding == null || !embedding.isValid()) {
                                        if (embedding != null) {
                                            Log.w(TAG, "Skipping invalid visual embedding for ${media.mediaStoreId} (${media.displayName})")
                                        }
                                        synchronized(indexLock) { database.markEmbeddingIndexed(media.mediaStoreId) }
                                        skipped.incrementAndGet()
                                        publish()
                                        continue
                                    }
                                    batchIds += media.mediaStoreId
                                    embedding.forEach { batchValues += it }
                                    if (batchIds.size >= BATCH_SIZE) flushBatch()
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            flushBatch()
                        }
                    }
                }
            }
            futures.forEach(Future<*>::get)
        } finally {
            workers.shutdownNow()
            index.close()
        }
        publish()
        return EmbeddingProgress(completed.get(), pending.size, skipped.get(), EmbeddingStage.IMAGE)
    }

    private fun commitBatch(
        index: NativeVectorIndex,
        ids: List<Long>,
        values: List<Float>,
    ) {
        index.upsertBatch(
            ids.toLongArray(),
            values.toFloatArray(),
        )
    }

    companion object {
        private const val BATCH_SIZE = 16
        private const val DIVERSITY_CANDIDATE_LIMIT = 100
        /** Retrieval budget only; presentation never truncates the returned set. */
        private const val MAX_SEARCH_RESULTS = 512
        internal const val MIN_SEMANTIC_COSINE_SCORE = 0.10f
        private const val MAX_QUERY_VARIANTS = 4
        private const val TAG = "AskGalaxyImageIndex"

        internal fun isAcceptedSemanticScore(score: Float): Boolean =
            score.isFinite() && score >= MIN_SEMANTIC_COSINE_SCORE

        private fun FloatArray.isValid(): Boolean =
            size == SigLipImageEncoder.EMBEDDING_DIMENSION && all { it.isFinite() }
    }
}
