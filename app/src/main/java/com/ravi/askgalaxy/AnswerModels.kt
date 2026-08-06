package com.ravi.askgalaxy

enum class AnswerSourceType {
    GALLERY_IMAGE,
}

data class AnswerSource(
    val id: String,
    val type: AnswerSourceType,
    val label: String,
    val detail: String,
    val media: GalleryMedia? = null,
)

data class AnswerResult(
    val text: String,
    val sources: List<AnswerSource>,
    val followUps: List<FollowUpSuggestion> = emptyList(),
    val timings: PhaseTimings = PhaseTimings(),
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
)

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

data class SearchResponse(
    val gallery: List<GalleryMedia>,
    /** Full evaluated match count; [gallery] is the bounded UI browsing window. */
    val totalGalleryMatches: Int = gallery.size,
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
    val queryCategory: QueryCategory = QueryCategory.SCENARY,
    val needsAnswer: Boolean = true,
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    /** OCR conjunction that grounded the original answer; follow-ups inherit it for "it". */
    val answerOcrKeywords: List<String> = emptyList(),
    val timings: PhaseTimings = PhaseTimings(),
    val documentMatches: List<DocumentMatch> = emptyList(),
)

/** Wall-clock durations shown to the user for one complete search answer. */
data class PhaseTimings(
    val queryPlanningMs: Long = 0L,
    val searchMs: Long = 0L,
    val diverseRerankingMs: Long = 0L,
    val evidenceCurationMs: Long = 0L,
    val answerGenerationMs: Long = 0L,
    val followUpMs: Long = 0L,
    val plannerProfile: GemmaRuntime.GenerationProfile? = null,
    val answerProfile: GemmaRuntime.GenerationProfile? = null,
) {
    fun withAnswerTimings(answerMs: Long, followUpPhaseMs: Long): PhaseTimings = copy(
        answerGenerationMs = answerMs,
        followUpMs = followUpPhaseMs,
    )

    fun withPlannerProfile(profile: GemmaRuntime.GenerationProfile?): PhaseTimings =
        if (profile == null) this else copy(plannerProfile = profile)

    fun withAnswerProfile(profile: GemmaRuntime.GenerationProfile?): PhaseTimings =
        if (profile == null) this else copy(answerProfile = profile)

    val totalMs: Long
        get() = queryPlanningMs + searchMs + diverseRerankingMs + evidenceCurationMs + answerGenerationMs + followUpMs
}
