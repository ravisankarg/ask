package com.ravi.askgalaxy

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
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
    val contextCount: Int = 0,
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
    val timings: PhaseTimings = PhaseTimings(),
)

class GalleryIndexer(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val database = GalleryDatabase(appContext)
    private val personalContextDatabase = PersonalContextDatabase(appContext)
    private val semanticIndexer = GallerySemanticIndexer(appContext, database)
    private val metadataReader = GalleryMetadataReader(appContext)
    private val structuredSearchExecutor =
        StructuredSearchExecutor(database, semanticIndexer, metadataReader, appContext)
    private val evidenceBuilder = EvidenceBuilder(database)
    private val answerContextPicker = AnswerContextPicker(semanticIndexer)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
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
        onMatches: (List<GalleryMedia>, Int) -> Unit = { _, _ -> },
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
                    val uiGallery = SearchResultPresentationPolicy.top(
                        rankedCandidates = candidates,
                        limit = UI_RESULT_LIMIT,
                    )
                    // Publish the practical browsing window before episode
                    // joining, Context Picker reranking, or Gemma answer work.
                    runCatching { onMatches(uiGallery, candidates.size) }
                    val contextMatches = if (
                        searchExecution.answerEvidenceScope.needsPersonalContext &&
                        PersonalContextSettings.isEnabled(appContext) &&
                        PersonalContextAccess.isNotificationListenerEnabled(appContext)
                    ) {
                        personalContextDatabase.search(query, CONTEXT_RETRIEVAL_LIMIT)
                    } else {
                        emptyList()
                    }
                    timings = timings.copy(
                        searchMs = elapsedMs(planningFinished, System.nanoTime()),
                    )
                    onProgress(
                        SearchProgress(
                            SearchStage.HYBRID_RETRIEVAL,
                            candidates.size,
                            contextMatches.size,
                            searchExecution.answerEvidenceScope,
                            searchExecution.plannerJson,
                            searchExecution.effectivePlanJson,
                            timings,
                        ),
                    )
                    onProgress(
                        SearchProgress(
                            SearchStage.DIVERSE_EVIDENCE,
                            candidates.size,
                            contextMatches.size,
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
                        useKvIndex = KvIndexPreferences.isEnabled(appContext),
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
                        personalContext = contextMatches.take(CONTEXT_ANSWER_LIMIT),
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
            var deferredFollowUps: (() -> Unit)? = null
            val result = try {
                runCatching {
                check(GemmaRuntime.isModelInstalled(appContext)) {
                    "Gemma 4 is not installed yet"
                }
                val evidenceScope = response.answerContext?.metadataFields
                    ?.let(response.answerEvidenceScope::withMetadataFields)
                    ?: response.answerEvidenceScope
                val attachedResults = ArrayList<GalleryMedia>(MAX_ANSWER_RECORDS)
                val selectedModel = GemmaModelSelection.selected(appContext)
                val useE2bDocumentImages =
                    selectedModel == GemmaModelVariant.E2B && response.queryCategory == QueryCategory.DOC
                val answerImageLimit =
                    QueryCategoryContextPolicy.answerImageLimit(response.queryCategory, selectedModel)
                val loadedEvidence = ArrayList<Pair<GalleryMedia, Bitmap>>(answerImageLimit)
                // A present AnswerContextBundle is authoritative even when its
                // strict category filter found zero eligible rows. Never fall
                // back to unrelated search results in that case.
                val selectedContextRecords = (
                    response.answerContext?.records
                        ?: response.answerGallery.ifEmpty {
                            response.gallery.take(MAX_ANSWER_RECORDS)
                        }
                    ).take(MAX_ANSWER_RECORDS)
                val includeVisuals = answerImageLimit > 0 && (
                    useE2bDocumentImages ||
                        (response.answerContext?.includeVisuals
                            ?: response.answerEvidenceScope.needsVisual)
                    )
                val visualGallery = if (includeVisuals) {
                    selectedContextRecords.take(answerImageLimit)
                } else {
                    emptyList()
                }
                val visualIds = visualGallery.map { it.mediaStoreId }.toSet()
                // The Context Picker is the complete answer context. Enrich
                // only the fields its policy selected; never append a second
                // uncurated metadata tail after the selected records.
                val visualContextFields = response.answerContext?.metadataFields
                    ?: evidenceScope.metadataFields
                val enrichedContextRecords = selectedContextRecords.map {
                    metadataReader.enrich(it, visualContextFields)
                }
                MediaBitmapLoader(appContext).use { loader ->
                    enrichedContextRecords
                        .filter { it.mediaStoreId in visualIds }
                        .forEach { enrichedMedia ->
                        val bitmap = loader.load(
                            enrichedMedia,
                            maxDimension =
                                QueryCategoryContextPolicy.ANSWER_IMAGE_MAX_DIMENSION,
                            applyExifOrientation = true,
                        )
                            ?: return@forEach
                        loadedEvidence += enrichedMedia to downscaleAnswerImage(bitmap)
                    }
                }
                // Keep all eight selected OCR/metadata records even when only
                // the first four scenery records receive image inputs.
                attachedResults += enrichedContextRecords
                val imageReferences = loadedEvidence.mapNotNull { (media, _) ->
                    attachedResults.indexOfFirst { it.mediaStoreId == media.mediaStoreId }
                        .takeIf { it >= 0 }
                        ?.let { "G${it + 1}" }
                }
                val imageBytes = try {
                    loadedEvidence.map { (_, bitmap) -> encodeGemmaImage(bitmap) }
                } finally {
                    loadedEvidence.forEach { (_, bitmap) ->
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
                    "Answer category=${response.queryCategory.wireName}, scope=${evidenceScope.label()}, " +
                        "records=${attachedResults.size}, " +
                        "visualCandidates=${visualGallery.size}, decodedVisuals=${loadedEvidence.size}, " +
                        "images=${imageBytes.size}, " +
                        "ocr=${evidenceScope.needsOcr}, metadata=${evidenceScope.needsMetadata}",
                )
                check(imageBytes.isNotEmpty() || attachedResults.isNotEmpty() || response.personalContext.isNotEmpty()) {
                    "No readable result evidence for Gemma"
                }
                val gemma = GemmaRuntime.shared(appContext)
                lastAnswerProfile = null
                val answerStarted = System.nanoTime()
                val generatedOutput = try {
                    val answerPrompt = buildAnswerPrompt(
                        query,
                        attachedResults,
                        response.personalContext,
                        imageReferences = imageReferences,
                        evidenceScope = evidenceScope,
                        richVisualIds = richVisualIds,
                        evidenceGroups = response.evidenceGroups,
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
                            attachedResults,
                            response.personalContext,
                            imageReferences = emptyList(),
                            evidenceScope = evidenceScope,
                            richVisualIds = emptySet(),
                            evidenceGroups = response.evidenceGroups,
                        ),
                        images = emptyList(),
                        query = query,
                        results = attachedResults,
                    )
                }
                val output = reviewGroundedAnswer(
                    gemma = gemma,
                    query = query,
                    draft = if (KvIndexPreferences.isEnabled(appContext)) generatedOutput else
                        DocumentAmountGrounding.constrainAnswer(query, generatedOutput, attachedResults),
                    results = attachedResults,
                    images = imageBytes,
                )
                val eventGroundedOutput = EventPhotoDateGrounding.constrainAnswer(
                    query,
                    output,
                    attachedResults,
                )
                val answerGenerationMs = elapsedMs(answerStarted, System.nanoTime())
                check(eventGroundedOutput.isNotBlank()) { "Gemma returned an empty answer" }
                val followUpStarted = System.nanoTime()
                val parsedOutput = parseAnswer(eventGroundedOutput)
                val parsed = if (parsedOutput.text.isBlank()) {
                    Log.w(TAG, "Gemma answer became empty after public-output sanitation; using grounded fallback")
                    parsedOutput.copy(text = groundedAnswerFallback(query, attachedResults))
                } else {
                    parsedOutput
                }
                val followUps = (parsed.followUps + defaultFollowUps(
                    query,
                    attachedResults,
                    response.personalContext,
                ))
                    .distinctBy { it.text.lowercase() }
                    .take(MAX_FOLLOW_UPS)
                val followUpMs = elapsedMs(followUpStarted, System.nanoTime())
                val followUpEvidence = buildFollowUpEvidence(attachedResults)
                deferredFollowUps = {
                    val generatedStarted = System.nanoTime()
                    runCatching {
                        generateFollowUps(
                            gemma = gemma,
                            query = query,
                            answer = parsed.text,
                            topFourEvidence = followUpEvidence,
                        )
                    }.onSuccess { generated ->
                        onFollowUps(if (generated.isNotEmpty()) generated else followUps)
                        Log.i(
                            TAG,
                            "Async follow-up generation: ${elapsedMs(generatedStarted, System.nanoTime())}ms, " +
                                "count=${generated.size}",
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
                    sources = buildAnswerSources(attachedResults, response.personalContext),
                    followUps = followUps,
                    timings = response.timings
                        .withAnswerTimings(answerGenerationMs, followUpMs)
                        .withAnswerProfile(lastAnswerProfile),
                    plannerJson = response.plannerJson,
                    effectivePlanJson = response.effectivePlanJson,
                )
                }.onFailure { error ->
                    Log.e(TAG, "Gemma answer generation failed", error)
                }
            } finally {
                plannerSession?.close()
            }
            if (warmAnswerAfterAnswer) {
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
                val useKvIndex = KvIndexPreferences.isEnabled(appContext) &&
                    currentResponse.queryCategory == QueryCategory.DOC
                val semanticScores = if (useKvIndex) {
                    runCatching {
                        KvDocumentVectorIndex.open(appContext).use { index ->
                            val embedding = SigLipTextEncoder.shared(appContext).encode(query)
                            val result = index.search(
                                embedding,
                                currentResults.size.coerceAtLeast(1),
                                currentResults.map { it.mediaStoreId }.toLongArray(),
                            )
                            result.ids.mapIndexed { position, id ->
                                id to result.scores.getOrElse(position) { 0f }
                            }.toMap()
                        }
                    }.getOrDefault(emptyMap())
                } else semanticIndexer.searchNearestScoredBlocking(
                    queries = listOf(query),
                    limit = currentResults.size.coerceAtLeast(1),
                    allowlist = currentResults.map { it.mediaStoreId }.toLongArray(),
                ).associate { it.mediaStoreId to it.score }
                val minSemantic = semanticScores.values.minOrNull() ?: 0f
                val maxSemantic = semanticScores.values.maxOrNull() ?: 0f
                val ocrScores = currentResults.associate { media ->
                    media.mediaStoreId to if (useKvIndex) 0 else followUpOcrLineMatchScore(query, media)
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
                val inheritedOcrKeywords = if (useKvIndex) emptyList() else currentResponse.answerOcrKeywords
                    .ifEmpty { followUpOcrKeywords(query) }
                val pickedFollowUpContext = answerContextPicker.pick(
                    query = query,
                    rankedCandidates = hybridRankedResults,
                    evidenceGroups = currentResponse.evidenceGroups,
                    evidenceScope = followUpScope,
                    queryCategory = followUpCategory,
                    ocrKeywords = inheritedOcrKeywords,
                    useKvIndex = useKvIndex,
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

    fun indexEmbeddingsBlocking(onProgress: (EmbeddingProgress) -> Unit = {}): EmbeddingProgress =
        semanticIndexer.indexBlocking(onProgress)

    fun close() {
        executor.shutdown()
        previewExecutor.shutdown()
        answerExecutor.shutdown()
        semanticIndexer.close()
        database.close()
        personalContextDatabase.close()
    }

    private fun searchBlocking(
        query: String,
        onQueryPlanned: (
            plannerJson: String,
            effectivePlanJson: String,
            plannerProfile: GemmaRuntime.GenerationProfile?,
        ) -> Unit = { _, _, _ -> },
    ): SearchExecution {
        val plannedQuery = QueryPlannerRuntime.planWithSession(
            appContext,
            query,
            database.namedPersonLabelsForPlanning(),
            database.selfPersonLabelForPlanning(),
        )
        // The planner KV is useful only for the planning turn. Answering uses
        // a clean conversation, so holding this session through SQLite,
        // native retrieval, and diversity only increases memory pressure.
        plannedQuery.session?.close()
        // Warm only the clean answer system prefix while retrieval and context
        // selection run. The planner turn is already closed and can never enter
        // the answer conversation.
        GemmaRuntime.preloadAnswerAsync(appContext, ANSWER_SYSTEM_INSTRUCTION)
        var plan = plannedQuery.plan
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
            val effectivePlan = plan.copy(
                semanticQueries = scopedSemanticQueries,
                personNames = personLabels,
                excludedPersonNames = excludedPersonLabels,
                negativeSemanticQueries = negativeSemanticQueries,
                executionSpec = resolvedExecutionSpec,
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
                    plannerSession = null,
                    plannerJson = plannedQuery.plannerJson,
                    effectivePlanJson = "(empty)",
                    queryCategory = effectivePlan.queryCategory,
                    needsAnswer = effectivePlan.needsAnswer,
                    plannerProfile = plannedQuery.generationProfile,
                    answerEvidenceScope = effectivePlan.answerEvidenceScope,
                    ocrKeywords = if (KvIndexPreferences.isEnabled(appContext)) emptyList() else effectivePlan.ocrTerms.flatMap(OcrKeywordPolicy::keywords),
                )
            }
            val renderedExecutionSpec = executionSpec.render()
            onQueryPlanned(
                plannedQuery.plannerJson,
                renderedExecutionSpec,
                plannedQuery.generationProfile,
            )
            Log.i(TAG, "Executing QP spec: ${renderedExecutionSpec.take(600)}")
            val candidates = structuredSearchExecutor.execute(executionSpec)
            return SearchExecution(
                candidates = candidates,
                plannerSession = null,
                plannerJson = plannedQuery.plannerJson,
                effectivePlanJson = renderedExecutionSpec,
                queryCategory = effectivePlan.queryCategory,
                needsAnswer = effectivePlan.needsAnswer,
                plannerProfile = plannedQuery.generationProfile,
                answerEvidenceScope = effectivePlan.answerEvidenceScope,
                ocrKeywords = if (KvIndexPreferences.isEnabled(appContext)) emptyList() else effectivePlan.ocrTerms.flatMap(OcrKeywordPolicy::keywords),
            )
        }
    }

    private fun buildAnswerPrompt(
        query: String,
        results: List<GalleryMedia>,
        contextMatches: List<PersonalContextMatch>,
        imageReferences: List<String>,
        evidenceScope: AnswerEvidenceScope,
        richVisualIds: Set<Long> = emptySet(),
        evidenceGroups: List<EvidenceGroup> = emptyList(),
    ): String {
        val includeImages = imageReferences.isNotEmpty()
        val useKvIndex = KvIndexPreferences.isEnabled(appContext)
        val useFullDocumentOcr = GemmaModelSelection.selected(appContext) == GemmaModelVariant.E2B
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
                    if (useKvIndex) {
                        // KV mode deliberately bypasses query-word OCR packing.
                        // Retrieval and answer grounding both consume only the
                        // model-produced document facts.
                        proofForAnswer = media.kvText.trim().ifBlank { "none" }
                        proofLabel = "document_facts"
                        rawOcrChars += proofForAnswer.length
                        documentMaps += "$label fields: $proofForAnswer"
                    } else {
                        val packed = OcrAnswerContextPacker.pack(query, media.ocrText)
                        rawOcrChars += packed.sourceChars
                        proofForAnswer = if (useFullDocumentOcr) {
                            media.ocrText.trim().ifBlank { "none" }
                        } else {
                            packed.proofLines
                        }
                        proofLabel = "document_facts"
                        documentMaps += "$label headings: ${packed.documentMap}"
                    }
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
        val amountEvidence = if (useKvIndex) {
            "none (KV index mode)"
        } else {
            DocumentAmountGrounding.promptEvidence(query, results)
        }
        val contextEvidence = if (contextMatches.isEmpty()) {
            "none"
        } else {
            contextMatches.mapIndexed { index, match ->
                val item = match.item
                "C${index + 1} ${item.title.ifBlank { item.kind.displayName }} " +
                    contextSummary(item)
            }.joinToString("\n")
        }
        val inputInstruction = when {
            includeImages ->
                "IMAGE INPUTS: the four-or-fewer downscaled images map in order to " +
                    imageReferences.joinToString(", ") + ". For document questions, use each " +
                    "image with that record's DOCUMENT_FACTS to distinguish documents and confirm label/value " +
                    "layout; document facts remain primary for exact text."
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
        val contextBlock = if (evidenceScope.needsPersonalContext) {
            "\nPERSONAL_CONTEXT:\n$contextEvidence"
        } else {
            ""
        }
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
        val prompt = """
            ANSWER_TASK:
            QUESTION: $query
            MODE: $answerDirective
            INPUT: $inputInstruction
            TRUSTED FIELDS: $selectedFields. Local person tags are authoritative; use capture time before a modified fallback; explicitly name relevant places.
            VERIFIED_AMOUNT_CANDIDATES: $amountEvidence
            RECORDS:
            $evidence
            $documentMapBlock$contextBlock$episodeBlock
            Return only a direct, natural answer in at most $MAX_ANSWER_GENERATED_TOKENS generated tokens. For a direct document-field question, output exactly one concise sentence and stop. First compare the requested field across every supplied DOCUMENT_FACTS record and its matching visual tile: if one distinct supported value exists, state it; if two or more distinct supported values exist, state every distinct value rather than silently choosing one. When supplied, a VERIFIED_AMOUNT_CANDIDATE is an exact document-backed value: copy only such a value, never calculate, infer, or substitute a plausible amount. Pair a value with an issue/expiry date only when that date is clearly associated in the same document facts; otherwise call them values from separate documents. Do not count duplicate scans of the same value twice. Do not reproduce document-fact/evidence lines, but never suppress another direct supported value merely because the question is singular. Do not include a follow-up question, suggestion, question mark, source, provenance, or explanation of how the answer was found. Use a calibrated caveat only for a missing exact fact.
            ANSWER:
        """.trimIndent()
        Log.i(
            TAG,
            "Answer prompt: chars=${prompt.length}, records=${results.size}, " +
                "rows=${evidence.lineSequence().count()}, images=${imageReferences.size}, " +
                "episodes=${episodeEvidence.size}, context=${contextMatches.size}, " +
                "ocrMode=${if (useFullDocumentOcr) "e2b-full" else "e4b-packed"}, " +
                "ocrRawChars=$rawOcrChars, ocrProofChars=$packedOcrChars",
        )
        return prompt
    }

    /** A value-redacted inventory of every selected answer record. */
    private fun buildFollowUpEvidence(results: List<GalleryMedia>): String = results
        .take(MAX_ANSWER_RECORDS)
        .mapIndexed { index, media ->
            val fields = if (KvIndexPreferences.isEnabled(appContext)) {
                media.kvText.trim().ifBlank { "none" }
            } else {
                OcrAnswerContextPacker.pack("", media.ocrText).documentMap
            }
            buildString {
                append("G${index + 1}: fields=$fields")
                media.personLabel?.trim()?.takeIf(String::isNotBlank)?.let { append("; person=$it") }
                media.locationName?.trim()?.takeIf(String::isNotBlank)?.let { append("; place=$it") }
                media.dateTakenMs?.let { append("; captured=${formatDate(it).substringBefore(' ')}") }
            }
        }
        .joinToString("\n")
        .take(MAX_FOLLOW_UP_EVIDENCE_CHARS)
        .ifBlank { "none" }

    private fun generateFollowUps(
        gemma: GemmaRuntime,
        query: String,
        answer: String,
        topFourEvidence: String,
    ): List<FollowUpSuggestion> {
        val session = gemma.createAnswerConversation(FOLLOW_UP_SYSTEM_INSTRUCTION)
        return try {
            val output = session.generate(
                """
                ORIGINAL QUESTION: $query
                ANSWER ALREADY GIVEN: $answer
                TOP 4 ANSWER RECORDS:
                $topFourEvidence
                """.trimIndent(),
            )
            output.lineSequence()
                .map(String::trim)
                .mapNotNull { line ->
                    line.indexOf("QUERY:", ignoreCase = true)
                        .takeIf { it >= 0 }
                        ?.let { line.substring(it + "QUERY:".length) }
                        ?.let(::sanitizeFollowUpQuery)
                        ?.takeIf { isUsefulFollowUp(it, query, answer) }
                        ?.takeIf(String::isNotBlank)
                        ?.let { FollowUpSuggestion(it) }
                }
                .distinctBy { it.text.lowercase() }
                .take(MAX_FOLLOW_UPS)
                .toList()
        } finally {
            session.close()
        }
    }

    private fun isUsefulFollowUp(value: String, previousQuery: String, previousAnswer: String): Boolean {
        val normalized = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (normalized.length < 8 || normalized == previousQuery.lowercase().trim()) return false
        if (Regex("\\b(?:tell me more|more details|what else|nearby|about it)\\b").containsMatchIn(normalized)) {
            return false
        }
        val answerTokens = Regex("[\\p{L}\\p{N}]{4,}")
            .findAll(previousAnswer.lowercase())
            .map { it.value }
            .toSet()
        return normalized.split(' ').any { it.length >= 4 && it !in answerTokens }
    }

    private fun answerDirective(query: String, includeImages: Boolean): String {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        return when {
            Regex("\\b(how much|spend|spent|cost|price|amount|total|paid)\\b")
                .containsMatchIn(normalized) ->
                "DOCUMENT AMOUNT TASK: prioritize the record matching the requested title or merchant. Read its OCR labels and values together; prefer Total Amount or Grand Total over component fees or per-ticket prices. State the amount directly, and do not reject a matching ticket merely because no image is attached."
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
                    "GENERAL TEXT TASK: synthesize the strongest identity, metadata, OCR, and personal-context fields into a useful direct answer."
                }
        }
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
     * A short ReAct-style verification pass. Every turn gets exactly the same
     * selected evidence and image bytes as the draft; it cannot retrieve a
     * tempting but unrelated result. `KEEP` exits immediately, while at most
     * three corrective turns are permitted for a genuinely disputed answer.
     */
    private fun reviewGroundedAnswer(
        gemma: GemmaRuntime,
        query: String,
        draft: String,
        results: List<GalleryMedia>,
        images: List<ByteArray>,
    ): String {
        val useKvIndex = KvIndexPreferences.isEnabled(appContext)
        val gate = AnswerReviewGate.reason(query, draft, results, useKvIndex)
        if (gate == null) {
            Log.i(TAG, "Answer evidence review skipped: routine grounded answer")
            return draft
        }
        Log.i(TAG, "Answer evidence review enabled: ${gate.logLabel}")
        var current = draft
        repeat(MAX_ANSWER_REVIEW_TURNS) { attempt ->
            val review = generateAnswerReview(
                gemma = gemma,
                prompt = buildAnswerReviewPrompt(query, current, results),
                images = images,
            )
            val replacement = parseAnswerReview(review) ?: run {
                Log.i(TAG, "Answer evidence review kept draft on pass ${attempt + 1}")
                return current
            }
            val constrained = if (useKvIndex) replacement else
                DocumentAmountGrounding.constrainAnswer(query, replacement, results)
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
    ): String {
        val useKvIndex = KvIndexPreferences.isEnabled(appContext)
        val records = results.mapIndexed { index, media ->
            val proof = if (useKvIndex) {
                media.kvText.trim().ifBlank { "none" }
            } else if (GemmaModelSelection.selected(appContext) == GemmaModelVariant.E2B) {
                media.ocrText.trim().ifBlank { "none" }
            } else {
                OcrAnswerContextPacker.pack(query, media.ocrText).proofLines
            }
            "G${index + 1} DOCUMENT_FACTS:\n${proof.prependIndent("  ")}"
        }.joinToString("\n")
        return """
            REVIEW_TASK:
            QUESTION: $query
            DRAFT_ANSWER: $draft
            VERIFIED_AMOUNT_CANDIDATES:
            ${if (useKvIndex) "none (KV index mode)" else DocumentAmountGrounding.promptEvidence(query, results)}
            TOP_GROUNDING_RECORDS:
            $records
            Inspect the supplied document facts and paired images. If the draft is fully supported and directly answers the question, return exactly KEEP. Otherwise return exactly one line: ANSWER: <corrected concise answer>. Never explain the review, identify records, calculate an amount, or invent a value.
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
        val maxDimension = QueryCategoryContextPolicy.SCENARY_ANSWER_IMAGE_MAX_DIMENSION
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
        contextMatches: List<PersonalContextMatch>,
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
        if (Regex("\\bpassport\\b").containsMatchIn(normalized)) {
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
        if (isEmpty() && contextMatches.isNotEmpty()) {
            add(FollowUpSuggestion("What happened nearby?"))
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
        results: List<GalleryMedia>,
        contextMatches: List<PersonalContextMatch>,
    ): List<AnswerSource> {
        val sources = ArrayList<AnswerSource>(results.size + contextMatches.size)
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
                    val documentFacts = if (KvIndexPreferences.isEnabled(appContext)) media.kvText else media.ocrText
                    if (documentFacts.isNotBlank()) {
                        append(if (KvIndexPreferences.isEnabled(appContext)) "\nDocument facts: " else "\nOCR: ")
                        append(compactPromptText(documentFacts))
                    }
                    media.personLabel?.takeIf { it.isNotBlank() }?.let {
                        append("\nPerson: ")
                        append(it)
                    }
                },
                media = media,
            )
        }
        contextMatches.forEachIndexed { index, match ->
            val item = match.item
            sources += AnswerSource(
                id = "C${index + 1}",
                type = AnswerSourceType.PERSONAL_CONTEXT,
                label = item.sourceLabel.ifBlank { item.kind.displayName },
                detail = "${item.title.ifBlank { item.kind.displayName }}\n${contextSummary(item)}",
                context = item,
            )
        }
        return sources
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

    private fun contextSummary(item: PersonalContextItem): String = buildString {
        item.route?.let { append(it).append("; ") }
        item.merchant?.let { append(it).append("; ") }
        item.amount?.let { append(it).append("; ") }
        item.dateHint?.let { append(it).append("; ") }
        append(item.body)
    }.trim().trimEnd(';').ifBlank { item.title }

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
        // LiteRT-LM 0.14 has no per-request max-output-token control. This is
        // an explicit model contract, and the runtime records any overrun.
        private const val MAX_ANSWER_GENERATED_TOKENS = 50
        private const val MAX_ANSWER_REVIEW_TURNS = 3
        private const val UI_RESULT_LIMIT = 30
        private const val MAX_EVIDENCE_LOG_CHARS = 600
        private const val MAX_FOLLOW_UPS = 2
        private const val MAX_FOLLOW_UP_EVIDENCE_CHARS = 1_400
        private const val FOLLOW_UP_SEMANTIC_WEIGHT = 0.70f
        private const val FOLLOW_UP_OCR_WEIGHT = 0.30f
        private const val MAX_NAME_PROMPT_CHARS = 48
        private const val CONTEXT_RETRIEVAL_LIMIT = 6
        private const val CONTEXT_ANSWER_LIMIT = 4
        private val FOLLOW_UP_OCR_STOP_WORDS = setOf(
            "what", "which", "when", "where", "whose", "with", "from", "this", "that",
            "have", "does", "please", "show", "find", "give", "tell", "about", "photo", "image",
            "document", "number", "date",
        )
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        private val FOLLOW_UP_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy")
        private val ANSWER_SYSTEM_INSTRUCTION = """
            You write Ask Galaxy's grounded gallery answer. Use only the latest task's joined text records and its attached downscaled image inputs. A visual=tile record supports visible details; for a document task pair it only with the same record's DOCUMENT_FACTS to distinguish scans and confirm label/value layout. Text-only fields support only their named person, time, place, or document facts. Local person tags are authoritative. For any document question, answer the exact field requested by the user only from DOCUMENT_FACTS lines. Each document-fact line preserves its key/value relationship: compare a value with its key, rather than choosing a merely plausible number elsewhere in the document. The document map is for natural follow-up ideas only, never answer evidence. A conventional field label may be missing or imperfect: compare the document facts and prefer the value that directly answers the question. Do not call a document fact absent merely because its usual label is absent; use a missing-fact caveat only after the supplied document facts have no credible answer. For a direct document-field question, inspect every supplied DOCUMENT_FACTS record before answering. If one supported distinct field value exists, output it in one concise sentence. If multiple distinct supported values exist across separate records, output every distinct value in one concise sentence; never silently choose one because the question uses a singular noun. Do not repeat values from duplicate scans, and attach a date only if that date is clearly associated with the same document facts record. For every other task, lead with the useful conclusion in 2-3 natural sentences, explicitly naming a relevant place when one is supplied. Return only that answer: state the requested supported value plainly, but do not reproduce document-fact/evidence lines or explain how the answer was found. Never mention evidence, records, prompts, models, reasoning, or private G/C/E/F IDs. Never output JSON, code, routing keys, a query plan, or a follow-up question, and never invent an identity, date, place, count, price, or visible activity.
        """.trimIndent()
        private val ANSWER_REVIEW_SYSTEM_INSTRUCTION = """
            You are Ask Galaxy's strict grounding reviewer. You receive one question, a draft answer, the exact top grounding records, and the same paired images used for the draft. Do not use outside knowledge. A number is valid only when it is copied from supplied document facts or a matching visible image label. Never calculate or infer prices. Reply exactly KEEP when the draft is supported, otherwise reply exactly ANSWER: followed by the corrected direct answer. Do not reveal evidence, reasoning, records, prompts, models, or IDs.
        """.trimIndent()
        private val FOLLOW_UP_SYSTEM_INSTRUCTION = """
            Generate exactly two surprisingly useful natural next gallery-search queries. Base them only on the previous question, its answer, and the TOP 4 ANSWER RECORDS. Each query must be a natural extension of what the user just asked, specific to the same person, document, event, or place when that is known. Choose a different useful angle for each query, and only ask about a field or fact suggested by the top-four records. Never repeat the prior question or answer, include a private value such as a document number, use source labels, or make vague suggestions such as "tell me more", "what else", "nearby", or "details". For a passport-number answer, an expiry or nationality question is good only when those fields are present in the records. Output exactly two lines and nothing else, each in the form QUERY: <question>.
        """.trimIndent()

        private fun elapsedMs(startNanos: Long, endNanos: Long): Long =
            ((endNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)
    }

    private data class SearchExecution(
        val candidates: List<GalleryMedia>,
        val plannerSession: GemmaRuntime.ConversationSession?,
        val plannerJson: String,
        val effectivePlanJson: String,
        val queryCategory: QueryCategory,
        val needsAnswer: Boolean,
        val plannerProfile: GemmaRuntime.GenerationProfile?,
        val answerEvidenceScope: AnswerEvidenceScope,
        val ocrKeywords: List<String>,
    )

    private data class ParsedAnswer(
        val text: String,
        val followUps: List<FollowUpSuggestion>,
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
        return scanned
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
