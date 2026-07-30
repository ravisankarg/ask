package com.ravi.askgalaxy

enum class AnswerEvidenceKind {
    VISUAL,
    OCR,
    METADATA,
    PERSONAL_CONTEXT,
}

/** Hard media-type constraint extracted from the user's query. */
enum class QueryMediaType {
    PHOTOS,
    VIDEOS,
    ;

    companion object {
        fun fromToken(value: String): QueryMediaType? = when (value.trim().lowercase()) {
            "photos" -> PHOTOS
            "videos" -> VIDEOS
            else -> null
        }
    }

    fun label(): String = when (this) {
        PHOTOS -> "photos"
        VIDEOS -> "videos"
    }

    fun mimePrefix(): String = when (this) {
        PHOTOS -> "image/"
        VIDEOS -> "video/"
    }
}

/**
 * Primary answer intent emitted by Gemma with every execution spec.
 *
 * `scenary` intentionally preserves the product vocabulary requested for the
 * broad visual/activity category. It is the only catch-all; the other values
 * route the Context Picker toward the evidence channel needed for the answer.
 */
enum class QueryCategory(val wireName: String) {
    DOC("doc"),
    SCENARY("scenary"),
    PERSON("person"),
    LOCATION("location"),
    TIME("time"),
    ;

    fun answerEvidenceScope(): AnswerEvidenceScope = when (this) {
        DOC -> AnswerEvidenceScope(
            kinds = setOf(
                AnswerEvidenceKind.OCR,
                AnswerEvidenceKind.METADATA,
                AnswerEvidenceKind.PERSONAL_CONTEXT,
            ),
        )
        SCENARY -> AnswerEvidenceScope(setOf(AnswerEvidenceKind.VISUAL))
        PERSON -> AnswerEvidenceScope(
            kinds = setOf(AnswerEvidenceKind.METADATA),
            metadataFields = setOf(AnswerMetadataField.PEOPLE),
        )
        LOCATION -> AnswerEvidenceScope(
            kinds = setOf(AnswerEvidenceKind.METADATA),
            metadataFields = setOf(AnswerMetadataField.LOCATION),
        )
        TIME -> AnswerEvidenceScope(
            kinds = setOf(AnswerEvidenceKind.METADATA),
            metadataFields = setOf(AnswerMetadataField.TIME),
        )
    }

    companion object {
        fun fromWireName(value: String): QueryCategory? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

enum class QueryOperationKind {
    ADD,
    UNION,
    INTERSECT,
    SUBTRACT,
    SORT,
}

/** Auditable set-operation view of a planner result. */
data class QueryOperation(
    val kind: QueryOperationKind,
    val field: String,
    val value: String,
)

enum class AnswerMetadataField {
    PEOPLE,
    TIME,
    LOCATION,
    ;

    companion object {
        fun fromTokens(tokens: List<String>): Set<AnswerMetadataField> =
            tokens.flatMap { it.split(Regex("[,;|+ ]")) }
                .mapNotNull { raw ->
                    when (raw.trim().lowercase()) {
                        "p", "person", "people", "persons", "face", "faces", "name", "names" -> PEOPLE
                        "t", "time", "date", "dates", "when", "capture", "captured" -> TIME
                        "l", "location", "place", "places", "where", "gps" -> LOCATION
                        else -> null
                    }
                }
                .toSet()
    }
}

/**
 * Minimum evidence channels needed by the final answer. Retrieval can still
 * use the visual index for discovery; this scope only controls what is sent
 * into Gemma 4 during answer generation.
 */
data class AnswerEvidenceScope(
    val kinds: Set<AnswerEvidenceKind>,
    /** Metadata fields are intentionally narrower than the metadata channel. */
    val metadataFields: Set<AnswerMetadataField> = emptySet(),
    val explicit: Boolean = false,
) {
    val needsVisual: Boolean
        get() = AnswerEvidenceKind.VISUAL in kinds
    val needsOcr: Boolean
        get() = AnswerEvidenceKind.OCR in kinds
    val needsMetadata: Boolean
        get() = AnswerEvidenceKind.METADATA in kinds
    val needsPersonalContext: Boolean
        get() = AnswerEvidenceKind.PERSONAL_CONTEXT in kinds
    val needsPeopleMetadata: Boolean
        get() = needsMetadata && AnswerMetadataField.PEOPLE in metadataFields
    val needsTimeMetadata: Boolean
        get() = needsMetadata && AnswerMetadataField.TIME in metadataFields
    val needsLocationMetadata: Boolean
        get() = needsMetadata && AnswerMetadataField.LOCATION in metadataFields

    fun withVisualSafety(): AnswerEvidenceScope =
        if (needsVisual) this else copy(kinds = kinds + AnswerEvidenceKind.VISUAL)

    fun withRequiredKinds(required: Set<AnswerEvidenceKind>): AnswerEvidenceScope =
        copy(kinds = kinds + required)

    fun withMetadataFields(required: Set<AnswerMetadataField>): AnswerEvidenceScope =
        if (required.isEmpty()) this else copy(
            kinds = kinds + AnswerEvidenceKind.METADATA,
            metadataFields = metadataFields + required,
        )

    fun label(): String = kinds
        .map {
            when (it) {
                AnswerEvidenceKind.VISUAL -> "visual"
                AnswerEvidenceKind.OCR -> "ocr"
                AnswerEvidenceKind.METADATA -> "metadata"
                AnswerEvidenceKind.PERSONAL_CONTEXT -> "context"
            }
        }
        .sorted()
        .joinToString("+")
        .ifBlank { "none" }

    companion object {
        fun all(explicit: Boolean = false): AnswerEvidenceScope = AnswerEvidenceScope(
            kinds = AnswerEvidenceKind.values().toSet(),
            metadataFields = AnswerMetadataField.values().toSet(),
            explicit = explicit,
        )

        fun fromTokens(tokens: List<String>): AnswerEvidenceScope? {
            val kinds = linkedSetOf<AnswerEvidenceKind>()
            tokens.flatMap { it.split(Regex("[,;|+]")) }.forEach { raw ->
                when (raw.trim().lowercase()) {
                    "v", "visual", "image", "images", "vision" -> kinds += AnswerEvidenceKind.VISUAL
                    "o", "ocr", "text", "words", "written" -> kinds += AnswerEvidenceKind.OCR
                    "m", "meta", "metadata", "people", "person", "time", "location" ->
                        kinds += AnswerEvidenceKind.METADATA
                    "c", "context", "personal", "notifications", "notification" ->
                        kinds += AnswerEvidenceKind.PERSONAL_CONTEXT
                    "all" -> kinds += AnswerEvidenceKind.values().toSet()
                }
            }
            return kinds.takeIf { it.isNotEmpty() }?.let {
                AnswerEvidenceScope(it, explicit = true)
            }
        }

        fun infer(query: String): AnswerEvidenceScope {
            val normalized = query.lowercase().replace(Regex("\\s+"), " ").trim()
            val asksTime = Regex(
                "\\b(when|date|dated|time|year|day|today|yesterday)\\b" +
                    "|\\b(last|this|previous)\\s+(month|week|year)\\b",
            ).containsMatchIn(normalized)
            val asksLocation = Regex(
                "\\b(where|location|place)\\b",
            ).containsMatchIn(normalized)
            val asksPeople = Regex(
                "\\b(who|whose|person|people)\\b",
            ).containsMatchIn(normalized)
            val asksMetadata = asksTime || asksLocation || asksPeople
            val asksOcr = Regex(
                "\\b(read|written|text|says|say|ocr|receipt|bill|invoice|amount|price|number|code|sign)\\b",
            ).containsMatchIn(normalized)
            val visualAssertion = Regex(
                "\\b(wearing|wear|glasses|smile|smiling|holding|drinking|sip|eating|standing|sitting|"
                    + "drank|ate|held|color|colour|look|looks|appearance|describe|scene|without)\\b",
            ).containsMatchIn(normalized)
            val context = Regex(
                "\\b(notification|notifications|message|messages|chat|email|mail|order|orders|payment|payments|transaction|transactions|ticket|tickets)\\b",
            ).containsMatchIn(normalized)
            // A time/location question may need visual retrieval to discover
            // the right event. Metadata/OCR remain authoritative for the
            // requested field, while the selected images are still auxiliary
            // context for event recognition and disambiguation.
            val visual = !asksTime && !asksLocation &&
                (!asksPeople || visualAssertion) &&
                (!asksOcr || visualAssertion) && (
                visualAssertion ||
                    Regex("\\b(photo|photos|picture|pictures|image|images|trip|scene)\\b")
                        .containsMatchIn(normalized)
                )
            val kinds = linkedSetOf<AnswerEvidenceKind>()
            if (asksMetadata) kinds += AnswerEvidenceKind.METADATA
            if (asksOcr) kinds += AnswerEvidenceKind.OCR
            if (visual) kinds += AnswerEvidenceKind.VISUAL
            if (context) kinds += AnswerEvidenceKind.PERSONAL_CONTEXT
            if (kinds.isEmpty()) {
                // A plain photo/scene request is answerable from visual
                // evidence. Add metadata only when the question asks for a
                // concrete field, or when the planner explicitly identifies
                // another required channel.
                kinds += AnswerEvidenceKind.VISUAL
            }
            val metadataFields = linkedSetOf<AnswerMetadataField>().apply {
                if (asksTime) add(AnswerMetadataField.TIME)
                if (asksLocation) add(AnswerMetadataField.LOCATION)
                if (asksPeople) add(AnswerMetadataField.PEOPLE)
            }
            return AnswerEvidenceScope(kinds, metadataFields)
        }
    }
}

/** Small, bounded routing result produced before gallery retrieval. */
data class QueryPlan(
    val semanticQueries: List<String>,
    val metadataQueries: List<String>,
    val personNames: List<String>,
    val ocrTerms: List<String>,
    val onlyPersonNames: List<String> = emptyList(),
    val excludedPersonNames: List<String> = emptyList(),
    val excludedOcrTerms: List<String> = emptyList(),
    val negativeSemanticQueries: List<String> = emptyList(),
    val assertions: List<String> = emptyList(),
    val timeHint: String = "",
    val fromDate: String = "",
    val toDate: String = "",
    val locationHint: String = "",
    val recentFirst: Boolean = false,
    val sortByLocation: Boolean = false,
    val needsPersonalContext: Boolean = false,
    val needsAnswer: Boolean = true,
    val answerIntentExplicit: Boolean = false,
    val mediaType: QueryMediaType? = null,
    val queryCategory: QueryCategory = QueryCategory.SCENARY,
    val answerEvidenceScope: AnswerEvidenceScope = AnswerEvidenceScope.all(),
    val executionSpec: QueryExecutionSpec? = null,
) {
    /**
     * Planner fields are executed as a small relational expression:
     * positive semantic/OCR branches are added or unioned, then hard fields
     * are intersected, and negative branches are subtracted before sorting.
     */
    fun operations(): List<QueryOperation> = buildList {
        var hasPositiveBranch = false
        semanticQueries.filter(String::isNotBlank).forEach { query ->
            add(
                QueryOperation(
                    if (hasPositiveBranch) QueryOperationKind.UNION else QueryOperationKind.ADD,
                    "semantic",
                    query,
                ),
            )
            hasPositiveBranch = true
        }
        ocrTerms.filter(String::isNotBlank).forEach { term ->
            add(
                QueryOperation(
                    if (hasPositiveBranch) QueryOperationKind.UNION else QueryOperationKind.ADD,
                    "ocr",
                    term,
                ),
            )
            hasPositiveBranch = true
        }
        personNames.forEach { add(QueryOperation(QueryOperationKind.INTERSECT, "person", it)) }
        locationHint.takeIf(String::isNotBlank)?.let {
            add(QueryOperation(QueryOperationKind.INTERSECT, "location", it))
        }
        timeHint.takeIf(String::isNotBlank)?.let {
            add(QueryOperation(QueryOperationKind.INTERSECT, "time", it))
        }
        fromDate.takeIf(String::isNotBlank)?.let {
            add(QueryOperation(QueryOperationKind.INTERSECT, "from_date", it))
        }
        toDate.takeIf(String::isNotBlank)?.let {
            add(QueryOperation(QueryOperationKind.INTERSECT, "to_date", it))
        }
        mediaType?.let { add(QueryOperation(QueryOperationKind.INTERSECT, "mime", it.label())) }
        excludedPersonNames.forEach { add(QueryOperation(QueryOperationKind.SUBTRACT, "person", it)) }
        negativeSemanticQueries.forEach { add(QueryOperation(QueryOperationKind.SUBTRACT, "semantic", it)) }
        excludedOcrTerms.forEach { add(QueryOperation(QueryOperationKind.SUBTRACT, "ocr", it)) }
        if (recentFirst) add(QueryOperation(QueryOperationKind.SORT, "time", "latest"))
        if (sortByLocation) add(QueryOperation(QueryOperationKind.SORT, "location", "ascending"))
    }

    fun operationSummary(): String = executionSpecString()

    fun executionSpecString(): String = canonicalExecutionSpec()?.render() ?: "(empty)"

    fun canonicalExecutionSpec(): QueryExecutionSpec? {
        executionSpec?.let { return it }
        val answerPredicate = ExecutionNode.Predicate(
            ExecutionField.ANSWER_NEEDED,
            needsAnswer.toString(),
        )
        val categoryPredicate = ExecutionNode.Predicate(
            ExecutionField.QUERY_CATEGORY,
            queryCategory.wireName,
        )
        val hardPredicates = buildList<ExecutionNode> {
            personNames.filter(String::isNotBlank).forEach {
                add(ExecutionNode.Predicate(ExecutionField.PERSON, it))
            }
            mediaType?.let {
                add(ExecutionNode.Predicate(ExecutionField.MIME_TYPE, it.label()))
            }
            fromDate.takeIf(String::isNotBlank)?.let {
                add(ExecutionNode.Predicate(ExecutionField.FROM_DATE, it))
            }
            toDate.takeIf(String::isNotBlank)?.let {
                add(ExecutionNode.Predicate(ExecutionField.TO_DATE, it))
            }
            locationHint.takeIf(String::isNotBlank)?.let {
                add(ExecutionNode.Predicate(ExecutionField.LOCATION, it))
            }
            onlyPersonNames.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                add(ExecutionNode.Predicate(ExecutionField.PEOPLE_ONLY, it.joinToString(",")))
            }
        }
        val retrievalPredicates: List<ExecutionNode> = semanticQueries
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .map { ExecutionNode.Predicate(ExecutionField.SEMANTIC, it) }
            .plus(
                ocrTerms
                    .filter(String::isNotBlank)
                    .distinctBy { it.lowercase() }
                    .map { ExecutionNode.Predicate(ExecutionField.OCR, it) },
            )
        val retrievalNode = retrievalPredicates.reduceOrNull { left, right ->
            ExecutionNode.Binary(left, ExecutionBinaryOperator.ADD, right)
        }
        val positiveNodes = hardPredicates + listOfNotNull(retrievalNode)
        var root = positiveNodes.reduceOrNull { left, right ->
            ExecutionNode.Binary(left, ExecutionBinaryOperator.INTERSECT, right)
        }
        val negativeNodes = buildList<ExecutionNode> {
            excludedPersonNames.filter(String::isNotBlank).forEach {
                add(ExecutionNode.Predicate(ExecutionField.PERSON, it))
            }
            negativeSemanticQueries
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .forEach {
                    add(ExecutionNode.Predicate(ExecutionField.SEMANTIC, it))
                }
            excludedOcrTerms
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .forEach {
                    add(ExecutionNode.Predicate(ExecutionField.OCR, it))
                }
        }
        if (root == null && negativeNodes.isNotEmpty()) {
            root = ExecutionNode.Predicate(
                ExecutionField.SEMANTIC,
                if (mediaType == QueryMediaType.VIDEOS) "video" else "photo",
            )
        }
        negativeNodes.forEach { negative ->
            root = ExecutionNode.Binary(
                requireNotNull(root),
                ExecutionBinaryOperator.SUBTRACT,
                negative,
            )
        }
        if (root != null) {
            root = ExecutionNode.Binary(
                answerPredicate,
                ExecutionBinaryOperator.INTERSECT,
                ExecutionNode.Binary(
                    categoryPredicate,
                    ExecutionBinaryOperator.INTERSECT,
                    root,
                ),
            )
        }
        if (recentFirst && root != null) root = ExecutionNode.Sorted(root, ExecutionSort.DATE)
        if (sortByLocation && root != null) root = ExecutionNode.Sorted(root, ExecutionSort.LOCATION)
        return root?.let(::QueryExecutionSpec)
    }

    companion object {
        @Deprecated(
            message = "Deterministic query planning is disabled; use QueryPlannerRuntime",
            level = DeprecationLevel.ERROR,
        )
        private fun fallback(query: String): QueryPlan {
            val normalized = query.trim()
                .replace(Regex("(?i)\\bwith\\s+out\\b"), "without")
                .replace(Regex("\\s+"), " ")
            val answerScope = AnswerEvidenceScope.infer(normalized)
            val negativeExtraction = extractNegativeClauses(normalized)
            val positiveText = negativeExtraction.positiveText
            val locationHint = extractLocationHint(normalized)
            val mediaType = extractMediaType(normalized)
            val explicitTimeHint = QueryScopeParser.explicitTimeHintFromQuery(normalized)
            val dateBounds = QueryScopeParser.explicitDateBoundsFromQuery(normalized)
            val semanticText = semanticCore(
                positiveText,
                locationHint,
                mediaType,
            )
            val keywords = (semanticText.ifBlank { positiveText })
                .split(" ")
                .map { it.trim(' ', ',', '.', '?', '!', ':', ';', '\"', '\'') }
                .filter { it.isNotBlank() && it.lowercase() !in STOP_WORDS }
                .distinctBy { it.lowercase() }
                .take(8)
            val compact = normalizeSemanticTerms(keywords.joinToString(" "))
            val negativeTerms = negativeExtraction.terms
            val negativeSemantic = negativeTerms
                .map { negativeVisualQuery(it) }
                .distinctBy { it.lowercase() }
                .take(3)
            return QueryPlan(
                // Keep a location/event phrase as a discovery branch. The
                // location intersection below still decides what survives;
                // this branch only finds candidates when location is stored
                // as EXIF/GPS rather than as a searchable text field.
                semanticQueries = listOfNotNull(
                    compact.takeIf { it.isNotBlank() },
                    locationHint.takeIf { it.isNotBlank() && compact.isBlank() }?.let { "$it trip" },
                ).distinct().ifEmpty {
                    // A pure subtraction still needs a positive universe to
                    // retrieve from: "photos without Ravi" should not turn
                    // into an empty search just because the positive words
                    // are all UI/media stop words. Keep it generic and let
                    // the hard MIME/person/visual subtraction scopes decide
                    // what survives.
                    negativeTerms.takeIf { it.isNotEmpty() }?.let {
                        listOf(if (mediaType == QueryMediaType.VIDEOS) "video" else "photo")
                    }.orEmpty()
                },
                metadataQueries = if (answerScope.needsOcr || answerScope.needsMetadata) {
                    listOfNotNull(compact.takeIf { it.isNotBlank() })
                } else {
                    emptyList()
                },
                personNames = emptyList(),
                // Keep a written-text phrase as one OCR branch. Expanding
                // "electricity bill" into independent `electricity` and
                // `bill` unions admits unrelated records that contain only
                // one token; the SQLite probe already ANDs the phrase terms.
                ocrTerms = if (answerScope.needsOcr) {
                    listOfNotNull(compact.takeIf { it.isNotBlank() })
                } else {
                    emptyList()
                },
                negativeSemanticQueries = negativeSemantic,
                locationHint = locationHint,
                excludedOcrTerms = if (answerScope.needsOcr) {
                    negativeTerms.map { it.trimEnd('.', '?', '!') }
                        .filter(String::isNotBlank)
                        .distinctBy { it.lowercase() }
                        .take(MAX_FALLBACK_OCR_TERMS)
                } else {
                    emptyList()
                },
                timeHint = explicitTimeHint.ifBlank { extractRelativeTimeHint(normalized) },
                fromDate = dateBounds?.first.orEmpty(),
                toDate = dateBounds?.second.orEmpty(),
                recentFirst = isRecentSortQuery(normalized),
                needsPersonalContext = answerScope.needsPersonalContext,
                mediaType = mediaType,
                answerEvidenceScope = answerScope,
            )
        }

        private val STOP_WORDS = setOf(
            "a", "about", "an", "and", "are", "at", "can", "do", "does", "did", "for", "from",
            "i", "in", "is", "me", "my", "of", "on", "or", "photo", "photos", "picture",
            "pictures", "show", "tell", "the", "to", "what", "when", "where", "which", "who",
            "with", "last", "latest", "newest", "recent", "most", "month", "months", "week",
            "weeks", "year", "years", "today", "yesterday", "this", "previous", "trip", "trips",
            "travel", "vacation", "holiday", "image", "images", "video", "videos", "how", "many", "so", "far",
            "visit", "visited", "visiting",
        )

        private val LOCATION_PATTERNS = listOf(
            Regex("(?i)\\b(?:trip|travel|vacation|holiday|visit(?:ed|ing)?)\\s+(?:to|in|at)\\s+([^,?.!;]+)"),
            Regex("(?i)\\b(?:last|latest|newest|most\\s+recent)\\s+([^,?.!;]+?)\\s+trips?\\b"),
            Regex("(?i)\\b(?:location|place)\\s*(?:is|:)?\\s+([^,?.!;]+)"),
            Regex("(?i)\\bphotos?\\s+(?:in|at|from)\\s+([^,?.!;]+)"),
            // Keep a trailing place after an infix exclusion, e.g.
            // "Ravi without glasses in Goa". More specific trip/photo
            // patterns above still win for ordinary queries.
            Regex("(?i)\\b(?:in|at|from)\\s+([^,?.!;]+)"),
        )
        private val LOCATION_END = Regex(
            "(?i)\\b(?:photos?|pictures?|images?|without|excluding|except|with|during|on|and|but|that|where)\\b",
        )
        private val GENERIC_LOCATION_WORDS = setOf(
            "a", "an", "the", "this", "that", "these", "those", "photo", "photos", "picture",
            "pictures", "image", "images", "morning", "evening", "night", "home", "bed",
        )
        private val EVENT_LOCATION_WORD = Regex(
            "(?i)\\b(outing|outings|birthday|birthdays|wedding|weddings|party|parties|" +
                "event|events|conference|conferences|concert|concerts|festival|festivals)\\b",
        )
        private const val MAX_FALLBACK_TEXT_CHARS = 120
        private const val MAX_FALLBACK_OCR_TERMS = 8

        private val NEGATIVE_MARKER = Regex(
            "(?i)\\b(?:without|excluding|except|not|no)\\s+",
        )
        private val NEGATIVE_BOUNDARY = Regex(
            "(?i)\\b(?:in|at|from|during|on|for|with|while|where|that|show|find|display)\\b",
        )
        private val NEGATIVE_TERM_SEPARATOR = Regex(
            "\\s+(?:and|but|while|where|that)\\s+",
            RegexOption.IGNORE_CASE,
        )

        private data class NegativeExtraction(
            val positiveText: String,
            val terms: List<String>,
        )

        /** Extracts subtraction spans while preserving later hard scopes. */
        private fun extractNegativeClauses(query: String): NegativeExtraction {
            val ranges = ArrayList<IntRange>()
            val clauses = ArrayList<String>()
            var cursor = 0
            while (cursor < query.length) {
                val marker = NEGATIVE_MARKER.find(query, cursor) ?: break
                val valueStart = marker.range.last + 1
                val nextMarker = NEGATIVE_MARKER.find(query, valueStart)
                val boundary = NEGATIVE_BOUNDARY.find(query, valueStart)
                val end = listOfNotNull(
                    nextMarker?.range?.first,
                    boundary?.range?.first,
                ).filter { it > valueStart }.minOrNull() ?: query.length
                val clause = query.substring(valueStart, end).trim()
                if (clause.isNotBlank()) {
                    clauses += clause
                    ranges += marker.range.first until end
                    cursor = end
                } else {
                    cursor = valueStart
                }
            }
            val positive = if (ranges.isEmpty()) {
                query
            } else {
                buildString {
                    var previousEnd = 0
                    ranges.forEach { range ->
                        append(query, previousEnd, range.first)
                        append(' ')
                        previousEnd = range.last + 1
                    }
                    append(query, previousEnd, query.length)
                }
            }.replace(Regex("\\s+"), " ").trim()
            val terms = clauses
                .flatMap { it.split(NEGATIVE_TERM_SEPARATOR) }
                .map {
                    it.trim()
                        .trimEnd('.', '?', '!')
                        .replace(Regex("(?i)\\s+(?:and|but|while|where|that)$"), "")
                        .trim()
                }
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .take(3)
            return NegativeExtraction(positive, terms)
        }

        private fun extractMediaType(query: String): QueryMediaType? = when {
            Regex("(?i)\\b(photo|photos|picture|pictures|image|images|still|stills)\\b").containsMatchIn(query) ->
                QueryMediaType.PHOTOS
            Regex("(?i)\\b(video|videos|movie|movies|clip|clips)\\b").containsMatchIn(query) ->
                QueryMediaType.VIDEOS
            // Ask Photos defaults to still images unless the query explicitly
            // requests video. OCR/document language still refers to photos of
            // those documents in the indexed gallery.
            else -> QueryMediaType.PHOTOS
        }

        private fun extractRelativeTimeHint(query: String): String = when {
            Regex("(?i)\\b(last|previous)\\s+month\\b").containsMatchIn(query) -> "last month"
            Regex("(?i)\\b(this|current)\\s+month\\b").containsMatchIn(query) -> "this month"
            Regex("(?i)\\b(last|previous)\\s+week\\b").containsMatchIn(query) -> "last week"
            Regex("(?i)\\b(this|current)\\s+week\\b").containsMatchIn(query) -> "this week"
            Regex("(?i)\\byesterday\\b").containsMatchIn(query) -> "yesterday"
            Regex("(?i)\\btoday\\b").containsMatchIn(query) -> "today"
            Regex("(?i)\\b(last|previous)\\s+year\\b").containsMatchIn(query) -> "last year"
            Regex("(?i)\\b(this|current)\\s+year\\b").containsMatchIn(query) -> "this year"
            else -> ""
        }

        private fun isRecentSortQuery(query: String): Boolean {
            if (Regex("(?i)\\b(latest|newest|most\\s+recent)\\b").containsMatchIn(query)) return true
            if (extractRelativeTimeHint(query).isBlank() && Regex(
                    "(?i)\\blast\\b.*\\b(?:trip|trips|visit|visits|vacation|holiday|outing|outings|event|events|photo|photos|picture|pictures|image|images|video|videos)\\b",
                ).containsMatchIn(query)
            ) return true
            return Regex(
                "(?i)\\blast\\s+(?:trip|trips|visit|visits|vacation|holiday|outing|outings|event|events|photo|photos|picture|pictures|image|images|video|videos|one|ones)\\b",
            ).containsMatchIn(query) && extractRelativeTimeHint(query).isBlank()
        }

        private fun semanticCore(
            text: String,
            locationHint: String,
            mediaType: QueryMediaType?,
        ): String {
            var value = text
            if (locationHint.isNotBlank()) {
                value = value.replace(Regex("(?i)\\b(?:to|in|at|from)\\s+${Regex.escape(locationHint)}\\b"), " ")
                value = value.replace(Regex("(?i)\\b${Regex.escape(locationHint)}\\b"), " ")
            }
            value = value
                .replace(Regex("(?i)\\b(last|previous|this|current)\\s+(month|week|year)\\b"), " ")
                .replace(Regex("(?i)\\b(today|yesterday|latest|newest|recent|most)\\b"), " ")
                .replace(Regex("(?i)\\b(without|excluding|except|not|no)\\b.*$"), " ")
            if (locationHint.isNotBlank()) {
                value = value.replace(Regex("(?i)\\b(trip|trips|travel|vacation|holiday)\\b"), " ")
            }
            mediaType?.let {
                value = value.replace(
                    Regex("(?i)\\b(photo|photos|picture|pictures|image|images|still|stills|video|videos|movie|movies|clip|clips)\\b"),
                    " ",
                )
            }
            return value.replace(Regex("\\s+"), " ").trim()
        }

        private fun negativeVisualQuery(value: String): String {
            return value.trim().take(MAX_FALLBACK_TEXT_CHARS)
        }

        private fun normalizeSemanticTerms(value: String): String = value
            .replace(Regex("(?i)\\bdate\\s+of\\s+birth\\b"), "birthday")
            .replace(Regex("(?i)\\bbirth\\s+date\\b"), "birthday")
            .replace(Regex("(?i)\\bbirthdate\\b"), "birthday")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun extractLocationHint(query: String): String {
            val raw = LOCATION_PATTERNS.asSequence()
                .mapNotNull { it.find(query)?.groupValues?.getOrNull(1) }
                .map { candidate -> LOCATION_END.split(candidate).firstOrNull().orEmpty() }
                .map { it.trim().trim(',', '.', ':', ';', '-', '"', '\'') }
                .map { it.replace(Regex("\\s+"), " ") }
                .map { it.removePrefix("the ").trim() }
                .firstOrNull { candidate ->
                    candidate.isNotBlank() &&
                        !Regex(
                            "(?i)^(last|latest|newest|most\\s+recent|previous|this)\\b",
                        ).containsMatchIn(candidate) &&
                        !EVENT_LOCATION_WORD.containsMatchIn(candidate) &&
                        candidate.lowercase() !in GENERIC_LOCATION_WORDS &&
                        !candidate.matches(Regex("\\d{4}"))
                }
                ?: return ""
            return raw.take(80)
        }
    }
}
