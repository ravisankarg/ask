package com.ravi.askgalaxy

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PreparationScheduler {
    private const val UNIQUE_WORK_NAME = "ask_galaxy_preparation"

    fun enqueue(context: Context) {
        val appContext = context.applicationContext
        GemmaDownloadScheduler.enqueueIfNeeded(appContext)
        PreparationNotifier.createChannel(appContext)
        val request = OneTimeWorkRequestBuilder<PreparationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30L,
                TimeUnit.SECONDS,
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Restarts only the resumable worker. It never clears SQLite rows, vector
     * files, model files, or the per-item completion flags.
     */
    fun restart(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        enqueue(appContext)
    }
}
