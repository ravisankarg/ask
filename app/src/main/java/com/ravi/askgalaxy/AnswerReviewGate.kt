package com.ravi.askgalaxy

import java.util.Locale

/** Decides when the extra visual/OCR answer-review pass earns its latency. */
object AnswerReviewGate {
    enum class Reason(val logLabel: String) {
        DIRECT_FIELD("direct-field"),
        CONFLICTING_OCR("conflicting-ocr"),
    }

    fun reason(
        query: String,
        draft: String,
        records: List<GalleryMedia>,
    ): Reason? {
        if (isDirectFieldQuestion(query)) {
            return Reason.DIRECT_FIELD
        }
        val draftValues = valueTokens(draft)
        if (draftValues.isEmpty()) return null
        val documentValues = records.asSequence()
            .flatMap { valueTokens(it.ocrText).asSequence() }
            .map(::normalise)
            .filter(String::isNotBlank)
            .toSet()
        return if (documentValues.size >= 2 && draftValues.any { normalise(it) in documentValues }) {
            Reason.CONFLICTING_OCR
        } else {
            null
        }
    }

    fun isDirectFieldQuestion(query: String): Boolean =
        DIRECT_FIELD_INTENT.containsMatchIn(query.lowercase(Locale.ROOT))

    private fun valueTokens(value: String): List<String> = VALUE_TOKEN.findAll(value)
        .map { it.value }
        .toList()

    private fun normalise(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")

    // Covers direct values users expect us to read, not every generic noun in
    // a document. Regular scene/location questions consequently remain one
    // generation unless their draft chooses between conflicting OCR values.
    private val DIRECT_FIELD_INTENT = Regex(
        "\\b(how much|spent|spend|paid|payment|cost|price|amount|total|" +
            "passport|licen[cs]e|identity|\\bid\\b|number|\\bno\\.?|code|reference|" +
            "account|expiry|expire|expiration|valid until|date of birth|birthdate|" +
            "invoice|receipt|bill|ticket|policy|certificate|booking)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val VALUE_TOKEN = Regex(
        "(?i)(?:₹|rs\\.?|inr)?\\s*[A-Z]{0,3}\\d{3,}[A-Z0-9-]*|" +
            "\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b",
    )
}
