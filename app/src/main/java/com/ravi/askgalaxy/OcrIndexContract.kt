package com.ravi.askgalaxy

/**
 * Versioned contract for persisted OCR rows.
 *
 * Changing the engine, input preparation, or quality gate must change this
 * signature. GalleryDatabase then exposes only mismatched image rows to the
 * OCR-only worker; visual embeddings, locations, faces, and episodes remain
 * untouched.
 */
object OcrIndexContract {
    const val SIGNATURE = "mlkit-latin-16.0.1|aspect-tiles-v3|quality-gate-v1"
}

data class RecognizedOcrLine(
    val text: String,
    val confidence: Float,
    val left: Int = -1,
    val top: Int = -1,
    val right: Int = -1,
    val bottom: Int = -1,
)

data class OcrTextResult(
    val text: String,
    val lineCount: Int,
    val candidateLineCount: Int = lineCount,
    val meanConfidence: Float = 0f,
)

/**
 * Rejects isolated OCR hallucinations without throwing away useful prices,
 * dates, or punctuation once the image has enough credible text structure.
 */
object OcrQualityGate {
    private val whitespace = Regex("\\s+")
    private val alphanumericToken = Regex("[\\p{L}\\p{N}]+")

    fun select(lines: List<RecognizedOcrLine>): OcrTextResult {
        val credible = lines.mapNotNull { candidate ->
            val text = candidate.text.replace(whitespace, " ").trim()
            if (text.isBlank() || !candidate.confidence.isFinite()) return@mapNotNull null
            val alphanumericCount = text.count(Char::isLetterOrDigit)
            if (alphanumericCount == 0 || candidate.confidence < MIN_LINE_CONFIDENCE) {
                return@mapNotNull null
            }
            candidate.copy(text = text)
        }.take(MAX_LINES)
        if (credible.isEmpty()) return OcrTextResult("", 0, 0, 0f)

        val totalAlphanumeric = credible.sumOf { line ->
            line.text.count(Char::isLetterOrDigit)
        }
        val tokenCount = credible.sumOf { line ->
            alphanumericToken.findAll(line.text).count()
        }
        val meanConfidence = credible.map(RecognizedOcrLine::confidence).average().toFloat()
        val strongest = credible.maxBy(RecognizedOcrLine::confidence)
        val strongestAlphanumeric = strongest.text.count(Char::isLetterOrDigit)

        val hasDocumentStructure =
            credible.size >= MIN_STRUCTURED_LINES &&
                totalAlphanumeric >= MIN_STRUCTURED_ALPHANUMERIC &&
                tokenCount >= MIN_STRUCTURED_TOKENS &&
                meanConfidence >= MIN_STRUCTURED_MEAN_CONFIDENCE
        val hasStrongStandaloneText =
            strongestAlphanumeric >= MIN_STANDALONE_ALPHANUMERIC &&
                strongest.confidence >= MIN_STANDALONE_CONFIDENCE
        if (!hasDocumentStructure && !hasStrongStandaloneText) {
            return OcrTextResult(
                text = "",
                lineCount = 0,
                candidateLineCount = credible.size,
                meanConfidence = meanConfidence,
            )
        }

        val kept = ArrayList<String>(credible.size)
        var characterCount = 0
        credible.forEach { line ->
            if (characterCount >= MAX_TEXT_CHARACTERS) return@forEach
            val remaining = MAX_TEXT_CHARACTERS - characterCount
            val text = line.text.take(remaining)
            if (text.isNotBlank()) {
                kept += text
                characterCount += text.length + 1
            }
        }
        return OcrTextResult(
            text = kept.joinToString("\n"),
            lineCount = kept.size,
            candidateLineCount = credible.size,
            meanConfidence = meanConfidence,
        )
    }

    private const val MIN_LINE_CONFIDENCE = 0.55f
    private const val MIN_STRUCTURED_LINES = 2
    private const val MIN_STRUCTURED_ALPHANUMERIC = 6
    private const val MIN_STRUCTURED_TOKENS = 2
    private const val MIN_STRUCTURED_MEAN_CONFIDENCE = 0.60f
    private const val MIN_STANDALONE_ALPHANUMERIC = 4
    private const val MIN_STANDALONE_CONFIDENCE = 0.72f
    private const val MAX_LINES = 512
    private const val MAX_TEXT_CHARACTERS = 12_000
}
