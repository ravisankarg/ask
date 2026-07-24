package com.ravi.askgalaxy

object FaceTaggingPolicy {
    /**
     * Naming every incidental face is unnecessary, but merge review must use
     * the complete stable cluster inventory so lower-frequency duplicates do
     * not appear to replace earlier cards after each merge.
     */
    const val MIN_NAMED_GROUPS_FOR_SKIP = 3
}

data class FaceEmbeddingRecord(
    val id: Long,
    val mediaStoreId: Long,
    val faceIndex: Int,
    val embedding: FloatArray,
    val detectionScore: Float,
    val box: FaceBox,
    val clusterId: String = "",
)

/** A named face occurrence reused as bounded multimodal answer evidence. */
data class TaggedFaceOccurrence(
    val mediaStoreId: Long,
    val faceIndex: Int,
    val label: String,
    val box: FaceBox,
    val detectionScore: Float,
)

data class StoredFaceCluster(
    val clusterId: String,
    val label: String,
    val centroid: FloatArray,
    val representativeMediaStoreId: Long,
    val faceCount: Int,
    val representativeFaceId: Long,
)

data class FaceCluster(
    val clusterId: String,
    val label: String,
    val faceCount: Int,
    val representative: GalleryMedia,
    val representativeBox: FaceBox? = null,
)

data class FaceClusterAssignment(
    val clusterId: String,
    val label: String,
    val centroid: FloatArray,
    val representativeMediaStoreId: Long,
    val representativeFaceId: Long,
    val representativeBox: FaceBox,
    val memberFaceIds: LongArray,
    val memberMediaStoreIds: LongArray,
)
