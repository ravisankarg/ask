package com.ravi.askgalaxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * Supplies OCR with aspect-preserving pixels.
 *
 * Ordinary images are decoded at a useful document resolution. Very tall or
 * wide images are region-decoded as overlapping strips, so a long screenshot
 * does not become an unreadable thumbnail or a huge in-memory bitmap.
 */
class OcrMediaReader(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val bitmapLoader = MediaBitmapLoader(appContext)

    fun read(media: GalleryMedia, engine: MlKitOcrEngine): OcrTextResult {
        if (!media.mimeType.startsWith("image/")) return OcrTextResult("", 0)
        val uri = Uri.parse(media.contentUri)
        val bounds = readBounds(uri)
            ?: throw OcrMediaReadException("Could not read image bounds for ${media.mediaStoreId}")
        val orientation = bitmapLoader.readOrientation(media)
        val rawLines = if (isLongImage(bounds.width, bounds.height)) {
            readTiled(uri, bounds, orientation, engine)
                ?: readOrdinary(media, engine)
        } else {
            readOrdinary(media, engine)
        }
        return OcrQualityGate.select(rawLines)
    }

    override fun close() {
        bitmapLoader.close()
    }

    private fun readOrdinary(
        media: GalleryMedia,
        engine: MlKitOcrEngine,
    ): List<RecognizedOcrLine> {
        val bitmap = bitmapLoader.load(
            media,
            maxDimension = STANDARD_MAX_DIMENSION,
            applyExifOrientation = true,
        ) ?: throw OcrMediaReadException("Could not decode image ${media.mediaStoreId}")
        return try {
            engine.readLines(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun readTiled(
        uri: Uri,
        bounds: ImageBounds,
        orientation: Int,
        engine: MlKitOcrEngine,
    ): List<RecognizedOcrLine>? {
        return resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val decoder = BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                ?: return@use null
            try {
                val sample = tileSample(bounds)
                val rawTileLength = TILE_LONG_DIMENSION * sample
                val rawOverlap = TILE_OVERLAP * sample
                val rawStep = rawTileLength - rawOverlap
                val vertical = bounds.height >= bounds.width
                val longDimension = if (vertical) bounds.height else bounds.width
                val allLines = ArrayList<RecognizedOcrLine>()
                var priorOverlap = emptyMap<String, Int>()
                var start = 0
                while (start < longDimension) {
                    val end = min(start + rawTileLength, longDimension)
                    val region = if (vertical) {
                        Rect(0, start, bounds.width, end)
                    } else {
                        Rect(start, 0, end, bounds.height)
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = decoder.decodeRegion(region, options)
                        ?: throw IllegalStateException("Could not decode OCR tile")
                    val bitmap = bitmapLoader.orientForExif(decoded, orientation)
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height
                    val longAxisIsVertical = vertical.xor(orientationSwapsAxes(orientation))
                    val tileLines = try {
                        engine.readLines(bitmap)
                    } finally {
                        bitmap.recycle()
                    }

                    val overlapPixels = max(1, rawOverlap / sample)
                    val overlapBudget = priorOverlap.toMutableMap()
                    tileLines.forEach { line ->
                        val isAtLeadingEdge = if (longAxisIsVertical) {
                            line.top in 0..overlapPixels
                        } else {
                            line.left in 0..overlapPixels
                        }
                        val key = line.text.normalizedOcrKey()
                        val duplicateCount = overlapBudget[key] ?: 0
                        if (isAtLeadingEdge && duplicateCount > 0) {
                            overlapBudget[key] = duplicateCount - 1
                        } else {
                            allLines += line
                        }
                    }
                    priorOverlap = tileLines
                        .filter { line ->
                            if (longAxisIsVertical) {
                                line.bottom >= bitmapHeight - overlapPixels
                            } else {
                                line.right >= bitmapWidth - overlapPixels
                            }
                        }
                        .groupingBy { it.text.normalizedOcrKey() }
                        .eachCount()

                    if (end >= longDimension) break
                    start += rawStep
                }
                allLines
            } finally {
                decoder.recycle()
            }
        }
    }

    private fun readBounds(uri: Uri): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            ImageBounds(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    private fun isLongImage(width: Int, height: Int): Boolean {
        val short = min(width, height).coerceAtLeast(1)
        val long = max(width, height)
        return long.toFloat() / short >= LONG_IMAGE_ASPECT_RATIO &&
            long > TILE_LONG_DIMENSION
    }

    private fun tileSample(bounds: ImageBounds): Int {
        val shortDimension = min(bounds.width, bounds.height)
        var sample = 1
        while (shortDimension / sample > TILE_CROSS_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun orientationSwapsAxes(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270

    private fun String.normalizedOcrKey(): String =
        trim().replace(Regex("\\s+"), " ").lowercase()

    private data class ImageBounds(val width: Int, val height: Int)

    private class OcrMediaReadException(message: String) : IllegalStateException(message)

    private companion object {
        // 2048 intentionally keeps common 4000px phone photos at full decode:
        // the device corpus proved that a real four-letter equipment label
        // disappeared at the next power-of-two downsample. ML Kit's documented
        // character-pixel requirement makes this a quality-critical boundary.
        const val STANDARD_MAX_DIMENSION = 2_048
        const val TILE_LONG_DIMENSION = 2_048
        const val TILE_CROSS_DIMENSION = 2_048
        const val TILE_OVERLAP = 256
        const val LONG_IMAGE_ASPECT_RATIO = 2.4f
    }
}
