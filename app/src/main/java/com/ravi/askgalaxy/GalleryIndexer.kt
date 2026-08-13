package com.ravi.askgalaxy

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.CalendarContract
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class SearchStage {
    QUERY_PLANNING,
    QUERY_PLANNED,
    HYBRID_RETRIEVAL,
    DIVERSE_EVIDENCE,
}

data class SearchProgress(
    val stage: SearchStage,
    val candidateCount: Int,
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
    val timings: PhaseTimings = PhaseTimings(),
)

internal object FollowUpSuggestionPolicy {
    private val DOCUMENT_QUERY = Regex(
        "(?i)\\b(?:aadhaa?r|passport|pan(?:\\s+card)?|driv(?:er'?s?|ing)\\s+licen[cs]e|" +
            "licen[cs]e|identity\\s+card|id\\s+card|receipt|bill|invoice|ticket|" +
            "boarding\\s+pass|voucher|coupon|mark\\s*sheet|report\\s+card|" +
            "insurance\\s+policy|policy|certificate)\\b",
    )
    private val DOCUMENT_FIELD = Regex(
        "(?i)\\b(?:expir(?:e|es|ed|y|ation)|valid|issued?|number|name|whose|holder|" +
            "address|birth|dob|date|amount|total|merchant|cost|price|class|vehicle|authority|status)\\b",
    )
    private val SCENE_DRIFT = Regex(
        "(?i)\\b(?:who\\s+else|there\\s+on|what\\s+(?:else\\s+)?(?:happened|occurred)|" +
            "other\\s+(?:moments|photos|people)|appeared\\s+with|nearby|what\\s+else\\s+did)\\b|" +
            "\\bwhere\\s+(?:was|were).*(?:taken|captured)\\b",
    )

    fun isDocumentQuery(query: String): Boolean = DOCUMENT_QUERY.containsMatchIn(query)

    fun isCompatible(candidate: String, originalQuery: String): Boolean {
        if (!isDocumentQuery(originalQuery)) return true
        return !SCENE_DRIFT.containsMatchIn(candidate) && DOCUMENT_FIELD.containsMatchIn(candidate)
    }

    /** Keeps displayed document follow-ups short; QP restores the prior anchor internally. */
    fun compactForDisplay(candidate: String, originalQuery: String): String {
        if (!isDocumentQuery(originalQuery)) return candidate
        val normalized = candidate.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return when {
            Regex("\\b(?:date of birth|birth date|dob)\\b").containsMatchIn(normalized) ->
                "What is the date of birth?"
            Regex("\\b(?:expir(?:e|es|ed|ing|y|ation)|valid(?:ity| until| till))\\b")
                .containsMatchIn(normalized) -> "What is the expiry date?"
            Regex("\\bissu(?:e|ed|ing)\\b").containsMatchIn(normalized) ->
                "What is the issue date?"
            Regex("\\baddress\\b").containsMatchIn(normalized) -> "What is the address?"
            Regex("\\b(?:number|no)\\b").containsMatchIn(normalized) -> "What is the number?"
            Regex("\\b(?:whose|holder|name)\\b").containsMatchIn(normalized) ->
                "What is the holder name?"
            Regex("\\b(?:amount|total|cost|price)\\b").containsMatchIn(normalized) ->
                "What is the total amount?"
            Regex("\\bmerchant\\b").containsMatchIn(normalized) -> "What is the merchant?"
            Regex("\\bstatus\\b").containsMatchIn(normalized) -> "What is the status?"
            Regex("\\bdate\\b").containsMatchIn(normalized) -> "What is the date?"
            else -> candidate
        }
    }
}

class GalleryIndexer(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val database = GalleryDatabase(appContext)
    private val semanticIndexer = GallerySemanticIndexer(appContext, database)
    private val metadataReader = GalleryMetadataReader(appContext)
    private val structuredSearchExecutor =
        StructuredSearchExecutor(
            database,
            semanticIndexer,
            metadataReader,
        )
    private val evidenceBuilder = EvidenceBuilder(database)
    private val answerContextPicker = AnswerContextPicker()
    private val answerImageCache = AnswerImageCache(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val retrievalExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val previewExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val answerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var lastAnswerProfile: GemmaRuntime.GenerationProfile? = null

    fun scanAsync(onFinished: (Result<Long>) -> Unit) {
        executor.execute {
            val result = runCatching { scanBlocking() }
            onFinished(result)
        }
    }

    fun count(): Long = database.count()

    fun pendingOcrCount(): Int = database.pendingOcrCount()

    fun indexEmbeddingsAsync(onFinished: (Result<EmbeddingProgress>) -> Unit) {
        semanticIndexer.indexAsync(onFinished)
    }

    fun searchMetadata(query: String, limit: Int = 8): List<GalleryMedia> =
        database.searchMetadata(query, limit)

    fun searchAsync(
        query: String,
        previousQuery: String = "",
        previousAnswer: String = "",
        onMatches: (List<HybridSearchResult>, Int) -> Unit = { _, _ -> },
        onProgress: (SearchProgress) -> Unit = {},
        onFinished: (Result<SearchResponse>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching {
                var timings = PhaseTimings()
                val planningStarted = System.nanoTime()
                try {
                    onProgress(
                        SearchProgress(
                            SearchStage.QUERY_PLANNING,
                            0,
                        ),
                    )
                    var planningFinished = planningStarted
                    val searchExecution = searchBlocking(
                        query = query,
                        previousQuery = previousQuery,
                        previousAnswer = previousAnswer,
                    ) { plannerJson, effectivePlanJson, plannerProfile ->
                        planningFinished = System.nanoTime()
                        timings = timings.copy(
                            queryPlanningMs = elapsedMs(planningStarted, planningFinished),
                        ).withPlannerProfile(plannerProfile)
                        onProgress(
                            SearchProgress(
                                stage = SearchStage.QUERY_PLANNED,
                                candidateCount = 0,
                                plannerJson = plannerJson,
                                effectivePlanJson = effectivePlanJson,
                                timings = timings,
                            ),
                        )
                    }
                    val candidates = searchExecution.candidates
                    // StructuredSearchExecutor owns category-aware relevance:
                    // scenery favors semantics, documents favor complete OCR
                    // conjunctions, and person/location hard metadata scopes
                    // are ranked before tie-breaking by recency. Never replace
                    // that overall query rank with a newest-first UI sort.
                    val globalWindow = mergeGlobalSearchWindow(
                        gallery = candidates,
                        documents = searchExecution.documentMatches,
                    )
                    val uiGallery = SearchResultPresentationPolicy.top(
                        rankedCandidates = globalWindow.gallery,
                        limit = UI_RESULT_LIMIT,
                    )
                    // Publish the practical browsing window before episode
                    // joining, Context Picker reranking, or Gemma answer work.
                    runCatching {
                        onMatches(
                            globalWindow.ordered,
                            candidates.size + searchExecution.documentMatches.size,
                        )
                    }
                    timings = timings.copy(
                        searchMs = elapsedMs(planningFinished, System.nanoTime()),
                    )
                    onProgress(
                        SearchProgress(
                            SearchStage.HYBRID_RETRIEVAL,
                            candidates.size,
                            searchExecution.answerEvidenceScope,
                            searchExecution.plannerJson,
                            searchExecution.effectivePlanJson,
                            timings,
                        ),
                    )
                    // The app is currently in QP + validator + search-result
                    // mode. Do not spend time or memory building answer
                    // evidence, diversity context, or follow-up state.
                    if (!searchExecution.needsAnswer) {
                        return@runCatching SearchResponse(
                            gallery = globalWindow.gallery,
                            totalGalleryMatches = candidates.size,
                            galleryCosineScores = searchExecution.galleryCosineScores,
                            plannerJson = searchExecution.plannerJson,
                            effectivePlanJson = searchExecution.effectivePlanJson,
                            resolvedQuery = searchExecution.resolvedQuery,
                            queryCategory = searchExecution.queryCategory,
                            needsAnswer = false,
                            answerEvidenceScope = searchExecution.answerEvidenceScope,
                            timings = timings,
                            documentMatches = globalWindow.documents,
                            mergedResults = globalWindow.ordered,
                        )
                    }
                    onProgress(
                        SearchProgress(
                            SearchStage.DIVERSE_EVIDENCE,
                            candidates.size,
                            searchExecution.answerEvidenceScope,
                            searchExecution.plannerJson,
                            searchExecution.effectivePlanJson,
                            timings,
                        ),
                    )
                    val evidenceStarted = System.nanoTime()
                    val evidenceBuild = evidenceBuilder.build(query, candidates)
                    timings = timings.copy(
                        evidenceCurationMs = elapsedMs(evidenceStarted, System.nanoTime()),
                    )
                    val diverseStarted = System.nanoTime()
                    // Answer grounding follows the displayed hybrid order.
                    // Episode and visual diversity remain browsing features;
                    // they must not replace a stronger public result here.
                    val answerContext = answerContextPicker.pick(
                        query = query,
                        rankedCandidates = candidates,
                        evidenceScope = searchExecution.answerEvidenceScope,
                        queryCategory = searchExecution.queryCategory,
                        ocrKeywords = searchExecution.ocrKeywords,
                        maxRecords = MAX_ANSWER_RECORDS,
                    )
                    Log.i(
                        TAG,
                        "Context picker: category=${answerContext.queryCategory.wireName}, " +
                            "eligible=${answerContext.eligibleCandidateCount}/${answerContext.inputCandidateCount}, " +
                            "selected=${answerContext.items.size}, visuals=${answerContext.includeVisuals}, fields=" +
                            answerContext.metadataFields.joinToString(",") { it.name.lowercase() } +
                            ", coverage=" + answerContext.items.joinToString(" ") { item ->
                            "${item.media.mediaStoreId}:${item.coverage.joinToString("+") { it.name.lowercase() }}"
                        }.take(MAX_EVIDENCE_LOG_CHARS),
                    )
                    timings = timings.copy(
                        diverseRerankingMs = elapsedMs(diverseStarted, System.nanoTime()),
                    )
                    Log.i(
                        TAG,
                        "Search phases: planning=${timings.queryPlanningMs}ms, retrieval=${timings.searchMs}ms, " +
                            "diversity=${timings.diverseRerankingMs}ms, curation=${timings.evidenceCurationMs}ms, " +
                            "groups=${evidenceBuild.evidenceGroups.size}, evidence=${searchExecution.answerEvidenceScope.label()}",
                    )
                    if (evidenceBuild.evidenceGroups.isNotEmpty()) {
                        Log.i(
                            TAG,
                            "Evidence groups: " + evidenceBuild.evidenceGroups.mapIndexed { index, group ->
                                "E${index + 1}:${group.memberCount}@${group.startTimeMs ?: 0L}-${group.endTimeMs ?: 0L}"
                            }.joinToString(" ").take(MAX_EVIDENCE_LOG_CHARS),
                        )
                    }

                    SigLipTextEncoder.releaseResident()
                    SearchResponse(
                        // The result browser is independent from the top-4
                        // answer context. Show up to 30 query-ranked matches while
                        // retaining the full evaluated count.
                        gallery = uiGallery,
                        totalGalleryMatches = candidates.size,
                        galleryCosineScores = searchExecution.galleryCosineScores,
                        answerGallery = answerContext.records,
                        answerContext = answerContext,
                        evidenceRecords = emptyList(),
                        evidenceGroups = evidenceBuild.evidenceGroups.filter { group ->
                            answerContext.items.any {
                                it.media.mediaStoreId == group.representative.mediaStoreId
                            }
                        },
                        evidenceGroupingMode = evidenceBuild.groupingMode,
                        plannerSession = searchExecution.plannerSession,
                        plannerJson = searchExecution.plannerJson,
                        effectivePlanJson = searchExecution.effectivePlanJson,
                        resolvedQuery = searchExecution.resolvedQuery,
                        queryCategory = searchExecution.queryCategory,
                        needsAnswer = searchExecution.needsAnswer,
                        answerEvidenceScope = searchExecution.answerEvidenceScope,
                        answerOcrKeywords = searchExecution.ocrKeywords,
                        timings = timings,
                        documentMatches = globalWindow.documents,
                        mergedResults = globalWindow.ordered,
                    )
                } finally {
                    SigLipTextEncoder.releaseResident()
                }
            }
            onFinished(result)
        }
    }

    fun faceClustersAsync(onFinished: (Result<List<FaceCluster>>) -> Unit) {
        executor.execute {
            // The UI virtualizes this complete snapshot. Do not reintroduce a
            // mutable "top N" window: merges must not reveal a different set.
            onFinished(runCatching { database.faceClusters() })
        }
    }

    fun saveFaceClusterIdentityAsync(
        clusterId: String,
        label: String,
        isSelf: Boolean,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        executor.execute {
            onFinished(runCatching {
                database.saveFaceClusterIdentity(clusterId, label, isSelf)
            })
        }
    }

    fun mergeFaceClustersAsync(
        clusterIds: List<String>,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        executor.execute {
            onFinished(runCatching { database.mergeFaceClusters(clusterIds) })
        }
    }

    fun loadThumbnailAsync(
        media: GalleryMedia,
        onFinished: (Result<android.graphics.Bitmap?>) -> Unit,
    ) {
        previewExecutor.execute {
            val result = runCatching {
                // User-facing thumbnails should honor EXIF rotation. Indexing
                // keeps the default raw orientation because face boxes are
                // stored in that coordinate system.
                MediaBitmapLoader(appContext).use {
                    it.load(media, maxDimension = 512, applyExifOrientation = true)
                }
            }
            onFinished(result)
        }
    }

    fun loadDetailPreviewAsync(
        media: GalleryMedia,
        onFinished: (Result<android.graphics.Bitmap?>) -> Unit,
    ) {
        previewExecutor.execute {
            val result = runCatching {
                MediaBitmapLoader(appContext).use {
                    it.load(media, maxDimension = 1_600, applyExifOrientation = true)
                }
            }
            onFinished(result)
        }
    }

    fun loadFaceThumbnailAsync(
        cluster: FaceCluster,
        onFinished: (Result<android.graphics.Bitmap?>) -> Unit,
    ) {
        previewExecutor.execute {
            val result = runCatching {
                MediaBitmapLoader(appContext).use { loader ->
                    val bitmap = loader.load(
                        cluster.representative,
                        maxDimension = 320,
                        applyExifOrientation = true,
                    )
                        ?: return@use null
                    val bounds = cluster.representativeBox ?: return@use bitmap
                    val orientedBounds = ImageOrientation.transformBox(
                        bounds,
                        loader.readOrientation(cluster.representative),
                    )
                    val detection = FaceDetection(
                        box = FaceBox(
                            left = orientedBounds.left * bitmap.width,
                            top = orientedBounds.top * bitmap.height,
                            right = orientedBounds.right * bitmap.width,
                            bottom = orientedBounds.bottom * bitmap.height,
                        ),
                        landmarks = FloatArray(0),
                        score = 1f,
                    )
                    val face = FaceCropper.crop(bitmap, detection)
                    if (face == null || face === bitmap) {
                        face ?: bitmap
                    } else {
                        bitmap.recycle()
                        face
                    }
                }
            }
            onFinished(result)
        }
    }

    fun answerAsync(
        query: String,
        response: SearchResponse,
        onFinished: (Result<AnswerResult>) -> Unit,
        onFollowUps: (List<FollowUpSuggestion>) -> Unit = {},
        onNextBriefs: (List<NextBriefSuggestion>) -> Unit = {},
        warmPlannerAfterAnswer: Boolean = true,
        warmAnswerAfterAnswer: Boolean = false,
        onStage: (AnswerPipelineStage) -> Unit = {},
    ) {
        answerExecutor.execute {
            if (!response.needsAnswer) {
                response.plannerSession?.close()
                onFinished(
                    Result.success(
                        AnswerResult(
                            text = "",
                            sources = emptyList(),
                            plannerJson = response.plannerJson,
                            effectivePlanJson = response.effectivePlanJson,
                            timings = response.timings,
                        ),
                    ),
                )
                return@execute
            }
            val plannerSession = response.plannerSession
            // The answer path deliberately uses a clean conversation; the
            // planner KV is no longer useful after retrieval. Release it
            // before EXIF reads, bounded scenery-image encoding, and answer
            // prompt assembly so
            // those hot paths do not compete with a second KV cache.
            plannerSession?.close()
            onStage(AnswerPipelineStage.ANSWERING)
            directCommunicationAnswer(query, response)?.let { directAnswer ->
                onStage(AnswerPipelineStage.ACCEPTING)
                onFinished(Result.success(directAnswer))
                if (warmPlannerAfterAnswer) {
                    GemmaRuntime.preloadPlannerAfterAnswerAsync(appContext, QueryPlannerRuntime.plannerSystemInstruction())
                }
                return@execute
            }
            var deferredFollowUps: (() -> Unit)? = null
            val result = try {
                runCatching {
                check(GemmaRuntime.isModelInstalled(appContext)) {
                    "Gemma 4 is not installed yet"
                }
                // Freeze the public grid's first eight records once. Every
                // downstream prompt, image, review, and source keeps this
                // order; there is no answer-only recovery rerank.
                val groundedResponse = response
                val displayedEvidence = AnswerEvidencePolicy.displayedTopEight(response)
                check(displayedEvidence.isNotEmpty()) { "No displayed result evidence for Gemma" }
                val orderedEvidence = displayedEvidence.mapIndexed { index, item ->
                    when (item) {
                        is HybridSearchResult.Gallery -> runCatching {
                            HybridSearchResult.Gallery(metadataReader.enrich(item.media))
                        }.onFailure { error ->
                            Log.w(
                                TAG,
                                "Could not enrich displayed answer result R${index + 1}; using its published metadata",
                                error,
                            )
                        }.getOrElse { item }
                        is HybridSearchResult.Document -> item
                    }
                }
                val attachedResults = orderedEvidence.mapNotNull { item ->
                    (item as? HybridSearchResult.Gallery)?.media
                }
                val documentEvidence = orderedEvidence.mapNotNull {
                    (it as? HybridSearchResult.Document)?.match
                }
                val imagePreparationStarted = System.nanoTime()
                var imageCacheHits = 0
                val loadedDocumentEvidence = ArrayList<Pair<DocumentMatch, ByteArray>>(documentEvidence.size)
                val documentRenderer = DocumentPageRenderer(appContext)
                documentEvidence.filter { it.chunk.source == DocumentSource.FILES }.forEach { match ->
                    val recordNumber = orderedEvidence.indexOfFirst {
                        (it as? HybridSearchResult.Document)?.match?.chunk?.stableId == match.chunk.stableId
                    } + 1
                    val cacheKey = documentAnswerImageCacheKey(match)
                    val cached = answerImageCache.get(cacheKey)
                    if (cached != null) {
                        imageCacheHits++
                        loadedDocumentEvidence += match to cached
                    } else {
                        val bitmap = documentRenderer.render(
                            match,
                            DOCUMENT_PAGE_RENDER_MAX_DIMENSION,
                        ) ?: throw AnswerEvidenceUnavailableException(
                            recordNumber = recordNumber,
                            detail = "its document page could not be rendered",
                        )
                        try {
                            val encoded = encodeGemmaImage(bitmap)
                            answerImageCache.put(cacheKey, encoded)
                            loadedDocumentEvidence += match to encoded
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
                // Only intrinsically textual phone sources supply text to the
                // answer model. Gallery OCR and file extraction stay retrieval-only.
                val textOnlyDocuments = documentEvidence.filter {
                    it.chunk.source != DocumentSource.FILES
                }
                val documentFactRows = if (textOnlyDocuments.isEmpty()) {
                    emptyMap()
                } else {
                    DocumentVectorIndex.shared(appContext).answerabilityFacts(textOnlyDocuments)
                }
                val textGroundingEvidence = orderedEvidence.map { item ->
                    when (item) {
                        is HybridSearchResult.Gallery -> HybridSearchResult.Gallery(
                            item.media.copy(ocrText = ""),
                        )
                        is HybridSearchResult.Document -> if (item.match.chunk.source == DocumentSource.FILES) {
                            HybridSearchResult.Document(
                                item.match.copy(
                                    chunk = item.match.chunk.copy(text = "", metadata = ""),
                                ),
                            )
                        } else {
                            item
                        }
                    }
                }
                val groundedFacts = AnswerFactGrounding.ground(
                    query = query,
                    evidence = textGroundingEvidence,
                    indexedFacts = documentFactRows,
                )
                val groundedFactValues = groundedFacts.matchedValues
                if (groundedFactValues.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Grounded labelled facts=${groundedFacts.matchedFacts.size}, " +
                            "distinctValues=${groundedFactValues.size}; passing to answer LLM for resolution",
                    )
                }
                val remainingVisualSlots = (
                    QueryCategoryContextPolicy.ANSWER_IMAGE_LIMIT - loadedDocumentEvidence.size
                    ).coerceAtLeast(0)
                val candidateVisualGallery = attachedResults
                    .filter(QueryCategoryContextPolicy::isVisualMedia)
                    .take(remainingVisualSlots)
                val evidenceScope = AnswerEvidenceScope.all()
                val loadedEvidence = ArrayList<Pair<GalleryMedia, ByteArray>>(candidateVisualGallery.size)
                val loadedFaceEvidence = ArrayList<Pair<GalleryMedia, ByteArray>>(1)
                // One full visual per top-eight gallery/file record. Auxiliary
                // crops must not displace a ranked record from the vision slots.
                val visualGallery = candidateVisualGallery.take(remainingVisualSlots)
                val visualIds = visualGallery.map { it.mediaStoreId }.toSet()
                val faceTarget = if (
                    response.answerEvidenceScope.needsPeopleMetadata ||
                    attachedResults.any { !it.personLabel.isNullOrBlank() } ||
                    Regex("(?i)\\b(who|whose|person|people|with|without)\\b").containsMatchIn(query)
                ) {
                    database.taggedFaceOccurrencesForMedia(visualIds.toLongArray())
                        .groupBy { it.mediaStoreId }
                        .let { occurrencesByMedia ->
                            visualGallery.firstNotNullOfOrNull { media ->
                                val occurrences = occurrencesByMedia[media.mediaStoreId].orEmpty()
                                val labels = media.personLabel.orEmpty().split(',').map(String::trim)
                                val occurrence = occurrences.firstOrNull { face ->
                                    labels.any { it.equals(face.label, ignoreCase = true) }
                                } ?: occurrences.firstOrNull()
                                occurrence?.let { media to it }
                            }
                        }
                } else {
                    null
                }
                MediaBitmapLoader(appContext).use { loader ->
                    visualGallery
                        .filter { it.mediaStoreId in visualIds }
                        .forEach { enrichedMedia ->
                        val recordNumber = orderedEvidence.indexOfFirst {
                            (it as? HybridSearchResult.Gallery)?.media?.mediaStoreId == enrichedMedia.mediaStoreId
                        } + 1
                        val maxDimension = galleryAnswerMaxDimension(enrichedMedia)
                        val cacheKey = galleryAnswerImageCacheKey(enrichedMedia, maxDimension)
                        val cached = answerImageCache.get(cacheKey)
                        if (cached != null) imageCacheHits++
                        val needsBitmap = cached == null ||
                            faceTarget?.first?.mediaStoreId == enrichedMedia.mediaStoreId
                        if (!needsBitmap) {
                            loadedEvidence += enrichedMedia to checkNotNull(cached)
                            return@forEach
                        }
                        val bitmap = runCatching {
                            loader.load(
                                enrichedMedia,
                                maxDimension = maxDimension,
                                applyExifOrientation = true,
                                exactMaxDimension = true,
                            )
                        }.onFailure { error ->
                            Log.w(TAG, "Could not decode displayed answer result R$recordNumber", error)
                        }.getOrNull() ?: throw AnswerEvidenceUnavailableException(
                            recordNumber = recordNumber,
                            detail = "its answer image could not be decoded",
                        )
                        try {
                            if (faceTarget?.first?.mediaStoreId == enrichedMedia.mediaStoreId) {
                                val occurrence = faceTarget.second
                                val orientedBounds = ImageOrientation.transformBox(
                                    occurrence.box,
                                    loader.readOrientation(enrichedMedia),
                                )
                                FaceCropper.crop(
                                    bitmap,
                                    FaceDetection(
                                        box = FaceBox(
                                            left = orientedBounds.left * bitmap.width,
                                            top = orientedBounds.top * bitmap.height,
                                            right = orientedBounds.right * bitmap.width,
                                            bottom = orientedBounds.bottom * bitmap.height,
                                        ),
                                        landmarks = FloatArray(0),
                                        score = occurrence.detectionScore,
                                    ),
                                )?.let { crop ->
                                    val boundedCrop = downscaleFaceCrop(crop)
                                    try {
                                        loadedFaceEvidence += enrichedMedia to encodeGemmaImage(boundedCrop)
                                    } finally {
                                        if (!boundedCrop.isRecycled) boundedCrop.recycle()
                                    }
                                }
                            }
                            val encoded = cached ?: encodeGemmaImage(bitmap).also { bytes ->
                                answerImageCache.put(cacheKey, bytes)
                            }
                            loadedEvidence += enrichedMedia to encoded
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
                val imageReferences = loadedEvidence.mapNotNull { (media, _) ->
                    orderedEvidence.indexOfFirst {
                        (it as? HybridSearchResult.Gallery)?.media?.mediaStoreId == media.mediaStoreId
                    }
                        .takeIf { it >= 0 }
                        ?.let { media.mediaStoreId to "R" + (it + 1) }
                }.toMap()
                val faceImageReferences = loadedFaceEvidence.mapNotNull { (media, _) ->
                    imageReferences[media.mediaStoreId]?.let { media.mediaStoreId to it }
                }.toMap()
                val documentImageReferences = loadedDocumentEvidence.mapNotNull { (match, _) ->
                    orderedEvidence.indexOfFirst {
                        (it as? HybridSearchResult.Document)?.match?.chunk?.stableId == match.chunk.stableId
                    }
                        .takeIf { it >= 0 }
                        ?.let { match.chunk.stableId to "R" + (it + 1) }
                }.toMap()
                val galleryImageBytes = loadedEvidence.associate { (media, bytes) ->
                    media.mediaStoreId to bytes
                }
                val documentImageBytes = loadedDocumentEvidence.associate { (match, bytes) ->
                    match.chunk.stableId to bytes
                }
                val imageBytes = orderedEvidence.mapNotNull { item ->
                    when (item) {
                        is HybridSearchResult.Gallery -> galleryImageBytes[item.media.mediaStoreId]
                        is HybridSearchResult.Document -> documentImageBytes[item.match.chunk.stableId]
                    }
                } + loadedFaceEvidence.map { it.second }
                val richVisualIds = loadedEvidence.map { it.first.mediaStoreId }.toSet()
                val imagePreparationMs = elapsedMs(imagePreparationStarted, System.nanoTime())
                Log.i(
                    TAG,
                    "Answer category=${groundedResponse.queryCategory.wireName}, scope=${evidenceScope.label()}, " +
                        "records=${orderedEvidence.size}, " +
                        "visualCandidates=${visualGallery.size}, decodedVisuals=${loadedEvidence.size}, " +
                        "documentPages=${loadedDocumentEvidence.size}, " +
                        "faceCrops=${loadedFaceEvidence.size}, " +
                        "images=${imageBytes.size}, " +
                        "galleryPhotoMax=$GALLERY_PHOTO_MAX_DIMENSION, " +
                        "galleryDocumentMax=$GALLERY_DOCUMENT_MAX_DIMENSION, " +
                        "documentRenderMax=$DOCUMENT_PAGE_RENDER_MAX_DIMENSION, " +
                        "imagePreparation=${imagePreparationMs}ms, cacheHits=$imageCacheHits, " +
                        "ocrTextToAnswer=false, metadata=${evidenceScope.needsMetadata}",
                )
                check(imageBytes.isNotEmpty() || orderedEvidence.isNotEmpty()) {
                    "No readable result evidence for Gemma"
                }
                val gemma = GemmaRuntime.shared(appContext)
                lastAnswerProfile = null
                val answerStarted = System.nanoTime()
                val generatedAnswer = try {
                    val answerPrompt = buildAnswerPrompt(
                        query,
                        emptyList(),
                        imageReferences = imageReferences,
                        faceImageReferences = faceImageReferences,
                        documentImageReferences = documentImageReferences,
                        evidenceScope = evidenceScope,
                        richVisualIds = richVisualIds,
                        evidenceGroups = groundedResponse.evidenceGroups,
                        orderedEvidence = orderedEvidence,
                        groundedFactValues = groundedFactValues,
                        groundedFactContext = groundedFacts.promptText(),
                    )
                    // The planner conversation contains the strict execution
                    // grammar and its previous turn. Reusing it for answers
                    // lets that syntax leak into the answer. Keep the Gemma engine
                    // resident, but create a clean answer conversation.
                    generateCheckedAnswer(
                        gemma = gemma,
                        prompt = answerPrompt,
                        images = imageBytes,
                        query = query,
                        results = attachedResults,
                        requiredValues = groundedFactValues,
                    )
                } catch (multimodalError: RuntimeException) {
                    if (imageBytes.isEmpty()) throw multimodalError
                    // Document answers intentionally depend on rendered page
                    // layout. Do not silently replace those pages with the
                    // flattened extraction that was used only for retrieval.
                    if (documentImageReferences.isNotEmpty()) throw multimodalError
                    // Some supported phone/runtime combinations reject the
                    // vision graph. A gallery-only query may still have useful
                    // person/time/place metadata, but OCR remains withheld.
                    Log.w(TAG, "Gemma vision path failed; retrying metadata-only answer", multimodalError)
                    generateCheckedAnswer(
                        gemma = gemma,
                        prompt = buildAnswerPrompt(
                            query,
                            emptyList(),
                            imageReferences = emptyMap(),
                            faceImageReferences = emptyMap(),
                            documentImageReferences = emptyMap(),
                            evidenceScope = evidenceScope,
                            richVisualIds = emptySet(),
                            evidenceGroups = groundedResponse.evidenceGroups,
                            orderedEvidence = orderedEvidence,
                            groundedFactValues = groundedFactValues,
                            groundedFactContext = groundedFacts.promptText(),
                        ),
                        images = emptyList(),
                        query = query,
                        results = attachedResults,
                        requiredValues = groundedFactValues,
                    )
                }
                val output = if (AnswerValueGrounding.matchesRequestedValueType(query, generatedAnswer.text)) {
                    generatedAnswer.text
                } else {
                    Log.e(TAG, "Answer retry returned the wrong requested value type; using safe fallback")
                    AnswerValueGrounding.typeMismatchFallback(query) ?: generatedAnswer.text
                }
                val eventGroundedOutput = EventPhotoDateGrounding.constrainAnswer(
                    query,
                    output,
                    attachedResults,
                )
                val fieldLabeledOutput = AnswerValueGrounding.constrainFieldLabel(
                    query,
                    eventGroundedOutput,
                )
                val answerGenerationMs = elapsedMs(answerStarted, System.nanoTime())
                check(fieldLabeledOutput.isNotBlank()) { "Gemma returned an empty answer" }
                val followUpStarted = System.nanoTime()
                val parsedOutput = parseAnswer(fieldLabeledOutput)
                val parsed = if (parsedOutput.text.isBlank()) {
                    Log.w(TAG, "Gemma answer became empty after public-output sanitation; using grounded fallback")
                    parsedOutput.copy(text = groundedAnswerFallback(query, attachedResults))
                } else {
                    parsedOutput
                }
                val followUps = selectDiverseFollowUps(
                    parsed.followUps + defaultFollowUps(query, attachedResults),
                    query,
                    parsed.text,
                )
                val followUpMs = elapsedMs(followUpStarted, System.nanoTime())
                onStage(AnswerPipelineStage.ACCEPTING)
                deferredFollowUps = {
                    val generatedStarted = System.nanoTime()
                    runCatching {
                        generateSuggestions(
                            gemma = gemma,
                            query = query,
                            answer = parsed.text,
                            answerRecords = attachedResults,
                        )
                    }.onSuccess { generated ->
                        onFollowUps(if (generated.followUps.isNotEmpty()) generated.followUps else followUps)
                        onNextBriefs(generated.nextBriefs)
                        Log.i(
                            TAG,
                            "Async follow-up generation: ${elapsedMs(generatedStarted, System.nanoTime())}ms, " +
                                "queries=${generated.followUps.size}, nextBriefs=${generated.nextBriefs.size}",
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Async follow-up generation failed; using fallback chips", error)
                        onFollowUps(followUps)
                    }
                }
                Log.i(
                    TAG,
                    "Answer phases: imagePreparation=${imagePreparationMs}ms, " +
                        "generation=${answerGenerationMs}ms, initial=${generatedAnswer.initialPassMs}ms, " +
                        "retry=${generatedAnswer.retryMs}ms, attempts=${generatedAnswer.attemptCount}, " +
                        "followUps=${followUpMs}ms, " +
                        "scope=${evidenceScope.label()}",
                )
                AnswerResult(
                    text = parsed.text,
                    sources = buildAnswerSources(
                        orderedEvidence,
                    ),
                    followUps = followUps,
                    timings = groundedResponse.timings
                        .withAnswerTimings(
                            answerMs = answerGenerationMs,
                            followUpPhaseMs = followUpMs,
                            imagePreparationMs = imagePreparationMs,
                            initialPassMs = generatedAnswer.initialPassMs,
                            retryMs = generatedAnswer.retryMs,
                            attemptCount = generatedAnswer.attemptCount,
                        )
                        .withAnswerProfile(lastAnswerProfile),
                    plannerJson = groundedResponse.plannerJson,
                    effectivePlanJson = groundedResponse.effectivePlanJson,
                )
                }.onFailure { error ->
                    Log.e(TAG, "Gemma answer generation failed", error)
                }
            } finally {
                plannerSession?.close()
            }
            // A visual answer cannot reuse a text-only raw prefill session:
            // takePrefilledAnswerSession(false) must close it before creating
            // the image-capable Conversation. Avoid retaining that needless
            // second KV allocation for document/scenery follow-ups.
            if (warmAnswerAfterAnswer &&
                QueryCategoryContextPolicy.answerImageLimit(response.queryCategory) == 0
            ) {
                GemmaRuntime.preloadAnswerAsync(appContext, ANSWER_SYSTEM_INSTRUCTION)
            }
            onFinished(result)
            deferredFollowUps?.invoke()
            if (!warmAnswerAfterAnswer && warmPlannerAfterAnswer) {
                GemmaRuntime.preloadPlannerAfterAnswerAsync(appContext, QueryPlannerRuntime.plannerSystemInstruction())
            }
        }
    }

    /**
     * A call-time question has one authoritative value: the call-log
     * timestamp. Do not ask the answer model to choose between that timestamp
     * and the phone number embedded in the same record text.
     */
    private fun directCommunicationAnswer(
        query: String,
        response: SearchResponse,
    ): AnswerResult? {
        val intent = CallLogQueryPolicy.detect(query) ?: return null
        val normalized = query.lowercase()
        val asksCallTime = intent.asksLatest ||
            Regex("\\b(when|qhen|date|time|last|latest|recent)\\b").containsMatchIn(normalized)
        val asksAnotherCallField = Regex(
            "\\b(duration|how\\s+long|phone\\s+number|call\\s+number|incoming|outgoing|missed|type)\\b",
        ).containsMatchIn(normalized)
        if (!asksCallTime && asksAnotherCallField) return null
        val documents = (response.documentMatches + response.mergedResults.mapNotNull {
            (it as? HybridSearchResult.Document)?.match
        })
            .filter { it.chunk.source == DocumentSource.CALL_LOGS && it.chunk.timestampMs != null }
            .distinctBy { it.chunk.stableId }
            .sortedByDescending { it.chunk.timestampMs }
        Log.i(
            TAG,
            "Direct call-time candidates=${documents.size} asksTime=$asksCallTime " +
                "responseDocs=${response.documentMatches.size} merged=${response.mergedResults.size}",
        )
        val match = documents.firstOrNull() ?: return null
        val chunk = match.chunk
        val name = chunk.title
            .takeIf { it.isNotBlank() && !Regex("^[+0-9 ()-]{7,}$").matches(it) }
            ?: Regex("(?:^|\\s)name=([^\\s]+)", RegexOption.IGNORE_CASE)
                .find(chunk.metadata)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
            ?: "the contact"
        val timestamp = formatDate(chunk.timestampMs)
        val answer = if (name == "the contact") {
            "The last matching call was on $timestamp."
        } else {
            "You called $name on $timestamp."
        }
        Log.i(TAG, "Direct call-time answer source=${chunk.recordKey} timestamp=${chunk.timestampMs}")
        return AnswerResult(
            text = answer,
            sources = buildAnswerSources(
                results = emptyList(),
                documentMatches = listOf(match),
                preferDocuments = true,
            ),
            timings = response.timings,
            plannerJson = response.plannerJson,
            effectivePlanJson = response.effectivePlanJson,
        )
    }

    /**
     * Answers a chip from the already displayed result set. This deliberately
     * does not call the planner or any retrieval index: it only reruns the
     * bounded Context Picker with the follow-up wording and invokes answer
     * generation on that new eight-record context.
     */
    fun answerFollowUpAsync(
        query: String,
        currentResponse: SearchResponse,
        onFinished: (Result<AnswerResult>) -> Unit,
        onFollowUps: (List<FollowUpSuggestion>) -> Unit = {},
        onNextBriefs: (List<NextBriefSuggestion>) -> Unit = {},
    ) {
        answerExecutor.execute {
            runCatching {
                val currentResults = (currentResponse.gallery + currentResponse.answerGallery)
                    .distinctBy { it.mediaStoreId }
                // A follow-up stays inside the current result window: no QP
                // and no global gallery search. Re-embed its full wording and
                // score only those already-visible records, blending the
                // persisted SigLIP image similarity with exact OCR evidence.
                // The Context Picker then applies its normal top-eight visual
                // diversity policy to this fresh hybrid order.
                val semanticScores = semanticIndexer.searchNearestScoredBlocking(
                    queries = listOf(query),
                    limit = currentResults.size.coerceAtLeast(1),
                    allowlist = currentResults.map { it.mediaStoreId }.toLongArray(),
                ).associate { it.mediaStoreId to it.score }
                val minSemantic = semanticScores.values.minOrNull() ?: 0f
                val maxSemantic = semanticScores.values.maxOrNull() ?: 0f
                val ocrScores = currentResults.associate { media ->
                    media.mediaStoreId to followUpOcrLineMatchScore(query, media)
                }
                val maxOcr = ocrScores.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                fun semanticScore(media: GalleryMedia): Float {
                    val raw = semanticScores[media.mediaStoreId] ?: minSemantic
                    return if (maxSemantic > minSemantic) {
                        ((raw - minSemantic) / (maxSemantic - minSemantic)).coerceIn(0f, 1f)
                    } else {
                        0.5f
                    }
                }
                val hybridRankedResults = currentResults.sortedWith(
                    compareByDescending<GalleryMedia> { media ->
                        semanticScore(media) * FOLLOW_UP_SEMANTIC_WEIGHT +
                            ((ocrScores[media.mediaStoreId] ?: 0).toFloat() / maxOcr) * FOLLOW_UP_OCR_WEIGHT
                    }.thenBy { currentResults.indexOf(it) },
                )
                val followUpScope = AnswerEvidenceScope.all()
                val followUpCategory = currentResponse.queryCategory
                // A chip such as "What date is on it?" intentionally omits
                // the ticket/merchant name. Keep the original planner OCR
                // conjunction as its evidence anchor, while the new wording
                // reranks only those existing grounded records. Falling back
                // to the current top context is safe because it was already
                // admitted by that same original conjunction.
                val inheritedOcrKeywords = currentResponse.answerOcrKeywords
                    .ifEmpty { followUpOcrKeywords(query) }
                val pickedFollowUpContext = answerContextPicker.pick(
                    query = query,
                    rankedCandidates = hybridRankedResults,
                    evidenceScope = followUpScope,
                    queryCategory = followUpCategory,
                    ocrKeywords = inheritedOcrKeywords,
                    maxRecords = MAX_ANSWER_RECORDS,
                )
                val followUpContext = if (
                    followUpCategory == QueryCategory.DOC &&
                    pickedFollowUpContext.records.isEmpty()
                ) {
                    currentResponse.answerContext?.takeIf { it.records.isNotEmpty() }
                        ?: pickedFollowUpContext
                } else {
                    pickedFollowUpContext
                }
                Log.i(
                    TAG,
                    "Follow-up local hybrid rerank: query=${query.take(MAX_NAME_PROMPT_CHARS)}, " +
                        "candidates=${currentResults.size}, semantic=${semanticScores.size}, ocrMatches=" +
                        ocrScores.values.count { it > 0 } +
                        ", anchorKeywords=${inheritedOcrKeywords.size}, selected=${followUpContext.items.size}, " +
                        "category=${followUpCategory.wireName}",
                )
                currentResponse.copy(
                    gallery = currentResults,
                    totalGalleryMatches = currentResponse.totalGalleryMatches,
                    answerGallery = followUpContext.records,
                    answerContext = followUpContext,
                    plannerSession = null,
                    queryCategory = followUpCategory,
                    needsAnswer = true,
                    answerEvidenceScope = followUpScope,
                    timings = PhaseTimings(),
                )
            }.onSuccess { followUpResponse ->
                answerAsync(
                    query = query,
                    response = followUpResponse,
                    onFinished = onFinished,
                    onFollowUps = onFollowUps,
                    onNextBriefs = onNextBriefs,
                    warmPlannerAfterAnswer = false,
                    warmAnswerAfterAnswer = true,
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not prepare follow-up answer context", error)
                onFinished(Result.failure(error))
            }
        }
    }

    fun scanBlocking(): Long = scanBlockingInternal()

    /**
     * Adds only MediaStore rows absent from the persisted gallery database.
     * Unlike the preparation/reconciliation scan, this path never updates or
     * removes an existing row, vector, OCR result, face, label, or episode.
     */
    fun scanNewMediaBlocking(): Long = scanNewMediaBlockingInternal()

    fun pendingIncrementalMediaCount(): Int = database.pendingIncrementalMediaCount()

    /**
     * Reconciles only gallery identities. This is intentionally separate from
     * the full preparation scan so deleting a photo after preparation does
     * not leave its SQLite metadata or visual vector searchable.
     */
    fun reconcileStaleRecordsAsync(onFinished: (Result<Int>) -> Unit = {}) {
        executor.execute {
            val result = runCatching { reconcileStaleRecordsBlocking() }
            onFinished(result)
        }
    }

    fun reconcileStaleRecordsBlocking(): Int {
        val activeIds = queryActiveMediaStoreIds() ?: return 0
        return removeStaleGalleryRows(activeIds)
    }

    fun indexEmbeddingsBlocking(onProgress: (EmbeddingProgress) -> Unit = {}): EmbeddingProgress =
        semanticIndexer.indexBlocking(onProgress)

    /** Appends pending per-record indexes without replacing clusters or episodes. */
    fun indexAdditionsBlocking(onProgress: (EmbeddingProgress) -> Unit = {}): EmbeddingProgress =
        semanticIndexer.indexAdditionsBlocking(onProgress)

    fun close() {
        executor.shutdown()
        retrievalExecutor.shutdown()
        previewExecutor.shutdown()
        answerExecutor.shutdown()
        semanticIndexer.close()
        DocumentVectorIndex.releaseResident()
        database.close()
    }

    private fun searchBlocking(
        query: String,
        previousQuery: String = "",
        previousAnswer: String = "",
        onQueryPlanned: (
            plannerJson: String,
            effectivePlanJson: String,
            plannerProfile: GemmaRuntime.GenerationProfile?,
        ) -> Unit = { _, _, _ -> },
    ): SearchExecution {
        // MediaStore deletion is not guaranteed to deliver a callback to the
        // app. Reconcile immediately before retrieval so a deleted gallery
        // item cannot survive in the displayed or answer evidence window.
        reconcileStaleRecordsBlocking()
        val communicationIntent = CallLogQueryPolicy.detect(query)
            ?: CallLogQueryPolicy.detectMessage(query)
        val plannerQuery = communicationIntent?.let {
            CallLogQueryPolicy.canonicalPlannerQuery(query)
        } ?: query
        val plannedQuery = QueryPlannerRuntime.planWithSession(
            appContext,
            plannerQuery,
            database.namedPersonLabelsForPlanning(),
            database.selfPersonLabelForPlanning(),
            previousQuery,
            previousAnswer,
        )
        // The planner KV is useful only for the planning turn. Answering uses
        // a clean conversation, so holding this session through SQLite,
        // native retrieval, and diversity only increases memory pressure.
        plannedQuery.session?.close()
        val resolvedSearchQuery = plannedQuery.resolvedQuery.ifBlank { plannerQuery }
        var plan = plannedQuery.plan
        val resolvedCommunicationIntent = communicationIntent?.let { intent ->
            val documentIndex = DocumentVectorIndex.shared(appContext)
            val indexedNames = when (intent.source) {
                DocumentSource.CALL_LOGS -> documentIndex.knownCallLogNames()
                DocumentSource.MESSAGES -> documentIndex.knownContactNames()
                else -> emptyList()
            }
            CallLogQueryPolicy.resolve(intent, indexedNames)
        }
        val communicationSenderNeedles = resolvedCommunicationIntent
            ?.takeIf { it.source == DocumentSource.MESSAGES }
            ?.let { intent ->
                buildList {
                    intent.resolvedPerson?.let(::add)
                    intent.fallbackPerson?.takeIf {
                        !it.equals(intent.resolvedPerson, ignoreCase = true)
                    }?.let(::add)
                    if (!intent.resolvedPerson.isNullOrBlank()) {
                        addAll(DocumentVectorIndex.shared(appContext).contactNumbersForName(intent.resolvedPerson))
                    }
                }.distinctBy { it.lowercase() }
            }
            .orEmpty()
        if (resolvedCommunicationIntent != null) {
            val intent = resolvedCommunicationIntent
            val sourceLabel = if (intent.source == DocumentSource.CALL_LOGS) "call history" else "messages"
            val person = intent.personForSearch
            // Sender identity is a hard message scope, not semantic content.
            // Keeping it out of the embedding phrase prevents a person's name
            // from making unrelated messages look semantically relevant.
            val semantic = if (intent.source == DocumentSource.MESSAGES) {
                "message conversation"
            } else {
                listOf(sourceLabel, person).filterNotNull().joinToString(" ")
            }
            val keyword = if (intent.source == DocumentSource.MESSAGES) {
                plan.keywordTerms.filterNot { term ->
                    listOfNotNull(intent.resolvedPerson, intent.fallbackPerson).any { personTerm ->
                        term.equals(personTerm, ignoreCase = true) ||
                            QuerySpellingMatcher.areClosePhrases(term, personTerm)
                    }
                }.distinctBy { it.lowercase() }
            } else {
                listOfNotNull(intent.resolvedPerson?.takeIf(String::isNotBlank))
            }
            val predicates = buildList<ExecutionNode> {
                add(ExecutionNode.Predicate(ExecutionField.MIME_TYPE, intent.source.wire))
                add(ExecutionNode.Predicate(ExecutionField.SEMANTIC, semantic))
                keyword.takeIf { it.isNotEmpty() }?.let {
                    add(ExecutionNode.Predicate(ExecutionField.KEYWORD, it.joinToString(" ")))
                }
            }
            val root = predicates.reduce { left, right ->
                ExecutionNode.Binary(left, ExecutionBinaryOperator.INTERSECT, right)
            }
            // Communication questions are private-source conversations, not
            // gallery questions. The source predicate is hard and the direct
            // newest-first path below avoids semantic neighbors from another
            // person winning the answer window.
            plan = plan.copy(
                semanticQueries = listOf(semantic),
                keywordTerms = keyword,
                personNames = emptyList(),
                excludedPersonNames = emptyList(),
                negativeSemanticQueries = emptyList(),
                assertions = emptyList(),
                timeHint = intent.timeHint,
                fromDate = "",
                toDate = "",
                locationHint = "",
                recentFirst = true,
                mediaType = if (intent.source == DocumentSource.CALL_LOGS) {
                    QueryMediaType.CALL_LOGS
                } else {
                    QueryMediaType.MESSAGES
                },
                queryCategory = QueryCategory.DOC,
                answerEvidenceScope = AnswerEvidenceScope(
                    kinds = setOf(AnswerEvidenceKind.OCR, AnswerEvidenceKind.METADATA),
                    metadataFields = setOf(AnswerMetadataField.TIME),
                ),
                executionSpec = QueryExecutionSpec(root),
            )
            Log.i(
                TAG,
                "Communication scope source=${intent.source.wire} " +
                    "person=${intent.personForSearch ?: "none"} latest=${intent.asksLatest}",
            )
        }
        // Gemma is the sole source of person predicates. The database only
        // resolves those already-validated planner values to real local face
        // labels; it never extracts an additional plan from the raw query.
        val personLabels = database.resolveNamedPersonLabels(plan.personNames)
            .distinctBy { it.lowercase() }
        val excludedPersonLabels = database.resolveNamedPersonLabels(plan.excludedPersonNames)
            .distinctBy { it.lowercase() }
        // A named-person query needs identity metadata even when the user
        // asks for time, location, or an activity. The image encoder cannot
        // tell Gemma that a face is "Ramani"; the local face tag is the
        // authoritative bridge between the query name and each G record.
        if (personLabels.isNotEmpty() || excludedPersonLabels.isNotEmpty()) {
            plan = plan.copy(
                answerEvidenceScope = plan.answerEvidenceScope.withMetadataFields(
                    setOf(AnswerMetadataField.PEOPLE),
                ),
            )
        }
        run {
            val scopedSemanticQueries = (plan.semanticQueries + plan.assertions)
                .map {
                    sanitizeSemanticQuery(
                        it,
                        personLabels + excludedPersonLabels,
                        plan.locationHint,
                        plan.mediaType,
                    )
                }
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
            val negativeSemanticQueries = plan.negativeSemanticQueries
                .filterNot { containsAnyPlanTerm(it, excludedPersonLabels) }
            val resolvedExecutionSpec = plan.executionSpec?.let { spec ->
                normalizeExecutionSpec(
                    spec = spec,
                    includedPeople = personLabels,
                    excludedPeople = excludedPersonLabels,
                    locationHint = plan.locationHint,
                    mediaType = plan.mediaType,
                )
            }
            val identitySafeExecutionSpec = resolvedExecutionSpec?.let {
                ensureIdentityOcrTerms(it, resolvedSearchQuery)
            }
            val effectivePlan = plan.copy(
                semanticQueries = scopedSemanticQueries,
                personNames = personLabels,
                excludedPersonNames = excludedPersonLabels,
                negativeSemanticQueries = negativeSemanticQueries,
                executionSpec = identitySafeExecutionSpec,
            )
            val executionSpec = effectivePlan.canonicalExecutionSpec()
            if (executionSpec == null) {
                onQueryPlanned(
                    plannedQuery.plannerJson,
                    "(empty)",
                    plannedQuery.generationProfile,
                )
                return SearchExecution(
                    candidates = emptyList(),
                    galleryCosineScores = emptyMap(),
                    plannerSession = null,
                    plannerJson = plannedQuery.plannerJson,
                    effectivePlanJson = "(empty)",
                    resolvedQuery = resolvedSearchQuery,
                    queryCategory = effectivePlan.queryCategory,
                    needsAnswer = effectivePlan.needsAnswer,
                    plannerProfile = plannedQuery.generationProfile,
                    answerEvidenceScope = effectivePlan.answerEvidenceScope,
                    ocrKeywords = effectivePlan.keywordTerms.flatMap(OcrKeywordPolicy::keywords),
                    documentMatches = emptyList(),
                )
            }
            val renderedExecutionSpec = executionSpec.render()
            // The normal OCR/doc and scenery paths attach images. A warmed
            // text-only answer session would be discarded before those image
            // turns, so reserve answer KV only for metadata-only categories.
            if (effectivePlan.needsAnswer &&
                QueryCategoryContextPolicy.answerImageLimit(effectivePlan.queryCategory) == 0
            ) {
                GemmaRuntime.preloadAnswerAsync(appContext, ANSWER_SYSTEM_INSTRUCTION)
            }
            onQueryPlanned(
                plannedQuery.plannerJson,
                renderedExecutionSpec,
                plannedQuery.generationProfile,
            )
            Log.i(TAG, "Executing QP spec: ${renderedExecutionSpec.take(600)}")
            // Category no longer routes retrieval. Every query searches the
            // gallery and all private sources, then each source applies only
            // the fields it can actually represent.
            val semanticPhrase = privateSemanticPhrase(effectivePlan, resolvedSearchQuery)
            val keywordGroups = buildPrivateKeywordGroups(effectivePlan, resolvedSearchQuery)
            val documentSources = effectivePlan.mediaType?.documentSources()
                ?: DocumentSource.entries.toSet()
            // Gallery and private retrieval use independent databases, native
            // indexes, and text encoders. Run them concurrently so
            // HYBRID_RETRIEVAL reflects the slower branch instead of their sum.
            val galleryFuture = retrievalExecutor.submit<StructuredSearchExecutor.ScoredGalleryResults> {
                structuredSearchExecutor.executeScored(
                    executionSpec,
                    allowOcrlessPhotoKeywordBypass =
                        GalleryKeywordIntersectionPolicy.allowsOcrlessPhotoBypass(
                            effectivePlan.queryCategory,
                        ),
                )
            }
            val documentFuture = retrievalExecutor.submit<List<DocumentMatch>> {
                if (documentSources.isEmpty()) {
                    emptyList()
                } else if (resolvedCommunicationIntent != null) {
                    val intent = resolvedCommunicationIntent
                    val documentIndex = DocumentVectorIndex.shared(appContext)
                    val directNeedles = buildList {
                        intent.resolvedPerson?.let(::add)
                        intent.fallbackPerson?.takeIf {
                            !it.equals(intent.resolvedPerson, ignoreCase = true)
                        }?.let(::add)
                        if (intent.source == DocumentSource.MESSAGES &&
                            !intent.resolvedPerson.isNullOrBlank()
                        ) {
                            addAll(documentIndex.contactNumbersForName(intent.resolvedPerson))
                        }
                    }
                    val direct = if (intent.source == DocumentSource.CALL_LOGS || intent.asksLatest) {
                        documentIndex.latestCommunicationMatches(
                            source = intent.source,
                            needles = if (intent.source == DocumentSource.MESSAGES) {
                                communicationSenderNeedles
                            } else {
                                directNeedles
                            },
                            limit = DOCUMENT_MATCHES_PER_SOURCE,
                            timeHint = effectivePlan.timeHint,
                            fromDate = effectivePlan.fromDate,
                            toDate = effectivePlan.toDate,
                            senderOnly = intent.source == DocumentSource.MESSAGES,
                        )
                    } else {
                        emptyList()
                    }
                    if (direct.isNotEmpty()) {
                        direct
                    } else {
                        documentIndex.search(
                            query = semanticPhrase,
                            keywordGroups = keywordGroups,
                            sources = documentSources,
                            limitPerSource = DOCUMENT_MATCHES_PER_SOURCE,
                            timeHint = effectivePlan.timeHint,
                            fromDate = effectivePlan.fromDate,
                            toDate = effectivePlan.toDate,
                            mediaType = effectivePlan.mediaType,
                            senderNeedles = communicationSenderNeedles,
                        )
                    }
                } else {
                    DocumentVectorIndex.shared(appContext).search(
                        query = semanticPhrase,
                        keywordGroups = keywordGroups,
                        sources = documentSources,
                        limitPerSource = DOCUMENT_MATCHES_PER_SOURCE,
                        timeHint = effectivePlan.timeHint,
                        fromDate = effectivePlan.fromDate,
                        toDate = effectivePlan.toDate,
                        mediaType = effectivePlan.mediaType,
                    )
                }
            }
            val scoredCandidates = galleryFuture.get()
            val documentMatches = documentFuture.get()
            val candidates = scoredCandidates.media
            return SearchExecution(
                candidates = candidates,
                galleryCosineScores = scoredCandidates.cosineScores,
                plannerSession = null,
                plannerJson = plannedQuery.plannerJson,
                effectivePlanJson = renderedExecutionSpec,
                resolvedQuery = resolvedSearchQuery,
                queryCategory = effectivePlan.queryCategory,
                needsAnswer = effectivePlan.needsAnswer,
                plannerProfile = plannedQuery.generationProfile,
                answerEvidenceScope = effectivePlan.answerEvidenceScope,
                ocrKeywords = effectivePlan.keywordTerms.flatMap(OcrKeywordPolicy::keywords),
                documentMatches = documentMatches,
            )
        }
    }

    private fun buildPrivateKeywordGroups(plan: QueryPlan, query: String): List<List<String>> = buildList {
        val identityDocument = IdentityDocumentQueryPolicy.match(query)
        if (identityDocument != null) {
            val namedPeople = (
                database.namedPersonLabelsMentioned(query) +
                    ExpiryKeywordPolicy.filter(
                        query,
                        plan.keywordTerms.flatMap(OcrKeywordPolicy::keywords),
                    )
            ).filterNot {
                it.lowercase() in IDENTITY_KEYWORD_STOP_WORDS
            }.distinctBy { it.lowercase() }
            // Identity queries require the named subject when one is present.
            // `number` is intentionally OCR-only; it is far too generic for
            // lexical document retrieval.
            add((identityDocument.keywordAnchor.split(' ') + namedPeople).distinct())
        } else {
            // Document lexical retrieval is one AND group. Person/location
            // are metadata fields for gallery, but plain text sources expose
            // them only through their indexed title/text/metadata.
            val terms = (
                ExpiryKeywordPolicy.filter(
                    query,
                    plan.keywordTerms.flatMap(OcrKeywordPolicy::keywords),
                ) +
                    plan.personNames.flatMap(OcrKeywordPolicy::keywords) +
                    OcrKeywordPolicy.keywords(plan.locationHint)
                ).filterNot {
                    it.lowercase() in IDENTITY_KEYWORD_STOP_WORDS ||
                        it.lowercase() in SearchKeywordPolicy.genericRecordWords
                }
                    .distinctBy { it.lowercase() }
                terms.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    /** One bounded relevance window shared by gallery and private sources. */
    private fun mergeGlobalSearchWindow(
        gallery: List<GalleryMedia>,
        documents: List<DocumentMatch>,
    ): GlobalSearchWindow {
        val galleryHits = gallery.mapIndexed { index, media ->
            GlobalSearchHit(score = 1f / (index + 1f), gallery = media)
        }
        val maxDocumentScore = documents.maxOfOrNull { it.fusionScore }?.coerceAtLeast(1.0e-6f) ?: 1f
        val documentHits = documents.filter { PersonalFileSearchPolicy.isEligible(it.chunk) }.map { match ->
            GlobalSearchHit(
                score = (match.fusionScore / maxDocumentScore).coerceIn(0f, 1f),
                document = match,
            )
        }
        val selected = (galleryHits + documentHits)
            .sortedByDescending(GlobalSearchHit::score)
            .take(GLOBAL_RESULT_LIMIT)
        val ordered = selected.mapNotNull { hit ->
            when {
                hit.gallery != null -> HybridSearchResult.Gallery(hit.gallery)
                hit.document != null -> HybridSearchResult.Document(hit.document)
                else -> null
            }
        }
        return GlobalSearchWindow(
            gallery = selected.mapNotNull(GlobalSearchHit::gallery),
            documents = selected.mapNotNull(GlobalSearchHit::document),
            ordered = ordered,
        )
    }

    private fun ensureIdentityOcrTerms(spec: QueryExecutionSpec, query: String): QueryExecutionSpec {
        val identity = Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e)\\b")
            .find(query)?.value ?: return spec
        fun rewrite(node: ExecutionNode): ExecutionNode = when (node) {
            is ExecutionNode.Predicate -> if (
                node.field == ExecutionField.OCR &&
                Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e|identity)\\b")
                    .containsMatchIn(node.value)
            ) {
                val subject = node.value
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter(String::isNotBlank)
                    .filterNot { it.lowercase() in IDENTITY_OCR_STOP_WORDS }
                    .distinctBy { it.lowercase() }
                    .take(3)
                ExecutionNode.Predicate(
                    ExecutionField.OCR,
                    (subject + identity + "number").distinctBy { it.lowercase() }.joinToString(" "),
                )
            } else node
            is ExecutionNode.Sorted -> ExecutionNode.Sorted(rewrite(node.value), node.sort)
            is ExecutionNode.Binary -> ExecutionNode.Binary(rewrite(node.left), node.operator, rewrite(node.right))
        }
        return QueryExecutionSpec(rewrite(spec.root))
    }

    /** Existing private retrieval plus a fallback semantic branch when QP emits none. */
    private fun privateSemanticPhrase(plan: QueryPlan, query: String): String {
        val planned = plan.semanticQueries.joinToString(" ").trim()
        if (planned.isNotBlank()) return planned
        return QueryLifecycleScaffoldingPolicy.strip(query)
            .replace(
                Regex(
                    "(?i)\\b(?:today|yesterday|tomorrow|tonight|now|recent|latest|last|this|previous|next|" +
                        "year|years|month|months|week|weeks|day|days|morning|evening|night|" +
                        "before|after|during)\\b",
                ),
                " ",
            )
            .replace(Regex("\\b\\d{4}(?:[-/]\\d{1,2}(?:[-/]\\d{1,2})?)?\\b"), " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
            .filterNot {
                it.lowercase() in SearchKeywordPolicy.forbiddenScaffoldingWords ||
                    it.lowercase() in setOf("i", "me", "my", "mine", "myself")
            }
            .joinToString(" ")
            .ifBlank { "all" }
    }

    private fun buildAnswerPrompt(
        query: String,
        results: List<GalleryMedia>,
        imageReferences: Map<Long, String>,
        faceImageReferences: Map<Long, String> = emptyMap(),
        documentImageReferences: Map<Long, String> = emptyMap(),
        evidenceScope: AnswerEvidenceScope,
        richVisualIds: Set<Long> = emptySet(),
        evidenceGroups: List<EvidenceGroup> = emptyList(),
        orderedEvidence: List<HybridSearchResult> = emptyList(),
        groundedFactValues: List<String> = emptyList(),
        groundedFactContext: String = "none",
    ): String {
        val includeImages = imageReferences.isNotEmpty() || documentImageReferences.isNotEmpty()
        val visualRecordOrder = (imageReferences.values + documentImageReferences.values)
            .distinct()
            .sortedBy { it.removePrefix("R").toIntOrNull() ?: Int.MAX_VALUE }
        val evidenceRows = results.mapIndexed { index, media ->
            val label = "G${index + 1}"
            val fields = buildString {
                val richVisual = media.mediaStoreId in richVisualIds
                append(if (richVisual) "visual=tile" else "visual=none")
                val personTag = media.personLabel?.takeIf(String::isNotBlank)
                if (personTag != null || evidenceScope.needsPeopleMetadata) {
                    append(" person=${personTag ?: "none"}")
                }
                if (richVisual) {
                    append(" media_type=${if (media.mimeType.startsWith("video/", ignoreCase = true)) "video" else "photo"}")
                    if (media.durationMs > 0L) append(" duration=${formatDuration(media.durationMs)}")
                }
                if (evidenceScope.needsTimeMetadata) {
                    val captureTime = formatDate(media.dateTakenMs)
                    append(
                        if (captureTime != "none") {
                            " time=$captureTime"
                        } else {
                            " time=${formatModifiedDate(media.dateModifiedSeconds)} (modified)"
                        },
                    )
                }
                if (evidenceScope.needsLocationMetadata) {
                    append(" place=${formatLocation(media)}")
                }
                if (evidenceScope.needsMetadata &&
                    !evidenceScope.needsTimeMetadata &&
                    !evidenceScope.needsLocationMetadata &&
                    !evidenceScope.needsPeopleMetadata
                ) {
                    // Defensive fallback if a future metadata channel is
                    // added without a field mapping.
                    append(" time=${formatDate(media.dateTakenMs)}")
                    append(" place=${formatLocation(media)}")
                    append(" person=${media.personLabel ?: "none"}")
                }
            }
            label to fields
        }
        // Collapse exact duplicate structured rows while retaining their join
        // labels. This removes redundant structure without truncating any
        // OCR or metadata text from the surviving row.
        val evidence = evidenceRows
            .groupBy(keySelector = { it.second }, valueTransform = { it.first })
            .entries
            .joinToString("\n") { (fields, labels) ->
                "${labels.joinToString(",")} $fields"
            }
        val episodeEvidence = evidenceGroups.mapIndexedNotNull { index, group ->
            val resultIndex = results.indexOfFirst {
                it.mediaStoreId == group.representative.mediaStoreId
            }
            val reference = if (resultIndex >= 0) {
                "G${resultIndex + 1}"
            } else {
                "episode representative"
            }
            val location = if (resultIndex >= 0) {
                formatLocation(results[resultIndex])
            } else {
                metadataReader.displayLocation(group.location) ?: "none"
            }
            "E${index + 1} representative=$reference episode_records=${group.memberCount} " +
                "query_matches=${group.matchedMemberCount} " +
                "start=${formatDate(group.startTimeMs)} end=${formatDate(group.endTimeMs)} " +
                "location=$location"
        }
        val answerDirective = answerDirective(query, includeImages)
        val groundedFactBlock = if (groundedFactValues.isNotEmpty()) {
            """
            LOCALLY_MATCHED_LABELLED_FACTS:
            $groundedFactContext
            These label/value pairs were selected generically by matching the QUESTION to field labels and record anchors. Re-check every pair against its full R record and any paired image. Include every distinct value that answers the same requested attribute, but reject a pair if the full record shows that its label, subject, document type, or scope does not match the question.
            """.trimIndent()
        } else {
            "LOCALLY_MATCHED_LABELLED_FACTS: none"
        }
        val inputInstruction = when {
            includeImages -> buildString {
                append("IMAGE INPUTS follow exact top-eight RECORD order: ")
                append(visualRecordOrder.joinToString(", "))
                append(". Gallery inputs retain a high-resolution bounded aspect-ratio image; file inputs are rendered matching pages. Use every image with its mapped record; read columns, rows, tables, labels, and nearby values directly. No gallery OCR or extracted file text is supplied.")
                if (faceImageReferences.isNotEmpty()) {
                    append(" One additional face crop follows all ranked record images and maps to ")
                    append(faceImageReferences.values.single())
                    append("; use it only as an identity cue for that same record.")
                }
            }
            evidenceScope.needsVisual ->
                "IMAGE INPUTS: unavailable. Do not make scene/activity claims."
            else ->
                "METADATA ONLY: person/time/place fields do not prove a visible scene or activity."
        }
        val selectedFields = buildList {
            if (evidenceScope.needsPeopleMetadata) add("person")
            if (evidenceScope.needsTimeMetadata) add("time")
            if (evidenceScope.needsLocationMetadata) add("place")
            if (orderedEvidence.any {
                    it is HybridSearchResult.Document && it.match.chunk.source != DocumentSource.FILES
                }
            ) {
                add("native phone-source text")
            }
        }.joinToString(", ").ifBlank { "visual" }
        val episodeBlock = if (episodeEvidence.isNotEmpty()) {
            "\nEPISODES:\n${episodeEvidence.joinToString("\n")}\n" +
                "Count E rows, not duplicate photos, for event-count questions; call them candidate episodes unless visually confirmed."
        } else {
            ""
        }
        val orderedRecordBlock = orderedEvidence.mapIndexed { index, item ->
            val label = "R" + (index + 1)
            when (item) {
                is HybridSearchResult.Gallery -> buildString {
                    val media = item.media
                    append(label)
                    append(" GALLERY_RECORD")
                    append(" display_name=")
                    append(media.displayName.ifBlank { "none" })
                    append(" media_type=")
                    append(if (media.mimeType.startsWith("video/", ignoreCase = true)) "video" else "photo")
                    append(" content_class=")
                    append(media.contentClass.wireName)
                    if (media.durationMs > 0L) {
                        append(" duration=")
                        append(formatDuration(media.durationMs))
                    }
                    append(" captured=")
                    append(
                        media.dateTakenMs?.let(::formatDate)
                            ?: formatModifiedDate(media.dateModifiedSeconds) + " (modified)",
                    )
                    append(" location=")
                    append(formatLocation(media))
                    append(" person=")
                    append(media.personLabel ?: "none")
                    append(" image=")
                    append(if (media.mediaStoreId in richVisualIds) "attached" else "unavailable")
                }
                is HybridSearchResult.Document -> buildString {
                    val chunk = item.match.chunk
                    append(label)
                    append(" PRIVATE_RECORD source=")
                    append(chunk.source.displayName)
                    append(" title=")
                    append(chunk.title.ifBlank { "none" })
                    append(" page=")
                    append(chunk.page ?: "none")
                    append(" time=")
                    append(formatDate(chunk.timestampMs))
                    if (chunk.source == DocumentSource.FILES) {
                        append(
                            if (chunk.stableId in documentImageReferences) {
                                "\nPAGE_IMAGE: attached; read the rendered page, not flattened extraction"
                            } else {
                                "\nPAGE_IMAGE: unavailable; extracted file text is intentionally withheld"
                            },
                        )
                    } else {
                        append("\nMETADATA:\n")
                        append(compactPromptText(chunk.metadata).ifBlank { "none" })
                        append("\nCONTENT_FULL:\n")
                        append(chunk.text.ifBlank { "none" })
                    }
                }
            }
        }.joinToString("\n\n")
        val prompt = """
            ANSWER_TASK:
            QUESTION: $query
            MODE: $answerDirective
            INPUT: $inputInstruction
            TRUSTED FIELDS: $selectedFields. Local person tags are authoritative; use capture time before a modified fallback; explicitly name relevant places.
            REQUESTED FIELD LABEL: ${AnswerValueGrounding.fieldLabelInstruction(query)}
            REQUESTED VALUE TYPE: ${AnswerValueGrounding.requestedValueTypeInstruction(query)}
            REQUESTED FACT RULE: ${AnswerFactGrounding.requestedFieldInstruction(query)}
            $groundedFactBlock
            RECORDS:
            $evidence
            ORDERED_TOP_EIGHT_RECORDS:
            $orderedRecordBlock
            $episodeBlock
            Use the QUESTION and all ORDERED_TOP_EIGHT_RECORDS together to answer. Consider all supplied records and attached images before deciding. Return only a direct, natural answer in at most $MAX_ANSWER_GENERATED_TOKENS generated tokens, using as few words as possible. Do not mention records, evidence, OCR, metadata, images, sources, provenance, reasoning, or whether information is present or absent. Do not say that a word or value is or is not contained in the supplied material. Do not calculate or invent values. If multiple distinct values directly answer the question, state them briefly. Do not ask a follow-up question or repeat the question.
            ANSWER:
        """.trimIndent()
        Log.i(
            TAG,
            "Answer prompt: chars=${prompt.length}, records=${results.size}, " +
                "rows=${evidence.lineSequence().count()}, images=${imageReferences.size}, " +
                "episodes=${episodeEvidence.size}, " +
                "galleryOcrText=false, fileExtractedText=false",
        )
        return prompt
    }

    /** Grounded context passed to the combined Try next / Next Brief call. */
    private fun buildNextBriefEvidence(results: List<GalleryMedia>): String = results
        .take(MAX_ANSWER_RECORDS)
        .mapIndexed { index, media ->
            buildString {
                append("G${index + 1}: ")
                media.ocrText
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_NEXT_BRIEF_OCR_CHARS)
                    .takeIf(String::isNotBlank)
                    ?.let { append("ocr=$it; ") }
                media.personLabel?.trim()?.takeIf(String::isNotBlank)?.let { append("; person=$it") }
                media.locationName?.trim()?.takeIf(String::isNotBlank)?.let { append("; place=$it") }
                media.location?.trim()?.takeIf(String::isNotBlank)?.let { append("; location=$it") }
                media.dateTakenMs?.let { append("; captured=${formatDate(it).substringBefore(' ')}") }
            }
        }
        .joinToString("\n")
        .take(MAX_NEXT_BRIEF_CONTEXT_CHARS)
        .ifBlank { "none" }

    private fun generateSuggestions(
        gemma: GemmaRuntime,
        query: String,
        answer: String,
        answerRecords: List<GalleryMedia>,
    ): GeneratedSuggestions {
        val session = gemma.createAnswerConversation(SUGGESTIONS_SYSTEM_INSTRUCTION)
        return try {
            val groundedContext = buildNextBriefEvidence(answerRecords)
            val capabilities = discoverNextBriefCapabilities()
            val output = session.generate(
                """
                ORIGINAL QUESTION: $query
                ANSWER ALREADY GIVEN: $answer
                GROUNDED ANSWER RECORDS:
                $groundedContext
                AVAILABLE PHONE CAPABILITIES:
                ${capabilities.joinToString("\n") { "${it.id}: ${it.label} (handlers=${it.handlers})" }}
                """.trimIndent(),
            )
            val followUps = ArrayList<FollowUpSuggestion>()
            val nextBriefs = ArrayList<NextBriefSuggestion>()
            output.lineSequence().map(String::trim).forEach { line ->
                when {
                    line.startsWith("QUERY:", ignoreCase = true) -> {
                        line.substringAfter(':')
                            .let(::sanitizeFollowUpQuery)
                            .let { FollowUpSuggestionPolicy.compactForDisplay(it, query) }
                            .takeIf { isUsefulFollowUp(it, query, answer) }
                            ?.takeIf(String::isNotBlank)
                            ?.let { followUps += FollowUpSuggestion(it) }
                    }
                    line.startsWith("ACTION:", ignoreCase = true) -> {
                        val fields = line.substringAfter(':').split('|', limit = 4)
                        if (fields.size >= 4) {
                            val action = runCatching {
                                NextBriefActionType.valueOf(fields[0].trim().uppercase())
                            }.getOrNull()
                            val label = fields[1].trim().take(MAX_NEXT_BRIEF_LABEL_CHARS)
                            val sourceId = fields[2].trim().uppercase()
                            val payload = fields.getOrNull(3).orEmpty()
                                .trim()
                                .replace(Regex("\\s+"), " ")
                                .take(MAX_NEXT_BRIEF_PAYLOAD_CHARS)
                            if (action != null && label.isNotBlank() &&
                                capabilities.any { it.id == action.name.lowercase() } &&
                                isGroundedNextBriefAction(
                                    action = action,
                                    sourceId = sourceId,
                                    records = answerRecords,
                                    payload = payload,
                                    capabilities = capabilities,
                                    query = query,
                                    answer = answer,
                                )
                            ) {
                                nextBriefs += NextBriefSuggestion(action, label, sourceId, payload)
                            }
                        }
                    }
                }
            }
            GeneratedSuggestions(
                followUps = selectDiverseFollowUps(followUps, query, answer),
                nextBriefs = nextBriefs.distinctBy { it.action to it.sourceId }.take(MAX_NEXT_BRIEFS),
            )
        } finally {
            session.close()
        }
    }

    private fun isGroundedNextBriefAction(
        action: NextBriefActionType,
        sourceId: String,
        records: List<GalleryMedia>,
        payload: String,
        capabilities: List<NextBriefCapability>,
        query: String,
        answer: String,
    ): Boolean {
        val index = sourceId.removePrefix("G").toIntOrNull()?.minus(1) ?: return false
        val media = records.getOrNull(index) ?: return false
        if (payload.length < 3) return false
        val taskText = "$answer ${media.ocrText} $payload"
            .lowercase()
            .replace(Regex("\\s+"), " ")
        return when (action) {
            NextBriefActionType.SHARE_MEDIA -> {
                val person = media.personLabel.orEmpty().trim()
                val hasPerson = person.isNotBlank() &&
                    person.lowercase() !in setOf("person", "unknown", "unnamed")
                val hasContact = extractContactTargets(media).isNotEmpty()
                val hasMessagingApp = capabilities
                    .filter { it.id == NextBriefActionType.SEND_MESSAGE.name.lowercase() }
                    .any { handlers ->
                        handlers.handlers.lowercase().let { names ->
                            names.contains("whatsapp") || names.contains("messag") || names.contains("chat")
                        }
                    }
                hasPerson && (hasContact || hasMessagingApp)
            }
            NextBriefActionType.CALENDAR_REMINDER -> Regex(
                "\\b(today|tomorrow|tonight|morning|evening|deadline|expiry|expires|" +
                    "appointment|booking|ticket|show|event|at \\d{1,2}(?::\\d{2})?)\\b",
            ).containsMatchIn(taskText)
            NextBriefActionType.MAPS_SEARCH -> {
                val hasPlace = !media.locationName.isNullOrBlank() || !media.location.isNullOrBlank()
                hasPlace && Regex("\\b(direction|directions|map|maps|route|where|nearby|go to|visit|arrive)\\b")
                    .containsMatchIn(taskText)
            }
            NextBriefActionType.CONTACT -> extractContactTargets(media).isNotEmpty()
            NextBriefActionType.CONTINUE_WEB_TASK -> {
                val continuationCue = Regex(
                    "\\b(book again|rebook|manage booking|check booking|check[- ]?in|reserve|" +
                        "buy|purchase|availability|open booking|continue|go to)\\b",
                ).containsMatchIn(taskText)
                val taskCue = Regex(
                    "\\b(hotel|flight|train|ticket|reservation|booking|itinerary|trip|" +
                        "restaurant|concert|event|show|travel)\\b",
                ).containsMatchIn(taskText)
                continuationCue && taskCue
            }
            NextBriefActionType.SEND_MESSAGE -> {
                val hasPerson = !media.personLabel.isNullOrBlank()
                hasPerson && extractContactTargets(media).isNotEmpty()
            }
        }
    }

    /** Discover standard phone actions and the installed apps that can receive them. */
    private fun discoverNextBriefCapabilities(): List<NextBriefCapability> {
        val packageManager = appContext.packageManager
        fun capability(id: String, label: String, intent: Intent): NextBriefCapability? {
            val handlers = runCatching {
                packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                )
            }.getOrDefault(emptyList())
            if (handlers.isEmpty()) return null
            val names = handlers.asSequence()
                .mapNotNull { info -> info.loadLabel(packageManager)?.toString()?.trim() }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_CAPABILITY_HANDLERS)
                .joinToString(", ")
            return NextBriefCapability(id, label, names.ifBlank { "available" })
        }

        return listOfNotNull(
            capability(
                NextBriefActionType.SHARE_MEDIA.name.lowercase(),
                "share selected gallery media",
                Intent(Intent.ACTION_SEND).setType("image/*"),
            ),
            capability(
                NextBriefActionType.MAPS_SEARCH.name.lowercase(),
                "open a place in maps",
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Ask+Galaxy")),
            ),
            capability(
                NextBriefActionType.CALENDAR_REMINDER.name.lowercase(),
                "create a calendar reminder",
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI),
            ),
            capability(
                NextBriefActionType.CONTINUE_WEB_TASK.name.lowercase(),
                "continue an explicit booking, purchase, or reservation task",
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=Ask+Galaxy")),
            ),
            capability(
                NextBriefActionType.CONTACT.name.lowercase(),
                "call or email a detected contact",
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
            ),
            capability(
                NextBriefActionType.SEND_MESSAGE.name.lowercase(),
                "send a message or share text",
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            ),
        )
    }

    private fun extractContactTargets(media: GalleryMedia): List<String> {
        val text = media.ocrText
        val emails = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
        val phones = Regex("(?<!\\d)\\+?[0-9][0-9 ()-]{6,}[0-9](?!\\d)")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.count(Char::isDigit) >= 7 }
        return (emails + phones).distinct().take(MAX_CONTACT_TARGETS).toList()
    }

    private fun isUsefulFollowUp(value: String, previousQuery: String, previousAnswer: String): Boolean {
        val normalized = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (normalized.length < 8 || normalized == previousQuery.lowercase().trim()) return false
        if (!FollowUpSuggestionPolicy.isCompatible(value, previousQuery)) return false
        if (Regex("\\b(?:tell me more|more details|what else|nearby|about it)\\b").containsMatchIn(normalized)) {
            return false
        }
        val previousTokens = followUpMeaningfulTokens(previousQuery)
        val candidateTokens = followUpMeaningfulTokens(normalized)
        if (candidateTokens.isEmpty()) return false
        val novelIntent = candidateTokens
            .minus(previousTokens)
            .any { it in FOLLOW_UP_DIVERSITY_DIMENSIONS }
        val shared = candidateTokens.intersect(previousTokens).size
        val overlap = shared.toFloat() / candidateTokens.size.coerceAtLeast(1)
        // Keep the person/document anchor, but require a new question
        // dimension instead of accepting a lightly reworded original query.
        if (!novelIntent && overlap >= 0.5f) return false
        if (candidateTokens.size < 2 && previousAnswer.isBlank()) return false
        return true
    }

    private fun selectDiverseFollowUps(
        candidates: List<FollowUpSuggestion>,
        previousQuery: String,
        previousAnswer: String,
    ): List<FollowUpSuggestion> {
        val selected = ArrayList<FollowUpSuggestion>(MAX_FOLLOW_UPS)
        candidates.forEach { candidate ->
            val text = sanitizeFollowUpQuery(candidate.text)
                .let { FollowUpSuggestionPolicy.compactForDisplay(it, previousQuery) }
            if (text.isBlank() || !isUsefulFollowUp(text, previousQuery, previousAnswer)) return@forEach
            val tokens = followUpMeaningfulTokens(text)
            val tooSimilar = selected.any { existing ->
                val existingTokens = followUpMeaningfulTokens(existing.text)
                val union = (tokens + existingTokens).toSet().size.coerceAtLeast(1)
                tokens.intersect(existingTokens).size.toFloat() / union >= 0.65f
            }
            if (!tooSimilar) selected += candidate.copy(text = text)
        }
        return selected.distinctBy { it.text.lowercase() }.take(MAX_FOLLOW_UPS)
    }

    private fun followUpMeaningfulTokens(value: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(value.lowercase())
            .map { it.value }
            .filterNot { it in FOLLOW_UP_DIVERSITY_STOP_WORDS }
            .toSet()

    private fun answerDirective(query: String, includeImages: Boolean): String {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        return when {
            CallLogQueryPolicy.detect(query) != null ->
                "CALL HISTORY TASK: use only the matching call-log entries. For when/last/latest questions, choose the newest matching call by its stored timestamp and state the date and time directly. Do not confuse call duration, phone number, or call type with the requested time."
            CallLogQueryPolicy.detectMessage(query) != null ->
                "MESSAGE HISTORY TASK: use only the matching Messages/SMS entries. For last/latest questions, choose the newest matching message and summarize its subject directly from the message text. Use its stored timestamp only when the user asks when; do not confuse the sender number with the message content."
            Regex("\\b(how much|spend|spent|cost|price|amount|total|paid)\\b")
                .containsMatchIn(normalized) ->
                "DOCUMENT AMOUNT TASK: prioritize the image or rendered page matching the requested title or merchant. Read visible labels and their nearby values together; prefer Total Amount or Grand Total over component fees or per-ticket prices. State the amount directly."
            AnswerValueGrounding.isIdentityExpiryDateQuestion(normalized) ->
                identityExpiryDateDirective(normalized)
            Regex("\\b(?:aadhaar|aadhar|passport|pan|ssn|licen[cs]e|identity)\\b").containsMatchIn(normalized) &&
                Regex("\\b(?:number|no\\.?|num)\\b").containsMatchIn(normalized) ->
                identityNumberDirective(normalized)
            Regex("\\b(?:n?policy|insurance)\\b").containsMatchIn(normalized) &&
                Regex("\\b(?:number|no\\.?|num)\\b").containsMatchIn(normalized) ->
                "INSURANCE POLICY NUMBER TASK: use the matching named-person policy records. Prefer a currently valid policy over an explicitly expired historical policy unless the user asks for an old or expired policy. If multiple matching current policies are supplied, state each policy number briefly. Do not choose a merely first-ranked unrelated gallery photo over matching policy files."
            AnswerReviewGate.isDirectFieldQuestion(query) ->
                "DIRECT DOCUMENT FIELD TASK: identify the exact attribute requested by the question, match it to the closest explicit field label in every relevant record, and answer with the value attached to that label. Treat neighbouring labels as different facts even when their values have the same format. Use all matching records; do not substitute capture time, modified time, another date, another number, or another document field."
            Regex("\\b(when|date|dated|time)\\b").containsMatchIn(normalized) &&
                Regex("\\b(go|went|doing|activity|swim|swimming|eat|eating|drink|drinking|run|running|play|playing|work|working|visit|visiting)\\b")
                    .containsMatchIn(normalized) ->
                if (includeImages) {
                    "ACTIVITY TIMELINE TASK: start with the strongest supported capture date(s) or closest event, then state whether the named activity is confirmed, likely, or only adjacent. Use the attached scenery images to make that distinction."
                } else {
                    "ACTIVITY TIMELINE TASK: answer only from OCR, capture time, place, and person tags. Do not turn person presence into proof of an activity."
                }
            Regex("\\b(when|date|dated|born|birth|birthdate|birthday|year|today|yesterday)\\b")
                .containsMatchIn(normalized) ->
                if (EventPhotoDateGrounding.applies(query)) {
                    "EVENT PHOTO DATE TASK: treat capture_time on the matched event photos as the event date. For birthday, anniversary, wedding, party, or celebration questions, answer directly from the matching photo capture date(s), even without a printed date. State every distinct matched event day; do not call it unsupported or a mere candidate."
                } else {
                    "DATE/TIMELINE TASK: rank the strongest matching dates from capture_time, visible page/image content, or native phone-source text. Do not use the earliest record as a birthdate proxy. Distinguish an exact printed date from a photo-event date; if the exact fact is not stored, give the strongest supported candidate or shortlist."
                }
            Regex("\\b(where|location|place)\\b").containsMatchIn(normalized) ->
                "LOCATION TASK: answer with the strongest supplied location details and explicitly name the place. If only GPS text or a partial place is available, present it naturally rather than discarding it."
            Regex("\\b(who|whose|person|people)\\b").containsMatchIn(normalized) ->
                "IDENTITY TASK: use local person tags as the authoritative identity link and answer only from person/place/time metadata, attached visuals, and native phone-source text."
            Regex("\\b(how many|count|number of|most|least)\\b").containsMatchIn(normalized) ->
                "COUNT/RANK TASK: count or rank only the scoped records and explain the basis briefly; do not replace a count with a generic evidence disclaimer."
            Regex("\\b(compare|comparison|difference|versus| vs )\\b").containsMatchIn(normalized) ->
                "COMPARISON TASK: compare the relevant groups or time periods directly using the supplied records, calling out missing or asymmetric evidence."
            Regex("\\b(without|excluding|except|not|no)\\b").containsMatchIn(normalized) ->
                "EXCLUSION TASK: answer from the positive set after applying the subtraction and state what remains instead of only describing the excluded set."
            else ->
                if (includeImages) {
                    "GENERAL VISUAL TASK: synthesize the high-resolution bounded gallery images and rendered document pages with their joined metadata into a useful direct answer."
                } else {
                    "GENERAL TEXT TASK: synthesize only the strongest native phone-source text and metadata into a useful direct answer."
                }
        }
    }

    private fun identityNumberDirective(normalized: String): String {
        val requested = when {
            Regex("\\b(?:aadhaar|aadhar)\\b").containsMatchIn(normalized) -> "Aadhaar number"
            Regex("\\bpassport\\b").containsMatchIn(normalized) -> "passport number"
            Regex("\\b(?:licen[cs]e|driving)\\b").containsMatchIn(normalized) -> "licence number"
            else -> "identity number"
        }
        return "IDENTITY NUMBER TASK: answer the requested $requested exactly from the matching identity record. " +
            "Preserve the requested field label: never call an Aadhaar, passport, licence, or identity number a phone, mobile, contact, or telephone number. " +
            "Ignore nearby unrelated phone numbers unless the question explicitly asks for a phone number. State the exact value directly."
    }

    private fun identityExpiryDateDirective(normalized: String): String {
        val document = when {
            Regex("\\bpassport\\b").containsMatchIn(normalized) -> "passport"
            Regex("\\b(?:licen[cs]e|driving)\\b").containsMatchIn(normalized) -> "licence"
            else -> "identity document"
        }
        return "IDENTITY EXPIRY DATE TASK: inspect all matching top-eight images/pages and find the $document field visibly labelled Date of Expiry, Expiry Date, Expiration Date, Valid Until, Valid Till, or equivalent. Return that calendar date only. Never answer with the passport/document number, MRZ line, phone number, date of issue, date of birth, capture time, modified time, or any uninterrupted long identifier. If the expiry date cannot be read reliably, do not guess."
    }

    private fun generateGroundedAnswer(
        gemma: GemmaRuntime,
        prompt: String,
        images: List<ByteArray>,
        usePrefilledAnswer: Boolean = true,
    ): AnswerGenerationPass {
        val session = if (usePrefilledAnswer) {
            GemmaRuntime.takePrefilledAnswerSession(allowTextOnlyRawSession = images.isEmpty())
                ?: gemma.createAnswerConversation(ANSWER_SYSTEM_INSTRUCTION)
        } else {
            gemma.createAnswerConversation(ANSWER_SYSTEM_INSTRUCTION)
        }
        return try {
            val started = System.nanoTime()
            val output = session.generate(prompt, images)
            val wallMs = elapsedMs(started, System.nanoTime())
            val profile = session.lastGenerationProfile
            profile?.takeIf { it.decodeTokens > MAX_ANSWER_GENERATED_TOKENS }?.let {
                Log.w(
                    TAG,
                    "Gemma answer exceeded the requested $MAX_ANSWER_GENERATED_TOKENS-token budget: " +
                        "decoded=${it.decodeTokens}",
                )
            }
            AnswerGenerationPass(output, wallMs, profile)
        } finally {
            session.close()
        }
    }

    private fun generateCheckedAnswer(
        gemma: GemmaRuntime,
        prompt: String,
        images: List<ByteArray>,
        query: String,
        results: List<GalleryMedia>,
        requiredValues: List<String> = emptyList(),
    ): CheckedAnswerGeneration {
        val profiles = ArrayList<GemmaRuntime.GenerationProfile>(2)
        val initial = generateGroundedAnswer(gemma, prompt, images)
        initial.profile?.let(profiles::add)
        var generated = initial.text
        var retryMs = 0L
        var attemptCount = 1
        fun needsRetry(value: String): Boolean =
            looksLikePlannerOutput(value) ||
                AnswerOutputGuard.needsRetry(value, query) ||
                !AnswerValueGrounding.matchesRequestedValueType(query, value) ||
                AnswerFactGrounding.checkDraft(value, requiredValues).needsRepair
        if (needsRetry(generated)) {
            Log.w(TAG, "Gemma answer failed local output/fact validation; retrying once in a clean answer turn")
            val retry = generateGroundedAnswer(
                gemma,
                "$prompt\nIMPORTANT: Return only the direct natural-language answer. ${AnswerValueGrounding.requestedValueTypeInstruction(query)} State the requested supported value plainly, without reproducing OCR/evidence lines, adding follow-ups, task text, JSON, routing keys, code, a query plan, or the question. Never start with QUERY:, QUESTION:, or FOLLOW_UPS:.",
                images,
                usePrefilledAnswer = false,
            )
            generated = retry.text
            retryMs = retry.wallMs
            attemptCount++
            retry.profile?.let(profiles::add)
        }
        val checked = if (needsRetry(generated)) {
            Log.e(TAG, "Gemma repeated protocol/text or wrong value type; using safe grounded fallback")
            AnswerValueGrounding.typeMismatchFallback(query) ?: groundedAnswerFallback(query, results)
        } else {
            generated
        }
        lastAnswerProfile = GemmaRuntime.GenerationProfile.combine(profiles)
        return CheckedAnswerGeneration(
            text = checked,
            initialPassMs = initial.wallMs,
            retryMs = retryMs,
            attemptCount = attemptCount,
        )
    }

    private fun looksLikePlannerOutput(output: String): Boolean {
        val normalized = output.trim()
        if (normalized.isBlank()) return false
        val candidate = normalized
            .removePrefix("ANSWER:")
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        if (candidate.contains("PLANNER_TASK", ignoreCase = true) ||
            candidate.contains("ANSWER_TASK", ignoreCase = true) ||
            candidate.contains("EVIDENCE_BUILDER:", ignoreCase = true) ||
            candidate.contains("semantic_queries", ignoreCase = true) ||
            candidate.contains("person_names", ignoreCase = true) ||
            candidate.startsWith("+ semantic=") ||
            candidate.contains(" && person=") ||
            (candidate.contains("==") &&
                (candidate.contains("[person", ignoreCase = true) ||
                    candidate.contains("[semantic", ignoreCase = true)))
        ) {
            return true
        }
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) return false
        val plannerKeys = listOf(
            "\"s\"", "\"p\"", "\"x\"", "\"o\"", "\"y\"", "\"n\"",
            "\"a\"", "\"t\"", "\"l\"", "\"h\"", "\"r\"", "\"e\"", "\"f\"",
        )
        return plannerKeys.count { candidate.contains(it) } >= 2
    }

    private fun groundedAnswerFallback(query: String, results: List<GalleryMedia>): String {
        if (results.isEmpty()) {
            return "I could not find matching gallery items or details for \"${query.take(MAX_NAME_PROMPT_CHARS)}\"."
        }
        val places = results.asSequence()
            .mapNotNull { it.locationName?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy(String::lowercase)
            .take(2)
            .toList()
        val people = results.asSequence()
            .flatMap { it.personLabel.orEmpty().split(',').asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .take(2)
            .toList()
        val dates = results.mapNotNull(GalleryMedia::dateTakenMs).sorted()
        val groundedDetails = buildList {
            if (places.isNotEmpty()) {
                add("The closest matches were taken in ${places.joinToString(" and ")}.")
            }
            if (people.isNotEmpty()) {
                add("${people.joinToString(" and ")} ${if (people.size == 1) "appears" else "appear"} in these photos.")
            }
            if (dates.isNotEmpty()) {
                val firstDate = formatDate(dates.first()).substringBefore(' ')
                val lastDate = formatDate(dates.last()).substringBefore(' ')
                add(
                    if (firstDate == lastDate) {
                        "They were captured on $firstDate."
                    } else {
                        "They span $firstDate to $lastDate."
                    },
                )
            }
        }
        val mediaNoun = when {
            results.all { it.mimeType.startsWith("video/", ignoreCase = true) } -> "video"
            results.all { it.mimeType.startsWith("image/", ignoreCase = true) } -> "photo"
            else -> "gallery item"
        }
        return groundedDetails.take(2).joinToString(" ").ifBlank {
            "I found ${results.size} close $mediaNoun${if (results.size == 1) "" else "s"} matching \"${query.take(MAX_NAME_PROMPT_CHARS)}\"."
        }
    }

    /** Encodes one bounded scenery image without creating an in-memory board. */
    private fun encodeGemmaImage(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Could not encode Gemma answer image"
            }
            output.toByteArray()
        }

    private fun galleryAnswerMaxDimension(media: GalleryMedia): Int =
        if (media.contentClass == MediaContentClass.DOC) {
            GALLERY_DOCUMENT_MAX_DIMENSION
        } else {
            GALLERY_PHOTO_MAX_DIMENSION
        }

    private fun galleryAnswerImageCacheKey(media: GalleryMedia, maxDimension: Int): String =
        "gallery|${media.mediaStoreId}|${media.dateModifiedSeconds}|${media.sizeBytes}|$maxDimension"

    private fun documentAnswerImageCacheKey(match: DocumentMatch): String {
        val chunk = match.chunk
        return "file|${chunk.stableId}|${chunk.timestampMs ?: 0L}|${chunk.text.hashCode()}|" +
            "${chunk.uri.orEmpty()}|$DOCUMENT_PAGE_RENDER_MAX_DIMENSION"
    }

    /** The optional ninth input is one bounded identity crop. */
    private fun downscaleFaceCrop(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= FACE_CROP_MAX_DIMENSION) return bitmap
        val scale = FACE_CROP_MAX_DIMENSION.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }

    private fun parseAnswer(output: String): ParsedAnswer {
        val normalized = output.trim()
        if (AnswerOutputGuard.needsRetry(normalized, "")) return ParsedAnswer("", emptyList())
        val followMarker = normalized.indexOf("FOLLOW_UPS:", ignoreCase = true)
        val answerPart = if (followMarker >= 0) {
            normalized.substring(0, followMarker)
        } else {
            normalized
        }.removePrefix("ANSWER:").trim().let(AnswerTextSanitizer::clean)
        if (followMarker < 0) return ParsedAnswer(answerPart, emptyList())

        val followUps = normalized.substring(followMarker + "FOLLOW_UPS:".length)
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val query = line.removePrefix("QUERY:").trim()
                if (query != line) {
                    sanitizeFollowUpQuery(query)
                        .takeIf(String::isNotBlank)
                        ?.let { FollowUpSuggestion(it, true) }
                } else {
                    null
                }
            }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text.lowercase() }
            .take(MAX_FOLLOW_UPS)
            .toList()
        return ParsedAnswer(answerPart, followUps)
    }

    private fun sanitizeFollowUpQuery(value: String): String = value
        .replace(
            Regex("(?i)\\[(?:[GCEF]\\d+)(?:\\s*[,;&]\\s*[GCEF]\\d+)*]"),
            "",
        )
        .replace(Regex("(?i)(?<![\\p{L}\\p{N}])[GCEF]\\d+(?![\\p{L}\\p{N}])"), "")
        .replace(Regex("(?i)\\b(?:review|open|inspect)\\s+(?:the\\s+)?(?:source|record|evidence)\\b.*$"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun followUpOcrKeywords(query: String): List<String> =
        Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(query.lowercase())
            .map { it.value }
            .filterNot { it in FOLLOW_UP_OCR_STOP_WORDS }
            .distinct()
            .take(OcrKeywordPolicy.MAX_KEYWORDS)
            .toList()

    /** Exact word-boundary matches keep follow-up proof focused on new terms. */
    private fun followUpOcrLineMatchScore(query: String, media: GalleryMedia): Int {
        val terms = followUpOcrKeywords(query)
        if (terms.isEmpty()) return 0
        return media.ocrText.lineSequence().sumOf { line ->
            terms.count { term ->
                Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])")
                    .containsMatchIn(line)
            }
        }
    }

    private fun defaultFollowUps(
        query: String,
        results: List<GalleryMedia>,
    ): List<FollowUpSuggestion> = buildList {
        val normalized = query.lowercase()
        val anchor = results.firstOrNull()
        val anchorTime = anchor?.dateTakenMs
            ?: anchor?.dateModifiedSeconds?.takeIf { it > 0L }?.times(1_000L)
        val anchorDay = anchorTime?.let {
            FOLLOW_UP_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }
        val anchorPlace = anchor?.locationName
            ?.substringBefore(" (")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: anchor?.location?.trim()?.takeIf(String::isNotBlank)
        val anchorPerson = results.asSequence()
            .mapNotNull { it.personLabel?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        val documentQuery = FollowUpSuggestionPolicy.isDocumentQuery(query)
        if (Regex("\\b(?:aadhaar|aadhar)\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("What is the holder name?"))
            add(FollowUpSuggestion("What is the date of birth?"))
        } else if (Regex("\\bpassport\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("What is the expiry date?"))
            add(FollowUpSuggestion("What is the holder name?"))
        } else if (Regex("\\b(driving licence|driving license|licence|license)\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("What is the expiry date?"))
            add(FollowUpSuggestion("What is the number?"))
        } else if (Regex("\\b(receipt|bill|invoice|ticket)\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("What was the total amount?"))
            add(FollowUpSuggestion("What date is on it?"))
        } else if (Regex("\\b(birthdate|birth date|birthday|born)\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("Who else was at the birthday?"))
            add(FollowUpSuggestion("What happened during the birthday celebration?"))
        } else if (Regex("\\b(when|date|dated|year|today|yesterday)\\b").containsMatchIn(normalized)) {
            anchorDay?.let {
                add(FollowUpSuggestion("What else happened on $it?"))
            }
        }
        if (!documentQuery) {
            anchorDay?.let {
                add(FollowUpSuggestion("Who else was there on $it?"))
            }
            if (anchorPerson != null && anchorDay != null) {
                add(FollowUpSuggestion("What else did $anchorPerson do on $anchorDay?"))
            } else if (anchorPerson != null) {
                add(FollowUpSuggestion("Who else appeared with $anchorPerson?"))
            }
            anchorPlace?.let {
                add(FollowUpSuggestion("What other moments happened in $it?"))
            }
            if (isEmpty() && results.isNotEmpty()) {
                add(FollowUpSuggestion("What happened nearby?"))
            }
        }
    }.map {
        FollowUpSuggestion(
            FollowUpSuggestionPolicy.compactForDisplay(sanitizeFollowUpQuery(it.text), query),
            it.isQuery,
        )
    }
        .filter { it.text.isNotBlank() }
        .distinctBy { it.text.lowercase() }
        .take(MAX_FOLLOW_UPS)

    private fun formatDate(value: Long?): String = value?.let {
        DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "none"

    private fun formatModifiedDate(seconds: Long): String =
        if (seconds > 0L) formatDate(seconds * 1000L) else "none"

    private fun formatLocation(media: GalleryMedia): String = when {
        !media.locationName.isNullOrBlank() && !media.location.isNullOrBlank() ->
            "${media.locationName} (${media.location})"
        !media.locationName.isNullOrBlank() -> media.locationName!!
        !media.location.isNullOrBlank() -> media.location!!
        else -> "none"
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds <= 0L) return "none"
        val totalSeconds = milliseconds / 1000L
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun compactPromptText(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun buildAnswerSources(
        orderedEvidence: List<HybridSearchResult>,
    ): List<AnswerSource> {
        val gallerySources = buildAnswerSources(
            results = orderedEvidence.mapNotNull { (it as? HybridSearchResult.Gallery)?.media },
        )
        val documentSources = buildAnswerSources(
            results = emptyList(),
            documentMatches = orderedEvidence.mapNotNull { (it as? HybridSearchResult.Document)?.match },
        )
        var galleryIndex = 0
        var documentIndex = 0
        return orderedEvidence.mapNotNull { item ->
            when (item) {
                is HybridSearchResult.Gallery -> gallerySources.getOrNull(galleryIndex++)
                is HybridSearchResult.Document -> documentSources.getOrNull(documentIndex++)
            }
        }
    }

    private fun buildAnswerSources(
        results: List<GalleryMedia>,
        documentMatches: List<DocumentMatch> = emptyList(),
        preferDocuments: Boolean = false,
    ): List<AnswerSource> {
        val sources = ArrayList<AnswerSource>(results.size)
        results.forEachIndexed { index, media ->
            sources += AnswerSource(
                id = "G${index + 1}",
                type = AnswerSourceType.GALLERY_IMAGE,
                label = sourceLabel(media, index),
                detail = buildString {
                    append(if (media.mimeType.startsWith("video/", ignoreCase = true)) "Gallery video" else "Gallery photo")
                    append("\nCapture time: ")
                    append(formatDate(media.dateTakenMs))
                    append("\nModified time: ")
                    append(formatModifiedDate(media.dateModifiedSeconds))
                    formatLocation(media).takeIf { it != "none" }?.let {
                        append("\nLocation: ")
                        append(it)
                    }
                    if (media.width > 0 && media.height > 0) {
                        append("\nDimensions: ")
                        append(media.width)
                        append('x')
                        append(media.height)
                    }
                    if (media.durationMs > 0L) {
                        append("\nDuration: ")
                        append(formatDuration(media.durationMs))
                    }
                    if (media.ocrText.isNotBlank()) {
                        append("\nOCR: ")
                        append(compactPromptText(media.ocrText))
                    }
                    media.personLabel?.takeIf { it.isNotBlank() }?.let {
                        append("\nPerson: ")
                        append(it)
                    }
                },
                media = media,
            )
        }
        documentMatches.take(MAX_DOCUMENT_ANSWER_RECORDS).forEachIndexed { index, match ->
            val chunk = match.chunk
            sources += AnswerSource(
                id = "D${index + 1}",
                type = AnswerSourceType.DOCUMENT_RECORD,
                label = "${chunk.source.displayName}: ${chunk.title}",
                detail = buildString {
                    append(chunk.text)
                    append("\nMetadata: ")
                    append(chunk.metadata.ifBlank { "none" })
                    append("\nRecord key: ")
                    append(chunk.recordKey)
                    chunk.page?.let { append("\nPage $it") }
                    chunk.timestampMs?.let { append("\nTime: ${formatDate(it)}") }
                    chunk.uri?.let { append("\nURI $it") }
                },
                document = chunk,
            )
        }
        if (!preferDocuments) return sources.take(MAX_ANSWER_RECORDS)
        val documentSources = sources.filter { it.type == AnswerSourceType.DOCUMENT_RECORD }
        val gallerySources = sources.filter { it.type == AnswerSourceType.GALLERY_IMAGE }
        return (documentSources + gallerySources).take(MAX_ANSWER_RECORDS)
    }

    private fun sourceLabel(media: GalleryMedia, index: Int): String {
        val type = if (media.mimeType.startsWith("video/", ignoreCase = true)) "Video" else "Photo"
        val place = media.locationName?.takeIf(String::isNotBlank)
        val date = formatDate(media.dateTakenMs)
        return buildString {
            append(type)
            append(' ')
            append(index + 1)
            if (place != null) append(" • ").append(place)
            if (date != "none") append(" • ").append(date.substringBefore(' '))
        }
    }

    private fun containsAnyPlanTerm(value: String, terms: List<String>): Boolean {
        val normalized = value.lowercase().replace(Regex("\\s+"), " ").trim()
        return terms.any { term ->
            val candidate = term.lowercase().replace(Regex("\\s+"), " ").trim()
            candidate.isNotBlank() && normalized.contains(candidate)
        }
    }

    /**
     * Cleans redundant hard-scope words from LLM semantic predicates while
     * preserving its nested set structure. Locally resolved face labels are
     * appended only when that polarity is absent from the model expression,
     * so an explicit "Ravi, Meghana" union is never flattened into an AND.
     */
    private fun normalizeExecutionSpec(
        spec: QueryExecutionSpec,
        includedPeople: List<String>,
        excludedPeople: List<String>,
        locationHint: String,
        mediaType: QueryMediaType?,
    ): QueryExecutionSpec {
        fun transform(node: ExecutionNode): ExecutionNode? = when (node) {
            is ExecutionNode.Predicate -> if (node.field == ExecutionField.SEMANTIC) {
                sanitizeSemanticQuery(
                    node.value,
                    includedPeople + excludedPeople,
                    locationHint,
                    mediaType,
                ).takeIf(String::isNotBlank)?.let {
                    ExecutionNode.Predicate(ExecutionField.SEMANTIC, it)
                }
            } else {
                node
            }
            is ExecutionNode.Sorted -> transform(node.value)?.let {
                ExecutionNode.Sorted(it, node.sort)
            }
            is ExecutionNode.Binary -> {
                val left = transform(node.left)
                val right = transform(node.right)
                when {
                    left != null && right != null ->
                        ExecutionNode.Binary(left, node.operator, right)
                    left != null -> left
                    right != null && node.operator != ExecutionBinaryOperator.SUBTRACT -> right
                    else -> null
                }
            }
        }

        var root = transform(spec.root) ?: spec.root
        val positivePeople = LinkedHashSet<String>()
        val negativePeople = LinkedHashSet<String>()
        fun collectPeople(node: ExecutionNode, subtract: Boolean = false) {
            when (node) {
                is ExecutionNode.Predicate -> if (node.field == ExecutionField.PERSON) {
                    (if (subtract) negativePeople else positivePeople).add(node.value.lowercase())
                }
                is ExecutionNode.Sorted -> collectPeople(node.value, subtract)
                is ExecutionNode.Binary -> {
                    collectPeople(node.left, subtract)
                    collectPeople(
                        node.right,
                        subtract || node.operator == ExecutionBinaryOperator.SUBTRACT,
                    )
                }
            }
        }
        collectPeople(root)
        if (positivePeople.isEmpty()) {
            includedPeople.forEach { label ->
                root = ExecutionNode.Binary(
                    root,
                    ExecutionBinaryOperator.INTERSECT,
                    ExecutionNode.Predicate(ExecutionField.PERSON, label),
                )
            }
        }
        excludedPeople
            .filterNot { label -> negativePeople.any { it.equals(label, ignoreCase = true) } }
            .forEach { label ->
                root = ExecutionNode.Binary(
                    root,
                    ExecutionBinaryOperator.SUBTRACT,
                    ExecutionNode.Predicate(ExecutionField.PERSON, label),
                )
            }
        return QueryExecutionSpec(root)
    }

    /** Removes hard-scope words from the semantic branch without widening it. */
    private fun sanitizeSemanticQuery(
        raw: String,
        scopedPeople: List<String>,
        locationHint: String,
        mediaType: QueryMediaType?,
    ): String {
        var value = raw.trim()
        if (value.isBlank()) return ""
        scopedPeople.filter(String::isNotBlank).forEach { person ->
            value = value.replace(Regex("(?i)\\b${Regex.escape(person)}\\b"), " ")
        }
        if (locationHint.isNotBlank()) {
            value = value.replace(Regex("(?i)\\b${Regex.escape(locationHint)}\\b"), " ")
        }
        value = value
            .replace(Regex("(?i)\\b(last|previous|this|current)\\s+(month|week|year)\\b"), " ")
            .replace(Regex("(?i)\\b(latest|newest|recent|most)\\b"), " ")
        val representedMediaWords = when (mediaType) {
            QueryMediaType.PHOTOS ->
                Regex("(?i)\\b(photo|photos|picture|pictures|image|images|still|stills)\\b")
            QueryMediaType.VIDEOS ->
                Regex("(?i)\\b(video|videos|clip|clips)\\b")
            QueryMediaType.PDF,
            QueryMediaType.DOC,
            QueryMediaType.MESSAGES,
            QueryMediaType.SMS,
            QueryMediaType.CALENDAR,
            QueryMediaType.CONTACTS,
            QueryMediaType.CALL_LOGS,
            QueryMediaType.FILES -> null
            null -> null
        }
        representedMediaWords?.let { value = value.replace(it, " ") }
        if (locationHint.isNotBlank()) {
            value = value.replace(Regex("(?i)\\b(trip|trips|travel|vacation|holiday)\\b"), " ")
        }
        return QueryLifecycleScaffoldingPolicy.strip(value)
    }

    companion object {
        private const val TAG = "AskGalaxy"
        private const val MAX_ANSWER_RECORDS = 8
        private const val MAX_SOURCE_ICONS = 8
        private const val MAX_DOCUMENT_ANSWER_RECORDS = 8
        private const val DOCUMENT_MATCHES_PER_SOURCE = 8
        private const val DOCUMENT_PAGE_RENDER_MAX_DIMENSION = 3_072
        private const val GALLERY_DOCUMENT_MAX_DIMENSION = 3_072
        private const val GALLERY_PHOTO_MAX_DIMENSION = 2_048
        private const val FACE_CROP_MAX_DIMENSION = 512
        // LiteRT-LM 0.14 has no per-request max-output-token control. This is
        // an explicit model contract, and the runtime records any overrun.
        private const val MAX_ANSWER_GENERATED_TOKENS = 50
        private const val UI_RESULT_LIMIT = 16
        private const val GLOBAL_RESULT_LIMIT = 16
        private const val MAX_EVIDENCE_LOG_CHARS = 600
        private const val MAX_FOLLOW_UPS = 2
        private const val MAX_NEXT_BRIEFS = 2
        private const val MAX_NEXT_BRIEF_LABEL_CHARS = 42
        private const val MAX_NEXT_BRIEF_PAYLOAD_CHARS = 96
        private const val MAX_NEXT_BRIEF_OCR_CHARS = 1_600
        private const val MAX_NEXT_BRIEF_CONTEXT_CHARS = 6_000
        private const val MAX_CAPABILITY_HANDLERS = 3
        private const val MAX_CONTACT_TARGETS = 4
        private val IDENTITY_KEYWORD_STOP_WORDS = setOf(
            "aadhar", "aadhaar", "passport", "pan", "ssn", "identity", "id",
            "licence", "license", "number", "numbers", "num",
        )
        private val IDENTITY_OCR_STOP_WORDS = IDENTITY_KEYWORD_STOP_WORDS
        private const val FOLLOW_UP_SEMANTIC_WEIGHT = 0.70f
        private const val FOLLOW_UP_OCR_WEIGHT = 0.30f
        private const val MAX_NAME_PROMPT_CHARS = 48
        private val FOLLOW_UP_OCR_STOP_WORDS = setOf(
            "what", "which", "when", "where", "whose", "with", "from", "this", "that",
            "have", "does", "please", "show", "find", "give", "tell", "about", "photo", "image",
            "document", "number", "date",
        )
        private val FOLLOW_UP_DIVERSITY_STOP_WORDS = setOf(
            "the", "is", "are", "was", "were", "does", "did", "do", "can", "could", "would",
            "to", "of", "on", "at", "in", "for", "from", "with", "and", "or", "me", "please",
            "show", "find", "tell", "give", "you", "it", "this", "that", "my", "your", "about",
        )
        private val FOLLOW_UP_DIVERSITY_DIMENSIONS = setOf(
            "when", "where", "who", "whose", "which", "expiry", "expire", "expiration", "date",
            "dob", "birth", "birthday", "location", "place", "address", "other", "else", "details",
            "happened", "appeared", "visited", "nearby", "count", "many", "total", "amount", "cost",
            "compare", "comparison", "timeline", "issued", "valid", "source",
        )
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        private val FOLLOW_UP_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy")
        private val ANSWER_SYSTEM_INSTRUCTION = """
            Answer the user's QUESTION using all supplied top-eight records together. Gallery records provide a high-resolution bounded image and metadata but no OCR text. PDF/DOC/file records provide a rendered page image and metadata but no extracted file text. Messages, SMS, call logs, contacts, and calendar records provide native text because they have no page image. Read visible document layout, columns, tables, labels, and nearby values directly from images. For call-history questions, use the newest matching call-log timestamp for "last time" or "when". For message-history questions, use the newest matching Messages/SMS text for "last message" or "what did ... text", and summarize that text directly. Preserve the exact field and value type requested by the user: an Aadhaar, passport, licence, or identity number is never a phone or mobile number unless explicitly requested; an expiry/expiration/valid-until question must return a visibly labelled calendar date and never a document number, MRZ, issue date, birth date, metadata timestamp, or uninterrupted long identifier. Return only the direct answer in as few words as possible. Never mention records, evidence, OCR, metadata, images, sources, provenance, reasoning, prompts, models, IDs, or how the answer was derived. Never say that information is present, absent, contained, or not contained in the supplied material. Do not calculate or invent values. If multiple distinct values directly answer the question, state them briefly. Do not ask a follow-up question, repeat the question, or output JSON, routing keys, or protocol text.
        """.trimIndent()
        private val SUGGESTIONS_SYSTEM_INSTRUCTION = """
            You generate two kinds of bounded suggestions after an Ask Galaxy answer.
            Use only the ORIGINAL QUESTION, the answer, GROUNDED ANSWER RECORDS, and AVAILABLE PHONE CAPABILITIES.
            First output up to two useful natural search queries as QUERY: <question>. They must be genuinely diverse follow-ups, not paraphrases or repetitions of the ORIGINAL QUESTION.
            Classify the ORIGINAL QUESTION as document or event/scene before suggesting queries. For a document such as a driving licence, Aadhaar card, passport, receipt, invoice, bill, or ticket, stay on that same document and ask only for a plausibly printed field such as holder name, number, issue date, expiry, address, merchant, or total. Keep the displayed query short and contextual: say "What is the address?" or "What is the expiry date?"; do not repeat the owner, answer value, passport, licence, or other document anchor because QP restores it from the previous turn. A document's capture timestamp is not an event date. Never ask who else was there, what happened that day, what a person did that day, where the image was taken, or about related moments/people for a document query.
            Only for an event, trip, activity, or ordinary photo question may follow-ups ask about date, place, other people, or related moments. Keep the original event context and change the information need. Do not output two queries from the same dimension. Every query must be answerable from GROUNDED ANSWER RECORDS. Never suggest a generic "tell me more" query.
            Then output at most two useful next-step actions as ACTION: TYPE|short label|SOURCE_ID|PAYLOAD.
            TYPE must exactly match an available capability id: share_media, maps_search, contact, calendar_reminder, continue_web_task, or send_message.
            Use only a capability listed in AVAILABLE PHONE CAPABILITIES. SOURCE_ID must be one grounded record such as G1.
            Every action MUST have a meaningful PAYLOAD of 3-96 characters, extracted or carefully paraphrased from the question, answer, or grounded record. Never leave it empty, generic, or invented.
            Examples: CONTINUE_WEB_TASK|Check hotel booking|G1|check hotel booking again; SHARE_MEDIA|Share Jaany photos|G1|Goa trip photos for Jaany; CALENDAR_REMINDER|Remember tomorrow's show|G1|Goa show tomorrow; SEND_MESSAGE|Message Jaany|G1|Ask Jaany about tomorrow's show.
            SHARE_MEDIA is useful only when the grounded record contains a person and either a detected phone/email or a messaging handler such as WhatsApp; never offer generic place-only photo sharing.
            MAPS_SEARCH is useful when a grounded place is present, especially for an imminent ticket, appointment, or trip.
            CONTACT is useful only when the grounded record contains a phone number or email address.
            CALENDAR_REMINDER is useful when the answer contains a date, deadline, ticket, appointment, expiry, or time-sensitive document.
            CONTINUE_WEB_TASK is useful only when the question or evidence explicitly asks to book again, rebook, manage/check a booking, reserve, buy, check availability, or continue a named hotel/flight/train/ticket/trip/event task. Never use it for a normal question about a person's activities.
            SEND_MESSAGE is useful only when the grounded person also has a detected phone/email; otherwise prefer sharing a photo through the available messaging handler.
            If the user only asks what a person did, where photos were taken, or what an image contains, output no action unless a concrete next task is clearly grounded.
            Use the exact grounded source ID, never invent a source. Do not suggest an action when its required data is absent.
            Labels must be short, natural, and specific to the task. Never expose OCR, include document numbers in labels, repeat the original question, use vague payloads, output JSON, or explain your reasoning. Output only QUERY and ACTION lines.
        """.trimIndent()

        private fun elapsedMs(startNanos: Long, endNanos: Long): Long =
            ((endNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)
    }

    private data class SearchExecution(
        val candidates: List<GalleryMedia>,
        val galleryCosineScores: Map<Long, Float>,
        val plannerSession: GemmaRuntime.ConversationSession?,
        val plannerJson: String,
        val effectivePlanJson: String,
        val resolvedQuery: String,
        val queryCategory: QueryCategory,
        val needsAnswer: Boolean,
        val plannerProfile: GemmaRuntime.GenerationProfile?,
        val answerEvidenceScope: AnswerEvidenceScope,
        val ocrKeywords: List<String>,
        val documentMatches: List<DocumentMatch>,
    )

    private data class GlobalSearchHit(
        val score: Float,
        val gallery: GalleryMedia? = null,
        val document: DocumentMatch? = null,
    )

    private data class GlobalSearchWindow(
        val gallery: List<GalleryMedia>,
        val documents: List<DocumentMatch>,
        val ordered: List<HybridSearchResult>,
    )

    private data class ParsedAnswer(
        val text: String,
        val followUps: List<FollowUpSuggestion>,
    )

    private data class AnswerGenerationPass(
        val text: String,
        val wallMs: Long,
        val profile: GemmaRuntime.GenerationProfile?,
    )

    private data class CheckedAnswerGeneration(
        val text: String,
        val initialPassMs: Long,
        val retryMs: Long,
        val attemptCount: Int,
    )

    private data class GeneratedSuggestions(
        val followUps: List<FollowUpSuggestion>,
        val nextBriefs: List<NextBriefSuggestion>,
    )

    private fun scanBlockingInternal(): Long {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        var scanned = 0L
        val activeMediaStoreIds = LinkedHashSet<Long>()
        var scanCompleted = false

        resolver.query(
            filesUri,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            scanCompleted = true
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                activeMediaStoreIds += id
                val mime = cursor.getString(mimeColumn).orEmpty()
                val contentUri = ContentUris.withAppendedId(filesUri, id).toString()
                database.upsert(
                    GalleryMedia(
                        mediaStoreId = id,
                        contentUri = contentUri,
                        mimeType = mime,
                        displayName = cursor.getString(nameColumn).orEmpty(),
                        dateModifiedSeconds = cursor.getLong(modifiedColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        width = cursor.getIntOrZero(widthColumn),
                        height = cursor.getIntOrZero(heightColumn),
                        durationMs = cursor.getLongOrZero(durationColumn),
                    ),
                )
                scanned += 1
            }
        }
        if (scanCompleted) {
            val removedIds = database.removeMissingMediaStoreIds(activeMediaStoreIds)
            if (removedIds.isNotEmpty()) {
                semanticIndexer.removeMediaStoreIdsBlocking(removedIds)
                Log.i(TAG, "Removed ${removedIds.size} deleted gallery records from SQLite and visual index")
            }
        }
        return scanned
    }

    private fun scanNewMediaBlockingInternal(): Long {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val existingIds = database.allMediaStoreIds()
        var added = 0L
        resolver.query(
            filesUri,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                if (!IncrementalIndexPolicy.shouldAdd(id, existingIds)) continue
                database.upsert(
                    GalleryMedia(
                        mediaStoreId = id,
                        contentUri = ContentUris.withAppendedId(filesUri, id).toString(),
                        mimeType = cursor.getString(mimeColumn).orEmpty(),
                        displayName = cursor.getString(nameColumn).orEmpty(),
                        dateModifiedSeconds = cursor.getLong(modifiedColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        width = cursor.getIntOrZero(widthColumn),
                        height = cursor.getIntOrZero(heightColumn),
                        durationMs = cursor.getLongOrZero(durationColumn),
                    ),
                )
                added += 1
            }
        }
        Log.i(TAG, "Incremental gallery scan added=$added existing=${existingIds.size}; deleted=0 updated=0")
        return added
    }

    private fun queryActiveMediaStoreIds(): Set<Long>? {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val activeIds = LinkedHashSet<Long>()
        resolver.query(filesUri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (cursor.moveToNext()) activeIds += cursor.getLong(idColumn)
        } ?: return null
        return activeIds
    }

    private fun removeStaleGalleryRows(activeMediaStoreIds: Set<Long>): Int {
        val removedIds = database.removeMissingMediaStoreIds(activeMediaStoreIds)
        if (removedIds.isNotEmpty()) {
            semanticIndexer.removeMediaStoreIdsBlocking(removedIds)
            Log.i(TAG, "Removed ${removedIds.size} deleted gallery records from SQLite and visual index")
        }
        return removedIds.size
    }

    private fun isMissingMediaError(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is FileNotFoundException ||
                cause.message?.contains("No item at", ignoreCase = true) == true
        }

}

/** Pure additive selection contract shared by worker code and regression tests. */
internal object IncrementalIndexPolicy {
    fun shouldAdd(stableId: Long, existingStableIds: Set<Long>): Boolean =
        stableId !in existingStableIds

    fun additions(discoveredStableIds: Collection<Long>, existingStableIds: Set<Long>): Set<Long> =
        discoveredStableIds.filterTo(LinkedHashSet()) { shouldAdd(it, existingStableIds) }
}

/**
 * The executor's order is the product's overall query score. Presentation may
 * bound that list, but it must never substitute a date/name/file sort.
 */
internal object SearchResultPresentationPolicy {
    fun top(
        rankedCandidates: List<GalleryMedia>,
        limit: Int,
    ): List<GalleryMedia> = rankedCandidates.take(limit.coerceAtLeast(0))
}

private fun android.database.Cursor.getIntOrZero(column: Int): Int =
    if (isNull(column)) 0 else getInt(column)

private fun android.database.Cursor.getLongOrZero(column: Int): Long =
    if (isNull(column)) 0L else getLong(column)
