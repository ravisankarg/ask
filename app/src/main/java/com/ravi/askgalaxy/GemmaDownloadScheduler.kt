package com.ravi.askgalaxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Owns the large Gemma download independently from gallery indexing. */
object GemmaDownloadScheduler {
    const val UNIQUE_WORK_NAME = "ask_galaxy_gemma_download"

    fun enqueueIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        if (ModelCatalog.gemma.isInstalled(appContext)) return false
        PreparationNotifier.createChannel(appContext)
        val request = OneTimeWorkRequestBuilder<GemmaDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }
}
