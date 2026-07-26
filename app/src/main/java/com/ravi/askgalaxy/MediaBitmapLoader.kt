package com.ravi.askgalaxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable

/** Decodes gallery inputs with an optional size bound for memory-sensitive paths. */
class MediaBitmapLoader(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun load(
        media: GalleryMedia,
        maxDimension: Int? = 768,
        applyExifOrientation: Boolean = false,
    ): Bitmap? {
        val uri = Uri.parse(media.contentUri)
        return if (media.mimeType.startsWith("video/")) {
            loadVideoFrame(uri, maxDimension)
        } else {
            loadImage(uri, maxDimension, applyExifOrientation)
        }
    }

    /** Face boxes were indexed in the raw bitmap coordinate system. */
    fun readOrientation(media: GalleryMedia): Int = readOrientation(Uri.parse(media.contentUri))

    /** Applies the same EXIF transform used by ordinary image decoding. */
    fun orientForExif(bitmap: Bitmap, orientation: Int): Bitmap = orient(bitmap, orientation)

    override fun close() = Unit

    private fun loadImage(
        uri: Uri,
        maxDimension: Int?,
        applyExifOrientation: Boolean,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = if (maxDimension == null || maxDimension <= 0) {
            1
        } else {
            generateSequence(1) { it * 2 }
                .takeWhile { it * maxDimension < largest }
                .lastOrNull() ?: 1
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (decoded == null || !applyExifOrientation) return decoded
        return orient(decoded, readOrientation(uri))
    }

    private fun readOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (oriented !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return oriented
    }

    private fun loadVideoFrame(uri: Uri, maxDimension: Int?): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(appContext, uri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
        if (frame == null || maxDimension == null || maxDimension <= 0 ||
            maxOf(frame.width, frame.height) <= maxDimension
        ) return frame
        val scale = maxDimension.toFloat() / maxOf(frame.width, frame.height)
        val scaled = Bitmap.createScaledBitmap(
            frame,
            (frame.width * scale).toInt().coerceAtLeast(1),
            (frame.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== frame) frame.recycle()
        return scaled
    }
}

/** Maps normalized face boxes stored on raw images onto EXIF-oriented images. */
object ImageOrientation {
    fun transformBox(box: FaceBox, orientation: Int): FaceBox = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> FaceBox(
            left = 1f - box.right,
            top = box.top,
            right = 1f - box.left,
            bottom = box.bottom,
        )
        ExifInterface.ORIENTATION_ROTATE_180 -> FaceBox(
            left = 1f - box.right,
            top = 1f - box.bottom,
            right = 1f - box.left,
            bottom = 1f - box.top,
        )
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> FaceBox(
            left = box.left,
            top = 1f - box.bottom,
            right = box.right,
            bottom = 1f - box.top,
        )
        ExifInterface.ORIENTATION_TRANSPOSE -> FaceBox(
            left = box.top,
            top = box.left,
            right = box.bottom,
            bottom = box.right,
        )
        ExifInterface.ORIENTATION_ROTATE_90 -> FaceBox(
            left = 1f - box.bottom,
            top = box.left,
            right = 1f - box.top,
            bottom = box.right,
        )
        ExifInterface.ORIENTATION_TRANSVERSE -> FaceBox(
            left = 1f - box.bottom,
            top = 1f - box.right,
            right = 1f - box.top,
            bottom = 1f - box.left,
        )
        ExifInterface.ORIENTATION_ROTATE_270 -> FaceBox(
            left = box.top,
            top = 1f - box.right,
            right = box.bottom,
            bottom = 1f - box.left,
        )
        else -> box
    }
}
