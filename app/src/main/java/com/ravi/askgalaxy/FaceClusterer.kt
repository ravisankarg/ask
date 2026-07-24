package com.ravi.askgalaxy

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Rebuilds anonymous person groups from the local FaceNet vectors. */
class FaceClusterer(
    private val database: GalleryDatabase,
) {
    fun rebuildBlocking(): Int {
        val records = database.allFaceEmbeddings()
        if (records.isEmpty()) {
            database.replaceFaceClusters(emptyList())
            return 0
        }
        val previous = database.existingFaceClusters()
        val discovered = cluster(records)
        val usedPrevious = HashSet<String>()
        val assignedIds = previous.mapTo(HashSet()) { it.clusterId }
        val assignments = discovered.map { cluster ->
            val match = previous
                .asSequence()
                .filter { it.clusterId !in usedPrevious }
                .map { it to cosine(it.centroid, cluster.centroid) }
                .filter { (_, score) -> score >= LABEL_REUSE_THRESHOLD }
                .maxByOrNull { (_, score) -> score }
                ?.first
            if (match != null) usedPrevious += match.clusterId
            val id = match?.clusterId ?: newClusterId(cluster.centroid, assignedIds)
            assignedIds += id
            val representative = cluster.members.maxByOrNull { it.detectionScore }
                ?: cluster.members.first()
            FaceClusterAssignment(
                clusterId = id,
                label = match?.label.orEmpty(),
                centroid = cluster.centroid,
                representativeMediaStoreId = representative.mediaStoreId,
                representativeFaceId = representative.id,
                representativeBox = representative.box,
                memberFaceIds = cluster.members.map { it.id }.toLongArray(),
                memberMediaStoreIds = cluster.members.map { it.mediaStoreId }.distinct().toLongArray(),
            )
        }
        database.replaceFaceClusters(assignments)
        return assignments.size
    }

    private fun cluster(records: List<FaceEmbeddingRecord>): List<DiscoveredCluster> {
        val clusters = ArrayList<MutableCluster>()
        records.sortedWith(compareBy<FaceEmbeddingRecord> { it.mediaStoreId }.thenBy { it.faceIndex })
            .forEach { record ->
                var bestIndex = -1
                var bestScore = Float.NEGATIVE_INFINITY
                val recordNorm = vectorNorm(record.embedding)
                clusters.forEachIndexed { index, cluster ->
                    val score = cluster.cosineTo(record.embedding, recordNorm)
                    if (score > bestScore) {
                        bestIndex = index
                        bestScore = score
                    }
                }
                if (bestIndex >= 0 && bestScore >= MATCH_THRESHOLD) {
                    clusters[bestIndex].add(record)
                } else {
                    clusters += MutableCluster(record)
                }
            }
        return clusters.map { it.freeze() }
    }

    private fun newClusterId(centroid: FloatArray, assignedIds: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(centroid.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        centroid.forEach(bytes::putFloat)
        val prefix = digest.digest(bytes.array()).take(6).joinToString("") { "%02x".format(it) }
        var candidate = "person-$prefix"
        var suffix = 2
        while (candidate in assignedIds) {
            candidate = "person-$prefix-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun cosine(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size || first.isEmpty()) return -1f
        var dot = 0.0
        var firstNorm = 0.0
        var secondNorm = 0.0
        for (index in first.indices) {
            dot += first[index].toDouble() * second[index].toDouble()
            firstNorm += first[index].toDouble() * first[index].toDouble()
            secondNorm += second[index].toDouble() * second[index].toDouble()
        }
        val denominator = sqrt(firstNorm * secondNorm)
        return if (denominator > 0.0) (dot / denominator).toFloat() else -1f
    }

    private class MutableCluster(first: FaceEmbeddingRecord) {
        private val members = ArrayList<FaceEmbeddingRecord>().apply { add(first) }
        private var sum = first.embedding.copyOf()
        private var sumNorm = vectorNorm(sum)

        val centroid: FloatArray
            get() = normalize(sum)

        /**
         * Scores directly against the unnormalised running sum. Normalising the
         * centroid here would allocate and scan a second 512-float array for
         * every face/cluster comparison during final indexing.
         */
        fun cosineTo(embedding: FloatArray, embeddingNorm: Float): Float {
            if (sum.size != embedding.size || sum.isEmpty()) return -1f
            val denominator = sumNorm * embeddingNorm
            if (denominator <= 0f) return -1f
            var dot = 0.0
            for (index in sum.indices) {
                dot += sum[index].toDouble() * embedding[index].toDouble()
            }
            return (dot / denominator.toDouble()).toFloat()
        }

        fun add(record: FaceEmbeddingRecord) {
            members += record
            for (index in sum.indices) sum[index] += record.embedding[index]
            sumNorm = vectorNorm(sum)
        }

        fun freeze(): DiscoveredCluster = DiscoveredCluster(centroid, members.toList())
    }

    private data class DiscoveredCluster(
        val centroid: FloatArray,
        val members: List<FaceEmbeddingRecord>,
    )

    companion object {
        private const val MATCH_THRESHOLD = 0.70f
        private const val LABEL_REUSE_THRESHOLD = 0.82f

        private fun vectorNorm(values: FloatArray): Float {
            var squared = 0.0
            values.forEach { value -> squared += value.toDouble() * value.toDouble() }
            return sqrt(squared).toFloat()
        }

        private fun normalize(values: FloatArray): FloatArray {
            val norm = vectorNorm(values).coerceAtLeast(1.0e-12f)
            return FloatArray(values.size) { values[it] / norm }
        }
    }
}
