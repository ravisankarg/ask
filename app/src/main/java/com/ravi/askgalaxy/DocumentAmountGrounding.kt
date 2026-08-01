package com.ravi.askgalaxy

import java.util.Locale

/**
 * Conservative numeric grounding for direct document-cost questions.
 *
 * A language model is useful for phrasing an answer, but it must not invent a
 * monetary value when the selected OCR already contains one.  This recognises
 * conventional amount labels across receipts, tickets, invoices, and payment
 * confirmations; it is deliberately independent of merchant or document
 * names.  It only produces a deterministic answer when one value is clearly
 * stronger than every competing distinct value.
 */
object DocumentAmountGrounding {
    data class Candidate(
        val recordId: Long,
        val value: String,
        val label: String,
        val score: Int,
    )

    data class Decision(
        val candidates: List<Candidate>,
        val selected: Candidate?,
    ) {
        fun directAnswer(): String? = selected?.let { "The amount is ${it.value}." }
    }

    fun isAmountQuestion(query: String): Boolean =
        AMOUNT_INTENT.containsMatchIn(query.lowercase(Locale.ROOT))

    fun analyse(query: String, results: List<GalleryMedia>): Decision {
        if (!isAmountQuestion(query)) return Decision(emptyList(), null)
        val candidates = results.flatMap { media -> candidatesFor(media, query) }
            .groupBy { normalizedValue(it.value) }
            .values
            .map { duplicates ->
                // Identical scans should strengthen the same value, never
                // make the UI list it twice.
                duplicates.maxWith(
                    compareByDescending<Candidate> { it.score }
                        .thenBy { it.recordId },
                )
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.recordId })

        val best = candidates.firstOrNull()
        val runnerUp = candidates.getOrNull(1)
        val selected = best?.takeIf { candidate ->
            candidate.score >= MIN_DIRECT_SCORE &&
                (runnerUp == null || candidate.score - runnerUp.score >= MIN_DIRECT_MARGIN)
        }
        return Decision(candidates, selected)
    }

    /**
     * Keeps a direct answer tied to a dominant OCR amount.  We intentionally
     * leave ambiguous documents to Gemma: multiple similarly credible totals
     * must be described rather than silently choosing one.
     */
    fun constrainAnswer(
        query: String,
        generated: String,
        results: List<GalleryMedia>,
    ): String {
        val decision = analyse(query, results)
        val selected = decision.selected ?: return generated
        val generatedValues = extractMoneyLikeValues(generated)
        return if (
            generatedValues.isEmpty() ||
            generatedValues.any { normalizedValue(it) != normalizedValue(selected.value) }
        ) {
            decision.directAnswer().orEmpty()
        } else {
            generated
        }
    }

    fun promptEvidence(query: String, results: List<GalleryMedia>): String {
        val decision = analyse(query, results)
        if (decision.candidates.isEmpty()) return "none"
        return decision.candidates.take(MAX_PROMPT_CANDIDATES).mapIndexed { index, candidate ->
            "A${index + 1} record=${candidate.recordId} label=${candidate.label} value=${candidate.value}"
        }.joinToString("\n")
    }

    private fun candidatesFor(media: GalleryMedia, query: String): List<Candidate> {
        val lines = media.ocrText.lineSequence()
            .map(::normalizeLine)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return emptyList()
        val queryTerms = subjectTerms(query)
        return lines.indices.flatMap { index ->
            val first = (index - NEIGHBOUR_LINES).coerceAtLeast(0)
            val last = (index + NEIGHBOUR_LINES).coerceAtMost(lines.lastIndex)
            val window = lines.subList(first, last + 1).joinToString(" ")
            // Associate a numeric value with its own OCR line first. If OCR
            // put a label and value on neighbouring lines, the small context
            // window supplies the label. This prevents a nearby GST value
            // from borrowing a later "Total Amount" label.
            val sourceLine = lines[index]
            val labelScore = labelScore(sourceLine).takeIf { it > 0 } ?: labelScore(window)
            if (labelScore <= 0) return@flatMap emptyList()
            val score = labelScore + currencyBonus(sourceLine) +
                maxOf(subjectScore(window, queryTerms), subjectScore(media.ocrText, queryTerms)) -
                if (COMPONENT_CHARGE.containsMatchIn(sourceLine)) COMPONENT_PENALTY else 0
            moneyValues(sourceLine).map { value ->
                Candidate(
                    recordId = media.mediaStoreId,
                    value = value,
                    label = compactLabel(if (labelScore(sourceLine) > 0) sourceLine else window),
                    score = score,
                )
            }
        }.distinctBy { "${normalizedValue(it.value)}:${it.label}:${it.recordId}" }
    }

    private fun labelScore(window: String): Int = when {
        GRAND_TOTAL.containsMatchIn(window) -> 72
        TOTAL_AMOUNT.containsMatchIn(window) -> 70
        AMOUNT_PAID.containsMatchIn(window) -> 67
        TOTAL.containsMatchIn(window) -> 58
        TICKET_PRICE.containsMatchIn(window) -> 55
        PRICE.containsMatchIn(window) -> 48
        AMOUNT.containsMatchIn(window) -> 45
        PAID.containsMatchIn(window) -> 42
        FARE.containsMatchIn(window) -> 40
        else -> 0
    }

    private fun currencyBonus(window: String): Int = if (CURRENCY_MARKER.containsMatchIn(window)) 18 else 0

    private fun subjectScore(window: String, queryTerms: Set<String>): Int = queryTerms.sumOf { term ->
        when {
            containsWord(window, term) -> 8
            windowWords(window).any { word -> similar(word, term) } -> 5
            else -> 0
        }
    }

    private fun compactLabel(window: String): String = window
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LABEL_CHARS)

    private fun moneyValues(text: String): List<String> {
        val explicit = EXPLICIT_MONEY.findAll(text).map { match ->
            match.value.replace(Regex("\\s+"), " ").trim()
        }.toList()
        if (explicit.isNotEmpty()) return explicit
        // OCR occasionally separates the currency glyph from its number. A
        // bare numeric value is acceptable only inside a recognised amount
        // label window, so dates and invoice IDs do not become candidates.
        return BARE_AMOUNT.findAll(text)
            .map { it.value.trim() }
            .filterNot { it.length == 4 && it.startsWith("19") || it.startsWith("20") }
            .toList()
    }

    private fun extractMoneyLikeValues(text: String): List<String> {
        val explicit = EXPLICIT_MONEY.findAll(text).map { it.value }.toList()
        return if (explicit.isNotEmpty()) explicit else BARE_AMOUNT.findAll(text).map { it.value }.toList()
    }

    private fun normalizedValue(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("(?:₹|rs\\.?|inr)"), "")
        .replace(Regex("[^0-9.]"), "")
        .trimStart('0')
        .ifBlank { "0" }

    private fun normalizeLine(value: String): String = value.replace(Regex("[\\t ]+"), " ").trim()

    private fun subjectTerms(query: String): Set<String> =
        WORD.findAll(query.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 4 && it !in QUERY_STOP_WORDS }
            .toSet()

    private fun containsWord(text: String, term: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    private fun windowWords(value: String): Sequence<String> = WORD.findAll(value.lowercase(Locale.ROOT)).map { it.value }

    // A small tolerance makes an OCR/name typo such as Odyssy/Odyssey useful
    // without treating unrelated short words as a match.
    private fun similar(a: String, b: String): Boolean {
        if (a.length < 5 || b.length < 5) return false
        val matrix = IntArray(b.length + 1) { it }
        a.forEachIndexed { row, char ->
            var diagonal = matrix[0]
            matrix[0] = row + 1
            b.forEachIndexed { column, other ->
                val above = matrix[column + 1]
                matrix[column + 1] = minOf(
                    matrix[column + 1] + 1,
                    matrix[column] + 1,
                    diagonal + if (char == other) 0 else 1,
                )
                diagonal = above
            }
        }
        return matrix.last() <= 2
    }

    private const val NEIGHBOUR_LINES = 2
    private const val MIN_DIRECT_SCORE = 70
    private const val MIN_DIRECT_MARGIN = 8
    private const val COMPONENT_PENALTY = 42
    private const val MAX_LABEL_CHARS = 96
    private const val MAX_PROMPT_CANDIDATES = 4
    private val WORD = Regex("[\\p{L}\\p{N}]{2,}")
    private val EXPLICIT_MONEY = Regex("(?i)(?:₹|rs\\.?|inr)\\s*[:=.-]?\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
    private val BARE_AMOUNT = Regex("(?<![\\p{L}\\p{N}])[0-9][0-9,]*(?:\\.[0-9]{1,2})?(?![\\p{L}\\p{N}])")
    private val CURRENCY_MARKER = Regex("(?i)(?:₹|\\brs\\.?|\\binr\\b)")
    private val GRAND_TOTAL = Regex("(?i)\\bgrand\\s*total\\b")
    private val TOTAL_AMOUNT = Regex("(?i)\\btotal\\s*amount\\b")
    private val AMOUNT_PAID = Regex("(?i)\\b(?:amount\\s*)?paid\\b|\\bpayment\\s*total\\b")
    private val TOTAL = Regex("(?i)\\btotal\\b")
    private val TICKET_PRICE = Regex("(?i)\\b(?:ticket\\s*)?price\\b")
    private val PRICE = Regex("(?i)\\bprice\\b")
    private val AMOUNT = Regex("(?i)\\bamount\\b")
    private val PAID = Regex("(?i)\\bpaid\\b")
    private val FARE = Regex("(?i)\\bfare\\b")
    private val COMPONENT_CHARGE = Regex("(?i)\\b(?:cgst|sgst|igst|gst|tax|fee|convenience|service charge)\\b")
    private val AMOUNT_INTENT = Regex("\\b(how much|spend|spent|cost|price|amount|total|paid)\\b")
    private val QUERY_STOP_WORDS = setOf(
        "what", "which", "when", "where", "whose", "with", "from", "this", "that", "have", "does",
        "please", "show", "find", "give", "tell", "about", "much", "spend", "spent", "cost", "price",
        "amount", "total", "paid", "movie", "ticket",
    )
}
