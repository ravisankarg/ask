package com.ravi.askgalaxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object DocumentIndexScheduler {
    private const val WORK = "ask_galaxy_document_index"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Starts a fresh pass after a permission change or a stale failed retry. */
    fun restart(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK)
        enqueue(appContext)
    }
}
