package com.ravi.askgalaxy

import java.util.Locale

/**
 * Packs persisted document OCR into two complementary, query-driven views:
 * a small raw-line proof for the current answer and a compact document map for
 * natural follow-ups. This is runtime-only; the OCR index remains untouched.
 */
data class PackedOcrAnswerContext(
    val proofLines: String,
    val documentMap: String,
    val sourceChars: Int,
)

object OcrAnswerContextPacker {
    fun pack(query: String, ocrText: String): PackedOcrAnswerContext {
        val lines = ocrText.lineSequence()
            .map(::normalizeLine)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return PackedOcrAnswerContext("none", "none", 0)

        val queryTerms = queryTerms(query)
        val anchorIndexes = lines.indices
            .map { index -> index to relevance(lines[index], queryTerms) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .take(MAX_ANCHORS)
            .map { it.first }

        val selectedIndexes = linkedSetOf<Int>()
        if (anchorIndexes.isEmpty()) {
            lines.indices.take(MAX_PROOF_LINES).forEach(selectedIndexes::add)
        } else {
            anchorIndexes.forEach { anchor ->
                ((anchor - NEIGHBOR_LINES).coerceAtLeast(0)..
                    (anchor + NEIGHBOR_LINES).coerceAtMost(lines.lastIndex))
                    .forEach(selectedIndexes::add)
            }
        }
        val proof = selectedIndexes
            .asSequence()
            .sorted()
            .map { index -> "L${index + 1}: ${lines[index]}" }
            .take(MAX_PROOF_LINES)
            .joinToString("\n")
            .take(MAX_PROOF_CHARS)
            .ifBlank { "none" }

        val map = lines.asSequence()
            .filter(::looksLikeHeading)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_MAP_HEADINGS)
            .joinToString(" • ")
            .take(MAX_MAP_CHARS)
            .ifBlank { "none" }
        return PackedOcrAnswerContext(proof, map, ocrText.length)
    }

    private fun normalizeLine(value: String): String =
        value.replace(Regex("[\\t ]+"), " ").trim()

    private fun queryTerms(query: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(query.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun relevance(line: String, queryTerms: Set<String>): Int {
        if (queryTerms.isEmpty()) return 0
        // OCR frequently renders a field label as "No." while the user asks
        // for its "number". Treat these conventional abbreviations as the
        // same anchor before spending the small proof-line budget; otherwise
        // a nearby name can crowd the actual label/value pair out entirely.
        val normalized = normalizeFieldAliases(line.lowercase(Locale.ROOT))
        return queryTerms.sumOf { term ->
            if (Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").containsMatchIn(normalized)) 3
            else if (normalized.contains(term)) 1
            else 0
        }
    }

    private fun normalizeFieldAliases(value: String): String = value
        .replace(Regex("(?<![\\p{L}\\p{N}])no\\.?(?![\\p{L}\\p{N}])"), "number")
        .replace(Regex("(?<![\\p{L}\\p{N}])num\\.?(?![\\p{L}\\p{N}])"), "number")

    /** Headings give follow-ups breadth, but never serve as answer evidence. */
    private fun looksLikeHeading(line: String): Boolean {
        if (line.length !in 3..72 || line.count(Char::isLetter) < 3) return false
        val digits = line.count(Char::isDigit)
        return digits <= 2 && (line.endsWith(":") || line.count(Char::isLetter) * 2 >= line.length)
    }

    private const val MAX_ANCHORS = 2
    private const val NEIGHBOR_LINES = 2
    private const val MAX_PROOF_LINES = 8
    private const val MAX_PROOF_CHARS = 460
    private const val MAX_MAP_HEADINGS = 6
    private const val MAX_MAP_CHARS = 220
    private val STOP_WORDS = setOf(
        "what", "which", "when", "where", "with", "from", "this", "that", "have", "does",
        "please", "show", "find", "give", "tell", "about", "photo", "image", "document",
    )
}
