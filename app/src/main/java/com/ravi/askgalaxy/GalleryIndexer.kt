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
        StructuredSearchExecutor(database, semanticIndexer, metadataReader)
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
                        // Preserve OCR-perfect then semantic-only rank before
                        // the document-specific Context Picker rechecks it.
                        (candidates + evidenceBuild.representativeCandidates)
                            .distinctBy { it.mediaStoreId }
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
                        // The result browser is independent from the top-8
                        // answer context. Show up to 200 query-ranked matches while
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
            val result = try {
                runCatching {
                check(GemmaRuntime.isModelInstalled(appContext)) {
                    "Gemma 4 is not installed yet"
                }
                val evidenceScope = response.answerContext?.metadataFields
                    ?.let(response.answerEvidenceScope::withMetadataFields)
                    ?: response.answerEvidenceScope
                val attachedResults = ArrayList<GalleryMedia>(MAX_ANSWER_RECORDS)
                val answerImageLimit =
                    QueryCategoryContextPolicy.answerImageLimit(response.queryCategory)
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
                    response.answerContext?.includeVisuals
                        ?: response.answerEvidenceScope.needsVisual
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
                                QueryCategoryContextPolicy.SCENARY_ANSWER_IMAGE_MAX_DIMENSION,
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
                val output = try {
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
                val answerGenerationMs = elapsedMs(answerStarted, System.nanoTime())
                check(output.isNotBlank()) { "Gemma returned an empty answer" }
                val followUpStarted = System.nanoTime()
                val parsedOutput = parseAnswer(output)
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
                GemmaRuntime.preloadPlannerAsync(appContext, QueryPlannerRuntime.plannerSystemInstruction())
            }
            onFinished(result)
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
                    ocrKeywords = effectivePlan.ocrTerms.flatMap(OcrKeywordPolicy::keywords),
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
                ocrKeywords = effectivePlan.ocrTerms.flatMap(OcrKeywordPolicy::keywords),
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
                    val ocr = compactPromptText(media.ocrText)
                        .replace('"', '\'')
                        .ifBlank { "none" }
                    append(" ocr=\"$ocr\"")
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
                "IMAGE INPUTS: the four-or-fewer downscaled scenery images map in order to " +
                    imageReferences.joinToString(", ") +
                    " and are the only source for visible scene/activity claims."
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
            RECORDS:
            $evidence
            $contextBlock$episodeBlock
            Return a direct, natural 2-3 sentence summary. Use a calibrated caveat only for a missing exact fact.
            ANSWER:
            FOLLOW_UPS:
            QUERY: <up to 3 short gallery questions about who else, that day, event, or place>
        """.trimIndent()
        Log.i(
            TAG,
            "Answer prompt: chars=${prompt.length}, records=${results.size}, " +
                "rows=${evidence.lineSequence().count()}, images=${imageReferences.size}, " +
                "episodes=${episodeEvidence.size}, context=${contextMatches.size}",
        )
        return prompt
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
                "DATE/TIMELINE TASK: rank the strongest matching dates from capture_time and OCR. Do not use the earliest record as a birthdate proxy. Distinguish an exact printed date from a photo-event date; if the exact fact is not stored, give the strongest supported candidate or shortlist."
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
        if (looksLikePlannerOutput(generated)) {
            Log.w(TAG, "Gemma returned task/planner-shaped answer; retrying clean answer turn")
            generated = generateGroundedAnswer(
                gemma,
                "$prompt\nIMPORTANT: Return only the natural-language answer and QUERY follow-up lines. Do not repeat task text, JSON, routing keys, code, or a query plan.",
                images,
                usePrefilledAnswer = false,
            )
        }
        return if (looksLikePlannerOutput(generated)) {
            Log.e(TAG, "Gemma repeated task/planner-shaped answer; using grounded UI fallback")
            groundedAnswerFallback(query, results)
        } else {
            generated
        }
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
            DateTimeFormatter.ofPattern("d MMMM yyyy")
                .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }
        val anchorPlace = anchor?.locationName
            ?.substringBefore(" (")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: anchor?.location?.trim()?.takeIf(String::isNotBlank)
        val anchorPerson = results.asSequence()
            .mapNotNull { it.personLabel?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        if (Regex("\\b(birthdate|birth date|birthday|born)\\b").containsMatchIn(normalized)) {
            val person = anchorPerson?.let { " for $it" }.orEmpty()
            add(FollowUpSuggestion("Who else was at the birthday$person?"))
            add(FollowUpSuggestion("What did we do through the whole birthday day$person?"))
        } else if (Regex("\\b(when|date|dated|year|today|yesterday)\\b").containsMatchIn(normalized)) {
            anchorDay?.let {
                add(FollowUpSuggestion("What did we do through the whole day on $it?"))
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
            add(FollowUpSuggestion("What other moments happened in $it around that time?"))
        }
        if (isEmpty() && contextMatches.isNotEmpty()) {
            add(FollowUpSuggestion("What else was happening around this event?"))
        }
        if (isEmpty() && results.isNotEmpty()) {
            add(FollowUpSuggestion("What happened before and after these moments?"))
        }
    }.distinctBy { it.text.lowercase() }.take(MAX_FOLLOW_UPS)

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
        private const val MAX_ANSWER_RECORDS = 8
        private const val UI_RESULT_LIMIT = 200
        private const val MAX_EVIDENCE_LOG_CHARS = 600
        private const val MAX_FOLLOW_UPS = 3
        private const val MAX_NAME_PROMPT_CHARS = 48
        private const val CONTEXT_RETRIEVAL_LIMIT = 6
        private const val CONTEXT_ANSWER_LIMIT = 4
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        private val ANSWER_SYSTEM_INSTRUCTION = """
            You write Ask Galaxy's grounded gallery answer. Use only the latest task's joined text records and, for a scenery task only, its attached downscaled image inputs. A visual=tile record supports visible details; text-only fields support only their named person, time, place, or OCR facts. Local person tags are authoritative. Lead with the useful conclusion in 2-3 natural sentences, explicitly naming a relevant place when one is supplied, and use a brief calibrated caveat only when an exact fact is genuinely absent from all supplied inputs. Never mention evidence, records, prompts, models, reasoning, or private G/C/E/F IDs. Never output JSON, code, routing keys, or a query plan, and never invent an identity, date, place, count, price, or visible activity.
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
