package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.time.LocalDate

/**
 * Gemma 4 E4B query planner. Planning is synchronous with respect to gallery
 * retrieval, but runs on GalleryIndexer's background executor. The planner
 * session is only needed for the planning turn and is released before
 * retrieval/answer context assembly. Answer generation intentionally starts a
 * clean conversation so routing syntax cannot leak into the user-facing answer.
 */
object QueryPlannerRuntime {
    private const val TAG = "AskGalaxyPlanner"
    private const val MAX_SEMANTIC_QUERIES = 3
    private const val MAX_METADATA_QUERIES = 3
    private const val MAX_OCR_TERMS = 8
    private const val MAX_NEGATIVE_QUERIES = 3
    private const val MAX_ASSERTIONS = 3
    private const val MAX_TEXT_CHARS = 120
    private const val MAX_QUERY_CHARS = 256
    private const val MAX_OUTPUT_CHARS = 800

    data class PlannedQuery(
        val plan: QueryPlan,
        val session: GemmaRuntime.ConversationSession?,
        val plannerJson: String = "",
    )

    fun isModelInstalled(context: Context): Boolean =
        GemmaRuntime.isModelInstalled(context.applicationContext)

    fun preloadAsync(context: Context) {
        GemmaRuntime.preloadPlannerAsync(context, plannerSystemInstruction())
    }

    /** Releases the shared Gemma engine; use only when the Activity is ending. */
    fun releaseResident() = GemmaRuntime.releaseResident()

    /** Compatibility wrapper for callers that do not need to continue the session. */
    fun plan(context: Context, query: String): QueryPlan {
        val planned = planWithSession(context, query)
        planned.session?.close()
        return planned.plan
    }

    /** Plans a clear set expression locally; ambiguous queries still use Gemma 4. */
    fun planWithSession(
        context: Context,
        query: String,
        knownPersonLabels: List<String> = emptyList(),
    ): PlannedQuery {
        val fallback = QueryPlan.fallback(query)
        val appContext = context.applicationContext
        val likelyMisspelledPerson = knownPersonLabels.any {
            QuerySpellingMatcher.isPlausibleCorrection(query, it) &&
                !query.contains(it, ignoreCase = true)
        }
        if (canCompileFast(fallback, query) && !likelyMisspelledPerson) {
            val session = GemmaRuntime.takePrefilledPlannerSession()
            Log.i(
                TAG,
                "Deterministic set plan accepted: ops=${fallback.operationSummary().take(360)}",
            )
            return PlannedQuery(fallback, session, canonicalExecutionSpec(fallback))
        }
        if (!isModelInstalled(appContext)) {
            return PlannedQuery(fallback, null, canonicalExecutionSpec(fallback))
        }

        var session: GemmaRuntime.ConversationSession? = null
        return runCatching {
            session = GemmaRuntime.takePrefilledPlannerSession()
                ?: GemmaRuntime.shared(appContext).createPlannerConversation(plannerSystemInstruction())
            val raw = session!!.generate(plannerUserPrompt(query, knownPersonLabels))
                .trim()
                .take(MAX_OUTPUT_CHARS)
            val parsed = parseExecutionSpec(raw, fallback.answerEvidenceScope)
            if (parsed == null) {
                Log.w(TAG, "Gemma 4 planner returned no execution spec; using deterministic guard plan")
                PlannedQuery(fallback, session, canonicalExecutionSpec(fallback))
            } else {
                val merged = mergeWithFallback(parsed, fallback)
                Log.i(
                    TAG,
                    "Gemma 4 plan accepted: semantic=${merged.semanticQueries.size}, " +
                        "metadata=${merged.metadataQueries.size}, people=${merged.personNames.size}, " +
                        "excludedPeople=${merged.excludedPersonNames.size}, " +
                        "ocr=${merged.ocrTerms.size}, excludedOcr=${merged.excludedOcrTerms.size}, " +
                        "negative=${merged.negativeSemanticQueries.size}, assertions=${merged.assertions.size}, " +
                        "time=${merged.timeHint.isNotBlank()}, location=${merged.locationHint.isNotBlank()}, " +
                        "recent=${merged.recentFirst}, evidence=${merged.answerEvidenceScope.label()}, " +
                        "metadataFields=${merged.answerEvidenceScope.metadataFields.joinToString(",") { it.name.lowercase() }}, " +
                        "ops=${merged.operationSummary().take(360)}",
                )
                PlannedQuery(merged, session, plannerSpecForDisplay(raw, merged))
            }
        }.onFailure { error ->
            session?.close()
            Log.w(TAG, "Gemma 4 query planning failed; using deterministic guard plan", error)
        }.getOrElse {
            PlannedQuery(fallback, null, canonicalExecutionSpec(fallback))
        }
    }

    private fun canonicalExecutionSpec(plan: QueryPlan): String =
        plan.executionSpecString()

    /** Canonical C-like spec for the post-resolution plan enforced by retrieval. */
    fun effectivePlanJson(plan: QueryPlan): String = canonicalExecutionSpec(plan)

    /** Stable preface placed in the conversation KV cache before a query arrives. */
    fun plannerSystemInstruction(): String = """
        You are Ask Galaxy's private on-device query compiler. For PLANNER_TASK return ONLY one C-like execution expression, no markdown or prose. Predicates must be bracketed and may use only these fields: person, mime type, from_date, to_date, location, semantic. Use == inside predicates. Operators follow C precedence: postfix SORT_DATE/SORT_LOC, then + and -, then &&, then comma. Use explicit outer brackets whenever needed to preserve intent. Comma is alternative union, + is fused positive retrieval, && is hard intersection, - is subtraction. Before compiling, silently correct obvious spelling mistakes in generic concepts, MIME words, date expressions, and recognizable place names; the returned expression must contain the corrected text. For a misspelled person name, choose only a close match from Known people in PLANNER_TASK and copy that label exactly. Otherwise preserve an unfamiliar proper name instead of guessing. Use ISO yyyy-MM-dd for from_date/to_date and calculate relative dates from the Today value in PLANNER_TASK. Preserve dates and negation; never invent values. "without X" subtracts [person == X] when X is a person, otherwise [semantic == X]. A photo word means [mime type == photos]. Documents, receipts, bills, scans, and visible written text remain semantic predicates intersected with [mime type == photos], because OCR and indexed metadata execute through the semantic field. Examples: Ravi without glasses => [[person == Ravi] && [mime type == photos]] - [semantic == glasses]. Meghana phots frm last tem outng, with Known people Meghana => [person == Meghana] && [semantic == team outing] SORT_DATE. last Goaa trip photos without Ramani => [[[location == Goa] && [mime type == photos]] - [person == Ramani]] SORT_DATE. electricity bills from June 2024 => [[from_date == 2024-06-01] && [to_date == 2024-06-30]] && [semantic == electricity bill]. For ANSWER_TASK ignore this execution grammar and answer only from the supplied gallery context. Never repeat the execution expression as the answer.
    """.trimIndent()

    private fun plannerUserPrompt(query: String, knownPersonLabels: List<String>): String {
        val people = knownPersonLabels.asSequence()
            .map { it.replace(Regex("[\\r\\n|]+"), " ").trim().take(48) }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(64)
            .joinToString(" | ")
            .ifBlank { "none" }
        return "PLANNER_TASK: Today=${LocalDate.now()}. Known people: $people. " +
            "Silently correct obvious spelling, then compile this query. Return only the execution expression described above. " +
            "Query: ${query.trim().take(MAX_QUERY_CHARS)}"
    }

    private fun parseExecutionSpec(
        raw: String,
        answerScope: AnswerEvidenceScope,
    ): QueryPlan? {
        val candidate = raw
            .replace("```text", "", ignoreCase = true)
            .replace("```", "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .removePrefix("EXECUTION_SPEC:")
            .trim()
        return runCatching {
            ExecutionSpecCompiler.compile(QueryExecutionSpec.parse(candidate), answerScope)
        }.getOrNull()
    }

    private fun plannerSpecForDisplay(raw: String, plan: QueryPlan): String {
        val parsed = parseExecutionSpec(raw, plan.answerEvidenceScope)
        return parsed?.executionSpecString()
            ?: canonicalExecutionSpec(plan)
    }

    private fun mergeWithFallback(model: QueryPlan, fallback: QueryPlan): QueryPlan {
        // The deterministic classifier is the safety boundary. Gemma still
        // supplies better semantic wording, but it cannot widen the set with
        // an accidental OCR/person/context branch.
        val fallbackScope = fallback.answerEvidenceScope
        val hasNegation = fallback.negativeSemanticQueries.isNotEmpty() ||
            fallback.excludedOcrTerms.isNotEmpty()
        val finalEvidence = fallbackScope.copy(
            explicit = fallbackScope.explicit || model.answerEvidenceScope.explicit,
        )
        val correctedEventPhrases = model.semanticQueries.filter {
            QuerySpellingMatcher.looksLikeEventPhrase(it)
        }
        val correctedLocation = when {
            model.locationHint.isNotBlank() &&
                correctedEventPhrases.any {
                    QuerySpellingMatcher.areClosePhrases(model.locationHint, it)
                } -> ""
            model.locationHint.isNotBlank() -> model.locationHint
            fallback.locationHint.isNotBlank() &&
                correctedEventPhrases.any {
                    QuerySpellingMatcher.areClosePhrases(fallback.locationHint, it)
                } -> ""
            else -> fallback.locationHint
        }
        val semantic = (model.semanticQueries + fallback.semanticQueries)
            .map {
                stripPlannerScopeWords(
                    it,
                    model.personNames + model.excludedPersonNames,
                    correctedLocation,
                )
            }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(MAX_SEMANTIC_QUERIES)
        val metadata = if (fallbackScope.needsOcr || fallbackScope.needsMetadata) {
            (model.metadataQueries + fallback.metadataQueries)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .take(MAX_METADATA_QUERIES)
        } else {
            emptyList()
        }
        val ocr = if (fallbackScope.needsOcr) {
            (model.ocrTerms + fallback.ocrTerms)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .take(MAX_OCR_TERMS)
        } else {
            emptyList()
        }
        val excludedOcr = if (fallbackScope.needsOcr) {
            (model.excludedOcrTerms + fallback.excludedOcrTerms)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .take(MAX_OCR_TERMS)
        } else {
            emptyList()
        }
        val negative = if (hasNegation) {
            (model.negativeSemanticQueries + fallback.negativeSemanticQueries)
                .map(::normalizeNegativeVisualQuery)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .take(MAX_NEGATIVE_QUERIES)
        } else {
            emptyList()
        }
        val assertions = if (fallbackScope.needsVisual && hasNegation) {
            (model.assertions + fallback.assertions)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .take(MAX_ASSERTIONS)
        } else {
            emptyList()
        }
        val merged = QueryPlan(
            semanticQueries = semantic,
            metadataQueries = metadata,
            personNames = model.personNames,
            excludedPersonNames = model.excludedPersonNames,
            ocrTerms = ocr,
            excludedOcrTerms = excludedOcr,
            negativeSemanticQueries = negative,
            assertions = assertions,
            timeHint = fallback.timeHint.ifBlank { model.timeHint },
            fromDate = fallback.fromDate.ifBlank { model.fromDate },
            toDate = fallback.toDate.ifBlank { model.toDate },
            locationHint = correctedLocation,
            recentFirst = if (fallback.timeHint.isNotBlank()) false else fallback.recentFirst || model.recentFirst,
            sortByLocation = fallback.sortByLocation || model.sortByLocation,
            needsPersonalContext = fallback.needsPersonalContext,
            mediaType = fallback.mediaType ?: model.mediaType,
            answerEvidenceScope = finalEvidence.copy(
                metadataFields = if (finalEvidence.needsMetadata) fallbackScope.metadataFields else emptySet(),
            ),
            executionSpec = null,
        )
        return merged.copy(
            executionSpec = model.executionSpec?.let {
                mergeSafetyPredicates(it, fallback, correctedLocation)
            },
        )
    }

    /**
     * Retains the LLM's nested union/additive structure while appending the
     * deterministic hard-scope safety predicates. Duplicate hard predicates
     * are harmless set intersections and preferable to widening the query.
     */
    private fun mergeSafetyPredicates(
        modelSpec: QueryExecutionSpec,
        fallback: QueryPlan,
        correctedLocation: String,
    ): QueryExecutionSpec {
        fun normalizeLocations(node: ExecutionNode): ExecutionNode? = when (node) {
            is ExecutionNode.Predicate -> if (node.field == ExecutionField.LOCATION) {
                correctedLocation.takeIf(String::isNotBlank)?.let {
                    ExecutionNode.Predicate(ExecutionField.LOCATION, it)
                }
            } else {
                node
            }
            is ExecutionNode.Sorted -> normalizeLocations(node.value)?.let {
                ExecutionNode.Sorted(it, node.sort)
            }
            is ExecutionNode.Binary -> {
                val left = normalizeLocations(node.left)
                val right = normalizeLocations(node.right)
                when {
                    left != null && right != null ->
                        ExecutionNode.Binary(left, node.operator, right)
                    left != null -> left
                    right != null && node.operator != ExecutionBinaryOperator.SUBTRACT -> right
                    else -> null
                }
            }
        }
        var root = normalizeLocations(modelSpec.root) ?: modelSpec.root
        val hardPredicates = buildList<ExecutionNode.Predicate> {
            fallback.mediaType?.let {
                add(ExecutionNode.Predicate(ExecutionField.MIME_TYPE, it.label()))
            }
            fallback.fromDate.takeIf(String::isNotBlank)?.let {
                add(ExecutionNode.Predicate(ExecutionField.FROM_DATE, it))
            }
            fallback.toDate.takeIf(String::isNotBlank)?.let {
                add(ExecutionNode.Predicate(ExecutionField.TO_DATE, it))
            }
            correctedLocation.takeIf(String::isNotBlank)?.let {
                add(ExecutionNode.Predicate(ExecutionField.LOCATION, it))
            }
        }
        fun containsPositivePredicate(
            node: ExecutionNode,
            predicate: ExecutionNode.Predicate,
            subtracted: Boolean = false,
        ): Boolean = when (node) {
            is ExecutionNode.Predicate ->
                !subtracted &&
                    node.field == predicate.field &&
                    node.value.equals(predicate.value, ignoreCase = true)
            is ExecutionNode.Sorted ->
                containsPositivePredicate(node.value, predicate, subtracted)
            is ExecutionNode.Binary ->
                containsPositivePredicate(node.left, predicate, subtracted) ||
                    containsPositivePredicate(
                        node.right,
                        predicate,
                        subtracted || node.operator == ExecutionBinaryOperator.SUBTRACT,
                    )
        }
        hardPredicates.filterNot { containsPositivePredicate(root, it) }.forEach { predicate ->
            root = ExecutionNode.Binary(root, ExecutionBinaryOperator.INTERSECT, predicate)
        }
        fallback.negativeSemanticQueries.forEach { value ->
            root = ExecutionNode.Binary(
                root,
                ExecutionBinaryOperator.SUBTRACT,
                ExecutionNode.Predicate(ExecutionField.SEMANTIC, value),
            )
        }
        if (fallback.recentFirst) root = ExecutionNode.Sorted(root, ExecutionSort.DATE)
        if (fallback.sortByLocation) root = ExecutionNode.Sorted(root, ExecutionSort.LOCATION)
        return QueryExecutionSpec(root)
    }

    private fun normalizeNegativeVisualQuery(value: String): String {
        return value.trim().take(MAX_TEXT_CHARS)
    }

    private fun stripPlannerScopeWords(
        value: String,
        people: List<String>,
        location: String,
    ): String {
        var cleaned = value.trim()
        people.filter(String::isNotBlank).forEach { person ->
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(person)}\\b"), " ")
        }
        if (location.isNotBlank()) {
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(location)}\\b"), " ")
        }
        cleaned = cleaned
            .replace(
                Regex("(?i)\\b(photo|photos|picture|pictures|image|images|still|stills|video|videos|movie|movies|clip|clips)\\b"),
                " ",
            )
            .replace(Regex("(?i)\\b(last|latest|newest|recent|most)\\b"), " ")
        val normalized = cleaned.replace(Regex("\\s+"), " ").trim()
        return normalized.ifBlank { value.trim() }
    }

    /**
     * Explicit field queries do not need a 4B generative round-trip. Their
     * operations are deterministic and safer; Gemma remains the planner for
     * genuinely ambiguous/relational language.
     */
    private fun canCompileFast(plan: QueryPlan, query: String): Boolean {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
        val countEpisodeQuery = Regex("\\bhow\\s+many\\b").containsMatchIn(normalized) &&
            Regex("\\b(trek|treks|trip|trips|journey|journeys|visit|visits|vacation|vacations|" +
                "holiday|holidays|birthday|birthdays|wedding|weddings|event|events)\\b")
                .containsMatchIn(normalized) &&
            (plan.timeHint.isNotBlank() || plan.locationHint.isNotBlank())
        if (countEpisodeQuery) return true
        val explicitMediaType = Regex(
            "\\b(photo|photos|picture|pictures|image|images|still|stills|video|videos|" +
                "movie|movies|clip|clips|document|documents|scan|scans)\\b",
        ).containsMatchIn(normalized)
        val hasStructuredSignal = explicitMediaType ||
            plan.locationHint.isNotBlank() ||
            plan.timeHint.isNotBlank() ||
            plan.recentFirst ||
            plan.negativeSemanticQueries.isNotEmpty() ||
            plan.excludedOcrTerms.isNotEmpty() ||
            plan.answerEvidenceScope.needsOcr ||
            plan.answerEvidenceScope.needsMetadata
        if (!hasStructuredSignal || normalized.length > 180) return false
        return !Regex(
            "\\b(why|how|compare|similar|same|best|which|explain|summari[sz]e|those|them|my|me|it)\\b",
        ).containsMatchIn(normalized)
    }

}

/**
 * Safety gate for accepting an LLM-corrected person value. The corrected name
 * must already exist in the private face-label vocabulary and every name token
 * must be within a small edit distance of a query token.
 */
internal object QuerySpellingMatcher {
    fun isPlausibleCorrection(query: String, candidate: String): Boolean {
        val queryTokens = tokens(query)
        val candidateTokens = tokens(candidate)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false
        return candidateTokens.all { expected ->
            queryTokens.any { actual ->
                actual == expected ||
                    damerauLevenshtein(actual, expected) <= allowedDistance(expected.length)
            }
        }
    }

    fun areClosePhrases(original: String, corrected: String): Boolean {
        val originalTokens = tokens(original)
        val correctedTokens = tokens(corrected)
        if (originalTokens.isEmpty() || correctedTokens.isEmpty()) return false
        if (kotlin.math.abs(originalTokens.size - correctedTokens.size) > 1) return false
        return correctedTokens.all { expected ->
            originalTokens.any { actual ->
                actual == expected ||
                    damerauLevenshtein(actual, expected) <= allowedDistance(expected.length)
            }
        }
    }

    fun looksLikeEventPhrase(value: String): Boolean =
        tokens(value).any { it in EVENT_WORDS }

    private fun tokens(value: String): List<String> = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)

    private fun allowedDistance(length: Int): Int = when {
        length <= 3 -> 0
        length <= 7 -> 1
        else -> 2
    }

    private fun damerauLevenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val rows = left.length + 1
        val columns = right.length + 1
        val distance = Array(rows) { IntArray(columns) }
        for (row in 0 until rows) distance[row][0] = row
        for (column in 0 until columns) distance[0][column] = column
        for (row in 1 until rows) {
            for (column in 1 until columns) {
                val substitution = if (left[row - 1] == right[column - 1]) 0 else 1
                var best = minOf(
                    distance[row - 1][column] + 1,
                    distance[row][column - 1] + 1,
                    distance[row - 1][column - 1] + substitution,
                )
                if (
                    row > 1 &&
                    column > 1 &&
                    left[row - 1] == right[column - 2] &&
                    left[row - 2] == right[column - 1]
                ) {
                    best = minOf(best, distance[row - 2][column - 2] + 1)
                }
                distance[row][column] = best
            }
        }
        return distance[left.length][right.length]
    }

    private val EVENT_WORDS = setOf(
        "birthday", "concert", "conference", "event", "festival", "holiday",
        "outing", "party", "trip", "vacation", "wedding",
    )
}
