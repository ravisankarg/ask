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

class GalleryIndexer(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val database = GalleryDatabase(appContext)
    private val semanticIndexer = GallerySemanticIndexer(appContext, database)
    private val metadataReader = GalleryMetadataReader(appContext)
    private val structuredSearchExecutor =
        StructuredSearchExecutor(database, semanticIndexer, metadataReader)
    private val evidenceBuilder = EvidenceBuilder(database)
    private val answerContextPicker = AnswerContextPicker(semanticIndexer)
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
                    val searchExecution = searchBlocking(query) { plannerJson, effectivePlanJson, plannerProfile ->
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
                    // The picker always operates on indexed records. Scenery
                    // alone uses persisted SigLIP embeddings for diversity;
                    // no phase here opens or decodes an image.
                    val diversityCandidates = if (
                        searchExecution.queryCategory == QueryCategory.DOC
                    ) {
                        // The document picker mines the first eight records
                        // ranked by the structured hybrid search. Episode
                        // representatives are useful for broad photo answers,
                        // but could inject a lower-ranked incidental OCR hit
                        // into that bounded document-evidence window.
                        candidates
                    } else {
                        (evidenceBuild.representativeCandidates + candidates)
                            .distinctBy { it.mediaStoreId }
                    }
                    val answerContext = answerContextPicker.pick(
                        query = query,
                        rankedCandidates = diversityCandidates,
                        evidenceGroups = evidenceBuild.contextGroups,
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
            directCommunicationAnswer(query, response)?.let { directAnswer ->
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
                // Keep the exact global hybrid order for answer grounding.
                // The answer contract is the first eight cross-source records,
                // not eight gallery records plus eight private records.
                val groundedResponse = recoverAnswerEvidence(query, response)
                val staleGalleryIds = LinkedHashSet<Long>()
                var orderedEvidence = answerEvidenceWindow(groundedResponse).mapNotNull { item ->
                    when (item) {
                        is HybridSearchResult.Gallery -> runCatching {
                            HybridSearchResult.Gallery(metadataReader.enrich(item.media))
                        }.onFailure { error ->
                            if (isMissingMediaError(error)) staleGalleryIds += item.media.mediaStoreId
                        }.getOrNull()
                        is HybridSearchResult.Document -> item
                    }
                }
                if (staleGalleryIds.isNotEmpty()) {
                    orderedEvidence = orderedEvidence.filterNot { item ->
                        (item as? HybridSearchResult.Gallery)?.media?.mediaStoreId in staleGalleryIds
                    }
                    removeStaleGalleryRows(database.allMediaStoreIds() - staleGalleryIds)
                }
                var attachedResults = orderedEvidence.mapNotNull { item ->
                    (item as? HybridSearchResult.Gallery)?.media
                }
                val galleryFactRows = database.ensureAnswerabilityFacts(
                    attachedResults.map(GalleryMedia::mediaStoreId).toLongArray(),
                ).mapKeys { (mediaStoreId, _) -> AnswerFactGrounding.galleryKey(mediaStoreId) }
                val documentEvidence = orderedEvidence.mapNotNull {
                    (it as? HybridSearchResult.Document)?.match
                }
                val documentFactRows = if (documentEvidence.isEmpty()) {
                    emptyMap()
                } else {
                    DocumentVectorIndex.shared(appContext).answerabilityFacts(documentEvidence)
                }
                val groundedFacts = AnswerFactGrounding.ground(
                    query = query,
                    evidence = orderedEvidence,
                    indexedFacts = galleryFactRows + documentFactRows,
                )
                val groundedFactValues = groundedFacts.matchedValues
                if (groundedFactValues.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Grounded labelled facts=${groundedFacts.matchedFacts.size}, " +
                            "distinctValues=${groundedFactValues.size}; passing to answer LLM for resolution",
                    )
                }
                val candidateVisualGallery = attachedResults
                    .filter(QueryCategoryContextPolicy::isVisualMedia)
                    .take(QueryCategoryContextPolicy.ANSWER_IMAGE_LIMIT)
                val evidenceScope = AnswerEvidenceScope.all()
                val loadedEvidence = ArrayList<Pair<GalleryMedia, Bitmap>>(candidateVisualGallery.size)
                val loadedFaceEvidence = ArrayList<Pair<GalleryMedia, Bitmap>>(MAX_FACE_CROP_IMAGES)
                val personEvidenceRequested = response.answerEvidenceScope.needsPeopleMetadata ||
                    attachedResults.any { !it.personLabel.isNullOrBlank() } ||
                    Regex("(?i)\\b(who|whose|person|people|with|without)\\b")
                        .containsMatchIn(query)
                val candidateVisualIds = candidateVisualGallery.map { it.mediaStoreId }.toSet()
                val taggedFacesByMedia = if (personEvidenceRequested && candidateVisualIds.isNotEmpty()) {
                    database.taggedFaceOccurrencesForMedia(candidateVisualIds.toLongArray())
                        .groupBy { it.mediaStoreId }
                } else {
                    emptyMap()
                }
                val faceTargets = if (personEvidenceRequested) {
                    candidateVisualGallery.mapNotNull { media ->
                        val occurrences = taggedFacesByMedia[media.mediaStoreId].orEmpty()
                        val personLabels = media.personLabel.orEmpty()
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotBlank)
                        val occurrence = occurrences.firstOrNull { face ->
                            personLabels.any { it.equals(face.label, ignoreCase = true) }
                        } ?: occurrences.firstOrNull()
                        occurrence?.let { media to it }
                    }.take(MAX_FACE_CROP_IMAGES)
                } else {
                    emptyList()
                }
                // Gemma's mobile vision contract allows four image inputs.
                // Reserve two slots for identity crops when person evidence is
                // requested, keeping full-image and face-crop pairs aligned.
                val visualGallery = (
                    faceTargets.map { it.first } +
                        candidateVisualGallery.filterNot { media ->
                            faceTargets.any { it.first.mediaStoreId == media.mediaStoreId }
                        }
                    ).distinctBy { it.mediaStoreId }
                    .take(
                        (QueryCategoryContextPolicy.ANSWER_IMAGE_LIMIT - faceTargets.size)
                            .coerceAtLeast(0),
                    )
                val visualIds = visualGallery.map { it.mediaStoreId }.toSet()
                MediaBitmapLoader(appContext).use { loader ->
                    visualGallery
                        .filter { it.mediaStoreId in visualIds }
                        .forEach { enrichedMedia ->
                        val bitmap = runCatching {
                            loader.load(
                                enrichedMedia,
                                maxDimension =
                                    QueryCategoryContextPolicy.ANSWER_IMAGE_MAX_DIMENSION,
                                applyExifOrientation = true,
                            )
                        }.onFailure { error ->
                            if (isMissingMediaError(error)) staleGalleryIds += enrichedMedia.mediaStoreId
                        }.getOrNull()
                            ?: return@forEach
                        val boundedBitmap = downscaleAnswerImage(bitmap)
                        loadedEvidence += enrichedMedia to boundedBitmap
                        faceTargets.firstOrNull { it.first.mediaStoreId == enrichedMedia.mediaStoreId }
                            ?.second
                            ?.let { occurrence ->
                                val orientedBounds = ImageOrientation.transformBox(
                                    occurrence.box,
                                    loader.readOrientation(enrichedMedia),
                                )
                                val detection = FaceDetection(
                                    box = FaceBox(
                                        left = orientedBounds.left * boundedBitmap.width,
                                        top = orientedBounds.top * boundedBitmap.height,
                                        right = orientedBounds.right * boundedBitmap.width,
                                        bottom = orientedBounds.bottom * boundedBitmap.height,
                                    ),
                                    landmarks = FloatArray(0),
                                    score = occurrence.detectionScore,
                                )
                                FaceCropper.crop(boundedBitmap, detection)
                            }
                            ?.let { faceCrop ->
                                loadedFaceEvidence += enrichedMedia to downscaleFaceCrop(faceCrop)
                            }
                    }
                }
                if (staleGalleryIds.isNotEmpty()) {
                    orderedEvidence = orderedEvidence.filterNot { item ->
                        (item as? HybridSearchResult.Gallery)?.media?.mediaStoreId in staleGalleryIds
                    }
                    attachedResults = attachedResults.filterNot {
                        it.mediaStoreId in staleGalleryIds
                    }
                    removeStaleGalleryRows(database.allMediaStoreIds() - staleGalleryIds)
                }
                val imageReferences = loadedEvidence.mapNotNull { (media, _) ->
                    orderedEvidence.indexOfFirst {
                        (it as? HybridSearchResult.Gallery)?.media?.mediaStoreId == media.mediaStoreId
                    }
                        .takeIf { it >= 0 }
                        ?.let { media.mediaStoreId to "R" + (it + 1) }
                }.toMap()
                val faceImageReferences = loadedFaceEvidence.mapNotNull { (media, _) ->
                    orderedEvidence.indexOfFirst {
                        (it as? HybridSearchResult.Gallery)?.media?.mediaStoreId == media.mediaStoreId
                    }
                        .takeIf { it >= 0 }
                        ?.let { media.mediaStoreId to "R" + (it + 1) }
                }.toMap()
                val imageBytes = try {
                    loadedEvidence.map { (_, bitmap) -> encodeGemmaImage(bitmap) } +
                        loadedFaceEvidence.map { (_, bitmap) -> encodeGemmaImage(bitmap) }
                } finally {
                    loadedEvidence.forEach { (_, bitmap) ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    loadedFaceEvidence.forEach { (_, bitmap) ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                val richVisualIds = if (imageBytes.isNotEmpty()) {
                    loadedEvidence.map { it.first.mediaStoreId }.toSet()
                } else {
                    emptySet()
                }
                Log.i(
                    TAG,
                    "Answer category=${groundedResponse.queryCategory.wireName}, scope=${evidenceScope.label()}, " +
                        "records=${orderedEvidence.size}, " +
                        "visualCandidates=${visualGallery.size}, decodedVisuals=${loadedEvidence.size}, " +
                        "faceCrops=${loadedFaceEvidence.size}, " +
                        "images=${imageBytes.size}, " +
                        "ocr=${evidenceScope.needsOcr}, metadata=${evidenceScope.needsMetadata}",
                )
                check(imageBytes.isNotEmpty() || orderedEvidence.isNotEmpty()) {
                    "No readable result evidence for Gemma"
                }
                val gemma = GemmaRuntime.shared(appContext)
                lastAnswerProfile = null
                val answerStarted = System.nanoTime()
                val generatedOutput = try {
                    val answerPrompt = buildAnswerPrompt(
                        query,
                        emptyList(),
                        imageReferences = imageReferences,
                        faceImageReferences = faceImageReferences,
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
                    )
                } catch (multimodalError: RuntimeException) {
                    if (imageBytes.isEmpty()) throw multimodalError
                    // Some supported phone/runtime combinations reject the
                    // Gemma 4 vision graph with DYNAMIC_UPDATE_SLICE. The
                    // resident text graph remains useful: keep the exact same
                    // selected evidence and answer from OCR/metadata rather
                    // than returning the generic UI failure.
                    Log.w(TAG, "Gemma vision path failed; retrying grounded text answer", multimodalError)
                    generateCheckedAnswer(
                        gemma = gemma,
                        prompt = buildAnswerPrompt(
                            query,
                            emptyList(),
                            imageReferences = emptyMap(),
                            faceImageReferences = emptyMap(),
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
                    )
                }
                val output = reviewGroundedAnswer(
                    gemma = gemma,
                    query = query,
                    draft = DocumentAmountGrounding.constrainAnswer(query, generatedOutput, attachedResults),
                    results = attachedResults,
                    documents = orderedEvidence.mapNotNull { (it as? HybridSearchResult.Document)?.match },
                    orderedEvidence = orderedEvidence,
                    faceImageReferences = faceImageReferences,
                    images = imageBytes,
                    groundedFactValues = groundedFactValues,
                    groundedFactContext = groundedFacts.promptText(),
                )
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
                    "Answer phases: generation=${answerGenerationMs}ms, followUps=${followUpMs}ms, " +
                        "scope=${evidenceScope.label()}",
                )
                AnswerResult(
                    text = parsed.text,
                    sources = buildAnswerSources(
                        orderedEvidence,
                    ),
                    followUps = followUps,
                    timings = groundedResponse.timings
                        .withAnswerTimings(answerGenerationMs, followUpMs)
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

    private fun recoverAnswerEvidence(query: String, response: SearchResponse): SearchResponse {
        val requested = requestedEvidenceKind(query)
        if (requested == EvidenceKind.NONE) return response
        var current = response
        repeat(MAX_EVIDENCE_RECOVERY_PASSES) { pass ->
            val currentTop = (current.answerContext?.records
                ?: current.answerGallery.ifEmpty { current.gallery })
                .take(MAX_ANSWER_RECORDS)
            val currentDocs = current.documentMatches.take(MAX_DOCUMENT_ANSWER_RECORDS)
            if (evidenceCovers(requested, currentTop, currentDocs)) {
                Log.i(TAG, "Evidence recovery pass=${pass + 1} covered=$requested")
                return current
            }
            val rankedGallery = current.gallery.sortedByDescending { evidenceScore(requested, it) }
            val rankedAnswerGallery = current.answerGallery.sortedByDescending { evidenceScore(requested, it) }
            val rankedDocs = current.documentMatches.sortedByDescending {
                evidenceScore(requested, it.chunk) * 10f + it.fusionScore
            }
            val merged = mergeGlobalSearchWindow(
                gallery = rankedGallery,
                documents = rankedDocs,
            )
            val next = current.copy(
                gallery = merged.gallery,
                answerGallery = rankedAnswerGallery.ifEmpty { rankedGallery },
                answerContext = null,
                documentMatches = merged.documents,
                mergedResults = merged.ordered,
            )
            Log.i(
                TAG,
                "Evidence recovery pass=${pass + 1} missing=$requested " +
                    "galleryTop=${rankedGallery.take(MAX_ANSWER_RECORDS).joinToString { it.mediaStoreId.toString() }} " +
                    "docs=${rankedDocs.take(MAX_DOCUMENT_ANSWER_RECORDS).size}",
            )
            if (next.gallery == current.gallery && next.documentMatches == current.documentMatches) return current
            current = next
        }
        return current
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

    private fun answerEvidenceWindow(response: SearchResponse): List<HybridSearchResult> {
        val merged = response.mergedResults.ifEmpty {
            buildList {
                response.gallery.forEach { add(HybridSearchResult.Gallery(it)) }
                response.documentMatches.forEach { add(HybridSearchResult.Document(it)) }
            }
        }
        return merged.take(MAX_ANSWER_RECORDS)
    }

    private enum class EvidenceKind { NONE, IDENTITY, AMOUNT, LOCATION, TIME }

    private fun requestedEvidenceKind(query: String): EvidenceKind = when {
        Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e|identity|id)\\b").containsMatchIn(query) -> EvidenceKind.IDENTITY
        Regex("(?i)\\b(amount|total|price|cost|spent|paid|payment|how much)\\b").containsMatchIn(query) -> EvidenceKind.AMOUNT
        Regex("(?i)\\b(where|location|place|visited|visit|go|travel)\\b").containsMatchIn(query) -> EvidenceKind.LOCATION
        Regex("(?i)\\b(when|date|time|day|year|month)\\b").containsMatchIn(query) -> EvidenceKind.TIME
        else -> EvidenceKind.NONE
    }

    private fun evidenceCovers(
        kind: EvidenceKind,
        gallery: List<GalleryMedia>,
        documents: List<DocumentMatch>,
    ): Boolean = gallery.any { evidenceScore(kind, it) > 0f } ||
        documents.any { evidenceScore(kind, it.chunk) > 0f }

    private fun evidenceScore(kind: EvidenceKind, media: GalleryMedia): Float {
        val text = listOf(media.displayName, media.ocrText, media.locationName, media.location)
            .filterNotNull().joinToString(" ").lowercase()
        return when (kind) {
            EvidenceKind.IDENTITY -> if (Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e|identity)\\b").containsMatchIn(text) &&
                Regex("\\b\\d{4,}\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.AMOUNT -> if (Regex("(?i)(₹|\\brs\\.?|\\binr\\b|amount|total|price|cost|paid|payment)\\s*[:=]?\\s*[-+]?\\d").containsMatchIn(text) ||
                Regex("(?i)\\b(amount|total|price|cost|paid|payment)\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.LOCATION -> if (!media.locationName.isNullOrBlank() || !media.location.isNullOrBlank()) 1f else 0f
            EvidenceKind.TIME -> if (media.dateTakenMs != null || Regex("\\b\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4}\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.NONE -> 0f
        }
    }

    private fun evidenceScore(kind: EvidenceKind, chunk: DocumentChunk): Float {
        val text = "${chunk.title} ${chunk.text} ${chunk.metadata}".lowercase()
        return when (kind) {
            EvidenceKind.IDENTITY -> if (Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e|identity)\\b").containsMatchIn(text) &&
                Regex("\\b\\d{4,}\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.AMOUNT -> if (Regex("(?i)(₹|\\brs\\.?|\\binr\\b|amount|total|price|cost|paid|payment)\\s*[:=]?\\s*[-+]?\\d").containsMatchIn(text) ||
                Regex("(?i)\\b(amount|total|price|cost|paid|payment)\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.LOCATION -> if (Regex("(?i)\\b(location|place|visited|visit|travel|city|address)\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.TIME -> if (chunk.timestampMs != null || Regex("\\b\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4}\\b").containsMatchIn(text) ||
                Regex("(?i)\\b(date|time|day|year|month)\\b").containsMatchIn(text)) 1f else 0f
            EvidenceKind.NONE -> 0f
        }
    }

    /**
     * Answers a chip from the already displayed result set. This deliberately
     * does not call the planner or any retrieval index: it only reruns the
     * bounded Context Picker with the follow-up wording and invokes answer
     * generation on that new four-record context.
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
                // The Context Picker then applies its normal top-four visual
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
                    evidenceGroups = currentResponse.evidenceGroups,
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
        )
        // The planner KV is useful only for the planning turn. Answering uses
        // a clean conversation, so holding this session through SQLite,
        // native retrieval, and diversity only increases memory pressure.
        plannedQuery.session?.close()
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
                ensureIdentityOcrTerms(it, query)
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
                    queryCategory = effectivePlan.queryCategory,
                    needsAnswer = effectivePlan.needsAnswer,
                    plannerProfile = plannedQuery.generationProfile,
                    answerEvidenceScope = effectivePlan.answerEvidenceScope,
                    ocrKeywords = effectivePlan.ocrTerms.flatMap(OcrKeywordPolicy::keywords),
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
            val semanticPhrase = privateSemanticPhrase(effectivePlan, query)
            val keywordGroups = buildPrivateKeywordGroups(effectivePlan, query)
            val documentSources = effectivePlan.mediaType?.documentSources()
                ?: DocumentSource.entries.toSet()
            // Gallery and private retrieval use independent databases, native
            // indexes, and text encoders. Run them concurrently so
            // HYBRID_RETRIEVAL reflects the slower branch instead of their sum.
            val galleryFuture = retrievalExecutor.submit<StructuredSearchExecutor.ScoredGalleryResults> {
                structuredSearchExecutor.executeScored(executionSpec)
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
                queryCategory = effectivePlan.queryCategory,
                needsAnswer = effectivePlan.needsAnswer,
                plannerProfile = plannedQuery.generationProfile,
                answerEvidenceScope = effectivePlan.answerEvidenceScope,
                ocrKeywords = effectivePlan.ocrTerms.flatMap(OcrKeywordPolicy::keywords),
                documentMatches = documentMatches,
            )
        }
    }

    private fun buildPrivateKeywordGroups(plan: QueryPlan, query: String): List<List<String>> = buildList {
        val identityQuery = Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e|identity|id)\\b")
            .containsMatchIn(query)
        if (identityQuery) {
            val identity = Regex("(?i)\\b(aadhaar|aadhar|passport|pan|ssn|licen[cs]e)\\b")
                .find(query)?.value ?: "identity"
            val namedPeople = (
                database.namedPersonLabelsMentioned(query) +
                    plan.ocrTerms.flatMap(OcrKeywordPolicy::keywords)
            ).filterNot {
                it.lowercase() in IDENTITY_KEYWORD_STOP_WORDS
            }.distinctBy { it.lowercase() }
            // Identity queries require the named subject when one is present.
            // `number` is intentionally OCR-only; it is far too generic for
            // lexical document retrieval.
            add((listOf(identity.lowercase()) + namedPeople).distinct())
        } else {
            // Document lexical retrieval is one AND group. Person/location
            // are metadata fields for gallery, but plain text sources expose
            // them only through their indexed title/text/metadata.
            val terms = (
                plan.keywordTerms.flatMap(OcrKeywordPolicy::keywords) +
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
        val documentHits = documents.map { match ->
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
        return query
            .replace(
                Regex(
                    "(?i)\\b(?:today|yesterday|tomorrow|tonight|now|recent|latest|last|this|previous|next|" +
                        "year|years|month|months|week|weeks|day|days|morning|evening|night|" +
                        "before|after|during)\\b",
                ),
                " ",
            )
            .replace(Regex("\\b\\d{4}(?:[-/]\\d{1,2}(?:[-/]\\d{1,2})?)?\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { query }
    }

    private fun buildAnswerPrompt(
        query: String,
        results: List<GalleryMedia>,
        imageReferences: Map<Long, String>,
        faceImageReferences: Map<Long, String> = emptyMap(),
        evidenceScope: AnswerEvidenceScope,
        richVisualIds: Set<Long> = emptySet(),
        evidenceGroups: List<EvidenceGroup> = emptyList(),
        orderedEvidence: List<HybridSearchResult> = emptyList(),
        groundedFactValues: List<String> = emptyList(),
        groundedFactContext: String = "none",
    ): String {
        val includeImages = imageReferences.isNotEmpty()
        val documentMaps = ArrayList<String>(results.size)
        var rawOcrChars = 0
        var packedOcrChars = 0
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
                // OCR is often the largest joined text field. Keep it for an
                // explicit OCR/text question; scene/location/activity answers
                // still receive pixels, people, time, and location without
                // spending prompt budget on unrelated text.
                if (evidenceScope.needsOcr) {
                    val proofForAnswer: String
                    val proofLabel: String
                    val packed = OcrAnswerContextPacker.pack(query, media.ocrText)
                    rawOcrChars += packed.sourceChars
                    proofForAnswer = packed.proofLines
                    proofLabel = "document_facts"
                    documentMaps += "$label headings: ${packed.documentMap}"
                    packedOcrChars += proofForAnswer.length
                    val proof = proofForAnswer
                        .replace('"', '\'')
                        .ifBlank { "none" }
                    append("\n  $proofLabel:\n")
                    append(proof.prependIndent("  "))
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
        val amountEvidence = DocumentAmountGrounding.promptEvidence(query, results)
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
                append("IMAGE INPUTS: full gallery images map to RECORD labels: ")
                append(imageReferences.values.joinToString(", "))
                append(". Use every full image with its full record.")
                if (faceImageReferences.isNotEmpty()) {
                    append(" Additional face-crop images follow the full images in this order: ")
                    append(faceImageReferences.values.joinToString(", "))
                    append(". Each face crop is the named face from that same RECORD; use it to identify the person among the full-image people.")
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
            if (evidenceScope.needsOcr) add("OCR")
        }.joinToString(", ").ifBlank { "visual" }
        val documentMapBlock = if (evidenceScope.needsOcr) {
            "\nDOCUMENT_MAP (for follow-up ideas only; not answer evidence):\n" +
                documentMaps.joinToString("\n").ifBlank { "none" }
        } else {
            ""
        }
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
                    append(" media_store_id=")
                    append(media.mediaStoreId)
                    append(" content_uri=")
                    append(media.contentUri)
                    append(" display_name=")
                    append(media.displayName.ifBlank { "none" })
                    append(" mime_type=")
                    append(media.mimeType)
                    append(" content_class=")
                    append(media.contentClass.wireName)
                    append(" dimensions=")
                    append(if (media.width > 0 && media.height > 0) media.width.toString() + "x" + media.height else "none")
                    append(" size_bytes=")
                    append(media.sizeBytes)
                    append(" duration=")
                    append(formatDuration(media.durationMs))
                    append(" captured=")
                    append(formatDate(media.dateTakenMs))
                    append(" modified=")
                    append(formatModifiedDate(media.dateModifiedSeconds))
                    append(" location=")
                    append(formatLocation(media))
                    append(" person=")
                    append(media.personLabel ?: "none")
                    append(" person_cluster_id=")
                    append(media.personClusterId ?: "none")
                    append(" location_raw=")
                    append(media.location ?: "none")
                    append(" location_name=")
                    append(media.locationName ?: "none")
                    append(" location_state=")
                    append(media.locationEnrichmentState)
                    append(" image=")
                    append(if (media.mediaStoreId in richVisualIds) "attached" else "unavailable")
                    append("\nOCR_FULL:\n")
                    append(media.ocrText.ifBlank { "none" })
                }
                is HybridSearchResult.Document -> buildString {
                    val chunk = item.match.chunk
                    append(label)
                    append(" PRIVATE_RECORD source=")
                    append(chunk.source.displayName)
                    append(" title=")
                    append(chunk.title.ifBlank { "none" })
                    append(" record_key=")
                    append(chunk.recordKey)
                    append(" chunk=")
                    append(chunk.chunkNumber)
                    append(" page=")
                    append(chunk.page ?: "none")
                    append(" time=")
                    append(formatDate(chunk.timestampMs))
                    append(" uri=")
                    append(chunk.uri ?: "none")
                    append("\nMETADATA_FULL:\n")
                    append(chunk.metadata.ifBlank { "none" })
                    append("\nCONTENT_FULL:\n")
                    append(chunk.text.ifBlank { "none" })
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
            REQUESTED FACT RULE: ${AnswerFactGrounding.requestedFieldInstruction(query)}
            VERIFIED_AMOUNT_CANDIDATES: $amountEvidence
            $groundedFactBlock
            RECORDS:
            $evidence
            ORDERED_TOP_FOUR_RECORDS:
            $orderedRecordBlock
            $documentMapBlock$episodeBlock
            Use the QUESTION and all ORDERED_TOP_FOUR_RECORDS together to answer. Consider all supplied records and attached images before deciding. Return only a direct, natural answer in at most $MAX_ANSWER_GENERATED_TOKENS generated tokens, using as few words as possible. Do not mention records, evidence, OCR, metadata, images, sources, provenance, reasoning, or whether information is present or absent. Do not say that a word or value is or is not contained in the supplied material. Do not calculate or invent values. If multiple distinct values directly answer the question, state them briefly. Do not ask a follow-up question or repeat the question.
            ANSWER:
        """.trimIndent()
        Log.i(
            TAG,
            "Answer prompt: chars=${prompt.length}, records=${results.size}, " +
                "rows=${evidence.lineSequence().count()}, images=${imageReferences.size}, " +
                "episodes=${episodeEvidence.size}, " +
                "ocrMode=e4b-packed, " +
                "ocrRawChars=$rawOcrChars, ocrProofChars=$packedOcrChars",
        )
        return prompt
    }

    private fun buildDocumentEvidence(matches: List<DocumentMatch>): String {
        if (matches.isEmpty()) return ""
        val rows = matches.take(MAX_DOCUMENT_ANSWER_RECORDS).mapIndexed { index, match ->
            val chunk = match.chunk
            "D${index + 1} source=${chunk.source.displayName} title=${chunk.title} " +
                "time=${formatDate(chunk.timestampMs)} page=${chunk.page ?: "none"} " +
                "metadata=${chunk.metadata.ifBlank { "none" }} text=${chunk.text.take(MAX_DOCUMENT_EVIDENCE_CHARS)}"
        }
        return "\nNON_GALLERY_DOCUMENT_RECORDS:\n${rows.joinToString("\n")}\n"
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
                "DOCUMENT AMOUNT TASK: prioritize the record matching the requested title or merchant. Read its OCR labels and values together; prefer Total Amount or Grand Total over component fees or per-ticket prices. State the amount directly, and do not reject a matching ticket merely because no image is attached."
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
                    "DATE/TIMELINE TASK: rank the strongest matching dates from capture_time and OCR. Do not use the earliest record as a birthdate proxy. Distinguish an exact printed date from a photo-event date; if the exact fact is not stored, give the strongest supported candidate or shortlist."
                }
            Regex("\\b(where|location|place)\\b").containsMatchIn(normalized) ->
                "LOCATION TASK: answer with the strongest supplied location details and explicitly name the place. If only GPS text or a partial place is available, present it naturally rather than discarding it."
            Regex("\\b(who|whose|person|people)\\b").containsMatchIn(normalized) ->
                "IDENTITY TASK: use local person tags as the authoritative identity link and answer only from the joined person, place, time, and OCR fields."
            Regex("\\b(how many|count|number of|most|least)\\b").containsMatchIn(normalized) ->
                "COUNT/RANK TASK: count or rank only the scoped records and explain the basis briefly; do not replace a count with a generic evidence disclaimer."
            Regex("\\b(compare|comparison|difference|versus| vs )\\b").containsMatchIn(normalized) ->
                "COMPARISON TASK: compare the relevant groups or time periods directly using the supplied records, calling out missing or asymmetric evidence."
            Regex("\\b(without|excluding|except|not|no)\\b").containsMatchIn(normalized) ->
                "EXCLUSION TASK: answer from the positive set after applying the subtraction and state what remains instead of only describing the excluded set."
            else ->
                if (includeImages) {
                    "GENERAL SCENERY TASK: synthesize the downscaled scenery images with the joined metadata into a useful direct answer."
                } else {
                    "GENERAL TEXT TASK: synthesize the strongest identity, metadata, and OCR fields into a useful direct answer."
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

    private fun generateGroundedAnswer(
        gemma: GemmaRuntime,
        prompt: String,
        images: List<ByteArray>,
        usePrefilledAnswer: Boolean = true,
    ): String {
        val session = if (usePrefilledAnswer) {
            GemmaRuntime.takePrefilledAnswerSession(allowTextOnlyRawSession = images.isEmpty())
                ?: gemma.createAnswerConversation(ANSWER_SYSTEM_INSTRUCTION)
        } else {
            gemma.createAnswerConversation(ANSWER_SYSTEM_INSTRUCTION)
        }
        return try {
            val output = session.generate(prompt, images)
            lastAnswerProfile = session.lastGenerationProfile
            lastAnswerProfile?.takeIf { it.decodeTokens > MAX_ANSWER_GENERATED_TOKENS }?.let { profile ->
                Log.w(
                    TAG,
                    "Gemma answer exceeded the requested $MAX_ANSWER_GENERATED_TOKENS-token budget: " +
                        "decoded=${profile.decodeTokens}",
                )
            }
            output
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
    ): String {
        var generated = generateGroundedAnswer(gemma, prompt, images)
        if (looksLikePlannerOutput(generated) || AnswerOutputGuard.needsRetry(generated, query)) {
            Log.w(TAG, "Gemma returned non-answer protocol/text; retrying clean answer turn")
            generated = generateGroundedAnswer(
                gemma,
                "$prompt\nIMPORTANT: Return only the direct natural-language answer. State the requested supported value plainly, without reproducing OCR/evidence lines, adding follow-ups, task text, JSON, routing keys, code, a query plan, or the question. Never start with QUERY:, QUESTION:, or FOLLOW_UPS:.",
                images,
                usePrefilledAnswer = false,
            )
        }
        return if (looksLikePlannerOutput(generated) || AnswerOutputGuard.needsRetry(generated, query)) {
            Log.e(TAG, "Gemma repeated non-answer protocol/text; using grounded UI fallback")
            groundedAnswerFallback(query, results)
        } else {
            generated
        }
    }

    /**
     * A bounded structured verification pass. Every turn gets exactly the
     * same selected evidence and image bytes as the draft; it cannot retrieve
     * a tempting but unrelated result. `KEEP` exits immediately, while at
     * most two corrective turns are permitted.
     */
    private fun reviewGroundedAnswer(
        gemma: GemmaRuntime,
        query: String,
        draft: String,
        results: List<GalleryMedia>,
        documents: List<DocumentMatch>,
        orderedEvidence: List<HybridSearchResult>,
        faceImageReferences: Map<Long, String>,
        images: List<ByteArray>,
        groundedFactValues: List<String> = emptyList(),
        groundedFactContext: String = "none",
    ): String {
        val gate = AnswerReviewGate.reason(query, draft, results)
            ?: AnswerReviewGate.Reason.DIRECT_FIELD.takeIf { groundedFactValues.isNotEmpty() }
        if (gate == null) {
            Log.i(TAG, "Answer evidence review skipped: routine grounded answer")
            return draft
        }
        Log.i(TAG, "Answer evidence review enabled: ${gate.logLabel}")
        val requiredValues = groundedFactValues
            .distinctBy { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
        var current = draft
        repeat(MAX_ANSWER_REVIEW_TURNS) { attempt ->
            val draftCheck = AnswerFactGrounding.checkDraft(current, requiredValues)
            val missingValues = draftCheck.missingValues
            val review = generateAnswerReview(
                gemma = gemma,
                prompt = buildAnswerReviewPrompt(
                    query,
                    current,
                    results,
                    documents,
                    orderedEvidence,
                    faceImageReferences,
                    requiredValues,
                    missingValues,
                    groundedFactContext,
                ),
                images = images,
            )
            val replacement = parseAnswerReview(review)
            if (replacement == null) {
                if (missingValues.isEmpty()) {
                    Log.i(TAG, "Answer evidence review kept draft on pass ${attempt + 1}")
                    return current
                }
                Log.w(
                    TAG,
                    "Answer evidence review tried KEEP with missing distinct values=" +
                        missingValues.size + " on pass ${attempt + 1}",
                )
                if (attempt == MAX_ANSWER_REVIEW_TURNS - 1) return current
                return@repeat
            }
            val constrained = DocumentAmountGrounding.constrainAnswer(query, replacement, results)
            val replacementCheck = AnswerFactGrounding.checkDraft(constrained, requiredValues)
            if (requiredValues.isNotEmpty() && replacementCheck.needsRepair) {
                Log.w(
                    TAG,
                    "Answer evidence review produced a replacement missing grounded values=" +
                        replacementCheck.missingValues.size + "; retaining current draft",
                )
                return@repeat
            }
            if (AnswerTextSanitizer.clean(constrained) == AnswerTextSanitizer.clean(current)) {
                Log.i(TAG, "Answer evidence review converged on pass ${attempt + 1}")
                return current
            }
            Log.i(TAG, "Answer evidence review corrected draft on pass ${attempt + 1}")
            current = constrained
        }
        return current
    }

    private fun generateAnswerReview(
        gemma: GemmaRuntime,
        prompt: String,
        images: List<ByteArray>,
    ): String {
        val session = gemma.createAnswerConversation(ANSWER_REVIEW_SYSTEM_INSTRUCTION)
        return try {
            session.generate(prompt, images)
        } finally {
            session.close()
        }
    }

    private fun buildAnswerReviewPrompt(
        query: String,
        draft: String,
        results: List<GalleryMedia>,
        documents: List<DocumentMatch>,
        orderedEvidence: List<HybridSearchResult>,
        faceImageReferences: Map<Long, String> = emptyMap(),
        requiredValues: List<String> = emptyList(),
        missingValues: List<String> = emptyList(),
        groundedFactContext: String = "none",
    ): String {
        val records = orderedEvidence.mapIndexed { index, item ->
            val label = "R${index + 1}"
            when (item) {
                is HybridSearchResult.Gallery -> {
                    val media = item.media
                    "$label GALLERY_RECORD display_name=${media.displayName} person=${media.personLabel ?: "none"} " +
                        "captured=${formatDate(media.dateTakenMs)} location=${formatLocation(media)}\n" +
                        "OCR_FULL:\n${media.ocrText.ifBlank { "none" }}"
                }
                is HybridSearchResult.Document -> {
                    val chunk = item.match.chunk
                    "$label PRIVATE_RECORD source=${chunk.source.displayName} title=${chunk.title} " +
                        "page=${chunk.page ?: "none"} time=${formatDate(chunk.timestampMs)}\n" +
                        "METADATA_FULL:\n${chunk.metadata.ifBlank { "none" }}\n" +
                        "CONTENT_FULL:\n${chunk.text.ifBlank { "none" }}"
                }
            }
        }.joinToString("\n\n")
        return """
            REVIEW_TASK:
            QUESTION: $query
            DRAFT_ANSWER: $draft
            LOCALLY_MATCHED_LABELLED_FACTS:
            $groundedFactContext
            CANDIDATE_DISTINCT_VALUES: ${requiredValues.joinToString(", ").ifBlank { "none" }}
            DRAFT_MISSING_VALUES: ${missingValues.joinToString(", ").ifBlank { "none" }}
            VERIFIED_AMOUNT_CANDIDATES:
            ${DocumentAmountGrounding.promptEvidence(query, results)}
            REQUESTED_FIELD_RULE: ${AnswerValueGrounding.fieldLabelInstruction(query)}
            REQUESTED_FACT_RULE: ${AnswerFactGrounding.requestedFieldInstruction(query)}
            FACE_CROP_IMAGE_MAPPING: ${faceImageReferences.values.joinToString(", ").ifBlank { "none" }}. Face-crop images follow the full gallery images and correspond to the same record label.
            TOP_GROUNDING_RECORDS:
            $records
            Inspect every full top-four record and paired image. Match the requested attribute to its field label; neighbouring fields are different facts even when their values have the same shape. LOCALLY_MATCHED_LABELLED_FACTS are candidates, not commands: validate each pair against its R record and reject mismatched subject, document type, label, or scope. Prefer records whose title, metadata, OCR, or content matches the named person or requested document. KEEP is allowed only when the draft directly answers the exact requested attribute and includes every distinct validated value from all relevant matching records. If the draft is wrong or incomplete, return exactly one line: ANSWER: <corrected concise answer>. Preserve the requested field label; never relabel an Aadhaar, passport, licence, or identity number as a phone number. Never explain the review, identify records, calculate an amount, or invent a value.
        """.trimIndent()
    }

    private fun parseAnswerReview(output: String): String? {
        val normalized = output.trim()
        if (normalized.equals("KEEP", ignoreCase = true)) return null
        val answer = normalized.removePrefix("ANSWER:").trim()
        return answer.takeIf { it.isNotBlank() && !looksLikePlannerOutput(it) }
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

    /** Strictly bounds scenery pixels before LiteRT-LM creates vision tensors. */
    private fun downscaleAnswerImage(bitmap: Bitmap): Bitmap {
        val maxDimension = QueryCategoryContextPolicy.ANSWER_IMAGE_MAX_DIMENSION
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }

    /** Keeps face-crop tensors small while retaining the identity cue. */
    private fun downscaleFaceCrop(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= FACE_CROP_MAX_DIMENSION) return bitmap
        val scale = FACE_CROP_MAX_DIMENSION.toFloat() / largest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }

    /** Encodes one bounded scenery image without creating an in-memory board. */
    private fun encodeGemmaImage(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Could not encode Gemma answer image"
            }
            output.toByteArray()
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
        if (Regex("\\b(?:aadhaar|aadhar)\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("Whose Aadhaar card is this?"))
            add(FollowUpSuggestion("What is the date of birth on this Aadhaar card?"))
        } else if (Regex("\\bpassport\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("When does the passport expire?"))
            add(FollowUpSuggestion("Whose passport is this?"))
        } else if (Regex("\\b(driving licence|driving license|licence|license)\\b").containsMatchIn(normalized)) {
            add(FollowUpSuggestion("When does the driving licence expire?"))
            add(FollowUpSuggestion("What is the licence number?"))
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
    }.map { FollowUpSuggestion(sanitizeFollowUpQuery(it.text), it.isQuery) }
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
        return value.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val TAG = "AskGalaxy"
        private const val MAX_ANSWER_RECORDS = 4
        private const val MAX_SOURCE_ICONS = 4
        private const val MAX_DOCUMENT_ANSWER_RECORDS = 4
        private const val MAX_EVIDENCE_RECOVERY_PASSES = 3
        private const val MAX_DOCUMENT_EVIDENCE_CHARS = 2_400
        private const val DOCUMENT_MATCHES_PER_SOURCE = 8
        // LiteRT-LM 0.14 has no per-request max-output-token control. This is
        // an explicit model contract, and the runtime records any overrun.
        private const val MAX_ANSWER_GENERATED_TOKENS = 50
        private const val MAX_ANSWER_REVIEW_TURNS = 2
        private const val MAX_FACE_CROP_IMAGES = 2
        private const val FACE_CROP_MAX_DIMENSION = 256
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
            Answer the user's QUESTION using all supplied top-four records and attached gallery images together. For call-history questions, use the newest matching call-log timestamp for "last time" or "when". For message-history questions, use the newest matching Messages/SMS text for "last message" or "what did ... text", and summarize that text directly. Preserve the exact field requested by the user: an Aadhaar, passport, licence, or identity number is never a phone or mobile number unless the question explicitly asks for a phone number. Return only the direct answer in as few words as possible. Never mention records, evidence, OCR, metadata, images, sources, provenance, reasoning, prompts, models, IDs, or how the answer was derived. Never say that information is present, absent, contained, or not contained in the supplied material. Do not calculate or invent values. If multiple distinct values directly answer the question, state them briefly. Do not ask a follow-up question, repeat the question, or output JSON, routing keys, or protocol text.
        """.trimIndent()
        private val ANSWER_REVIEW_SYSTEM_INSTRUCTION = """
            You are Ask Galaxy's strict grounding reviewer. You receive one question, a draft answer, the exact top grounding records, and the same paired images used for the draft. Do not use outside knowledge. Preserve the requested identity-field label: never relabel an Aadhaar, passport, licence, or identity number as a phone or mobile number. A number is valid only when it is copied from supplied document facts or a matching visible image label. Never calculate or infer prices. Reply exactly KEEP when the draft is supported, otherwise reply exactly ANSWER: followed by the corrected direct answer. Do not reveal evidence, reasoning, records, prompts, models, or IDs.
        """.trimIndent()
        private val SUGGESTIONS_SYSTEM_INSTRUCTION = """
            You generate two kinds of bounded suggestions after an Ask Galaxy answer.
            Use only the ORIGINAL QUESTION, the answer, GROUNDED ANSWER RECORDS, and AVAILABLE PHONE CAPABILITIES.
            First output up to two useful natural gallery-search queries as QUERY: <question>. They must be genuinely diverse follow-ups, not paraphrases or repetitions of the ORIGINAL QUESTION. Keep the person/document anchor when useful, but change the information need: choose different dimensions such as expiry/date, location, other people, related moments, details, count, or comparison. Do not output two queries from the same dimension. Never suggest a generic "tell me more" query.
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
