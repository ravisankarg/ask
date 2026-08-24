package com.ravi.askgalaxy

import android.content.Context
import java.time.LocalDate

internal enum class QueryPlannerProtocol {
    V1,
    V2,
}

/** Persisted rollback switch. Existing installs start on V2; V1 remains intact. */
internal object QueryPlannerProtocolPreferences {
    private const val PREFERENCES = "query_planner_protocol"
    private const val USE_V2 = "use_v2"

    fun selected(context: Context): QueryPlannerProtocol =
        if (context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(USE_V2, true)
        ) {
            QueryPlannerProtocol.V2
        } else {
            QueryPlannerProtocol.V1
        }

    fun setV2Enabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(USE_V2, enabled)
            .apply()
    }
}

internal data class V2PlannerContext(
    val currentQuery: String,
    val previousQuery: String,
    val knownPeople: List<String>,
    val selfPerson: String?,
    val currentDate: LocalDate,
)

internal data class V2CompiledPlannerOutput(
    val resolvedQuery: String,
    val plan: QueryPlan,
    val plannerJson: String,
)

internal enum class V2LiteralKind {
    DATE,
    NUMBER,
    CLOCK,
}

internal data class V2LiteralReference(
    val id: String,
    val value: String,
    val kind: V2LiteralKind,
    val fromPrevious: Boolean,
)

/**
 * Mechanical copies of fragile user literals. E2B still decides whether a
 * literal is a date, range endpoint, amount, or clock by choosing an operator
 * and reference; Kotlin only resolves the selected reference without asking
 * the small model to reproduce digits exactly.
 */
internal object V2LiteralCatalog {
    private val isoDate = Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)")
    private val explicitClock = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:(?:0?[1-9]|1[0-2])(?::[0-5]\\d)?\\s*(?:am|pm)|" +
            "(?:[01]?\\d|2[0-3]):[0-5]\\d)(?![\\p{L}\\p{N}])",
    )
    private val number = Regex("(?<![\\p{L}\\p{N}])\\d{1,3}(?![\\p{L}\\p{N}])")

    fun fromContext(context: V2PlannerContext): List<V2LiteralReference> =
        extract(context.currentQuery, previous = false) +
            extract(context.previousQuery, previous = true)

    fun promptLine(query: String, previous: Boolean): String = extract(query, previous)
        .joinToString("|") { "${it.id}=${it.value}" }
        .ifBlank { "none" }

    private fun extract(query: String, previous: Boolean): List<V2LiteralReference> {
        if (query.isBlank()) return emptyList()
        val prefix = if (previous) "P" else ""
        val occupied = ArrayList<IntRange>()
        fun isFree(range: IntRange): Boolean = occupied.none { existing ->
            range.first <= existing.last && existing.first <= range.last
        }
        val result = ArrayList<V2LiteralReference>()
        isoDate.findAll(query).forEach { match ->
            if (runCatching { LocalDate.parse(match.value) }.isSuccess && isFree(match.range)) {
                occupied += match.range
                result += V2LiteralReference(
                    id = "${prefix}D${result.count { it.kind == V2LiteralKind.DATE }}",
                    value = match.value,
                    kind = V2LiteralKind.DATE,
                    fromPrevious = previous,
                )
            }
        }
        explicitClock.findAll(query).forEach { match ->
            if (isFree(match.range)) {
                occupied += match.range
                result += V2LiteralReference(
                    id = "${prefix}C${result.count { it.kind == V2LiteralKind.CLOCK }}",
                    value = match.value.replace(Regex("\\s+"), " ").trim(),
                    kind = V2LiteralKind.CLOCK,
                    fromPrevious = previous,
                )
            }
        }
        number.findAll(query).forEach { match ->
            if (isFree(match.range)) {
                occupied += match.range
                result += V2LiteralReference(
                    id = "${prefix}N${result.count { it.kind == V2LiteralKind.NUMBER }}",
                    value = match.value,
                    kind = V2LiteralKind.NUMBER,
                    fromPrevious = previous,
                )
            }
        }
        return result
    }
}

internal object V2FaceCatalog {
    fun promptLine(knownPeople: List<String>): String = knownPeople
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .mapIndexed { index, label -> "F$index=$label" }
        .joinToString("|")
        .ifBlank { "none" }

    fun resolve(reference: String, knownPeople: List<String>): String {
        val index = Regex("F(\\d{1,2})").matchEntire(reference)?.groupValues?.get(1)?.toIntOrNull()
        require(index != null && index in knownPeople.indices) {
            "Unknown face reference '$reference'"
        }
        return knownPeople[index].trim().also {
            require(it.isNotBlank()) { "Face reference '$reference' is blank" }
        }
    }
}

/** Model-authored typed IR. Kotlin validates provenance and compiles it mechanically. */
internal object QueryPlannerV2 {
    private const val MAX_JSON_DEPTH = 8
    private const val MAX_ARRAY_ITEMS = 12
    private const val MAX_STRING_CHARS = 256
    const val MAX_OUTPUT_CHARS = 768
    private const val MAX_OPERATIONS = 8

    val systemInstruction: String = """
        You are Ask Galaxy Query Planner V2.5. Return one compact JSON object only.
        You author all meaning. Kotlin validates fixed shapes, resolves references you selected, and executes the operations unchanged.

        ROOT
        {"v":2,"q":"standalone corrected query","intent":"browse|answer:text|answer:person|answer:location|answer:date","ops":[OP]}
        Add "prev":true only for a real follow-up whose subject is absent from current_query. Emit 1-8 operations. No extra keys, prose, nulls, or duplicate concepts.

        EXACT FIXED-ARITY OPERATIONS
        ["term",VALUE,"semantic|all|semantic+all"]
        ["person_ref","F#"] visual face only; ["self"] visual me/my; ["self_term"] written me/my owner; ["people_only"]
        ["media","photos|videos|pdf|doc|messages|sms|calendar|contacts|call_logs|files"]
        ["location",VALUE]
        ["travel","outside_normal"] unnamed trip/vacation only
        ["relative_date","this_year"]
        ["relative_date","last|past|since","N#|PN#","days|weeks|months|years"]
        ["calendar_period","this|last","week|month|year"]
        ["calendar_date","today|yesterday|tomorrow|future"]
        ["date_ref","on|after|from|since|before|until|through","D#|PD#"]
        ["date_between","D#|PD#","D#|PD#"]
        ["clock_ref","C#|PC#"]
        ["daypart","morning|afternoon|evening|night"]
        ["sort","newest|oldest|location"]
        ["not_term",VALUE]
        ["not_person_ref","F#"]

        REFERENCES
        face_refs are lookup choices, never defaults. Use person_ref only when the query explicitly names that visual person. current_literal_refs and previous_literal_refs are exact user literals. Select their IDs instead of copying digits. Never output a reference not supplied by the task.

        ANSWER-SLOT GATE
        For every answer:* intent, q keeps the full standalone question, but each term VALUE contains only the evidence subject to retrieve. Remove the requested value/field span from every term and not_term. Words such as number, amount, total, cost, price, balance, date, time, expiry/expiration, departure/arrival time, confirmation/reference number, phone number, email address, who, where, and when describe the answer slot; they are not evidence subjects. Keep the meaningful owner and document/concept words. This rule overrides compound preservation.
        Correct reductions: driving licence number -> driving licence; electricity bill amount -> electricity bill; hotel booking confirmation number -> hotel booking; flight ticket departure time -> flight ticket; vaccination certificate date -> vaccination certificate; Ravi phone number -> Ravi + phone; Ramani email address -> Ramani + email. Do not remove these words when they are actual browse content or constraints rather than an answer slot, e.g. SMS containing OTP or contacts with Bangalore address.

        SOURCE-INTENT GATE
        Routing words select a phone source and never become term text: call/called/calling/dialed -> media call_logs; text/texted/SMS -> media sms; message/messaged/chat/chatted -> media messages; calendar/event/appointment/meeting/scheduled -> media calendar. Apply this mapping only when the user's intent is those phone records, not when the word is actual content. Keep searchable payload such as person, topic, missed/incoming/outgoing status, and date in separate operations. A query without source intent emits no media and searches all sources. Emit media photos/videos/pdf/files only when the user explicitly asks to browse only that type; never guess one as the evidence source of an answer.

        PLAN
        1. Decide follow-up and intent. Browse shows records. answer:* extracts a fact; person=who, location=where, date=when.
        2. Assign each meaningful span once: source/type, visual person/self, place, travel, time, sort, exclusion, requested answer attribute, or content.
        3. Preserve a compound concept as one term: "cycling in rain", "dogs running", "indoors at night", "driving licence", "hotel booking", "vaccination certificate".
        4. semantic is conceptual/visual. all is mandatory written text. semantic+all is one written document concept requiring both. Never split one concept to assign multiple roles.
        5. In visual photo/video queries use person_ref/self. In written records or cross-source facts use term NAME all; written me/my uses self_term. Never use a face operator for a document owner or sender.
        6. Apply SOURCE-INTENT GATE. The routed phone source is the only media scope; keep its searchable payload. Contained media is content, e.g. videos received in messages -> media messages + term video semantic.
        7. Apply ANSWER-SLOT GATE before emitting operations. Never copy an answer attribute into a term just because it appears next to the subject.
        8. For gallery queries, scene/weather words rain, snow, beach, forest, and indoors are semantic, not locations; named places are location. In written/private records, a mentioned place is content term, not gallery location. A standalone daypart constraining records uses daypart, but preserve compounds such as "indoors at night" as one semantic scene.
        9. An unnamed trip/vacation/outing uses travel outside_normal. A named destination uses location and omits travel. recent/latest/newest adds sort newest; oldest adds sort oldest.
        10. Correct typos in q and VALUE. Do not alter supplied literal references.

        EXAMPLES
        current_query=cycling in rain
        {"v":2,"q":"cycling in rain","intent":"browse","ops":[["term","cycling in rain","semantic"]]}
        current_query=Ramani dancing photos; face_refs=F1=Ramani
        {"v":2,"q":"Ramani dancing photos","intent":"browse","ops":[["person_ref","F1"],["term","dancing","semantic"],["media","photos"]]}
        current_query=my photos last 3 years; current_literal_refs=N0=3
        {"v":2,"q":"my photos last 3 years","intent":"browse","ops":[["self"],["media","photos"],["relative_date","last","N0","years"]]}
        current_query=messages from Ramani yesterday
        {"v":2,"q":"messages from Ramani yesterday","intent":"browse","ops":[["media","messages"],["term","Ramani","all"],["calendar_date","yesterday"]]}
        current_query=next appointment
        {"v":2,"q":"next appointment","intent":"browse","ops":[["media","calendar"],["calendar_date","future"],["sort","oldest"]]}
        current_query=Ravi called me yesterday
        {"v":2,"q":"Ravi called me yesterday","intent":"browse","ops":[["media","call_logs"],["term","Ravi","all"],["calendar_date","yesterday"]]}
        current_query=Ravi passport number
        {"v":2,"q":"Ravi passport number","intent":"answer:text","ops":[["term","Ravi","all"],["term","passport","semantic+all"]]}
        current_query=electricity bill amount
        {"v":2,"q":"electricity bill amount","intent":"answer:text","ops":[["term","electricity bill","semantic+all"]]}
        current_query=flight ticket departure time
        {"v":2,"q":"flight ticket departure time","intent":"answer:date","ops":[["term","flight ticket","semantic+all"]]}
        previous_query=Ravi passport number; current_query=expiry date
        {"v":2,"q":"Ravi passport expiry date","intent":"answer:date","ops":[["term","Ravi","all"],["term","passport","semantic+all"]],"prev":true}
        current_query=recent vacation
        {"v":2,"q":"recent vacation","intent":"browse","ops":[["travel","outside_normal"],["sort","newest"]]}
        current_query=my passport number
        {"v":2,"q":"my passport number","intent":"answer:text","ops":[["self_term"],["term","passport","semantic+all"]]}
        current_query=who sent passport photo
        {"v":2,"q":"who sent passport photo","intent":"answer:person","ops":[["term","passport photo","semantic+all"]]}
        current_query=where is my passport
        {"v":2,"q":"where is my passport","intent":"answer:location","ops":[["self_term"],["term","passport","semantic+all"]]}
        previous_query=Ramani birthday photos; current_query=where were these taken; face_refs=F1=Ramani
        {"v":2,"q":"where were Ramani birthday photos taken","intent":"answer:location","ops":[["person_ref","F1"],["term","birthday","semantic"],["media","photos"]],"prev":true}
        current_query=photos at Goa excluding beach
        {"v":2,"q":"photos at Goa excluding beach","intent":"browse","ops":[["media","photos"],["location","Goa"],["not_term","beach"]]}
    """.trimIndent()

    fun userPrompt(
        currentDate: LocalDate,
        query: String,
        knownPeople: List<String>,
        selfPerson: String?,
        previousQuery: String,
    ): String = "QP_V2_TASK\n" +
        "current_date=$currentDate\n" +
        "face_refs=${V2FaceCatalog.promptLine(knownPeople)}\n" +
        "self_face_available=${if (selfPerson.isNullOrBlank()) "no" else "yes"}\n" +
        "current_literal_refs=${V2LiteralCatalog.promptLine(query, previous = false)}\n" +
        "previous_literal_refs=${V2LiteralCatalog.promptLine(previousQuery, previous = true)}\n" +
        "previous_query=${previousQuery.ifBlank { "none" }}\n" +
        "current_query=$query"

    fun repairPrompt(
        currentDate: LocalDate,
        context: V2PlannerContext,
        validatorError: String,
        attempt: Int,
    ): String = """
        QP_V2_REPAIR
        attempt=$attempt
        current_date=$currentDate
        face_refs=${V2FaceCatalog.promptLine(context.knownPeople)}
        self_face_available=${if (context.selfPerson.isNullOrBlank()) "no" else "yes"}
        current_literal_refs=${V2LiteralCatalog.promptLine(context.currentQuery, previous = false)}
        previous_literal_refs=${V2LiteralCatalog.promptLine(context.previousQuery, previous = true)}
        previous_query=${context.previousQuery.ifBlank { "none" }}
        current_query=${context.currentQuery}
        validator_error=$validatorError
        The prior answer was rejected and is intentionally omitted. Start over from current_query. Return only one V2.5 JSON object.
        Use the exact fixed-arity grammar from the system instruction. person_ref/not_person_ref select one supplied F#. Dates, numbers, and clocks select supplied D#/N#/C# references; never copy their digits. Keep compound content in one term. Route call/text/message/calendar intent to its media source and omit routing words from terms. Use self only for visual presence and self_term for written ownership. For answer:* keep the requested field in q but remove it from every term VALUE. A complete current query omits prev.
    """.trimIndent()

    fun parseAndCompile(raw: String, context: V2PlannerContext): V2CompiledPlannerOutput {
        require(raw.length <= MAX_OUTPUT_CHARS) { "V2.1 JSON exceeds $MAX_OUTPUT_CHARS characters" }
        val root = StrictJsonParser(
            raw.trim(),
            maxDepth = MAX_JSON_DEPTH,
            maxArrayItems = MAX_ARRAY_ITEMS,
            maxStringChars = MAX_STRING_CHARS,
        ).parse().requireObject("root")
        root.requireAllowedKeys("v", "q", "intent", "ops", "prev")
        root.requireKeys("v", "q", "intent", "ops")
        require(root.requireInt("v") == 2) { "v must be 2" }
        val resolvedQuery = root.requireString("q").trim()
        require(resolvedQuery.isNotEmpty() && resolvedQuery.length <= MAX_STRING_CHARS) {
            "q must contain 1-$MAX_STRING_CHARS characters"
        }
        val usedPrevious = root.optionalBoolean("prev") ?: false
        if ("prev" in root.values) require(usedPrevious) { "Omit prev instead of emitting false" }
        if (usedPrevious) require(context.previousQuery.isNotBlank()) {
            "prev cannot be true when previous_query is empty"
        }
        val intent = root.requireEnum(
            "intent",
            setOf("browse", "answer:text", "answer:person", "answer:location", "answer:date"),
        )
        val operations = root.requireArray("ops").values
        require(operations.isNotEmpty()) { "ops must contain a retrieval operation" }
        require(operations.size <= MAX_OPERATIONS) { "ops may contain at most $MAX_OPERATIONS operations" }
        val canonicalOperations = ArrayList<List<String>>(operations.size)

        val semantic = ArrayList<Grounded>()
        val lexical = ArrayList<Grounded>()
        val people = ArrayList<Grounded>()
        val excludedSemantic = ArrayList<Grounded>()
        val excludedPeople = ArrayList<Grounded>()
        var peopleOnly: Grounded? = null
        var media: Grounded? = null
        var location: Grounded? = null
        var travel: Grounded? = null
        var date: DateRange? = null
        var clock: Grounded? = null
        var sort: Grounded? = null
        val uniqueOperations = LinkedHashSet<String>()
        val uniqueTerms = LinkedHashSet<String>()
        val literalReferences = V2LiteralCatalog.fromContext(context)

        fun literal(reference: String, kind: V2LiteralKind, path: String): V2LiteralReference {
            val resolved = literalReferences.singleOrNull { it.id == reference && it.kind == kind }
            require(resolved != null) { "$path references unknown ${kind.name.lowercase()} '$reference'" }
            require(!resolved.fromPrevious || usedPrevious) {
                "$path uses previous literal '$reference' without prev=true"
            }
            return resolved
        }

        fun direct(value: String, path: String): Grounded {
            val clean = value.trim()
            require(clean.isNotBlank()) { "$path value must not be blank" }
            return Grounded(clean, fromPrevious = false)
        }

        operations.forEachIndexed { index, rawOperation ->
            val path = "ops[$index]"
            val operation = rawOperation.requireArray(path)
            val kind = operation.requireString(0, path)
            val originalOperation = operation.values.indices.map { operation.requireString(it, path) }
            var canonicalOperation: List<String>? = null
            fun accept(fingerprint: String) {
                require(uniqueOperations.add(fingerprint.lowercase())) { "$path duplicates an earlier operation" }
            }
            when (kind) {
                "term" -> {
                    operation.requireSize(3..3, path)
                    val role = operation.requireEnum(2, setOf("semantic", "all", "semantic+all"), path)
                    val grounded = direct(operation.requireString(1, path), path)
                    require(uniqueTerms.add(grounded.value.lowercase())) {
                        "$path repeats term '${grounded.value}'; use semantic+all once"
                    }
                    accept("term:${grounded.value}")
                    if (role == "semantic" || role == "semantic+all") semantic += grounded
                    if (role == "all" || role == "semantic+all") lexical += grounded
                }
                "person_ref" -> {
                    operation.requireSize(2..2, path)
                    val grounded = direct(
                        V2FaceCatalog.resolve(operation.requireString(1, path), context.knownPeople),
                        path,
                    )
                    accept("person:${grounded.value}")
                    people += grounded
                    canonicalOperation = listOf("person", grounded.value)
                }
                "self" -> {
                    operation.requireSize(1..1, path)
                    val label = requireNotNull(context.selfPerson?.takeIf(String::isNotBlank)) {
                        "$path self face is unavailable"
                    }
                    val grounded = direct(label, path)
                    accept("person:${grounded.value}")
                    people += grounded
                    canonicalOperation = listOf("person", grounded.value)
                }
                "self_term" -> {
                    operation.requireSize(1..1, path)
                    val label = requireNotNull(context.selfPerson?.takeIf(String::isNotBlank)) {
                        "$path self identity is unavailable"
                    }
                    val grounded = direct(label, path)
                    require(uniqueTerms.add(grounded.value.lowercase())) {
                        "$path repeats term '${grounded.value}'"
                    }
                    accept("term:${grounded.value}")
                    lexical += grounded
                    canonicalOperation = listOf("term", grounded.value, "all")
                }
                "people_only" -> {
                    operation.requireSize(1..1, path)
                    val grounded = direct("people_only", path)
                    accept("people_only")
                    peopleOnly = grounded
                }
                "media", "location", "not_term" -> {
                    operation.requireSize(2..2, path)
                    val grounded = direct(operation.requireString(1, path), path)
                    accept("$kind:${grounded.value}")
                    when (kind) {
                        "media" -> {
                            require(media == null) { "Only one media operation is allowed" }
                            require(QueryMediaType.fromToken(grounded.value) != null) {
                                "Invalid media value '${grounded.value}'"
                            }
                            media = grounded
                        }
                        "location" -> {
                            require(location == null) { "Only one location operation is allowed" }
                            location = grounded
                        }
                        else -> excludedSemantic += grounded
                    }
                }
                "clock_ref" -> {
                    operation.requireSize(2..2, path)
                    require(clock == null) { "Only one clock operation is allowed" }
                    val resolved = literal(operation.requireString(1, path), V2LiteralKind.CLOCK, path)
                    val grounded = Grounded(resolved.value, resolved.fromPrevious)
                    require(EXPLICIT_CLOCK.matches(grounded.value)) { "$path resolved clock is invalid" }
                    accept("clock:${grounded.value}")
                    clock = grounded
                    canonicalOperation = listOf("clock", grounded.value)
                }
                "not_person_ref" -> {
                    operation.requireSize(2..2, path)
                    val grounded = direct(
                        V2FaceCatalog.resolve(operation.requireString(1, path), context.knownPeople),
                        path,
                    )
                    accept("not_person:${grounded.value}")
                    excludedPeople += grounded
                    canonicalOperation = listOf("not_person", grounded.value)
                }
                "travel" -> {
                    operation.requireSize(2..2, path)
                    require(travel == null) { "Only one travel operation is allowed" }
                    val grounded = direct(operation.requireString(1, path), path)
                    require(grounded.value == "outside_normal") {
                        "travel must use outside_normal"
                    }
                    accept("travel:${grounded.value}")
                    travel = grounded
                }
                "date_ref" -> {
                    operation.requireSize(3..3, path)
                    require(date == null) { "Only one date operation is allowed" }
                    val relation = operation.requireEnum(
                        1,
                        setOf("on", "after", "from", "since", "before", "until", "through"),
                        path,
                    )
                    val resolved = literal(operation.requireString(2, path), V2LiteralKind.DATE, path)
                    val (from, to) = when (relation) {
                        "on" -> resolved.value to resolved.value
                        "after", "from", "since" -> resolved.value to ""
                        "before", "until", "through" -> "" to resolved.value
                        else -> error("unreachable")
                    }
                    accept("date:$from:$to")
                    date = DateRange(from, to, resolved.fromPrevious)
                    canonicalOperation = listOf("date", from, to, "$relation ${resolved.value}")
                }
                "date_between" -> {
                    operation.requireSize(3..3, path)
                    require(date == null) { "Only one date operation is allowed" }
                    val fromRef = literal(operation.requireString(1, path), V2LiteralKind.DATE, path)
                    val toRef = literal(operation.requireString(2, path), V2LiteralKind.DATE, path)
                    val from = LocalDate.parse(fromRef.value)
                    val to = LocalDate.parse(toRef.value)
                    require(!from.isAfter(to)) { "$path start date must not exceed end date" }
                    accept("date:$from:$to")
                    date = DateRange(
                        from.toString(),
                        to.toString(),
                        fromRef.fromPrevious || toRef.fromPrevious,
                    )
                    canonicalOperation = listOf("date", from.toString(), to.toString(), "between")
                }
                "relative_date" -> {
                    require(date == null) { "Only one date operation is allowed" }
                    if (operation.values.size == 2) {
                        require(operation.requireString(1, path) == "this_year") {
                            "$path two-string relative date must use this_year"
                        }
                        val from = context.currentDate.withDayOfYear(1).toString()
                        val to = context.currentDate.toString()
                        accept("relative_date:this_year")
                        date = DateRange(from, to, fromPrevious = false)
                        canonicalOperation = listOf("date", from, to, "this year")
                    } else {
                        operation.requireSize(4..4, path)
                        val relation = operation.requireEnum(1, setOf("last", "past", "since"), path)
                        val amountRef = literal(
                            operation.requireString(2, path),
                            V2LiteralKind.NUMBER,
                            path,
                        )
                        val amountText = amountRef.value
                        require(Regex("[1-9]\\d{0,2}").matches(amountText)) {
                            "$path relative amount must be 1-999"
                        }
                        val amount = amountText.toLong()
                        val unit = operation.requireEnum(
                            3,
                            setOf("days", "weeks", "months", "years"),
                            path,
                        )
                        val from = when (unit) {
                            "days" -> context.currentDate.minusDays(amount)
                            "weeks" -> context.currentDate.minusWeeks(amount)
                            "months" -> context.currentDate.minusMonths(amount)
                            "years" -> context.currentDate.minusYears(amount)
                            else -> error("unreachable")
                        }.toString()
                        val to = context.currentDate.toString()
                        accept("relative_date:$relation:$amount:$unit")
                        date = DateRange(from, to, amountRef.fromPrevious)
                        canonicalOperation = listOf("date", from, to, "$relation $amountText $unit")
                    }
                }
                "calendar_period" -> {
                    operation.requireSize(3..3, path)
                    require(date == null) { "Only one date operation is allowed" }
                    val relation = operation.requireEnum(1, setOf("this", "last"), path)
                    val unit = operation.requireEnum(2, setOf("week", "month", "year"), path)
                    val range = calendarPeriod(context.currentDate, relation, unit)
                    accept("calendar_period:$relation:$unit")
                    date = DateRange(range.first.toString(), range.second.toString(), false)
                    canonicalOperation = listOf(
                        "date",
                        range.first.toString(),
                        range.second.toString(),
                        "$relation $unit",
                    )
                }
                "calendar_date" -> {
                    operation.requireSize(2..2, path)
                    require(date == null) { "Only one date operation is allowed" }
                    val calendar = operation.requireEnum(
                        1,
                        setOf("today", "yesterday", "tomorrow", "future"),
                        path,
                    )
                    val (from, to) = when (calendar) {
                        "today" -> context.currentDate to context.currentDate
                        "yesterday" -> context.currentDate.minusDays(1) to context.currentDate.minusDays(1)
                        "tomorrow" -> context.currentDate.plusDays(1) to context.currentDate.plusDays(1)
                        "future" -> context.currentDate to null
                        else -> error("unreachable")
                    }
                    accept("calendar_date:$calendar")
                    date = DateRange(from.toString(), to?.toString().orEmpty(), fromPrevious = false)
                    canonicalOperation = listOf(
                        "date",
                        from.toString(),
                        to?.toString().orEmpty(),
                        calendar,
                    )
                }
                "sort" -> {
                    operation.requireSize(2..2, path)
                    require(sort == null) { "Only one sort operation is allowed" }
                    val grounded = direct(operation.requireString(1, path), path)
                    require(grounded.value in setOf("newest", "oldest", "location")) {
                        "sort must be newest, oldest, or location"
                    }
                    accept("sort:${grounded.value}")
                    sort = grounded
                }
                "daypart" -> {
                    operation.requireSize(2..2, path)
                    val grounded = direct(operation.requireString(1, path), path)
                    require(grounded.value in setOf("morning", "afternoon", "evening", "night")) {
                        "daypart must be morning, afternoon, evening, or night"
                    }
                    accept("daypart:${grounded.value}")
                    require(uniqueTerms.add(grounded.value.lowercase())) {
                        "$path repeats term '${grounded.value}'"
                    }
                    semantic += grounded
                    canonicalOperation = listOf("term", grounded.value, "semantic")
                }
                else -> throw IllegalArgumentException("Unknown operation '$kind' at $path")
            }
            canonicalOperations += canonicalOperation ?: originalOperation
        }
        require(peopleOnly == null || people.isNotEmpty()) { "people_only requires a person operation" }
        require(semantic.map { it.value.lowercase() }.intersect(excludedSemantic.map { it.value.lowercase() }.toSet()).isEmpty()) {
            "The same semantic term cannot be both included and excluded"
        }
        require(people.map { it.value.lowercase() }.intersect(excludedPeople.map { it.value.lowercase() }.toSet()).isEmpty()) {
            "The same person cannot be both included and excluded"
        }

        val category = when (intent) {
            "browse" -> QueryCategory.SCENARY
            "answer:text" -> QueryCategory.DOC
            "answer:person" -> QueryCategory.PERSON
            "answer:location" -> QueryCategory.LOCATION
            "answer:date" -> QueryCategory.TIME
            else -> error("unreachable")
        }
        val positive = ArrayList<ExecutionNode>()
        positive += ExecutionNode.Predicate(ExecutionField.QUERY_CATEGORY, category.wireName)
        positive += ExecutionNode.Predicate(ExecutionField.ANSWER_NEEDED, (intent != "browse").toString())
        people.forEach { positive += ExecutionNode.Predicate(ExecutionField.PERSON, it.value) }
        if (peopleOnly != null) {
            positive += ExecutionNode.Predicate(
                ExecutionField.PEOPLE_ONLY,
                people.joinToString(",") { it.value },
            )
        }
        media?.let { positive += ExecutionNode.Predicate(ExecutionField.MIME_TYPE, it.value) }
        location?.let { positive += ExecutionNode.Predicate(ExecutionField.LOCATION, it.value) }
        travel?.let { positive += ExecutionNode.Predicate(ExecutionField.TRAVEL, it.value) }
        date?.from?.takeIf(String::isNotBlank)?.let {
            positive += ExecutionNode.Predicate(ExecutionField.FROM_DATE, it)
        }
        date?.to?.takeIf(String::isNotBlank)?.let {
            positive += ExecutionNode.Predicate(ExecutionField.TO_DATE, it)
        }
        clock?.let { positive += ExecutionNode.Predicate(ExecutionField.TIME, it.value) }
        semantic.forEach { positive += ExecutionNode.Predicate(ExecutionField.SEMANTIC, it.value) }
        if (lexical.isNotEmpty()) {
            positive += ExecutionNode.Predicate(
                ExecutionField.KEYWORD,
                lexical.joinToString(" && ") { "{${it.value}}" },
            )
        }
        require(positive.size > 2) { "V2 plan must contain at least one retrieval field" }
        var expression = positive.reduce { left, right ->
            ExecutionNode.Binary(left, ExecutionBinaryOperator.INTERSECT, right)
        }
        excludedSemantic.forEach { excluded ->
            expression = ExecutionNode.Binary(
                expression,
                ExecutionBinaryOperator.SUBTRACT,
                ExecutionNode.Predicate(ExecutionField.SEMANTIC, excluded.value),
            )
        }
        excludedPeople.forEach { excluded ->
            expression = ExecutionNode.Binary(
                expression,
                ExecutionBinaryOperator.SUBTRACT,
                ExecutionNode.Predicate(ExecutionField.PERSON, excluded.value),
            )
        }
        expression = when (sort?.value) {
            "newest" -> ExecutionNode.Sorted(expression, ExecutionSort.DATE)
            "oldest" -> ExecutionNode.Sorted(expression, ExecutionSort.OLDEST)
            "location" -> ExecutionNode.Sorted(expression, ExecutionSort.LOCATION)
            else -> expression
        }
        val plan = ModelAuthoredPlanStructure.compile(QueryExecutionSpec(expression))
        return V2CompiledPlannerOutput(
            resolvedQuery = resolvedQuery,
            plan = plan,
            plannerJson = canonicalPlannerJson(
                resolvedQuery = resolvedQuery,
                intent = intent,
                operations = canonicalOperations,
                usedPrevious = usedPrevious,
            ),
        )
    }

    private fun canonicalPlannerJson(
        resolvedQuery: String,
        intent: String,
        operations: List<List<String>>,
        usedPrevious: Boolean,
    ): String = buildString {
        append("{\"v\":2,\"q\":").append(jsonString(resolvedQuery))
        append(",\"intent\":").append(jsonString(intent))
        append(",\"ops\":[")
        operations.forEachIndexed { operationIndex, operation ->
            if (operationIndex > 0) append(',')
            append('[')
            operation.forEachIndexed { valueIndex, value ->
                if (valueIndex > 0) append(',')
                append(jsonString(value))
            }
            append(']')
        }
        append(']')
        if (usedPrevious) append(",\"prev\":true")
        append('}')
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private val EXPLICIT_CLOCK = Regex(
        "(?i)^(?:(?:[01]?\\d|2[0-3]):[0-5]\\d|(?:0?[1-9]|1[0-2])(?::[0-5]\\d)?\\s*(?:am|pm))$",
    )

    private fun calendarPeriod(
        currentDate: LocalDate,
        relation: String,
        unit: String,
    ): Pair<LocalDate, LocalDate> {
        val thisStart = when (unit) {
            "week" -> currentDate.minusDays((currentDate.dayOfWeek.value - 1).toLong())
            "month" -> currentDate.withDayOfMonth(1)
            "year" -> currentDate.withDayOfYear(1)
            else -> error("unreachable")
        }
        if (relation == "this") return thisStart to currentDate
        val previousStart = when (unit) {
            "week" -> thisStart.minusWeeks(1)
            "month" -> thisStart.minusMonths(1)
            "year" -> thisStart.minusYears(1)
            else -> error("unreachable")
        }
        return previousStart to thisStart.minusDays(1)
    }

    private data class Grounded(
        val value: String,
        val fromPrevious: Boolean,
    )
    private data class DateRange(
        val from: String,
        val to: String,
        val fromPrevious: Boolean,
    )

}

private sealed interface JsonValue
private data class JsonObject(val values: Map<String, JsonValue>) : JsonValue {
    fun requireAllowedKeys(vararg allowed: String) {
        val allowedSet = allowed.toSet()
        require(values.keys.all { it in allowedSet }) {
            val extra = values.keys - allowedSet
            "Unexpected JSON keys: ${extra.joinToString()}"
        }
    }

    fun requireKeys(vararg required: String) {
        val missing = required.toSet() - values.keys
        require(missing.isEmpty()) { "Missing JSON keys: ${missing.joinToString()}" }
    }

    fun requireValue(key: String): JsonValue = requireNotNull(values[key]) { "Missing JSON key '$key'" }
    fun requireString(key: String): String = (requireValue(key) as? JsonString)?.value
        ?: throw IllegalArgumentException("$key must be a string")
    fun requireBoolean(key: String): Boolean = (requireValue(key) as? JsonBoolean)?.value
        ?: throw IllegalArgumentException("$key must be a boolean")
    fun optionalBoolean(key: String): Boolean? = values[key]?.let { value ->
        (value as? JsonBoolean)?.value ?: throw IllegalArgumentException("$key must be a boolean")
    }
    fun requireInt(key: String): Int = (requireValue(key) as? JsonNumber)?.value?.toIntOrNull()
        ?: throw IllegalArgumentException("$key must be an integer")
    fun requireArray(key: String): JsonArray = requireValue(key) as? JsonArray
        ?: throw IllegalArgumentException("$key must be an array")
    fun requireEnum(key: String, allowed: Set<String>): String = requireString(key).also { value ->
        require(value in allowed) { "$key must be one of ${allowed.joinToString()}" }
    }
}
private data class JsonArray(val values: List<JsonValue>) : JsonValue
private data class JsonString(val value: String) : JsonValue
private data class JsonBoolean(val value: Boolean) : JsonValue
private data class JsonNumber(val value: String) : JsonValue
private data object JsonNull : JsonValue

private fun JsonValue.requireObject(path: String): JsonObject = this as? JsonObject
    ?: throw IllegalArgumentException("$path must be an object")

private fun JsonValue.requireArray(path: String): JsonArray = this as? JsonArray
    ?: throw IllegalArgumentException("$path must be an array")

private fun JsonArray.requireSize(allowed: IntRange, path: String) {
    require(values.size in allowed) {
        "$path must contain ${allowed.first}-${allowed.last} string values"
    }
}

private fun JsonArray.requireString(index: Int, path: String): String =
    (values.getOrNull(index) as? JsonString)?.value
        ?: throw IllegalArgumentException("$path[$index] must be a string")

private fun JsonArray.optionalString(index: Int, path: String): String? =
    values.getOrNull(index)?.let { value ->
        (value as? JsonString)?.value
            ?: throw IllegalArgumentException("$path[$index] must be a string")
    }

private fun JsonArray.requireEnum(index: Int, allowed: Set<String>, path: String): String =
    requireString(index, path).also { value ->
        require(value in allowed) { "$path[$index] must be one of ${allowed.joinToString()}" }
    }

/** Small strict JSON parser so JVM contract tests and Android use identical behavior. */
private class StrictJsonParser(
    private val input: String,
    private val maxDepth: Int,
    private val maxArrayItems: Int,
    private val maxStringChars: Int,
) {
    private var index = 0

    fun parse(): JsonValue {
        require(input.length <= 4_096) { "V2 JSON exceeds 4096 characters" }
        val value = parseValue(1)
        skipWhitespace()
        require(index == input.length) { "Unexpected text after JSON object" }
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        require(depth <= maxDepth) { "JSON is too deeply nested" }
        skipWhitespace()
        return when (input.getOrNull(index)) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Invalid JSON value at character $index")
        }
    }

    private fun parseObject(depth: Int): JsonObject {
        expect('{')
        skipWhitespace()
        val values = LinkedHashMap<String, JsonValue>()
        if (consume('}')) return JsonObject(values)
        while (true) {
            skipWhitespace()
            require(input.getOrNull(index) == '"') { "JSON object key must be a string" }
            val key = parseString()
            require(key !in values) { "Duplicate JSON key '$key'" }
            skipWhitespace()
            expect(':')
            values[key] = parseValue(depth + 1)
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return JsonObject(values)
    }

    private fun parseArray(depth: Int): JsonArray {
        expect('[')
        skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (consume(']')) return JsonArray(values)
        while (true) {
            require(values.size < maxArrayItems) { "JSON array has too many items" }
            values += parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        return JsonArray(values)
    }

    private fun parseString(): String {
        expect('"')
        val output = StringBuilder()
        while (true) {
            val char = input.getOrNull(index++) ?: throw IllegalArgumentException("Unterminated JSON string")
            when (char) {
                '"' -> break
                '\\' -> {
                    val escaped = input.getOrNull(index++)
                        ?: throw IllegalArgumentException("Unterminated JSON escape")
                    output.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = input.substring(index, (index + 4).coerceAtMost(input.length))
                                require(hex.length == 4 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                    "Invalid JSON unicode escape"
                                }
                                index += 4
                                hex.toInt(16).toChar()
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        },
                    )
                }
                else -> {
                    require(char.code >= 0x20) { "Control character in JSON string" }
                    output.append(char)
                }
            }
            require(output.length <= maxStringChars) { "JSON string is too long" }
        }
        return output.toString()
    }

    private fun parseNumber(): JsonNumber {
        val start = index
        if (input.getOrNull(index) == '-') index += 1
        while (input.getOrNull(index)?.isDigit() == true) index += 1
        require(index > start && input.substring(start, index) != "-") { "Invalid JSON number" }
        return JsonNumber(input.substring(start, index))
    }

    private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
        require(input.startsWith(literal, index)) { "Invalid JSON literal" }
        index += literal.length
        return value
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        require(input.getOrNull(index) == expected) { "Expected '$expected' at character $index" }
        index += 1
    }

    private fun consume(expected: Char): Boolean {
        skipWhitespace()
        if (input.getOrNull(index) != expected) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (input.getOrNull(index)?.isWhitespace() == true) index += 1
    }
}
