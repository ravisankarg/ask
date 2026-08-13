package com.ravi.askgalaxy

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
    val evidenceGroupingMode: EvidenceGroupingMode = EvidenceGroupingMode.NONE,
    /** Planner session; answer generation deliberately uses a clean turn. */
    val plannerSession: GemmaRuntime.ConversationSession? = null,
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
    /** E2B-resolved standalone wording retained only for the next contextual turn. */
    val resolvedQuery: String = "",
    val queryCategory: QueryCategory = QueryCategory.SCENARY,
    val needsAnswer: Boolean = true,
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    /** OCR conjunction that grounded the original answer; follow-ups inherit it for "it". */
    val answerOcrKeywords: List<String> = emptyList(),
    val timings: PhaseTimings = PhaseTimings(),
    val documentMatches: List<DocumentMatch> = emptyList(),
    /** The exact cross-source order used by the shared top-16 result window. */
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
