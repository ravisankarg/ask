package com.ravi.askgalaxy

import android.content.Context
import android.util.Log

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
    private const val MAX_OUTPUT_CHARS = 900
    private const val MAX_REPAIR_ERROR_CHARS = 180
    private const val MAX_REPAIR_ATTEMPTS = 2
    private val semanticForbiddenWords = setOf(
        "when", "where", "who", "whom", "whose", "which", "what", "why", "how",
        "show", "find", "search", "tell", "display",
        "today", "yesterday", "tomorrow", "last", "latest", "newest", "recent",
        "previous", "current", "this", "next",
        "day", "days", "week", "weeks", "month", "months", "year", "years",
        "person", "people", "location", "place", "mime",
        "photo", "photos", "picture", "pictures", "image", "images",
        "video", "videos",
    )
    /** Words that describe the requested answer, not the record to retrieve. */
    private val answerAttributeWords = setOf(
        "cost", "price", "amount", "total", "spent", "spend", "paid", "payment",
        "number", "numbers", "count", "many", "date", "time", "when", "address",
        "holder", "name", "dob", "birth", "issue", "issued", "status",
    )
    private val isoDateToken = Regex("\\b\\d{4}(?:-\\d{2}(?:-\\d{2})?)?\\b")

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

    /**
     * Every query is compiled by the selected Gemma 4 model. A malformed response gets one
     * repair turn in the same session; there is no deterministic QP fallback.
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
        val planningQuery = normalizePlannerQuery(query)
        val previousTurn = PreviousQueryTurn(
            query = cleanQuery(previousQuery),
            answer = cleanPriorAnswer(previousAnswer),
        ).takeIf {
            FollowUpQueryContextPolicy.shouldResolve(
                currentQuery = planningQuery,
                previousQuery = it.query,
                knownPersonLabels = knownPersonLabels,
            )
        }
        check(isModelInstalled(appContext)) {
            "A selected Gemma 4 model is required for query planning"
        }
        var session: GemmaRuntime.ConversationSession? = null
        return try {
            session = GemmaRuntime.takePrefilledPlannerSession()
                ?: GemmaRuntime.shared(appContext).createPlannerConversation(plannerSystemInstruction())
            val generationProfiles = ArrayList<GemmaRuntime.GenerationProfile>()
            var candidateRaw = session.generate(
                plannerUserPrompt(
                    planningQuery,
                    knownPersonLabels,
                    selfPersonLabel,
                    previousTurn,
                ),
            )
                .trim()
                .take(MAX_OUTPUT_CHARS)
            session.lastGenerationProfile?.let(generationProfiles::add)
            var repairUsed = false
            var repairAttempt = 0
            lateinit var parsed: QueryPlan
            var resolvedQuery = planningQuery
            while (true) {
                try {
                    val modelOutput = ContextualPlannerOutputPolicy.parse(
                        output = candidateRaw,
                        currentQuery = planningQuery,
                        previousTurn = previousTurn,
                        knownPersonLabels = knownPersonLabels,
                        selfPersonLabel = selfPersonLabel,
                    )
                    resolvedQuery = modelOutput.resolvedQuery
                    parsed = compileGemmaPlan(
                        modelOutput.expression,
                        resolvedQuery,
                        knownPersonLabels,
                        selfPersonLabel,
                    )
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
                    candidateRaw = session.generate(
                        plannerRepairPrompt(
                            query = planningQuery,
                            previousTurn = previousTurn,
                            invalidOutput = candidateRaw,
                            error = validationError,
                            repairAttempt = repairAttempt,
                        ),
                    ).trim().take(MAX_OUTPUT_CHARS)
                    session.lastGenerationProfile?.let(generationProfiles::add)
                }
            }
            Log.i(
                TAG,
                "Gemma-only plan accepted: category=${parsed.queryCategory.wireName}, " +
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
                plannerJson = canonicalExecutionSpec(parsed),
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

    private val COMPACT_PLANNER_SYSTEM_INSTRUCTION = """
        You are Ask Galaxy's private query compiler. Every PLANNER_TASK and PLANNER_TASK_REPAIR must return exactly two lines and nothing else:
        RESOLVED_QUERY: <the standalone, spelling-corrected current query>
        PLAN: <one balanced search expression>
        Repeat an independent current query without changing its subject. Only when previous_query is supplied and the current query omits or indirectly refers to its subject, carry that subject into RESOLVED_QUERY. Never copy an identifier or number from previous_answer.

        SYNTAX: Emit only person, location, time, semantic, keyword, and optional mime type. Do not emit query_category, answer_needed, ocr, people_only, metadata, or dates. Every predicate is exactly [field == value], with one field per bracket. Join complete predicates with &&, alternatives with comma, fused intent with +, and exclusions with -. `[person == Ramani && location == Goa]` is invalid; `[person == Ramani] && [location == Goa]` is correct. Keyword is the only field whose value may contain inner braces joined by &&: [keyword == {Ravi} && {passport}].

        INTENT RULES:
        - Visual/gallery intent: use [person] for every known person, [location] for an explicit place, [time] for a date/period/time-of-day, and [semantic] for each visible object, activity, scene, or event. Do not emit keyword for visual intent. A bare person plus place has no semantic: "Ramani at Goa" is person + location. An activity remains semantic: dancing, hiking, birthday party, beach, glasses. Do not infer photos or videos unless the user says a photo/image/picture or video/clip word.
        - Written/document intent: passports, Aadhaar, SSN, licences, IDs, tickets, receipts, bills, invoices, policies, certificates, amounts, numbers, and expiry fields require one document [semantic] AND one same-record [keyword]. A written subject name belongs in keyword, never person. Semantic describes the evidence record, not the requested field: passport identity document, not passport number or expiry date. Keyword keeps the subject and distinctive content terms but removes requested fields and generic containers.
        - Communication intent: calling/speaking/phone history uses [mime type == call_logs] plus call-history semantic. Texting/messages uses [mime type == messages] plus message-conversation semantic. Sender identity is keyword, not face/person.
        - mime type is emitted only for an explicit source/format request. Canonical values: photos, videos, pdf, doc, messages, sms, calendar, contacts, call_logs, files. The words document/documents do not imply doc MIME because photographed documents must remain searchable.
        - time is only a real temporal constraint: ISO date/range, last week, month/year, morning, afternoon, evening, or night. Never use when, date, time, visit, went, happened, or recent as a time value.
        - Emit [time] only when current_query itself contains an explicit temporal constraint. Runtime/current-date metadata is never a user constraint. An expiry date, issue date, birth date, ticket date, or other requested document field is not a [time] search filter.
        - File lifecycle wording has no searchable meaning. Never put save/saved, download/downloaded, edit/edited, receive/received, send/sent, share/shared, upload/uploaded, import/imported, export/exported, store/stored, sync/synced, back up/backed up, open/opened, view/viewed, access/accessed, transfer/transferred, forward/forwarded, attach/attached, or take/took/captured screenshots in semantic or keyword. These words describe how a record reached or changed on the phone, not its content. Keep only real remaining content plus structured person/source, time, location, and explicit media type. If no searchable content remains, emit only the hard fields; if there are no hard fields either, use [semantic == all] and do not invent a topic.
        - Resolve me/my/mine/myself to self_person. Document intent puts self_person in keyword and never person. Visual intent puts self_person in person and never keyword. First-person I in a spending/action question is grammar, not document ownership. Relationship people use symbolic person values such as {_sister_}, {_brother_}, or {_mother_}.
        - Every without, excluding, except, but not, not, or no clause is a subtraction group. Never leave excluded content in a positive predicate.
        - Correct obvious spelling before RESOLVED_QUERY, semantic, and keyword: odyssy -> Odyssey; spyde man -> spiderman; withour/witout -> without. A corrected without clause must use subtraction.

        KEYWORD RULES FOR NON-VISUAL RECORDS: Use one brace per meaningful content word. Grammar/question words are banned, including a, an, the, am, is, are, was, were, be, do, does, did, have, has, had, what, when, where, why, how, which, who, else, and, or, but, of, in, on, at, to, from, for, with, by, please. Never emit {does} or {was}. Also remove numbers/dates, represented MIME words, requested fields cost, price, amount, total, spent, paid, count, number, expiry, expire, expiration, validity, valid-until, valid-till, and generic containers ticket, receipt, bill, invoice, document, file, message, calendar, contact, call log, record, event, booking.

        EXAMPLES (each output still has exactly two lines):
        current_query=Ramani at Goa
        RESOLVED_QUERY: Ramani at Goa
        PLAN: [person == Ramani] && [location == Goa]
        current_query=Ramani dancing
        RESOLVED_QUERY: Ramani dancing
        PLAN: [person == Ramani] && [semantic == dancing]
        current_query=Ramani dancing in the morning
        RESOLVED_QUERY: Ramani dancing in the morning
        PLAN: [person == Ramani] && [semantic == dancing] && [time == morning]
        current_query=what I saved last week
        RESOLVED_QUERY: what I saved last week
        PLAN: [time == last week]
        current_query=beach photos I downloaded and edited
        RESOLVED_QUERY: beach photos I downloaded and edited
        PLAN: [mime type == photos] && [semantic == beach]
        current_query=Ramani photos without me; self_person=Ravi
        RESOLVED_QUERY: Ramani photos without Ravi
        PLAN: [[person == Ramani] && [mime type == photos]] - [person == Ravi]
        current_query=when is Ravi birthday
        RESOLVED_QUERY: when is Ravi birthday
        PLAN: [person == Ravi] && [semantic == birthday]
        current_query=Ravi passport number
        RESOLVED_QUERY: Ravi passport number
        PLAN: [semantic == passport identity document] && [keyword == {Ravi} && {passport}]
        current_query=my driving licence expire date; self_person=Ravi
        RESOLVED_QUERY: my driving licence expiry date
        PLAN: [semantic == driving licence identity document] && [keyword == {Ravi} && {driving} && {licence}]
        current_query=how much I spent on odyssy movie ticket
        RESOLVED_QUERY: how much I spent on Odyssey movie ticket
        PLAN: [semantic == movie ticket] && [keyword == {Odyssey} && {movie}]
        previous_query=Ravi passport number; current_query=when does passport expire
        RESOLVED_QUERY: when does Ravi passport expire
        PLAN: [semantic == passport identity document] && [keyword == {Ravi} && {passport}]
    """.trimIndent()

    /** Stable preface placed in the conversation KV cache before a query arrives. */
    fun plannerSystemInstruction(): String = COMPACT_PLANNER_SYSTEM_INSTRUCTION


    private fun plannerUserPrompt(
        query: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
        previousTurn: PreviousQueryTurn?,
    ): String = "PLANNER_TASK\n" +
        "known_people=${knownPeopleText(knownPersonLabels)}\n" +
        "self_person=${selfPersonText(selfPersonLabel)}\n" +
        "previous_query=${previousTurn?.let { cleanQuery(it.query) } ?: "none"}\n" +
        "previous_answer=${previousTurn?.let { cleanPriorAnswer(it.answer) } ?: "none"}\n" +
        "current_query=${cleanQuery(query)}"

    private fun plannerRepairPrompt(
        query: String,
        previousTurn: PreviousQueryTurn?,
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
            original_query=${cleanQuery(query)}
            $context
            validator_error=$safeError
            invalid_expression=$safeOutput
            Do not copy the invalid expression. Return exactly two lines: RESOLVED_QUERY: <standalone query> then PLAN: <balanced expression>.
            Every predicate must be [field == value] with exactly one field per bracket; operators go only between complete predicates. Use only semantic, location, person, time, keyword, and optional mime type. Visual intent uses person/location/time plus semantic for visible content and never keyword. Document intent uses one semantic AND one same-record keyword. Emit mime type only when explicitly requested. Never emit query_category, answer_needed, ocr, or people_only. Correct spelling. Exclude grammar words and requested answer fields from keyword. Every without/excluding/not/no clause must use `-`.
        """.trimIndent()
    }

    private fun knownPeopleText(knownPersonLabels: List<String>): String =
        knownPersonLabels.asSequence()
            .map { it.replace(Regex("[\\r\\n|]+"), " ").trim().take(48) }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(64)
            .joinToString("|")
            .ifBlank { "none" }

    private fun selfPersonText(selfPersonLabel: String?): String =
        selfPersonLabel
            ?.replace(Regex("[\\r\\n|]+"), " ")
            ?.trim()
            ?.take(48)
            ?.takeIf(String::isNotBlank)
            ?: "not set"

    private fun cleanQuery(query: String): String =
        query.replace(Regex("[\\r\\n]+"), " ").trim().take(MAX_QUERY_CHARS)

    private fun cleanPriorAnswer(answer: String): String =
        answer.replace(Regex("[\\r\\n]+"), " ").trim().take(240).ifBlank { "none" }

    internal fun normalizePlannerQuery(query: String): String =
        query.trim()
            .replace(Regex("(?i)\\bwith\\s+out\\b"), "without")
            .replace(Regex("(?i)\\b(?:withour|witout|withot|withuot|withou)\\b"), "without")
            .replace(
                Regex("(?i)\\b(?:licennce|licennse|liscence|liscense|lisence|lisense|" +
                    "licenece|licecnce|licnce|licnese)\\b"),
                "licence",
            )
            .replace(Regex("\\s+"), " ")

    /** Removes natural-language exclusion spans before extracting positive scopes. */
    private fun removeNaturalNegativeClauses(query: String): String {
        val normalized = normalizePlannerQuery(query)
        val marker = Regex(
            "(?i)\\b(?:without|excluding|exclude|except|but\\s+not|not|no)\\b",
        )
        val boundary = Regex(
            "(?i)\\b(?:in|at|from|during|on|for|with|while|where|that|show|find|display)\\b|" +
                "\\b(?:and|but)\\b",
        )
        val ranges = ArrayList<IntRange>()
        var cursor = 0
        while (cursor < normalized.length) {
            val match = marker.find(normalized, cursor) ?: break
            val markerText = match.value.lowercase()
            var valueStart = match.range.last + 1
            // Keep the preposition inside "not from/in/at ..." so the date or
            // place after it cannot be mistaken for a positive constraint.
            if (markerText == "not" || markerText == "no") {
                Regex("(?i)^\\s+(?:from|in|at|during|on)\\b")
                    .find(normalized, valueStart)
                    ?.let { valueStart = it.range.last + 1 }
            }
            val nextMarker = marker.find(normalized, valueStart)?.range?.first
            val nextBoundary = boundary.find(normalized, valueStart)?.range?.first
            val end = listOfNotNull(nextMarker, nextBoundary)
                .filter { it > valueStart }
                .minOrNull()
                ?: normalized.length
            ranges += match.range.first until end
            cursor = end
        }
        if (ranges.isEmpty()) return normalized
        return buildString {
            var previousEnd = 0
            ranges.forEach { range ->
                append(normalized, previousEnd, range.first)
                append(' ')
                previousEnd = range.last + 1
            }
            append(normalized, previousEnd, normalized.length)
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun singleLineForLog(value: String): String =
        value.replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(420)

    private fun compileGemmaPlan(
        raw: String,
        query: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
    ): QueryPlan {
        val candidate = raw
            .replace("```text", "", ignoreCase = true)
            .replace("```", "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .removePrefix("EXECUTION_SPEC:")
            .trim()
        val modelSpec = QueryExecutionSpec.parse(
            PlannerExpressionSyntaxPolicy.canonicalize(candidate),
        )
        validatePlannerFields(modelSpec.root)
        val normalizedSpec = normalizeFiniteConstraints(
            modelSpec,
            query,
            knownPersonLabels,
            selfPersonLabel,
        )
        validatePlannerOcrSyntax(normalizedSpec.render())
        val plan = ExecutionSpecCompiler.compile(
            normalizedSpec,
            // These are deterministic answer-routing metadata, not
            // planner-authored search predicates.
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
        )
        validateCompiledPlan(query, knownPersonLabels, plan, selfPersonLabel)
        return plan
    }

    /**
     * Gemma authors retrieval intent, but a handful of planner fields are not
     * open-ended language decisions: the answer route, known face names,
     * explicit date window, media type, and requested sort all have one
     * canonical value derived directly from the user's words. Preserve the
     * model's semantic/keyword/grouping intent while replacing only those finite
     * constraints. This prevents a repair turn from throwing away an otherwise
     * useful plan because it chose `person` instead of `scenary`, omitted a
     * named face predicate, or used a relative-date shorthand.
     */
    internal fun normalizeFiniteConstraints(
        modelSpec: QueryExecutionSpec,
        query: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String? = null,
    ): QueryExecutionSpec {
        val expectedCategory = QueryCategoryConstraintPolicy.expectedCategory(query)
        val isDocumentQuery = expectedCategory == QueryCategory.DOC
        val referencesSelf = SelfPersonQueryPolicy.referencesSelfForCategory(query, expectedCategory)
        val mentionedKnownPeople = knownPersonLabels.asSequence()
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .filter { label ->
                containsPhrase(query, label) || QuerySpellingMatcher.isPlausibleCorrection(query, label)
            }
            .toList()
            .toMutableList()
        // A printed name identifies a document through OCR; it is not a face
        // constraint. Keep that distinction before adding scene/person fields.
        val mentionedExcludedPeople = mentionedKnownPeople.filter {
            QueryStructuredIntentPolicy.isNegatedMention(query, it)
        }
        val namedPeople = if (expectedCategory == QueryCategory.DOC) {
            mutableListOf()
        } else {
            mentionedKnownPeople.filterNot { it in mentionedExcludedPeople }.toMutableList()
        }
        val excludedNamedPeople = mentionedExcludedPeople.toMutableList()
        if (
            !isDocumentQuery &&
            referencesSelf &&
            !selfPersonLabel.isNullOrBlank() &&
            namedPeople.none { it.equals(selfPersonLabel, ignoreCase = true) } &&
            excludedNamedPeople.none { it.equals(selfPersonLabel, ignoreCase = true) }
        ) {
            if (SelfPersonQueryPolicy.isNegatedSelfReference(query)) {
                excludedNamedPeople += selfPersonLabel
            } else {
                namedPeople += selfPersonLabel
            }
        }
        val structuredPersonKeywordWords = (namedPeople + excludedNamedPeople)
            .flatMap { label ->
                Regex("[\\p{L}\\p{N}]+").findAll(label).map { it.value.lowercase() }.toList()
            }
            .toSet()
        val expectedMedia = QueryStructuredIntentPolicy.expectedMediaType(query)
        val explicitLocations = QueryStructuredIntentPolicy.explicitLocationCandidates(
            query,
            knownPersonLabels,
        )
        val deterministicLocation = explicitLocations.singleOrNull()
        // A negated year/month must never become the positive scope as well.
        // "photos not from 2022" means all matching photos minus 2022.
        val positiveConstraintQuery = removeNaturalNegativeClauses(query)
        val dateBounds = QueryScopeParser.explicitDateBoundsFromQuery(positiveConstraintQuery)
        val metadataOnly = QueryStructuredIntentPolicy.isMetadataOnlyIntent(query) ||
            QueryStructuredIntentPolicy.isPersonLocationMediaOnly(query, knownPersonLabels)
        val explicitVisualSemantic = if (
            expectedMedia == QueryMediaType.PHOTOS || expectedMedia == QueryMediaType.VIDEOS
        ) {
            var residue = QueryLifecycleScaffoldingPolicy.strip(positiveConstraintQuery)
            (mentionedKnownPeople + explicitLocations).distinctBy(String::lowercase).forEach { value ->
                residue = residue.replace(
                    Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(value)}(?![\\p{L}\\p{N}])"),
                    " ",
                )
            }
            val visualStopWords = SearchKeywordPolicy.forbiddenScaffoldingWords + setOf(
                "i", "me", "my", "mine", "myself",
                "photo", "photos", "picture", "pictures", "image", "images",
                "video", "videos", "clip", "clips",
                "today", "yesterday", "tomorrow", "morning", "afternoon", "evening", "night",
                "noon", "midnight", "last", "this", "next", "previous", "current",
                "day", "days", "week", "weeks", "month", "months", "year", "years",
                "without", "excluding", "exclude", "except", "not", "no",
            )
            Regex("[\\p{L}\\p{N}]+").findAll(residue)
                .map { it.value }
                .filter { token ->
                    token.lowercase() !in visualStopWords && token.any(Char::isLetter)
                }
                .joinToString(" ")
                .trim()
        } else {
            ""
        }
        val identityDocument = IdentityDocumentQueryPolicy.match(query)
        val documentSelfKeywordWords = if (
            isDocumentQuery && referencesSelf && !selfPersonLabel.isNullOrBlank()
        ) {
            Regex("[\\p{L}\\p{N}]+").findAll(selfPersonLabel).map { it.value }.toList()
        } else {
            emptyList()
        }
        val deterministicDocumentKeywords = if (isDocumentQuery && identityDocument != null) {
            val identityQueryStopWords = setOf(
                "a", "an", "the", "what", "which", "who", "whose", "is", "are", "was", "were",
                "my", "me", "mine", "our", "ours", "your", "yours", "his", "her", "hers", "their",
                "of", "on", "in", "at", "from", "for", "to", "with", "and", "or", "s",
                "show", "find", "search", "tell", "give", "read", "please",
                "aadhaar", "aadhar", "aadhard", "passport", "pan", "card", "driving", "driver",
                "drivers", "licence", "license", "identity", "id", "document", "ssn", "social",
                "security",
            ) + answerAttributeWords + SearchKeywordPolicy.genericRecordWords +
                QueryLifecycleScaffoldingPolicy.forbiddenWords +
                ExpiryKeywordPolicy.forbiddenWordsFor(query) +
                SearchKeywordPolicy.forbiddenScaffoldingWords
            val explicitSubjectWords = if (mentionedKnownPeople.isNotEmpty()) {
                mentionedKnownPeople
                    .filterNot { it in mentionedExcludedPeople }
                    .flatMap { label ->
                        Regex("[\\p{L}\\p{N}]+").findAll(label).map { it.value }.toList()
                    }
            } else {
                Regex("[\\p{L}\\p{N}]+").findAll(query)
                    .map { it.value }
                    .filter { it.lowercase() !in identityQueryStopWords }
                    .toList()
            }
            (
                documentSelfKeywordWords +
                    explicitSubjectWords +
                    identityDocument.keywordAnchor.split(' ')
                ).distinctBy(String::lowercase)
        } else {
            emptyList()
        }
        var retainedExpectedMedia = false
        var retainedDeterministicLocation = false
        var retainedPositiveDocumentKeyword = false

        fun firstPositiveValue(field: ExecutionField): String? {
            fun visit(node: ExecutionNode, subtract: Boolean = false): String? = when (node) {
                is ExecutionNode.Predicate ->
                    node.value.takeIf { !subtract && node.field == field }
                is ExecutionNode.Sorted -> visit(node.value, subtract)
                is ExecutionNode.Binary ->
                    visit(node.left, subtract) ?: visit(
                        node.right,
                        subtract || node.operator == ExecutionBinaryOperator.SUBTRACT,
                    )
            }
            return visit(modelSpec.root)
        }

        val modelDocumentSemantic = firstPositiveValue(ExecutionField.SEMANTIC).orEmpty()
        val documentGenericWords = setOf(
            "a", "an", "the", "how", "much", "what", "is", "are", "was", "were",
            "my", "current", "printed", "print", "cost", "amount", "price", "total",
            "number", "date", "time", "expiry", "expiration", "document",
        ) + ExpiryKeywordPolicy.forbiddenWordsFor(query) +
            SearchKeywordPolicy.forbiddenScaffoldingWords
        fun documentTokens(value: String): List<String> = value
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map(String::trim)
            .filter { it.length >= 2 }
            .distinctBy(String::lowercase)
        val documentSemanticTokens = documentTokens(modelDocumentSemantic.ifBlank { query })
        val documentSemantic = identityDocument?.semanticExpression
            ?: documentSemanticTokens
                .filter { it.lowercase() !in documentGenericWords }
                .joinToString(" ")
                .ifBlank { "document" }
        fun stripNamedPeople(value: String): String {
            var cleaned = value
            (mentionedKnownPeople + namedPeople + excludedNamedPeople)
                .distinctBy(String::lowercase)
                .forEach { label ->
                cleaned = cleaned.replace(
                    Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(label)}(?![\\p{L}\\p{N}])"),
                    " ",
                )
            }
            val representedMediaWords = if (expectedMedia == null) {
                emptySet()
            } else {
                setOf(
                    "photo", "photos", "picture", "pictures", "image", "images",
                    "video", "videos", "clip", "clips", "pdf", "doc",
                    "message", "messages", "sms", "calendar", "contact", "contacts",
                    "call", "calls", "file", "files",
                )
            }
            return QueryLifecycleScaffoldingPolicy.strip(cleaned)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter(String::isNotBlank)
                .filterNot {
                    it.lowercase() in SearchKeywordPolicy.forbiddenScaffoldingWords ||
                        it.lowercase() in setOf("i", "me", "my", "mine", "myself") ||
                        it.lowercase() in representedMediaWords
                }
                .joinToString(" ")
        }

        fun retain(node: ExecutionNode, subtract: Boolean = false): ExecutionNode? = when (node) {
            is ExecutionNode.Predicate -> when (node.field) {
                ExecutionField.QUERY_CATEGORY,
                ExecutionField.ANSWER_NEEDED,
                ExecutionField.FROM_DATE,
                ExecutionField.TO_DATE,
                ExecutionField.PEOPLE_ONLY,
                -> if (subtract && node.field != ExecutionField.PEOPLE_ONLY) node else null
                ExecutionField.PERSON -> if (subtract) {
                    node
                } else if (isDocumentQuery) {
                    // A name printed on an identity document is same-record
                    // lexical evidence, not a face-cluster constraint.
                    null
                } else if (
                    namedPeople.isNotEmpty() ||
                    excludedNamedPeople.any { it.equals(node.value, ignoreCase = true) }
                ) {
                    null
                } else {
                    node
                }
                ExecutionField.MIME_TYPE -> {
                    if (subtract) {
                        node
                    } else if (expectedMedia != null && node.value == expectedMedia.label()) {
                        retainedExpectedMedia = true
                        node
                    } else {
                        null
                    }
                }
                ExecutionField.SEMANTIC -> {
                    if (subtract) {
                        stripNamedPeople(node.value).takeIf(String::isNotBlank)?.let {
                            node.copy(value = it)
                        }
                    } else if (isDocumentQuery || metadataOnly || explicitVisualSemantic.isNotBlank()) null else {
                        stripNamedPeople(node.value).takeIf(String::isNotBlank)?.let {
                            node.copy(value = it)
                        }
                    }
                }
                ExecutionField.OCR -> null
                ExecutionField.LOCATION -> {
                    if (subtract) {
                        node
                    } else if (deterministicLocation != null) {
                        retainedDeterministicLocation = true
                        node.copy(value = deterministicLocation)
                    } else {
                        node
                    }
                }
                ExecutionField.TIME -> {
                    // Relative and numeric date phrases are canonicalized from
                    // the user's query below.  A model-authored TIME value can
                    // be an incorrect endpoint (for example, 2026-12-31 for
                    // "this year") and would intersect the real bounds into
                    // an empty result set. Keep TIME only when no deterministic
                    // date range was derived; subtraction predicates remain
                    // untouched.
                    when {
                        subtract -> node
                        !QueryDateConstraintPolicy.hasExplicitTemporalConstraint(query) -> null
                        dateBounds == null -> node
                        else -> null
                    }
                }
                ExecutionField.KEYWORD -> {
                    if (!subtract && !isDocumentQuery) {
                        // Visual retrieval is semantic plus structured metadata.
                        // OCR keywords would exclude otherwise valid gallery photos.
                        null
                    } else if (!subtract && deterministicDocumentKeywords.isNotEmpty()) {
                        // Identity-document anchors are finite and explicit in
                        // the query. Replace unstable model variants below.
                        null
                    } else {
                        val kept = node.value
                            .split(Regex("[^\\p{L}\\p{N}]+"))
                            .filter {
                                it.isNotBlank() &&
                                    it.lowercase() !in SearchKeywordPolicy.genericRecordWords &&
                                    it.lowercase() !in SearchKeywordPolicy.forbiddenScaffoldingWords &&
                                    it.lowercase() !in answerAttributeWords &&
                                    it.lowercase() !in ExpiryKeywordPolicy.forbiddenWordsFor(query) &&
                                    (isDocumentQuery || it.lowercase() !in structuredPersonKeywordWords)
                            }
                        val selfGrounded = if (!subtract && isDocumentQuery) {
                            (documentSelfKeywordWords + kept).distinctBy(String::lowercase)
                        } else {
                            kept
                        }
                        selfGrounded.takeIf { it.isNotEmpty() }?.let {
                            if (!subtract && isDocumentQuery) retainedPositiveDocumentKeyword = true
                            node.copy(value = it.joinToString(" "))
                        }
                    }
                }
            }
            // Sorting is a deterministic finite constraint. Ignore a model-
            // authored wrapper and add only the sort required by the query.
            is ExecutionNode.Sorted -> retain(node.value, subtract)
            is ExecutionNode.Binary -> {
                val left = retain(node.left, subtract)
                val right = retain(
                    node.right,
                    subtract || node.operator == ExecutionBinaryOperator.SUBTRACT,
                )
                when {
                    left != null && right != null -> ExecutionNode.Binary(left, node.operator, right)
                    left != null -> left
                    right != null && node.operator != ExecutionBinaryOperator.SUBTRACT -> right
                    else -> null
                }
            }
        }

        fun intersect(left: ExecutionNode?, right: ExecutionNode): ExecutionNode =
            left?.let { ExecutionNode.Binary(it, ExecutionBinaryOperator.INTERSECT, right) } ?: right

        var root: ExecutionNode? = retain(modelSpec.root)
        if (isDocumentQuery) {
            root = intersect(
                root,
                ExecutionNode.Predicate(
                    ExecutionField.SEMANTIC,
                    stripNamedPeople(documentSemantic).ifBlank { "document" },
                ),
            )
        }
        if (explicitVisualSemantic.isNotBlank()) {
            root = intersect(
                root,
                ExecutionNode.Predicate(ExecutionField.SEMANTIC, explicitVisualSemantic),
            )
        }
        if (deterministicDocumentKeywords.isNotEmpty()) {
            root = intersect(
                root,
                ExecutionNode.Predicate(
                    ExecutionField.KEYWORD,
                    deterministicDocumentKeywords.joinToString(" "),
                ),
            )
        } else if (documentSelfKeywordWords.isNotEmpty() && !retainedPositiveDocumentKeyword) {
            root = intersect(
                root,
                ExecutionNode.Predicate(
                    ExecutionField.KEYWORD,
                    documentSelfKeywordWords.joinToString(" "),
                ),
            )
        }
        namedPeople.forEach { label ->
            root = intersect(root, ExecutionNode.Predicate(ExecutionField.PERSON, label))
        }
        fun hasSubtractedPerson(node: ExecutionNode?, label: String, subtract: Boolean = false): Boolean =
            when (node) {
                null -> false
                is ExecutionNode.Predicate ->
                    subtract && node.field == ExecutionField.PERSON &&
                        node.value.equals(label, ignoreCase = true)
                is ExecutionNode.Sorted -> hasSubtractedPerson(node.value, label, subtract)
                is ExecutionNode.Binary ->
                    hasSubtractedPerson(node.left, label, subtract) ||
                        hasSubtractedPerson(
                            node.right,
                            label,
                            subtract || node.operator == ExecutionBinaryOperator.SUBTRACT,
                        )
            }
        excludedNamedPeople.filterNot { label -> hasSubtractedPerson(root, label) }.forEach { label ->
            root = ExecutionNode.Binary(
                requireNotNull(root),
                ExecutionBinaryOperator.SUBTRACT,
                ExecutionNode.Predicate(ExecutionField.PERSON, label),
            )
        }
        if (QueryStructuredIntentPolicy.requiresExclusivePeople(query)) {
            namedPeople.forEach { label ->
                root = intersect(root, ExecutionNode.Predicate(ExecutionField.PEOPLE_ONLY, label))
            }
        }
        if (expectedMedia != null && !retainedExpectedMedia) {
            root = intersect(root, ExecutionNode.Predicate(ExecutionField.MIME_TYPE, expectedMedia.label()))
        }
        if (deterministicLocation != null && !retainedDeterministicLocation) {
            root = intersect(root, ExecutionNode.Predicate(ExecutionField.LOCATION, deterministicLocation))
        }
        dateBounds?.let { (from, to) ->
            if (from.isNotBlank()) root = intersect(root, ExecutionNode.Predicate(ExecutionField.FROM_DATE, from))
            if (to.isNotBlank()) root = intersect(root, ExecutionNode.Predicate(ExecutionField.TO_DATE, to))
        }
        if (root == null) {
            root = ExecutionNode.Predicate(ExecutionField.SEMANTIC, "all")
        }
        var normalizedRoot = requireNotNull(root) { "QP must contain at least one field" }
        if (QuerySortConstraintPolicy.requiresDateSort(query, expectedCategory)) {
            normalizedRoot = ExecutionNode.Sorted(normalizedRoot, ExecutionSort.DATE)
        }
        if (QuerySortConstraintPolicy.requiresLocationSort(query)) {
            normalizedRoot = ExecutionNode.Sorted(normalizedRoot, ExecutionSort.LOCATION)
        }
        return QueryExecutionSpec(normalizedRoot)
    }

    internal fun validatePlannerOcrSyntax(candidate: String) {
        require(!Regex("(?i)\\[(?:answer_needed|ocr)\\s*==").containsMatchIn(candidate)) {
            "answer_needed and ocr are not supported QP fields"
        }
    }

    private fun validatePlannerFields(root: ExecutionNode) {
        val allowed = setOf(
            ExecutionField.PERSON,
            ExecutionField.LOCATION,
            ExecutionField.TIME,
            ExecutionField.SEMANTIC,
            ExecutionField.KEYWORD,
            ExecutionField.MIME_TYPE,
        )
        fun visit(node: ExecutionNode) {
            when (node) {
                is ExecutionNode.Predicate -> require(node.field in allowed) {
                    "Unsupported QP field '${node.field.wireName}'. Allowed fields: person, location, time, semantic, keyword, mime type"
                }
                is ExecutionNode.Sorted -> visit(node.value)
                is ExecutionNode.Binary -> {
                    visit(node.left)
                    visit(node.right)
                }
            }
        }
        visit(root)
    }

    /** Re-runs every post-parse production validator against a compiled plan. */
    internal fun validateCompiledPlan(
        query: String,
        knownPersonLabels: List<String>,
        plan: QueryPlan,
        selfPersonLabel: String? = null,
    ) {
        (plan.personNames + plan.excludedPersonNames).forEach { candidate ->
            val presentVerbatim = containsPhrase(query, candidate)
            val symbolicRelationship = symbolicRelationshipAppearsInQuery(query, candidate)
            val knownCorrection = knownPersonLabels.any { it.equals(candidate, ignoreCase = true) } &&
                QuerySpellingMatcher.isPlausibleCorrection(query, candidate)
            val selfAlias = selfPersonLabel?.equals(candidate, ignoreCase = true) == true &&
                plan.queryCategory != QueryCategory.DOC &&
                SelfPersonQueryPolicy.referencesSelfForCategory(query, plan.queryCategory)
            require(presentVerbatim || symbolicRelationship || knownCorrection || selfAlias) {
                "Person '$candidate' is neither present in the query nor a close Known people correction"
            }
        }
        plan.locationHint.takeIf(String::isNotBlank)?.let { location ->
            require(
                containsPhrase(query, location) ||
                    QuerySpellingMatcher.isPlausibleLocationCorrection(query, location),
            ) {
                "Location '$location' is not a spelling correction of a query value"
            }
        }
        validateSemanticValues(plan)
        validateTimeValue(plan)
        if (plan.timeHint.isNotBlank()) {
            require(QueryDateConstraintPolicy.hasExplicitTemporalConstraint(query)) {
                "time is forbidden because the resolved user query has no explicit temporal constraint"
            }
        }
        validateKeywordValues(plan)
        validateRequestedAnswerAttributes(query, plan)
        require(plan.ocrTerms.isEmpty() && plan.excludedOcrTerms.isEmpty()) {
            "Compact QP does not support legacy OCR predicates"
        }

        QueryStructuredIntentPolicy.expectedMediaType(query)?.let { expectedMedia ->
            require(plan.mediaType == expectedMedia) {
                "Explicit media request requires [mime type == ${expectedMedia.label()}]"
            }
        }
        if (SelfPersonQueryPolicy.referencesSelfForCategory(query, plan.queryCategory)) {
            require(!selfPersonLabel.isNullOrBlank()) {
                "Self-reference requires a tagged self person"
            }
        }
        QueryCategoryConstraintPolicy.validate(query, plan)
        QueryStructuredIntentPolicy.validate(query, knownPersonLabels, plan, selfPersonLabel)
        QuerySortConstraintPolicy.validate(query, plan)
    }

    private fun validateTimeValue(plan: QueryPlan) {
        val value = plan.timeHint.trim()
        if (value.isBlank()) return
        val actualTime = Regex(
            "(?i)(?:\\b\\d{4}(?:-\\d{2}(?:-\\d{2})?)?\\b|\\b(?:today|yesterday|tomorrow|last|this|next|previous)\\b|" +
                "\\b(?:morning|afternoon|evening|night|noon|midnight)\\b|\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\b)",
        )
        require(actualTime.containsMatchIn(value)) {
            "time must contain an actual date, period, or time of day, not '$value'"
        }
    }

    private fun validateKeywordValues(plan: QueryPlan) {
        val mimeWords = setOf(
            "photo", "photos", "video", "videos", "pdf", "doc", "document", "documents",
            "message", "messages", "sms", "text", "texts", "calendar", "calendars",
            "appointment", "appointments", "contact", "contacts", "call", "calls",
            "call_log", "call_logs", "file", "files",
        )
        plan.keywordTerms.forEach { value ->
            val words = value.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter(String::isNotBlank)
            require(words.isNotEmpty()) { "keyword must contain one or more words" }
            require(words.none { it.lowercase() in SearchKeywordPolicy.forbiddenScaffoldingWords }) {
                "keyword must not contain grammar or question scaffolding words"
            }
            require(words.none { it.lowercase() in mimeWords }) {
                "keyword must not contain MIME type words"
            }
            require(words.none { it.lowercase() in setOf("number", "numbers", "num", "no") }) {
                "keyword must not contain a number-reference word"
            }
            require(words.none {
                    it.lowercase() in setOf("person", "people", "self", "me", "my", "mine", "myself", "owner")
                }
            ) {
                "keyword must contain real searchable entities, not self/person aliases"
            }
        }
    }

    private fun validateRequestedAnswerAttributes(query: String, plan: QueryPlan) {
        val normalized = query.lowercase()
        val requestedAttributes = answerAttributeWords.filter { word ->
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(normalized)
        }.toSet()
        val expiryAttributes = ExpiryKeywordPolicy.forbiddenWordsFor(query)
        if (requestedAttributes.isEmpty() && expiryAttributes.isEmpty()) return
        val keywordWords = plan.keywordTerms
            .flatMap { it.split(Regex("[^\\p{L}\\p{N}]+")) }
            .filter(String::isNotBlank)
            .map(String::lowercase)
        val forbiddenKeywordAttributes = requestedAttributes + expiryAttributes
        require(keywordWords.none { it in forbiddenKeywordAttributes }) {
            "keyword must describe the record, not requested answer attributes: " +
                forbiddenKeywordAttributes.joinToString(", ")
        }
        val semanticWords = plan.semanticQueries
            .flatMap { it.split(Regex("[^\\p{L}\\p{N}]+")) }
            .filter(String::isNotBlank)
            .map(String::lowercase)
        require(semanticWords.none { it in requestedAttributes }) {
            "semantic must describe the record, not requested answer attributes: " +
                requestedAttributes.joinToString(", ")
        }
    }

    private fun symbolicRelationshipAppearsInQuery(query: String, candidate: String): Boolean {
        val symbol = candidate.trim()
        if (!symbol.startsWith("{_") || !symbol.endsWith("_}")) return false
        val relationship = symbol.removePrefix("{_").removeSuffix("_}").lowercase()
        val aliases = mapOf(
            "mom" to setOf("mom", "mother", "mon"),
            "dad" to setOf("dad", "father", "dady", "daddy"),
            "sister" to setOf("sister", "sis"),
            "brother" to setOf("brother", "bro"),
            "wife" to setOf("wife"),
            "husband" to setOf("husband"),
            "daughter" to setOf("daughter"),
            "son" to setOf("son"),
            "grandmother" to setOf("grandmother", "grandma", "granny"),
            "grandfather" to setOf("grandfather", "grandpa"),
        )
        val normalizedQuery = query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        return aliases[relationship].orEmpty().any { alias ->
            Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(normalizedQuery)
        }
    }

    internal fun validateSemanticValues(plan: QueryPlan) {
        (plan.semanticQueries + plan.negativeSemanticQueries).forEach { semantic ->
            val tokens = semantic.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter(String::isNotBlank)
            require(tokens.isNotEmpty()) { "semantic must contain a searchable word or phrase" }
            val forbidden = tokens.filter(semanticForbiddenWords::contains)
            require(forbidden.isEmpty()) {
                "semantic contains routing or structured words: ${forbidden.distinct().joinToString(", ")}"
            }
            val lifecycle = tokens.filter(QueryLifecycleScaffoldingPolicy.forbiddenWords::contains)
            require(lifecycle.isEmpty()) {
                "semantic contains file lifecycle scaffolding: ${lifecycle.distinct().joinToString(", ")}"
            }
            require(!isoDateToken.containsMatchIn(semantic)) {
                "semantic must not contain dates or years"
            }
            (plan.personNames + plan.excludedPersonNames)
                .filter(String::isNotBlank)
                .forEach { person ->
                    require(!containsPhrase(semantic, person)) {
                        "semantic repeats structured person '$person'"
                    }
                }
            plan.locationHint.takeIf(String::isNotBlank)?.let { location ->
                require(!containsPhrase(semantic, location)) {
                    "semantic repeats structured location '$location'"
                }
            }
        }
    }

    internal fun validateOcrKeywords(plan: QueryPlan) {
        require(
            (plan.ocrTerms + plan.excludedOcrTerms).isEmpty() ||
                plan.queryCategory == QueryCategory.DOC,
        ) {
            "ocr predicates are allowed only for query_category doc"
        }
        plan.ocrTerms.forEach { value ->
            val count = OcrKeywordPolicy.keywordCount(value)
            require(count in 2..OcrKeywordPolicy.MAX_KEYWORDS) {
                "positive ocr requires 2-${OcrKeywordPolicy.MAX_KEYWORDS} searchable keywords"
            }
        }
        plan.excludedOcrTerms.forEach { value ->
            val count = OcrKeywordPolicy.keywordCount(value)
            require(count in 1..OcrKeywordPolicy.MAX_KEYWORDS) {
                "negative ocr requires 1-${OcrKeywordPolicy.MAX_KEYWORDS} searchable keywords"
            }
        }
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val normalizedText = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalizedPhrase = phrase.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalizedPhrase.isNotBlank() &&
            " $normalizedText ".contains(" $normalizedPhrase ")
    }
}

internal data class PreviousQueryTurn(
    val query: String,
    val answer: String,
)

/** Supplies one prior grounded turn only when the current wording is context-dependent. */
internal object FollowUpQueryContextPolicy {
    private val INDIRECT_REFERENCE = Regex(
        "(?i)\\b(?:it|its|that|this|those|these|them|they|same|previous|former|latter)\\b",
    )
    private val DOCUMENT_ANCHOR = Regex(
        "(?i)\\b(?:passport|aadhaa?r(?:d)?|ssn|social\\s+security|pan(?:\\s+card)?|" +
            "driving\\s+licen[cs]e|licen[cs]e|receipt|bill|invoice|ticket|policy|certificate)\\b",
    )
    private val FOLLOW_UP_FIELD = Regex(
        "(?i)\\b(?:expir(?:e|es|ed|ing|y|ation)|valid(?:ity|\\s+until)|issue(?:d)?|" +
            "number|name|address|date|amount|total|cost|price|when|where|who)\\b",
    )
    private val FIELD_ONLY_WORDS = setOf(
        "a", "an", "the", "what", "which", "when", "where", "who", "whose", "how",
        "is", "are", "was", "were", "does", "do", "did", "it", "its", "this", "that",
        "expiry", "expire", "expires", "expired", "expiring", "expiration", "valid",
        "validity", "until", "issue", "issued", "number", "name", "holder", "address",
        "date", "birth", "dob", "amount", "total", "cost", "price", "merchant", "status",
        "of", "on", "in", "at", "for", "to",
    )

    fun shouldResolve(
        currentQuery: String,
        previousQuery: String,
        knownPersonLabels: List<String> = emptyList(),
    ): Boolean {
        if (currentQuery.isBlank() || previousQuery.isBlank()) return false
        if (INDIRECT_REFERENCE.containsMatchIn(currentQuery)) return true
        val hasExplicitPerson = knownPersonLabels.any { label ->
            containsPhrase(currentQuery, label)
        }
        if (hasExplicitPerson) return false
        return FOLLOW_UP_FIELD.containsMatchIn(currentQuery) &&
            DOCUMENT_ANCHOR.containsMatchIn(previousQuery) &&
            (DOCUMENT_ANCHOR.containsMatchIn(currentQuery) || isFieldOnly(currentQuery))
    }

    private fun isFieldOnly(query: String): Boolean {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase()).map { it.value }.toList()
        return words.isNotEmpty() && words.all { it in FIELD_ONLY_WORDS }
    }

    fun resolveFieldOnly(
        currentQuery: String,
        previousQuery: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
    ): String? {
        if (!isFieldOnly(currentQuery) || !DOCUMENT_ANCHOR.containsMatchIn(previousQuery)) return null
        val anchor = DOCUMENT_ANCHOR.find(previousQuery)?.value?.trim() ?: return null
        val owner = when {
            SelfPersonQueryPolicy.referencesSelf(previousQuery) -> "my"
            else -> knownPersonLabels.firstOrNull { containsPhrase(previousQuery, it) }.orEmpty()
        }
        val ownedDocument = listOf(owner, anchor).filter(String::isNotBlank).joinToString(" ")
        val normalized = currentQuery.lowercase()
        val field = when {
            Regex("\\b(?:expir|valid)").containsMatchIn(normalized) -> "expiry date"
            Regex("\\baddress\\b").containsMatchIn(normalized) -> "address"
            Regex("\\b(?:date of birth|birth|dob)\\b").containsMatchIn(normalized) -> "date of birth"
            Regex("\\bissu(?:e|ed)\\b").containsMatchIn(normalized) -> "issue date"
            Regex("\\b(?:number|no)\\b").containsMatchIn(normalized) -> "number"
            Regex("\\b(?:holder|whose|name)\\b").containsMatchIn(normalized) -> "holder name"
            Regex("\\b(?:amount|total|cost|price)\\b").containsMatchIn(normalized) -> "total amount"
            Regex("\\bmerchant\\b").containsMatchIn(normalized) -> "merchant"
            Regex("\\bstatus\\b").containsMatchIn(normalized) -> "status"
            Regex("\\bdate\\b").containsMatchIn(normalized) -> "date"
            else -> return null
        }
        return "What is the $field on $ownedDocument?"
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalizedText = normalize(text)
        val normalizedPhrase = normalize(phrase)
        return normalizedPhrase.isNotBlank() &&
            " $normalizedText ".contains(" $normalizedPhrase ")
    }
}

/** Repairs only E2B's common flat serialization; retrieval intent stays untouched. */
internal object PlannerExpressionSyntaxPolicy {
    private val FIELD = Regex(
        "(?i)(?:person|location|time|semantic|keyword|mime\\s+type)\\s*==",
    )

    fun canonicalize(expression: String): String {
        val trimmed = expression.trim().removePrefix("PLAN:").trim()
        if (runCatching { QueryExecutionSpec.parse(trimmed) }.isSuccess) return trimmed
        // This recovery is intentionally limited to a linear intersection. It
        // must never guess how malformed subtraction, alternatives, or fusion
        // should be grouped.
        if (Regex("\\s[-+,]\\s").containsMatchIn(trimmed)) return trimmed
        val fields = FIELD.findAll(trimmed).toList()
        if (fields.isEmpty()) return trimmed
        val prefix = trimmed.substring(0, fields.first().range.first)
            .replace("[", "")
            .trim()
        if (prefix.isNotEmpty()) return trimmed
        val predicates = fields.mapIndexed { index, match ->
            val end = fields.getOrNull(index + 1)?.range?.first ?: trimmed.length
            val value = trimmed.substring(match.range.last + 1, end)
                .trim()
                .trimEnd('[')
                .trim()
                .removeSuffix("&&")
                .trim()
                .trimEnd(']')
                .trim()
            require(value.isNotBlank()) { "Flat planner predicate has no value" }
            val field = match.value.substringBefore("==").trim().lowercase()
                .replace(Regex("\\s+"), " ")
            "[$field == $value]"
        }
        return predicates.joinToString(" && ")
    }
}

internal data class ContextualPlannerOutput(
    val resolvedQuery: String,
    val expression: String,
)

/** Parses and bounds E2B's contextual rewrite before normal QP validation runs. */
internal object ContextualPlannerOutputPolicy {
    private val CONTEXTUAL_OUTPUT = Regex(
        "(?is)^\\s*RESOLVED_QUERY:\\s*(.+?)\\s+PLAN:\\s*(.+?)\\s*$",
    )
    private val UNRESOLVED_REFERENCE = Regex(
        "(?i)\\b(?:it|its|that|this|those|these|them|same|previous)\\b",
    )

    fun parse(
        output: String,
        currentQuery: String,
        previousTurn: PreviousQueryTurn?,
        knownPersonLabels: List<String>,
        selfPersonLabel: String? = null,
    ): ContextualPlannerOutput {
        val match = CONTEXTUAL_OUTPUT.matchEntire(output)
        if (match == null) {
            // Accept the legacy expression-only shape for an independent query
            // so a harmless formatting miss cannot blank the QP. For a
            // contextual turn, a PLAN-only response is still grounded with
            // the previous question; the previous answer is never copied.
            val expression = output.trim().removePrefix("PLAN:").trim()
            require(expression.isNotBlank() && "==" in expression) {
                "Planner expression is empty or missing predicates"
            }
            if (previousTurn == null) {
                return ContextualPlannerOutput(currentQuery, expression)
            }
            val boundedCurrent = currentQuery.take(120).trim()
            val boundedPrevious = previousTurn.query.take(120).trim()
            return ContextualPlannerOutput(
                resolvedQuery = FollowUpQueryContextPolicy.resolveFieldOnly(
                    currentQuery,
                    previousTurn.query,
                    knownPersonLabels,
                    selfPersonLabel,
                ) ?: "$boundedCurrent for $boundedPrevious".take(256),
                expression = expression,
            )
        }
        val generatedResolved = match.groupValues[1]
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(256)
        // E2B may improve spelling in its plan, but an independent query's
        // explicit me/my/person wording is authoritative for finite scopes.
        val resolved = if (previousTurn == null) {
            currentQuery
        } else {
            FollowUpQueryContextPolicy.resolveFieldOnly(
                currentQuery,
                previousTurn.query,
                knownPersonLabels,
                selfPersonLabel,
            ) ?: generatedResolved
        }
        val expression = match.groupValues[2].trim()
        require(resolved.isNotBlank() && expression.isNotBlank()) {
            "Contextual follow-up rewrite and plan must both be non-empty"
        }
        require('[' !in resolved && ']' !in resolved && "PLAN:" !in resolved.uppercase()) {
            "Resolved follow-up must be a natural standalone query"
        }
        val priorQuery = previousTurn?.query.orEmpty()
        val allowedNumbers = Regex("\\d{4,}")
            .findAll("$currentQuery $priorQuery")
            .map { it.value }
            .toSet()
        require(Regex("\\d{4,}").findAll(resolved).all { it.value in allowedNumbers }) {
            "Resolved follow-up must not copy identifier-like values from the previous answer"
        }
        val selfWasReferenced = !selfPersonLabel.isNullOrBlank() &&
            (SelfPersonQueryPolicy.referencesSelf(currentQuery) ||
                SelfPersonQueryPolicy.referencesSelf(priorQuery))
        val allowedPeople = knownPersonLabels.filter { label ->
            containsPhrase(currentQuery, label) || containsPhrase(priorQuery, label)
        }.toMutableList().apply {
            if (selfWasReferenced && none { it.equals(selfPersonLabel, ignoreCase = true) }) {
                add(requireNotNull(selfPersonLabel))
            }
        }
        knownPersonLabels.filter { containsPhrase(resolved, it) }.forEach { label ->
            require(allowedPeople.any { it.equals(label, ignoreCase = true) }) {
                "Resolved follow-up introduced unrelated person '$label'"
            }
        }
        val omittedPriorPeople = if (previousTurn == null) {
            emptyList()
        } else {
            allowedPeople.filterNot { containsPhrase(currentQuery, it) }
        }
        if (omittedPriorPeople.isNotEmpty()) {
            require(
                omittedPriorPeople.any { label ->
                    containsPhrase(resolved, label) ||
                        (label.equals(selfPersonLabel, ignoreCase = true) &&
                            SelfPersonQueryPolicy.referencesSelf(resolved))
                },
            ) {
                "Resolved follow-up must carry its omitted person reference"
            }
        }
        if (previousTurn != null && UNRESOLVED_REFERENCE.containsMatchIn(currentQuery)) {
            require(!UNRESOLVED_REFERENCE.containsMatchIn(resolved)) {
                "Resolved follow-up still contains an indirect reference"
            }
        }
        return ContextualPlannerOutput(resolved, expression)
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val normalizedText = normalize(text)
        val normalizedPhrase = normalize(phrase)
        return normalizedPhrase.isNotBlank() &&
            " $normalizedText ".contains(" $normalizedPhrase ")
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
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

/** Canonical anchors for written identity records, independent of the requested field. */
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

/**
 * Validates only answer-routing invariants that are explicit in the user's
 * interrogative. Gemma remains the sole plan author; a mismatch is rejected
 * and sent back through the model's repair turn.
 */
internal object QueryCategoryConstraintPolicy {
    fun validate(query: String, plan: QueryPlan) {
        val expected = expectedCategory(query)
        require(plan.queryCategory == expected) {
            "query_category must be ${expected.wireName} because the requested answer is " +
                answerLabel(expected)
        }
    }

    internal fun expectedCategory(query: String): QueryCategory {
        val normalized = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (isWrittenDocumentAnswer(normalized)) return QueryCategory.DOC
        if (
            Regex("^(?:who|whose)\\b|^which\\s+(?:person|people|persons)\\b")
                .containsMatchIn(normalized)
        ) {
            return QueryCategory.PERSON
        }
        if (
            Regex(
                "^(?:where|what\\s+(?:place|places|location|locations)|how\\s+many\\s+(?:places|locations|cities)|" +
                    "which\\s+(?:place|places|location|locations|city|cities|national\\s+parks?))\\b",
            ).containsMatchIn(normalized) ||
            Regex("^which\\s+(?:photos?|pictures?|images?)\\b.*\\b(?:in|at|from)\\b")
                .containsMatchIn(normalized)
        ) {
            return QueryCategory.LOCATION
        }
        if (
            Regex(
                "^(?:when|what\\s+(?:date|time)|which\\s+(?:date|dates|day|days)|" +
                    "on\\s+which\\s+(?:date|dates|day|days))\\b",
            ).containsMatchIn(normalized) ||
            Regex("\\bon\\s+which\\s+(?:date|dates|day|days)\\b")
                .containsMatchIn(normalized)
        ) {
            return QueryCategory.TIME
        }
        return QueryCategory.SCENARY
    }

    private fun isWrittenDocumentAnswer(query: String): Boolean {
        // These are document/text searches even when the user does not phrase
        // the request as a specific printed field (for example, "insurance"
        // or "property tax"). They must never fall into visual scenery.
        if (
            Regex(
                "\\b(?:expir(?:e|es|ed|ing|y|ation)|validity|valid\\s+(?:until|till|through|to))\\b",
            ).containsMatchIn(query)
        ) return true
        if (Regex(
                "\\b(?:message|messages|texted|text\\s+message|sms|calendar|appointment|meeting|" +
                    "contact|contacts|phone\\s+number|call\\s+log|called|call|file|files|pdf|docx?|" +
                    "spreadsheet|note|notes)\\b",
            ).containsMatchIn(query)
        ) return true
        if (
            Regex(
                "\\b(?:insurance|polic(?:y|ies)|tax|property\\s+tax|receipts?|recipts?|bills?|invoices?|" +
                    "sale\\s+deeds?|government\\s+(?:id|ids|identification)|govt\\.?\\s*(?:id|ids)|" +
                    "identification(?:\\s+(?:doc|docs|document|documents))?|coupons?|tickets?|" +
                    "marks?|mark\\s*sheet|report\\s*card|transcript|certificates?|cerficiates?|" +
                    "passport|licen[cs]e|identity\\s+card|id\\s+card|aadhaa?r(?:d)?|pan\\s+card)\\b",
            ).containsMatchIn(query)
        ) {
            return true
        }
        if (
            Regex("\\bhow\\s+much\\b|\\b(?:spend|spent|paid|payment|cost|price|total|amount)\\b")
                .containsMatchIn(query)
        ) {
            return true
        }
        if (Regex("\\brestaurant\\s+name\\b").containsMatchIn(query)) return true
        if (
            Regex(
                "\\b(?:passport|driving\\s+licen[cs]e|driver'?s?\\s+licen[cs]e|dl|" +
                    "ssn|social\\s+security(?:\\s+number)?|pan\\s+card|" +
                    "aadhaa?r(?:d)?(?:\\s+card)?|identity\\s+card|id\\s+card|" +
                    "mark\\s*sheet|report\\s+card|transcript)\\b",
            ).containsMatchIn(query) ||
            Regex(
                "\\b(?:wi[ -]?fi\\s+password|password|passcode|user\\s*id|username|" +
                    "login\\s*id|account\\s*id|dob|date\\s+of\\s+birth|birth\\s+date|" +
                    "id\\s+(?:number|no)|social\\s+security\\s+number)\\b",
            ).containsMatchIn(query) ||
            Regex("\\bhow\\s+old\\b|\\bage\\b|\\b(?:exam\\s+)?marks?\\b|\\bgrades?\\b")
                .containsMatchIn(query)
        ) {
            return true
        }
        val document = Regex(
            "\\b(?:screenshot|receipt|bill|invoice|ticket|passport|licen[cs]e|coupon|" +
                "voucher|menu|document|booking|boarding\\s+pass|hotel\\s+card|" +
                "identity\\s+card|id\\s+card|mark\\s*sheet|report\\s+card)\\b",
        ).containsMatchIn(query)
        val writtenField = Regex(
            "\\b(?:read|written|text|total|amount|cost|price|number|code|password|" +
                "reference|gate|account|name|expir(?:e|es|ed|y|ation)|valid\\s+until)\\b",
        ).containsMatchIn(query)
        return document && writtenField
    }

    private fun answerLabel(category: QueryCategory): String = when (category) {
        QueryCategory.DOC -> "written document content"
        QueryCategory.SCENARY -> "visual scene content"
        QueryCategory.PERSON -> "a person or people"
        QueryCategory.LOCATION -> "a place or location"
        QueryCategory.TIME -> "an event date or time"
    }
}

/**
 * Ensures that explicit hard constraints were not silently omitted or moved
 * into semantic text. This policy never adds predicates to a plan.
 */
internal object QueryStructuredIntentPolicy {
    fun validate(
        query: String,
        knownPersonLabels: List<String>,
        plan: QueryPlan,
        selfPersonLabel: String? = null,
    ) {
        expectedMediaType(query)?.let { expected ->
            require(plan.mediaType == expected) {
                "Explicit media request requires [mime type == ${expected.label()}]"
            }
        }
        val mentionedKnownPeople = knownPersonLabels.asSequence()
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .filter { label ->
                containsPhrase(query, label) ||
                    QuerySpellingMatcher.isPlausibleCorrection(query, label)
            }
            .toList()
        if (Regex("(?i)\\b(?:birthday|birth\\s+day)\\b").containsMatchIn(query)) {
            require(plan.semanticQueries.any { containsPhrase(it, "birthday") || containsPhrase(it, "birth day") }) {
                "Birthday queries require birthday as visual semantic evidence"
            }
            mentionedKnownPeople.forEach { label ->
                require(plan.keywordTerms.none { containsPhrase(it, label) }) {
                    "Birthday subject '$label' must use person scope, not same-record keywords"
                }
            }
        }
        if (plan.semanticQueries.isNotEmpty() && plan.keywordTerms.isNotEmpty()) {
            require(hasIntersectedSemanticAndKeyword(plan.executionSpec?.root)) {
                "Semantic and keyword predicates must be joined with && so both match the same record"
            }
        }
        if (plan.queryCategory == QueryCategory.DOC) {
            require(plan.semanticQueries.size == 1 && plan.ocrTerms.isEmpty()) {
                "Every document query requires one semantic predicate and no legacy OCR predicate"
            }
            require(plan.keywordTerms.size <= 1) {
                "All document keywords must be emitted in one same-record keyword predicate"
            }
            mentionedKnownPeople.forEach { label ->
                require(
                    plan.keywordTerms.any { keywords -> containsPhrase(keywords, label) },
                ) {
                    "Document subject '$label' must remain in the same-record keywords"
                }
                if (!explicitlyRequestsFacePresence(query, label)) {
                    require(plan.personNames.none { it.equals(label, ignoreCase = true) }) {
                        "Document subject '$label' must not become a face/person predicate"
                    }
                }
            }
        } else {
            mentionedKnownPeople.forEach { label ->
                if (isNegatedMention(query, label)) {
                    require(plan.excludedPersonNames.any { it.equals(label, ignoreCase = true) }) {
                        "Negated known person '$label' must use subtraction"
                    }
                } else {
                    require(
                        plan.personNames.any { it.equals(label, ignoreCase = true) } &&
                            plan.excludedPersonNames.none { it.equals(label, ignoreCase = true) },
                    ) {
                        "Mentioned known person '$label' must be a positive person predicate " +
                            "and must not be subtracted"
                    }
                    require(plan.keywordTerms.none { containsPhrase(it, label) }) {
                        "Structured person '$label' must not be repeated in keyword"
                    }
                }
            }
        }

        if (SelfPersonQueryPolicy.referencesSelfForCategory(query, plan.queryCategory)) {
            val selfLabel = requireNotNull(selfPersonLabel?.takeIf(String::isNotBlank)) {
                "This self-reference requires the user to identify 'This is me' in face tagging"
            }
            if (plan.queryCategory == QueryCategory.DOC) {
                require(plan.personNames.none { it.equals(selfLabel, ignoreCase = true) }) {
                    "Document self-reference must not use a face/person predicate"
                }
                require(plan.keywordTerms.any { containsPhrase(it, selfLabel) }) {
                    "Document self-reference must use '$selfLabel' in same-record keywords"
                }
            } else if (SelfPersonQueryPolicy.isNegatedSelfReference(query)) {
                require(
                    plan.excludedPersonNames.any { it.equals(selfLabel, ignoreCase = true) },
                ) {
                    "A negated I/me/my reference must subtract Self person '$selfLabel'"
                }
            } else {
                require(
                    plan.personNames.any { it.equals(selfLabel, ignoreCase = true) } &&
                        plan.excludedPersonNames.none { it.equals(selfLabel, ignoreCase = true) },
                ) {
                    "A presence/identity use of I/me/my must use Self person '$selfLabel'"
                }
                require(plan.keywordTerms.none { containsPhrase(it, selfLabel) }) {
                    "Non-document self-reference must use person scope, not keyword '$selfLabel'"
                }
            }
        }

        if (requiresExclusivePeople(query)) {
            require(plan.onlyPersonNames.isNotEmpty()) {
                "Exclusive person intent requires a people_only predicate"
            }
            require(plan.onlyPersonNames.all { only ->
                plan.personNames.any { it.equals(only, ignoreCase = true) }
            }) { "people_only must contain only positive person predicates" }
            require(
                plan.onlyPersonNames.map(String::lowercase).toSet() ==
                    plan.personNames.map(String::lowercase).toSet(),
            ) { "people_only must contain the complete positive person set" }
            require(plan.mediaType == QueryMediaType.PHOTOS) {
                "Exclusive person gallery searches require photos"
            }
        }

        val explicitLocations = explicitLocationCandidates(query, knownPersonLabels)
        if (explicitLocations.size == 1) {
            val expected = explicitLocations.single()
            if (isNegatedValueMention(query, expected)) {
                require(
                    hasSubtractedPredicate(
                        plan.executionSpec,
                        ExecutionField.LOCATION,
                        expected,
                    ),
                ) {
                    "Negated place '$expected' requires a subtraction location predicate"
                }
            } else {
                require(
                    plan.locationHint.isNotBlank() &&
                        (
                            plan.locationHint.equals(expected, ignoreCase = true) ||
                                QuerySpellingMatcher.areClosePhrases(expected, plan.locationHint)
                            ),
                ) {
                    "Explicit place '$expected' requires a location predicate"
                }
            }
        }
        if (NEGATION_MARKER.containsMatchIn(query)) {
            require(
                plan.excludedPersonNames.isNotEmpty() ||
                    plan.negativeSemanticQueries.isNotEmpty() ||
                    hasAnySubtraction(plan.executionSpec),
            ) {
                "Explicit exclusion requires a subtraction predicate"
            }
        }
        if (EVENT_DISCOVERY_TERM.containsMatchIn(query)) {
            require(plan.semanticQueries.isNotEmpty()) {
                "The named event or activity requires a positive semantic predicate"
            }
        }
        if (isMetadataOnlyIntent(query) || isPersonLocationMediaOnly(query, knownPersonLabels)) {
            require(plan.semanticQueries.isEmpty()) {
                "Metadata-only co-occurrence or place-list intent forbids a positive semantic predicate"
            }
        }
        if (isPersonLocationMediaOnly(query, knownPersonLabels)) {
            require(plan.keywordTerms.isEmpty()) {
                "Person/location media lookup must use metadata scopes, not OCR keywords"
            }
        }
    }

    internal fun expectedMediaType(query: String): QueryMediaType? {
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
            .map(String::lowercase)
        val asksPhotos = tokens.any { token ->
            listOf("photo", "photos", "picture", "pictures", "image", "images")
                .any { canonical -> QuerySpellingMatcher.areClosePhrases(token, canonical) }
        }
        val asksVideos = tokens.any { token ->
            listOf("video", "videos", "clip", "clips")
                .any { canonical -> QuerySpellingMatcher.areClosePhrases(token, canonical) }
        }
        val asksSms = tokens.any { it in setOf("sms", "text", "texts") }
        val asksMessages = tokens.any { it in setOf("message", "messages") }
        val asksPdf = tokens.any { it == "pdf" }
        // "document(s)" is content intent, not a hard file-format request;
        // photographed documents must remain in the gallery search universe.
        val asksDoc = tokens.any { it == "doc" }
        val asksCalendar = tokens.any { it in setOf("calendar", "calendars", "appointment", "appointments") }
        val asksContacts = tokens.any { it == "contact" || it == "contacts" }
        val asksCallLogs = tokens.any { it == "call" || it == "calls" || it == "call_logs" }
        val asksFiles = tokens.any { it == "file" || it == "files" }
        return when {
            asksPhotos && !asksVideos -> QueryMediaType.PHOTOS
            asksVideos && !asksPhotos -> QueryMediaType.VIDEOS
            asksSms -> QueryMediaType.SMS
            asksMessages -> QueryMediaType.MESSAGES
            asksPdf -> QueryMediaType.PDF
            asksDoc -> QueryMediaType.DOC
            asksCalendar -> QueryMediaType.CALENDAR
            asksContacts -> QueryMediaType.CONTACTS
            asksCallLogs -> QueryMediaType.CALL_LOGS
            asksFiles -> QueryMediaType.FILES
            else -> null
        }
    }

    internal fun requiresExclusivePeople(query: String): Boolean = Regex(
        "(?i)\\b(?:only|alone|by\\s+themselves|no\\s+other\\s+(?:human|person|people))\\b",
    ).containsMatchIn(query)

    internal fun explicitLocationCandidates(
        query: String,
        knownPersonLabels: List<String> = emptyList(),
    ): List<String> {
        val properName =
            "([\\p{Lu}][\\p{L}'’]*(?:\\s+[\\p{Lu}][\\p{L}'’]*){0,2})"
        val candidates = buildList {
            Regex(
                "(?:\\b(?:in|at|to|from|visit|visited|visiting)\\s+(?:the\\s+)?)$properName",
            ).findAll(query).forEach { match -> add(match.groupValues[1]) }
            Regex(
                "\\b$properName\\s+(?:trip|offsite|visit|vacation|holiday)\\b",
            ).findAll(query).forEach { match -> add(match.groupValues[1]) }
            // Voice/typed queries often lowercase proper nouns: "photo at goa".
            Regex(
                "(?i)\\b(?:in|at|to|from|visit|visited|visiting)\\s+(?:the\\s+)?" +
                    "([\\p{L}][\\p{L}'’.-]*)\\b",
            ).findAll(query).forEach { match -> add(match.groupValues[1]) }
        }
        val excluded = (
            knownPersonLabels +
                listOf(
                    "I",
                    "January", "February", "March", "April", "May", "June", "July",
                    "August", "September", "October", "November", "December",
                )
            ).map(String::lowercase).toSet()
        return candidates.asSequence()
            .map { it.trim() }
            .filter(String::isNotBlank)
            .filter { it.lowercase() !in excluded }
            .filter { it.lowercase() !in LOCATION_STOP_WORDS }
            .distinctBy(String::lowercase)
            .toList()
    }

    internal fun isNegatedMention(query: String, label: String): Boolean {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).toList()
        return words.any { word ->
            QuerySpellingMatcher.areClosePhrases(word.value, label) &&
                query.substring(maxOf(0, word.range.first - 36), word.range.first)
                    .matches(Regex("(?is).*\\b(?:without|with\\s+out|excluding|exclude|except|but\\s+not|not|no)\\b\\s*"))
        }
    }

    private fun isNegatedValueMention(query: String, value: String): Boolean {
        val escaped = Regex.escape(value.trim())
        return Regex(
            "(?i)\\b(?:without|with\\s+out|excluding|exclude|except|but\\s+not|no)\\b\\s+(?:the\\s+)?$escaped\\b|" +
                "\\bnot\\b\\s+(?:(?:from|in|at|to)\\s+)?$escaped\\b",
        ).containsMatchIn(query)
    }

    private fun hasSubtractedPredicate(
        spec: QueryExecutionSpec?,
        field: ExecutionField,
        value: String,
    ): Boolean {
        if (spec == null) return false
        fun visit(node: ExecutionNode, subtract: Boolean): Boolean = when (node) {
            is ExecutionNode.Predicate ->
                subtract && node.field == field &&
                    (node.value.equals(value, ignoreCase = true) ||
                        QuerySpellingMatcher.areClosePhrases(node.value, value))
            is ExecutionNode.Sorted -> visit(node.value, subtract)
            is ExecutionNode.Binary ->
                visit(node.left, subtract) ||
                    visit(node.right, subtract || node.operator == ExecutionBinaryOperator.SUBTRACT)
        }
        return visit(spec.root, false)
    }

    private fun hasAnySubtraction(spec: QueryExecutionSpec?): Boolean {
        if (spec == null) return false
        fun visit(node: ExecutionNode): Boolean = when (node) {
            is ExecutionNode.Predicate -> false
            is ExecutionNode.Sorted -> visit(node.value)
            is ExecutionNode.Binary ->
                node.operator == ExecutionBinaryOperator.SUBTRACT ||
                    visit(node.left) || visit(node.right)
        }
        return visit(spec.root)
    }

    private fun explicitlyRequestsFacePresence(query: String, label: String): Boolean {
        val normalizedQuery = normalize(query)
        val normalizedLabel = Regex.escape(normalize(label))
        return Regex(
            "\\b(?:with|beside|alongside|containing|including)\\s+$normalizedLabel\\b|" +
                "\\b(?:photo|picture|image)\\s+of\\s+$normalizedLabel\\b|" +
                "\\b$normalizedLabel\\s+(?:holding|showing|wearing)\\b",
        ).containsMatchIn(normalizedQuery)
    }

    private fun hasIntersectedSemanticAndKeyword(node: ExecutionNode?): Boolean {
        if (node == null) return false
        fun containsField(value: ExecutionNode, field: ExecutionField): Boolean = when (value) {
            is ExecutionNode.Predicate -> value.field == field
            is ExecutionNode.Sorted -> containsField(value.value, field)
            is ExecutionNode.Binary ->
                containsField(value.left, field) || containsField(value.right, field)
        }
        return when (node) {
            is ExecutionNode.Predicate -> false
            is ExecutionNode.Sorted -> hasIntersectedSemanticAndKeyword(node.value)
            is ExecutionNode.Binary ->
                (
                    node.operator == ExecutionBinaryOperator.INTERSECT &&
                        containsField(node, ExecutionField.SEMANTIC) &&
                        containsField(node, ExecutionField.KEYWORD)
                    ) ||
                    hasIntersectedSemanticAndKeyword(node.left) ||
                    hasIntersectedSemanticAndKeyword(node.right)
        }
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val normalizedText = normalize(text)
        val normalizedPhrase = normalize(phrase)
        return normalizedPhrase.isNotBlank() &&
            " $normalizedText ".contains(" $normalizedPhrase ")
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    internal fun isMetadataOnlyIntent(query: String): Boolean {
        if (EVENT_DISCOVERY_TERM.containsMatchIn(query)) return false
        val normalized = normalize(query)
        val coOccurrence = Regex(
            "^(?:who|when)\\b.*\\b(?:appear|appears|appeared|was|were)\\b.*\\bwith\\b",
        ).containsMatchIn(normalized)
        val placeList = Regex(
            "^(?:what|which|how\\s+many)\\s+(?:places|locations|cities)\\b.*\\bvisit(?:ed)?\\b",
        ).containsMatchIn(normalized)
        return coOccurrence || placeList
    }

    internal fun isPersonLocationMediaOnly(
        query: String,
        knownPersonLabels: List<String>,
    ): Boolean {
        val mediaType = expectedMediaType(query)
        if (mediaType != QueryMediaType.PHOTOS && mediaType != QueryMediaType.VIDEOS) return false
        val people = knownPersonLabels.filter { containsPhrase(query, it) }
        val locations = explicitLocationCandidates(query, knownPersonLabels)
        if (people.isEmpty() || locations.size != 1 || EVENT_DISCOVERY_TERM.containsMatchIn(query)) {
            return false
        }
        var residue = normalize(query)
        (people + locations).forEach { value ->
            residue = residue.replace(
                Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(normalize(value))}(?![\\p{L}\\p{N}])"),
                " ",
            )
        }
        val ignored = SearchKeywordPolicy.forbiddenScaffoldingWords + setOf(
            "photo", "photos", "picture", "pictures", "image", "images",
            "video", "videos", "clip", "clips", "show", "find", "search",
        )
        return residue.split(Regex("\\s+")).filter(String::isNotBlank).all { it in ignored }
    }

    private val NEGATION_MARKER = Regex(
        "(?i)\\b(?:without|with\\s+out|excluding|exclude|except|but\\s+not|not|no)\\b",
    )
    private val EVENT_DISCOVERY_TERM = Regex(
        "(?i)\\b(?:danc(?:e|es|ed|ing)|outing|wedding|birthday|party|dinner|hiking|trek|camp|camping|" +
            "picnic|celebration|meeting|offsite)\\b",
    )
    private val LOCATION_STOP_WORDS = setOf(
        "a", "an", "the", "my", "me", "mine", "myself", "last", "next", "this", "that",
        "today", "tomorrow", "yesterday", "week", "month", "year", "photo", "photos",
        "morning", "afternoon", "evening", "night", "noon", "midnight",
        "picture", "pictures", "image", "images", "video", "videos", "without", "with",
        "beach", "dinner", "birthday", "party", "wedding", "hiking", "camping", "picnic",
    )
}

internal object AnswerIntentPolicy {
    fun expected(query: String): Boolean {
        val normalized = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (QueryCategoryConstraintPolicy.expectedCategory(normalized) == QueryCategory.DOC) {
            return true
        }
        return Regex(
            "^(?:who|whose|when|where|what|which|how|why)\\b|" +
                "\\b(?:tell|identify|count|number|name|price|amount|date|time|total|read|says?)\\b",
        ).containsMatchIn(normalized)
    }
}

/**
 * Distinguishes self-presence from ordinary first-person grammar. In
 * particular, "photos of me" and "where did I go" need the tagged self face,
 * while "my passport" and "how much did I spend" must remain document/OCR
 * queries without a face constraint.
 */
internal object SelfPersonQueryPolicy {
    fun referencesSelf(query: String): Boolean =
        Regex("(?i)(?<![\\p{L}\\p{N}])(?:i|me|my|mine|myself)(?![\\p{L}\\p{N}])")
            .containsMatchIn(query)

    fun referencesSelfForCategory(query: String, category: QueryCategory): Boolean =
        if (category == QueryCategory.DOC) {
            Regex("(?i)(?<![\\p{L}\\p{N}])(?:me|my|mine|myself)(?![\\p{L}\\p{N}])")
                .containsMatchIn(query)
        } else {
            referencesSelfAsPerson(query)
        }

    fun referencesSelfIdentityDocument(query: String): Boolean {
        val normalized = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return SELF_IDENTITY_DOCUMENT.containsMatchIn(normalized)
    }

    fun referencesSelfAsPerson(query: String): Boolean {
        val normalized = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        if (DOCUMENT_FIRST_PERSON.containsMatchIn(normalized)) return false
        return PHOTO_OF_ME.containsMatchIn(normalized) ||
            WITH_ME.containsMatchIn(normalized) ||
            ME_IN_SCENE.containsMatchIn(normalized) ||
            SELF_ACTION.containsMatchIn(normalized) ||
            MY_EVENT_OR_APPEARANCE.containsMatchIn(normalized) ||
            MY_MEDIA.containsMatchIn(normalized) ||
            Regex("^(?:me|myself|i)\\s+(?:only|alone)$").containsMatchIn(normalized)
    }

    fun isNegatedSelfReference(query: String): Boolean {
        val normalized = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Regex("\\b(?:without|excluding|except|but not)\\s+(?:photos?\\s+of\\s+)?me\\b")
            .containsMatchIn(normalized) ||
            Regex("\\b(?:no|not)\\s+(?:photos?\\s+)?(?:with|containing|including|of)\\s+me\\b")
                .containsMatchIn(normalized)
    }

    private val DOCUMENT_FIRST_PERSON = Regex(
        "\\b(?:my\\s+(?:passport|driving\\s+licen[cs]e|licen[cs]e|dl|ssn|" +
            "social\\s+security|pan\\s+card|aadhaa?r(?:d)?|id\\s+card|password|" +
            "user\\s*id|dob|date\\s+of\\s+birth|age|marks?|grades?|receipt|bill|" +
            "invoice|ticket|account)|i\\s+(?:spend|spent|pay|paid|purchase|purchased|" +
            "buy|bought|order|ordered))\\b",
    )
    private val SELF_IDENTITY_DOCUMENT = Regex(
        "\\bmy\\s+(?:passport|driving\\s+licen[cs]e|licen[cs]e|dl|ssn|" +
            "social\\s+security|pan\\s+card|aadhaa?r(?:d)?(?:\\s+card)?|" +
            "identity\\s+card|id\\s+card|user\\s*id|dob|date\\s+of\\s+birth|" +
            "age|marks?|grades?)\\b",
    )
    private val PHOTO_OF_ME = Regex(
        "\\b(?:photos?|pictures?|images?|videos?|clips?|selfies?|portraits?)\\s+of\\s+me\\b|" +
            "\\b(?:show|find)\\s+me\\s+(?:in|at|with|wearing|doing)\\b",
    )
    private val WITH_ME = Regex(
        "\\b(?:with|without|excluding|except|beside|alongside)\\s+me\\b",
    )
    private val ME_IN_SCENE = Regex(
        "\\bme\\s+(?:in|at|during|wearing|doing|playing|standing|sitting|walking|" +
            "running|swimming|eating|holding)\\b",
    )
    private val SELF_ACTION = Regex(
        "\\bi\\s+(?:am|was|were|appear|appeared|go|went|visit|visited|travel|" +
            "travelled|traveled|wear|wearing|wore|play|played|swim|swam|camp|" +
            "camped|hike|hiked|stand|stood|sit|sat|walk|walked|run|ran|eat|ate|" +
            "photograph|photographed)\\b",
    )
    private val MY_EVENT_OR_APPEARANCE = Regex(
        "\\bmy\\s+(?:birthday|wedding|trip|vacation|holiday|outing|party|selfie|" +
            "selfies|portrait|portraits|outfit|appearance)\\b",
    )
    private val MY_MEDIA = Regex(
        "\\bmy\\s+(?:photo|photos|picture|pictures|image|images|video|videos|clip|clips|selfie|selfies)\\b",
    )
}

/** Validates result-order operators that are explicit in the query contract. */
internal object QuerySortConstraintPolicy {
    internal fun requiresDateSort(query: String, category: QueryCategory): Boolean =
        category == QueryCategory.TIME ||
            Regex("\\b(?:latest|newest|earliest|most\\s+recent|last\\s+time)\\b")
                .containsMatchIn(query.lowercase().replace(Regex("\\s+"), " "))

    internal fun requiresLocationSort(query: String): Boolean = Regex(
        "^(?:what|which|how\\s+many)\\s+(?:places|locations|cities|national\\s+parks?)\\b|" +
            "\\b(?:north\\s+to\\s+south|south\\s+to\\s+north)\\b",
    ).containsMatchIn(query.lowercase().replace(Regex("\\s+"), " "))

    fun validate(query: String, plan: QueryPlan) {
        if (requiresDateSort(query, plan.queryCategory)) {
            require(plan.recentFirst) { "This query requires SORT_DATE" }
        }
        if (requiresLocationSort(query)) {
            require(plan.sortByLocation) { "This query requires SORT_LOC" }
        } else {
            require(!plan.sortByLocation) {
                "SORT_LOC is forbidden because the query did not request a plural place list or location order"
            }
        }
    }
}

/**
 * Prevents a language-model plan from silently narrowing an undated query.
 * This gate validates model output; it does not add or rewrite dates.
 */
internal object QueryDateConstraintPolicy {
    fun validate(query: String, plan: QueryPlan) {
        QueryScopeParser.explicitDateBoundsFromQuery(query)?.let { requiredBounds ->
            require(
                plan.fromDate == requiredBounds.first &&
                    plan.toDate == requiredBounds.second,
            ) {
                "Query date range must be exactly " +
                    "${requiredBounds.first.ifBlank { "open" }}.." +
                    requiredBounds.second.ifBlank { "open" }
            }
            return
        }
        if (plan.fromDate.isBlank() && plan.toDate.isBlank()) return
        require(hasExplicitTemporalConstraint(query)) {
            "from_date/to_date are forbidden because the user query has no explicit temporal constraint"
        }
    }

    internal fun requiredSingleDay(query: String): String? {
        if (OPEN_ENDED_OR_RANGE.containsMatchIn(query)) return null
        val bounds = QueryScopeParser.explicitDateBoundsFromQuery(query) ?: return null
        return bounds.first.takeIf { it == bounds.second }
    }

    fun hasExplicitTemporalConstraint(query: String): Boolean {
        if (QueryScopeParser.explicitTimeHintFromQuery(query).isNotBlank()) return true
        return EXTENDED_TEMPORAL_PHRASE.containsMatchIn(query)
    }

    private val EXTENDED_TEMPORAL_PHRASE = Regex(
        "(?i)\\b(?:" +
            "(?:last|previous|this|current|next|past)\\s+" +
            "(?:(?:a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+)\\s+)?" +
            "(?:hours?|days?|weekends?|weeks?|months?|years?|spring|summer|autumn|fall|winter)|" +
            "(?:a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+)\\s+" +
            "(?:hours?|days?|weeks?|months?|years?)\\s+ago|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "spring|summer|autumn|fall|winter|" +
            "morning|afternoon|evening|tonight|noon|midnight" +
        ")\\b",
    )
    private val OPEN_ENDED_OR_RANGE = Regex(
        "(?i)\\b(?:" +
            "after|before|since|until|through|between|onwards?|later\\s+than|earlier\\s+than" +
            ")\\b|\\bfrom\\b.+\\b(?:to|through|until)\\b",
    )
}

/**
 * Safety gate for accepting a Gemma-corrected structured value. Person names
 * use a stricter short-token rule; locations permit one typo in names such as
 * "Goaa" -> "Goa".
 */
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
