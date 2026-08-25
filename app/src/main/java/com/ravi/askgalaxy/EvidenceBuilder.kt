package com.ravi.askgalaxy

/** The kind of curated evidence handed to the final answer writer. */
enum class EvidenceGroupingMode {
    NONE,
    EPISODES,
}

/**
 * Query-specific representative of a preprocessing-time episode.
 *
 * The representative is the highest-ranked retrieved member, not necessarily
 * the generic representative stored in SQLite. This is how the Context Picker
 * gets the visually/semantically relevant view from each episode cheaply.
 */
data class EvidenceGroup(
    val episodeId: String,
    val representative: GalleryMedia,
    val memberCount: Int,
    val matchedMemberCount: Int,
    val matchedMediaStoreIds: Set<Long>,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val location: String?,
)

data class EvidenceBuildResult(
    val representativeCandidates: List<GalleryMedia>,
    /** Episode groups used for diversity on every query. */
    val contextGroups: List<EvidenceGroup> = emptyList(),
    /** Episode groups exposed to the answer only for explicit event/count intent. */
    val evidenceGroups: List<EvidenceGroup> = emptyList(),
    val groupingMode: EvidenceGroupingMode = EvidenceGroupingMode.NONE,
)

/**
 * Joins ranked retrieval results to the durable episode membership index.
 *
 * No EXIF reads, geocoding, or chronological clustering occur here. The only
 * query-time work is a bounded indexed SQLite join and stable grouping by
 * episode ID.
 */
class EvidenceBuilder(
    private val database: GalleryDatabase,
) {
    fun build(
        query: String,
        candidates: List<GalleryMedia>,
    ): EvidenceBuildResult {
        if (candidates.isEmpty()) return EvidenceBuildResult(emptyList())
        val ranked = candidates
            .distinctBy { it.mediaStoreId }
            .take(MAX_EPISODE_CANDIDATES)
        val rankById = ranked.mapIndexed { rank, media -> media.mediaStoreId to rank }.toMap()
        val memberships = database.episodeMemberships(
            ranked.map { it.mediaStoreId }.toLongArray(),
        )
        val candidatesByEpisode = LinkedHashMap<String, MutableList<GalleryMedia>>()
        ranked.forEach { media ->
            val episodeId = memberships[media.mediaStoreId]?.episodeId
                ?: "unindexed-${media.mediaStoreId}"
            candidatesByEpisode.getOrPut(episodeId, ::arrayListOf) += media
        }
        val contextGroups = candidatesByEpisode.map { (episodeId, members) ->
            val representative = members.minBy { rankById[it.mediaStoreId] ?: Int.MAX_VALUE }
            val stored = memberships[representative.mediaStoreId]
            val timestamps = members.mapNotNull(::timestamp)
            EvidenceGroup(
                episodeId = episodeId,
                representative = representative,
                memberCount = stored?.memberCount ?: members.size,
                matchedMemberCount = members.size,
                matchedMediaStoreIds = members.mapTo(linkedSetOf()) { it.mediaStoreId },
                startTimeMs = stored?.startTimeMs ?: timestamps.minOrNull(),
                endTimeMs = stored?.endTimeMs ?: timestamps.maxOrNull(),
                location = stored?.location
                    ?: representative.locationName
                    ?: representative.location,
            )
        }.sortedBy { rankById[it.representative.mediaStoreId] ?: Int.MAX_VALUE }
            .take(MAX_CONTEXT_EPISODES)

        val answerGroups = if (needsEpisodeEvidence(query)) {
            contextGroups.take(MAX_ANSWER_EPISODES)
        } else {
            emptyList()
        }
        return EvidenceBuildResult(
            representativeCandidates = contextGroups.map { it.representative },
            contextGroups = contextGroups,
            evidenceGroups = answerGroups,
            groupingMode = if (answerGroups.isEmpty()) {
                EvidenceGroupingMode.NONE
            } else {
                EvidenceGroupingMode.EPISODES
            },
        )
    }

    private fun needsEpisodeEvidence(query: String): Boolean {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        val eventNoun = Regex(
            "\\b(trek|treks|trip|trips|journey|journeys|visit|visits|vacation|vacations|" +
                "holiday|holidays|outing|outings|birthday|birthdays|wedding|weddings|" +
                "festival|festivals|concert|concerts|conference|conferences|party|parties|" +
                "event|events)\\b",
        ).containsMatchIn(normalized)
        if (!eventNoun) return false
        return Regex(
            "\\b(how\\s+many|count|number\\s+of|each|every|different|separate|list|which|all)\\b",
        ).containsMatchIn(normalized)
    }

    private fun timestamp(media: GalleryMedia): Long? =
        media.dateTakenMs ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1_000L)

    private companion object {
        const val MAX_EPISODE_CANDIDATES = 100
        // Standout selection may inspect any accepted result in the fused Top 100.
        const val MAX_CONTEXT_EPISODES = CrossEngineFusionPolicy.OVERALL_RESULT_LIMIT
        const val MAX_ANSWER_EPISODES = 12
    }
}
