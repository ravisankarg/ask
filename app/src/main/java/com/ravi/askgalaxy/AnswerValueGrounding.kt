package com.ravi.askgalaxy

import java.util.Locale

/**
 * Finds distinct values for a requested direct document field without
 * treating every number in OCR as an answer. The field label must occur on
 * the same or a neighbouring OCR line, and duplicates from repeated scans
 * are collapsed.
 */
internal object AnswerValueGrounding {
    fun expectedValues(query: String, records: List<GalleryMedia>): List<String> =
        expectedValues(query, records, emptyList())

    /** Ground direct-field values across gallery OCR and private documents. */
    fun expectedValues(
        query: String,
        records: List<GalleryMedia>,
        documents: List<DocumentMatch>,
    ): List<String> {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (!AnswerReviewGate.isDirectFieldQuestion(normalizedQuery)) return emptyList()
        if (DocumentAmountGrounding.isAmountQuestion(normalizedQuery)) return emptyList()

        val gallerySources = records.map { SourceRecord(it.ocrText, isDocument = false) }
        val documentSources = documents.map { match ->
            SourceRecord(
                text = listOf(match.chunk.title, match.chunk.metadata, match.chunk.text)
                    .filter(String::isNotBlank)
                    .joinToString("\n"),
                isDocument = true,
            )
        }
        val relevantSources = selectRelevantSources(normalizedQuery, gallerySources, documentSources)
        val candidates = relevantSources.flatMapIndexed { sourceIndex, source ->
            extractValues(normalizedQuery, source.text).map { candidate ->
                candidate.copy(
                    recordIndex = sourceIndex,
                    score = candidate.score + if (source.isDocument) 1 else 0,
                )
            }
        }
        return candidates
            .groupBy { it.normalized }
            .values
            .map { group -> group.maxWith(compareBy<Candidate> { it.score }.thenBy { -it.recordIndex }) }
            .sortedWith(compareBy<Candidate> { it.recordIndex }.thenByDescending { it.score })
            .map { it.value }
            .take(MAX_VALUES)
    }

    private fun selectRelevantSources(
        normalizedQuery: String,
        gallerySources: List<SourceRecord>,
        documentSources: List<SourceRecord>,
    ): List<SourceRecord> {
        val anchors = SUBJECT_STOP_WORDS
            .let { stopWords ->
                Regex("[\\p{L}\\p{N}]{3,}")
                    .findAll(normalizedQuery)
                    .map { it.value }
                    .filterNot { it in stopWords }
                    .distinct()
                    .toList()
            }
        if (anchors.isEmpty()) return gallerySources + documentSources

        val matchingDocuments = documentSources.filter { source ->
            val text = source.text.lowercase(Locale.ROOT)
            anchors.any { it in text }
        }
        val matchingGallery = gallerySources.filter { source ->
            val text = source.text.lowercase(Locale.ROOT)
            anchors.any { it in text }
        }
        // For insurance/policy fields, a matching private policy file is a
        // stronger field source than an incidental gallery scan. This keeps an
        // old photographed policy from overriding the named person's PDF.
        if (POLICY_FIELD.containsMatchIn(normalizedQuery) && matchingDocuments.isNotEmpty()) {
            return matchingDocuments
        }
        return (matchingGallery + matchingDocuments).ifEmpty { gallerySources + documentSources }
    }

    /** Extracts a field-labelled value without borrowing a nearby phone line. */
    private fun extractValues(query: String, text: String): List<Candidate> {
        val fieldPattern = fieldPattern(query)
        val asksDate = DATE_FIELD.containsMatchIn(query)
        val lines = text.lineSequence()
            .map { it.replace(Regex("[\\t ]+"), " ").trim() }
            .filter(String::isNotBlank)
            .toList()
        val candidates = ArrayList<Candidate>()
        lines.forEachIndexed { lineIndex, line ->
            val first = (lineIndex - 1).coerceAtLeast(0)
            val last = (lineIndex + 1).coerceAtMost(lines.lastIndex)
            val window = lines.subList(first, last + 1).joinToString(" ")
            if (!fieldPattern.containsMatchIn(window)) return@forEachIndexed
            val fieldLineValues = if (fieldPattern.containsMatchIn(line)) {
                if (asksDate) DATE_VALUE.findAll(line).map { it.value }.toList()
                else VALUE.findAll(line).map { it.value.trim() }.toList()
            } else {
                emptyList()
            }
            val values = if (fieldLineValues.isNotEmpty()) fieldLineValues else if (asksDate) {
                DATE_VALUE.findAll(window).map { it.value }.toList()
            } else {
                VALUE.findAll(window)
                    .map { it.value.trim() }
                    .filterNot(::looksLikeDate)
                    .filterNot(::looksLikeYear)
                    .toList()
            }
            values.forEach { value ->
                val normalized = normalize(value)
                if (normalized.isNotBlank()) {
                    candidates += Candidate(
                        value = value,
                        normalized = normalized,
                        recordIndex = 0,
                        score = (if (fieldPattern.containsMatchIn(line)) 3 else 1) +
                            if (value in line) 1 else 0,
                    )
                }
            }
        }
        return candidates
    }

    private fun fieldPattern(query: String): Regex = when {
        PASSPORT_FIELD.containsMatchIn(query) -> PASSPORT_FIELD
        AADHAAR_FIELD.containsMatchIn(query) -> AADHAAR_FIELD
        LICENCE_FIELD.containsMatchIn(query) -> LICENCE_FIELD
        POLICY_FIELD.containsMatchIn(query) -> POLICY_FIELD
        else -> GENERIC_FIELD
    }

    fun missingValues(query: String, draft: String, records: List<GalleryMedia>): List<String> {
        return missingValues(query, draft, records, emptyList())
    }

    fun missingValues(
        query: String,
        draft: String,
        records: List<GalleryMedia>,
        documents: List<DocumentMatch>,
    ): List<String> {
        val answer = normalize(draft)
        return expectedValues(query, records, documents).filterNot { normalize(it) in answer }
    }

    fun completeAnswer(draft: String, missingValues: List<String>): String {
        if (missingValues.isEmpty()) return draft
        val suffix = missingValues.joinToString(", ")
        return if (draft.isBlank()) suffix else "$draft Also: $suffix."
    }

    fun directValueFallback(query: String, values: List<String>): String {
        if (values.isEmpty()) return ""
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val label = when {
            POLICY_FIELD.containsMatchIn(normalizedQuery) -> "Policy numbers"
            AADHAAR_FIELD.containsMatchIn(normalizedQuery) -> "Aadhaar number"
            PASSPORT_FIELD.containsMatchIn(normalizedQuery) -> "Passport number"
            LICENCE_FIELD.containsMatchIn(normalizedQuery) -> "Licence number"
            else -> "Matching values"
        }
        return "$label: ${values.joinToString(", ")}" 
    }

    /**
     * Exact identity/policy fields are safer as a local structured answer than
     * as a generative choice among several equally valid records.
     */
    fun isDeterministicValueQuestion(query: String): Boolean {
        val normalized = query.lowercase(Locale.ROOT)
        if (!NUMBER_FIELD.containsMatchIn(normalized)) return false
        return PASSPORT_FIELD.containsMatchIn(normalized) ||
            AADHAAR_FIELD.containsMatchIn(normalized) ||
            LICENCE_FIELD.containsMatchIn(normalized) ||
            POLICY_FIELD.containsMatchIn(normalized)
    }

    /** Keeps Gemma from relabelling an explicit identity value as a phone. */
    fun constrainFieldLabel(query: String, draft: String): String {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (!NUMBER_FIELD.containsMatchIn(normalizedQuery)) return draft
        if (PHONE_FIELD.containsMatchIn(normalizedQuery)) return draft
        val label = when {
            AADHAAR_FIELD.containsMatchIn(normalizedQuery) -> "Aadhaar number"
            PASSPORT_FIELD.containsMatchIn(normalizedQuery) -> "passport number"
            LICENCE_FIELD.containsMatchIn(normalizedQuery) -> "licence number"
            else -> return draft
        }
        return draft.replace(
            Regex("(?i)\\b(?:phone|mobile|cell|telephone|contact)\\s+number\\b"),
            label,
        ).replace(
            Regex("(?i)\\b(?:phone|mobile|cell|telephone|contact)\\s*(?:no\\.?|number)\\b"),
            label,
        )
    }

    fun fieldLabelInstruction(query: String): String {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (!NUMBER_FIELD.containsMatchIn(normalizedQuery)) return "No explicit identity-number label rule."
        return when {
            AADHAAR_FIELD.containsMatchIn(normalizedQuery) ->
                "The requested value is an Aadhaar number. Call it an Aadhaar number, never a phone, mobile, contact, or telephone number."
            PASSPORT_FIELD.containsMatchIn(normalizedQuery) ->
                "The requested value is a passport number. Call it a passport number, never a phone, mobile, contact, or telephone number."
            LICENCE_FIELD.containsMatchIn(normalizedQuery) ->
                "The requested value is a licence number. Call it a licence number, never a phone, mobile, contact, or telephone number."
            POLICY_FIELD.containsMatchIn(normalizedQuery) ->
                "The requested value is an insurance policy number. Prefer matching named-person policy files and do not use an unrelated or expired historical policy when a valid matching policy is supplied."
            else -> "Preserve the identity-field label requested by the user; do not substitute a phone-number label."
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")

    private fun looksLikeDate(value: String): Boolean =
        DATE_VALUE.matches(value.trim())

    private fun looksLikeYear(value: String): Boolean =
        value.trim().matches(Regex("(?:19|20)\\d{2}"))

    private data class Candidate(
        val value: String,
        val normalized: String,
        val recordIndex: Int,
        val score: Int,
    )

    private const val MAX_VALUES = 8
    private val DATE_FIELD = Regex(
        "\\b(date|dated|dob|birth|born|expiry|expiration|expire|valid until)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PASSPORT_FIELD = Regex("\\bpassport\\b", RegexOption.IGNORE_CASE)
    private val AADHAAR_FIELD = Regex("\\b(?:aadhaar|aadhar)\\b", RegexOption.IGNORE_CASE)
    private val LICENCE_FIELD = Regex("\\b(?:licen[cs]e|driving)\\b", RegexOption.IGNORE_CASE)
    private val POLICY_FIELD = Regex("\\b(?:n?policy|insurance)\\b", RegexOption.IGNORE_CASE)
    private val GENERIC_FIELD = Regex(
        "\\b(?:number|no\\.?|code|reference|ref\\.?|account|policy|certificate|identity|\\bid\\b)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER_FIELD = Regex(
        "\\b(?:number|no\\.?|num)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PHONE_FIELD = Regex(
        "\\b(?:phone|mobile|cell|telephone|contact)\\s*(?:number|no\\.?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SUBJECT_STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "for", "with", "from", "in", "on", "at",
        "what", "which", "who", "whose", "where", "when", "how", "show", "find", "give",
        "tell", "me", "my", "your", "number", "numbers", "num", "no", "policy", "npolicy",
        "insurance", "document", "file", "files", "photo", "photos", "image", "images", "pdf",
        "details", "current", "active", "old", "expired", "expiry", "expiration", "valid",
    )
    private val VALUE = Regex("(?i)(?<![\\p{L}\\p{N}])[A-Z]{0,3}\\d{3,}[A-Z0-9-]*(?![\\p{L}\\p{N}])")
    private val DATE_VALUE = Regex("(?<![\\p{L}\\p{N}])\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}(?![\\p{L}\\p{N}])")

    private data class SourceRecord(
        val text: String,
        val isDocument: Boolean,
    )
}
