package com.ravi.askgalaxy

import android.content.Context
import android.os.SystemClock
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
            request(roundedLatitude, roundedLongitude)
        }
    }

    private fun request(latitude: Double, longitude: Double): String? {
        val language = Locale.getDefault().toLanguageTag()
            .takeIf(String::isNotBlank)
            ?: "en"
        val query = buildString {
            append("format=jsonv2")
            append("&lat=").append(latitude)
            append("&lon=").append(longitude)
            append("&zoom=14")
            append("&addressdetails=1")
            append("&layer=address")
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
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
        val address = result.optJSONObject("address") ?: return null
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
    }

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
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_CHARACTERS = 64_000
        const val MIN_REQUEST_INTERVAL_MS = 1_100L
        val REQUEST_LOCK = SerializedRequestGate()
    }
}
