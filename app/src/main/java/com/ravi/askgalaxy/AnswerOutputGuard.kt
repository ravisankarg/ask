package com.ravi.askgalaxy

/** Rejects planner/follow-up protocol that must never reach an answer bubble. */
internal object AnswerOutputGuard {
    fun needsRetry(output: String, query: String): Boolean {
        val clean = AnswerTextSanitizer.clean(output).trim()
        if (clean.isBlank()) return true
        if (
            Regex("(?im)^\\s*(?:query|question|follow_?ups?)\\s*:").containsMatchIn(clean) ||
            clean.startsWith("QUERY:", ignoreCase = true)
        ) {
            return true
        }
        val normalizedOutput = normalize(clean)
        val normalizedQuery = normalize(query)
        return normalizedOutput == normalizedQuery || clean.endsWith("?")
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
