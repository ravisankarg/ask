package com.ravi.askgalaxy

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads metadata only for answer evidence. The gallery index remains
 * reusable; no full scan is needed just to make a date/location answer.
 */
class GalleryMetadataReader(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = context.applicationContext.contentResolver
    private val onlineReverseGeocoder = OnlineReverseGeocoder(appContext)
    private val timeScopeCache = ConcurrentHashMap<QueryTimeScope, CachedTimeScope>()
    private val locationScopeCache = ConcurrentHashMap<String, CachedLocationScope>()
    private val mediaStoreDateCache = ConcurrentHashMap<Long, Long>()
    private val mediaStoreLocationCache = ConcurrentHashMap<Long, String>()
    // Empty strings are intentional cache values: an offline device or an
    // image without GPS must not retry the same lookup on every answer.
    private val mediaStoreLocationNameCache = ConcurrentHashMap<Long, String>()
    private val reverseLocationCache = ConcurrentHashMap<String, String>()
    private val forwardLocationCache = ConcurrentHashMap<String, ResolvedLocation>()

    fun enrich(media: GalleryMedia): GalleryMedia = enrich(
        media,
        AnswerMetadataField.values().toSet(),
    )

    fun enrich(
        media: GalleryMedia,
        fields: Set<AnswerMetadataField>,
        includeLocationName: Boolean = true,
    ): GalleryMedia {
        val wantsTime = AnswerMetadataField.TIME in fields && media.dateTakenMs == null
        val cachedLocation = mediaStoreLocationCache[media.mediaStoreId]
        val wantsRawLocation = AnswerMetadataField.LOCATION in fields &&
            media.location == null && cachedLocation == null
        val wantsLocationName = includeLocationName &&
            AnswerMetadataField.LOCATION in fields &&
            media.locationName == null
        if (!wantsTime && !wantsRawLocation && !wantsLocationName) return media
        val uri = runCatching { Uri.parse(media.contentUri) }.getOrNull()
            ?: return media
        val isVideo = media.mimeType.startsWith("video/", ignoreCase = true)
        val mediaStoreDate = if (wantsTime) {
            mediaStoreDateCache[media.mediaStoreId] ?: readMediaStoreDate(uri)?.also {
                mediaStoreDateCache[media.mediaStoreId] = it
            }
        } else null
        val needsEmbeddedTime = wantsTime && mediaStoreDate == null
        val exif = if (isVideo) {
            null
        } else if (needsEmbeddedTime || wantsRawLocation) {
            readExif(uri, needsEmbeddedTime, wantsRawLocation)
        } else {
            null
        }
        val video = if (isVideo && (needsEmbeddedTime || wantsRawLocation)) {
            readVideoMetadata(uri, needsEmbeddedTime, wantsRawLocation)
        } else {
            null
        }
        val location = media.location ?: cachedLocation ?: exif?.location ?: video?.location
        if (location != null) mediaStoreLocationCache[media.mediaStoreId] = location
        val locationName = if (wantsLocationName) {
            val cachedName = mediaStoreLocationNameCache[media.mediaStoreId]
            if (cachedName != null) {
                cachedName.takeIf(String::isNotBlank)
            } else {
                val resolved = location?.let(::reverseGeocode).orEmpty()
                mediaStoreLocationNameCache[media.mediaStoreId] = resolved
                resolved.takeIf(String::isNotBlank)
            }
        } else {
            media.locationName
        }
        return media.copy(
            dateTakenMs = media.dateTakenMs ?: mediaStoreDate ?: exif?.dateTakenMs ?: video?.dateTakenMs,
            location = location,
            locationName = locationName,
        )
    }

    /**
     * Matches a user place against raw GPS or provider location strings.
     * Direct text matching stays first and is effectively free; geocoding is
     * only used for the one requested place and is cached for the session.
     */
    fun locationMatches(actual: String?, requested: String): Boolean {
        val normalizedActual = normalize(actual.orEmpty())
        val normalizedRequested = normalize(requested)
        if (normalizedActual.isBlank() || normalizedRequested.isBlank()) return false
        if (normalizedActual.contains(normalizedRequested)) return true
        val requestedTokens = normalizedRequested.split(' ').filter { it.length > 1 }
        if (requestedTokens.isNotEmpty() && requestedTokens.all(normalizedActual::contains)) return true

        val actualPoint = parseCoordinates(actual.orEmpty()) ?: return false
        val requestedPlace = forwardGeocode(requested) ?: return false
        return distanceKm(
            actualPoint.first,
            actualPoint.second,
            requestedPlace.latitude,
            requestedPlace.longitude,
        ) <= requestedPlace.radiusKm
    }

    /** Returns a readable place name for a raw GPS/provider location, if one is available. */
    fun displayLocation(rawLocation: String?): String? {
        val raw = rawLocation?.trim()?.takeIf(String::isNotBlank) ?: return null
        return reverseGeocode(raw) ?: raw
    }

    /**
     * Resolves raw GPS through Android's network-backed Geocoder. Unlike
     * [displayLocation], this returns null on an unavailable/offline lookup so
     * preprocessing can leave the row retryable instead of indexing GPS text
     * as though it were a human place name.
     */
    fun resolveLocationName(rawLocation: String): String? =
        reverseGeocode(rawLocation, allowOnlineFallback = true)

    /** Stable, locality-sized key for durable reverse-geocode reuse. */
    fun locationCacheKey(rawLocation: String): String? {
        val point = parseCoordinates(rawLocation) ?: return null
        return coordinateKey(point.first, point.second)
    }

    /**
     * Uses the photo provider's indexed GPS columns when available. This is
     * much cheaper and more complete than asking the semantic index for a
     * place that was never part of its text embedding. A null result means
     * this provider does not expose usable coordinates, so callers can retain
     * their lazy EXIF fallback.
     */
    fun mediaStoreIdsMatchingLocation(requested: String): Set<Long>? {
        val key = normalize(requested)
        if (key.isBlank()) return null
        val now = SystemClock.elapsedRealtime()
        locationScopeCache[key]?.let { cached ->
            if (now - cached.createdAtMs < LOCATION_SCOPE_CACHE_TTL_MS) {
                return cached.ids
            }
            locationScopeCache.remove(key, cached)
        }

        // Resolve the requested place once. Calling locationMatches for every
        // row would repeatedly cross the Geocoder boundary while the provider
        // cursor scans the gallery.
        val requestedPlace = forwardGeocode(requested)
        if (requestedPlace == null) {
            cacheLocationScope(key, now, null)
            return null
        }
        val ids = LinkedHashSet<Long>()
        var sawCoordinate = false
        val queried = runCatching {
            // Use the Files collection so the location hard-scope has the
            // same image+video universe as the time scope and the SQLite
            // index. The Images-only provider query silently excluded videos
            // from otherwise valid place searches.
            val filesUri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                "latitude",
                "longitude",
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
            resolver.query(
                filesUri,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val latitudeIndex = cursor.getColumnIndex("latitude")
                val longitudeIndex = cursor.getColumnIndex("longitude")
                if (idIndex < 0 || latitudeIndex < 0 || longitudeIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val latitude = cursor.getString(latitudeIndex)?.trim().orEmpty()
                    val longitude = cursor.getString(longitudeIndex)?.trim().orEmpty()
                    if (latitude.isBlank() || longitude.isBlank()) continue
                    val latitudeValue = latitude.toDoubleOrNull() ?: continue
                    val longitudeValue = longitude.toDoubleOrNull() ?: continue
                    if (latitudeValue !in -90.0..90.0 || longitudeValue !in -180.0..180.0) continue
                    sawCoordinate = true
                    val mediaStoreId = cursor.getLong(idIndex)
                    if (distanceKm(
                            latitudeValue,
                            longitudeValue,
                            requestedPlace.latitude,
                            requestedPlace.longitude,
                        ) <= requestedPlace.radiusKm
                    ) {
                        ids += mediaStoreId
                        // The final evidence pass will ask for the same raw
                        // location. Retain only coordinates that actually
                        // matched this bounded scope so we avoid reopening
                        // those files through EXIF without growing a full
                        // gallery-sized string cache.
                        mediaStoreLocationCache[mediaStoreId] = String.format(
                            Locale.US,
                            "GPS %.6f, %.6f",
                            latitudeValue,
                            longitudeValue,
                        )
                    }
                }
            }
            true
        }.getOrDefault(false)
        val result = if (queried && sawCoordinate) ids else null
        cacheLocationScope(key, now, result)
        return result
    }

    private fun cacheLocationScope(key: String, createdAtMs: Long, ids: Set<Long>?) {
        locationScopeCache[key] = CachedLocationScope(createdAtMs, ids)
        while (locationScopeCache.size > LOCATION_SCOPE_CACHE_MAX_ENTRIES) {
            val oldest = locationScopeCache.entries.minByOrNull { it.value.createdAtMs } ?: break
            if (!locationScopeCache.remove(oldest.key, oldest.value)) break
        }
    }

    /**
     * Reads MediaStore's indexed capture timestamps in one cursor pass. This
     * is deliberately separate from EXIF enrichment: it constrains native
     * retrieval without opening every candidate image or touching the vector
     * index. A null result means the provider exposed no usable capture dates
     * (or failed); a non-null empty set is an authoritative hard scope with no
     * matching media. Callers may use lazy EXIF/modified-time fallback only for
     * the null case.
     */
    fun mediaStoreIdsMatchingTime(scope: QueryTimeScope): Set<Long>? {
        val now = SystemClock.elapsedRealtime()
        timeScopeCache[scope]?.let { cached ->
            if (now - cached.createdAtMs < TIME_SCOPE_CACHE_TTL_MS) {
                return cached.ids
            }
            timeScopeCache.remove(scope, cached)
        }
        var sawUsableTimestamp = false
        val ids = runCatching {
            val ids = LinkedHashSet<Long>()
            val filesUri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                DATE_TAKEN_COLUMN,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND $DATE_TAKEN_COLUMN > 0"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
            resolver.query(filesUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dateIndex = cursor.getColumnIndexOrThrow(DATE_TAKEN_COLUMN)
                while (cursor.moveToNext()) {
                    val timestamp = cursor.getLong(dateIndex).takeIf { it > 0L } ?: continue
                    sawUsableTimestamp = true
                    val mediaStoreId = cursor.getLong(idIndex)
                    mediaStoreDateCache[mediaStoreId] = timestamp
                    if (scope.matches(timestamp)) ids += mediaStoreId
                }
            }
            ids
        }.getOrNull() ?: return null
        val scopedIds = ids.takeIf { sawUsableTimestamp }
        timeScopeCache[scope] = CachedTimeScope(now, scopedIds)
        // Query scopes are user-generated. Keep this cache bounded so a long
        // session with many date searches cannot retain one 16K-id set per
        // distinct phrase indefinitely.
        while (timeScopeCache.size > TIME_SCOPE_CACHE_MAX_ENTRIES) {
            val oldest = timeScopeCache.entries.minByOrNull { it.value.createdAtMs } ?: break
            if (!timeScopeCache.remove(oldest.key, oldest.value)) break
        }
        return scopedIds
    }

    private fun readMediaStoreDate(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(DATE_TAKEN_COLUMN), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(DATE_TAKEN_COLUMN)
            if (index < 0 || cursor.isNull(index)) return@use null
            cursor.getLong(index).takeIf { it > 0L }
        }
    }.getOrNull()

    private fun readExif(
        uri: Uri,
        wantsTime: Boolean,
        wantsLocation: Boolean,
    ): ExifMetadata? = runCatching {
        val readableUri = if (wantsLocation) {
            MediaLocationAccess.originalUri(appContext, uri) ?: return@runCatching null
        } else {
            uri
        }
        resolver.openFileDescriptor(readableUri, "r")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            val dateTakenMs = if (wantsTime) {
                listOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_DATETIME,
                ).asSequence()
                    .mapNotNull { tag -> exif.getAttribute(tag)?.let(::parseExifDate) }
                    .firstOrNull()
            } else null
            val location = if (wantsLocation) {
                exif.latLong?.let { coordinates ->
                    String.format(Locale.US, "GPS %.6f, %.6f", coordinates[0], coordinates[1])
                }
            } else null
            ExifMetadata(dateTakenMs, location)
        }
    }.getOrNull()

    private fun readVideoMetadata(
        uri: Uri,
        wantsTime: Boolean,
        wantsLocation: Boolean,
    ): VideoMetadata? = runCatching {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: return@runCatching null
        descriptor.use { assetFileDescriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(assetFileDescriptor.fileDescriptor)
                VideoMetadata(
                    dateTakenMs = if (wantsTime) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                            ?.let(::parseVideoDate)
                    } else null,
                    location = if (wantsLocation) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    } else null,
                )
            } finally {
                retriever.release()
            }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun reverseGeocode(
        rawLocation: String,
        allowOnlineFallback: Boolean = false,
    ): String? {
        val point = parseCoordinates(rawLocation) ?: return null
        val key = coordinateKey(point.first, point.second)
        val cached = reverseLocationCache[key]
        if (cached != null) return cached.takeIf(String::isNotBlank)
        val systemResolved = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val address = Geocoder(appContext, Locale.getDefault())
                .getFromLocation(point.first, point.second, 1)
                ?.firstOrNull()
                ?: return@runCatching null
            readableAddress(address)
        }.getOrNull()
        val resolved = (
            systemResolved ?: if (allowOnlineFallback) {
                onlineReverseGeocoder.resolve(point.first, point.second)
            } else {
                null
            }
            ).orEmpty()
        reverseLocationCache[key] = resolved
        return resolved.takeIf(String::isNotBlank)
    }

    @Suppress("DEPRECATION")
    private fun forwardGeocode(requested: String): ResolvedLocation? {
        val key = normalize(requested)
        if (key.isBlank()) return null
        val cached = forwardLocationCache[key]
        if (cached != null) return cached.takeUnless { it.isUnavailable }
        val resolved = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val address = Geocoder(appContext, Locale.getDefault())
                .getFromLocationName(requested.trim(), 1)
                ?.firstOrNull()
                ?: return@runCatching null
            val latitude = address.latitude
            val longitude = address.longitude
            if (!latitude.isFinite() || !longitude.isFinite()) return@runCatching null
            ResolvedLocation(
                latitude = latitude,
                longitude = longitude,
                radiusKm = addressRadiusKm(address, requested),
            )
        }.getOrNull() ?: ResolvedLocation.UNAVAILABLE
        forwardLocationCache[key] = resolved
        return resolved.takeUnless { it.isUnavailable }
    }

    private fun readableAddress(address: Address): String? {
        val parts = listOfNotNull(
            address.locality,
            address.subAdminArea,
            address.adminArea,
            address.countryName,
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(::normalize)
        return parts.joinToString(", ").takeIf(String::isNotBlank)
    }

    private fun addressRadiusKm(address: Address, requested: String): Double {
        val normalized = normalize(requested)
        val admin = normalize(address.adminArea.orEmpty())
        val subAdmin = normalize(address.subAdminArea.orEmpty())
        val locality = normalize(address.locality.orEmpty())
        return when {
            normalized == admin || normalized == subAdmin -> 180.0
            normalized == locality -> 45.0
            normalized.contains(admin) && admin.isNotBlank() -> 180.0
            normalized.contains(locality) && locality.isNotBlank() -> 45.0
            else -> 100.0
        }
    }

    private fun coordinateKey(latitude: Double, longitude: Double): String =
        // Roughly 110 m at the equator. Nearby burst photos should share one
        // online lookup while retaining their exact raw GPS separately.
        String.format(Locale.US, "%.3f,%.3f", latitude, longitude)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}.+-]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun distanceKm(
        firstLatitude: Double,
        firstLongitude: Double,
        secondLatitude: Double,
        secondLongitude: Double,
    ): Double {
        val earthRadiusKm = 6_371.0
        val dLatitude = Math.toRadians(secondLatitude - firstLatitude)
        val dLongitude = Math.toRadians(secondLongitude - firstLongitude)
        val a = kotlin.math.sin(dLatitude / 2) * kotlin.math.sin(dLatitude / 2) +
            kotlin.math.cos(Math.toRadians(firstLatitude)) *
            kotlin.math.cos(Math.toRadians(secondLatitude)) *
            kotlin.math.sin(dLongitude / 2) * kotlin.math.sin(dLongitude / 2)
        return earthRadiusKm * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun parseExifDate(value: String): Long? = runCatching {
        LocalDateTime.parse(value.trim(), EXIF_DATE_FORMAT)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun parseVideoDate(value: String): Long? = runCatching {
        val normalized = value.trim()
            .removePrefix("UTC")
            .replaceFirst("^(\\d{4})(\\d{2})(\\d{2})T".toRegex(), "$1-$2-$3T")
        OffsetDateTime.parse(normalized)
            .toInstant()
            .toEpochMilli()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value.trim(), VIDEO_DATE_FORMAT)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private data class ExifMetadata(
        val dateTakenMs: Long?,
        val location: String?,
    )

    private data class VideoMetadata(
        val dateTakenMs: Long?,
        val location: String?,
    )

    private data class CachedTimeScope(
        val createdAtMs: Long,
        val ids: Set<Long>?,
    )

    private data class CachedLocationScope(
        val createdAtMs: Long,
        val ids: Set<Long>?,
    )

    private data class ResolvedLocation(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
        val isUnavailable: Boolean = false,
    ) {
        companion object {
            val UNAVAILABLE = ResolvedLocation(0.0, 0.0, 0.0, true)
        }
    }

    companion object {
        private const val DATE_TAKEN_COLUMN = "datetaken"
        private const val TIME_SCOPE_CACHE_TTL_MS = 30_000L
        private const val TIME_SCOPE_CACHE_MAX_ENTRIES = 8
        private const val LOCATION_SCOPE_CACHE_TTL_MS = 5 * 60_000L
        private const val LOCATION_SCOPE_CACHE_MAX_ENTRIES = 8
        private val COORDINATE_PATTERN = Regex(
            "(?i)(?:gps\\s*)?([+-]?\\d+(?:\\.\\d+)?)\\s*[,/ ]\\s*([+-]?\\d+(?:\\.\\d+)?)",
        )
        private val ISO_6709_COORDINATE_PATTERN = Regex(
            "(?i)^\\s*(?:gps\\s*)?([+-]\\d{1,2}(?:\\.\\d+)?)([+-]\\d{1,3}(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?/?\\s*$",
        )
        private val EXIF_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val VIDEO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** Accepts both app-formatted `GPS lat, lon` and ISO-6709 `+lat+lon/`. */
        internal fun parseCoordinates(value: String): Pair<Double, Double>? {
            val match = COORDINATE_PATTERN.find(value)
                ?: ISO_6709_COORDINATE_PATTERN.find(value)
                ?: return null
            val latitude = match.groupValues[1].toDoubleOrNull()
            val longitude = match.groupValues[2].toDoubleOrNull()
            return if (
                latitude != null &&
                longitude != null &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0 &&
                // A small number of cameras/exporters write 0°,0° as a
                // missing-location sentinel. Treat "Null Island" as absent
                // GPS so it is not geocoded or retried forever.
                !(kotlin.math.abs(latitude) < NULL_ISLAND_EPSILON &&
                    kotlin.math.abs(longitude) < NULL_ISLAND_EPSILON)
            ) {
                latitude to longitude
            } else {
                null
            }
        }

        private const val NULL_ISLAND_EPSILON = 1.0e-5
    }
}
