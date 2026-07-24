package com.ravi.askgalaxy

import android.content.Context

/**
 * Keeps the public reverse-geocoder optional at runtime. Android's system
 * Geocoder remains the primary provider; users can switch the external
 * fallback off immediately without an application update.
 */
object LocationEnrichmentPreferences {
    private const val PREFERENCES = "location_enrichment"
    private const val KEY_OPENSTREETMAP_FALLBACK = "openstreetmap_fallback"

    fun isOpenStreetMapFallbackEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_OPENSTREETMAP_FALLBACK, true)

    fun setOpenStreetMapFallbackEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OPENSTREETMAP_FALLBACK, enabled)
            .apply()
    }
}
