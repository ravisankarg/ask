package com.ravi.askgalaxy

/**
 * Natural-language routing for phone-history questions. This is deliberately
 * independent of the visual planner: call records are a private document
 * source, and words such as "spoke" or "last time" must not become gallery
 * scene predicates.
 */
internal object CallLogQueryPolicy {
    data class Intent(
        val source: DocumentSource,
        val asksLatest: Boolean,
        val fallbackPerson: String?,
        val timeHint: String = "",
        val candidateTokens: List<String> = emptyList(),
        val resolvedPerson: String? = null,
    ) {
        val personForSearch: String?
            get() = resolvedPerson ?: fallbackPerson
    }

    private val callAction = Regex(
        "(?i)\\b(call|called|calls|calling|spoke|speak|speaking|talk|talked|talking|" +
            "phone|phoned|rang|ring|contacted|conversation|conversations)\\b",
    )
    private val timeQuestion = Regex(
        "(?i)\\b(when|qhen|last|latest|recent|time|date|day|yesterday|today)\\b",
    )
    private val explicitCallSource = Regex("(?i)\\bcall(?:s|\\s+log(?:s)?)\\b")
    private val messageAction = Regex(
        "(?i)\\b(text|texted|texts|texting|message|messaged|messages|messaging|sms|" +
            "wrote|write|written|sent|send)\\b",
    )
    private val stopWords = setOf(
        "a", "an", "the", "i", "me", "my", "we", "did", "do", "does", "to", "with",
        "who", "whom", "when", "qhen", "what", "which", "was", "were", "have", "has",
        "last", "latest", "recent", "time", "date", "day", "today", "yesterday", "ago",
        "call", "called", "calls", "calling", "spoke", "speak", "speaking", "talk", "talked",
        "talking", "phone", "phoned", "rang", "ring", "contacted", "conversation", "conversations",
        "calllog", "logs", "log", "history", "please", "tell", "show", "find", "didnt",
        "text", "texted", "texts", "texting", "message", "messaged", "messages", "messaging",
        "sms", "wrote", "write", "written", "sent", "send", "about", "week", "weeks",
    )

    fun detect(query: String): Intent? {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        val hasCallLanguage = callAction.containsMatchIn(normalized) ||
            explicitCallSource.containsMatchIn(normalized)
        if (!hasCallLanguage) return null
        val tokens = Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(normalized)
            .map { it.value }
            .filterNot { it in stopWords }
            .filterNot { it.all(Char::isDigit) }
            .toList()
        return Intent(
            source = DocumentSource.CALL_LOGS,
            asksLatest = timeQuestion.containsMatchIn(normalized),
            fallbackPerson = tokens.lastOrNull(),
            timeHint = temporalScope(normalized),
            candidateTokens = tokens,
        )
    }

    fun detectMessage(query: String): Intent? {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        if (!messageAction.containsMatchIn(normalized)) return null
        val tokens = Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(normalized)
            .map { it.value }
            .filterNot { it in stopWords }
            .filterNot { it.all(Char::isDigit) }
            .toList()
        return Intent(
            source = DocumentSource.MESSAGES,
            asksLatest = timeQuestion.containsMatchIn(normalized) ||
                Regex("(?i)\\b(last|latest|recent)\\s+message\\b").containsMatchIn(normalized),
            fallbackPerson = tokens.lastOrNull(),
            timeHint = temporalScope(normalized),
            candidateTokens = tokens,
        )
    }

    private fun temporalScope(normalized: String): String = Regex(
        "(?i)\\b(?:last|this|previous)\\s+(?:week|month|year|day)\\b|\\b(?:today|yesterday|tomorrow)\\b",
    ).find(normalized)?.value.orEmpty()

    /** Correct a small typo class before the planner sees the call question. */
    fun canonicalPlannerQuery(query: String): String = query
        .replace(Regex("(?i)\\bqhen\\b"), "when")
        .replace(Regex("(?i)\\balst\\b"), "last")
        .replace(Regex("(?i)\\btiem\\b"), "time")

    /** Resolve a misspelled query name against names actually present in call logs. */
    fun resolve(intent: Intent, indexedNames: List<String>): Intent {
        val queryTokens = intent.candidateTokens.ifEmpty {
            Regex("[\\p{L}\\p{N}]{3,}")
                .findAll(listOfNotNull(intent.fallbackPerson).joinToString(" ").lowercase())
                .map { it.value }
                .toList()
        }
        val resolved = indexedNames.asSequence()
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .mapNotNull { name ->
                val parts = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 3 }
                if (parts.isEmpty()) return@mapNotNull null
                val matched = parts.count { part ->
                    queryTokens.any { token ->
                        token == part || QuerySpellingMatcher.areClosePhrases(token, part)
                    }
                }
                if (matched == parts.size) name to matched else null
            }
            .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            ?.first
        return intent.copy(resolvedPerson = resolved)
    }
}
