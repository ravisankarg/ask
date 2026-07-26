package com.ravi.askgalaxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

enum class IsolatedIndex(val wire: String, val displayName: String) {
    VISUAL("visual", "visual search"),
    FACE("face", "face"),
    EPISODE("episode", "photo episodes"),
}

/** Clears and rebuilds one derived index without touching unrelated indexes. */
object IndexRebuildScheduler {
    fun rebuild(context: Context, index: IsolatedIndex): Int {
        val appContext = context.applicationContext
        val invalidated = GalleryDatabase(appContext).use { database ->
            when (index) {
                IsolatedIndex.VISUAL -> {
                    NativeVectorIndex.clearPersisted(appContext)
                    database.invalidateImageEmbeddingIndex()
                }
                IsolatedIndex.FACE -> database.invalidateFaceEmbeddingIndex()
                IsolatedIndex.EPISODE -> 0
            }
        }
        enqueue(appContext, index, ExistingWorkPolicy.REPLACE)
        return invalidated
    }

    private fun enqueue(context: Context, index: IsolatedIndex, policy: ExistingWorkPolicy) {
        PreparationNotifier.createChannel(context)
        val request = OneTimeWorkRequestBuilder<IndexRebuildWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .setInputData(androidx.work.workDataOf(KEY_INDEX to index.wire))
            .addTag(uniqueWorkName(index))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(index),
            policy,
            request,
        )
    }

    internal fun uniqueWorkName(index: IsolatedIndex): String =
        "ask_galaxy_${index.wire}_index_rebuild"

    internal const val KEY_INDEX = "index"
}
