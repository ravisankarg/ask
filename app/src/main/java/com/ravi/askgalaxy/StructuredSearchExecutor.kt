package com.ravi.askgalaxy

import android.util.Log
import java.util.Locale

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
    data class ScoredGalleryResults(
        val media: List<GalleryMedia>,
        val cosineScores: Map<Long, Float>,
    )

    fun execute(
        spec: QueryExecutionSpec,
        allowOcrlessPhotoKeywordBypass: Boolean,
    ): List<GalleryMedia> = executeScored(spec, allowOcrlessPhotoKeywordBypass).media

    fun executeScored(
        spec: QueryExecutionSpec,
        allowOcrlessPhotoKeywordBypass: Boolean,
    ): ScoredGalleryResults {
        // query_category is deterministic answer-routing metadata, not a
        // search predicate. Retrieval narrows only on fields emitted by QP.
        val categoryAllowlist = database.allMediaStoreIds()
        if (categoryAllowlist.isEmpty()) return ScoredGalleryResults(emptyList(), emptyMap())
        val evaluated = evaluate(
            spec.root,
            categoryAllowlist,
            allowOcrlessPhotoKeywordBypass = allowOcrlessPhotoKeywordBypass,
        )
        val scoreById = evaluated.scores.filterKeys { it in categoryAllowlist }
        if (scoreById.isEmpty()) return ScoredGalleryResults(emptyList(), emptyMap())
        val rows = database.findByMediaStoreIds(scoreById.keys.toLongArray())
        val comparator = relevanceFirstComparator(scoreById, evaluated.sorts)
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
            ExecutionSort.OLDEST in sorts ->
                relevance.thenBy {
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

    private fun evaluate(
        node: ExecutionNode,
        categoryAllowlist: Set<Long>,
        allowOcrlessPhotoKeywordBypass: Boolean,
    ): EvaluatedSet = when (node) {
        is ExecutionNode.Predicate ->
            evaluatePredicate(
                node,
                categoryAllowlist,
                allowOcrlessPhotoKeywordBypass,
            )
        is ExecutionNode.Sorted ->
            evaluate(
                node.value,
                categoryAllowlist,
                allowOcrlessPhotoKeywordBypass,
            ).let {
                it.copy(sorts = it.sorts + node.sort)
            }
        is ExecutionNode.Binary -> {
            when (node.operator) {
                ExecutionBinaryOperator.INTERSECT ->
                    evaluateIntersection(
                        node,
                        categoryAllowlist,
                        allowOcrlessPhotoKeywordBypass,
                    )
                ExecutionBinaryOperator.SUBTRACT -> {
                    val left = evaluate(
                        node.left,
                        categoryAllowlist,
                        allowOcrlessPhotoKeywordBypass,
                    )
                    // The negative branch is scoped to the positive set, so
                    // "photos excluding selfies" does not search selfies
                    // across an unrelated MIME/date/person universe first.
                    val scopedIds = left.scores.keys.intersect(categoryAllowlist)
                    val right = if (scopedIds.isEmpty()) {
                        EvaluatedSet()
                    } else {
                        evaluate(
                            node.right,
                            scopedIds,
                            allowOcrlessPhotoKeywordBypass = allowOcrlessPhotoKeywordBypass,
                        )
                    }
                    subtract(left, right)
                }
                ExecutionBinaryOperator.UNION,
                ExecutionBinaryOperator.ADD,
                -> {
                    val left = evaluate(
                        node.left,
                        categoryAllowlist,
                        allowOcrlessPhotoKeywordBypass,
                    )
                    val right = evaluate(
                        node.right,
                        categoryAllowlist,
                        allowOcrlessPhotoKeywordBypass,
                    )
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
     * ranking. This prevents a 512-neighbor search over the whole category from
     * being emptied later by MIME/person/date/place intersection.
     */
    private fun evaluateIntersection(
        node: ExecutionNode.Binary,
        categoryAllowlist: Set<Long>,
        allowOcrlessPhotoKeywordBypass: Boolean,
    ): EvaluatedSet {
        val leftHasSemantic = containsScoredRetrieval(node.left)
        val rightHasSemantic = containsScoredRetrieval(node.right)
        return when {
            !leftHasSemantic && rightHasSemantic -> {
                val left = evaluate(
                    node.left,
                    categoryAllowlist,
                    allowOcrlessPhotoKeywordBypass,
                )
                val scopedIds = left.scores.keys.intersect(categoryAllowlist)
                // The hard scope is authoritative. Search both semantic and
                // keyword channels inside it, instead of taking global top-K
                // neighbours and intersecting them afterwards.
                val right = if (scopedIds.isEmpty()) {
                    EvaluatedSet()
                } else {
                    evaluate(
                        node.right,
                        scopedIds,
                        allowOcrlessPhotoKeywordBypass,
                    )
                }
                intersect(left, right)
            }
            leftHasSemantic && !rightHasSemantic -> {
                val right = evaluate(
                    node.right,
                    categoryAllowlist,
                    allowOcrlessPhotoKeywordBypass,
                )
                val scopedIds = right.scores.keys.intersect(categoryAllowlist)
                val left = if (scopedIds.isEmpty()) {
                    EvaluatedSet()
                } else {
                    evaluate(
                        node.left,
                        scopedIds,
                        allowOcrlessPhotoKeywordBypass,
                    )
                }
                intersect(left, right)
            }
            else -> {
                val left = evaluate(
                    node.left,
                    categoryAllowlist,
                    allowOcrlessPhotoKeywordBypass,
                )
                val right = evaluate(
                    node.right,
                    categoryAllowlist,
                    allowOcrlessPhotoKeywordBypass,
                )
                // The gallery hybrid contract is a true intersection: the
                // same row must be found by both semantic and keyword search.
                // A row matching only one channel must not be published.
                intersect(left, right)
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
        allowOcrlessPhotoKeywordBypass: Boolean,
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
            ExecutionField.TRAVEL -> {
                require(predicate.value == "outside_normal") { "travel must be outside_normal" }
                val travel = database.mediaStoreIdsOutsideNormalLocation()
                Log.i(
                    TAG,
                    "Travel scope: locatedEpisodes=${travel.locatedEpisodeCount}, " +
                        "normalEpisodes=${travel.normalEpisodeCount}, " +
                        "travelEpisodes=${travel.travelEpisodeCount}, media=${travel.mediaStoreIds.size}",
                )
                hardSet(travel.mediaStoreIds.intersect(categoryAllowlist))
            }
            ExecutionField.SEMANTIC -> evaluateSemantic(
                predicate.value,
                categoryAllowlist,
            )
            ExecutionField.KEYWORD -> evaluateOcrKeywords(
                predicate.value,
                categoryAllowlist,
                relaxForImagesWithoutOcr = allowOcrlessPhotoKeywordBypass,
            )
            ExecutionField.OCR -> evaluateOcrKeywords(
                predicate.value,
                categoryAllowlist,
                relaxForImagesWithoutOcr = false,
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
    ): EvaluatedSet {
        if (categoryAllowlist.isEmpty()) return EvaluatedSet()
        val nearestResult = runCatching {
            semanticIndexer.searchNearestScoredBlocking(
                listOf(value),
                SEMANTIC_CANDIDATE_LIMIT,
                categoryAllowlist.toLongArray(),
            )
        }
        nearestResult.exceptionOrNull()?.let { error ->
            Log.w(
                TAG,
                "Scoped semantic search failed: term=${value.take(MAX_DIAGNOSTIC_TERM_CHARS)}, " +
                    "scope=${categoryAllowlist.size}",
                error,
            )
        }
        val nearest = nearestResult.getOrDefault(emptyList())
        val semantic = nearest.filter {
            GallerySemanticIndexer.isAcceptedSemanticScore(it.score)
        }
        val highestScore = nearest.maxOfOrNull(SemanticMatch::score)
        val highestScoreText = highestScore?.let { String.format(Locale.US, "%.6f", it) } ?: "none"
        Log.i(
            TAG,
            "Scoped semantic: term=${value.take(MAX_DIAGNOSTIC_TERM_CHARS)}, " +
                "scope=${categoryAllowlist.size}, neighbors=${nearest.size}, " +
                "highest=$highestScoreText, " +
                "threshold=${GallerySemanticIndexer.MIN_SEMANTIC_COSINE_SCORE}, accepted=${semantic.size}",
        )
        val metadata = database.searchMetadataRanked(
            value,
            SEMANTIC_CANDIDATE_LIMIT,
            allowedMediaStoreIds = categoryAllowlist,
        )
            .filter { it.media.mediaStoreId in categoryAllowlist }
        val cosineScores = semantic.associate { it.mediaStoreId to it.score }
        val metadataScores = metadata.associate { it.media.mediaStoreId to it.score }
        val scores = SemanticMetadataFusion.rerank(
            semanticScores = cosineScores,
            metadataScores = metadataScores,
            semanticWeight = SEMANTIC_WEIGHT,
            metadataWeight = METADATA_WEIGHT,
        )
        return EvaluatedSet(scores = scores, cosineScores = cosineScores)
    }

    private fun evaluateOcrKeywords(
        value: String,
        categoryAllowlist: Set<Long>,
        relaxForImagesWithoutOcr: Boolean,
    ): EvaluatedSet {
        if (categoryAllowlist.isEmpty()) return EvaluatedSet(
            hasRelaxableKeywordPredicate = relaxForImagesWithoutOcr,
            hasStrictOcrPredicate = !relaxForImagesWithoutOcr,
        )
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
        return EvaluatedSet(
            scores = scores,
            keywordCoverage = scores,
            hasRelaxableKeywordPredicate = relaxForImagesWithoutOcr,
            hasStrictOcrPredicate = !relaxForImagesWithoutOcr,
        )
    }

    private fun hardSet(ids: Set<Long>): EvaluatedSet =
        EvaluatedSet(
            scores = ids.associateWithTo(LinkedHashMap()) { HARD_SCOPE_SCORE },
            mandatoryScopeIds = ids,
        )

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
        return EvaluatedSet(
            scores,
            left.sorts + right.sorts,
            cosineScores,
            keywordCoverage,
            left.hasRelaxableKeywordPredicate || right.hasRelaxableKeywordPredicate,
            left.hasStrictOcrPredicate || right.hasStrictOcrPredicate,
            null,
        )
    }

    private fun intersect(left: EvaluatedSet, right: EvaluatedSet): EvaluatedSet {
        val keywordReference = (left.cosineScores.values + right.cosineScores.values)
            .maxOrNull()
            ?.coerceIn(0f, 1f)
            ?: 1f
        val scores = LinkedHashMap<Long, Float>()
        val mandatoryScopeIds = when {
            left.mandatoryScopeIds == null -> right.mandatoryScopeIds
            right.mandatoryScopeIds == null -> left.mandatoryScopeIds
            else -> left.mandatoryScopeIds.intersect(right.mandatoryScopeIds)
        }
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
        val relaxedSemanticSide = when {
            right.isRelaxableKeywordGate() && left.cosineScores.isNotEmpty() -> left
            left.isRelaxableKeywordGate() && right.cosineScores.isNotEmpty() -> right
            else -> null
        }
        if (relaxedSemanticSide != null) {
            val semanticCandidates = relaxedSemanticSide.cosineScores.keys
                .intersect(relaxedSemanticSide.scores.keys)
                .let { candidates ->
                    mandatoryScopeIds?.let(candidates::intersect) ?: candidates
                }
            val noOcrPhotoIds = GalleryKeywordIntersectionPolicy.eligibleWithoutKeywordMatch(
                semanticCandidateIds = semanticCandidates,
                records = database.findByMediaStoreIds(semanticCandidates.toLongArray()),
            )
            noOcrPhotoIds.forEach { id ->
                relaxedSemanticSide.scores[id]?.let { scores.putIfAbsent(id, it) }
            }
            if (noOcrPhotoIds.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Keyword AND bypassed only for ${noOcrPhotoIds.size} semantic photo candidates without OCR",
                )
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
        return EvaluatedSet(
            scores,
            left.sorts + right.sorts,
            cosineScores,
            keywordCoverage,
            left.hasRelaxableKeywordPredicate || right.hasRelaxableKeywordPredicate,
            left.hasStrictOcrPredicate || right.hasStrictOcrPredicate,
            mandatoryScopeIds,
        )
    }

    private fun subtract(left: EvaluatedSet, right: EvaluatedSet): EvaluatedSet =
        EvaluatedSet(
            left.scores.filterKeys { it !in right.scores }.toMap(LinkedHashMap()),
            left.sorts + right.sorts,
            left.cosineScores.filterKeys { it !in right.scores },
            left.keywordCoverage.filterKeys { it !in right.scores },
            left.hasRelaxableKeywordPredicate,
            left.hasStrictOcrPredicate,
            left.mandatoryScopeIds,
        )

    private data class EvaluatedSet(
        val scores: Map<Long, Float> = emptyMap(),
        val sorts: Set<ExecutionSort> = emptySet(),
        val cosineScores: Map<Long, Float> = emptyMap(),
        val keywordCoverage: Map<Long, Float> = emptyMap(),
        val hasRelaxableKeywordPredicate: Boolean = false,
        val hasStrictOcrPredicate: Boolean = false,
        val mandatoryScopeIds: Set<Long>? = null,
    ) {
        fun isRelaxableKeywordGate(): Boolean =
            hasRelaxableKeywordPredicate && !hasStrictOcrPredicate && cosineScores.isEmpty()
    }

    private companion object {
        const val TAG = "AskGalaxySearch"
        // This is a relevance-retrieval budget, not a presentation cap.
        // Exact structured predicates remain exhaustive.
        const val SEMANTIC_CANDIDATE_LIMIT = 512
        const val OCR_CANDIDATE_LIMIT = 512
        const val HARD_SCOPE_SCORE = 0f
        const val SEMANTIC_WEIGHT = 0.68f
        const val METADATA_WEIGHT = 0.32f
        const val MAX_DIAGNOSTIC_TERM_CHARS = 80
    }
}

/**
 * Metadata may rerank a real semantic hit, but it must not manufacture one.
 * Keeping membership anchored to [semanticScores] preserves the cosine cutoff
 * and makes `semantic + keyword` a genuine two-channel intersection.
 */
internal object SemanticMetadataFusion {
    fun rerank(
        semanticScores: Map<Long, Float>,
        metadataScores: Map<Long, Float>,
        semanticWeight: Float,
        metadataWeight: Float,
    ): Map<Long, Float> {
        if (semanticScores.isEmpty()) return emptyMap()
        val retainedMetadata = metadataScores.filterKeys(semanticScores::containsKey)
        val maximumMetadata = retainedMetadata.values.maxOrNull()?.coerceAtLeast(1.0e-6f) ?: 1f
        return semanticScores.mapValuesTo(LinkedHashMap()) { (id, semanticScore) ->
            val metadataScore = retainedMetadata[id] ?: return@mapValuesTo semanticScore
            val normalizedMetadata = (metadataScore / maximumMetadata).coerceIn(0f, 1f)
            semanticScore * semanticWeight + normalizedMetadata * metadataWeight
        }
    }
}

/**
 * OCR-less photos may bypass keyword matching only for visual/metadata intents.
 * A document answer must remain semantic AND keyword across every candidate.
 */
internal object GalleryKeywordIntersectionPolicy {
    fun allowsOcrlessPhotoBypass(queryCategory: QueryCategory): Boolean =
        queryCategory != QueryCategory.DOC

    fun eligibleWithoutKeywordMatch(
        semanticCandidateIds: Set<Long>,
        records: List<GalleryMedia>,
    ): Set<Long> = records.asSequence()
        .filter { it.mediaStoreId in semanticCandidateIds }
        .filter { it.mimeType.startsWith("image/", ignoreCase = true) }
        .filter { it.ocrText.isBlank() }
        .mapTo(LinkedHashSet()) { it.mediaStoreId }
}

internal object OcrKeywordPolicy {
    fun keywords(value: String): List<String> = tokenize(value).take(MAX_KEYWORDS)

    fun keywordCount(value: String): Int = tokenize(value).size

    private fun tokenize(value: String): List<String> = value
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map(String::trim)
        .filter { it.length >= 2 }
        .filterNot { it in SearchKeywordPolicy.forbiddenScaffoldingWords }
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
