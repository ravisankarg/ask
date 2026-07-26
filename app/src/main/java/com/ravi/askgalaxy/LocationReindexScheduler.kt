package com.ravi.askgalaxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LocationReindexScheduler {
    const val UNIQUE_WORK_NAME = "ask_galaxy_location_reindex"

    fun enqueueIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!MediaLocationAccess.hasPermission(appContext)) return false
        val pending = GalleryDatabase(appContext).use { it.pendingLocationCount() }
        if (pending == 0) return false
        enqueue(appContext, ExistingWorkPolicy.KEEP)
        return true
    }

    fun rebuild(context: Context): Int {
        val appContext = context.applicationContext
        if (!MediaLocationAccess.hasPermission(appContext)) return 0
        val invalidated = GalleryDatabase(appContext).use {
            it.invalidatePhotoLocationIndex()
        }
        enqueue(appContext, ExistingWorkPolicy.REPLACE)
        return invalidated
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        PreparationNotifier.createChannel(context)
        val request = OneTimeWorkRequestBuilder<LocationReindexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }
}
