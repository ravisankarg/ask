package com.ravi.askgalaxy

import java.time.LocalDate

/**
 * Canonical query execution language.
 *
 * Precedence follows C-style expression rules:
 * postfix SORT operators > additive + / - > logical && > comma.
 * Brackets are both predicate delimiters and explicit grouping delimiters.
 */
data class QueryExecutionSpec(val root: ExecutionNode) {
    fun render(): String = ExecutionSpecRenderer.render(root)

    /** Reads an optional legacy/internal category envelope. Compact QP never emits it. */
    fun queryCategoryOrNull(): QueryCategory? {
        val categories = ArrayList<ExecutionNode.Predicate>(1)
        fun collectCategories(node: ExecutionNode) {
            when (node) {
                is ExecutionNode.Predicate -> {
                    if (node.field == ExecutionField.QUERY_CATEGORY) categories += node
                }
                is ExecutionNode.Sorted -> collectCategories(node.value)
                is ExecutionNode.Binary -> {
                    collectCategories(node.left)
                    collectCategories(node.right)
                }
            }
        }
        collectCategories(root)
        if (categories.isEmpty()) return null
        require(categories.size == 1) { "Planner spec may contain at most one query_category" }
        val categoryPredicate = categories.single()
        return requireNotNull(QueryCategory.fromWireName(categoryPredicate.value)) {
            "query_category must be one of doc, scenary, person, location, time"
        }
    }

    fun requiredQueryCategory(): QueryCategory = queryCategoryOrNull() ?: QueryCategory.SCENARY

    /** Reads an optional legacy/internal answer-routing envelope. */
    fun answerNeededOrNull(): Boolean? {
        val answers = ArrayList<ExecutionNode.Predicate>(1)
        fun collect(node: ExecutionNode) {
            when (node) {
                is ExecutionNode.Predicate -> if (node.field == ExecutionField.ANSWER_NEEDED) answers += node
                is ExecutionNode.Sorted -> collect(node.value)
                is ExecutionNode.Binary -> {
                    collect(node.left)
                    collect(node.right)
                }
            }
        }
        collect(root)
        require(answers.size <= 1) { "Planner spec may contain at most one answer_needed" }
        return answers.singleOrNull()?.value?.let { value ->
            require(value == "true" || value == "false") {
                "answer_needed must be true or false"
            }
            value == "true"
        }
    }

    fun requiredAnswerNeeded(): Boolean = answerNeededOrNull() ?: true

    /**
     * Normalizes Gemma's routing predicate without planning any user intent.
     * All non-category predicates and operators remain model-authored.
     */
    fun canonicalizeCategoryEnvelope(): QueryExecutionSpec {
        fun withoutCategory(node: ExecutionNode): ExecutionNode? = when (node) {
            is ExecutionNode.Predicate -> if (node.field == ExecutionField.QUERY_CATEGORY) {
                null
            } else if (node.field == ExecutionField.ANSWER_NEEDED) {
                null
            } else {
                node
            }
            is ExecutionNode.Sorted -> withoutCategory(node.value)?.let {
                ExecutionNode.Sorted(it, node.sort)
            }
            is ExecutionNode.Binary -> {
                val left = withoutCategory(node.left)
                val right = withoutCategory(node.right)
                when {
                    left != null && right != null -> ExecutionNode.Binary(left, node.operator, right)
                    left != null -> left
                    right != null -> right
                    else -> null
                }
            }
        }
        val retrieval = requireNotNull(withoutCategory(root)) { "Planner spec must include a retrieval predicate" }
        return QueryExecutionSpec(retrieval)
    }

    companion object {
        fun parse(value: String): QueryExecutionSpec {
            val root = ExecutionSpecParser(value).parse()
            var predicates = 0
            fun validate(node: ExecutionNode, depth: Int) {
                require(depth <= MAX_DEPTH) { "Execution spec is too deeply nested" }
                when (node) {
                    is ExecutionNode.Predicate -> {
                        predicates += 1
                        require(predicates <= MAX_PREDICATES) {
                            "Execution spec has too many predicates"
                        }
                        require(node.value.length <= MAX_VALUE_CHARS) {
                            "Execution predicate is too long"
                        }
                        when (node.field) {
                            ExecutionField.MIME_TYPE -> require(
                                QueryMediaType.fromToken(node.value) != null,
                            ) {
                                "mime type must be one of photos, videos, pdf, doc, messages, sms, calendar, contacts, call_logs, or files"
                            }
                            ExecutionField.FROM_DATE,
                            ExecutionField.TO_DATE,
                            -> require(runCatching { LocalDate.parse(node.value) }.isSuccess) {
                                "${node.field.wireName} must be an ISO yyyy-MM-dd date"
                            }
                            ExecutionField.QUERY_CATEGORY -> require(
                                QueryCategory.fromWireName(node.value) != null,
                            ) {
                                "query_category must be one of doc, scenary, person, location, time"
                            }
                            else -> Unit
                        }
                    }
                    is ExecutionNode.Sorted -> validate(node.value, depth + 1)
                    is ExecutionNode.Binary -> {
                        validate(node.left, depth + 1)
                        validate(node.right, depth + 1)
                    }
                }
            }
            validate(root, 1)
            return QueryExecutionSpec(root)
        }

        private const val MAX_PREDICATES = 16
        private const val MAX_DEPTH = 20
        private const val MAX_VALUE_CHARS = 160
    }
}

enum class ExecutionField(val wireName: String) {
    QUERY_CATEGORY("query_category"),
    ANSWER_NEEDED("answer_needed"),
    PERSON("person"),
    PEOPLE_ONLY("people_only"),
    MIME_TYPE("mime type"),
    FROM_DATE("from_date"),
    TO_DATE("to_date"),
    LOCATION("location"),
    TIME("time"),
    SEMANTIC("semantic"),
    KEYWORD("keyword"),
    OCR("ocr"),
    ;

    companion object {
        fun fromWireName(value: String): ExecutionField? = when (
            value.lowercase().replace(Regex("\\s+"), " ").trim()
        ) {
            "query_category" -> QUERY_CATEGORY
            "answer_needed" -> ANSWER_NEEDED
            "person" -> PERSON
            "people_only" -> PEOPLE_ONLY
            "mime type" -> MIME_TYPE
            "from_date" -> FROM_DATE
            "to_date" -> TO_DATE
            "location" -> LOCATION
            "time" -> TIME
            "semantic" -> SEMANTIC
            "keyword" -> KEYWORD
            "ocr" -> OCR
            else -> null
        }
    }
}

enum class ExecutionBinaryOperator(val symbol: String, val precedence: Int) {
    UNION(",", 1),
    INTERSECT("&&", 2),
    ADD("+", 3),
    SUBTRACT("-", 3),
}

enum class ExecutionSort(val wireName: String) {
    DATE("SORT_DATE"),
    LOCATION("SORT_LOC"),
}

sealed interface ExecutionNode {
    data class Predicate(
        val field: ExecutionField,
        val value: String,
    ) : ExecutionNode

    data class Binary(
        val left: ExecutionNode,
        val operator: ExecutionBinaryOperator,
        val right: ExecutionNode,
    ) : ExecutionNode

    data class Sorted(
        val value: ExecutionNode,
        val sort: ExecutionSort,
    ) : ExecutionNode
}

object ExecutionSpecRenderer {
    fun render(node: ExecutionNode): String = render(node, parentPrecedence = 0)

    private fun render(node: ExecutionNode, parentPrecedence: Int): String = when (node) {
        is ExecutionNode.Predicate -> {
            val value = if (node.field == ExecutionField.OCR || node.field == ExecutionField.KEYWORD) {
                renderWordList(node.value)
            } else {
                renderValue(node.value)
            }
            "[${node.field.wireName} == $value]"
        }
        is ExecutionNode.Sorted -> {
            // SORT is a result-set modifier. Keep it at the expression tail so
            // the canonical form matches SQL/C-style reading without adding a
            // redundant outer bracket around the complete set expression.
            val body = render(node.value, 0)
            val rendered = "$body ${node.sort.wireName}"
            if (parentPrecedence > 0) "[$rendered]" else rendered
        }
        is ExecutionNode.Binary -> {
            val precedence = node.operator.precedence
            val left = render(node.left, precedence)
            // Add one to preserve left associativity for a right child with the
            // same precedence, especially A - (B + C).
            val right = render(node.right, precedence + 1)
            val rendered = "$left ${node.operator.symbol} $right"
            if (precedence < parentPrecedence) "[$rendered]" else rendered
        }
    }

    private fun renderValue(value: String): String {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        val needsQuotes = clean.isBlank() ||
            clean.any { it in charArrayOf('[', ']', ',', '+', '&', '"') } ||
            Regex("(^|\\s)-(\\s|$)").containsMatchIn(clean) ||
            Regex("\\b(?:SORT_DATE|SORT_LOC)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
            clean.contains(" == ") ||
            clean.contains('\\')
        if (!needsQuotes) return clean
        return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun renderWordList(value: String): String {
        val keywords = value
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
        return keywords.joinToString(" && ") { "{$it}" }
    }
}

private class ExecutionSpecParser(value: String) {
    private val tokens = tokenize(value)
    private var position = 0

    fun parse(): ExecutionNode {
        require(tokens.isNotEmpty()) { "Execution spec is empty" }
        val result = parseSorts(parseComma())
        require(peek() == null) { "Unexpected token '${peek()?.text}'" }
        return result
    }

    private fun parseComma(): ExecutionNode {
        var left = parseAnd()
        while (match(TokenKind.COMMA)) {
            left = ExecutionNode.Binary(left, ExecutionBinaryOperator.UNION, parseAnd())
        }
        return left
    }

    private fun parseAnd(): ExecutionNode {
        var left = parseAdditive()
        while (match(TokenKind.AND)) {
            left = ExecutionNode.Binary(left, ExecutionBinaryOperator.INTERSECT, parseAdditive())
        }
        return left
    }

    private fun parseAdditive(): ExecutionNode {
        var left = parsePrimary()
        while (true) {
            left = when {
                match(TokenKind.PLUS) ->
                    ExecutionNode.Binary(left, ExecutionBinaryOperator.ADD, parsePrimary())
                match(TokenKind.MINUS) ->
                    ExecutionNode.Binary(left, ExecutionBinaryOperator.SUBTRACT, parsePrimary())
                else -> return left
            }
        }
    }

    private fun parseSorts(node: ExecutionNode): ExecutionNode {
        var value = node
        while (true) {
            value = when {
                match(TokenKind.SORT_DATE) -> ExecutionNode.Sorted(value, ExecutionSort.DATE)
                match(TokenKind.SORT_LOC) -> ExecutionNode.Sorted(value, ExecutionSort.LOCATION)
                else -> return value
            }
        }
    }

    private fun parsePrimary(): ExecutionNode {
        expect(TokenKind.LEFT_BRACKET)
        if (peek()?.kind == TokenKind.LEFT_BRACKET) {
            val group = parseSorts(parseComma())
            expect(TokenKind.RIGHT_BRACKET)
            return group
        }

        val fieldText = collectUntil(TokenKind.EQUALS)
        expect(TokenKind.EQUALS)
        val field = ExecutionField.fromWireName(fieldText)
            ?: throw IllegalArgumentException("Unsupported execution field '$fieldText'")
        val value = if (field == ExecutionField.OCR || field == ExecutionField.KEYWORD) {
            collectWordListValue(field.wireName)
        } else {
            collectUntil(TokenKind.RIGHT_BRACKET)
        }
        expect(TokenKind.RIGHT_BRACKET)
        require(value.isNotBlank()) { "Execution predicate value is empty" }
        return ExecutionNode.Predicate(field, unquote(value))
    }

    /**
     * OCR is the one predicate whose displayed value is an explicit keyword
     * conjunction: [ocr == {Ravi} && {passport}]. It compiles to the compact
     * internal value "Ravi passport"; the AND tokens never become AST-level
     * set intersections.
     */
    private fun collectWordListValue(fieldName: String): String {
        val values = ArrayList<String>()
        var expectKeyword = true
        var usedExplicitAnd = false
        val requireBraces = fieldName == "keyword"
        while (peek() != null && peek()?.kind != TokenKind.RIGHT_BRACKET) {
            val token = peek()!!
            if (expectKeyword) {
                require(token.kind == TokenKind.WORD) {
                    "Expected {word} inside $fieldName predicate but found '${token.text}'"
                }
                val unquoted = unquote(token.text)
                val keyword = if (
                    unquoted.startsWith("{") &&
                    unquoted.endsWith("}") &&
                    unquoted.length > 2
                ) {
                    unquoted.substring(1, unquoted.lastIndex)
                } else {
                    require(!requireBraces) {
                        "$fieldName must use one braced word per list item"
                    }
                    unquoted
                }
                require(!keyword.any { it.isWhitespace() }) {
                    "$fieldName items must be individual words, not phrases"
                }
                require(keyword.isNotBlank() && '{' !in keyword && '}' !in keyword) {
                    "Invalid $fieldName word '${token.text}'"
                }
                values += keyword
                position += 1
                expectKeyword = false
            } else if (token.kind == TokenKind.AND) {
                usedExplicitAnd = true
                position += 1
                expectKeyword = true
            } else if (!usedExplicitAnd && token.kind == TokenKind.WORD) {
                // Backward-compatible parsing for previously persisted/logged word lists.
                expectKeyword = true
            } else {
                throw IllegalArgumentException(
                    "Expected && between $fieldName words but found '${token.text}'",
                )
            }
        }
        require(values.isNotEmpty() && !expectKeyword) {
            "$fieldName predicate must end with a word"
        }
        return values.joinToString(" ")
    }

    private fun collectUntil(kind: TokenKind): String {
        val values = ArrayList<String>()
        while (peek() != null && peek()?.kind != kind) {
            val token = peek()!!
            require(token.kind == TokenKind.WORD) {
                "Unexpected token '${token.text}' inside predicate"
            }
            values += token.text
            position += 1
        }
        return values.joinToString(" ").trim()
    }

    private fun unquote(value: String): String {
        val clean = value.trim()
        if (clean.length < 2 || clean.first() != '"' || clean.last() != '"') return clean
        return clean.substring(1, clean.lastIndex)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun match(kind: TokenKind): Boolean {
        if (peek()?.kind != kind) return false
        position += 1
        return true
    }

    private fun expect(kind: TokenKind) {
        val token = peek()
        require(token?.kind == kind) {
            "Expected $kind but found '${token?.text ?: "end of spec"}'"
        }
        position += 1
    }

    private fun peek(): Token? = tokens.getOrNull(position)

    private data class Token(val kind: TokenKind, val text: String)

    private enum class TokenKind {
        LEFT_BRACKET,
        RIGHT_BRACKET,
        EQUALS,
        PLUS,
        MINUS,
        AND,
        COMMA,
        SORT_DATE,
        SORT_LOC,
        WORD,
    }

    private companion object {
        fun tokenize(value: String): List<Token> {
            val output = ArrayList<Token>()
            var cursor = 0
            while (cursor < value.length) {
                when {
                    value[cursor].isWhitespace() -> cursor += 1
                    value[cursor] == '[' -> {
                        output += Token(TokenKind.LEFT_BRACKET, "[")
                        cursor += 1
                    }
                    value[cursor] == ']' -> {
                        output += Token(TokenKind.RIGHT_BRACKET, "]")
                        cursor += 1
                    }
                    value.startsWith("==", cursor) -> {
                        output += Token(TokenKind.EQUALS, "==")
                        cursor += 2
                    }
                    value.startsWith("&&", cursor) -> {
                        output += Token(TokenKind.AND, "&&")
                        cursor += 2
                    }
                    value[cursor] == '+' -> {
                        output += Token(TokenKind.PLUS, "+")
                        cursor += 1
                    }
                    value[cursor] == '-' && isMinusOperator(value, cursor) -> {
                        output += Token(TokenKind.MINUS, "-")
                        cursor += 1
                    }
                    value[cursor] == ',' -> {
                        output += Token(TokenKind.COMMA, ",")
                        cursor += 1
                    }
                    value[cursor] == '"' -> {
                        val start = cursor
                        cursor += 1
                        var escaped = false
                        while (cursor < value.length) {
                            val character = value[cursor]
                            cursor += 1
                            if (character == '"' && !escaped) break
                            escaped = character == '\\' && !escaped
                            if (character != '\\') escaped = false
                        }
                        require(value[cursor - 1] == '"') { "Unterminated quoted execution value" }
                        output += Token(TokenKind.WORD, value.substring(start, cursor))
                    }
                    else -> {
                        val start = cursor
                        while (cursor < value.length &&
                            !value[cursor].isWhitespace() &&
                            value[cursor] !in charArrayOf('[', ']', '+', ',') &&
                            !(value[cursor] == '-' && isMinusOperator(value, cursor)) &&
                            !value.startsWith("==", cursor) &&
                            !value.startsWith("&&", cursor)
                        ) {
                            cursor += 1
                        }
                        val word = value.substring(start, cursor)
                        output += when (word.uppercase()) {
                            "SORT_DATE" -> Token(TokenKind.SORT_DATE, word)
                            "SORT_LOC" -> Token(TokenKind.SORT_LOC, word)
                            else -> Token(TokenKind.WORD, word)
                        }
                    }
                }
            }
            return output
        }

        private fun isMinusOperator(value: String, index: Int): Boolean {
            val before = value.getOrNull(index - 1)
            val after = value.getOrNull(index + 1)
            val separatedBefore = before == null || before.isWhitespace() || before == ']'
            val separatedAfter = after == null || after.isWhitespace() || after == '['
            return separatedBefore && separatedAfter
        }
    }
}

/**
 * Compiles the Gemma planner AST into the bounded retrieval and answer-routing
 * model. Category and answer intent may be derived from the user's wording;
 * only retrieval predicates execute in [StructuredSearchExecutor].
 */
object ExecutionSpecCompiler {
    fun compile(
        spec: QueryExecutionSpec,
        derivedCategory: QueryCategory? = null,
        derivedAnswerNeeded: Boolean? = null,
    ): QueryPlan {
        val explicitCategory = spec.queryCategoryOrNull()
        val explicitAnswerNeeded = spec.answerNeededOrNull()
        val requiredAnswerNeeded = derivedAnswerNeeded ?: explicitAnswerNeeded ?: true
        val queryCategory = derivedCategory ?: explicitCategory ?: QueryCategory.SCENARY
        val canonicalSpec = spec.canonicalizeCategoryEnvelope()
        val semantic = ArrayList<String>()
        val keywords = ArrayList<String>()
        val negativeSemantic = ArrayList<String>()
        val ocr = ArrayList<String>()
        val negativeOcr = ArrayList<String>()
        val people = ArrayList<String>()
        val excludedPeople = ArrayList<String>()
        val onlyPeople = ArrayList<String>()
        var mediaType: QueryMediaType? = null
        var fromDate = ""
        var toDate = ""
        var location = ""
        var time = ""
        var sortDate = false
        var sortLocation = false

        fun visit(node: ExecutionNode, subtract: Boolean = false) {
            when (node) {
                is ExecutionNode.Predicate -> when (node.field) {
                    ExecutionField.ANSWER_NEEDED -> Unit
                    ExecutionField.QUERY_CATEGORY -> Unit
                    ExecutionField.PERSON ->
                        if (subtract) excludedPeople += node.value else people += node.value
                    ExecutionField.PEOPLE_ONLY -> if (!subtract) {
                        onlyPeople += node.value.split(',').map(String::trim).filter(String::isNotBlank)
                    }
                    ExecutionField.MIME_TYPE -> if (!subtract) {
                        mediaType = requireNotNull(QueryMediaType.fromToken(node.value)) {
                            "Invalid canonical MIME value '${node.value}'"
                        }
                    }
                    ExecutionField.FROM_DATE -> if (!subtract) fromDate = node.value
                    ExecutionField.TO_DATE -> if (!subtract) toDate = node.value
                    ExecutionField.LOCATION -> if (!subtract) location = node.value
                    ExecutionField.TIME -> if (!subtract) time = node.value
                    ExecutionField.SEMANTIC ->
                        if (subtract) negativeSemantic += node.value else semantic += node.value
                    ExecutionField.KEYWORD -> if (!subtract) keywords += node.value
                    ExecutionField.OCR ->
                        if (subtract) negativeOcr += node.value else ocr += node.value
                }
                is ExecutionNode.Sorted -> {
                    when (node.sort) {
                        ExecutionSort.DATE -> sortDate = true
                        ExecutionSort.LOCATION -> sortLocation = true
                    }
                    visit(node.value, subtract)
                }
                is ExecutionNode.Binary -> {
                    visit(node.left, subtract)
                    visit(
                        node.right,
                        subtract = subtract || node.operator == ExecutionBinaryOperator.SUBTRACT,
                    )
                }
            }
        }
        visit(canonicalSpec.root)
        val metadataFields = buildSet {
            if (people.isNotEmpty() || excludedPeople.isNotEmpty()) add(AnswerMetadataField.PEOPLE)
            if (fromDate.isNotBlank() || toDate.isNotBlank() || time.isNotBlank()) add(AnswerMetadataField.TIME)
            if (location.isNotBlank()) add(AnswerMetadataField.LOCATION)
        }
        val answerScope = queryCategory.answerEvidenceScope()
            .withMetadataFields(metadataFields)
        val cleanSemantic = semantic.distinctBy { it.lowercase() }
        val cleanOcr = ocr.distinctBy { it.lowercase() }
        return QueryPlan(
            semanticQueries = cleanSemantic,
            keywordTerms = keywords.distinctBy { it.lowercase() },
            metadataQueries = if (answerScope.needsMetadata || answerScope.needsOcr) {
                (cleanSemantic + cleanOcr).distinctBy { it.lowercase() }
            } else {
                emptyList()
            },
            personNames = people.distinctBy { it.lowercase() },
            onlyPersonNames = onlyPeople.distinctBy { it.lowercase() },
            ocrTerms = if (answerScope.needsOcr) cleanOcr else emptyList(),
            excludedPersonNames = excludedPeople.distinctBy { it.lowercase() },
            excludedOcrTerms = if (answerScope.needsOcr) {
                negativeOcr.distinctBy { it.lowercase() }
            } else {
                emptyList()
            },
            negativeSemanticQueries = negativeSemantic.distinctBy { it.lowercase() },
            fromDate = fromDate,
            toDate = toDate,
            locationHint = location,
            recentFirst = sortDate,
            sortByLocation = sortLocation,
            mediaType = mediaType,
            queryCategory = queryCategory,
            timeHint = time,
            needsAnswer = requiredAnswerNeeded,
            answerIntentExplicit = derivedAnswerNeeded != null || explicitAnswerNeeded != null,
            answerEvidenceScope = answerScope,
            executionSpec = canonicalSpec,
        )
    }
}
