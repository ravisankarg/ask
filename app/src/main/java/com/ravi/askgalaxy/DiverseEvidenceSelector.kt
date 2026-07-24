package com.ravi.askgalaxy

import kotlin.math.sqrt

/**
 * Chooses representatives from a small, already-retrieved candidate set.
 *
 * The first candidate preserves retrieval relevance. Later representatives are
 * selected by max-min cosine distance, with near-duplicates treated as one
 * visual group. This keeps the multimodal prompt small without throwing away
 * visually different evidence.
 */
object DiverseEvidenceSelector {
    fun select(
        candidates: List<Pair<GalleryMedia, FloatArray>>,
        maxCount: Int,
    ): List<GalleryMedia> {
        if (maxCount <= 0 || candidates.isEmpty()) return emptyList()
        val usable = candidates.filter { (_, embedding) -> embedding.isValid() }
        if (usable.isEmpty()) return emptyList()
        if (usable.size <= maxCount) return usable.map { it.first }

        val remaining = usable.toMutableList()
        val selected = ArrayList<Pair<GalleryMedia, FloatArray>>(maxCount)
        selected += remaining.removeAt(0)

        while (selected.size < maxCount && remaining.isNotEmpty()) {
            // Prefer a new visual group whenever one exists. The rank bonus
            // keeps the selection anchored to the original retrieval order.
            val distinct = remaining.filter { candidate ->
                selected.none { chosen ->
                    cosine(candidate.second, chosen.second) >= SAME_GROUP_SIMILARITY
                }
            }
            val pool = if (distinct.isNotEmpty()) distinct else remaining
            val next = pool.maxWithOrNull(
                compareBy<Pair<GalleryMedia, FloatArray>> { candidate ->
                    val nearestDistance = selected.minOf { chosen ->
                        1f - cosine(candidate.second, chosen.second)
                    }
                    val rank = candidates.indexOf(candidate)
                    val relevance = 1f - rank.toFloat() / candidates.size.coerceAtLeast(1)
                    nearestDistance * DIVERSITY_WEIGHT + relevance * RANK_WEIGHT
                }.thenByDescending { candidate ->
                    candidates.indexOf(candidate)
                },
            ) ?: break
            selected += next
            remaining.remove(next)
        }
        return selected.map { it.first }
    }

    private fun FloatArray.isValid(): Boolean =
        isNotEmpty() && all { it.isFinite() } && norm(this) > 1.0e-6f

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        val count = minOf(left.size, right.size)
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until count) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            leftNorm += a * a
            rightNorm += b * b
        }
        if (leftNorm <= 1.0e-12 || rightNorm <= 1.0e-12) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat().coerceIn(-1f, 1f)
    }

    private fun norm(values: FloatArray): Float {
        var sum = 0.0
        values.forEach { value -> sum += value.toDouble() * value.toDouble() }
        return sqrt(sum).toFloat()
    }

    private const val SAME_GROUP_SIMILARITY = 0.90f
    private const val DIVERSITY_WEIGHT = 0.65f
    private const val RANK_WEIGHT = 0.35f
}
