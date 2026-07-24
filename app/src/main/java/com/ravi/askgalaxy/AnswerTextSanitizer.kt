package com.ravi.askgalaxy

import java.text.BreakIterator
import java.util.Locale

/** Keeps internal grounding language out of the user-facing answer card. */
internal object AnswerTextSanitizer {
    fun clean(value: String): String {
        var cleaned = value.trim()
        cleaned = cleaned
            .replace(
                Regex("(?i)^(?:as )?(?:your|the) evidence (?:is not there|is unavailable|is missing)\\s*[,;:.]?\\s*"),
                "",
            )
            .replace(
                Regex("(?i)^evidence is like this\\s*[,;:.]?\\s*"),
                "",
            )
            .replace(
                Regex("(?i)^(?:based on|according to|from) (?:the )?(?:(?:provided|available|supplied) evidence|evidence you provided|evidence provided)\\s*[,;:]?\\s*"),
                "",
            )
            .replace(
                Regex("(?i)^based on (?:the )?(?:provided|available|supplied) (?:records|images|information)\\s*[,;:]?\\s*"),
                "",
            )
            .replace(
                Regex("(?i)\\b(?:the )?(?:evidence|records|images) you provided\\b"),
                "the photos and details",
            )
            .replace(Regex("(?i)\\b(?:provided|available|supplied) evidence\\b"), "the photos and details")
            .replace(Regex("(?i)\\bthe provided evidence\\b"), "the photos and details")
            .replace(Regex("(?i)\\bthe supplied records\\b"), "the photos and details")
            .replace(Regex("(?i)\\bthe evidence does not (?:explicitly )?confirm\\b"), "the photos do not clearly confirm")
            .replace(Regex("(?i)\\bthe evidence does not (?:explicitly )?(specify|show|contain|indicate|provide|establish)\\b"), "the photos and details do not clearly establish")
            .replace(Regex("(?i)\\bthe evidence (shows|indicates|suggests)\\b"), "the photos $1")
            .replace(Regex("(?i)\\bbased on the evidence\\b"), "from the photos")
            .replace(Regex("(?i)\\baccording to the evidence\\b"), "the photos and details show")
            // Last safety net for model variants such as "visual evidence"
            // or "the evidence is insufficient": internal grounding terms
            // must never reach the user-facing answer.
            .replace(Regex("(?i)\\bevidence\\b"), "photos and details")
            .replace(
                Regex("(?i)\\b(?:(?:the|provided|available|supplied) )?(?:gallery )?records?\\b"),
                "photos and details",
            )
            .replace(Regex("(?i)\\bavailable images\\b"), "photos")
            .replace(Regex("(?i)\\bavailable information\\b"), "the photos and details")
            .replace(
                Regex("(?i)^(?:based on|according to)\\s+(?:the )?(?:photos(?: and details)?)(?: you provided)?\\s*[,;:]?\\s*"),
                "",
            )
            // G/C/E/F labels are prompt-local join keys, never user-facing
            // citations. Remove both bracketed lists and an occasional bare
            // model reference before constraining answer length.
            .replace(
                Regex("(?i)\\[(?:[GCEF]\\d+)(?:\\s*[,;&]\\s*[GCEF]\\d+)*]"),
                "",
            )
            .replace(Regex("(?i)(?<![\\p{L}\\p{N}])[GCEF]\\d+(?![\\p{L}\\p{N}])"), "")
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex("([,;:])\\s*([.!?])"), "$2")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        cleaned = cleaned.replaceFirstChar { it.uppercaseChar() }
        val summary = firstSentences(cleaned, MAX_SENTENCES)
        return if (sentenceCount(summary) == 1) {
            "$summary You can browse the matching moments below."
        } else {
            summary
        }
    }

    private fun firstSentences(value: String, limit: Int): String {
        if (value.isBlank()) return value
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(value)
        var end = iterator.first()
        repeat(limit) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return value.trim()
            end = next
        }
        return value.substring(0, end).trim()
    }

    private fun sentenceCount(value: String): Int {
        if (value.isBlank()) return 0
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(value)
        var count = 0
        var start = iterator.first()
        while (true) {
            val end = iterator.next()
            if (end == BreakIterator.DONE) break
            if (value.substring(start, end).isNotBlank()) count += 1
            start = end
        }
        return count
    }

    private const val MAX_SENTENCES = 3
}
