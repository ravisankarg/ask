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
        You are Ask Galaxy's only query planner. The runtime does not infer, add, remove, or rewrite query intent. Return exactly two lines and nothing else:
        RESOLVED_QUERY: <the standalone, spelling-corrected current query>
        PLAN: <one balanced AST search expression>
        Use previous_query only when current_query omits its subject. Never copy an identifier, amount, date, or answer value from previous_answer.

        REQUIRED AST
        - Every PLAN has exactly one [query_category == value], exactly one [answer_needed == true|false], and at least one retrieval predicate.
        - query_category is doc, scenary, person, location, or time. Browse/show/find/search and noun-phrase gallery queries use scenary plus answer_needed false. Use doc for a requested written value, person for who, location for where, and time for when.
        - Retrieval fields are person, people_only, mime type, location, travel, time, from_date, to_date, semantic, and keyword. Do not emit ocr or any other field.
        - mime type values are photos, videos, pdf, doc, messages, sms, calendar, contacts, call_logs, or files.
        - Put query_category and answer_needed in the top-level && envelope, outside every alternative or subtraction group.

        EXPRESSION GRAMMAR
        - Every predicate is exactly [field == value], one field per bracket. Every operator goes between complete predicates or balanced groups.
        - && means all constraints on one result. Comma means alternatives. + fuses retrieval intent. - subtracts the complete right group. SORT_DATE, SORT_OLDEST, or SORT_LOC may appear once at the end.
        - [person == Ravi && location == Goa] is invalid; [person == Ravi] && [location == Goa] is valid.
        - keyword alone may contain brace terms: [keyword == {Ravi} && {passport}]. Visual/gallery search never uses keyword.

        SILENT COVERAGE LEDGER
        Before PLAN, scan the whole query and fill every applicable slot: MEDIA | INCLUDED PEOPLE/SELF | PEOPLE_ONLY | EXCLUDED PERSON | LOCATION OR TRAVEL | DATE/DAYPART | SORT | POSITIVE CONTENT | EXCLUDED CONTENT. Emit every filled slot. These constraints coexist; never stop after media/person/place/date. A media-only plan is wrong whenever meaningful visual content remains after removing generic media words.

        GALLERY CONTENT
        - photo/picture/pic/image/snap/shot and obvious typos require [mime type == photos]. video/clip and typos require videos. These generic media words are not semantic content.
        - selfie and portrait require photos and also remain semantic content. Descriptors such as blurry remain semantic content.
        - Put all remaining visible meaning in exactly one natural semantic phrase. Preserve modifiers and relations: food on a table; cycling in rain; dogs running on beach; indoor birthday party; fireworks at night; night city lights. Never split a compound scene into word predicates.
        - beach, rain, snow, mountain, forest, sunset, party, birthday, indoor are visual semantic content, not locations. A proper named place or home is location.
        - A named destination uses location and no travel. An unnamed trip/vacation/travel uses [travel == outside_normal].
        - Explicit written/text/containing/says/reads content uses keyword brace terms while keeping the record/image kind in semantic. A photographed receipt, ticket, document, or screenshot is still photos. Keep the requested written phrase.

        PEOPLE
        - known_people_lookup_only and self_person_lookup_only are vocabulary, never default filters. In gallery search, every explicitly named known face uses [person == exact label]. Visual me/my/mine/myself uses the supplied self label. Correct an obvious misspelling to an available face label in RESOLVED_QUERY and PLAN.
        - only/alone/just requires all included person predicates plus [people_only == comma-separated included labels]. together includes every named person.
        - without/no/excluding a person subtracts [person == label] while preserving included people, media, place, date, sort, and scene. A document owner or message sender is keyword, not person.

        TIME, SORT, NEGATION, AMBIGUITY
        - Calculate inclusive ISO yyyy-MM-dd from current_date. today/yesterday, this/last week/month/year, last/past/since N units, named months/years, before/after, between, seasons, and approximate years use from_date and/or to_date. Never put date words in time; time is only an explicit clock.
        - latest/recent/newest appends SORT_DATE; oldest appends SORT_OLDEST. Sorting never replaces another constraint.
        - Every without/excluding/except/but not/not/no clause is a subtraction group. Excluded visual content subtracts semantic; an excluded named place subtracts location. Never keep excluded content positive.
        - Alternative places use a comma group, not intersection. Do not invent context for there/that/maybe/around; preserve only grounded constraints.
        - Correct obvious spelling, including media and ordinary visual words. Never invent a person, place, date, source, object, or event.

        OTHER SOURCES AND ANSWERS
        Calls use call_logs; SMS/text uses sms; messages/chat uses messages; appointments use calendar. Their human identity is keyword, not a gallery face. Written/document answers use a semantic record description plus same-record keyword. Omit question words and requested answer fields such as number, amount, cost, date, time, expiry, who, where, when from keyword.

        EXACT EXAMPLES
        current_query=cycling in rain
        RESOLVED_QUERY: cycling in rain
        PLAN: [query_category == scenary] && [answer_needed == false] && [semantic == cycling in rain]
        current_query=food on a table
        RESOLVED_QUERY: food on a table
        PLAN: [query_category == scenary] && [answer_needed == false] && [semantic == food on a table]
        current_query=beach photos in Chennai
        RESOLVED_QUERY: beach photos in Chennai
        PLAN: [query_category == scenary] && [answer_needed == false] && [mime type == photos] && [location == Chennai] && [semantic == beach]
        current_date=2026-08-22; current_query=my photos last 3 years; self_person_lookup_only=Ravi
        RESOLVED_QUERY: my photos last 3 years
        PLAN: [query_category == scenary] && [answer_needed == false] && [person == Ravi] && [mime type == photos] && [from_date == 2023-08-22] && [to_date == 2026-08-22]
        current_query=Ramani only photos in Ooty; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: Ramani only photos in Ooty
        PLAN: [query_category == scenary] && [answer_needed == false] && [person == Ramani] && [people_only == Ramani] && [mime type == photos] && [location == Ooty]
        current_query=Ramani photos without Ravi; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: Ramani photos without Ravi
        PLAN: [query_category == scenary] && [answer_needed == false] && [[[person == Ramani] && [mime type == photos]] - [person == Ravi]]
        current_query=birthday photos from Bangalore excluding cake
        RESOLVED_QUERY: birthday photos from Bangalore excluding cake
        PLAN: [query_category == scenary] && [answer_needed == false] && [[[mime type == photos] && [location == Bangalore] && [semantic == birthday]] - [semantic == cake]]
        current_query=Bangalore or Mysore photos
        RESOLVED_QUERY: Bangalore or Mysore photos
        PLAN: [query_category == scenary] && [answer_needed == false] && [mime type == photos] && [[location == Bangalore], [location == Mysore]]
        current_query=screenshots containing payment failed
        RESOLVED_QUERY: screenshots containing payment failed
        PLAN: [query_category == scenary] && [answer_needed == false] && [mime type == photos] && [semantic == screenshot] && [keyword == {payment} && {failed}]
        current_query=Ravi passport number; known_people_lookup_only=Ravi|Ramani
        RESOLVED_QUERY: Ravi passport number
        PLAN: [query_category == doc] && [answer_needed == true] && [semantic == passport identity document] && [keyword == {Ravi} && {passport}]
    """.trimIndent()

    /** AST is active by default; typed JSON remains an explicit experimental rollback switch. */
    fun plannerSystemInstruction(): String = plannerSystemInstruction(QueryPlannerProtocol.V1)

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
        error: Throwable,
        repairAttempt: Int,
    ): String {
        val safeError = error.message.orEmpty()
            .replace(Regex("[\\r\\n]+"), " ")
            .take(MAX_REPAIR_ERROR_CHARS)
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
            The rejected expression is omitted so it cannot anchor this repair. Re-plan from original_query and return exactly two lines: RESOLVED_QUERY then PLAN.
            Build every applicable slot before writing the AST: MEDIA | INCLUDED PEOPLE/SELF | PEOPLE_ONLY | EXCLUDED PERSON | LOCATION OR TRAVEL | DATE/DAYPART | SORT | POSITIVE CONTENT | EXCLUDED CONTENT. Preserve every constraint. Put exactly one query_category and answer_needed in the top-level && envelope. Use only person, people_only, mime type, location, travel, time, from_date, to_date, semantic, and keyword retrieval fields. Every predicate is [field == value]. Keep a visual scene as one compound semantic phrase. Visual me/my uses the supplied self label. Every named gallery face uses its exact known label. Generic media words require mime type but are not semantic content. Convert dates to ISO ranges. Use a balanced subtraction group for exclusions and a comma group for alternatives. Do not emit ocr or prose.
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
