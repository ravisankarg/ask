package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.time.LocalDate

/**
 * Gemma 4 E4B is the sole query planner. Planning runs on GalleryIndexer's
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
    private val isoDateToken = Regex("\\b\\d{4}(?:-\\d{2}(?:-\\d{2})?)?\\b")
    private val explicitOcrAndSyntax = Regex(
        "(?i)\\[ocr\\s*==\\s*\\{[^{}\\]]+\\}\\s*&&\\s*\\{[^{}\\]]+\\}",
    )

    data class PlannedQuery(
        val plan: QueryPlan,
        val session: GemmaRuntime.ConversationSession?,
        val plannerJson: String = "",
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
     * Every query is compiled by Gemma 4 E4B. A malformed response gets one
     * repair turn in the same session; there is no deterministic QP fallback.
     */
    fun planWithSession(
        context: Context,
        query: String,
        knownPersonLabels: List<String> = emptyList(),
        selfPersonLabel: String? = null,
    ): PlannedQuery {
        val appContext = context.applicationContext
        check(isModelInstalled(appContext)) {
            "Gemma 4 E4B is required for query planning"
        }

        var session: GemmaRuntime.ConversationSession? = null
        return try {
            session = GemmaRuntime.takePrefilledPlannerSession()
                ?: GemmaRuntime.shared(appContext).createPlannerConversation(plannerSystemInstruction())
            var candidateRaw = session.generate(
                plannerUserPrompt(query, knownPersonLabels, selfPersonLabel),
            )
                .trim()
                .take(MAX_OUTPUT_CHARS)
            var repairUsed = false
            var repairAttempt = 0
            lateinit var parsed: QueryPlan
            while (true) {
                try {
                    parsed = compileGemmaPlan(
                        candidateRaw,
                        query,
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
                        throw validationError
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
                            query = query,
                            knownPersonLabels = knownPersonLabels,
                            selfPersonLabel = selfPersonLabel,
                            invalidOutput = candidateRaw,
                            error = validationError,
                            repairAttempt = repairAttempt,
                        ),
                    ).trim().take(MAX_OUTPUT_CHARS)
                }
            }
            Log.i(
                TAG,
                "Gemma-only plan accepted: category=${parsed.queryCategory.wireName}, " +
                    "semantic=${parsed.semanticQueries.size}, ocr=${parsed.ocrTerms.size}, " +
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
                repairUsed = repairUsed,
            )
        } catch (error: Throwable) {
            session?.close()
            Log.e(TAG, "Gemma-only query planning failed", error)
            throw IllegalStateException("Gemma 4 E4B could not compile this query", error)
        }
    }

    private fun canonicalExecutionSpec(plan: QueryPlan): String =
        plan.executionSpecString()

    /** Canonical C-like spec for the post-resolution plan enforced by retrieval. */
    fun effectivePlanJson(plan: QueryPlan): String = canonicalExecutionSpec(plan)

    /** Stable preface placed in the conversation KV cache before a query arrives. */
    fun plannerSystemInstruction(): String = """
        You are Ask Galaxy's private on-device query compiler. Gemma 4 E4B is the only plan author.
        For PLANNER_TASK output ONLY one C-like expression: no reasoning, markdown, JSON, labels, or prose.

        Start exactly:
        [query_category == CATEGORY] &&
        CATEGORY is exactly doc, scenary, person, location, or time.

        Decide CATEGORY from the answer requested, before reading event nouns:
        - doc: read written content in a screenshot, receipt, bill, invoice, ticket, passport, driving licence/DL, SSN/social-security card, PAN card, Aadhaar/Aadhar card, ID card, mark sheet, report card, coupon, voucher, menu, sign, or card. Spending, totals, document-owner names, passport/licence/ID numbers, passwords, Wi-Fi passwords, user IDs, DOB/date of birth, exact age, marks, grades, scores, codes, and document expiry are doc.
        - person: who, whose, which person/people, or who else.
        - location: where, what/which place, places/cities visited, or destination.
        - time: when an event happened, what date, which dates/day, or what time.
        - scenary: visual objects, actions, appearance, activities, events, or any other visual search.
        Leading who/which people MUST be person. Leading where/what places MUST be location. Leading when/what date/what time/on which dates MUST be time even if a person, place, or scene is prominent. Exception: a date printed in a licence, passport, coupon, voucher, ticket, receipt, or screenshot is doc.

        Retrieval predicates use exactly one field from:
        person, mime type, from_date, to_date, location, semantic, ocr
        Each ordinary predicate contains exactly ONE `field == value`. NEVER put `+`, `-`, `&&`, comma, or another `==` inside an ordinary predicate value. The only exception is the required OCR keyword syntax [ocr == {word1} && {word2}], where each braced word is required in the same OCR text.

        Structured fields:
        - Preserve every explicitly named Known person as a person predicate when the query asks about that person's presence in photos, scenes, places, times, or events. Correct only a close misspelling to the exact Known people label. A negated person must be subtracted.
        - SELF PERSON: the task may provide one `Self person` label. For presence/identity uses of I, me, my, mine, or myself, emit that exact label as a person predicate; examples include photos of me, who was with me, where I went, when I visited, what I wore, and my birthday photos. Do not create a person predicate for grammatical ownership/agency in doc queries such as my passport, my password, my receipt, or how much I spent. For a self identity-document query, place only the actual Self person name word(s) plus essential document word(s) in OCR. Never emit literal OCR keywords such as person, people, self, me, my, mine, myself, or owner. If Self person is `not set`, never invent one.
        - DOC PERSON NAMES: when a doc query names the document owner or subject, keep that name inside the ocr keywords; do not add a face/person predicate merely because the name is known. A passport, ID, mark sheet, bill, or account screenshot may contain the printed name without containing a tagged face.
        - Preserve one explicitly named place as location. Do not leave that place inside semantic. If multiple route endpoints are named, keep the route as semantic instead of choosing one.
        - Explicit photo/picture/image wording requires [mime type == photos]. Explicit video/clip wording requires [mime type == videos]. Correct spelling such as phootos. Movie ticket is content, never MIME.
        - Dates are ISO yyyy-MM-dd and appear only for an explicit temporal constraint. Resolve the complete requested range from Today. Last year is January 1 through December 31 of the previous year; last month is its full calendar month. One exact day requires equal from_date and to_date. After/before/since may use one open boundary. Never infer today.
        - semantic is one compact conceptual phrase for SigLIP image similarity. It may be clarified or paraphrased. Exclude question words, dates, time words, person names, location names, and MIME words already represented structurally.
        - OCR HYBRID FOR EVERY DOC QUERY: emit exactly one semantic predicate plus one OCR predicate joined with `+` inside one group: [[semantic == conceptual document phrase] + [ocr == {word1} && {word2}]]. The `&&` inside OCR means every braced word must occur in the same photo OCR text. A complete OCR match is perfect and always ranks above semantic-only document matches in the result grid and answer context. The outer `+` retains semantic-only fallback when no complete OCR match exists.
        - ocr is doc-only and contains 2-6 essential words likely to coexist on the intended document. Every word is separately braced and joined by `&&`; never write an OCR phrase, synonyms, or alternatives. Include the actual document subject name when known. For my passport with Self person Ravi, write [ocr == {Ravi} && {passport}], never person/self/me/my/owner aliases.
        - BROAD DOCUMENT EXPANSION: for an aggregate or collection question with no named merchant, item, event, or document, use one broad semantic phrase and only a small co-occurring OCR conjunction such as [ocr == {total} && {amount}]. Do not AND mutually exclusive document types such as receipt, bill, and invoice. Never use spending, expenses, finances, paperwork, or documents as OCR keywords.
        - Metadata-only co-occurrence queries and plural place/city lists need no positive semantic predicate. Do not invent relational phrases such as "appears with", "most frequent companion", "cities visited", "places visited", or "travel destination".

        Operators: postfix SORT_DATE/SORT_LOC, then + and -, then &&, then comma.
        comma = alternative union; + = fused positive retrieval; && = hard intersection; - = subtraction.
        Operators join complete bracketed predicates or groups, never words within a value.
        For negation use a balanced group:
        [query_category == person] && [[[person == Ravi] && [semantic == wedding]] - [person == Ramani]]
        Use SORT_DATE for every time-category plan and for latest/newest/earliest intent.
        Use SORT_LOC for plural place/city lists or north-to-south intent.
        Complete every subtraction before the final sort:
        [query_category == location] && [[POSITIVE] - [NEGATIVE]] SORT_LOC
        Maximum eight retrieval predicates. Every `[` has one matching `]`.

        Examples:
        who is in these beach photos => [query_category == person] && [[mime type == photos] && [semantic == beach]]
        who else was with Meghana at the team outing => [query_category == person] && [[person == Meghana] && [semantic == team outing]]
        who appears most often with Ramani => [query_category == person] && [person == Ramani]
        who appears with Meghana in Bengaluru without Ravi => [query_category == person] && [[[person == Meghana] && [location == Bengaluru]] - [person == Ravi]]
        which people joined both the Goa trip and the mountain trek => [query_category == person] && [[location == Goa] && [semantic == mountain trek]]
        which person is wearing a red jacket beside the dog => [query_category == person] && [semantic == red jacket beside dog]
        who else was present across Ravi's whole birthday celebration, excluding restaurant screenshots => [query_category == person] && [[[person == Ravi] && [semantic == birthday celebration]] - [semantic == restaurant screenshot]]
        where was the lighthouse photo taken => [query_category == location] && [[mime type == photos] && [semantic == lighthouse]]
        when did I visit Goa => [query_category == time] && [location == Goa] SORT_DATE
        what date was the beach picnic => [query_category == time] && [semantic == beach picnic] SORT_DATE
        who was with me at dinner on 5 October 2025 => [query_category == person] && [[from_date == 2025-10-05] && [to_date == 2025-10-05] && [semantic == dinner]]
        on which dates did we visit national parks last year => [query_category == time] && [[from_date == LAST_YEAR_START] && [to_date == LAST_YEAR_END] && [semantic == national park visit]] SORT_DATE
        what places did I visit last year => [query_category == location] && [[from_date == LAST_YEAR_START] && [to_date == LAST_YEAR_END]] SORT_LOC
        when does my driving licence expire, Self person Ravi => [query_category == doc] && [[semantic == driving licence expiry document] + [ocr == {Ravi} && {licence}]]
        passport number in the passport photo => [query_category == doc] && [[mime type == photos] && [[semantic == passport identity document] + [ocr == {passport} && {number}]]]
        what is my passport number, Self person Ravi => [query_category == doc] && [[semantic == passport identity document] + [ocr == {Ravi} && {passport}]]
        what is Ravi passport number => [query_category == doc] && [[semantic == passport identity document] + [ocr == {Ravi} && {passport}]]
        what is Ravi driving licence number => [query_category == doc] && [[semantic == driving licence identity document] + [ocr == {Ravi} && {licence}]]
        what is Ravi Aadhaar number => [query_category == doc] && [[semantic == Aadhaar identity card] + [ocr == {Ravi} && {Aadhaar}]]
        what is Ravi date of birth => [query_category == doc] && [[semantic == identity record date of birth] + [ocr == {Ravi} && {birth}]]
        what are Ravi exam marks => [query_category == doc] && [[semantic == exam mark sheet report card] + [ocr == {Ravi} && {marks}]]
        what is the Wi-Fi password => [query_category == doc] && [[semantic == Wi-Fi credential card] + [ocr == {wifi} && {password}]]
        how much did I spend on the odyssy movie ticket => [query_category == doc] && [[semantic == Odyssey movie ticket price] + [ocr == {Odyssey} && {ticket}]]
        how much did I spend last month => [query_category == doc] && [[from_date == LAST_MONTH_START] && [to_date == LAST_MONTH_END] && [[semantic == purchase receipts bills invoices payment confirmations] + [ocr == {total} && {amount}]]]
        photos that make great phone backgrounds => [query_category == scenary] && [[mime type == photos] && [semantic == beautiful phone wallpaper background]]
        clearest national park photo excluding selfies => [query_category == scenary] && [[[mime type == photos] && [semantic == clear national park landscape]] - [semantic == selfie]]
        which places did I visit in July 2025 from north to south excluding airport layovers => [query_category == location] && [[[from_date == 2025-07-01] && [to_date == 2025-07-31]] - [semantic == airport layover]] SORT_LOC
        where did my Bengaluru-to-Goa road trip stop for lunch => [query_category == location] && [semantic == lunch stop on Bengaluru-to-Goa road trip]
        where did we camp during the latest mountain trip => [query_category == location] && [semantic == mountain campsite] SORT_DATE
        which people were at the wedding but not Ravi => [query_category == person] && [[semantic == wedding] - [person == Ravi]]

        Replace all date placeholders in examples with concrete ISO dates calculated from Today.

        For ANSWER_TASK ignore this execution grammar and answer only from supplied gallery context. Never repeat the execution expression as the answer.
    """.trimIndent()

    private fun plannerUserPrompt(
        query: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
    ): String =
        "PLANNER_TASK: Today=${LocalDate.now()}. Known people: ${knownPeopleText(knownPersonLabels)}. " +
            "Self person: ${selfPersonText(selfPersonLabel)}. " +
            "First classify by the requested answer: written document, who, where, when, or visual scene. " +
            "Preserve every explicit person-presence constraint, single place, media type, date range, and negation " +
            "as structured fields. In doc queries keep an owner/subject name in the OCR keywords instead of " +
            "creating a face/person predicate. " +
            "Use only people actually named in the Query, except map a presence/identity use of I/me/my/mine/myself " +
            "to the exact Self person label. Known people is correction vocabulary, not a result list. " +
            "Never write person/people/place/places/location or without/excluding inside semantic; " +
            "exclusion always uses subtraction. " +
            "Do not add semantic to metadata-only co-occurrence or plural place/city-list queries. " +
            "Time answers require SORT_DATE; plural place answers require SORT_LOC. " +
            "Each ordinary bracket has one field == value and contains no operator. Every doc query requires one " +
            "conceptual semantic predicate plus [ocr == {word1} && {word2}] joined by outer + inside one group. " +
            "Every braced OCR word is required in the same photo; a complete match ranks before semantic-only. " +
            "For self documents use only the exact Self person name and document words, never person/self/me/my aliases. " +
            "Spending/expenses/documents are not useful OCR keywords. " +
            "Return only the expression. Query: ${cleanQuery(query)}"

    private fun plannerRepairPrompt(
        query: String,
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
        val retryDirective = if (repairAttempt > 1) {
            "The previous repair also failed. Do not copy its invalid semantic text or operator grouping. "
        } else {
            ""
        }
        return "PLANNER_TASK REPAIR: Today=${LocalDate.now()}. " +
            "Known people: ${knownPeopleText(knownPersonLabels)}. " +
            "Self person: ${selfPersonText(selfPersonLabel)}. " +
            retryDirective +
            "The validator error is authoritative: $safeError. Fix that exact error and recompile the original query. " +
            "If the error names forbidden semantic words, remove those exact words from semantic; do not repeat them. " +
            "Do not reuse malformed syntax. Start [query_category == CATEGORY] &&. " +
            "Each ordinary predicate must be exactly [field == plain value]; an operator can appear only BETWEEN " +
            "complete predicates or groups. OCR is the only exception and must be [ocr == {word1} && {word2}], " +
            "where every word is required in the same OCR text. Put every negative constraint completely on the right side of subtraction, as " +
            "[query_category == CATEGORY] && [[[POSITIVE predicates]] - [[NEGATIVE predicates]]]. " +
            "Never append a negative predicate with && after the subtraction. Finish every subtraction before " +
            "appending a final SORT_DATE or SORT_LOC. " +
            "For every doc query emit one conceptual semantic predicate + one 2-6 word OCR conjunction in one " +
            "fused group. Use only essential co-occurring words. For my identity document, use the exact Self " +
            "person name plus the document word; never person, self, me, my, mine, myself, or owner. " +
            "Never use spending, expenses, finances, paperwork, " +
            "or documents alone as OCR keywords. Preserve explicit people, one place, photos/videos, " +
            "and map I/me/my/mine/myself to Self person only when they refer to that person's presence or identity, " +
            "not ordinary document ownership or spending grammar. " +
            "the exact full date range, negation, and required sort. Use at most eight predicates and balance all " +
            "brackets. Return only the corrected expression. Original query: ${cleanQuery(query)}. " +
            "Previous invalid expression: $safeOutput"
    }

    private fun knownPeopleText(knownPersonLabels: List<String>): String =
        knownPersonLabels.asSequence()
            .map { it.replace(Regex("[\\r\\n|]+"), " ").trim().take(48) }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(64)
            .joinToString(" | ")
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
        validatePlannerOcrSyntax(candidate)
        val plan = ExecutionSpecCompiler.compile(QueryExecutionSpec.parse(candidate))
        validateCompiledPlan(query, knownPersonLabels, plan, selfPersonLabel)
        return plan
    }

    internal fun validatePlannerOcrSyntax(candidate: String) {
        if (Regex("(?i)\\[query_category\\s*==\\s*doc]").containsMatchIn(candidate)) {
            require(explicitOcrAndSyntax.containsMatchIn(candidate)) {
                "Doc OCR must use explicit AND syntax: [ocr == {word1} && {word2}]"
            }
        }
    }

    /** Re-runs every post-parse production validator against a compiled plan. */
    internal fun validateCompiledPlan(
        query: String,
        knownPersonLabels: List<String>,
        plan: QueryPlan,
        selfPersonLabel: String? = null,
    ) {
        QueryCategoryConstraintPolicy.validate(query, plan)
        (plan.personNames + plan.excludedPersonNames).forEach { candidate ->
            val presentVerbatim = containsPhrase(query, candidate)
            val knownCorrection = knownPersonLabels.any { it.equals(candidate, ignoreCase = true) } &&
                QuerySpellingMatcher.isPlausibleCorrection(query, candidate)
            val selfAlias = selfPersonLabel?.equals(candidate, ignoreCase = true) == true &&
                SelfPersonQueryPolicy.referencesSelfAsPerson(query)
            require(presentVerbatim || knownCorrection || selfAlias) {
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
        QueryStructuredIntentPolicy.validate(query, knownPersonLabels, plan, selfPersonLabel)
        QueryDateConstraintPolicy.validate(query, plan)
        QuerySortConstraintPolicy.validate(query, plan)
        validateSemanticValues(plan)
        validateOcrKeywords(plan)
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
                "^(?:where|what\\s+(?:place|places|location|locations)|" +
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
        if (plan.queryCategory == QueryCategory.DOC) {
            require(plan.semanticQueries.size == 1 && plan.ocrTerms.size == 1) {
                "Every doc query requires exactly one semantic predicate and one ocr predicate"
            }
            require(hasFusedSemanticAndOcr(plan.executionSpec?.root)) {
                "Doc semantic and ocr predicates must be joined with + so dual matches are boosted"
            }
            mentionedKnownPeople.forEach { label ->
                require(
                    plan.ocrTerms.any { ocr -> containsPhrase(ocr, label) },
                ) {
                    "Document subject '$label' must remain in the OCR keywords"
                }
                if (!explicitlyRequestsFacePresence(query, label)) {
                    require(plan.personNames.none { it.equals(label, ignoreCase = true) }) {
                        "Document subject '$label' must not become a face/person predicate"
                    }
                }
            }
            if (SelfPersonQueryPolicy.referencesSelfIdentityDocument(query)) {
                val selfLabel = requireNotNull(selfPersonLabel?.takeIf(String::isNotBlank)) {
                    "This document query requires the user to identify 'This is me' in face tagging"
                }
                require(plan.ocrTerms.any { containsPhrase(it, selfLabel) }) {
                    "A self identity-document query must include Self person '$selfLabel' in OCR keywords"
                }
                val aliases = plan.ocrTerms
                    .flatMap(OcrKeywordPolicy::keywords)
                    .filter(SELF_OCR_ALIAS_WORDS::contains)
                require(aliases.isEmpty()) {
                    "Self document OCR must use the actual tagged name, not aliases: " +
                        aliases.distinct().joinToString(", ")
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
                }
            }
        }

        if (SelfPersonQueryPolicy.referencesSelfAsPerson(query)) {
            val selfLabel = requireNotNull(selfPersonLabel?.takeIf(String::isNotBlank)) {
                "This self-reference requires the user to identify 'This is me' in face tagging"
            }
            if (SelfPersonQueryPolicy.isNegatedSelfReference(query)) {
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
            }
        }

        val explicitLocations = explicitLocationCandidates(query, knownPersonLabels)
        if (explicitLocations.size == 1) {
            val expected = explicitLocations.single()
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
        if (NEGATION_MARKER.containsMatchIn(query)) {
            require(
                plan.excludedPersonNames.isNotEmpty() ||
                    plan.negativeSemanticQueries.isNotEmpty(),
            ) {
                "Explicit exclusion requires a subtraction predicate"
            }
        }
        if (EVENT_DISCOVERY_TERM.containsMatchIn(query)) {
            require(plan.semanticQueries.isNotEmpty()) {
                "The named event or activity requires a positive semantic predicate"
            }
        }
        if (isMetadataOnlyIntent(query)) {
            require(plan.semanticQueries.isEmpty()) {
                "Metadata-only co-occurrence or place-list intent forbids a positive semantic predicate"
            }
        }
    }

    internal fun expectedMediaType(query: String): QueryMediaType? {
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
        val asksPhotos = tokens.any { token ->
            listOf("photo", "photos", "picture", "pictures", "image", "images")
                .any { canonical -> QuerySpellingMatcher.areClosePhrases(token, canonical) }
        }
        val asksVideos = tokens.any { token ->
            listOf("video", "videos", "clip", "clips")
                .any { canonical -> QuerySpellingMatcher.areClosePhrases(token, canonical) }
        }
        return when {
            asksPhotos && !asksVideos -> QueryMediaType.PHOTOS
            asksVideos && !asksPhotos -> QueryMediaType.VIDEOS
            else -> null
        }
    }

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
            .distinctBy(String::lowercase)
            .toList()
    }

    private fun isNegatedMention(query: String, label: String): Boolean {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).toList()
        return words.any { word ->
            QuerySpellingMatcher.areClosePhrases(word.value, label) &&
                query.substring(maxOf(0, word.range.first - 36), word.range.first)
                    .matches(Regex("(?is).*\\b(?:without|excluding|except|not|no)\\b\\s*"))
        }
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

    private fun hasFusedSemanticAndOcr(node: ExecutionNode?): Boolean {
        if (node == null) return false
        fun containsField(value: ExecutionNode, field: ExecutionField): Boolean = when (value) {
            is ExecutionNode.Predicate -> value.field == field
            is ExecutionNode.Sorted -> containsField(value.value, field)
            is ExecutionNode.Binary ->
                containsField(value.left, field) || containsField(value.right, field)
        }
        return when (node) {
            is ExecutionNode.Predicate -> false
            is ExecutionNode.Sorted -> hasFusedSemanticAndOcr(node.value)
            is ExecutionNode.Binary ->
                (
                    node.operator == ExecutionBinaryOperator.ADD &&
                        containsField(node, ExecutionField.SEMANTIC) &&
                        containsField(node, ExecutionField.OCR)
                    ) ||
                    hasFusedSemanticAndOcr(node.left) ||
                    hasFusedSemanticAndOcr(node.right)
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
            "^(?:what|which)\\s+(?:places|locations|cities)\\b.*\\bvisit(?:ed)?\\b",
        ).containsMatchIn(normalized)
        return coOccurrence || placeList
    }

    private val NEGATION_MARKER = Regex(
        "(?i)\\b(?:without|excluding|exclude|except|but\\s+not)\\b",
    )
    private val EVENT_DISCOVERY_TERM = Regex(
        "(?i)\\b(?:outing|wedding|birthday|party|dinner|hiking|trek|camp|camping|" +
            "picnic|celebration|meeting|offsite)\\b",
    )
    private val SELF_OCR_ALIAS_WORDS = setOf(
        "person", "people", "self", "me", "my", "mine", "myself", "owner",
    )
}

/**
 * Distinguishes self-presence from ordinary first-person grammar. In
 * particular, "photos of me" and "where did I go" need the tagged self face,
 * while "my passport" and "how much did I spend" must remain document/OCR
 * queries without a face constraint.
 */
internal object SelfPersonQueryPolicy {
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
            MY_EVENT_OR_APPEARANCE.containsMatchIn(normalized)
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
            "camped|hike|hiked|stand|stood|sit|sat|walk|walked|run|ran|eat|ate)\\b",
    )
    private val MY_EVENT_OR_APPEARANCE = Regex(
        "\\bmy\\s+(?:birthday|wedding|trip|vacation|holiday|outing|party|selfie|" +
            "selfies|portrait|portraits|outfit|appearance)\\b",
    )
}

/** Validates result-order operators that are explicit in the query contract. */
internal object QuerySortConstraintPolicy {
    fun validate(query: String, plan: QueryPlan) {
        val normalized = query.lowercase().replace(Regex("\\s+"), " ")
        val requiresDateSort = plan.queryCategory == QueryCategory.TIME ||
            Regex("\\b(?:latest|newest|earliest|most\\s+recent|last\\s+time)\\b")
                .containsMatchIn(normalized)
        if (requiresDateSort) {
            require(plan.recentFirst) { "This query requires SORT_DATE" }
        }
        val requiresLocationSort =
            Regex(
                "^(?:what|which)\\s+(?:places|locations|cities|national\\s+parks?)\\b|" +
                    "\\b(?:north\\s+to\\s+south|south\\s+to\\s+north)\\b",
            ).containsMatchIn(normalized)
        if (requiresLocationSort) {
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
