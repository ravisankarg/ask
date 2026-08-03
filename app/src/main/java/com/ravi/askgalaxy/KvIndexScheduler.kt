package com.ravi.askgalaxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object KvIndexScheduler {
    const val UNIQUE_WORK_NAME = "ask_galaxy_kv_document_index"

    fun enqueue(context: Context, replaceExisting: Boolean) {
        val request = OneTimeWorkRequestBuilder<KvIndexWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * A native crash can leave WorkManager's persisted retry record in a long
     * backoff. On the next app launch, replace only that incomplete record so
     * the opted-in document index resumes from its saved document count.
     */
    fun resumeIncompleteIndex(context: Context) {
        val appContext = context.applicationContext
        if (!KvIndexPreferences.isEnabled(appContext) ||
            IndexProgressStore(appContext).read(IndexProgressStage.KV).completed
        ) return
        enqueue(appContext, replaceExisting = true)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context.applicationContext)
        .cancelUniqueWork(UNIQUE_WORK_NAME)
}
