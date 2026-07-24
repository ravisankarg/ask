package com.ravi.askgalaxy

import android.content.Context

enum class PreparationPhase(val wire: String) {
    IDLE("idle"),
    DOWNLOADING("downloading"),
    WAITING_FOR_MODELS("waiting_for_models"),
    WAITING_FOR_PERMISSION("waiting_for_permission"),
    SCANNING("scanning"),
    INDEXING("indexing"),
    WAITING_FOR_FACE_TAGS("waiting_for_face_tags"),
    READY("ready"),
    RETRYING("retrying"),
    FAILED("failed");

    companion object {
        fun fromWire(value: String): PreparationPhase =
            entries.firstOrNull { it.wire == value } ?: IDLE
    }
}

data class PreparationSnapshot(
    val phase: PreparationPhase = PreparationPhase.IDLE,
    val message: String = "Preparing Ask Galaxy…",
    val current: Long = 0L,
    val total: Long = 0L,
    val modelCurrent: Long = 0L,
    val modelTotal: Long = 0L,
    val indexCurrent: Long = 0L,
    val indexTotal: Long = 0L,
    val bytesCurrent: Long = 0L,
    val bytesTotal: Long = 0L,
    val updatedAtMs: Long = 0L,
    val error: String = "",
) {
    val isReady: Boolean get() = phase == PreparationPhase.READY

    val isPrepared: Boolean
        get() = phase == PreparationPhase.READY || phase == PreparationPhase.WAITING_FOR_FACE_TAGS

    val percent: Int
        get() = if (total <= 0L) 0 else ((current * 100L) / total).toInt().coerceIn(0, 100)
}

class PreparationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): PreparationSnapshot = synchronized(preferences) {
        val savedVersion = preferences.getInt(KEY_FLOW_VERSION, 0)
        if (savedVersion < CURRENT_FLOW_VERSION) {
            return@synchronized PreparationSnapshot(
                phase = PreparationPhase.IDLE,
                message = "Preparing the new private gallery index…",
            )
        }
        PreparationSnapshot(
            phase = PreparationPhase.fromWire(preferences.getString(KEY_PHASE, null).orEmpty()),
            message = preferences.getString(KEY_MESSAGE, null) ?: "Preparing Ask Galaxy…",
            current = preferences.getLong(KEY_CURRENT, 0L),
            total = preferences.getLong(KEY_TOTAL, 0L),
            modelCurrent = preferences.getLong(KEY_MODEL_CURRENT, 0L),
            modelTotal = preferences.getLong(KEY_MODEL_TOTAL, 0L),
            indexCurrent = preferences.getLong(KEY_INDEX_CURRENT, 0L),
            indexTotal = preferences.getLong(KEY_INDEX_TOTAL, 0L),
            bytesCurrent = preferences.getLong(KEY_BYTES_CURRENT, 0L),
            bytesTotal = preferences.getLong(KEY_BYTES_TOTAL, 0L),
            updatedAtMs = preferences.getLong(KEY_UPDATED_AT, 0L),
            error = preferences.getString(KEY_ERROR, null).orEmpty(),
        )
    }

    fun write(snapshot: PreparationSnapshot) {
        synchronized(preferences) {
            preferences.edit()
                .putString(KEY_PHASE, snapshot.phase.wire)
                .putString(KEY_MESSAGE, snapshot.message)
                .putLong(KEY_CURRENT, snapshot.current)
                .putLong(KEY_TOTAL, snapshot.total)
                .putLong(KEY_MODEL_CURRENT, snapshot.modelCurrent)
                .putLong(KEY_MODEL_TOTAL, snapshot.modelTotal)
                .putLong(KEY_INDEX_CURRENT, snapshot.indexCurrent)
                .putLong(KEY_INDEX_TOTAL, snapshot.indexTotal)
                .putLong(KEY_BYTES_CURRENT, snapshot.bytesCurrent)
                .putLong(KEY_BYTES_TOTAL, snapshot.bytesTotal)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .putString(KEY_ERROR, snapshot.error)
                .putInt(KEY_FLOW_VERSION, CURRENT_FLOW_VERSION)
                .commit()
        }
    }

    fun markFaceTagsComplete() {
        val snapshot = read()
        if (snapshot.phase != PreparationPhase.WAITING_FOR_FACE_TAGS) return
        write(
            snapshot.copy(
                phase = PreparationPhase.READY,
                message = "Ready for private gallery search.",
                error = "",
            ),
        )
    }

    companion object {
        private const val PREFS_NAME = "preparation_state"
        private const val KEY_PHASE = "phase"
        private const val KEY_MESSAGE = "message"
        private const val KEY_CURRENT = "current"
        private const val KEY_TOTAL = "total"
        private const val KEY_MODEL_CURRENT = "model_current"
        private const val KEY_MODEL_TOTAL = "model_total"
        private const val KEY_INDEX_CURRENT = "index_current"
        private const val KEY_INDEX_TOTAL = "index_total"
        private const val KEY_BYTES_CURRENT = "bytes_current"
        private const val KEY_BYTES_TOTAL = "bytes_total"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ERROR = "error"
        private const val KEY_FLOW_VERSION = "flow_version"
        // v5 retries comma-separated and ISO-6709 GPS rows through the
        // rate-limited online fallback before rebuilding the v9 episode index.
        private const val CURRENT_FLOW_VERSION = 5
    }
}
