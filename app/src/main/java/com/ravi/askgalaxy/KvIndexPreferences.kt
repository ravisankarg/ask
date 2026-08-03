package com.ravi.askgalaxy

import android.content.Context

/** Opt-in gate for the separate document key-value index. */
object KvIndexPreferences {
    private const val PREFS = "ask_galaxy_kv_index"
    private const val ENABLED = "enabled"
    private const val INDEXING = "indexing"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    /** True only while the KV WorkManager worker is actively doing its work. */
    fun isIndexing(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(INDEXING, false)

    internal fun setIndexing(context: Context, indexing: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(INDEXING, indexing)
            .apply()
    }
}
