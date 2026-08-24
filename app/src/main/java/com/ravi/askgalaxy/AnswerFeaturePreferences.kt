package com.ravi.askgalaxy

import android.content.Context

/** User-owned answer-generation mode. Query planning and retrieval always remain active. */
internal object AnswerFeaturePreferences {
    private const val PREFERENCES = "answer_feature"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}

internal object AnswerFeaturePolicy {
    fun effectiveNeedsAnswer(plannerNeedsAnswer: Boolean, answerFeatureEnabled: Boolean): Boolean =
        plannerNeedsAnswer && answerFeatureEnabled
}
