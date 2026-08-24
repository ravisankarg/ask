package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.time.LocalDate

internal class QueryPlannerValidationException(
    val rawPlannerOutput: String,
    val validatorRejection: String,
    cause: Throwable,
) : IllegalArgumentException("QP validator rejected E2B output: $validatorRejection", cause)

internal object QueryPlannerFailureDiagnostics {
    data class Rejection(
        val rawPlannerOutput: String,
        val validatorRejection: String,
    ) {
        fun render(): String = buildString {
            appendLine("E2B QP output:")
            appendLine(rawPlannerOutput.ifBlank { "(empty output)" })
            appendLine()
            appendLine("QP validator rejected:")
            append(validatorRejection.ifBlank { "No validator reason was provided." })
        }
    }

    fun from(error: Throwable): Rejection? = generateSequence(error) { it.cause }
        .filterIsInstance<QueryPlannerValidationException>()
        .firstOrNull()
        ?.let { Rejection(it.rawPlannerOutput, it.validatorRejection) }
}

/**
 * The selected Gemma 4 model is the sole query planner. Planning runs on GalleryIndexer's
 * background executor and must produce one validated canonical execution spec.
 *
 * The planner conversation is released before retrieval. Answer generation
 * starts a clean conversation so execution grammar cannot leak into the
 * user-facing answer.
 */
object QueryPlannerRuntime {
    private const val TAG = "AskGalaxyPlanner"
    private const val MAX_QUERY_CHARS = 256
    private const val MAX_OUTPUT_CHARS = 4_096
    private const val MAX_REPAIR_ERROR_CHARS = 512
    private const val MAX_REPAIR_ATTEMPTS = 1

    data class PlannedQuery(
        val plan: QueryPlan,
        val session: GemmaRuntime.ConversationSession?,
        val plannerJson: String = "",
        /** Standalone wording after E2B resolves a contextual follow-up. */
        val resolvedQuery: String = "",
        val generationProfile: GemmaRuntime.GenerationProfile? = null,
        /** True when the first Gemma expression failed validation and the repair turn was used. */
        val repairUsed: Boolean = false,
    )

    fun isModelInstalled(context: Context): Boolean =
        GemmaRuntime.isModelInstalled(context.applicationContext)

    fun preloadAsync(context: Context) {
        GemmaRuntime.preloadPlannerAsync(context, plannerSystemInstruction(context))
    }

    /** Releases the shared Gemma engine; use only when the Activity is ending. */
    fun releaseResident() = GemmaRuntime.releaseResident()

    /** Compatibility wrapper for callers that do not need to continue the session. */
    fun plan(context: Context, query: String): QueryPlan {
        val planned = planWithSession(context, query)
        planned.session?.close()
        return planned.plan
    }

    /**
     * Every query is compiled by the selected Gemma 4 model. A malformed response gets exactly
     * one fresh-session retry with the validator rejection; there is no deterministic QP fallback.
     */
    fun planWithSession(
        context: Context,
        query: String,
        knownPersonLabels: List<String> = emptyList(),
        selfPersonLabel: String? = null,
        previousQuery: String = "",
        previousAnswer: String = "",
    ): PlannedQuery {
        val appContext = context.applicationContext
        val protocol = QueryPlannerProtocolPreferences.selected(appContext)
        val planningQuery = cleanQuery(query)
        val plannerCurrentDate = LocalDate.now()
        val previousTurn = PreviousQueryTurn(
            query = cleanQuery(previousQuery),
            answer = cleanPriorAnswer(previousAnswer),
        ).takeIf { it.query.isNotBlank() }
        check(isModelInstalled(appContext)) {
            "A selected Gemma 4 model is required for query planning"
        }
        var session: GemmaRuntime.ConversationSession? = null
        return try {
            val plannerOutputLimit = when (protocol) {
                QueryPlannerProtocol.V1 -> MAX_OUTPUT_CHARS
                QueryPlannerProtocol.V2 -> QueryPlannerV2.MAX_OUTPUT_CHARS
            }
            session = GemmaRuntime.takePrefilledPlannerSession()
                ?: GemmaRuntime.shared(appContext).createPlannerConversation(
                    plannerSystemInstruction(protocol),
                )
            val generationProfiles = ArrayList<GemmaRuntime.GenerationProfile>()
            var candidateRaw = session.generate(
                when (protocol) {
                    QueryPlannerProtocol.V1 -> plannerUserPrompt(
                        planningQuery,
                        knownPersonLabels,
                        selfPersonLabel,
                        previousTurn,
                    )
                    QueryPlannerProtocol.V2 -> QueryPlannerV2.userPrompt(
                        currentDate = plannerCurrentDate,
                        query = planningQuery,
                        knownPeople = cleanKnownPeople(knownPersonLabels),
                        selfPerson = cleanSelfPerson(selfPersonLabel),
                        previousQuery = previousTurn?.query.orEmpty(),
                    )
                },
            )
                .trim()
                .take(plannerOutputLimit)
            session.lastGenerationProfile?.let(generationProfiles::add)
            var repairUsed = false
            var repairAttempt = 0
            lateinit var parsed: QueryPlan
            var resolvedQuery = planningQuery
            var canonicalV2Json = candidateRaw
            while (true) {
                try {
                    when (protocol) {
                        QueryPlannerProtocol.V1 -> {
                            val modelOutput = ContextualPlannerOutputPolicy.parse(output = candidateRaw)
                            resolvedQuery = modelOutput.resolvedQuery
                            parsed = compileGemmaPlan(modelOutput.expression)
                        }
                        QueryPlannerProtocol.V2 -> {
                            val modelOutput = QueryPlannerV2.parseAndCompile(
                                raw = candidateRaw,
                                context = V2PlannerContext(
                                    currentQuery = planningQuery,
                                    previousQuery = previousTurn?.query.orEmpty(),
                                    knownPeople = cleanKnownPeople(knownPersonLabels),
                                    selfPerson = cleanSelfPerson(selfPersonLabel),
                                    currentDate = plannerCurrentDate,
                                ),
                            )
                            resolvedQuery = modelOutput.resolvedQuery
                            parsed = modelOutput.plan
                            canonicalV2Json = modelOutput.plannerJson
                        }
                    }
                    break
                } catch (validationError: Throwable) {
                    if (repairAttempt >= MAX_REPAIR_ATTEMPTS) {
                        Log.e(
                            TAG,
                            "Gemma 4 planner remained invalid after $MAX_REPAIR_ATTEMPTS repairs. " +
                                "output=${singleLineForLog(candidateRaw)}",
                            validationError,
                        )
                        throw QueryPlannerValidationException(
                            rawPlannerOutput = candidateRaw,
                            validatorRejection = validationError.message
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?: validationError::class.java.simpleName,
                            cause = validationError,
                        )
                    }
                    repairAttempt += 1
                    repairUsed = true
                    Log.w(
                        TAG,
                        "Gemma 4 planner returned an invalid spec; requesting repair " +
                            "$repairAttempt/$MAX_REPAIR_ATTEMPTS. " +
                            "output=${singleLineForLog(candidateRaw)}",
                        validationError,
                    )
                    // A small model often anchors on malformed output. The sole repair is a
                    // genuinely fresh E2B planner conversation that receives the concrete local
                    // validator rejection and original query, never a continuation of bad KV.
                    session?.close()
                    session = GemmaRuntime.shared(appContext).createPlannerConversation(
                        plannerSystemInstruction(protocol),
                    )
                    val repairSession = requireNotNull(session)
                    candidateRaw = repairSession.generate(
                        when (protocol) {
                            QueryPlannerProtocol.V1 -> plannerRepairPrompt(
                                query = planningQuery,
                                previousTurn = previousTurn,
                                knownPersonLabels = knownPersonLabels,
                                selfPersonLabel = selfPersonLabel,
                                invalidOutput = candidateRaw,
                                error = validationError,
                                repairAttempt = repairAttempt,
                            )
                            QueryPlannerProtocol.V2 -> QueryPlannerV2.repairPrompt(
                                currentDate = plannerCurrentDate,
                                context = V2PlannerContext(
                                    currentQuery = planningQuery,
                                    previousQuery = previousTurn?.query.orEmpty(),
                                    knownPeople = cleanKnownPeople(knownPersonLabels),
                                    selfPerson = cleanSelfPerson(selfPersonLabel),
                                    currentDate = plannerCurrentDate,
                                ),
                                validatorError = validationError.message.orEmpty()
                                    .replace(Regex("[\\r\\n]+"), " ")
                                    .take(MAX_REPAIR_ERROR_CHARS),
                                attempt = repairAttempt,
                            )
                        },
                    ).trim().take(plannerOutputLimit)
                    repairSession.lastGenerationProfile?.let(generationProfiles::add)
                }
            }
            Log.i(
                TAG,
                "Gemma-only ${protocol.name} plan accepted: category=${parsed.queryCategory.wireName}, " +
                    "semantic=${parsed.semanticQueries.size}, " +
                    "people=${parsed.personNames.size}, " +
                    "excludedPeople=${parsed.excludedPersonNames.size}, " +
                    "negative=${parsed.negativeSemanticQueries.size}, recent=${parsed.recentFirst}, " +
                    "evidence=${parsed.answerEvidenceScope.label()}, " +
                    "metadataFields=${parsed.answerEvidenceScope.metadataFields.joinToString(",") { it.name.lowercase() }}, " +
                    "ops=${parsed.operationSummary().take(360)}",
            )
            PlannedQuery(
                plan = parsed,
                session = session,
                // V2's typed IR is useful diagnostic evidence while trialling the
                // new protocol. Retrieval still receives only the independently
                // compiled QueryPlan below. V1 keeps its existing canonical view.
                plannerJson = when (protocol) {
                    QueryPlannerProtocol.V1 -> canonicalExecutionSpec(parsed)
                    QueryPlannerProtocol.V2 -> canonicalV2Json
                },
                resolvedQuery = resolvedQuery,
                generationProfile = GemmaRuntime.GenerationProfile.combine(generationProfiles),
                repairUsed = repairUsed,
            )
        } catch (error: Throwable) {
            session?.close()
            Log.e(TAG, "Gemma-only query planning failed", error)
            throw IllegalStateException("Gemma 4 could not compile this query", error)
        }
    }

    private fun canonicalExecutionSpec(plan: QueryPlan): String =
        plan.executionSpecString()

    /** Canonical C-like spec for the post-resolution plan enforced by retrieval. */
    fun effectivePlanJson(plan: QueryPlan): String = canonicalExecutionSpec(plan)

    private val V1_PLANNER_SYSTEM_INSTRUCTION = """
        You are Ask Galaxy's only query planner. The runtime does not infer, add, remove, or rewrite query intent. Therefore every PLANNER_TASK and PLANNER_TASK_REPAIR must return one complete executable plan as exactly two lines and nothing else:
        RESOLVED_QUERY: <the standalone, spelling-corrected current query>
        PLAN: <one balanced search expression>
        If current_query is independent, preserve its subject. If it is a follow-up, use previous_query only to make the omitted subject explicit. Never copy an identifier, amount, date, or answer value from previous_answer.

        COMPLETE PLAN SCHEMA:
        - Every PLAN must contain exactly one [query_category == value], exactly one [answer_needed == true|false], and at least one retrieval predicate.
        - query_category value is exactly one of doc, scenary, person, location, time. Use doc for written facts; person when the requested answer is who; location when it is where; time when it is when; otherwise scenary for visual content or browsing.
        - answer_needed is true for a factual/question answer and false for a browse/show/find request.
        - Retrieval fields are person, people_only, mime type, location, time, from_date, to_date, semantic, and keyword. Do not emit ocr or any other field.
        - mime type values are exactly photos, videos, pdf, doc, messages, sms, calendar, contacts, call_logs, or files. Emit MIME only when the user explicitly names that source or format.
        - from_date and to_date must be ISO yyyy-MM-dd calculated from current_date. Convert every relative or calendar date window, including today, yesterday, this/last week/month/year, and last/past/since N days/weeks/months/years, into inclusive from_date AND to_date. Never copy relative date words into time.
        - time is only for an explicit clock such as 09:30 or 6 pm. Vague ordering words recent/latest/newest do not create a date range; append SORT_DATE instead. Do not turn a requested document field such as expiry date into a search-time constraint.

        EXPRESSION GRAMMAR:
        - Every predicate is exactly [field == value], one field per bracket. Operators go only between complete predicates.
        - && means all constraints apply to the same result. Comma means alternatives. + fuses retrieval intent. - subtracts the complete right group. SORT_DATE or SORT_LOC may appear once at the end.
        - `[person == Ravi && location == Goa]` is invalid. `[person == Ravi] && [location == Goa]` is valid.
        - keyword is the only field whose value may contain brace terms: [keyword == {Ravi} && {passport}].

        INTENT MAPPING:
        - known_people_lookup_only and self_person_lookup_only are vocabulary, never default filters. Emit person only when the standalone resolved query explicitly names that known face or explicitly says me/my/mine/myself for visual presence. If no person reference exists, emit no person even when self_person_lookup_only is set. Never assume an event, trip, place, activity, photo, or device belongs to self.
        - A printed document owner or message sender is keyword, not person. A self-reference in written/document intent may resolve to the self name inside keyword, but never person.
        - Use people_only with the same face labels only when the user explicitly says only/alone/no other people.
        - Use location only for a real explicitly named place. Weather and surroundings such as rain, snow, beach, forest, or indoors are semantic visual content, not locations.
        - Use semantic for visible objects, activities, scenes, events, or the type of written record. Preserve a compound visual idea as one natural phrase: cycling in rain, birthday party, playing cricket.
        - Visual/gallery intent never uses keyword. A person plus a place needs no semantic unless an activity, object, scene, or event is also requested.
        - Written/document intent uses one semantic record description AND one same-record keyword predicate. Semantic describes the record, not the requested answer field. Keyword keeps only distinctive searchable names/topics; omit question words, answer fields, numbers, dates, MIME words, and generic containers.
        - Calling history uses mime type call_logs; messages use messages or sms; their human identity is keyword rather than face person.
        - Every without/excluding/except/but not/not/no clause must be a subtraction group. Never leave excluded content in the positive group.
        - Phone/file lifecycle words such as saved, downloaded, edited, received, sent, shared, opened, or captured are not searchable content. Keep the actual subject and explicit source/time/person/place.
        - Correct obvious spelling in RESOLVED_QUERY and the plan. Never invent a person, place, date, source, object, or event.

        EXAMPLES:
        current_date=2026-08-22; current_query=my photos last 3 years; self_person_lookup_only=Ravi
        RESOLVED_QUERY: my photos last 3 years
        PLAN: [query_category == scenary] && [answer_needed == false] && [person == Ravi] && [mime type == photos] && [from_date == 2023-08-22] && [to_date == 2026-08-22]
        current_date=2026-08-22; current_query=birthday photos since 3 months; self_person_lookup_only=Ravi
        RESOLVED_QUERY: birthday photos since 3 months
        PLAN: [query_category == scenary] && [answer_needed == false] && [mime type == photos] && [semantic == birthday] && [from_date == 2026-05-22] && [to_date == 2026-08-22]
        current_date=2026-08-22; current_query=recent trip; self_person_lookup_only=Ravi; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: recent trip
        PLAN: [query_category == scenary] && [answer_needed == false] && [semantic == trip] SORT_DATE
        current_query=cycling in rain
        RESOLVED_QUERY: cycling in rain
        PLAN: [query_category == scenary] && [answer_needed == false] && [semantic == cycling in rain]
        current_query=Ravi photos at BR Hills; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: Ravi photos at BR Hills
        PLAN: [query_category == scenary] && [answer_needed == false] && [person == Ravi] && [mime type == photos] && [location == BR Hills]
        current_query=when is Ravi birthday; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: when is Ravi birthday
        PLAN: [query_category == time] && [answer_needed == true] && [person == Ravi] && [semantic == birthday] SORT_DATE
        current_query=Ravi passport number; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: Ravi passport number
        PLAN: [query_category == doc] && [answer_needed == true] && [semantic == passport identity document] && [keyword == {Ravi} && {passport}]
        current_query=Ramani photos without me; self_person_lookup_only=Ravi; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: Ramani photos without Ravi
        PLAN: [query_category == scenary] && [answer_needed == false] && [[person == Ramani] && [mime type == photos]] - [person == Ravi]
        previous_query=Ravi passport number; current_query=when does it expire
        RESOLVED_QUERY: when does Ravi passport expire
        PLAN: [query_category == doc] && [answer_needed == true] && [semantic == passport identity document] && [keyword == {Ravi} && {passport}]
    """.trimIndent()

    /** V2 is the default for tests and fresh installs; Settings can safely select retained V1. */
    fun plannerSystemInstruction(): String = plannerSystemInstruction(QueryPlannerProtocol.V2)

    fun plannerSystemInstruction(context: Context): String =
        plannerSystemInstruction(QueryPlannerProtocolPreferences.selected(context))

    internal fun plannerSystemInstruction(protocol: QueryPlannerProtocol): String = when (protocol) {
        QueryPlannerProtocol.V1 -> V1_PLANNER_SYSTEM_INSTRUCTION
        QueryPlannerProtocol.V2 -> QueryPlannerV2.systemInstruction
    }

    private fun plannerUserPrompt(
        query: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
        previousTurn: PreviousQueryTurn?,
    ): String = "PLANNER_TASK\n" +
        "current_date=${LocalDate.now()}\n" +
        "known_people_lookup_only=${knownPeopleText(knownPersonLabels)}\n" +
        "self_person_lookup_only=${selfPersonText(selfPersonLabel)}\n" +
        "previous_query=${previousTurn?.let { cleanQuery(it.query) } ?: "none"}\n" +
        "previous_answer=${previousTurn?.let { cleanPriorAnswer(it.answer) } ?: "none"}\n" +
        "current_query=${cleanQuery(query)}"

    private fun plannerRepairPrompt(
        query: String,
        previousTurn: PreviousQueryTurn?,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
        invalidOutput: String,
        error: Throwable,
        repairAttempt: Int,
    ): String {
        val safeError = error.message.orEmpty()
            .replace(Regex("[\\r\\n]+"), " ")
            .take(MAX_REPAIR_ERROR_CHARS)
        val safeOutput = invalidOutput
            .replace(Regex("[\\r\\n]+"), " ")
            .take(MAX_OUTPUT_CHARS)
        val context = previousTurn?.let {
            "previous_query=${cleanQuery(it.query)}\nprevious_answer=${cleanPriorAnswer(it.answer)}"
        } ?: "previous_query=none\nprevious_answer=none"
        return """
            PLANNER_TASK_REPAIR
            attempt=$repairAttempt
            current_date=${LocalDate.now()}
            known_people_lookup_only=${knownPeopleText(knownPersonLabels)}
            self_person_lookup_only=${selfPersonText(selfPersonLabel)}
            original_query=${cleanQuery(query)}
            $context
            validator_error=$safeError
            invalid_expression=$safeOutput
            Do not copy the invalid expression. Re-plan from the original query. Return exactly two lines: RESOLVED_QUERY: <standalone query> then PLAN: <balanced expression>.
            PLAN must contain exactly one query_category, exactly one answer_needed, and at least one retrieval predicate. Use only query_category, answer_needed, person, people_only, mime type, location, time, from_date, to_date, semantic, and keyword. Every predicate is [field == value] with one field per bracket; operators go only between complete predicates. Do not emit ocr. Convert relative/calendar date windows from current_date into ISO from_date and to_date; never put relative date words in time. known_people_lookup_only and self_person_lookup_only are vocabulary only: without an explicit person name or self-reference, emit no person. Correct spelling, preserve complete user intent, and use subtraction for every exclusion.
        """.trimIndent()
    }

    private fun knownPeopleText(knownPersonLabels: List<String>): String =
        cleanKnownPeople(knownPersonLabels).joinToString("|")
            .ifBlank { "none" }

    private fun cleanKnownPeople(knownPersonLabels: List<String>): List<String> =
        knownPersonLabels.asSequence()
            .map { it.replace(Regex("[\\r\\n|]+"), " ").trim().take(48) }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(64)
            .toList()

    private fun selfPersonText(selfPersonLabel: String?): String =
        cleanSelfPerson(selfPersonLabel)
            ?: "not set"

    private fun cleanSelfPerson(selfPersonLabel: String?): String? =
        selfPersonLabel
            ?.replace(Regex("[\\r\\n|]+"), " ")
            ?.trim()
            ?.take(48)
            ?.takeIf(String::isNotBlank)

    private fun cleanQuery(query: String): String =
        query.replace(Regex("[\\r\\n]+"), " ").trim().take(MAX_QUERY_CHARS)

    private fun cleanPriorAnswer(answer: String): String =
        answer.replace(Regex("[\\r\\n]+"), " ").trim().take(240).ifBlank { "none" }

    private fun singleLineForLog(value: String): String =
        value.replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(420)

    private fun compileGemmaPlan(
        raw: String,
    ): QueryPlan {
        val modelSpec = QueryExecutionSpec.parse(raw.trim())
        return ModelAuthoredPlanStructure.compile(modelSpec)
    }

}

/**
 * The complete plan is model-authored. This gate checks only protocol/schema
 * structure before compilation; it never compares the plan with user wording.
 */
internal object ModelAuthoredPlanStructure {
    private val explicitClock = Regex(
        "(?i)^(?:(?:[01]?\\d|2[0-3]):[0-5]\\d|(?:0?[1-9]|1[0-2])(?::[0-5]\\d)?\\s*(?:am|pm))$",
    )
    private val retrievalFields = setOf(
        ExecutionField.PERSON,
        ExecutionField.PEOPLE_ONLY,
        ExecutionField.MIME_TYPE,
        ExecutionField.FROM_DATE,
        ExecutionField.TO_DATE,
        ExecutionField.LOCATION,
        ExecutionField.TRAVEL,
        ExecutionField.TIME,
        ExecutionField.SEMANTIC,
        ExecutionField.KEYWORD,
    )

    fun compile(spec: QueryExecutionSpec): QueryPlan {
        require(spec.queryCategoryOrNull() != null) {
            "PLAN must contain exactly one query_category"
        }
        require(spec.answerNeededOrNull() != null) {
            "PLAN must contain exactly one answer_needed"
        }
        var retrievalPredicates = 0

        fun validate(node: ExecutionNode, insideRetrievalOperator: Boolean = false) {
            when (node) {
                is ExecutionNode.Predicate -> when (node.field) {
                    ExecutionField.QUERY_CATEGORY,
                    ExecutionField.ANSWER_NEEDED,
                    -> require(!insideRetrievalOperator) {
                        "query_category and answer_needed must be top-level && envelope predicates"
                    }
                    ExecutionField.TIME -> {
                        require(explicitClock.matches(node.value.trim())) {
                            "time accepts only an explicit clock; relative/calendar dates require ISO from_date and to_date"
                        }
                        retrievalPredicates += 1
                    }
                    in retrievalFields -> retrievalPredicates += 1
                    else -> throw IllegalArgumentException(
                        "Unsupported planner field '${node.field.wireName}'",
                    )
                }
                is ExecutionNode.Sorted -> validate(node.value, insideRetrievalOperator)
                is ExecutionNode.Binary -> {
                    val nested = insideRetrievalOperator ||
                        node.operator != ExecutionBinaryOperator.INTERSECT
                    validate(node.left, nested)
                    validate(node.right, nested)
                }
            }
        }
        validate(spec.root)
        require(retrievalPredicates > 0) { "PLAN must contain at least one retrieval predicate" }
        return ExecutionSpecCompiler.compile(spec)
    }
}

internal data class PreviousQueryTurn(
    val query: String,
    val answer: String,
)

internal data class ContextualPlannerOutput(
    val resolvedQuery: String,
    val expression: String,
)

/** Parses only E2B's two-line protocol; it never resolves or rewrites intent. */
internal object ContextualPlannerOutputPolicy {
    fun parse(
        output: String,
        @Suppress("UNUSED_PARAMETER") currentQuery: String = "",
        @Suppress("UNUSED_PARAMETER") previousTurn: PreviousQueryTurn? = null,
        @Suppress("UNUSED_PARAMETER") knownPersonLabels: List<String> = emptyList(),
        @Suppress("UNUSED_PARAMETER") selfPersonLabel: String? = null,
    ): ContextualPlannerOutput {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(lines.size == 2) { "Planner output must contain exactly two non-empty lines" }
        require(lines[0].startsWith("RESOLVED_QUERY:")) {
            "Planner output line 1 must start with RESOLVED_QUERY:"
        }
        require(lines[1].startsWith("PLAN:")) {
            "Planner output line 2 must start with PLAN:"
        }
        val resolved = lines[0].removePrefix("RESOLVED_QUERY:").trim()
        val expression = lines[1].removePrefix("PLAN:").trim()
        require(resolved.isNotBlank() && resolved.length <= 256) {
            "RESOLVED_QUERY must contain 1-256 characters"
        }
        require(expression.isNotBlank()) { "PLAN expression must not be empty" }
        require('[' !in resolved && ']' !in resolved) {
            "RESOLVED_QUERY must be natural language, not planner syntax"
        }
        return ContextualPlannerOutput(resolved, expression)
    }
}

/** Generic container/record labels belong in semantic context, not lexical keywords. */
internal object SearchKeywordPolicy {
    /** Grammar-only tokens can never be lexical retrieval requirements. */
    val forbiddenScaffoldingWords = setOf(
        "a", "an", "the",
        "am", "is", "are", "was", "were", "be", "been", "being",
        "do", "does", "did", "doing",
        "have", "has", "had", "having",
        "can", "could", "would", "should", "shall",
        "what", "when", "where", "why", "how", "which", "who", "whom", "whose", "else",
        "and", "or", "but", "of", "in", "on", "at", "to", "from", "for", "with", "by",
        "please",
    ) + QueryLifecycleScaffoldingPolicy.forbiddenWords

    val genericRecordWords = setOf(
        "ticket", "tickets", "receipt", "receipts", "bill", "bills",
        "invoice", "invoices", "document", "documents", "file", "files",
        "message", "messages", "sms", "calendar", "calendars", "contact",
        "contacts", "call", "calls", "log", "logs", "appointment",
        "appointments", "record", "records", "event", "events", "booking",
        "bookings",
    )

    fun filterScaffolding(words: List<String>): List<String> =
        words.filterNot { it.lowercase() in forbiddenScaffoldingWords }
}

/** Phone/file operations describe provenance, not searchable record content. */
internal object QueryLifecycleScaffoldingPolicy {
    val forbiddenWords = setOf(
        "save", "saves", "saved", "saving",
        "download", "downloads", "downloaded", "downloading",
        "edit", "edits", "edited", "editing", "modify", "modifies", "modified", "modifying",
        "receive", "receives", "received", "receiving",
        "send", "sends", "sent", "sending", "share", "shares", "shared", "sharing",
        "upload", "uploads", "uploaded", "uploading",
        "import", "imports", "imported", "importing", "export", "exports", "exported", "exporting",
        "store", "stores", "stored", "storing", "sync", "syncs", "synced", "syncing",
        "backup", "backups", "backed", "backing",
        "open", "opens", "opened", "opening", "view", "views", "viewed", "viewing",
        "access", "accesses", "accessed", "accessing",
        "transfer", "transfers", "transferred", "transferring",
        "forward", "forwards", "forwarded", "forwarding",
        "attach", "attaches", "attached", "attaching",
        "take", "takes", "taking", "took", "taken", "capture", "captures", "captured", "capturing",
    )
    private val lifecycleWord = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:" +
            forbiddenWords.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) } +
            ")(?![\\p{L}\\p{N}])",
    )
    private val screenshotAcquisition = Regex(
        "(?i)\\b(?:take|takes|taking|took|taken|capture|captures|captured|capturing)\\s+" +
            "(?:(?:a|an|the|some|my)\\s+)?screenshots?\\b|" +
            "\\bscreenshots?\\s+(?:that\\s+)?i\\s+(?:take|took|captured)\\b",
    )
    private val lifecyclePhrase = Regex(
        "(?i)\\b(?:backed|backing)\\s+up\\b|\\bsaved?\\s+(?:to|from)\\b|" +
            "\\breceived?\\s+from\\b|\\b(?:sent|shared|uploaded|downloaded)\\s+(?:to|from)\\b",
    )

    fun strip(value: String): String = value
        .replace(screenshotAcquisition, " ")
        .replace(lifecyclePhrase, " ")
        .replace(lifecycleWord, " ")
        .replace(Regex("(?i)^\\s*(?:and|or)\\s+|\\s+(?:and|or)\\s*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Expiry wording requests a field from a record; it is never a retrieval keyword. */
internal object ExpiryKeywordPolicy {
    private val EXPIRY_INTENT = Regex(
        "(?i)\\b(?:expir(?:e|es|ed|ing|y|ation)|validity|valid\\s+(?:until|till|through|to))\\b",
    )
    private val FIELD_WORDS = setOf(
        "expiry", "expire", "expires", "expired", "expiring", "expiration",
        "validity", "valid", "until", "till", "through", "to",
    )

    fun isExpiryFieldQuery(query: String): Boolean = EXPIRY_INTENT.containsMatchIn(query)

    fun forbiddenWordsFor(query: String): Set<String> =
        if (isExpiryFieldQuery(query)) FIELD_WORDS else emptySet()

    fun filter(query: String, words: List<String>): List<String> {
        val forbidden = forbiddenWordsFor(query)
        return if (forbidden.isEmpty()) words else words.filterNot { it.lowercase() in forbidden }
    }
}

/** Canonical anchors used by downstream document answer preparation, not QP. */
internal object IdentityDocumentQueryPolicy {
    data class Match(
        val keywordAnchor: String,
        val semanticExpression: String,
    )

    fun match(query: String): Match? = when {
        Regex("(?i)\\baadhaa?r(?:d)?(?:\\s+card)?\\b").containsMatchIn(query) ->
            Match("aadhaar", "aadhaar government identity card document")
        Regex("(?i)\\bpassport\\b").containsMatchIn(query) ->
            Match("passport", "passport travel identity document")
        Regex("(?i)\\bssn\\b").containsMatchIn(query) ->
            Match("ssn", "social security card identity document")
        Regex("(?i)\\bsocial\\s+security(?:\\s+(?:number|card))?\\b").containsMatchIn(query) ->
            Match("social security", "social security card identity document")
        Regex("(?i)\\bpan(?:\\s+card)?\\b").containsMatchIn(query) ->
            Match("pan", "PAN tax identity card document")
        Regex("(?i)\\b(?:driving|driver'?s?)\\s+licen[cs]e\\b|\\bdl\\b").containsMatchIn(query) ->
            Match("driving licence", "driving licence government identity document")
        Regex("(?i)\\bidentity\\s+(?:card|document)|\\bid\\s+card\\b").containsMatchIn(query) ->
            Match("identity card", "government identity card document")
        else -> null
    }
}

/** Fuzzy token matching used by downstream retrieval helpers, not QP. */
internal object QuerySpellingMatcher {
    fun isPlausibleCorrection(query: String, candidate: String): Boolean =
        isPlausibleCorrection(query, candidate, permitShortLocationTypo = false)

    fun isPlausibleLocationCorrection(query: String, candidate: String): Boolean =
        isPlausibleCorrection(query, candidate, permitShortLocationTypo = true)

    private fun isPlausibleCorrection(
        query: String,
        candidate: String,
        permitShortLocationTypo: Boolean,
    ): Boolean {
        val queryTokens = tokens(query)
        val candidateTokens = tokens(candidate)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false
        return candidateTokens.all { expected ->
            queryTokens.any { actual ->
                actual == expected ||
                    damerauLevenshtein(actual, expected) <= allowedDistance(
                        expected.length,
                        permitShortLocationTypo,
                    )
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
                    damerauLevenshtein(actual, expected) <= allowedDistance(
                        expected.length,
                        permitShortLocationTypo = false,
                    )
            }
        }
    }

    fun looksLikeEventPhrase(value: String): Boolean =
        tokens(value).any { it in EVENT_WORDS }

    private fun tokens(value: String): List<String> = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)

    private fun allowedDistance(length: Int, permitShortLocationTypo: Boolean): Int = when {
        length <= 2 -> 0
        length == 3 && permitShortLocationTypo -> 1
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
