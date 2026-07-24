package com.ravi.askgalaxy

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    fun scanAsync(onFinished: (Result<Long>) -> Unit) {
        executor.execute {
            val result = runCatching { scanBlocking() }
            onFinished(result)
        }
    }

    fun count(): Long = database.count()

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
                    val searchExecution = searchBlocking(query) { plannerJson, effectivePlanJson ->
                        planningFinished = System.nanoTime()
                        timings = timings.copy(
                            queryPlanningMs = elapsedMs(planningStarted, planningFinished),
                        )
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
                    val uiGallery = candidates
                        .sortedWith(
                            compareByDescending<GalleryMedia> {
                                it.dateTakenMs ?: it.dateModifiedSeconds * 1000L
                            }.thenByDescending { it.mediaStoreId },
                        )
                        .take(UI_RESULT_LIMIT)
                    // Publish a practical browsing window immediately. The
                    // full retrieval pool continues into episode coverage and
                    // the independent top-16 Context Picker.
                    // joining, Context Picker reranking, or Gemma work.
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
                    // Keep a small visual context set for every answer. The
                    // planner still controls which metadata/OCR fields are
                    // authoritative, but images help Gemma identify the
                    // event behind a time/location/person question.
                    val diversityCandidates = (
                        evidenceBuild.representativeCandidates + candidates
                        ).distinctBy { it.mediaStoreId }
                    val answerContext = answerContextPicker.pick(
                        query = query,
                        rankedCandidates = diversityCandidates,
                        evidenceGroups = evidenceBuild.contextGroups,
                        evidenceScope = searchExecution.answerEvidenceScope,
                        maxImages = MAX_GEMMA_IMAGES,
                    )
                    Log.i(
                        TAG,
                        "Context picker: selected=${answerContext.items.size}, fields=" +
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
                        // The result browser is independent from the top-16
                        // answer context. Show up to 200 newest matches while
                        // retaining the full evaluated count.
                        gallery = uiGallery,
                        totalGalleryMatches = candidates.size,
                        personalContext = contextMatches.take(CONTEXT_ANSWER_LIMIT),
                        answerGallery = answerContext.images,
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

    fun saveFaceClusterLabelAsync(
        clusterId: String,
        label: String,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        executor.execute {
            onFinished(runCatching { database.saveFaceClusterLabel(clusterId, label) })
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
            val plannerSession = response.plannerSession
            // The answer path deliberately uses a clean conversation; the
            // planner KV is no longer useful after retrieval. Release it
            // before EXIF reads, bitmap boards, and answer prompt assembly so
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
                val attachedResults = ArrayList<GalleryMedia>(MAX_GEMMA_IMAGES)
                val loadedEvidence = ArrayList<Pair<GalleryMedia, Bitmap>>(MAX_GEMMA_IMAGES)
                val visualGallery = (response.answerGallery.ifEmpty {
                    response.gallery.take(MAX_GEMMA_IMAGES)
                }).take(MAX_GEMMA_IMAGES)
                val visualIds = visualGallery.map { it.mediaStoreId }.toSet()
                val taggedFaceOccurrences = database.taggedFaceOccurrencesForMedia(
                    visualIds.toLongArray(),
                )
                // The Context Picker is the complete answer context. Enrich
                // only the fields its policy selected; never append a second
                // uncurated metadata tail after the top-16 images.
                val visualContextFields = response.answerContext?.metadataFields
                    ?: evidenceScope.metadataFields
                MediaBitmapLoader(appContext).use { loader ->
                    visualGallery.forEach { media ->
                        val enrichedMedia = metadataReader.enrich(media, visualContextFields)
                        val bitmap = loader.load(
                            enrichedMedia,
                            maxDimension = 384,
                            applyExifOrientation = true,
                        )
                            ?: return@forEach
                        loadedEvidence += enrichedMedia to bitmap
                    }
                }
                // G1..Gn must describe the same successfully decoded images
                // that appear in the contact sheet. A failed decode must not
                // shift the labels onto a different gallery record.
                val attachedVisualResults = if (loadedEvidence.isNotEmpty()) {
                    loadedEvidence.map { it.first }
                } else {
                    visualGallery.map { metadataReader.enrich(it, visualContextFields) }
                }
                val identityFaceEvidence = if (loadedEvidence.isNotEmpty()) {
                    buildIdentityFaceEvidence(loadedEvidence, taggedFaceOccurrences)
                } else {
                    emptyList()
                }
                attachedResults += attachedVisualResults
                val imageBytes = try {
                    if (loadedEvidence.isEmpty()) {
                        emptyList()
                    } else {
                        buildList {
                            // Keep one multimodal image input: the upper half
                            // is the gallery board and the lower half is the
                            // labeled face board. This preserves joint visual
                            // reasoning while avoiding a second vision
                            // prefill and its large latency/memory cost.
                            add(encodeGemmaEvidenceBoard(loadedEvidence, identityFaceEvidence))
                        }
                    }
                } finally {
                    identityFaceEvidence.forEach { face ->
                        if (!face.bitmap.isRecycled) face.bitmap.recycle()
                    }
                    loadedEvidence.forEach { (_, bitmap) ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                val richVisualIds = if (imageBytes.isNotEmpty()) {
                    attachedVisualResults.map { it.mediaStoreId }.toSet()
                } else {
                    emptySet()
                }
                Log.i(
                    TAG,
                    "Answer evidence scope=${evidenceScope.label()}, records=${attachedResults.size}, " +
                        "visualCandidates=${visualGallery.size}, decodedVisuals=${loadedEvidence.size}, " +
                        "images=${imageBytes.size}, ocr=${evidenceScope.needsOcr}, metadata=${evidenceScope.needsMetadata}",
                )
                check(imageBytes.isNotEmpty() || attachedResults.isNotEmpty() || response.personalContext.isNotEmpty()) {
                    "No readable result evidence for Gemma"
                }
                val gemma = GemmaRuntime.shared(appContext)
                val answerStarted = System.nanoTime()
                val output = try {
                    val answerPrompt = buildAnswerPrompt(
                        query,
                        attachedResults,
                        response.personalContext,
                        includeImages = imageBytes.isNotEmpty(),
                        evidenceScope = evidenceScope,
                        richVisualIds = richVisualIds,
                        identityFaceReferences = identityFaceEvidence.map { it.reference },
                        evidenceGroups = response.evidenceGroups,
                    )
                    // The planner conversation contains the strict execution
                    // grammar and its previous turn. Reusing it for answers
                    // lets that syntax leak into the answer, especially on
                    // deterministic fast-path plans. Keep the Gemma engine
                    // resident, but create a clean answer conversation.
                    var generated = generateGroundedAnswer(gemma, answerPrompt, imageBytes)
                    if (looksLikePlannerOutput(generated)) {
                        Log.w(TAG, "Gemma returned planner-shaped answer; retrying clean answer turn")
                        generated = generateGroundedAnswer(
                            gemma,
                            "$answerPrompt\nIMPORTANT: Output only a natural-language gallery answer. Do not output JSON, routing keys, regex, a query plan, or the planner example.",
                            imageBytes,
                        )
                    }
                    if (looksLikePlannerOutput(generated)) {
                        Log.e(TAG, "Gemma repeated planner-shaped answer; using grounded UI fallback")
                        groundedAnswerFallback(query, attachedResults)
                    } else {
                        generated
                    }
                } catch (multimodalError: RuntimeException) {
                    if (imageBytes.isEmpty()) throw multimodalError
                    // Some supported phone/runtime combinations reject the
                    // Gemma 4 vision graph with DYNAMIC_UPDATE_SLICE. The
                    // resident text graph remains useful: keep the exact same
                    // selected evidence and answer from OCR/metadata rather
                    // than returning the generic UI failure.
                    Log.w(TAG, "Gemma vision path failed; retrying grounded text answer", multimodalError)
                    generateGroundedAnswer(
                        gemma,
                        prompt = buildAnswerPrompt(
                            query,
                            attachedResults,
                            response.personalContext,
                            includeImages = false,
                            evidenceScope = evidenceScope,
                            richVisualIds = richVisualIds,
                            identityFaceReferences = emptyList(),
                            evidenceGroups = response.evidenceGroups,
                        ),
                        images = emptyList(),
                    )
                }
                val answerGenerationMs = elapsedMs(answerStarted, System.nanoTime())
                check(output.isNotBlank()) { "Gemma returned an empty answer" }
                val followUpStarted = System.nanoTime()
                val parsed = parseAnswer(output)
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
                    timings = response.timings.withAnswerTimings(answerGenerationMs, followUpMs),
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
        onQueryPlanned: (plannerJson: String, effectivePlanJson: String) -> Unit = { _, _ -> },
    ): SearchExecution {
        val plannedQuery = QueryPlannerRuntime.planWithSession(
            appContext,
            query,
            database.namedPersonLabelsForPlanning(),
        )
        // The planner KV is useful only for the planning turn. Answering uses
        // a clean conversation, so holding this session through SQLite,
        // native retrieval, and diversity only increases memory pressure.
        plannedQuery.session?.close()
        var plan = plannedQuery.plan
        // Named people are hard retrieval constraints. Resolve planner output
        // against actual local labels and also inspect the original query so
        // one malformed planner response cannot widen a person search.
        val personLabels = (
            database.namedPersonLabelsMentioned(query) +
                database.resolveNamedPersonLabels(
                    plan.personNames.filter {
                        QuerySpellingMatcher.isPlausibleCorrection(query, it)
                    },
                )
            ).distinctBy { it.lowercase() }
        val excludedPersonLabels = (
            database.namedPersonLabelsNegated(query) +
                database.resolveNamedPersonLabels(
                    plan.excludedPersonNames.filter {
                        QuerySpellingMatcher.isPlausibleCorrection(query, it)
                    },
                )
            ).distinctBy { it.lowercase() }
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
            val timeHint = plan.timeHint.ifBlank {
                QueryScopeParser.explicitTimeHintFromQuery(query)
            }
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
                timeHint = timeHint,
                executionSpec = resolvedExecutionSpec,
            )
            val executionSpec = effectivePlan.canonicalExecutionSpec()
            if (executionSpec == null) {
                onQueryPlanned(plannedQuery.plannerJson, "(empty)")
                return SearchExecution(
                    candidates = emptyList(),
                    plannerSession = null,
                    plannerJson = plannedQuery.plannerJson,
                    effectivePlanJson = "(empty)",
                    answerEvidenceScope = effectivePlan.answerEvidenceScope,
                )
            }
            val renderedExecutionSpec = executionSpec.render()
            onQueryPlanned(plannedQuery.plannerJson, renderedExecutionSpec)
            Log.i(TAG, "Executing QP spec: ${renderedExecutionSpec.take(600)}")
            val candidates = structuredSearchExecutor.execute(executionSpec)
            return SearchExecution(
                candidates = candidates,
                plannerSession = null,
                plannerJson = plannedQuery.plannerJson,
                effectivePlanJson = renderedExecutionSpec,
                answerEvidenceScope = effectivePlan.answerEvidenceScope,
            )
        }
    }

    private fun buildAnswerPrompt(
        query: String,
        results: List<GalleryMedia>,
        contextMatches: List<PersonalContextMatch>,
        includeImages: Boolean,
        evidenceScope: AnswerEvidenceScope,
        richVisualIds: Set<Long> = emptySet(),
        identityFaceReferences: List<String> = emptyList(),
        evidenceGroups: List<EvidenceGroup> = emptyList(),
    ): String {
        val evidence = results.mapIndexed { index, media ->
            buildString {
                append("G${index + 1}")
                val richVisual = media.mediaStoreId in richVisualIds
                append(if (richVisual) " visual_evidence=attached_tile" else " visual_evidence=not_attached")
                val personTag = media.personLabel
                    ?.take(MAX_PERSON_PROMPT_CHARS)
                    ?.takeIf(String::isNotBlank)
                if (personTag != null || evidenceScope.needsPeopleMetadata) {
                    append(" person=${personTag ?: "none"}")
                }
                if (richVisual) {
                    append(" media_type=${if (media.mimeType.startsWith("video/", ignoreCase = true)) "video" else "photo"}")
                    if (media.durationMs > 0L) append(" duration=${formatDuration(media.durationMs)}")
                }
                if (evidenceScope.needsTimeMetadata) {
                    append(" capture_time=${formatDate(media.dateTakenMs)}")
                    append(" modified_time=${formatModifiedDate(media.dateModifiedSeconds)}")
                }
                if (evidenceScope.needsLocationMetadata) {
                    append(" location=${formatLocation(media)}")
                }
                if (evidenceScope.needsMetadata &&
                    !evidenceScope.needsTimeMetadata &&
                    !evidenceScope.needsLocationMetadata &&
                    !evidenceScope.needsPeopleMetadata
                ) {
                    // Defensive fallback if a future metadata channel is
                    // added without a field mapping.
                    append(" capture_time=${formatDate(media.dateTakenMs)}")
                    append(" location=${formatLocation(media)}")
                    append(" person=${media.personLabel?.take(MAX_PERSON_PROMPT_CHARS) ?: "none"}")
                }
                // OCR is often the largest joined text field. Keep it for an
                // explicit OCR/text question; scene/location/activity answers
                // still receive pixels, people, time, and location without
                // spending prompt budget on unrelated text.
                if (evidenceScope.needsOcr) {
                    append(" ocr=${ocrPromptSnippet(media.ocrText, query).ifBlank { "none" }}")
                }
            }
        }.joinToString("\n")
        val attachedLabels = results.mapIndexedNotNull { index, media ->
            if (media.mediaStoreId in richVisualIds) "G${index + 1}" else null
        }
        val structuredOnlyLabels = results.mapIndexedNotNull { index, media ->
            if (media.mediaStoreId !in richVisualIds) "G${index + 1}" else null
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
        val evidenceBuilderInstruction = if (episodeEvidence.isNotEmpty()) {
            "Evidence builder pass: grouped the scoped candidates into exactly ${episodeEvidence.size} candidate episodes. For a how-many trip/event question, report this as the candidate-group count first, then say how many are visually supported as the requested event if that is smaller. Count E groups rather than raw near-duplicate photos, but call them candidate episodes unless the evidence proves distinct human trips. Use each representative G for visual confirmation and the supplied span/location for the episode summary."
        } else {
            ""
        }
        val identityEvidence = results.mapIndexedNotNull { index, media ->
            media.personLabel
                ?.take(MAX_PERSON_PROMPT_CHARS)
                ?.takeIf(String::isNotBlank)
                ?.let { "G${index + 1}=$it" }
        }.joinToString(", ")
        val identityInstruction = if (identityEvidence.isNotBlank()) {
            "Identity links from the local face index (authoritative for names): $identityEvidence. Use person= tags to connect a name in Q to its G image; the name does not need to be visibly written in the pixels."
        } else if (evidenceScope.needsPeopleMetadata) {
            "No local person tag is available for these records, so do not assign a named identity from appearance alone."
        } else {
            ""
        }
        val identityFaceInstruction = if (identityFaceReferences.isNotEmpty()) {
            "The lower half of the attached image is a labeled identity face board. Its tiles are ${identityFaceReferences.joinToString(", ")}. Compare those face crops with the people in the upper gallery board, then reason over the scene, metadata, OCR, and face links together in one answer. Face-board tiles are identity aids, not separate gallery sources."
        } else {
            ""
        }
        val answerDirective = answerDirective(query)
        val contextEvidence = if (!evidenceScope.needsPersonalContext) {
            "not requested"
        } else if (contextMatches.isEmpty()) {
            "none"
        } else {
            contextMatches.take(MAX_CONTEXT_PROMPT_ITEMS).mapIndexed { index, match ->
                val item = match.item
                "C${index + 1} ${item.title.ifBlank { item.kind.displayName }.take(MAX_NAME_PROMPT_CHARS)} " +
                    "${contextSummary(item).take(MAX_CONTEXT_PROMPT_CHARS)}"
            }.joinToString("\n")
        }
        val evidenceInstruction = buildString {
            if (includeImages) {
                append("Use the attached contact-sheet tiles as visual evidence when relevant.")
                if (identityFaceReferences.isNotEmpty()) {
                    append(" The upper half is the gallery contact sheet; the lower half is the labeled identity face board.")
                }
                if (attachedLabels.isNotEmpty()) {
                    append(" Attached tiles: ${attachedLabels.joinToString(", ")} in the same order as the G labels.")
                }
                if (structuredOnlyLabels.isNotEmpty()) {
                    append(" ${structuredOnlyLabels.joinToString(", ")} are structured-only records; do not describe their pixels or claim that they visibly show an activity.")
                }
            } else if (evidenceScope.needsVisual) {
                append("Visual evidence is unavailable; do not make visual claims.")
            } else {
                append("Do not infer visual facts from filenames or metadata.")
            }
            if (evidenceScope.needsMetadata) {
                append(" Use supplied structured metadata as authoritative and combine it with the images and identity tags:")
                if (evidenceScope.needsPeopleMetadata) append(" people")
                if (evidenceScope.needsTimeMetadata) append(" capture time")
                if (evidenceScope.needsLocationMetadata) append(" location")
                append(".")
            }
            if (evidenceScope.needsOcr) {
                append(" Use only the supplied OCR text.")
            }
            if (richVisualIds.isNotEmpty()) {
                append(" OCR and metadata on the selected visual records are supplementary joined evidence; use them when they help answer Q.")
            }
            if (episodeEvidence.isNotEmpty()) {
                append(" Use the evidence-builder episode rows below as a curated multi-pass summary, not as extra images.")
            }
            if (evidenceScope.needsPersonalContext) {
                append(" Use only the supplied personal context.")
            }
            append(" Answer directly when the joined evidence supports it. Do not open with a generic refusal when candidate records exist: lead with the closest useful date, place, or event, then state the confidence or limitation. For activity or appearance questions, call something confirmed only when an attached tile clearly supports it; otherwise say likely or adjacent and explain why. For when+activity questions, attach the activity claim to the strongest visual tile before giving its capture date; a metadata-only record can establish when a tagged person was present, but cannot prove what they were doing. If an exact fact is absent, say what the evidence does support instead of only saying that evidence is missing. Do not invent an exact identity or date.")
        }
        val sourceInstruction = if (includeImages) {
            "Use attached tiles for visual claims and structured G records only for their supplied metadata. G/C/E/F labels are private join keys: never print or refer to them in the answer or follow-up questions."
        } else {
            "Treat each G label as its matching structured gallery record. G/C/E/F labels are private join keys: never print or refer to them in the answer or follow-up questions."
        }
        return """
            ANSWER_TASK:
            Return only a natural-language answer. Never output JSON, routing keys, regex, code, or a query plan.
            Do not mention "provided evidence", "supplied records", the prompt, the model, or internal reasoning. Speak directly about the photos and their details.
            $evidenceInstruction
            $identityInstruction
            $identityFaceInstruction
            $answerDirective
            $evidenceBuilderInstruction
            Treat each G record as joined evidence: attached image pixels describe the event or activity, person tags identify people, capture_time answers when, location answers where, and OCR supplies written text. Combine these channels before deciding that the answer is unavailable, but keep visual claims limited to the attached tiles.
            ${if (evidenceScope.needsMetadata) {
                buildString {
                    if (evidenceScope.needsTimeMetadata) {
                        append("For time questions prefer capture_time; use modified_time only when capture_time is none. ")
                    }
                    if (evidenceScope.needsLocationMetadata) {
                        append("For where questions use location only when present. ")
                    }
                    if (evidenceScope.needsPeopleMetadata) {
                        append("Use tagged people only when present. ")
                    }
                }.trim()
            } else {
                ""
            }} $sourceInstruction
            Q: $query
            EVIDENCE:
            $evidence
            CONTEXT:
            $contextEvidence
            EVIDENCE_BUILDER:
            ${episodeEvidence.ifEmpty { listOf("none") }.joinToString("\n")}
            Reply in 2-3 concise, natural sentences and lead with the best supported answer. Never include source IDs such as G1, G2, C1, E1, or F1:
            ANSWER: <answer>
            FOLLOW_UPS:
            QUERY: <interesting gallery question grounded in the matched people, day, event, or place>
            Add at most 3 QUERY lines. Prefer questions such as who else was there, what happened through the whole day, or what other moments occurred at that place. Do not add source-review actions.
        """.trimIndent()
    }

    private fun answerDirective(query: String): String {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        return when {
            Regex("\\b(when|date|dated|time)\\b").containsMatchIn(normalized) &&
                Regex("\\b(go|went|doing|activity|swim|swimming|eat|eating|drink|drinking|run|running|play|playing|work|working|visit|visiting)\\b")
                    .containsMatchIn(normalized) ->
                "ACTIVITY TIMELINE TASK: start with the strongest supported capture date(s) or closest event, then state whether the named activity is confirmed, likely, or only adjacent. Use the attached visual tiles to make that distinction; do not turn person presence in a metadata-only record into proof of the activity."
            Regex("\\b(when|date|dated|born|birth|birthdate|birthday|year|today|yesterday)\\b")
                .containsMatchIn(normalized) ->
                "DATE/TIMELINE TASK: rank the strongest matching event dates from capture_time and the images. For birthdate/birthday questions, inspect the images for birthday cues such as cake, candles, or a celebration; do not use the earliest record as a birthdate proxy. Distinguish an exact birthdate from a birthday-event photo date; if the exact fact is not stored, give the strongest supported event-date candidate or shortlist and explain the distinction briefly."
            Regex("\\b(where|location|place)\\b").containsMatchIn(normalized) ->
                "LOCATION TASK: answer with the strongest supplied location details and explicitly name the place. If only GPS text or a partial place is available, present it naturally rather than discarding it."
            Regex("\\b(who|whose|person|people)\\b").containsMatchIn(normalized) ->
                "IDENTITY TASK: use local person tags as the primary identity link, then use the image to describe what that person is doing or where they appear."
            Regex("\\b(how many|count|number of|most|least)\\b").containsMatchIn(normalized) ->
                "COUNT/RANK TASK: count or rank only the scoped records and explain the basis briefly; do not replace a count with a generic evidence disclaimer."
            Regex("\\b(compare|comparison|difference|versus| vs )\\b").containsMatchIn(normalized) ->
                "COMPARISON TASK: compare the relevant groups or time periods directly using the supplied records, calling out missing or asymmetric evidence."
            Regex("\\b(without|excluding|except|not|no)\\b").containsMatchIn(normalized) ->
                "EXCLUSION TASK: answer from the positive set after applying the subtraction and state what remains instead of only describing the excluded set."
            else ->
                "GENERAL TASK: synthesize the strongest visual, identity, metadata, OCR, and personal-context clues into a useful direct answer, with a calibrated caveat only where needed."
        }
    }

    private fun generateGroundedAnswer(
        gemma: GemmaRuntime,
        prompt: String,
        images: List<ByteArray>,
    ): String {
        val session = gemma.createAnswerConversation(ANSWER_SYSTEM_INSTRUCTION)
        return try {
            session.generate(prompt, images)
        } finally {
            session.close()
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
            return "I could not find matching photos or details for \"${query.take(MAX_NAME_PROMPT_CHARS)}\"."
        }
        return "I found ${results.size} matching photo${if (results.size == 1) "" else "s"} for \"${query.take(MAX_NAME_PROMPT_CHARS)}\". The newest matches are ready to browse below."
    }

    private fun buildIdentityFaceEvidence(
        loadedEvidence: List<Pair<GalleryMedia, Bitmap>>,
        occurrences: List<TaggedFaceOccurrence>,
    ): List<IdentityFaceEvidence> {
        if (loadedEvidence.isEmpty() || occurrences.isEmpty()) return emptyList()
        val loadedById = loadedEvidence.associateBy { it.first.mediaStoreId }
        val sourceRank = loadedEvidence.mapIndexed { index, (media, _) -> media.mediaStoreId to index }.toMap()
        val orientationById = MediaBitmapLoader(appContext).use { loader ->
            loadedEvidence.associate { (media, _) ->
                media.mediaStoreId to loader.readOrientation(media)
            }
        }
        // One strong crop per distinct local name gives the model an identity
        // anchor without flooding the multimodal token budget with repeats.
        val chosen = occurrences
            .filter { it.mediaStoreId in loadedById }
            .groupBy { it.label.trim().lowercase() }
            .values
            .mapNotNull { group ->
                group.sortedWith(
                    compareByDescending<TaggedFaceOccurrence> { it.detectionScore }
                        .thenBy { sourceRank[it.mediaStoreId] ?: Int.MAX_VALUE },
                ).firstOrNull()
            }
            .sortedWith(
                compareBy<TaggedFaceOccurrence> { sourceRank[it.mediaStoreId] ?: Int.MAX_VALUE }
                    .thenByDescending { it.detectionScore },
            )
            .take(MAX_IDENTITY_FACE_CROPS)

        return chosen.mapIndexedNotNull { faceIndex, occurrence ->
            val sourceIndex = sourceRank[occurrence.mediaStoreId] ?: return@mapIndexedNotNull null
            val bitmap = loadedById[occurrence.mediaStoreId]?.second ?: return@mapIndexedNotNull null
            val orientedBox = ImageOrientation.transformBox(
                occurrence.box,
                orientationById[occurrence.mediaStoreId] ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
            )
            val detection = FaceDetection(
                box = FaceBox(
                    left = orientedBox.left * bitmap.width,
                    top = orientedBox.top * bitmap.height,
                    right = orientedBox.right * bitmap.width,
                    bottom = orientedBox.bottom * bitmap.height,
                ),
                landmarks = FloatArray(0),
                score = occurrence.detectionScore,
            )
            val crop = runCatching { FaceCropper.crop(bitmap, detection) }.getOrNull()
                ?: return@mapIndexedNotNull null
            IdentityFaceEvidence(
                reference = "F${faceIndex + 1}=${occurrence.label} from G${sourceIndex + 1}",
                label = occurrence.label,
                sourceLabel = "G${sourceIndex + 1}",
                bitmap = crop,
            )
        }
    }

    /**
     * Encodes gallery scenes and named face anchors into one bounded vision
     * input. The split board makes the cross-reference explicit to Gemma,
     * while one image prefill is materially cheaper than two independent
     * multimodal inputs on the phone.
     */
    private fun encodeGemmaEvidenceBoard(
        gallery: List<Pair<GalleryMedia, Bitmap>>,
        faces: List<IdentityFaceEvidence>,
    ): ByteArray {
        val size = EVIDENCE_BOARD_SIZE
        // Reserve the lower half only when there are identity anchors to show.
        // A blank identity area would otherwise cut every scene tile in half
        // for ordinary visual/location/time questions without improving
        // grounding.
        val hasFaceBoard = faces.isNotEmpty()
        val galleryAreaHeight = if (hasFaceBoard) size / 2 else size
        val galleryColumns = CONTACT_SHEET_COLUMNS
        val galleryCell = galleryAreaHeight / galleryColumns
        val faceColumns = IDENTITY_BOARD_COLUMNS
        val faceRows = if (hasFaceBoard) {
            (faces.size + faceColumns - 1) / faceColumns
        } else {
            0
        }
        val faceAreaTop = galleryAreaHeight
        val faceCellWidth = size / faceColumns
        val faceCellHeight = if (faceRows > 0) (size - faceAreaTop) / faceRows else 0
        val sheet = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val galleryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = EVIDENCE_BOARD_GALLERY_LABEL_SIZE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val faceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = EVIDENCE_BOARD_FACE_LABEL_SIZE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        gallery.take(MAX_GEMMA_IMAGES).forEachIndexed { index, (_, bitmap) ->
            val column = index % galleryColumns
            val row = index / galleryColumns
            val left = column * galleryCell + CONTACT_SHEET_GAP
            val top = row * galleryCell + CONTACT_SHEET_GAP
            val right = (column + 1) * galleryCell - CONTACT_SHEET_GAP
            val bottom = (row + 1) * galleryCell - CONTACT_SHEET_GAP
            val scale = minOf(
                (right - left).toFloat() / bitmap.width,
                (bottom - top).toFloat() / bitmap.height,
            )
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val imageLeft = left + ((right - left) - width) / 2f
            val imageTop = top + ((bottom - top) - height) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(imageLeft, imageTop, imageLeft + width, imageTop + height),
                imagePaint,
            )
            canvas.drawRect(
                RectF(
                    left.toFloat(),
                    top.toFloat(),
                    (left + EVIDENCE_BOARD_GALLERY_LABEL_WIDTH).toFloat(),
                    (top + EVIDENCE_BOARD_GALLERY_LABEL_HEIGHT).toFloat(),
                ),
                labelBackgroundPaint,
            )
            canvas.drawText(
                "G${index + 1}",
                left + EVIDENCE_BOARD_LABEL_INSET,
                top + EVIDENCE_BOARD_GALLERY_LABEL_BASELINE,
                galleryLabelPaint,
            )
        }
        faces.forEachIndexed { index, face ->
            val column = index % faceColumns
            val row = index / faceColumns
            val left = column * faceCellWidth + IDENTITY_SHEET_GAP
            val top = faceAreaTop + row * faceCellHeight + IDENTITY_SHEET_GAP
            val right = (column + 1) * faceCellWidth - IDENTITY_SHEET_GAP
            val bottom = faceAreaTop + (row + 1) * faceCellHeight - IDENTITY_SHEET_GAP
            val imageBottom = bottom - EVIDENCE_BOARD_FACE_LABEL_HEIGHT
            val scale = minOf(
                (right - left).toFloat() / face.bitmap.width,
                (imageBottom - top).toFloat() / face.bitmap.height,
            ).coerceAtLeast(0.01f)
            val width = face.bitmap.width * scale
            val height = face.bitmap.height * scale
            val imageLeft = left + ((right - left) - width) / 2f
            val imageTop = top + ((imageBottom - top) - height) / 2f
            canvas.drawBitmap(
                face.bitmap,
                null,
                RectF(imageLeft, imageTop, imageLeft + width, imageTop + height),
                imagePaint,
            )
            canvas.drawRect(
                RectF(
                    left.toFloat(),
                    imageBottom.toFloat(),
                    right.toFloat(),
                    bottom.toFloat(),
                ),
                labelBackgroundPaint,
            )
            canvas.drawText(
                "F${index + 1} ${face.label.take(MAX_IDENTITY_LABEL_CHARS)} • ${face.sourceLabel}",
                left + EVIDENCE_BOARD_LABEL_INSET,
                bottom - EVIDENCE_BOARD_FACE_LABEL_BASELINE,
                faceLabelPaint,
            )
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(sheet.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                    "Could not encode Gemma evidence board"
                }
                output.toByteArray()
            }
        } finally {
            if (!sheet.isRecycled) sheet.recycle()
        }
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

    /** Keeps the OCR window around query terms instead of always taking its header. */
    private fun ocrPromptSnippet(value: String, query: String): String {
        val compact = compactPromptText(value)
        if (compact.length <= MAX_OCR_PROMPT_CHARS) return compact
        val terms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
        val hit = terms.asSequence()
            .map { compact.lowercase().indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (hit == null) return compact.take(MAX_OCR_PROMPT_CHARS)
        val start = (hit - MAX_OCR_CONTEXT_PADDING).coerceAtLeast(0)
        val end = (start + MAX_OCR_PROMPT_CHARS).coerceAtMost(compact.length)
        return (if (start > 0) "…" else "") + compact.substring(start, end) +
            if (end < compact.length) "…" else ""
    }

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
                        append(compactPromptText(media.ocrText).take(MAX_OCR_PROMPT_CHARS))
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
        append(item.body.take(MAX_CONTEXT_PROMPT_CHARS))
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
            .replace(
                Regex("(?i)\\b(photo|photos|picture|pictures|image|images|still|stills|video|videos|movie|movies|clip|clips)\\b"),
                " ",
            )
        if (locationHint.isNotBlank()) {
            value = value.replace(Regex("(?i)\\b(trip|trips|travel|vacation|holiday)\\b"), " ")
        }
        return value.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val TAG = "AskGalaxy"
        private const val MAX_GEMMA_IMAGES = 16
        private const val UI_RESULT_LIMIT = 200
        private const val MAX_IDENTITY_FACE_CROPS = 8
        private const val MAX_EVIDENCE_LOG_CHARS = 600
        private const val MAX_FOLLOW_UPS = 3
        private const val MAX_NAME_PROMPT_CHARS = 48
        private const val MAX_PERSON_PROMPT_CHARS = 64
        private const val MAX_OCR_PROMPT_CHARS = 160
        private const val MAX_OCR_CONTEXT_PADDING = 48
        private const val MAX_CONTEXT_PROMPT_ITEMS = 2
        private const val CONTEXT_RETRIEVAL_LIMIT = 6
        private const val CONTEXT_ANSWER_LIMIT = 4
        private const val MAX_CONTEXT_PROMPT_CHARS = 160
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        private val ANSWER_SYSTEM_INSTRUCTION = """
            You are Ask Galaxy's grounded gallery answer writer. Answer the user's question using only the joined context in the latest ANSWER_TASK. Reason jointly over every attached image board, labeled identity face tile, metadata record, OCR snippet, local person tag, personal-context item, and the query; do not answer each image independently or ignore the cross-references. Give a natural 2-3 sentence summary that leads with the most useful supported conclusion; use partial context and calibrated wording when an exact fact is missing instead of giving a generic refusal. Never say "based on the provided evidence", "according to the supplied records", "the prompt says", or similar system/evaluation wording. Say what the photos, dates, places, and tags show directly. When a readable location is present and relevant, explicitly name it. Separate confirmed visual details from likely or adjacent details, and never treat a metadata-only record as visual proof. G/C/E/F labels are private join keys: never include source IDs such as G1, G2, C1, E1, or F1 in the answer or follow-ups. Never emit JSON, routing keys, regular expressions, source code, or a query plan. Do not repeat the task instructions or invent an exact identity, date, place, or count.
        """.trimIndent()
        private const val CONTACT_SHEET_COLUMNS = 4
        private const val CONTACT_SHEET_GAP = 6
        private const val EVIDENCE_BOARD_SIZE = 768
        private const val IDENTITY_BOARD_COLUMNS = 4
        private const val EVIDENCE_BOARD_GALLERY_LABEL_WIDTH = 52
        private const val EVIDENCE_BOARD_GALLERY_LABEL_HEIGHT = 30
        private const val EVIDENCE_BOARD_GALLERY_LABEL_BASELINE = 22f
        private const val EVIDENCE_BOARD_GALLERY_LABEL_SIZE = 16f
        private const val EVIDENCE_BOARD_FACE_LABEL_HEIGHT = 38
        private const val EVIDENCE_BOARD_FACE_LABEL_BASELINE = 9f
        private const val EVIDENCE_BOARD_FACE_LABEL_SIZE = 13f
        private const val EVIDENCE_BOARD_LABEL_INSET = 8f
        private const val IDENTITY_SHEET_GAP = 8
        private const val MAX_IDENTITY_LABEL_CHARS = 24

        private fun elapsedMs(startNanos: Long, endNanos: Long): Long =
            ((endNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)
    }

    private data class SearchExecution(
        val candidates: List<GalleryMedia>,
        val plannerSession: GemmaRuntime.ConversationSession?,
        val plannerJson: String,
        val effectivePlanJson: String,
        val answerEvidenceScope: AnswerEvidenceScope,
    )

    private data class ParsedAnswer(
        val text: String,
        val followUps: List<FollowUpSuggestion>,
    )

    private data class IdentityFaceEvidence(
        val reference: String,
        val label: String,
        val sourceLabel: String,
        val bitmap: Bitmap,
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

private fun android.database.Cursor.getIntOrZero(column: Int): Int =
    if (isNull(column)) 0 else getInt(column)

private fun android.database.Cursor.getLongOrZero(column: Int): Long =
    if (isNull(column)) 0L else getLong(column)
