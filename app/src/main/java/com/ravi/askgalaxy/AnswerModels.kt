package com.ravi.askgalaxy

enum class AnswerSourceType {
    GALLERY_IMAGE,
    PERSONAL_CONTEXT,
}

data class AnswerSource(
    val id: String,
    val type: AnswerSourceType,
    val label: String,
    val detail: String,
    val media: GalleryMedia? = null,
    val context: PersonalContextItem? = null,
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

data class SearchResponse(
    val gallery: List<GalleryMedia>,
    /** Full evaluated match count; [gallery] is the bounded UI browsing window. */
    val totalGalleryMatches: Int = gallery.size,
    val personalContext: List<PersonalContextMatch>,
    val answerGallery: List<GalleryMedia> = emptyList(),
    val answerContext: AnswerContextBundle? = null,
    /** One representative record per evidence-builder episode, when used. */
    val evidenceRecords: List<GalleryMedia> = emptyList(),
    val evidenceGroups: List<EvidenceGroup> = emptyList(),
    val evidenceGroupingMode: EvidenceGroupingMode = EvidenceGroupingMode.NONE,
    /** Planner-only KV session; answer generation deliberately uses a clean turn. */
    val plannerSession: GemmaRuntime.ConversationSession? = null,
    val plannerJson: String = "",
    val effectivePlanJson: String = "",
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    val timings: PhaseTimings = PhaseTimings(),
)

/** Wall-clock durations shown to the user for one complete search answer. */
data class PhaseTimings(
    val queryPlanningMs: Long = 0L,
    val searchMs: Long = 0L,
    val diverseRerankingMs: Long = 0L,
    val evidenceCurationMs: Long = 0L,
    val answerGenerationMs: Long = 0L,
    val followUpMs: Long = 0L,
) {
    fun withAnswerTimings(answerMs: Long, followUpPhaseMs: Long): PhaseTimings = copy(
        answerGenerationMs = answerMs,
        followUpMs = followUpPhaseMs,
    )

    val totalMs: Long
        get() = queryPlanningMs + searchMs + diverseRerankingMs + evidenceCurationMs + answerGenerationMs + followUpMs
}
