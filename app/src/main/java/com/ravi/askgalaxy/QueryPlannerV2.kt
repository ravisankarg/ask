package com.ravi.askgalaxy

import android.content.Context
import java.time.LocalDate
import java.util.Locale

internal enum class QueryPlannerProtocol {
    V1,
    V2,
}

/** Persisted rollback switch. Existing installs start on V2; V1 remains intact. */
internal object QueryPlannerProtocolPreferences {
    private const val PREFERENCES = "query_planner_protocol"
    // New key intentionally makes AST the default on existing installs that
    // previously persisted the retired typed-planner trial as enabled.
    private const val USE_TYPED_PLANNER = "use_typed_planner_experimental"

    fun selected(context: Context): QueryPlannerProtocol =
        if (context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(USE_TYPED_PLANNER, false)
        ) {
            QueryPlannerProtocol.V2
        } else {
            QueryPlannerProtocol.V1
        }

    fun setV2Enabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(USE_TYPED_PLANNER, enabled)
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
        You are Ask Galaxy Query Planner V2.10. Compile the request into one compact JSON object only.
        You alone author the plan. Kotlin only validates and executes your operations unchanged. Never output prose, markdown, tool tags, comments, or an operation not listed below.

        ROOT
        {"v":2,"q":"standalone typo-corrected query","intent":"browse|answer:text|answer:person|answer:location|answer:date","ops":[OP]}
        Use 1-8 operations. Add "prev":true only when current_query lacks the subject and previous_query supplies it. Otherwise omit prev. No extra keys.

        EXACT OPERATION FORMS
        ["term",VALUE,"semantic|all|semantic+all"]
        ["person_ref","F#"] ["self"] ["self_term"] ["people_only"] ["not_person_ref","F#"]
        ["media","photos|videos|pdf|doc|messages|sms|calendar|contacts|call_logs|files"]
        ["location",VALUE] ["travel","outside_normal"] ["not_term",VALUE]
        ["relative_date","this_year"]
        ["relative_date","last|past|since","N#|PN#","days|weeks|months|years"]
        ["calendar_period","this|last","week|month|year"]
        ["calendar_date","today|yesterday|tomorrow|future"]
        ["date_ref","on|after|from|since|before|until|through","D#|PD#"]
        ["date_between","D#|PD#","D#|PD#"]
        ["clock_ref","C#|PC#"] ["daypart","morning|afternoon|evening|night"]
        ["sort","newest|oldest|location"]

        SILENTLY BUILD A COVERAGE LEDGER BEFORE JSON
        MEDIA | INCLUDED PEOPLE/SELF | PEOPLE_ONLY | EXCLUDED PERSON | LOCATION OR TRAVEL | DATE/DAYPART | SORT | POSITIVE CONTENT | EXCLUDED CONTENT
        Scan the whole current_query and fill every applicable column. Then emit one operation for every filled column. Do not stop after the first operation. Media, person, place, date, sort, and content are independent constraints and must coexist. A media-only plan is wrong whenever any meaningful word remains after removing generic media words. Finally re-read the query and JSON: no meaningful requested content may be missing.

        GALLERY RULES
        - A search, command, or noun phrase that asks to find/show/browse gallery items has intent browse. answer:* is only for extracting a who/where/when/written value.
        - photo/picture/pic/image/snap/shot, including obvious typos and plurals, fills MEDIA=photos and is not content. video/clip fills MEDIA=videos and is not content. selfie and portrait fill MEDIA=photos but also remain visual content. Descriptors such as blurry also remain visual content.
        - In a gallery query, a named person fills INCLUDED PEOPLE with the F# beside that exact label in this task's face_refs. The second value of person_ref/not_person_ref must be an F# ID, never a name. me/my/myself fills SELF using ["self"]. A typo that clearly matches one face label is corrected in q and uses that label's F#. Never turn a gallery person's name into a term.
        - only/alone/just fills PEOPLE_ONLY in addition to every included person/self. together includes every named person. without/no/excluding NAME fills EXCLUDED PERSON with that NAME's exact F#; it does not delete included people.
        - A proper named place or home fills LOCATION. beach, rain, snow, mountain, forest, sunset, party, birthday, indoor are visual content, not locations. An unnamed trip/vacation/travel fills TRAVEL. If a destination is named, use LOCATION and omit TRAVEL.
        - After removing all filled structural columns and connector words, preserve all remaining visual meaning in exactly one compound ["term",VALUE,"semantic"]. Keep modifiers and relations together: "food on a table", "cycling in rain", "dogs running on beach", "night city lights". Never split a scene into word terms. Never use all or semantic+all for an ordinary visual scene.
        - Explicit written/text/containing/says/reads content uses ["term",VALUE,"all"]. A photographed receipt, ticket, bill, certificate, or document may use one semantic+all term. Keep the requested written phrase; "screenshots containing payment failed" needs photos plus semantic screenshot plus all "payment failed".

        TIME, SORT, AND NEGATION
        - today/yesterday/tomorrow -> calendar_date. this/last week/month/year -> calendar_period. Exact phrase "this year" may instead use relative_date this_year.
        - last/past/since NUMBER units -> relative_date with the N# or PN# whose value is that NUMBER, plus the exact unit. Copy reference IDs; never copy, invent, edit, or merge raw digits. ISO dates use supplied D# or PD# references.
        - morning/afternoon/evening/night fills DAYPART only when it independently limits media, as in "night videos". Keep it inside one compound semantic when it describes a scene, as in "fireworks at night" or "night city lights".
        - latest/recent/newest fills SORT=newest; oldest fills SORT=oldest. A sort never replaces any other column.
        - except/excluding/without/not visual content fills EXCLUDED CONTENT using exactly ["not_term",VALUE]. Keep every positive column. Never make an excluded place a positive location.

        OTHER SOURCES
        call/dialed -> media call_logs; text/SMS -> media sms; message/chat -> media messages; appointment/meeting/calendar -> media calendar. In written records use term NAME all rather than a face, and written me/my uses self_term. For answer:* keep the question in q but remove answer-slot words such as number, amount, cost, date, time, expiry, who, where, when from retrieval terms.

        EXACT EXAMPLES
        food on a table -> {"v":2,"q":"food on a table","intent":"browse","ops":[["term","food on a table","semantic"]]}
        beach photos in Chennai -> {"v":2,"q":"beach photos in Chennai","intent":"browse","ops":[["media","photos"],["location","Chennai"],["term","beach","semantic"]]}
        videos of dancing -> {"v":2,"q":"videos of dancing","intent":"browse","ops":[["media","videos"],["term","dancing","semantic"]]}
        blurry photos -> {"v":2,"q":"blurry photos","intent":"browse","ops":[["media","photos"],["term","blurry","semantic"]]}
        fireworks at night -> {"v":2,"q":"fireworks at night","intent":"browse","ops":[["term","fireworks at night","semantic"]]}
        me only -> {"v":2,"q":"me only","intent":"browse","ops":[["self"],["people_only"]]}
        If face_refs=F7=Alice|F9=Bob, Alice and Bob together -> {"v":2,"q":"Alice and Bob together","intent":"browse","ops":[["person_ref","F7"],["person_ref","F9"]]}
        If face_refs=F7=Alice|F9=Bob, Alice photos without Bob -> {"v":2,"q":"Alice photos without Bob","intent":"browse","ops":[["person_ref","F7"],["not_person_ref","F9"],["media","photos"]]}
        If face_refs=F7=Alice, Alice alone -> {"v":2,"q":"Alice alone","intent":"browse","ops":[["person_ref","F7"],["people_only"]]}
        screenshots containing payment failed -> {"v":2,"q":"screenshots containing payment failed","intent":"browse","ops":[["media","photos"],["term","screenshot","semantic"],["term","payment failed","all"]]}
        photos this week -> {"v":2,"q":"photos this week","intent":"browse","ops":[["media","photos"],["calendar_period","this","week"]]}
        photos last 3 years; current_literal_refs=N0=3 -> {"v":2,"q":"photos last 3 years","intent":"browse","ops":[["media","photos"],["relative_date","last","N0","years"]]}
        latest vacation pictures -> {"v":2,"q":"latest vacation pictures","intent":"browse","ops":[["media","photos"],["travel","outside_normal"],["sort","newest"]]}
        photos at Goa excluding beach -> {"v":2,"q":"photos at Goa excluding beach","intent":"browse","ops":[["media","photos"],["location","Goa"],["not_term","beach"]]}
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
        The prior answer was rejected and omitted. Start from current_query again; do not patch or imitate it. Return one V2.10 JSON object only.
        Build this ledger and emit every filled column: MEDIA | INCLUDED PEOPLE/SELF | PEOPLE_ONLY | EXCLUDED PERSON | LOCATION OR TRAVEL | DATE/DAYPART | SORT | POSITIVE CONTENT | EXCLUDED CONTENT. Do not stop after one operation. Gallery search uses browse. Visual me/my uses self. only/alone/just adds people_only without deleting included people. person_ref/not_person_ref must copy the F# ID beside the requested face label, never the label text. Copy supplied D#/N#/C# IDs; never invent or alter digits. Use only exact operation forms. Keep all remaining visual meaning in one compound semantic, without generic media words. Ordinary scenes never use all. A complete current query omits prev.
        Fix validator_error literally: missing media -> add media; missing self -> add ["self"]; named people -> map every included/excluded name to its F#; face not named after a typo -> correct q to that face label; fragmented scene -> merge the full phrase; visual scene used as location -> move it to semantic; wrong operation size -> copy its exact form, especially ["not_term",VALUE]. Preserve every already requested constraint while fixing it.
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
        if (!ANSWER_EXTRACTION_WORDING.containsMatchIn(normalizeGrounding(context.currentQuery))) {
            require(intent == "browse") {
                "A non-question search without an answer-slot cue requires intent browse"
            }
        }
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
        val groundingQuery = buildString {
            append(context.currentQuery).append(' ').append(resolvedQuery)
            if (usedPrevious) append(' ').append(context.previousQuery)
        }
        require(digitTokens(context.currentQuery).all { it in digitTokens(resolvedQuery) }) {
            "q must preserve every digit sequence from current_query exactly"
        }

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
                    require(mentionsGroundedLabel(groundingQuery, grounded.value)) {
                        "$path selected face '${grounded.value}' that is not named by the query"
                    }
                    accept("person:${grounded.value}")
                    people += grounded
                    canonicalOperation = listOf("person", grounded.value)
                }
                "self" -> {
                    operation.requireSize(1..1, path)
                    require(hasExplicitSelfReference(groundingQuery)) {
                        "$path self requires explicit me/my/mine/myself wording"
                    }
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
                    require(hasExplicitSelfReference(groundingQuery)) {
                        "$path self_term requires explicit me/my/mine/myself wording"
                    }
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
                    require(mentionsGroundedLabel(groundingQuery, grounded.value)) {
                        "$path excluded face '${grounded.value}' is not named by the query"
                    }
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
        require(peopleOnly == null || EXCLUSIVE_PERSON_WORDING.containsMatchIn(groundingQuery)) {
            "people_only requires only/alone/just/no-other wording"
        }
        require(location == null || travel == null) {
            "A named location and outside_normal travel cannot both be mandatory"
        }
        location?.let { groundedLocation ->
            require(normalizeGrounding(groundedLocation.value) !in VISUAL_SCENE_NOT_LOCATIONS) {
                "location '${groundedLocation.value}' is a visual scene, not a named place"
            }
        }
        val normalizedCurrent = normalizeGrounding(context.currentQuery)
        val mediaType = media?.value?.let(QueryMediaType::fromToken)
        val galleryBrowse = intent == "browse" && (
            mediaType?.isGalleryType() == true ||
                (mediaType == null && !WRITTEN_EVIDENCE_WORDING.containsMatchIn(normalizedCurrent))
            )
        if (intent == "browse") {
            if (PHOTO_WORDING.containsMatchIn(normalizedCurrent)) {
                require(mediaType == QueryMediaType.PHOTOS) {
                    "Explicit photo/picture/pic/image/snap wording requires media photos"
                }
            }
            if (VIDEO_WORDING.containsMatchIn(normalizedCurrent)) {
                require(mediaType == QueryMediaType.VIDEOS) {
                    "Explicit video wording requires media videos"
                }
            }
            if (galleryBrowse && !WRITTEN_EVIDENCE_WORDING.containsMatchIn(normalizedCurrent)) {
                require(lexical.isEmpty()) {
                    "Ordinary gallery scenes cannot require written-text matching"
                }
            }
            if (galleryBrowse) {
                require(semantic.size <= 1) {
                    "Gallery visual meaning was fragmented; emit one compound term semantic"
                }
            }
            if (DATE_CONSTRAINT_WORDING.containsMatchIn(normalizedCurrent)) {
                require(date != null) { "Explicit date wording requires one date operation" }
            }
            if (NEWEST_WORDING.containsMatchIn(normalizedCurrent)) {
                require(sort?.value == "newest") { "latest/recent/newest requires sort newest" }
            }
            if (OLDEST_WORDING.containsMatchIn(normalizedCurrent)) {
                require(sort?.value == "oldest") { "oldest requires sort oldest" }
            }
        }
        val namedPeople = context.knownPeople.filter { mentionsGroundedLabel(groundingQuery, it) }
        val plannedPeople = (people + excludedPeople).map { normalizeGrounding(it.value) }.toSet()
        if (galleryBrowse) {
            require(namedPeople.all { normalizeGrounding(it) in plannedPeople }) {
                "Every explicitly named gallery person must use its matching person_ref or not_person_ref"
            }
        }
        if (
            galleryBrowse &&
            hasExplicitSelfReference(groundingQuery)
        ) {
            val self = context.selfPerson?.let(::normalizeGrounding)
            require(self != null && self in people.map { normalizeGrounding(it.value) }) {
                "Explicit me/my gallery presence requires self"
            }
        }
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
        val envelope = ExecutionNode.Binary(
            ExecutionNode.Predicate(ExecutionField.QUERY_CATEGORY, category.wireName),
            ExecutionBinaryOperator.INTERSECT,
            ExecutionNode.Predicate(ExecutionField.ANSWER_NEEDED, (intent != "browse").toString()),
        )
        val retrieval = ArrayList<ExecutionNode>()
        people.forEach { retrieval += ExecutionNode.Predicate(ExecutionField.PERSON, it.value) }
        if (peopleOnly != null) {
            retrieval += ExecutionNode.Predicate(
                ExecutionField.PEOPLE_ONLY,
                people.joinToString(",") { it.value },
            )
        }
        media?.let { retrieval += ExecutionNode.Predicate(ExecutionField.MIME_TYPE, it.value) }
        location?.let { retrieval += ExecutionNode.Predicate(ExecutionField.LOCATION, it.value) }
        travel?.let { retrieval += ExecutionNode.Predicate(ExecutionField.TRAVEL, it.value) }
        date?.from?.takeIf(String::isNotBlank)?.let {
            retrieval += ExecutionNode.Predicate(ExecutionField.FROM_DATE, it)
        }
        date?.to?.takeIf(String::isNotBlank)?.let {
            retrieval += ExecutionNode.Predicate(ExecutionField.TO_DATE, it)
        }
        clock?.let { retrieval += ExecutionNode.Predicate(ExecutionField.TIME, it.value) }
        semantic.forEach { retrieval += ExecutionNode.Predicate(ExecutionField.SEMANTIC, it.value) }
        if (lexical.isNotEmpty()) {
            retrieval += ExecutionNode.Predicate(
                ExecutionField.KEYWORD,
                lexical.joinToString(" && ") { "{${it.value}}" },
            )
        }
        require(retrieval.isNotEmpty()) { "V2 plan must contain at least one retrieval field" }
        var retrievalExpression = retrieval.reduce { left, right ->
            ExecutionNode.Binary(left, ExecutionBinaryOperator.INTERSECT, right)
        }
        excludedSemantic.forEach { excluded ->
            retrievalExpression = ExecutionNode.Binary(
                retrievalExpression,
                ExecutionBinaryOperator.SUBTRACT,
                ExecutionNode.Predicate(ExecutionField.SEMANTIC, excluded.value),
            )
        }
        excludedPeople.forEach { excluded ->
            retrievalExpression = ExecutionNode.Binary(
                retrievalExpression,
                ExecutionBinaryOperator.SUBTRACT,
                ExecutionNode.Predicate(ExecutionField.PERSON, excluded.value),
            )
        }
        retrievalExpression = when (sort?.value) {
            "newest" -> ExecutionNode.Sorted(retrievalExpression, ExecutionSort.DATE)
            "oldest" -> ExecutionNode.Sorted(retrievalExpression, ExecutionSort.OLDEST)
            "location" -> ExecutionNode.Sorted(retrievalExpression, ExecutionSort.LOCATION)
            else -> retrievalExpression
        }
        val expression = ExecutionNode.Binary(
            envelope,
            ExecutionBinaryOperator.INTERSECT,
            retrievalExpression,
        )
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

    private fun normalizeGrounding(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun mentionsGroundedLabel(query: String, label: String): Boolean {
        val normalizedQuery = " ${normalizeGrounding(query)} "
        val normalizedLabel = normalizeGrounding(label)
        return normalizedLabel.isNotBlank() && normalizedQuery.contains(" $normalizedLabel ")
    }

    private fun hasExplicitSelfReference(query: String): Boolean =
        Regex("(?i)(?<![\\p{L}\\p{N}])(me|my|mine|myself)(?![\\p{L}\\p{N}])")
            .containsMatchIn(query)

    private fun digitTokens(value: String): Set<String> = Regex("\\d+")
        .findAll(value)
        .map { it.value }
        .toSet()

    private val EXCLUSIVE_PERSON_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(only|alone|just|no\\s+other\\s+people)(?![\\p{L}\\p{N}])",
    )
    private val ANSWER_EXTRACTION_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:who|where|when|what|which|how\\s+many|number|amount|total|" +
            "cost|price|balance|expiry|expiration|phone\\s+number|email\\s+address)(?![\\p{L}\\p{N}])",
    )
    private val PHOTO_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:photos?|pictures?|pics?|images?|snaps?|shots?|selfies?|portraits?|" +
            "picturs?|fotoss?)(?![\\p{L}\\p{N}])|(?<![\\p{L}\\p{N}])photographed(?![\\p{L}\\p{N}])",
    )
    private val VIDEO_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:videos?|vidoes?|vido)(?![\\p{L}\\p{N}])",
    )
    private val WRITTEN_EVIDENCE_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:text|written|containing|contains|says|reads|receipt|receipts|" +
            "ticket|tickets|document|documents|passport|licence|license|bill|bills|certificate|certificates|screenshot|screenshots|" +
            "error\\s+message)(?![\\p{L}\\p{N}])",
    )
    private val DATE_CONSTRAINT_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:today|yesterday|tomorrow|week|month|year|years|before|after|" +
            "since|between|past|summer|winter|spring|autumn|fall|january|february|march|april|may|" +
            "june|july|august|september|october|november|december|\\d{4})(?![\\p{L}\\p{N}])",
    )
    private val NEWEST_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:latest|recent|newest)(?![\\p{L}\\p{N}])",
    )
    private val OLDEST_WORDING = Regex(
        "(?i)(?<![\\p{L}\\p{N}])oldest(?![\\p{L}\\p{N}])",
    )
    private val VISUAL_SCENE_NOT_LOCATIONS = setOf(
        "beach", "rain", "snow", "mountain", "mountains", "forest", "sunset", "sunsets",
        "party", "indoors", "indoor",
    )

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
