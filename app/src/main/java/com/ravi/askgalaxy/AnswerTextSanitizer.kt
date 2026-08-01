package com.ravi.askgalaxy

import java.text.BreakIterator
import java.util.Locale

/** Keeps internal grounding language out of the user-facing answer card. */
internal object AnswerTextSanitizer {
    fun clean(value: String): String {
        var cleaned = stripPrivateModelArtifacts(value)
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
            // A direct field answer is sufficient. Drop a separate generic
            // provenance sentence rather than surfacing an explanation such
            // as "This information was found in a document...".
            .replace(
                Regex(
                    "(?i)\\s*(?:this|that) (?:information|answer|detail) " +
                        "(?:was|is) (?:found|shown|taken) (?:in|from) " +
                        "(?:a|the) (?:document|photo|image|record)[^.?!]*[.?!]?",
                ),
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
        if (cleaned.isBlank()) return ""
        cleaned = cleaned.replaceFirstChar { it.uppercaseChar() }
        return firstSentences(cleaned, MAX_SENTENCES)
    }

    /**
     * Reasoning and prompt-echo text are private model protocol, not gallery
     * content. Prefer an explicit final-answer section when one is present,
     * then remove complete or dangling reasoning blocks and task wrappers.
     */
    private fun stripPrivateModelArtifacts(value: String): String {
        var cleaned = value.trim()
        cleaned = cleaned.replace(COMPLETE_THINK_BLOCK, " ")
        val danglingThinkEnd = cleaned.lastIndexOf("</think>", ignoreCase = true)
        if (danglingThinkEnd >= 0) {
            cleaned = cleaned.substring(danglingThinkEnd + "</think>".length)
        }
        cleaned = cleaned.replace(THINK_TAG, " ").trim()

        val answerMarkers = ANSWER_MARKER.findAll(cleaned).toList()
        if (answerMarkers.isNotEmpty()) {
            cleaned = cleaned.substring(answerMarkers.last().range.last + 1)
        }
        return cleaned
            .replace(Regex("(?im)^\\s*(?:ANSWER_TASK|EVIDENCE|CONTEXT|EVIDENCE_BUILDER)\\s*:\\s*$"), " ")
            .replace(Regex("(?im)^\\s*(?:final answer|response)\\s*:\\s*"), "")
            .replace(Regex("(?m)^\\s*```(?:text|markdown)?\\s*$"), " ")
            .trim()
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

    private val COMPLETE_THINK_BLOCK = Regex("(?is)<think\\b[^>]*>.*?</think>")
    private val THINK_TAG = Regex("(?is)</?think\\b[^>]*>")
    private val ANSWER_MARKER = Regex("(?im)^\\s*ANSWER\\s*:\\s*")
    private const val MAX_SENTENCES = 3
}
