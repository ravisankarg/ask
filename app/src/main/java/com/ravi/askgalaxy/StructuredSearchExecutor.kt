package com.ravi.askgalaxy

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
    fun execute(
        spec: QueryExecutionSpec,
    ): List<GalleryMedia> {
        val evaluated = evaluate(spec.root)
        if (evaluated.scores.isEmpty()) return emptyList()
        val rows = database.findByMediaStoreIds(evaluated.scores.keys.toLongArray())
        val scoreById = evaluated.scores
        val comparator = when {
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
        // answer Context Picker independently chooses at most 16 items.
        return rows.sortedWith(comparator)
    }

    private fun evaluate(node: ExecutionNode): EvaluatedSet = when (node) {
        is ExecutionNode.Predicate -> evaluatePredicate(node)
        is ExecutionNode.Sorted -> evaluate(node.value).let {
            it.copy(sorts = it.sorts + node.sort)
        }
        is ExecutionNode.Binary -> {
            val left = evaluate(node.left)
            val right = evaluate(node.right)
            when (node.operator) {
                ExecutionBinaryOperator.UNION -> union(left, right, fused = false)
                ExecutionBinaryOperator.ADD -> union(left, right, fused = true)
                ExecutionBinaryOperator.INTERSECT -> intersect(left, right)
                ExecutionBinaryOperator.SUBTRACT -> subtract(left, right)
            }
        }
    }

    private fun evaluatePredicate(predicate: ExecutionNode.Predicate): EvaluatedSet =
        when (predicate.field) {
            ExecutionField.PERSON -> {
                val labels = database.resolveNamedPersonLabels(listOf(predicate.value))
                hardSet(database.mediaStoreIdsForPersonLabels(labels))
            }
            ExecutionField.MIME_TYPE -> {
                val type = QueryMediaType.fromToken(predicate.value)
                hardSet(type?.let(database::mediaStoreIdsForMediaType).orEmpty())
            }
            ExecutionField.FROM_DATE -> evaluateDate(fromDate = predicate.value)
            ExecutionField.TO_DATE -> evaluateDate(toDate = predicate.value)
            ExecutionField.LOCATION -> {
                val ids = database.mediaStoreIdsMatchingLocation(predicate.value)
                    ?: metadataReader.mediaStoreIdsMatchingLocation(predicate.value)
                    ?: emptySet()
                hardSet(ids)
            }
            ExecutionField.SEMANTIC -> evaluateSemantic(predicate.value)
        }

    private fun evaluateDate(
        fromDate: String = "",
        toDate: String = "",
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
        return hardSet(ids)
    }

    private fun evaluateSemantic(value: String): EvaluatedSet {
        val semantic = runCatching {
            semanticIndexer.searchScoredBlocking(value, SEMANTIC_CANDIDATE_LIMIT)
        }.getOrDefault(emptyList())
        val metadata = database.searchMetadataRanked(value, SEMANTIC_CANDIDATE_LIMIT)
        val scores = LinkedHashMap<Long, Float>()
        semantic.forEach { match ->
            scores[match.mediaStoreId] = maxOf(scores[match.mediaStoreId] ?: 0f, match.score)
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
        return EvaluatedSet(scores)
    }

    private fun hardSet(ids: Set<Long>): EvaluatedSet =
        EvaluatedSet(ids.associateWithTo(LinkedHashMap()) { HARD_SCOPE_SCORE })

    private fun union(
        left: EvaluatedSet,
        right: EvaluatedSet,
        fused: Boolean,
    ): EvaluatedSet {
        val scores = LinkedHashMap(left.scores)
        right.scores.forEach { (id, score) ->
            val prior = scores[id]
            scores[id] = when {
                prior == null -> score
                fused -> prior + score + FUSION_BONUS
                else -> maxOf(prior, score)
            }
        }
        return EvaluatedSet(scores, left.sorts + right.sorts)
    }

    private fun intersect(left: EvaluatedSet, right: EvaluatedSet): EvaluatedSet {
        val scores = LinkedHashMap<Long, Float>()
        val smaller = if (left.scores.size <= right.scores.size) left.scores else right.scores
        smaller.forEach { (id, score) ->
            val leftScore = left.scores[id] ?: return@forEach
            val rightScore = right.scores[id] ?: return@forEach
            scores[id] = when {
                leftScore == HARD_SCOPE_SCORE -> rightScore
                rightScore == HARD_SCOPE_SCORE -> leftScore
                else -> leftScore + rightScore
            }
        }
        return EvaluatedSet(scores, left.sorts + right.sorts)
    }

    private fun subtract(left: EvaluatedSet, right: EvaluatedSet): EvaluatedSet =
        EvaluatedSet(
            left.scores.filterKeys { it !in right.scores }.toMap(LinkedHashMap()),
            left.sorts + right.sorts,
        )

    private data class EvaluatedSet(
        val scores: Map<Long, Float> = emptyMap(),
        val sorts: Set<ExecutionSort> = emptySet(),
    )

    private companion object {
        // This is a relevance-retrieval budget, not a presentation cap.
        // Exact structured predicates remain exhaustive.
        const val SEMANTIC_CANDIDATE_LIMIT = 512
        const val HARD_SCOPE_SCORE = 0f
        const val SEMANTIC_WEIGHT = 0.68f
        const val METADATA_WEIGHT = 0.32f
        const val FUSION_BONUS = 0.05f
    }
}
