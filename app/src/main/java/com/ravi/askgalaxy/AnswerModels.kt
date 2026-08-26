package com.ravi.askgalaxy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AnswerSourceType {
    GALLERY_IMAGE,
    DOCUMENT_RECORD,
}

data class AnswerSource(
    val id: String,
    val type: AnswerSourceType,
    val label: String,
    val detail: String,
    val media: GalleryMedia? = null,
    val document: DocumentChunk? = null,
)

data class AnswerResult(
    val text: String,
    val sources: List<AnswerSource>,
    val followUps: List<FollowUpSuggestion> = emptyList(),
    val timings: PhaseTimings = PhaseTimings(),
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
)

/** Live stages shown by the compact answer pipeline indicator. */
enum class AnswerPipelineStage {
    ANSWERING,
    REVIEWING,
    ACCEPTING,
}

data class FollowUpSuggestion(
    val text: String,
    val isQuery: Boolean = true,
)

enum class NextBriefActionType {
    SHARE_MEDIA,
    MAPS_SEARCH,
    CONTACT,
    CALENDAR_REMINDER,
    CONTINUE_WEB_TASK,
    SEND_MESSAGE,
}

data class NextBriefSuggestion(
    val action: NextBriefActionType,
    val text: String,
    /** Grounding record such as G1; resolved locally before the action runs. */
    val sourceId: String,
    /** Optional short cue selected by Gemma, used only for web/message intents. */
    val payload: String = "",
)

data class NextBriefCapability(
    val id: String,
    val label: String,
    val handlers: String,
)

sealed class HybridSearchResult {
    data class Gallery(val media: GalleryMedia) : HybridSearchResult()
    data class Document(val match: DocumentMatch) : HybridSearchResult()
}

/**
 * Existing public fusion contract: gallery contributes its final structured-search rank, while
 * private records contribute their already-fused EmbeddingGemma/keyword score normalized inside
 * that engine. Raw gallery and document scores are never compared directly.
 */
internal object CrossEngineFusionPolicy {
    const val FEATURED_RESULT_LIMIT = 8
    const val OVERALL_RESULT_LIMIT = 100

    fun rank(
        gallery: List<GalleryMedia>,
        documents: List<DocumentMatch>,
        limit: Int = OVERALL_RESULT_LIMIT,
    ): List<HybridSearchResult> {
        val galleryHits = gallery.mapIndexed { index, media ->
            RankedHit(
                score = 1f / (index + 1f),
                result = HybridSearchResult.Gallery(media),
            )
        }
        val eligibleDocuments = documents.filter { PersonalFileSearchPolicy.isEligible(it.chunk) }
        val maxDocumentScore = eligibleDocuments.maxOfOrNull(DocumentMatch::fusionScore)
            ?.coerceAtLeast(1.0e-6f)
            ?: 1f
        val documentHits = eligibleDocuments.map { match ->
            RankedHit(
                score = (match.fusionScore / maxDocumentScore).coerceIn(0f, 1f),
                result = HybridSearchResult.Document(match),
            )
        }
        return (galleryHits + documentHits)
            .sortedByDescending(RankedHit::score)
            .take(limit.coerceAtLeast(0))
            .map(RankedHit::result)
    }

    private data class RankedHit(
        val score: Float,
        val result: HybridSearchResult,
    )
}

enum class StandoutIntent(val displayLabel: String) {
    SCENERY("Visual / event"),
    PEOPLE("Person"),
    DOCUMENT("Document"),
    LOCATION("Location"),
    TIME("Time"),
}

/** Validated QP intent carried into presentation; the UI never guesses it from result text. */
data class StandoutIntentProfile(
    val primary: StandoutIntent = StandoutIntent.SCENERY,
    val hasPeopleConstraint: Boolean = false,
    val hasLocationConstraint: Boolean = false,
    val hasTimeConstraint: Boolean = false,
    val hasWrittenTextConstraint: Boolean = false,
) {
    companion object {
        fun fromPlan(plan: QueryPlan): StandoutIntentProfile {
            val hasPeople = plan.personNames.isNotEmpty() || plan.onlyPersonNames.isNotEmpty()
            val hasLocation = plan.locationHint.isNotBlank() || plan.travelScope.isNotBlank()
            val hasTime = plan.fromDate.isNotBlank() || plan.toDate.isNotBlank() ||
                plan.timeHint.isNotBlank()
            val hasWritten = plan.keywordTerms.isNotEmpty() || plan.ocrTerms.isNotEmpty() ||
                plan.mediaType?.documentSources()?.isNotEmpty() == true
            val primary = when {
                plan.queryCategory == QueryCategory.DOC || hasWritten -> StandoutIntent.DOCUMENT
                plan.queryCategory == QueryCategory.PERSON || hasPeople -> StandoutIntent.PEOPLE
                plan.queryCategory == QueryCategory.LOCATION || hasLocation -> StandoutIntent.LOCATION
                plan.queryCategory == QueryCategory.TIME || hasTime -> StandoutIntent.TIME
                else -> StandoutIntent.SCENERY
            }
            return StandoutIntentProfile(primary, hasPeople, hasLocation, hasTime, hasWritten)
        }
    }
}

data class StandoutSearchResult(
    val result: HybridSearchResult,
    val badge: String,
    val relatedCount: Int = 0,
)

/**
 * Picks eight intent-aware standouts from the complete fused Top 100.
 * Rank remains the majority signal; grounded novelty can promote a genuinely
 * different moment or record but cannot bypass the validated retrieval set.
 */
internal object StandoutResultPolicy {
    fun select(
        overall: List<HybridSearchResult>,
        profile: StandoutIntentProfile,
        episodeGroups: List<EvidenceGroup> = emptyList(),
        limit: Int = CrossEngineFusionPolicy.FEATURED_RESULT_LIMIT,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<StandoutSearchResult> {
        if (limit <= 0) return emptyList()
        val candidates = overall
            .take(CrossEngineFusionPolicy.OVERALL_RESULT_LIMIT)
            .distinctBy(::identityKey)
        if (candidates.isEmpty()) return emptyList()
        val groupByMediaId = buildMap<Long, EvidenceGroup> {
            episodeGroups.forEach { group ->
                group.matchedMediaStoreIds.forEach { put(it, group) }
            }
        }
        val selected = arrayListOf(0)
        while (selected.size < limit && selected.size < candidates.size) {
            val next = candidates.indices
                .asSequence()
                .filterNot(selected::contains)
                .maxWithOrNull(
                    compareBy<Int> { index ->
                        val relevance = if (candidates.size == 1) 1f else {
                            1f - index.toFloat() / (candidates.size - 1).toFloat()
                        }
                        val novelty = noveltyScore(
                            candidate = candidates[index],
                            selected = selected.map(candidates::get),
                            profile = profile,
                            groups = groupByMediaId,
                            zoneId = zoneId,
                        )
                        relevance * RELEVANCE_WEIGHT + novelty * NOVELTY_WEIGHT
                    }.thenBy { -it },
                ) ?: break
            selected += next
        }
        val chosen = selected.map(candidates::get)
        return chosen.mapIndexed { index, result ->
            val group = (result as? HybridSearchResult.Gallery)
                ?.let { groupByMediaId[it.media.mediaStoreId] }
            StandoutSearchResult(
                result = result,
                badge = badge(
                    result = result,
                    index = index,
                    previouslySelected = chosen.take(index),
                    profile = profile,
                    group = group,
                    groups = groupByMediaId,
                    zoneId = zoneId,
                ),
                relatedCount = (group?.memberCount?.minus(1) ?: 0).coerceAtLeast(0),
            )
        }
    }

    private fun noveltyScore(
        candidate: HybridSearchResult,
        selected: List<HybridSearchResult>,
        profile: StandoutIntentProfile,
        groups: Map<Long, EvidenceGroup>,
        zoneId: ZoneId,
    ): Float {
        fun novelty(value: String, selectedValues: Set<String>): Float = when {
            value.isBlank() -> UNKNOWN_NOVELTY
            value !in selectedValues -> 1f
            else -> 0f
        }
        val selectedMoments = selected.mapTo(linkedSetOf()) { momentKey(it, groups) }
        val selectedSources = selected.mapTo(linkedSetOf(), ::sourceKey)
        val selectedPeople = selected.mapTo(linkedSetOf(), ::peopleKey)
        val selectedLocations = selected.mapTo(linkedSetOf(), ::locationKey)
        val selectedDates = selected.mapTo(linkedSetOf()) { dateKey(it, zoneId) }
        val selectedShapes = selected.mapTo(linkedSetOf(), ::shapeKey)
        val moment = novelty(momentKey(candidate, groups), selectedMoments)
        val source = novelty(sourceKey(candidate), selectedSources)
        val people = novelty(peopleKey(candidate), selectedPeople)
        val location = novelty(locationKey(candidate), selectedLocations)
        val date = novelty(dateKey(candidate, zoneId), selectedDates)
        val shape = novelty(shapeKey(candidate), selectedShapes)
        return when (profile.primary) {
            StandoutIntent.DOCUMENT -> moment * 0.45f + source * 0.30f + date * 0.15f + shape * 0.10f
            StandoutIntent.PEOPLE -> moment * 0.38f + date * 0.22f + location * 0.16f +
                people * 0.16f + shape * 0.08f
            StandoutIntent.LOCATION -> moment * 0.40f + date * 0.28f + people * 0.14f +
                shape * 0.10f + source * 0.08f
            StandoutIntent.TIME -> moment * 0.38f + location * 0.24f + people * 0.18f +
                source * 0.12f + shape * 0.08f
            StandoutIntent.SCENERY -> moment * 0.34f + location * 0.20f + date * 0.16f +
                shape * 0.12f + source * 0.10f + people * 0.08f
        }.coerceIn(0f, 1f)
    }

    private fun badge(
        result: HybridSearchResult,
        index: Int,
        previouslySelected: List<HybridSearchResult>,
        profile: StandoutIntentProfile,
        group: EvidenceGroup?,
        groups: Map<Long, EvidenceGroup>,
        zoneId: ZoneId,
    ): String {
        if (index == 0) return "Best match"
        fun newValue(value: String, key: (HybridSearchResult) -> String): Boolean =
            value.isNotBlank() && previouslySelected.none { key(it) == value }
        val newMoment = newValue(momentKey(result, groups)) { momentKey(it, groups) }
        val newSource = newValue(sourceKey(result), ::sourceKey)
        val newPeople = newValue(peopleKey(result), ::peopleKey)
        val newLocation = newValue(locationKey(result), ::locationKey)
        val newDate = newValue(dateKey(result, zoneId)) { dateKey(it, zoneId) }
        val newShape = newValue(shapeKey(result), ::shapeKey)
        return when (result) {
            is HybridSearchResult.Document -> when (profile.primary) {
                StandoutIntent.DOCUMENT -> when {
                    newSource -> "Different source"
                    newMoment -> "Different record"
                    newDate -> "Useful date"
                    else -> "Strong text match"
                }
                else -> result.match.chunk.source.displayName
            }
            is HybridSearchResult.Gallery -> {
                val media = result.media
                when (profile.primary) {
                    StandoutIntent.PEOPLE -> when {
                        newPeople -> "Different companions"
                        newLocation -> "Different place"
                        newDate -> "Different date"
                        newMoment -> "Different occasion"
                        else -> media.personLabel?.trim()?.takeIf(String::isNotBlank)
                            ?: "Strong identity match"
                    }
                    StandoutIntent.LOCATION -> when {
                        newDate -> "Different date"
                        newMoment -> "Different moment"
                        else -> media.locationName?.trim()?.takeIf(String::isNotBlank)
                            ?: group?.location?.trim()?.takeIf(String::isNotBlank)
                            ?: "Strong place match"
                    }
                    StandoutIntent.TIME -> when {
                        newLocation -> "Different place"
                        newMoment -> "Different moment"
                        else -> dateLabel(media, zoneId) ?: "Strong time match"
                    }
                    StandoutIntent.DOCUMENT -> "Text found"
                    StandoutIntent.SCENERY -> when {
                        newLocation -> "Different setting"
                        newDate -> "Different date"
                        newShape -> "Different framing"
                        newMoment -> "Different moment"
                        (group?.memberCount ?: 0) > 1 -> "Moment highlight"
                        else -> "Visual variety"
                    }
                }
            }
        }
    }

    private fun identityKey(result: HybridSearchResult): String = when (result) {
        is HybridSearchResult.Gallery -> "g:${result.media.mediaStoreId}"
        is HybridSearchResult.Document -> "d:${result.match.chunk.stableId}"
    }

    private fun momentKey(result: HybridSearchResult, groups: Map<Long, EvidenceGroup>): String = when (result) {
        is HybridSearchResult.Document -> "document:${result.match.chunk.source}:${result.match.chunk.recordKey}"
        is HybridSearchResult.Gallery -> groups[result.media.mediaStoreId]?.episodeId
            ?: run {
                val media = result.media
                val timestamp = media.dateTakenMs
                    ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1_000L)
                val timeBucket = timestamp?.div(APPROXIMATE_MOMENT_MS)?.toString().orEmpty()
                "gallery:$timeBucket:${locationKey(result)}:${peopleKey(result)}"
            }
    }

    private fun sourceKey(result: HybridSearchResult): String = when (result) {
        is HybridSearchResult.Gallery -> result.media.mimeType.substringBefore('/').lowercase(Locale.ROOT)
        is HybridSearchResult.Document -> result.match.chunk.source.name
    }

    private fun peopleKey(result: HybridSearchResult): String = when (result) {
        is HybridSearchResult.Gallery -> result.media.personLabel.orEmpty().normalizedKey()
        is HybridSearchResult.Document -> ""
    }

    private fun locationKey(result: HybridSearchResult): String = when (result) {
        is HybridSearchResult.Gallery ->
            (result.media.locationName ?: result.media.location).orEmpty().normalizedKey()
        is HybridSearchResult.Document -> ""
    }

    private fun dateKey(result: HybridSearchResult, zoneId: ZoneId): String = when (result) {
        is HybridSearchResult.Document -> result.match.chunk.timestampMs
            ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().toString() }
            .orEmpty()
        is HybridSearchResult.Gallery -> {
            val timestamp = result.media.dateTakenMs
                ?: result.media.dateModifiedSeconds.takeIf { it > 0L }?.times(1_000L)
            timestamp?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().toString() }.orEmpty()
        }
    }

    private fun shapeKey(result: HybridSearchResult): String = when (result) {
        is HybridSearchResult.Document -> "document"
        is HybridSearchResult.Gallery -> when {
            result.media.width > result.media.height -> "landscape"
            result.media.height > result.media.width -> "portrait"
            else -> "square"
        }
    }

    private fun dateLabel(media: GalleryMedia, zoneId: ZoneId): String? {
        val timestamp = media.dateTakenMs
            ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1_000L)
            ?: return null
        return DATE_LABEL_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(zoneId))
    }

    private fun String.normalizedKey(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private const val RELEVANCE_WEIGHT = 0.60f
    private const val NOVELTY_WEIGHT = 0.40f
    private const val UNKNOWN_NOVELTY = 0.15f
    private const val APPROXIMATE_MOMENT_MS = 2L * 60L * 60L * 1_000L
    private val DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy")
}

data class SearchResponse(
    val gallery: List<GalleryMedia>,
    /** Full evaluated match count; [gallery] is the bounded UI browsing window. */
    val totalGalleryMatches: Int = gallery.size,
    /** Raw gallery semantic cosine by media-store ID, when semantic retrieval contributed. */
    val galleryCosineScores: Map<Long, Float> = emptyMap(),
    val answerGallery: List<GalleryMedia> = emptyList(),
    val answerContext: AnswerContextBundle? = null,
    /** One representative record per evidence-builder episode, when used. */
    val evidenceRecords: List<GalleryMedia> = emptyList(),
    val evidenceGroups: List<EvidenceGroup> = emptyList(),
    /** Bounded episode metadata used only to curate the public Standout 8. */
    val standoutGroups: List<EvidenceGroup> = emptyList(),
    val standoutIntent: StandoutIntentProfile = StandoutIntentProfile(),
    val evidenceGroupingMode: EvidenceGroupingMode = EvidenceGroupingMode.NONE,
    /** Planner session; answer generation deliberately uses a clean turn. */
    val plannerSession: GemmaRuntime.ConversationSession? = null,
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
    /** E2B-resolved standalone wording retained only for the next contextual turn. */
    val resolvedQuery: String = "",
    val queryCategory: QueryCategory = QueryCategory.SCENARY,
    /** Frozen Settings value for this query; false means no answer work was scheduled. */
    val answerFeatureEnabled: Boolean = true,
    val needsAnswer: Boolean = true,
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    /** OCR conjunction that grounded the original answer; follow-ups inherit it for "it". */
    val answerOcrKeywords: List<String> = emptyList(),
    val timings: PhaseTimings = PhaseTimings(),
    val documentMatches: List<DocumentMatch> = emptyList(),
    /** Full accepted private-record count before the shared Top 100 window. */
    val totalDocumentMatches: Int = documentMatches.size,
    /** The exact cross-source rank-fused order used by the shared top-100 result window. */
    val mergedResults: List<HybridSearchResult> = emptyList(),
)

/**
 * Freezes the answer window to the same mixed order already published in the
 * search grid. Answer preparation may enrich these records, but it must never
 * privately rerank, replace, or backfill them.
 */
internal object AnswerEvidencePolicy {
    const val MAX_RECORDS = 8

    fun displayedTopEight(response: SearchResponse): List<HybridSearchResult> {
        val displayed = response.mergedResults.ifEmpty {
            buildList {
                response.gallery.forEach { add(HybridSearchResult.Gallery(it)) }
                response.documentMatches.forEach { add(HybridSearchResult.Document(it)) }
            }
        }
        return displayed.take(MAX_RECORDS)
    }
}

/** A displayed visual record could not be attached, so answering was stopped. */
class AnswerEvidenceUnavailableException(
    val recordNumber: Int,
    detail: String,
) : IllegalStateException("Displayed result R$recordNumber could not be read: $detail")

/** Wall-clock durations shown to the user for one complete search answer. */
data class PhaseTimings(
    val queryPlanningMs: Long = 0L,
    val searchMs: Long = 0L,
    val diverseRerankingMs: Long = 0L,
    val evidenceCurationMs: Long = 0L,
    val answerImagePreparationMs: Long = 0L,
    val answerGenerationMs: Long = 0L,
    val answerInitialPassMs: Long = 0L,
    val answerRetryMs: Long = 0L,
    val answerAttemptCount: Int = 0,
    val followUpMs: Long = 0L,
    val plannerProfile: GemmaRuntime.GenerationProfile? = null,
    val answerProfile: GemmaRuntime.GenerationProfile? = null,
) {
    fun withAnswerTimings(
        answerMs: Long,
        followUpPhaseMs: Long,
        imagePreparationMs: Long = 0L,
        initialPassMs: Long = 0L,
        retryMs: Long = 0L,
        attemptCount: Int = 0,
    ): PhaseTimings = copy(
        answerImagePreparationMs = imagePreparationMs,
        answerGenerationMs = answerMs,
        answerInitialPassMs = initialPassMs,
        answerRetryMs = retryMs,
        answerAttemptCount = attemptCount,
        followUpMs = followUpPhaseMs,
    )

    fun withPlannerProfile(profile: GemmaRuntime.GenerationProfile?): PhaseTimings =
        if (profile == null) this else copy(plannerProfile = profile)

    fun withAnswerProfile(profile: GemmaRuntime.GenerationProfile?): PhaseTimings =
        if (profile == null) this else copy(answerProfile = profile)

    val totalMs: Long
        get() = queryPlanningMs + searchMs + diverseRerankingMs + evidenceCurationMs +
            answerImagePreparationMs + answerGenerationMs + followUpMs
}
