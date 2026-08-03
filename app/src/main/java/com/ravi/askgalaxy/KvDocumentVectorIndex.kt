package com.ravi.askgalaxy

import android.content.Context
import java.io.Closeable

/** Separate SigLIP-text space for LFM-produced document key-value text. */
class KvDocumentVectorIndex private constructor(
    private val delegate: NativeVectorIndex,
) : Closeable {
    val size: Long get() = delegate.size

    fun upsert(mediaStoreId: Long, embedding: FloatArray) = delegate.upsert(mediaStoreId, embedding)

    fun search(queryEmbedding: FloatArray, limit: Int, allowlist: LongArray?): NativeSearchResult =
        delegate.search(queryEmbedding, limit, allowlist)

    override fun close() = delegate.close()

    companion object {
        const val FILE_NAME = "kv-siglip-text-768-4bit.tvim"

        fun open(context: Context): KvDocumentVectorIndex =
            KvDocumentVectorIndex(NativeVectorIndex.open(context, fileName = FILE_NAME))

        fun clearPersisted(context: Context) {
            java.io.File(context.applicationContext.filesDir, "indexes/$FILE_NAME").delete()
        }
    }
}
