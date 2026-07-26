package com.ravi.askgalaxy

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object OcrReindexScheduler {
    const val UNIQUE_WORK_NAME = "ask_galaxy_ocr_reindex"

    /** Enqueues a signature migration without invalidating already-current rows. */
    fun enqueueIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        val pending = GalleryDatabase(appContext).use { it.pendingOcrCount() }
        if (pending == 0) return false
        enqueue(appContext, ExistingWorkPolicy.KEEP)
        return true
    }

    /** Explicit user/debug rebuild. No non-OCR table or vector file is changed. */
    fun rebuild(context: Context): Int {
        val appContext = context.applicationContext
        val invalidated = GalleryDatabase(appContext).use { it.invalidateOcrIndex() }
        enqueue(appContext, ExistingWorkPolicy.REPLACE)
        return invalidated
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        PreparationNotifier.createChannel(context)
        val request = OneTimeWorkRequestBuilder<OcrReindexWorker>()
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
