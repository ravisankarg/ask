package com.ravi.askgalaxy

import java.util.Locale

/**
 * Generic labelled-field grounding for the bounded answer window.
 *
 * The resolver does not decide what a passport, policy, expiry, or other
 * product field means. It extracts label/value relationships, matches the
 * question against those labels, and leaves final validation to the answer
 * model with the complete records.
 */
object AnswerFactGrounding {
    data class Grounding(
        val matchedFacts: List<Fact>,
    ) {
        val matchedValues: List<String>
            get() = matchedFacts
                .distinctBy { normalize(it.value) }
                .map(Fact::value)

        fun promptText(): String = if (matchedFacts.isEmpty()) {
            "none"
        } else {
            matchedFacts.joinToString("\n") { fact ->
                "R${fact.recordIndex + 1} field=${quote(fact.label)} value=${quote(fact.value)}"
            }
        }
    }

    data class Fact(
        val recordIndex: Int,
        val label: String,
        val value: String,
        val fieldScore: Int,
        val recordScore: Int,
    )

    /** Persisted candidate fact produced without an LLM. */
    data class IndexedFact(
        val label: String,
        val value: String,
    )

    data class DraftCheck(
        val needsRepair: Boolean,
        val missingValues: List<String>,
    )

    private data class Record(
        val key: String,
        val text: String,
        val indexedFacts: List<IndexedFact>,
    )

    private data class RawFact(
        val recordIndex: Int,
        val recordText: String,
        val label: String,
        val value: String,
    )

    fun ground(query: String, evidence: List<HybridSearchResult>): Grounding {
        return ground(query, evidence, emptyMap())
    }

    /**
     * Resolves against persisted candidate facts when available. The complete
     * evidence text is still retained for record-anchor scoring and is always
     * sent to Gemma; the persisted rows are only a fast candidate lookup.
     */
    fun ground(
        query: String,
        evidence: List<HybridSearchResult>,
        indexedFacts: Map<String, List<IndexedFact>>,
    ): Grounding {
        if (DocumentAmountGrounding.isAmountQuestion(query)) return Grounding(emptyList())

        val records = evidence.map { item ->
            when (item) {
                is HybridSearchResult.Gallery -> Record(
                    key = galleryKey(item.media.mediaStoreId),
                    text = listOf(
                        item.media.displayName,
                        item.media.personLabel,
                        item.media.ocrText,
                    ).filterNotNull().filter(String::isNotBlank).joinToString("\n"),
                    indexedFacts = indexedFacts[galleryKey(item.media.mediaStoreId)].orEmpty(),
                )
                is HybridSearchResult.Document -> Record(
                    key = documentKey(item.match.chunk.stableId),
                    text = listOf(
                        item.match.chunk.title,
                        item.match.chunk.metadata,
                        item.match.chunk.text,
                    ).filter(String::isNotBlank).joinToString("\n"),
                    indexedFacts = indexedFacts[documentKey(item.match.chunk.stableId)].orEmpty(),
                )
            }
        }
        val rawFacts = records.flatMapIndexed { recordIndex, record ->
            val facts = record.indexedFacts.ifEmpty {
                extractFacts(record.text).map { (label, value) -> IndexedFact(label, value) }
            }
            facts.map { fact ->
                RawFact(recordIndex, record.text, fact.label, fact.value)
            }
        }
        if (rawFacts.isEmpty()) return Grounding(emptyList())

        val queryTokens = comparableTokens(query)
        val scored = rawFacts.map { fact ->
            val labelTokens = comparableTokens(fact.label)
            val fieldScore = labelTokens.sumOf { token ->
                if (token !in queryTokens) 0 else if (token in GENERIC_FIELD_TOKENS) 1 else 3
            }
            fact to fieldScore
        }
        val bestFieldScore = scored.maxOfOrNull { it.second } ?: 0
        if (bestFieldScore <= 0) return Grounding(emptyList())

        val fieldMatches = scored.filter { it.second == bestFieldScore }
        val matchedLabelTokens = fieldMatches
            .flatMap { comparableTokens(it.first.label) }
            .toSet()
        val recordAnchorTokens = queryTokens
            .filterNot { it in matchedLabelTokens || it in QUERY_STOP_WORDS }
            .toSet()
        val withRecordScores = fieldMatches.map { (fact, fieldScore) ->
            val recordTokens = comparableTokens(fact.recordText)
            val recordScore = recordAnchorTokens.sumOf { token ->
                if (token in recordTokens) 1 else 0
            }
            Fact(
                recordIndex = fact.recordIndex,
                label = fact.label,
                value = fact.value,
                fieldScore = fieldScore,
                recordScore = recordScore,
            )
        }
        val bestRecordScore = withRecordScores.maxOfOrNull(Fact::recordScore) ?: 0
        val selected = withRecordScores
            .filter { bestRecordScore == 0 || it.recordScore == bestRecordScore }
            .distinctBy { "${it.recordIndex}|${normalize(it.label)}|${normalize(it.value)}" }
            .sortedWith(compareBy<Fact> { it.recordIndex }.thenByDescending(Fact::fieldScore))
            .take(MAX_MATCHED_FACTS)
        return Grounding(selected)
    }

    fun missingValues(draft: String, values: List<String>): List<String> {
        val normalizedDraft = normalize(draft)
        return values.filterNot { normalizedDraft.contains(normalize(it)) }
    }

    /** Cheap pre-review contract; it never attempts to interpret the answer. */
    fun checkDraft(draft: String, requiredValues: List<String>): DraftCheck {
        val missing = missingValues(draft, requiredValues)
        val failedText = draft.isBlank() || FAILED_ANSWER_MARKER.containsMatchIn(draft)
        return DraftCheck(
            needsRepair = failedText || missing.isNotEmpty(),
            missingValues = missing,
        )
    }

    fun extractFactsForIndex(text: String): List<IndexedFact> =
        extractFacts(text).map { (label, value) -> IndexedFact(label, value) }

    fun galleryKey(mediaStoreId: Long): String = "gallery:$mediaStoreId"

    fun documentKey(stableId: Long): String = "document:$stableId"

    fun requestedFieldInstruction(query: String): String =
        if (AnswerValueGrounding.isIdentityExpiryDateQuestion(query)) {
            "Resolve the expiry-date attribute requested in ${quote(query)} only from a visible Expiry, Date of Expiry, Expiration, Valid Until, or Valid Till label. The answer must be a calendar date, not a document number, MRZ, phone number, issue date, birth date, capture time, modified time, or another long identifier. Check every relevant top-eight record and do not guess."
        } else {
            "Resolve the exact attribute requested in ${quote(query)} by matching it to the closest explicit field label in each relevant record. " +
                "A value belongs only to its own label; never substitute a neighbouring field, date, number, timestamp, or metadata value. " +
                "Check every relevant top-eight record and return every distinct value that answers that same requested attribute."
        }

    private fun extractFacts(text: String): List<Pair<String, String>> {
        val lines = text.lineSequence()
            .map(::cleanLine)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return emptyList()

        val facts = ArrayList<Pair<String, String>>()
        lines.forEachIndexed { index, line ->
            parseDelimited(line)?.let(facts::add)
            parseInlineScalar(line)?.let(facts::add)

            if (looksLikeStandaloneLabel(line)) {
                val next = lines.getOrNull(index + 1)
                if (next != null && !looksLikeStandaloneLabel(next)) {
                    scalarOrTextValue(next)?.let { value -> facts += line to value }
                }
            }
        }
        return facts
            .map { (label, value) -> cleanLabel(label) to cleanValue(value) }
            .filter { (label, value) -> isUsefulLabel(label) && isUsefulValue(value) }
            .distinctBy { (label, value) -> "${normalize(label)}|${normalize(value)}" }
    }

    private fun parseDelimited(line: String): Pair<String, String>? {
        val match = DELIMITED_FIELD.matchEntire(line) ?: return null
        val label = match.groupValues[1]
        val value = match.groupValues[2]
        return label to value
    }

    private fun parseInlineScalar(line: String): Pair<String, String>? {
        val match = SCALAR_VALUE.find(line) ?: return null
        val label = line.substring(0, match.range.first)
        if (!isUsefulLabel(label)) return null
        return label to match.value
    }

    private fun looksLikeStandaloneLabel(line: String): Boolean {
        if (line.length !in 2..64) return false
        if (line.count(Char::isLetter) < 2) return false
        if (SCALAR_VALUE.containsMatchIn(line)) return false
        if (DELIMITED_FIELD.matches(line)) return false
        return line.split(Regex("\\s+")).size <= MAX_LABEL_WORDS
    }

    private fun scalarOrTextValue(line: String): String? =
        SCALAR_VALUE.find(line)?.value
            ?: line.takeIf { candidate ->
                candidate.length in 1..MAX_VALUE_CHARS &&
                    candidate.count(Char::isLetter) >= 2 &&
                    !looksLikeStandaloneLabel(candidate)
            }

    private fun isUsefulLabel(value: String): Boolean {
        val cleaned = cleanLabel(value)
        if (cleaned.length !in 2..64 || cleaned.count(Char::isLetter) < 2) return false
        if (cleaned.split(Regex("\\s+")).size > MAX_LABEL_WORDS) return false
        return normalize(cleaned) !in IGNORED_LABELS
    }

    private fun isUsefulValue(value: String): Boolean {
        val cleaned = cleanValue(value)
        if (cleaned.isBlank() || cleaned.length > MAX_VALUE_CHARS) return false
        return normalize(cleaned) !in IGNORED_VALUES
    }

    private fun comparableTokens(value: String): Set<String> = TOKEN.findAll(value.lowercase(Locale.ROOT))
        .flatMap { match -> canonicalTokens(match.value).asSequence() }
        .filterNot { it in QUERY_STOP_WORDS }
        .toSet()

    private fun canonicalTokens(token: String): Set<String> = when (token.trimEnd('.')) {
        "no", "num" -> setOf("number")
        "expiry", "expiration", "expires", "expired", "expire" -> setOf("expiry")
        "issued", "issuing" -> setOf("issue")
        "dob", "birthdate" -> setOf("birth", "date")
        "validity" -> setOf("valid")
        "ref" -> setOf("reference")
        else -> setOf(token.trimEnd('.'))
    }

    private fun cleanLine(value: String): String = value
        .replace(Regex("[\\t ]+"), " ")
        .trim()

    private fun cleanLabel(value: String): String = cleanLine(value)
        .trim(':', '=', '#', '-', ' ')

    private fun cleanValue(value: String): String = cleanLine(value)
        .trim(':', '=', '#', ' ')
        .take(MAX_VALUE_CHARS)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")

    private fun quote(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ") + "\""

    private val TOKEN = Regex("[\\p{L}\\p{N}]{2,}")
    private val DELIMITED_FIELD = Regex("^(.{2,64}?)\\s*[:=#]\\s*(.{1,160})$")
    private val SCALAR_VALUE = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:" +
            "\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|" +
            "\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}|" +
            "\\d{1,2}[ -](?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[A-Z]*[ -]\\d{2,4}|" +
            "(?=[A-Z0-9/-]{4,32}(?![\\p{L}\\p{N}]))(?=[A-Z0-9/-]*\\d)[A-Z0-9][A-Z0-9/-]{3,31}" +
            ")(?![\\p{L}\\p{N}])",
    )
    private val QUERY_STOP_WORDS = setOf(
        "what", "which", "when", "where", "who", "whose", "how", "is", "are", "was", "were",
        "the", "a", "an", "of", "for", "from", "with", "my", "me", "your", "show", "find",
        "tell", "give", "please", "current", "active", "old", "expired", "valid", "document",
        "documents", "file", "files", "photo", "photos", "image", "images", "pdf",
    )
    private val GENERIC_FIELD_TOKENS = setOf("number", "date", "id", "code", "value")
    private val IGNORED_LABELS = setOf("http", "https", "uri", "url")
    private val IGNORED_VALUES = setOf("none", "null", "unknown", "na")
    private val FAILED_ANSWER_MARKER = Regex(
        "(?i)\\b(?:couldn['’]?t|cannot|can['’]?t|unable to)\\b.*\\b(?:answer|find|determine)\\b",
    )
    private const val MAX_LABEL_WORDS = 8
    private const val MAX_VALUE_CHARS = 160
    private const val MAX_MATCHED_FACTS = 8
}
