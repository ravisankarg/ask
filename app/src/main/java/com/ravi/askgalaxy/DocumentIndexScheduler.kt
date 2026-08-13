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
    private const val SCHEDULER_PREFERENCES = "ask_galaxy_document_index_scheduler"
    private const val NETWORK_FREE_WORK_VERSION = 1
    private const val NETWORK_FREE_WORK_VERSION_KEY = "network_free_work_version"

    fun enqueue(context: Context) {
        val store = IndexProgressStore(context.applicationContext)
        // App/activity lifecycle callbacks may run repeatedly after a
        // successful pass. Do not create a fresh full scan once the current
        // document index is complete; restart() is the explicit rebuild path.
        if (store.read(IndexProgressStage.DOCUMENT).completed) return
        // A failed pass is also an explicit-retry state. Without this guard,
        // every Activity launch would enqueue the failed unique work again.
        if (DocumentSource.entries.any { source ->
                store.read(source.progressStage()).error.isNotBlank()
            }
        ) return
        // A gated EmbeddingGemma authorization failure is an explicit user
        // action, not a reason to launch an anonymous request on every app
        // resume. AuthorizationActivity calls restart() after approval.
        if (!embeddingGemmaInstalled(context) &&
            store.read(IndexProgressStage.EMBEDDING_GEMMA).error.isNotBlank()
        ) return
        val appContext = context.applicationContext
        val migrated = migrateToNetworkFreeWork(appContext)
        enqueueInternal(appContext, replaceExisting = migrated)
    }

    private fun enqueueInternal(context: Context, replaceExisting: Boolean = false) {
        val constraints = Constraints.Builder().apply {
            // EmbeddingGemma is the only network-dependent part. Once the
            // two artifacts are present, extraction and indexing are local;
            // CONNECTED permits Wi-Fi, cellular, or hotspot connectivity.
            if (!embeddingGemmaInstalled(context)) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Starts a fresh pass after a permission change or a stale failed retry. */
    fun restart(context: Context) {
        val appContext = context.applicationContext
        if (embeddingGemmaInstalled(appContext)) markNetworkFreeWorkMigrated(appContext)
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK)
        enqueueInternal(appContext)
    }

    private fun embeddingGemmaInstalled(context: Context): Boolean =
        ModelCatalog.embeddingGemma.isInstalled(context) &&
            ModelCatalog.embeddingGemmaTokenizer.isInstalled(context)

    /** Replace a pre-update CONNECTED-constrained request once, after models exist. */
    private fun migrateToNetworkFreeWork(context: Context): Boolean {
        if (!embeddingGemmaInstalled(context)) return false
        val preferences = context.getSharedPreferences(SCHEDULER_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getInt(NETWORK_FREE_WORK_VERSION_KEY, 0) >= NETWORK_FREE_WORK_VERSION) {
            return false
        }
        markNetworkFreeWorkMigrated(context)
        return true
    }

    private fun markNetworkFreeWorkMigrated(context: Context) {
        context.getSharedPreferences(SCHEDULER_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(NETWORK_FREE_WORK_VERSION_KEY, NETWORK_FREE_WORK_VERSION)
            .apply()
    }
}
