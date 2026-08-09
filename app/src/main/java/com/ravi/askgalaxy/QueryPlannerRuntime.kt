package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.time.LocalDate

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
        "number", "numbers", "count", "many", "date", "time", "when",
    )
    private val explicitOcrAndSyntax = Regex(
        "(?i)\\[ocr\\s*==\\s*\\{[^{}\\]]+\\}\\s*&&\\s*\\{[^{}\\]]+\\}",
    )
    private val missingEnvelopeBracket = Regex(
        "(?i)\\[(answer_needed|query_category)\\s*==\\s*" +
            "(true|false|doc|scenary|person|location|time)\\s*&&",
    )
    private val collapsedRoutingEnvelope = Regex(
        "(?is)^\\[answer_needed\\s*==\\s*(true|false)\\s*&&\\s*" +
            "query_category\\s*==\\s*(doc|scenary|person|location|time)\\s*&&\\s*" +
            "(\\[.*])\\]\\s*$",
    )
    private val isoDateToken = Regex("\\b\\d{4}(?:-\\d{2}(?:-\\d{2})?)?\\b")

    data class PlannedQuery(
        val plan: QueryPlan,
        val session: GemmaRuntime.ConversationSession?,
        val plannerJson: String = "",
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
    ): PlannedQuery {
        val appContext = context.applicationContext
        val planningQuery = normalizePlannerQuery(query)
        check(isModelInstalled(appContext)) {
            "A selected Gemma 4 model is required for query planning"
        }

        var session: GemmaRuntime.ConversationSession? = null
        return try {
            session = GemmaRuntime.takePrefilledPlannerSession()
                ?: GemmaRuntime.shared(appContext).createPlannerConversation(plannerSystemInstruction())
            val generationProfiles = ArrayList<GemmaRuntime.GenerationProfile>()
            var candidateRaw = session.generate(
                plannerUserPrompt(planningQuery, knownPersonLabels, selfPersonLabel),
            )
                .trim()
                .take(MAX_OUTPUT_CHARS)
            session.lastGenerationProfile?.let(generationProfiles::add)
            var repairUsed = false
            var repairAttempt = 0
            lateinit var parsed: QueryPlan
            while (true) {
                try {
                    parsed = compileGemmaPlan(
                        candidateRaw,
                        planningQuery,
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
                            query = planningQuery,
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
        You are Ask Galaxy's private query compiler. For PLANNER_TASK return ONLY one balanced expression: no prose, Markdown, JSON, labels, or explanation.
        Emit only these query fields: person, location, time, semantic, keyword, and optional mime type. Do not emit query_category, answer_needed, ocr, people_only, metadata, dates, or any other field. Emit mime type when the user explicitly requests photos, videos, pdf, doc (the file format), messages, sms, calendar, contacts, call logs, or files, or when the user clearly asks about calling/speaking/phone history or texting/message history. Never infer it from the content word document/documents. A document may be a photographed gallery image and must remain searchable there. Use the canonical values photos, videos, pdf, doc, messages, sms, calendar, contacts, call_logs, or files.
        semantic is the meaningful searchable source/evidence concept, rewritten or typo-corrected from the user's intent. It must describe what record, object, document, scene, or event should be searched—not the attribute the user wants extracted. Remove question words, requested attributes such as cost/price/amount/number, time/date words, locations, person names, and MIME words because those have no semantic meaning there; put structured values in their own fields. For example, "SFO to London flight time" means [semantic == flight ticket], not "flight time". A document is searched through its semantic document meaning; do not create an OCR predicate. Natural questions about calling, speaking, phoning, or call history must use [mime type == call_logs] and a call-history semantic. Natural questions about texting, messages, SMS, or what someone wrote must use [mime type == messages] and a message-conversation semantic; never put the sender name in that semantic because sender identity is a hard sender scope.
        time is the actual temporal constraint: an ISO date, date range, relative period such as last week, year/month, or time of day. Never use event verbs or generic labels such as time, date, when, visit, went, happened, or recent as the value of time.
        keyword is mandatory whenever the query contains searchable content. Extract every unique meaningful content word or entity from the user's intent, including nouns, verbs, adjectives, compound-term components, corrected misspellings, explicit person names, and locations. Do not put generic record/container descriptors such as ticket, receipt, bill, invoice, document, file, message, calendar, contact, call log, record, event, or booking in keyword; keep those in semantic context. Person and location words must remain in keyword: gallery retrieval uses them as hybrid lexical evidence alongside structured person/location predicates, while other app sources have no equivalent metadata predicates. Do not put requested answer attributes such as cost, price, amount, total, spent, paid, count, or number in keyword; they are references to information to extract, not record text. Do not put a bare number-reference word in keyword. Exclude question scaffolding, dates/numbers, and MIME words represented by a mime type predicate. Correct split or misspelled proper names when possible: "spyde man" refers to the entity "spiderman", so use {spiderman}, not {spyde} and {man}. Write each keyword as its own brace; keyword is never a phrase: [keyword == {word1} && {word2} && {word3}].
        Possessive and relationship words must resolve to structured identity/media fields. "my photos", "photos of me", or "self photos" means [person == the exact self_person label supplied in PLANNER_TASK] plus [mime type == photos]; if self_person is Ravi, emit [person == Ravi], never literal Self. Apply the same self replacement for my/me/mine/myself and equivalent relationship wording wherever it means the user's identity. Never put photos, videos, pdf, or doc in keyword.
        Relationship queries such as "sister photos", "my sister photos", "brother videos", or "photos of my mother" must emit the corrected relationship as a symbolic replaceable person value: [person == {_sister_}], [person == {_brother_}], or [person == {_mother_}]. Do not resolve or validate relationship words against known_people in QP; a later search layer replaces the symbolic relationship token with the real person label. Correct close misspellings before wrapping the relationship, so "my mon number" becomes {_mom_}.
        Use && for intersection, + for additive/fused intent, - for exclusion, and comma for alternatives. Operators join complete predicates or groups only. Every without, with out, excluding, exclude, except, but not, not, or no clause must be a subtraction group: without Ravi subtracts [person == Ravi], without glasses subtracts [semantic == glasses], not from 2022 subtracts the 2022 time range, and not from Goa subtracts [location == Goa]. Do not leave a negative value in positive semantic or keyword.
        Examples:
        beach photos => [[mime type == photos] && [semantic == beach] && [keyword == {beach}]]
        spyde man move cost => [[semantic == spider man movie ticket] && [keyword == {spiderman} && {movie}]]
        my photos (self_person=Ravi) => [[person == Ravi] && [mime type == photos]]
        sister photos => [[person == {_sister_}] && [mime type == photos]]
        my sister photos => [[person == {_sister_}] && [mime type == photos]]
        my mon number => [person == {_mom_}] && [semantic == mom contact]
        Ravi dancing in Goa => [[[semantic == dancing] && [keyword == {Ravi} && {dancing} && {Goa}]] && [person == Ravi] && [location == Goa]]
        who is in beach photos => [semantic == beach] && [keyword == {beach}]
        where was the lighthouse photo taken => [location == lighthouse] && [semantic == lighthouse] && [keyword == {lighthouse}]
        when did I visit Goa on 5 October 2025 => [location == Goa] && [time == 2025-10-05]
        photos from last week => [time == last week] && [mime type == photos]
        messages from Ravi => [[mime type == messages] && [person == Ravi]]
        when did I call Ravi last time => [[mime type == call_logs] && [semantic == call history] && [keyword == {Ravi}]]
        what did Vanraj text me last week => [[mime type == messages] && [semantic == message conversation] && [keyword == {Vanraj}] && [time == last week]]
        without Ravi => [semantic == all] - [person == Ravi]
        excluding Ramani => [semantic == all] - [person == Ramani]
        without glasses => [semantic == all] - [semantic == glasses]
        not from 2022 => [semantic == all] - [[time == 2022-01-01] && [time == 2022-12-31]]
        not from Goa => [semantic == all] - [location == Goa]
        Ravi passport number => [semantic == passport identity document] && [keyword == {Ravi} && {passport}]
        what is my Aadhaar number => [semantic == Aadhaar identity document] && [keyword == {Aadhaar}]
        SFO to London flight time => [[location == SFO] && [location == London] && [semantic == flight ticket] && [keyword == {SFO} && {London} && {flight}]]
        show photos excluding selfies => [mime type == photos] - [semantic == selfie]
    """.trimIndent()

    /** Stable preface placed in the conversation KV cache before a query arrives. */
    fun plannerSystemInstruction(): String = COMPACT_PLANNER_SYSTEM_INSTRUCTION

    /* Previous long prompt retained temporarily for source-history context; it is not sent to either model.
    fun obsoletePlannerSystemInstruction(): String = """
        You are Ask Galaxy's private on-device query compiler. The selected Gemma 4 model is the only plan author.
        For PLANNER_TASK output ONLY one C-like expression: no reasoning, markdown, JSON, labels, or prose.

        Start exactly:
        [answer_needed == true|false] && [query_category == CATEGORY] &&
        CATEGORY is exactly doc, scenary, person, location, or time.

        Decide CATEGORY from the answer requested, before reading event nouns:
        - doc: search written content in gallery OCR/metadata plus non-gallery Messages, Calendar, My Files, call logs, and Contacts. Receipts, bills, invoices, tickets, passports, IDs, passwords, totals, dates, contacts, messages, appointments, and file text are doc.
        - person: who, whose, which person/people, or who else.
        - location: where, what/which place, places/cities visited, or destination.
        - time: when an event happened, what date, which dates/day, or what time.
        - scenary: visual objects, actions, appearance, activities, events, or any other visual search.
        Leading who/which people MUST be person. Leading where/what places MUST be location. Leading when/what date/what time/on which dates MUST be time even if a person, place, or scene is prominent. Exception: a date printed in a licence, passport, coupon, voucher, ticket, receipt, or screenshot is doc.

        Retrieval predicates use exactly one field from:
        answer_needed, people_only, person, mime type, from_date, to_date, location, semantic, ocr
        Each ordinary predicate contains exactly ONE `field == value`. NEVER put `+`, `-`, `&&`, comma, or another `==` inside an ordinary predicate value. The only exception is the required OCR keyword syntax [ocr == {word1} && {word2}], where each braced word is required in the same OCR text.

        Structured fields:
        - Known people is correction vocabulary, never a result list. Use a person predicate only for a person explicitly named in the query when it asks about presence in photos, scenes, places, times, or events. Correct only a close misspelling to the exact Known people label. A negated person must be subtracted.
        - PEOPLE-ONLY: when the query says only, alone, by themselves, or no other human/person, emit people_only with the complete allowed set. `me only` means `[person == Self] && [people_only == Self]`; `me with Ramani alone` means both positive person predicates plus `[people_only == Self, Ramani]`. This is a hard exact-face-set constraint: an untagged or additional face must make the photo fail.
        - SELF PERSON: the task may provide one `Self person` label. For presence/identity uses of I, me, my, mine, or myself, emit that exact label as a person predicate; examples include photos of me, who was with me, where I went, when I visited, what I wore, and my birthday photos. Do not create a person predicate for grammatical ownership/agency in doc queries such as my passport, my password, my receipt, or how much I spent. For a self identity-document query, place only the actual Self person name word(s) plus essential document word(s) in OCR. Never emit literal OCR keywords such as person, people, self, me, my, mine, myself, or owner. If Self person is `not set`, never invent one.
        - DOC PERSON NAMES: when a doc query names the document owner or subject, keep that name inside the ocr keywords; do not add a face/person predicate merely because the name is known. A passport, ID, mark sheet, bill, or account screenshot may contain the printed name without containing a tagged face.
        - Preserve one explicitly named place as location. Do not leave that place inside semantic. If multiple route endpoints are named, keep the route as semantic instead of choosing one.
        - Explicit photo/picture/image wording requires [mime type == photos]. Explicit video/clip wording requires [mime type == videos]. Correct spelling such as phootos. Movie ticket is content, never MIME.
        - Dates are ISO yyyy-MM-dd and appear only for an explicit temporal constraint. Resolve the complete requested range from Today. Last year is January 1 through December 31 of the previous year; last month is its full calendar month. One exact day requires equal from_date and to_date. After/before/since may use one open boundary. Never infer today.
        - semantic is one compact conceptual phrase. The gallery branch sends it to SigLIP; non-gallery sources send the original document question to EmbeddingGemma. Exclude question words, dates, time words, person names, location names, and MIME words already represented structurally.
        - OCR HYBRID FOR EVERY DOC QUERY: emit exactly one semantic predicate plus one OCR predicate joined with `+` inside one group: [[semantic == conceptual document phrase] + [ocr == {word1} && {word2}]]. The `&&` inside OCR means every braced word must occur in the same photo OCR text. A complete OCR match is perfect and always ranks above semantic-only document matches in the result grid and answer context. The outer `+` retains semantic-only fallback when no complete OCR match exists.
        - ocr is doc-only and contains 2-6 essential words likely to coexist on the intended document. Every word is separately braced and joined by `&&`; never write an OCR phrase, synonyms, or alternatives. Include the actual document subject name when known. For my passport with Self person Ravi, write [ocr == {Ravi} && {passport}], never person/self/me/my/owner aliases.
        - BROAD DOCUMENT EXPANSION: for an aggregate or collection question with no named merchant, item, event, or document, use one broad semantic phrase and only a small co-occurring OCR conjunction such as [ocr == {total} && {amount}]. Do not AND mutually exclusive document types such as receipt, bill, and invoice. Never use spending, expenses, finances, paperwork, or documents as OCR keywords.
        - Metadata-only co-occurrence queries and plural place/city lists need no positive semantic predicate. Do not invent relational phrases such as "appears with", "most frequent companion", "cities visited", "places visited", or "travel destination".
        - ANSWER_NEEDED: emit exactly one `[answer_needed == true|false]` predicate. Use true when the user asks for information to read, identify, count, explain, or answer (for example `passport number`). Use false for gallery-browsing requests whose requested result is only the photos/videos (for example `beach photos`, `me only`, or `me with Ramani alone`).

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
        beach photos => [answer_needed == false] && [query_category == scenary] && [[mime type == photos] && [semantic == beach]]
        me only => [answer_needed == false] && [query_category == scenary] && [[mime type == photos] && [person == Ravi] && [people_only == Ravi]]
        me with Ramani alone => [answer_needed == false] && [query_category == scenary] && [[mime type == photos] && [person == Ravi] && [person == Ramani] && [people_only == "Ravi, Ramani"]]
        who is in these beach photos => [answer_needed == true] && [query_category == person] && [[mime type == photos] && [semantic == beach]]
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
        passport number in the passport photo => [answer_needed == true] && [query_category == doc] && [[mime type == photos] && [[semantic == passport identity document] + [ocr == {passport} && {number}]]]
        what is my passport number, Self person Ravi => [query_category == doc] && [[semantic == passport identity document] + [ocr == {Ravi} && {passport}]]
        what is Ravi passport number => [query_category == doc] && [[semantic == passport identity document] + [ocr == {Ravi} && {passport}]]
        what is Ravi driving licence number => [query_category == doc] && [[semantic == driving licence identity document] + [ocr == {Ravi} && {licence}]]
        what is Ravi Aadhaar number => [query_category == doc] && [[semantic == Aadhaar identity card] + [ocr == {Ravi} && {Aadhaar}]]
        what is Ravi date of birth => [query_category == doc] && [[semantic == identity record date of birth] + [ocr == {Ravi} && {birth}]]
        what are Ravi exam marks => [query_category == doc] && [[semantic == exam mark sheet report card] + [ocr == {Ravi} && {marks}]]
        what is the Wi-Fi password => [query_category == doc] && [[semantic == Wi-Fi credential card] + [ocr == {wifi} && {password}]]
        how much did I spend on the odyssy movie ticket => [query_category == doc] && [[semantic == Odyssey movie ticket price] + [ocr == {Odyssey} && {ticket}]]
        what date is printed on my Odyssey movie ticket => [answer_needed == true] && [query_category == doc] && [[semantic == Odyssey movie ticket printed date] + [ocr == {Odyssey} && {ticket}]]
        what bike did I have => [answer_needed == true] && [query_category == scenary] && [semantic == motorcycle bike]
        how much did I spend last month => [query_category == doc] && [[from_date == LAST_MONTH_START] && [to_date == LAST_MONTH_END] && [[semantic == purchase receipts bills invoices payment confirmations] + [ocr == {total} && {amount}]]]
        photos that make great phone backgrounds => [query_category == scenary] && [[mime type == photos] && [semantic == beautiful phone wallpaper background]]
        clearest national park photo excluding selfies => [query_category == scenary] && [[[mime type == photos] && [semantic == clear national park landscape]] - [semantic == selfie]]
        which places did I visit in July 2025 from north to south excluding airport layovers => [query_category == location] && [[[from_date == 2025-07-01] && [to_date == 2025-07-31]] - [semantic == airport layover]] SORT_LOC
        where did my Bengaluru-to-Goa road trip stop for lunch => [query_category == location] && [semantic == lunch stop on Bengaluru-to-Goa road trip]
        where did we camp during the latest mountain trip => [query_category == location] && [semantic == mountain campsite] SORT_DATE
        which people were at the wedding but not Ravi => [query_category == person] && [[semantic == wedding] - [person == Ravi]]

        Replace all date placeholders in examples with concrete ISO dates calculated from Today.

        FINAL FORMAT OVERRIDE: regardless of the examples above, every PLANNER_TASK response MUST begin with
        [answer_needed == true|false] && [query_category == CATEGORY] &&
        and MUST contain exactly one answer_needed predicate. Missing or duplicate answer_needed is invalid.

    """.trimIndent()
    */

    private fun plannerUserPrompt(
        query: String,
        knownPersonLabels: List<String>,
        selfPersonLabel: String?,
    ): String =
        "PLANNER_TASK\n" +
            "today=${LocalDate.now()}\n" +
            "known_people=${knownPeopleText(knownPersonLabels)}\n" +
            "self_person=${selfPersonText(selfPersonLabel)}\n" +
            "query=${cleanQuery(query)}"

    private fun plannerRepairPrompt(
        query: String,
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
        return """
            PLANNER_TASK_REPAIR
            attempt=$repairAttempt
            original_query=${cleanQuery(query)}
            validator_error=$safeError
            invalid_expression=$safeOutput
            Return only a new balanced expression. Do not copy the invalid expression.
            Return only a balanced expression. Ordinary brackets contain one field == value; operators appear only between complete brackets. Use only semantic, location, person, time, keyword, and optional mime type. Emit mime type only when explicitly requested; allowed values are photos, videos, pdf, doc (the file format), messages, sms, calendar, contacts, call_logs, and files. The content words document/documents never create a MIME scope because a document may be a photographed gallery image. Keyword must retain explicit person and location names because gallery uses `(semantic OR keyword) AND person AND location AND time`, while other app sources use `(semantic OR keyword)` without separate metadata fields. Exclude requested attributes such as cost, price, amount, total, spent, paid, count, and number, plus stopwords, question scaffolding, dates/numbers, and represented MIME words. Use one brace per word, never a phrase. Never emit query_category, answer_needed, ocr, or people_only. Every without, excluding, not, or no clause must use `-`, for example without glasses => [semantic == all] - [semantic == glasses] and not from Goa => [semantic == all] - [location == Goa]. Keep the concrete visible object/action in semantic and remove requested answer attributes and numbers from semantic. For "spyde man move cost", return semantic "spider man movie ticket" and keywords {spiderman}, {movie}, {ticket}. For "my photos" with self_person Ravi, return person Ravi and mime type photos.
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

    private fun normalizePlannerQuery(query: String): String =
        query.trim()
            .replace(Regex("(?i)\\bwith\\s+out\\b"), "without")
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
        val repairedEnvelope = repairUnambiguousEnvelopeBracket(candidate)
        if (repairedEnvelope != candidate) {
            Log.w(TAG, "Recovered one missing planner envelope bracket: ${singleLineForLog(candidate)}")
        }
        val modelSpec = QueryExecutionSpec.parse(repairedEnvelope)
        validatePlannerFields(modelSpec.root)
        val normalizedSpec = normalizeFiniteConstraints(
            modelSpec,
            query,
            knownPersonLabels,
            selfPersonLabel,
        )
        validatePlannerOcrSyntax(normalizedSpec.render())
        val plan = ExecutionSpecCompiler.compile(normalizedSpec).copy(
            // Category is derived from the user's requested result type. It
            // is routing metadata, not a planner-authored retrieval field.
            queryCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            needsAnswer = AnswerIntentPolicy.expected(query),
            answerIntentExplicit = AnswerIntentPolicy.expected(query),
        )
        validateCompiledPlan(query, knownPersonLabels, plan, selfPersonLabel)
        return plan
    }

    /**
     * Gemma authors retrieval intent, but a handful of planner fields are not
     * open-ended language decisions: the answer route, known face names,
     * explicit date window, media type, and requested sort all have one
     * canonical value derived directly from the user's words. Preserve the
     * model's semantic/OCR/grouping intent while replacing only those finite
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
            SelfPersonQueryPolicy.referencesSelfAsPerson(query) &&
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
        val expectedMedia = QueryStructuredIntentPolicy.expectedMediaType(query)
        // A negated year/month must never become the positive scope as well.
        // "photos not from 2022" means all matching photos minus 2022.
        val positiveConstraintQuery = removeNaturalNegativeClauses(query)
        val dateBounds = QueryScopeParser.explicitDateBoundsFromQuery(positiveConstraintQuery)
        val metadataOnly = QueryStructuredIntentPolicy.isMetadataOnlyIntent(query)
        val isDocumentQuery = expectedCategory == QueryCategory.DOC
        var retainedExpectedMedia = false

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
        val modelDocumentOcr = firstPositiveValue(ExecutionField.OCR).orEmpty()
        val documentGenericWords = setOf(
            "a", "an", "the", "how", "much", "what", "is", "are", "was", "were",
            "my", "current", "printed", "print", "cost", "amount", "price", "total",
            "number", "date", "time", "expiry", "expiration", "document",
        )
        val documentTypeWords = setOf(
            "ticket", "receipt", "receipts", "bill", "invoice", "passport", "licence",
            "license", "card", "voucher", "coupon", "screenshot", "statement", "booking",
            "boarding", "menu",
        )
        fun documentTokens(value: String): List<String> = value
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map(String::trim)
            .filter { it.length >= 2 }
            .distinctBy(String::lowercase)
        val documentSemanticTokens = documentTokens(modelDocumentSemantic.ifBlank { query })
        val documentSemantic = documentSemanticTokens
            .filter { it.lowercase() !in documentGenericWords }
            .joinToString(" ")
            .ifBlank { "document" }
        fun canonicalDocumentOcr(): String {
            val queryTokens = documentTokens(query)
            val usable = documentTokens(documentSemantic)
            val type = usable.firstOrNull { it.lowercase() in documentTypeWords }
            val anchors = usable.filter { token ->
                val lower = token.lowercase()
                lower !in documentTypeWords && lower !in setOf("movie", "identity") &&
                    queryTokens.any { queryToken ->
                        queryToken.equals(token, ignoreCase = true) ||
                            QuerySpellingMatcher.areClosePhrases(queryToken, token)
                    }
            }
            val chosen = when {
                mentionedKnownPeople.isNotEmpty() ->
                    listOf(mentionedKnownPeople.first()) + listOfNotNull(type ?: anchors.firstOrNull())
                anchors.isNotEmpty() && type != null -> listOf(anchors.first(), type)
                anchors.size >= 2 -> anchors.take(2)
                else -> OcrKeywordPolicy.keywords(modelDocumentOcr).take(2)
            }.toMutableList()
            if (chosen.size == 1) {
                chosen += when {
                    type != null && !chosen.first().equals(type, ignoreCase = true) -> type
                    else -> "amount"
                }
            }
            if (chosen.isEmpty()) chosen += listOf("total", "amount")
            return chosen.distinctBy(String::lowercase).take(6).joinToString(" ")
        }
        val documentOcr = canonicalDocumentOcr()

        fun stripNamedPeople(value: String): String {
            var cleaned = value
            namedPeople.forEach { label ->
                cleaned = cleaned.replace(
                    Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(label)}(?![\\p{L}\\p{N}])"),
                    " ",
                )
            }
            cleaned = cleaned
                .replace(Regex("(?i)\\b(?:and|with|by|of|at|in)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return cleaned.takeIf { it.isNotBlank() } ?: ""
        }

        fun retain(node: ExecutionNode, subtract: Boolean = false): ExecutionNode? = when (node) {
            is ExecutionNode.Predicate -> when (node.field) {
                ExecutionField.QUERY_CATEGORY,
                ExecutionField.FROM_DATE,
                ExecutionField.TO_DATE,
                ExecutionField.PEOPLE_ONLY,
                -> if (subtract && node.field != ExecutionField.PEOPLE_ONLY) node else null
                ExecutionField.PERSON -> if (subtract) {
                    node
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
                    } else if (isDocumentQuery || metadataOnly) null else {
                        stripNamedPeople(node.value).takeIf(String::isNotBlank)?.let {
                            node.copy(value = it)
                        }
                    }
                }
                ExecutionField.OCR -> if (subtract) node else null
                ExecutionField.TIME -> {
                    // Relative and numeric date phrases are canonicalized from
                    // the user's query below.  A model-authored TIME value can
                    // be an incorrect endpoint (for example, 2026-12-31 for
                    // "this year") and would intersect the real bounds into
                    // an empty result set. Keep TIME only when no deterministic
                    // date range was derived; subtraction predicates remain
                    // untouched.
                    if (subtract || dateBounds == null) node else null
                }
                // Person/location terms are deliberately retained in keyword.
                // Gallery uses them as hybrid lexical evidence; private
                // sources use them because they have no gallery metadata join.
                ExecutionField.KEYWORD -> {
                    if (subtract) {
                        node
                    } else {
                        val kept = node.value
                            .split(Regex("[^\\p{L}\\p{N}]+"))
                            .filter { it.isNotBlank() && it.lowercase() !in SearchKeywordPolicy.genericRecordWords }
                        kept.takeIf { it.isNotEmpty() }?.let {
                            node.copy(value = it.joinToString(" "))
                        }
                    }
                }
                else -> node
            }
            is ExecutionNode.Sorted -> retain(node.value, subtract)?.let {
                ExecutionNode.Sorted(it, node.sort)
            }
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
            root = intersect(root, ExecutionNode.Predicate(ExecutionField.SEMANTIC, documentSemantic))
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
        dateBounds?.let { (from, to) ->
            if (from.isNotBlank()) root = intersect(root, ExecutionNode.Predicate(ExecutionField.FROM_DATE, from))
            if (to.isNotBlank()) root = intersect(root, ExecutionNode.Predicate(ExecutionField.TO_DATE, to))
        }
        return QueryExecutionSpec(requireNotNull(root) { "QP must contain at least one field" })
    }

    /**
     * The planner occasionally omits the closing bracket immediately before the top-level
     * `&&` in a routing predicate, for example `[query_category == doc && ...`.
     * This changes no query term or operator: only the two fixed envelope fields
     * and their finite allowed values are eligible. The normal parser and every
     * production validator still run after this recovery.
     */
    internal fun repairUnambiguousEnvelopeBracket(candidate: String): String {
        return candidate
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
                SelfPersonQueryPolicy.referencesSelfAsPerson(query)
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
        validateKeywordValues(plan)
        validateRequestedAnswerAttributes(query, plan)

        QueryStructuredIntentPolicy.expectedMediaType(query)?.let { expectedMedia ->
            require(plan.mediaType == expectedMedia) {
                "Explicit media request requires [mime type == ${expectedMedia.label()}]"
            }
        }
        if (SelfPersonQueryPolicy.referencesSelfAsPerson(query)) {
            require(!selfPersonLabel.isNullOrBlank()) {
                "Self-media query requires a tagged self person"
            }
            require(plan.personNames.any { it.equals(selfPersonLabel, ignoreCase = true) }) {
                "Self-media query must use the tagged self person '$selfPersonLabel'"
            }
        }
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
            require(words.none { it.lowercase() in mimeWords }) {
                "keyword must not contain MIME type words"
            }
            require(words.none { it.lowercase() in setOf("number", "numbers", "num", "no") }) {
                "keyword must not contain a number-reference word"
            }
        }
    }

    private fun validateRequestedAnswerAttributes(query: String, plan: QueryPlan) {
        val normalized = query.lowercase()
        val requestedAttributes = answerAttributeWords.filter { word ->
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(normalized)
        }.toSet()
        if (requestedAttributes.isEmpty()) return
        val keywordWords = plan.keywordTerms
            .flatMap { it.split(Regex("[^\\p{L}\\p{N}]+")) }
            .filter(String::isNotBlank)
            .map(String::lowercase)
        require(keywordWords.none { it in requestedAttributes }) {
            "keyword must describe the record, not requested answer attributes: " +
                requestedAttributes.joinToString(", ")
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

/** Generic container/record labels belong in semantic context, not lexical keywords. */
internal object SearchKeywordPolicy {
    val genericRecordWords = setOf(
        "ticket", "tickets", "receipt", "receipts", "bill", "bills",
        "invoice", "invoices", "document", "documents", "file", "files",
        "message", "messages", "sms", "calendar", "calendars", "contact",
        "contacts", "call", "calls", "log", "logs", "appointment",
        "appointments", "record", "records", "event", "events", "booking",
        "bookings",
    )
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
        if (Regex(
                "\\b(?:message|messages|texted|text\\s+message|sms|calendar|appointment|meeting|" +
                    "contact|contacts|phone\\s+number|call\\s+log|called|call|file|files|pdf|docx?|" +
                    "spreadsheet|note|notes)\\b",
            ).containsMatchIn(query)
        ) return true
        if (
            Regex(
                "\\b(?:insurance|tax|property\\s+tax|receipts?|recipts?|bills?|invoices?|" +
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
        if (isMetadataOnlyIntent(query)) {
            require(plan.semanticQueries.isEmpty()) {
                "Metadata-only co-occurrence or place-list intent forbids a positive semantic predicate"
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
            "^(?:what|which|how\\s+many)\\s+(?:places|locations|cities)\\b.*\\bvisit(?:ed)?\\b",
        ).containsMatchIn(normalized)
        return coOccurrence || placeList
    }

    private val NEGATION_MARKER = Regex(
        "(?i)\\b(?:without|with\\s+out|excluding|exclude|except|but\\s+not|not|no)\\b",
    )
    private val EVENT_DISCOVERY_TERM = Regex(
        "(?i)\\b(?:outing|wedding|birthday|party|dinner|hiking|trek|camp|camping|" +
            "picnic|celebration|meeting|offsite)\\b",
    )
    private val SELF_OCR_ALIAS_WORDS = setOf(
        "person", "people", "self", "me", "my", "mine", "myself", "owner",
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
            "take|took|takes|photograph|photographed|capture|captured)\\b",
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
