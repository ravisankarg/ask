package com.ravi.askgalaxy

import android.content.Context

data class LocationIndexProgress(
    val completed: Int,
    val total: Int,
    val withGps: Int,
    val resolved: Int,
    val retryable: Int,
)

/**
 * Indexing-time capture/GPS enrichment.
 *
 * Raw coordinates and readable place names are separate SQLite fields. The
 * Android's online Geocoder is attempted first. A rate-limited OpenStreetMap
 * fallback handles devices whose system service returns no result. Successful
 * names are cached by a coarse coordinate key so burst photos do not cause
 * repeated network requests. Rows with GPS but no readable result remain
 * pending and are retried by the next resumable preparation pass.
 */
class LocationIndexer(
    context: Context,
    private val database: GalleryDatabase,
) {
    private val metadataReader = GalleryMetadataReader(context.applicationContext)

    fun indexBlocking(
        onProgress: (LocationIndexProgress) -> Unit = {},
    ): LocationIndexProgress {
        val pending = database.pendingLocationMetadata()
        if (pending.isEmpty()) return LocationIndexProgress(0, 0, 0, 0, 0)

        var completed = 0
        var withGps = 0
        var resolved = 0
        var retryable = 0
        pending.forEach { media ->
            val enriched = metadataReader.enrich(
                media,
                setOf(AnswerMetadataField.TIME, AnswerMetadataField.LOCATION),
                includeLocationName = false,
            )
            val rawLocation = enriched.location?.trim()?.takeIf(String::isNotBlank)
            val locationName = rawLocation?.let { raw ->
                val key = metadataReader.locationCacheKey(raw)
                val cached = key?.let(database::cachedLocationName)
                cached ?: metadataReader.resolveLocationName(raw)?.also { name ->
                    if (key != null) database.saveLocationName(key, name)
                }
            }
            database.saveEnrichedMetadata(
                mediaStoreId = media.mediaStoreId,
                dateTakenMs = enriched.dateTakenMs,
                rawLocation = rawLocation,
                locationName = locationName,
            )

            completed += 1
            if (rawLocation != null) withGps += 1
            if (locationName != null) resolved += 1
            if (rawLocation != null && locationName == null) retryable += 1
            if (completed == pending.size || completed % PROGRESS_INTERVAL == 0) {
                onProgress(
                    LocationIndexProgress(
                        completed = completed,
                        total = pending.size,
                        withGps = withGps,
                        resolved = resolved,
                        retryable = retryable,
                    ),
                )
            }
        }
        return LocationIndexProgress(completed, pending.size, withGps, resolved, retryable)
    }

    private companion object {
        const val PROGRESS_INTERVAL = 16
    }
}
