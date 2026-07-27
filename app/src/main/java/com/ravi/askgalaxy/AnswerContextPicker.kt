package com.ravi.askgalaxy

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class AnswerCoverageFacet {
    CATEGORY_MATCH,
    RELEVANCE,
    VISUAL_DIVERSITY,
    PERSON,
    LOCATION,
    OCR,
    TIMELINE,
    EPISODE,
}

data class AnswerContextItem(
    val media: GalleryMedia,
    val coverage: Set<AnswerCoverageFacet>,
)

data class AnswerContextBundle(
    val items: List<AnswerContextItem>,
    val metadataFields: Set<AnswerMetadataField>,
    val includeOcr: Boolean,
    val queryCategory: QueryCategory,
    val includeVisuals: Boolean,
    val inputCandidateCount: Int,
    val eligibleCandidateCount: Int,
) {
    val records: List<GalleryMedia>
        get() = items.map { it.media }
}

/** Converts the Gemma category into the same eligibility policy used by search and answers. */
object QueryCategoryContextPolicy {
    const val SCENARY_ANSWER_IMAGE_LIMIT = 4
    const val SCENARY_ANSWER_IMAGE_MAX_DIMENSION = 512

    fun answerImageLimit(category: QueryCategory): Int = when (category) {
        QueryCategory.SCENARY -> SCENARY_ANSWER_IMAGE_LIMIT
        QueryCategory.DOC,
        QueryCategory.PERSON,
        QueryCategory.LOCATION,
        QueryCategory.TIME,
        -> 0
    }

    fun includesVisuals(category: QueryCategory): Boolean =
        answerImageLimit(category) > 0

    fun accepts(category: QueryCategory, media: GalleryMedia): Boolean = when (category) {
        QueryCategory.DOC -> isDocumentLike(media)
        QueryCategory.SCENARY -> !isDocumentLike(media)
        QueryCategory.PERSON -> !media.personLabel.isNullOrBlank()
        QueryCategory.LOCATION -> !media.locationName.isNullOrBlank()
        QueryCategory.TIME ->
            media.dateTakenMs != null ||
                media.dateModifiedSeconds > 0L
    }

    fun isDocumentLike(media: GalleryMedia): Boolean =
        media.contentClass == MediaContentClass.DOC
}

/**
 * Query-aware top-8 record policy.
 *
 * Selection runs in two passes:
 * 1. reserve a bounded coverage budget for requested people/place/OCR/time and
 *    EvidenceBuilder episodes;
 * 2. fill remaining slots from text/metadata relevance. Scenery alone may use
 *    the persisted SigLIP embedding order for diversity. No bitmap is decoded
 *    or passed into the picker.
 *
 * The result is the complete LLM record context; callers should not append a
 * second uncurated metadata tail after this bundle.
 */
class AnswerContextPicker(
    private val selectDiverse: (List<GalleryMedia>, Int) -> List<GalleryMedia>,
) {
    constructor(semanticIndexer: GallerySemanticIndexer) : this(
        semanticIndexer::selectDiverseCandidatesBlocking,
    )

    fun pick(
        query: String,
        rankedCandidates: List<GalleryMedia>,
        evidenceGroups: List<EvidenceGroup>,
        evidenceScope: AnswerEvidenceScope,
        queryCategory: QueryCategory,
        ocrKeywords: List<String> = emptyList(),
        maxRecords: Int = MAX_RECORDS,
    ): AnswerContextBundle {
        val inputCandidates = rankedCandidates.distinctBy { it.mediaStoreId }
        val eligibleCandidates = inputCandidates.filter {
            QueryCategoryContextPolicy.accepts(queryCategory, it)
        }
        val includeVisuals = QueryCategoryContextPolicy.includesVisuals(queryCategory)
        if (eligibleCandidates.isEmpty() || maxRecords <= 0) {
            return AnswerContextBundle(
                items = emptyList(),
                metadataFields = evidenceScope.metadataFields,
                includeOcr = queryCategory == QueryCategory.DOC,
                queryCategory = queryCategory,
                includeVisuals = includeVisuals,
                inputCandidateCount = inputCandidates.size,
                eligibleCandidateCount = eligibleCandidates.size,
            )
        }
        val safeMax = maxRecords.coerceIn(1, MAX_RECORDS)
        val originalRank = eligibleCandidates.mapIndexed { index, media ->
            media.mediaStoreId to index
        }.toMap()
        val queryText = query.lowercase(Locale.ROOT)
        if (queryCategory == QueryCategory.DOC) {
            val normalizedKeywords = ocrKeywords
                .map(String::lowercase)
                .distinct()
                .take(OcrKeywordPolicy.MAX_KEYWORDS)
            val candidates = eligibleCandidates.sortedWith(
                compareByDescending<GalleryMedia> {
                    OcrKeywordPolicy.matchesAll(it.ocrText, normalizedKeywords)
                }.thenByDescending {
                    textEvidenceScore(queryText, it)
                }.thenBy {
                    originalRank[it.mediaStoreId] ?: Int.MAX_VALUE
                },
            )
            val chosen = candidates.take(safeMax).map { media ->
                val coverage = linkedSetOf(
                    AnswerCoverageFacet.CATEGORY_MATCH,
                    AnswerCoverageFacet.RELEVANCE,
                    AnswerCoverageFacet.OCR,
                )
                AnswerContextItem(media, coverage)
            }
            val metadataFields = buildSet {
                addAll(evidenceScope.metadataFields)
                if (chosen.any { !it.media.personLabel.isNullOrBlank() }) {
                    add(AnswerMetadataField.PEOPLE)
                }
                if (chosen.any {
                        !it.media.locationName.isNullOrBlank() || !it.media.location.isNullOrBlank()
                    }
                ) {
                    add(AnswerMetadataField.LOCATION)
                }
            }
            return AnswerContextBundle(
                items = chosen,
                metadataFields = metadataFields,
                includeOcr = true,
                queryCategory = queryCategory,
                includeVisuals = false,
                inputCandidateCount = inputCandidates.size,
                eligibleCandidateCount = candidates.size,
            )
        }
        val candidates = if (includeVisuals) {
            eligibleCandidates
        } else {
            eligibleCandidates.sortedWith(
                compareByDescending<GalleryMedia> {
                    textEvidenceScore(queryText, it)
                }.thenBy {
                    originalRank[it.mediaStoreId] ?: Int.MAX_VALUE
                },
            )
        }
        val diversityOrder = if (includeVisuals) {
            selectDiverse(candidates, minOf(candidates.size, safeMax))
        } else {
            candidates.take(safeMax)
        }
        val diversityRank = diversityOrder.mapIndexed { index, media ->
            media.mediaStoreId to index
        }.toMap()
        val relevanceRank = candidates.mapIndexed { index, media -> media.mediaStoreId to index }.toMap()
        val episodeByMediaId = buildMap {
            evidenceGroups.forEach { group ->
                group.matchedMediaStoreIds.forEach { mediaStoreId ->
                    put(mediaStoreId, group.episodeId)
                }
            }
        }
        val selected = LinkedHashMap<Long, MutableContextItem>()
        val coverageBudget = minOf(
            safeMax,
            (safeMax * COVERAGE_BUDGET_NUMERATOR / COVERAGE_BUDGET_DENOMINATOR)
                .coerceAtLeast(MIN_COVERAGE_BUDGET),
        )

        fun add(
            media: GalleryMedia,
            facet: AnswerCoverageFacet,
            capacity: Int = coverageBudget,
        ) {
            val existing = selected[media.mediaStoreId]
            if (existing != null) {
                existing.coverage += facet
            } else if (selected.size < capacity) {
                selected[media.mediaStoreId] = MutableContextItem(media, linkedSetOf(facet))
            }
        }

        candidates.firstOrNull()?.let { add(it, AnswerCoverageFacet.CATEGORY_MATCH) }

        // Preserve one strong retrieval anchor before spending slots on
        // coverage. A second anchor is added only when the context budget is
        // large enough to retain broad diversity; prefer another episode
        // within the leading relevance window.
        val firstAnchor = candidates.firstOrNull()
        firstAnchor?.let { add(it, AnswerCoverageFacet.RELEVANCE) }
        if (safeMax >= 8) {
            val firstEpisode = firstAnchor?.let { episodeByMediaId[it.mediaStoreId] }
            candidates.take(SECOND_ANCHOR_RELEVANCE_WINDOW)
                .drop(1)
                .firstOrNull {
                    val episode = episodeByMediaId[it.mediaStoreId]
                    episode == null || firstEpisode == null || episode != firstEpisode
                }
                .orElse(candidates.getOrNull(1))
                ?.let { add(it, AnswerCoverageFacet.RELEVANCE) }
        }

        val orderedForCoverage = candidates.sortedWith(
            compareBy<GalleryMedia> { diversityRank[it.mediaStoreId] ?: Int.MAX_VALUE }
                .thenBy { relevanceRank[it.mediaStoreId] ?: Int.MAX_VALUE },
        )
        val needsPeople = evidenceScope.needsPeopleMetadata ||
            Regex("\\b(who|person|people|with|without)\\b").containsMatchIn(queryText)
        val needsLocation = evidenceScope.needsLocationMetadata ||
            Regex("\\b(where|location|place|trip|travel|visit)\\b").containsMatchIn(queryText)
        val needsTimeline = evidenceScope.needsTimeMetadata ||
            Regex("\\b(when|date|time|last|latest|before|after|during)\\b").containsMatchIn(queryText)

        // Give every requested evidence dimension one representative before
        // any one dimension consumes its full allowance. This avoids, for
        // example, four timeline representatives crowding OCR or place
        // evidence out of a mixed "receipt from the last Goa trip" query.
        val coverageStreams = buildList {
            if (needsPeople) {
                add(
                    CoverageStream(
                        AnswerCoverageFacet.PERSON,
                        uniqueByKey(orderedForCoverage, PERSON_COVERAGE_LIMIT, ::personKey),
                    ),
                )
            }
            if (needsLocation || candidates.any {
                    !it.locationName.isNullOrBlank() || !it.location.isNullOrBlank()
                }
            ) {
                add(
                    CoverageStream(
                        AnswerCoverageFacet.LOCATION,
                        uniqueByKey(orderedForCoverage, LOCATION_COVERAGE_LIMIT, ::locationKey),
                    ),
                )
            }
            if (evidenceScope.needsOcr) {
                add(
                    CoverageStream(
                        AnswerCoverageFacet.OCR,
                        uniqueByKey(
                            orderedForCoverage.filter { it.ocrText.isNotBlank() },
                            OCR_COVERAGE_LIMIT,
                            ::ocrKey,
                        ),
                    ),
                )
            }
            if (needsTimeline) {
                add(
                    CoverageStream(
                        AnswerCoverageFacet.TIMELINE,
                        uniqueByKey(orderedForCoverage, TIMELINE_COVERAGE_LIMIT, ::timelineKey),
                    ),
                )
            }
            val episodeRepresentatives = evidenceGroups
                .sortedBy { relevanceRank[it.representative.mediaStoreId] ?: Int.MAX_VALUE }
                .map { it.representative }
                .filter { it.mediaStoreId in relevanceRank }
                .distinctBy { it.mediaStoreId }
                .take(EPISODE_COVERAGE_LIMIT)
            if (episodeRepresentatives.isNotEmpty()) {
                add(CoverageStream(AnswerCoverageFacet.EPISODE, episodeRepresentatives))
            }
        }
        val maximumDepth = coverageStreams.maxOfOrNull { it.candidates.size } ?: 0
        for (depth in 0 until maximumDepth) {
            coverageStreams.forEach { stream ->
                stream.candidates.getOrNull(depth)?.let { add(it, stream.facet) }
            }
            if (selected.size >= coverageBudget) break
        }

        if (selected.size < safeMax) {
            val remaining = candidates.filterNot { it.mediaStoreId in selected }
            val selectedEpisodes = selected.keys.mapNotNullTo(linkedSetOf()) {
                episodeByMediaId[it]
            }
            // First offer one query-ranked candidate from every unseen
            // preprocessing-time episode. Only after cross-episode coverage
            // is exhausted may a second view from an already represented
            // episode consume a context slot.
            val unseenEpisodeRepresentatives = remaining
                .groupBy { episodeByMediaId[it.mediaStoreId] ?: "media-${it.mediaStoreId}" }
                .filterKeys { it !in selectedEpisodes }
                .values
                .mapNotNull { episodeMembers ->
                    episodeMembers.minByOrNull {
                        relevanceRank[it.mediaStoreId] ?: Int.MAX_VALUE
                    }
                }
            val episodeFill = if (includeVisuals) {
                selectDiverse(
                    unseenEpisodeRepresentatives,
                    (safeMax - selected.size).coerceAtLeast(0),
                )
            } else {
                unseenEpisodeRepresentatives.take((safeMax - selected.size).coerceAtLeast(0))
            }
            episodeFill.forEach { add(it, AnswerCoverageFacet.EPISODE, safeMax) }
            if (selected.size < safeMax) {
                val finalCandidates = remaining.filterNot { it.mediaStoreId in selected }
                val finalFill = if (includeVisuals) {
                    selectDiverse(
                        finalCandidates,
                        (safeMax - selected.size).coerceAtLeast(0),
                    )
                } else {
                    finalCandidates.take((safeMax - selected.size).coerceAtLeast(0))
                }
                finalFill.forEach {
                    add(
                        it,
                        if (includeVisuals) {
                            AnswerCoverageFacet.VISUAL_DIVERSITY
                        } else {
                            AnswerCoverageFacet.CATEGORY_MATCH
                        },
                        safeMax,
                    )
                }
            }
        }

        val chosen = selected.values.take(safeMax).map {
            AnswerContextItem(it.media, it.coverage.toSet())
        }
        val metadataFields = buildSet {
            addAll(evidenceScope.metadataFields)
            if (chosen.any { !it.media.personLabel.isNullOrBlank() }) add(AnswerMetadataField.PEOPLE)
            // A readable location is cheap, highly grounding context and must
            // be available to the answer writer whenever applicable.
            if (chosen.any {
                    !it.media.locationName.isNullOrBlank() || !it.media.location.isNullOrBlank()
                }
            ) {
                add(AnswerMetadataField.LOCATION)
            }
            if (needsTimeline) add(AnswerMetadataField.TIME)
        }
        return AnswerContextBundle(
            items = chosen,
            metadataFields = metadataFields,
            includeOcr = queryCategory == QueryCategory.DOC,
            queryCategory = queryCategory,
            includeVisuals = includeVisuals,
            inputCandidateCount = inputCandidates.size,
            eligibleCandidateCount = candidates.size,
        )
    }

    private fun uniqueByKey(
        candidates: List<GalleryMedia>,
        limit: Int,
        key: (GalleryMedia) -> String,
    ): List<GalleryMedia> {
        val seen = LinkedHashSet<String>()
        return candidates.filter { media ->
            if (seen.size >= limit) return@filter false
            val value = key(media)
            value.isNotBlank() && seen.add(value)
        }.take(limit)
    }

    private fun personKey(media: GalleryMedia): String =
        media.personLabel.orEmpty().lowercase(Locale.ROOT).trim()

    /**
     * Text-only categories are ranked from fields already in memory. This
     * favors an exact OCR/title match over a visually similar document while
     * preserving the original hybrid rank as the stable tie-breaker.
     */
    private fun textEvidenceScore(queryText: String, media: GalleryMedia): Int {
        val queryTokens = queryText
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in TEXT_STOP_WORDS }
            .distinct()
        val name = media.displayName.lowercase(Locale.ROOT)
        val ocr = media.ocrText.lowercase(Locale.ROOT)
        val metadata = listOfNotNull(
            media.personLabel,
            media.locationName,
            media.location,
            media.mimeType,
        ).joinToString(" ").lowercase(Locale.ROOT)
        var score = 0
        queryTokens.forEach { token ->
            if (ocr.contains(token)) score += 5
            if (name.contains(token)) score += 4
            if (metadata.contains(token)) score += 3
        }
        queryTokens.windowed(2).forEach { pair ->
            val phrase = pair.joinToString(" ")
            if (ocr.contains(phrase)) score += 4
            if (name.contains(phrase)) score += 3
        }
        if (AMOUNT_INTENT.containsMatchIn(queryText)) {
            if (ocr.contains("total amount")) score += 8
            if (ocr.contains("grand total")) score += 7
            if (ocr.contains("ticket") && ocr.contains("price")) score += 7
            if (ocr.contains("amount")) score += 3
            if (ocr.contains("price")) score += 2
        }
        return score
    }

    private fun locationKey(media: GalleryMedia): String =
        (media.locationName ?: media.location).orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun ocrKey(media: GalleryMedia): String = media.ocrText
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 }
        .take(OCR_KEY_TOKENS)
        .sorted()
        .joinToString(" ")

    private fun timelineKey(media: GalleryMedia): String {
        val timestamp = media.dateTakenMs
            ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1000L)
            ?: return ""
        val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
    }

    private fun <T> T?.orElse(fallback: T?): T? = this ?: fallback

    private data class MutableContextItem(
        val media: GalleryMedia,
        val coverage: MutableSet<AnswerCoverageFacet>,
    )

    private data class CoverageStream(
        val facet: AnswerCoverageFacet,
        val candidates: List<GalleryMedia>,
    )

    private companion object {
        const val MAX_RECORDS = 8
        const val SECOND_ANCHOR_RELEVANCE_WINDOW = 8
        const val EPISODE_COVERAGE_LIMIT = 4
        const val PERSON_COVERAGE_LIMIT = 3
        const val LOCATION_COVERAGE_LIMIT = 3
        const val OCR_COVERAGE_LIMIT = 3
        const val TIMELINE_COVERAGE_LIMIT = 4
        const val OCR_KEY_TOKENS = 10
        const val COVERAGE_BUDGET_NUMERATOR = 2
        const val COVERAGE_BUDGET_DENOMINATOR = 3
        const val MIN_COVERAGE_BUDGET = 4
        val AMOUNT_INTENT = Regex("\\b(how much|spend|spent|cost|price|amount|total|paid)\\b")
        val TEXT_STOP_WORDS = setOf(
            "the",
            "and",
            "for",
            "from",
            "with",
            "that",
            "this",
            "help",
            "find",
            "show",
            "document",
            "photo",
            "photos",
            "image",
            "images",
        )
    }
}
