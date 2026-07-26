package com.ravi.askgalaxy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Conservative fallback for devices whose Android Geocoder service is absent
 * or returns no result.
 *
 * The public Nominatim service is called synchronously from the single
 * WorkManager indexing thread, never from query execution. Requests are
 * globally limited to one start per 1.1 seconds and the caller persists every
 * successful locality-sized result. Coordinates are rounded to three decimal
 * places before transmission so the service receives only the precision
 * needed for city/neighbourhood metadata.
 */
class OnlineReverseGeocoder(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun resolve(latitude: Double, longitude: Double): String? {
        if (!LocationEnrichmentPreferences.isOpenStreetMapFallbackEnabled(appContext)) {
            return null
        }
        val roundedLatitude = roundToLocality(latitude)
        val roundedLongitude = roundToLocality(longitude)
        return REQUEST_LOCK.runSerialized {
            request(roundedLatitude, roundedLongitude, DETAIL_ZOOM, ADDRESS_LAYER)
        } ?: REQUEST_LOCK.runSerialized {
            // Some valid camera coordinates sit in parks, beaches, water, or
            // roadless areas and have no zoom-14 address object. A second,
            // coarser reverse lookup produces a city/region/country name
            // without transmitting any additional coordinate precision.
            request(roundedLatitude, roundedLongitude, REGION_ZOOM, null)
        }
    }

    private fun request(
        latitude: Double,
        longitude: Double,
        zoom: Int,
        layer: String?,
    ): String? {
        val language = Locale.getDefault().toLanguageTag()
            .takeIf(String::isNotBlank)
            ?: "en"
        val query = buildString {
            append("format=jsonv2")
            append("&lat=").append(latitude)
            append("&lon=").append(longitude)
            append("&zoom=").append(zoom)
            append("&addressdetails=1")
            if (layer != null) append("&layer=").append(layer)
            append("&accept-language=")
            append(URLEncoder.encode(language, StandardCharsets.UTF_8.name()))
        }
        val connection = runCatching {
            java.net.URL("$ENDPOINT?$query").openConnection() as HttpsURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", language)
            connection.setRequestProperty(
                "User-Agent",
                "AskGalaxy/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})",
            )
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "OpenStreetMap reverse lookup returned HTTP ${connection.responseCode}")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { reader ->
                reader.readText().take(MAX_RESPONSE_CHARACTERS)
            }
            readableAddress(JSONObject(body))
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readableAddress(result: JSONObject): String? {
        val address = result.optJSONObject("address")
        if (address == null) return coarseDisplayName(result)
        val locality = firstNonBlank(
            address,
            "neighbourhood",
            "suburb",
            "city_district",
            "city",
            "town",
            "village",
            "municipality",
        )
        val city = firstNonBlank(address, "city", "town", "village", "municipality")
        val region = firstNonBlank(address, "state", "region", "county")
        val country = address.optString("country").trim()
        return listOf(locality, city, region, country)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { value -> value.lowercase(Locale.ROOT) }
            .joinToString(", ")
            .takeIf(String::isNotBlank)
            ?: coarseDisplayName(result)
    }

    /** Keeps only regional trailing components, never a house/street address. */
    private fun coarseDisplayName(result: JSONObject): String? =
        result.optString("display_name")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .takeLast(MAX_DISPLAY_NAME_PARTS)
            .distinctBy { value -> value.lowercase(Locale.ROOT) }
            .joinToString(", ")
            .takeIf(String::isNotBlank)

    private fun firstNonBlank(source: JSONObject, vararg keys: String): String? =
        keys.asSequence()
            .map { key -> source.optString(key).trim() }
            .firstOrNull(String::isNotBlank)

    private fun roundToLocality(value: Double): Double =
        String.format(Locale.US, "%.3f", value).toDouble()

    private class SerializedRequestGate {
        private var lastRequestStartedAtMs = 0L

        fun <T> runSerialized(block: () -> T): T = synchronized(this) {
            val nowMs = SystemClock.elapsedRealtime()
            val waitMs = (
                MIN_REQUEST_INTERVAL_MS -
                    (nowMs - lastRequestStartedAtMs)
                ).coerceAtLeast(0L)
            if (waitMs > 0L) SystemClock.sleep(waitMs)
            lastRequestStartedAtMs = SystemClock.elapsedRealtime()
            block()
        }
    }

    private companion object {
        const val ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
        const val TAG = "AskGalaxyGeocoder"
        const val DETAIL_ZOOM = 14
        const val REGION_ZOOM = 8
        const val ADDRESS_LAYER = "address"
        const val MAX_DISPLAY_NAME_PARTS = 3
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_CHARACTERS = 64_000
        const val MIN_REQUEST_INTERVAL_MS = 1_100L
        val REQUEST_LOCK = SerializedRequestGate()
    }
}
