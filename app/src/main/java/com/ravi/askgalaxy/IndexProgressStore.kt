package com.ravi.askgalaxy

import android.content.Context

enum class IndexProgressStage(val wire: String, val label: String) {
    MODELS("models", "Model downloads"),
    EMBEDDING_GEMMA("embedding_gemma", "EmbeddingGemma 300M download"),
    VISUAL("visual", "SigLIP visual index"),
    OCR("ocr", "OCR text index"),
    LOCATION("location", "Photo locations"),
    FACE("face", "Face index"),
    EPISODE("episode", "Photo episodes"),
    INCREMENTAL_UPDATE("incremental_update", "Incremental update"),
    DOCUMENT("document", "All personal sources (overall)"),
    DOCUMENT_MESSAGES("document_messages", "Messages"),
    DOCUMENT_CALENDAR("document_calendar", "Calendar"),
    DOCUMENT_FILES("document_files", "My Files text chunks"),
    DOCUMENT_CONTACTS("document_contacts", "Contacts"),
    DOCUMENT_CALL_LOGS("document_call_logs", "Call logs"),
}

data class StageProgress(
    val current: Long = 0L,
    val total: Long = 0L,
    val startedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val completed: Boolean = false,
    val paused: Boolean = false,
    val error: String = "",
    val phase: String = "",
) {
    val percent: Int
        get() = if (total <= 0L) 0 else ((current * 100L) / total).toInt().coerceIn(0, 100)

    fun etaMs(nowMs: Long = System.currentTimeMillis()): Long? {
        if (completed || current <= 0L || total <= current || startedAtMs <= 0L) return null
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(1L)
        val rate = current.toDouble() / elapsed.toDouble()
        if (rate <= 0.0) return null
        return ((total - current) / rate).toLong().coerceAtLeast(0L)
    }
}

/** Small independent progress records so model and index workers never overwrite each other. */
class IndexProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(stage: IndexProgressStage): StageProgress = synchronized(preferences) {
        StageProgress(
            current = preferences.getLong(key(stage, "current"), 0L),
            total = preferences.getLong(key(stage, "total"), 0L),
            startedAtMs = preferences.getLong(key(stage, "started"), 0L),
            updatedAtMs = preferences.getLong(key(stage, "updated"), 0L),
            completed = preferences.getBoolean(key(stage, "completed"), false),
            paused = preferences.getBoolean(key(stage, "paused"), false),
            error = preferences.getString(key(stage, "error"), null).orEmpty(),
            phase = preferences.getString(key(stage, "phase"), null).orEmpty(),
        )
    }

    fun readAll(): Map<IndexProgressStage, StageProgress> =
        IndexProgressStage.entries.associateWith(::read)

    fun update(
        stage: IndexProgressStage,
        current: Long,
        total: Long,
        completed: Boolean = total > 0L && current >= total,
        error: String = "",
        phase: String = "",
        paused: Boolean? = null,
    ) {
        synchronized(preferences) {
            val previousStarted = preferences.getLong(key(stage, "started"), 0L)
            val previousCurrent = preferences.getLong(key(stage, "current"), 0L)
            val previousPaused = preferences.getBoolean(key(stage, "paused"), false)
            val now = System.currentTimeMillis()
            val started = if (previousStarted == 0L || current < previousCurrent) now else previousStarted
            preferences.edit()
                .putLong(key(stage, "current"), current.coerceAtLeast(0L))
                .putLong(key(stage, "total"), total.coerceAtLeast(0L))
                .putLong(key(stage, "started"), started)
                .putLong(key(stage, "updated"), now)
                .putBoolean(key(stage, "completed"), completed)
                .putBoolean(key(stage, "paused"), paused ?: previousPaused)
                .putString(key(stage, "error"), error)
                .putString(key(stage, "phase"), phase)
                .apply()
        }
    }

    fun reset(stage: IndexProgressStage) {
        update(stage, 0L, 0L, completed = false, paused = false)
    }

    private fun key(stage: IndexProgressStage, suffix: String): String =
        "${stage.wire}_$suffix"

    private companion object {
        const val PREFS_NAME = "index_progress"
    }
}
