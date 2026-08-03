package com.ravi.askgalaxy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable

class LfmKvExtractor(context: Context) : Closeable {
    private val appContext = context.applicationContext

    init {
        check(
            LfmKvNative.open(
                ModelCatalog.lfmKvModel.file(appContext).absolutePath,
                ModelCatalog.lfmKvVisionProjector.file(appContext).absolutePath,
            ),
        ) { "Could not load the local LFM KV model" }
    }

    fun extract(media: GalleryMedia): String? {
        // Gallery items commonly use HEIC. llama.cpp's bundled stb decoder
        // cannot read it, while Android can. Re-encode every input to a
        // bounded JPEG so native extraction sees one reliable format.
        val bytes = decodedJpeg(Uri.parse(media.contentUri)) ?: run {
            Log.w(TAG, "Unable to prepare image ${media.mediaStoreId} for LFM KV extraction")
            return null
        }
        val raw = LfmKvNative.extract(bytes, INSTRUCTION) ?: return null
        return normalize(raw) ?: run {
            Log.w(TAG, "LFM returned no valid KV JSON for image ${media.mediaStoreId}")
            null
        }
    }

    private fun decodedJpeg(uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = appContext.contentResolver.openInputStream(uri) ?: return null
        boundsInput.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (largestDimension / sampleSize > MAX_INPUT_DIMENSION) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                output.toByteArray().takeIf { it.size <= MAX_IMAGE_BYTES }
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    override fun close() = LfmKvNative.close()

    private fun normalize(raw: String): String? = strictNormalize(raw) ?: partialJsonNormalize(raw)

    private fun strictNormalize(raw: String): String? = runCatching {
        val objectText = raw.substringAfter('{').substringBeforeLast('}').let { "{$it}" }
        val root = JSONObject(objectText)
        val title = root.optString("title")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(TITLE_MAX_LENGTH)
        val facts = when {
            root.has("facts") -> root.optJSONArray("facts") ?: JSONArray()
            else -> JSONArray().also { array ->
                root.keys().forEach { key -> array.put(JSONObject().put("key", key).put("value", root.opt(key))) }
            }
        }
        buildList {
            if (title.isNotBlank()) add("Document title: $title")
            for (index in 0 until facts.length()) {
                val fact = facts.optJSONObject(index) ?: continue
                val key = fact.optString("key").trim().replace(Regex("\\s+"), " ")
                val value = fact.opt("value")?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
                if (key.isNotBlank() && value.isNotBlank() && key.length <= 96 && value.length <= 384) {
                    add("$key: $value")
                }
            }
        }.distinct().take(MAX_FACTS).joinToString("\n").takeIf(String::isNotBlank)
    }.getOrNull()

    /**
     * CPU generation is deliberately bounded. LFM can therefore finish a
     * useful JSON prefix before its final closing brace. Recover only complete
     * quoted title/key/value fields; never index arbitrary model prose.
     */
    private fun partialJsonNormalize(raw: String): String? = buildList {
        TITLE_FIELD.find(raw)?.groupValues?.getOrNull(1)?.let { value ->
            value.trim().replace(Regex("\\s+"), " ").take(TITLE_MAX_LENGTH)
                .takeIf(String::isNotBlank)
                ?.let { add("Document title: $it") }
        }
        KEY_VALUE_FIELDS.findAll(raw).take(MAX_FACTS).forEach { match ->
            val key = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val value = match.groupValues[2].trim().replace(Regex("\\s+"), " ")
            if (key.isNotBlank() && value.isNotBlank() && key.length <= 96 && value.length <= 384) {
                add("$key: $value")
            }
        }
    }.distinct().joinToString("\n").takeIf(String::isNotBlank)

    private companion object {
        const val TAG = "AskGalaxyLfm"
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        const val MAX_INPUT_DIMENSION = 1_600
        const val JPEG_QUALITY = 90
        const val MAX_FACTS = 10
        const val TITLE_MAX_LENGTH = 160
        val TITLE_FIELD = Regex("""(?i)[\"']title[\"']\s*:\s*[\"']([^\"'\r\n]{1,160})""")
        val KEY_VALUE_FIELDS = Regex(
            """(?is)[\"']key[\"']\s*:\s*[\"']([^\"']{1,96})[\"'][^{}]{0,512}?[\"']value[\"']\s*:\s*[\"']([^\"']{1,384})""",
        )
        const val INSTRUCTION = """
            Extract only explicit document facts from this image. Return one JSON object exactly:
            {"title":"short specific document title","facts":[{"key":"field name","value":"field value"}]}
            Title: 2 to 8 words identifying the document and its issuer or subject when visible.
            Include up to 10 highest-signal names, IDs, dates, totals, amounts, issuer, status, addresses, and document type when visible.
            Do not infer missing facts. Do not include prose, markdown, or OCR transcript.
        """
    }
}
