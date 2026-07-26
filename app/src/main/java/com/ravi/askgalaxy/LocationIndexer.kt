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
    private val appContext = context.applicationContext
    private val metadataReader = GalleryMetadataReader(appContext)

    fun indexBlocking(
        shouldContinue: () -> Boolean = { true },
        onProgress: (LocationIndexProgress) -> Unit = {},
    ): LocationIndexProgress {
        val pending = database.pendingLocationMetadata()
        if (pending.isEmpty()) return LocationIndexProgress(0, 0, 0, 0, 0)

        var completed = 0
        var withGps = 0
        var resolved = 0
        var retryable = 0
        pending.forEach { media ->
            if (!shouldContinue()) {
                return LocationIndexProgress(completed, pending.size, withGps, resolved, retryable)
            }
            val imageNeedsConsent =
                media.mimeType.startsWith("image/", ignoreCase = true) &&
                    !MediaLocationAccess.hasPermission(appContext)
            if (imageNeedsConsent) {
                // Leave the row pending. Redacted EXIF must never be persisted
                // as authoritative proof that this image has no GPS.
                completed += 1
                retryable += 1
                publishIfNeeded(
                    completed,
                    pending.size,
                    withGps,
                    resolved,
                    retryable,
                    onProgress,
                )
                return@forEach
            }
            val enriched = metadataReader.enrich(
                media,
                setOf(AnswerMetadataField.TIME, AnswerMetadataField.LOCATION),
                includeLocationName = false,
            )
            val rawLocation = enriched.location
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeIf { GalleryMetadataReader.parseCoordinates(it) != null }
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
            publishIfNeeded(
                completed,
                pending.size,
                withGps,
                resolved,
                retryable,
                onProgress,
            )
        }
        return LocationIndexProgress(completed, pending.size, withGps, resolved, retryable)
    }

    private fun publishIfNeeded(
        completed: Int,
        total: Int,
        withGps: Int,
        resolved: Int,
        retryable: Int,
        onProgress: (LocationIndexProgress) -> Unit,
    ) {
        if (completed == total || completed % PROGRESS_INTERVAL == 0) {
            onProgress(
                LocationIndexProgress(
                    completed = completed,
                    total = total,
                    withGps = withGps,
                    resolved = resolved,
                    retryable = retryable,
                ),
            )
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL = 16
    }
}
