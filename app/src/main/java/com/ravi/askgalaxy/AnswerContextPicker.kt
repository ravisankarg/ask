package com.ravi.askgalaxy

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
    /** Eight bounded inputs keep the multimodal answer path explicit and capped. */
    const val ANSWER_IMAGE_LIMIT = 8

    fun answerImageLimit(category: QueryCategory): Int = when (category) {
        QueryCategory.DOC, QueryCategory.SCENARY -> ANSWER_IMAGE_LIMIT
        else -> 0
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

    fun isVisualMedia(media: GalleryMedia): Boolean =
        media.mimeType.startsWith("image/", ignoreCase = true) ||
            media.mimeType.startsWith("video/", ignoreCase = true)
}

/** Selects the first eight grounded gallery records without changing public rank order. */
class AnswerContextPicker {
    fun pick(
        query: String,
        rankedCandidates: List<GalleryMedia>,
        evidenceScope: AnswerEvidenceScope,
        queryCategory: QueryCategory,
        ocrKeywords: List<String> = emptyList(),
        maxRecords: Int = MAX_RECORDS,
    ): AnswerContextBundle {
        val inputCandidates = rankedCandidates.distinctBy { it.mediaStoreId }
        if (inputCandidates.isEmpty() || maxRecords <= 0) {
            return AnswerContextBundle(
                items = emptyList(),
                metadataFields = evidenceScope.metadataFields,
                includeOcr = evidenceScope.needsOcr,
                queryCategory = queryCategory,
                includeVisuals = false,
                inputCandidateCount = inputCandidates.size,
                eligibleCandidateCount = 0,
            )
        }
        val safeMax = maxRecords.coerceIn(1, MAX_RECORDS)
        val normalizedKeywords = ocrKeywords
            .map { it.lowercase(Locale.ROOT) }
            .filter(String::isNotBlank)
            .distinct()
            .take(OcrKeywordPolicy.MAX_KEYWORDS)
        val boundedWindow = inputCandidates.take(MAX_RECORDS)
        val eligibleCandidates = if (queryCategory == QueryCategory.DOC && normalizedKeywords.isNotEmpty()) {
            // Gallery retrieval already enforces semantic AND all keywords. The
            // repeat check is a final grounding guard, and filtering retains
            // the exact public result order.
            boundedWindow.filter { media ->
                OcrKeywordPolicy.matchesAll(media.ocrText, normalizedKeywords)
            }
        } else {
            boundedWindow
        }
        val selected = eligibleCandidates.take(safeMax)
        val chosen = selected.map { media ->
            val coverage = linkedSetOf(
                AnswerCoverageFacet.CATEGORY_MATCH,
                AnswerCoverageFacet.RELEVANCE,
            )
            if (media.ocrText.isNotBlank() && evidenceScope.needsOcr) {
                coverage += AnswerCoverageFacet.OCR
            }
            AnswerContextItem(media, coverage)
        }
        val queryText = query.lowercase(Locale.ROOT)
        val needsTimeline = evidenceScope.needsTimeMetadata ||
            Regex("\\b(when|date|time|last|latest|before|after|during)\\b").containsMatchIn(queryText)
        val metadataFields = buildSet {
            addAll(evidenceScope.metadataFields)
            if (chosen.any { !it.media.personLabel.isNullOrBlank() }) add(AnswerMetadataField.PEOPLE)
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
            includeOcr = evidenceScope.needsOcr || chosen.any { it.media.ocrText.isNotBlank() },
            queryCategory = queryCategory,
            includeVisuals = chosen.any { QueryCategoryContextPolicy.isVisualMedia(it.media) },
            inputCandidateCount = inputCandidates.size,
            eligibleCandidateCount = eligibleCandidates.size,
        )
    }

    private companion object {
        const val MAX_RECORDS = 8
    }
}
