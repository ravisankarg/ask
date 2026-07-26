package com.ravi.askgalaxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Owns Android's explicit consent boundary for unredacted photo EXIF.
 *
 * READ_MEDIA_IMAGES is not enough on Android 10+: GPS remains redacted unless
 * ACCESS_MEDIA_LOCATION is granted and the photo is opened through
 * MediaStore.setRequireOriginal().
 */
object MediaLocationAccess {
    private const val PREFERENCES = "media_location_access"
    private const val KEY_REQUESTED = "permission_requested"

    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasPermission(context: Context): Boolean =
        !isRequired() ||
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun shouldRequest(context: Context): Boolean =
        isRequired() &&
            !hasPermission(context) &&
            !context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    /**
     * Returns an unredacted MediaStore URI, or null when explicit consent is
     * unavailable. Callers must never interpret null as proof that a photo has
     * no GPS.
     */
    fun originalUri(context: Context, uri: Uri): Uri? = when {
        !isRequired() -> uri
        !hasPermission(context) -> null
        else -> MediaStore.setRequireOriginal(uri)
    }
}
