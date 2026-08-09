package com.ravi.askgalaxy

import android.util.Log

/**
 * Executes the canonical QP AST directly against SQLite metadata and the
 * resident semantic index. Set operators are evaluated recursively, so nested
 * union/intersection/subtraction expressions keep their formal semantics.
 */
class StructuredSearchExecutor(
    private val database: GalleryDatabase,
    private val semanticIndexer: GallerySemanticIndexer,
    private val metadataReader: GalleryMetadataReader,
) {
    private var activeQueryCategory: QueryCategory = QueryCategory.SCENARY

    data class ScoredGalleryResults(
        val media: List<GalleryMedia>,
        val cosineScores: Map<Long, Float>,
    )

    fun execute(
        spec: QueryExecutionSpec,
    ): List<GalleryMedia> = executeScored(spec).media

    fun executeScored(
        spec: QueryExecutionSpec,
    ): ScoredGalleryResults {
        // query_category was removed from QP.  Keep the compatibility value
        // for downstream answer policy, but retrieval starts with every
        // gallery row and narrows only on fields actually emitted by QP.
        val queryCategory = spec.requiredQueryCategory()
        activeQueryCategory = queryCategory
        val categoryAllowlist = database.allMediaStoreIds()
        if (categoryAllowlist.isEmpty()) return ScoredGalleryResults(emptyList(), emptyMap())
        val evaluated = evaluate(spec.root, categoryAllowlist)
        val scoreById = evaluated.scores.filterKeys { it in categoryAllowlist }
        if (scoreById.isEmpty()) return ScoredGalleryResults(emptyList(), emptyMap())
        val rows = database.findByMediaStoreIds(scoreById.keys.toLongArray())
        val comparator = when {
            queryCategory == QueryCategory.DOC -> documentComparator(
                scoreById = scoreById,
                sorts = evaluated.sorts,
            )
            queryCategory == QueryCategory.SCENARY ||
                queryCategory == QueryCategory.PERSON ||
                queryCategory == QueryCategory.LOCATION ->
                relevanceFirstComparator(
                    scoreById = scoreById,
                    sorts = evaluated.sorts,
                )
            ExecutionSort.DATE in evaluated.sorts ->
                compareByDescending<GalleryMedia> {
                    it.dateTakenMs ?: it.dateModifiedSeconds.takeIf { value -> value > 0L }?.times(1000L)
                }.thenByDescending { scoreById[it.mediaStoreId] ?: 0f }
            ExecutionSort.LOCATION in evaluated.sorts ->
                compareBy<GalleryMedia> {
                    (it.locationName ?: it.location).orEmpty().lowercase()
                }.thenByDescending { scoreById[it.mediaStoreId] ?: 0f }
            else ->
                compareByDescending<GalleryMedia> { scoreById[it.mediaStoreId] ?: 0f }
                    .thenByDescending { it.dateTakenMs ?: it.dateModifiedSeconds * 1000L }
        }
        // Hard predicates (people, dates, places, MIME type) are exact index
        // sets and can legitimately contain hundreds or thousands of rows.
        // Return the complete evaluated set; the UI virtualizes it and the
        // answer Context Picker independently chooses at most 8 items.
        val sorted = rows.sortedWith(comparator)
        return ScoredGalleryResults(
            media = sorted,
            cosineScores = evaluated.cosineScores
                .filterKeys { it in scoreById }
                .filterValues { it.isFinite() },
        )
    }

    /**
     * Scenery uses semantic relevance as its primary signal. Person and
     * location plans have already applied their exact indexed metadata scopes,
     * so semantic relevance orders records inside that authoritative scope.
     * Explicit date/location sorting and recency are tie-breakers.
     */
    private fun relevanceFirstComparator(
        scoreById: Map<Long, Float>,
        sorts: Set<ExecutionSort>,
    ): Comparator<GalleryMedia> {
        val relevance = compareByDescending<GalleryMedia> {
            scoreById[it.mediaStoreId] ?: 0f
        }
        return when {
            ExecutionSort.DATE in sorts ->
                relevance.thenByDescending {
                    it.dateTakenMs ?: it.dateModifiedSeconds.takeIf { value -> value > 0L }?.times(1000L)
                }
            ExecutionSort.LOCATION in sorts ->
                relevance.thenBy {
                    (it.locationName ?: it.location).orEmpty().lowercase()
                }
            else ->
                relevance.thenByDescending {
                    it.dateTakenMs ?: it.dateModifiedSeconds * 1000L
                }
        }
    }

    /**
     * Document results have two strict tiers. A complete OCR-keyword match is
     * always above semantic-only retrieval; requested sorting and relevance
     * operate only within each tier.
     */
    private fun documentComparator(
        scoreById: Map<Long, Float>,
        sorts: Set<ExecutionSort>,
    ): Comparator<GalleryMedia> {
        val tier = compareByDescending<GalleryMedia> {
            (scoreById[it.mediaStoreId] ?: 0f) >= OcrKeywordPolicy.PERFECT_MATCH_SCORE
        }
        return when {
            ExecutionSort.DATE in sorts ->
                tier.thenByDescending {
                    it.dateTakenMs ?: it.dateModifiedSeconds.takeIf { value -> value > 0L }?.times(1000L)
                }.thenByDescending { scoreById[it.mediaStoreId] ?: 0f }
            ExecutionSort.LOCATION in sorts ->
                tier.thenBy {
                    (it.locationName ?: it.location).orEmpty().lowercase()
                }.thenByDescending { scoreById[it.mediaStoreId] ?: 0f }
            else ->
                tier.thenByDescending { scoreById[it.mediaStoreId] ?: 0f }
                    .thenByDescending { it.dateTakenMs ?: it.dateModifiedSeconds * 1000L }
        }
    }

    private fun evaluate(
        node: ExecutionNode,
        categoryAllowlist: Set<Long>,
        allowSemanticFallback: Boolean = true,
    ): EvaluatedSet = when (node) {
        is ExecutionNode.Predicate ->
            evaluatePredicate(node, categoryAllowlist, allowSemanticFallback)
        is ExecutionNode.Sorted ->
            evaluate(node.value, categoryAllowlist, allowSemanticFallback).let {
                it.copy(sorts = it.sorts + node.sort)
            }
        is ExecutionNode.Binary -> {
            when (node.operator) {
                ExecutionBinaryOperator.INTERSECT ->
                    evaluateIntersection(node, categoryAllowlist, allowSemanticFallback)
                ExecutionBinaryOperator.SUBTRACT -> {
                    val left = evaluate(node.left, categoryAllowlist, allowSemanticFallback)
                    // A low-confidence nearest-neighbor fallback is useful for
                    // positive discovery, but unsafe for exclusions: it could
                    // subtract unrelated photos from an otherwise valid set.
                    // The negative branch is also scoped to the positive set,
                    // so "photos excluding selfies" does not search selfies
                    // across an unrelated MIME/date/person universe first.
                    val scopedIds = left.scores.keys.intersect(categoryAllowlist)
                    val right = if (scopedIds.isEmpty()) {
                        EvaluatedSet()
                    } else {
                        evaluate(
                            node.right,
                            scopedIds,
                            allowSemanticFallback = false,
                        )
                    }
                    subtract(left, right)
                }
                ExecutionBinaryOperator.UNION,
                ExecutionBinaryOperator.ADD,
                -> {
                    val left = evaluate(node.left, categoryAllowlist, allowSemanticFallback)
                    val right = evaluate(node.right, categoryAllowlist, allowSemanticFallback)
                    union(
                        left,
                        right,
                        fused = node.operator == ExecutionBinaryOperator.ADD,
                    )
                }
            }
        }
    }

    /**
     * Push a pure structured sibling into retrieval before lexical or semantic
     * ranking. Besides making fallback candidates more relevant, this prevents
     * a 512-neighbor search over the whole category from being emptied later
     * by MIME/person/date/place intersection.
     */
    private fun evaluateIntersection(
        node: ExecutionNode.Binary,
        categoryAllowlist: Set<Long>,
        allowSemanticFallback: Boolean,
    ): EvaluatedSet {
        val leftHasSemantic = containsScoredRetrieval(node.left)
        val rightHasSemantic = containsScoredRetrieval(node.right)
        return when {
            !leftHasSemantic && rightHasSemantic -> {
                val left = evaluate(node.left, categoryAllowlist, allowSemanticFallback)
                val scopedIds = left.scores.keys.intersect(categoryAllowlist)
                // The hard scope is authoritative. Search both semantic and
                // keyword channels inside it, instead of taking global top-K
                // neighbours and intersecting them afterwards.
                val right = if (scopedIds.isEmpty()) {
                    EvaluatedSet()
                } else {
                    evaluate(node.right, scopedIds, allowSemanticFallback)
                }
                intersect(left, right)
            }
            leftHasSemantic && !rightHasSemantic -> {
                val right = evaluate(node.right, categoryAllowlist, allowSemanticFallback)
                val scopedIds = right.scores.keys.intersect(categoryAllowlist)
                val left = if (scopedIds.isEmpty()) {
                    EvaluatedSet()
                } else {
                    evaluate(node.left, scopedIds, allowSemanticFallback)
                }
                intersect(left, right)
            }
            else -> {
                val left = evaluate(node.left, categoryAllowlist, allowSemanticFallback)
                val right = evaluate(node.right, categoryAllowlist, allowSemanticFallback)
                // semantic and keyword are the two hybrid channels. Their
                // conjunction in canonical QP means “use both signals”, not
                // “the same row must be found by both indexes”.
                if (leftHasSemantic && rightHasSemantic) {
                    union(left, right, fused = true)
                } else {
                    intersect(left, right)
                }
            }
        }
    }

    private fun containsScoredRetrieval(node: ExecutionNode): Boolean = when (node) {
        is ExecutionNode.Predicate ->
            node.field == ExecutionField.SEMANTIC ||
                node.field == ExecutionField.KEYWORD ||
                node.field == ExecutionField.OCR
        is ExecutionNode.Sorted -> containsScoredRetrieval(node.value)
        is ExecutionNode.Binary ->
            containsScoredRetrieval(node.left) || containsScoredRetrieval(node.right)
    }

    private fun evaluatePredicate(
        predicate: ExecutionNode.Predicate,
        categoryAllowlist: Set<Long>,
        allowSemanticFallback: Boolean,
    ): EvaluatedSet =
        when (predicate.field) {
            ExecutionField.QUERY_CATEGORY -> hardSet(categoryAllowlist)
            ExecutionField.ANSWER_NEEDED -> hardSet(categoryAllowlist)
            ExecutionField.PERSON -> {
                val labels = database.resolveNamedPersonLabels(listOf(predicate.value))
                hardSet(database.mediaStoreIdsForPersonLabels(labels).intersect(categoryAllowlist))
            }
            ExecutionField.PEOPLE_ONLY -> {
                val labels = database.resolveNamedPersonLabels(predicate.value.split(','))
                hardSet(database.mediaStoreIdsForOnlyPersonLabels(labels).intersect(categoryAllowlist))
            }
            ExecutionField.MIME_TYPE -> {
                val type = requireNotNull(QueryMediaType.fromToken(predicate.value)) {
                    "Invalid canonical MIME value '${predicate.value}'"
                }
                hardSet(database.mediaStoreIdsForMediaType(type).intersect(categoryAllowlist))
            }
            ExecutionField.FROM_DATE -> evaluateDate(
                fromDate = predicate.value,
                categoryAllowlist = categoryAllowlist,
            )
            ExecutionField.TO_DATE -> evaluateDate(
                toDate = predicate.value,
                categoryAllowlist = categoryAllowlist,
            )
            ExecutionField.TIME -> evaluateTime(predicate.value, categoryAllowlist)
            ExecutionField.LOCATION -> {
                val ids = database.mediaStoreIdsMatchingLocation(predicate.value)
                    ?: metadataReader.mediaStoreIdsMatchingLocation(predicate.value)
                    ?: emptySet()
                hardSet(ids.intersect(categoryAllowlist))
            }
            ExecutionField.SEMANTIC -> evaluateSemantic(
                predicate.value,
                categoryAllowlist,
                allowSemanticFallback,
                queryCategory = activeQueryCategory,
            )
            ExecutionField.KEYWORD -> evaluateOcrKeywords(
                predicate.value,
                categoryAllowlist,
            )
            ExecutionField.OCR -> evaluateOcrKeywords(
                predicate.value,
                categoryAllowlist,
            )
        }

    private fun evaluateDate(
        fromDate: String = "",
        toDate: String = "",
        categoryAllowlist: Set<Long>,
    ): EvaluatedSet {
        val scope = QueryScopeParser.parse(
            timeHint = "",
            locationHint = "",
            fromDate = fromDate,
            toDate = toDate,
        ).time ?: return EvaluatedSet()
        val ids = database.mediaStoreIdsMatchingTime(scope)
            ?: metadataReader.mediaStoreIdsMatchingTime(scope)
            ?: emptySet()
        return hardSet(ids.intersect(categoryAllowlist))
    }

    private fun evaluateTime(
        value: String,
        categoryAllowlist: Set<Long>,
    ): EvaluatedSet {
        val scope = QueryScopeParser.parse(
            timeHint = value,
            locationHint = "",
        ).time ?: return EvaluatedSet()
        val ids = database.mediaStoreIdsMatchingTime(scope)
            ?: metadataReader.mediaStoreIdsMatchingTime(scope)
            ?: emptySet()
        return hardSet(ids.intersect(categoryAllowlist))
    }

    private fun evaluateSemantic(
        value: String,
        categoryAllowlist: Set<Long>,
        allowSemanticFallback: Boolean,
        queryCategory: QueryCategory,
    ): EvaluatedSet {
        if (categoryAllowlist.isEmpty()) return EvaluatedSet()
        val nearest = runCatching {
            semanticIndexer.searchNearestScoredBlocking(
                listOf(value),
                SEMANTIC_CANDIDATE_LIMIT,
                categoryAllowlist.toLongArray(),
            )
        }.getOrDefault(emptyList())
        val strictSemantic = nearest.filter {
            GallerySemanticIndexer.isAcceptedSemanticScore(it.score)
        }
        val metadata = database.searchMetadataRanked(
            value,
            SEMANTIC_CANDIDATE_LIMIT,
            allowedMediaStoreIds = categoryAllowlist,
        )
            .filter { it.media.mediaStoreId in categoryAllowlist }
        val semantic = SemanticFallbackPolicy.select(
            strictMatches = strictSemantic,
            nearestMatches = nearest,
            hasMetadataMatches = metadata.isNotEmpty(),
            allowFallback = allowSemanticFallback,
            limit = SEMANTIC_FALLBACK_LIMIT,
        )
        if (strictSemantic.isEmpty() && semantic.isNotEmpty()) {
            Log.i(
                TAG,
                "No semantic neighbor met the " +
                    "${GallerySemanticIndexer.MIN_SEMANTIC_COSINE_SCORE} cutoff; " +
                    "publishing ${semantic.size} " +
                    "structured-scope nearest matches (top=${semantic.first().score})",
            )
        }
        val scores = LinkedHashMap<Long, Float>()
        semantic.forEach { match ->
            // Zero is reserved for hard-scope sets in the executor.
            val score = match.score.takeUnless { it == HARD_SCOPE_SCORE } ?: FALLBACK_ZERO_SCORE
            scores[match.mediaStoreId] = maxOf(scores[match.mediaStoreId] ?: score, score)
        }
        val maximumMetadata = metadata.maxOfOrNull { it.score }?.coerceAtLeast(1.0e-6f) ?: 1f
        metadata.forEach { match ->
            val normalized = (match.score / maximumMetadata).coerceIn(0f, 1f)
            val existing = scores[match.media.mediaStoreId]
            scores[match.media.mediaStoreId] = if (existing == null) {
                normalized
            } else {
                existing * SEMANTIC_WEIGHT + normalized * METADATA_WEIGHT
            }
        }
        val cosineScores = semantic.associate { it.mediaStoreId to it.score }
        return EvaluatedSet(scores = scores, cosineScores = cosineScores)
    }

    private fun evaluateOcrKeywords(
        value: String,
        categoryAllowlist: Set<Long>,
    ): EvaluatedSet {
        if (categoryAllowlist.isEmpty()) return EvaluatedSet()
        val keywords = OcrKeywordPolicy.keywords(value)
        if (keywords.isEmpty()) return EvaluatedSet()
        val matches = database.searchOcrKeywordsRanked(
            keywords = keywords,
            limit = OCR_CANDIDATE_LIMIT,
            allowedMediaStoreIds = categoryAllowlist,
        )
        val scores = LinkedHashMap<Long, Float>()
        matches.forEach { match ->
            if (match.media.mediaStoreId in categoryAllowlist) {
                scores[match.media.mediaStoreId] = match.score
            }
        }
        return EvaluatedSet(scores = scores, keywordCoverage = scores)
    }

    private fun hardSet(ids: Set<Long>): EvaluatedSet =
        EvaluatedSet(ids.associateWithTo(LinkedHashMap()) { HARD_SCOPE_SCORE })

    private fun union(
        left: EvaluatedSet,
        right: EvaluatedSet,
        fused: Boolean,
    ): EvaluatedSet {
        val keywordReference = (left.cosineScores.values + right.cosineScores.values)
            .maxOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1f
        val scores = LinkedHashMap(left.scores)
        right.scores.forEach { (id, score) ->
            val prior = scores[id]?.let { left.keywordCoverage[id]?.let { coverage -> keywordReference * coverage } ?: it }
            val incoming = right.keywordCoverage[id]?.let { coverage -> keywordReference * coverage } ?: score
            scores[id] = RetrievalScoreFusion.merge(prior, incoming, fused)
        }
        left.keywordCoverage.forEach { (id, coverage) ->
            if (id !in right.scores) scores[id] = keywordReference * coverage
        }
        val cosineScores = LinkedHashMap(left.cosineScores)
        right.cosineScores.forEach { (id, score) ->
            cosineScores[id] = maxOf(cosineScores[id] ?: score, score)
        }
        val keywordCoverage = LinkedHashMap(left.keywordCoverage)
        right.keywordCoverage.forEach { (id, coverage) ->
            keywordCoverage[id] = maxOf(keywordCoverage[id] ?: 0f, coverage)
        }
        return EvaluatedSet(scores, left.sorts + right.sorts, cosineScores, keywordCoverage)
    }

    private fun intersect(left: EvaluatedSet, right: EvaluatedSet): EvaluatedSet {
        val keywordReference = (left.cosineScores.values + right.cosineScores.values)
            .maxOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1f
        val scores = LinkedHashMap<Long, Float>()
        val smaller = if (left.scores.size <= right.scores.size) left.scores else right.scores
        smaller.forEach { (id, score) ->
            val leftScore = left.scores[id] ?: return@forEach
            val rightScore = right.scores[id] ?: return@forEach
            val normalizedLeft = left.keywordCoverage[id]?.let { keywordReference * it } ?: leftScore
            val normalizedRight = right.keywordCoverage[id]?.let { keywordReference * it } ?: rightScore
            scores[id] = when {
                leftScore == HARD_SCOPE_SCORE -> normalizedRight
                rightScore == HARD_SCOPE_SCORE -> normalizedLeft
                else -> normalizedLeft + normalizedRight
            }
        }
        val cosineScores = scores.keys.mapNotNull { id ->
            val score = listOf(
                left.cosineScores[id] ?: Float.NEGATIVE_INFINITY,
                right.cosineScores[id] ?: Float.NEGATIVE_INFINITY,
            ).maxOrNull()?.takeIf { it.isFinite() }
            score?.let { id to it }
        }.toMap(LinkedHashMap())
        val keywordCoverage = (left.keywordCoverage.keys + right.keywordCoverage.keys)
            .filter { it in scores }
            .associateWith { id -> maxOf(left.keywordCoverage[id] ?: 0f, right.keywordCoverage[id] ?: 0f) }
        return EvaluatedSet(scores, left.sorts + right.sorts, cosineScores, keywordCoverage)
    }

    private fun subtract(left: EvaluatedSet, right: EvaluatedSet): EvaluatedSet =
        EvaluatedSet(
            left.scores.filterKeys { it !in right.scores }.toMap(LinkedHashMap()),
            left.sorts + right.sorts,
            left.cosineScores.filterKeys { it !in right.scores },
            left.keywordCoverage.filterKeys { it !in right.scores },
        )

    private data class EvaluatedSet(
        val scores: Map<Long, Float> = emptyMap(),
        val sorts: Set<ExecutionSort> = emptySet(),
        val cosineScores: Map<Long, Float> = emptyMap(),
        val keywordCoverage: Map<Long, Float> = emptyMap(),
    )

    private companion object {
        const val TAG = "AskGalaxySearch"
        // This is a relevance-retrieval budget, not a presentation cap.
        // Exact structured predicates remain exhaustive.
        const val SEMANTIC_CANDIDATE_LIMIT = 512
        const val OCR_CANDIDATE_LIMIT = 512
        // The public gallery has a 200-item browsing window. On a strict
        // semantic miss, fill that window with the nearest candidates from
        // the already-applied category and structured scope.
        const val SEMANTIC_FALLBACK_LIMIT = 200
        const val HARD_SCOPE_SCORE = 0f
        const val FALLBACK_ZERO_SCORE = 1.0e-6f
        const val SEMANTIC_WEIGHT = 0.68f
        const val METADATA_WEIGHT = 0.32f
    }
}

internal object OcrKeywordPolicy {
    fun keywords(value: String): List<String> = tokenize(value).take(MAX_KEYWORDS)

    fun keywordCount(value: String): Int = tokenize(value).size

    private fun tokenize(value: String): List<String> = value
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map(String::trim)
        .filter { it.length >= 2 }
        .distinct()

    fun score(ocrText: String, keywords: List<String>): Float = coverage(ocrText, keywords)

    fun coverage(ocrText: String, keywords: List<String>): Float {
        if (keywords.isEmpty()) return 0f
        val normalized = ocrText.lowercase()
        val matched = keywords.count { keyword ->
            normalized.contains(keyword) || when (keyword) {
                "aadhar", "aadhaar" -> normalized.contains("aadhar") || normalized.contains("aadhaar")
                else -> false
            }
        }
        return matched.toFloat() / keywords.size.toFloat()
    }

    fun matchesAll(ocrText: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val normalized = ocrText.lowercase()
        return keywords.all { keyword ->
            normalized.contains(keyword) || when (keyword) {
                "aadhar", "aadhaar" ->
                    normalized.contains("aadhar") || normalized.contains("aadhaar")
                else -> false
            }
        }
    }

    const val MAX_KEYWORDS = 6
    const val PERFECT_MATCH_SCORE = 2f
}

internal object RetrievalScoreFusion {
    fun merge(prior: Float?, incoming: Float, fused: Boolean): Float = when {
        prior == null -> incoming
        fused -> prior + incoming + FUSION_BONUS
        else -> maxOf(prior, incoming)
    }

    private const val FUSION_BONUS = 0.05f
}

/**
 * Confidence-aware search contract: retain the 0.10 cutoff whenever it finds
 * anything, but never turn a valid positive semantic search into an empty
 * gallery solely because every nearest neighbor sits just below that cutoff.
 */
internal object SemanticFallbackPolicy {
    fun select(
        strictMatches: List<SemanticMatch>,
        nearestMatches: List<SemanticMatch>,
        hasMetadataMatches: Boolean,
        allowFallback: Boolean,
        limit: Int,
    ): List<SemanticMatch> {
        if (strictMatches.isNotEmpty()) return strictMatches
        if (!allowFallback || hasMetadataMatches || limit <= 0) return emptyList()
        return nearestMatches.asSequence()
            .filter { it.score.isFinite() }
            .distinctBy { it.mediaStoreId }
            .take(limit)
            .toList()
    }
}
