package com.ravi.askgalaxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Full-screen, back-navigable detail view for a search result. */
class MediaDetailActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val media = mediaFromIntent(intent) ?: run {
            finish()
            return
        }
        setContentView(createContent(media))
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(media: GalleryMedia): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            contentDescription = "Back to search results"
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.rgb(40, 43, 52))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = if (media.mimeType.startsWith("video/", ignoreCase = true)) {
                "Video details"
            } else {
                "Photo details"
            }
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(25, 28, 36))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, matchWrap())

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(235, 237, 243))
            contentDescription = "Expanded gallery result"
        }
        root.addView(
            image,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)).apply {
                topMargin = dp(14)
            },
        )
        executor.execute {
            val bitmap = MediaBitmapLoader(this).use {
                it.load(media, maxDimension = 1280, applyExifOrientation = true)
            }
            runOnUiThread {
                if (!isFinishing && bitmap != null) image.setImageBitmap(bitmap)
            }
        }

        val detailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(Color.rgb(247, 248, 252), dp(18).toFloat())
        }
        val timestamp = media.dateTakenMs
            ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1000L)
        detailCard.addDetail("Captured", timestamp?.let(::formatDate) ?: "Unknown")
        detailCard.addDetail(
            "Location",
            media.locationName
                ?: media.location
                ?: "No location stored",
        )
        if (!media.locationName.isNullOrBlank() && !media.location.isNullOrBlank()) {
            detailCard.addDetail("GPS", media.location)
        }
        media.personLabel?.takeIf(String::isNotBlank)?.let {
            detailCard.addDetail("People", it)
        }
        detailCard.addDetail(
            "Media",
            buildString {
                append(if (media.mimeType.startsWith("video/", true)) "Video" else "Photo")
                if (media.width > 0 && media.height > 0) append(" • ${media.width} × ${media.height}")
                if (media.durationMs > 0L) append(" • ${formatDuration(media.durationMs)}")
            },
        )
        media.ocrText.takeIf(String::isNotBlank)?.let {
            detailCard.addDetail("Text found", it.replace(Regex("\\s+"), " ").take(MAX_OCR_CHARS))
        }
        root.addView(
            detailCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            },
        )
        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun LinearLayout.addDetail(label: String, value: String) {
        addView(TextView(this@MediaDetailActivity).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(91, 95, 110))
            setPadding(0, dp(8), 0, dp(2))
        }, matchWrap())
        addView(TextView(this@MediaDetailActivity).apply {
            text = value
            textSize = 15f
            setTextColor(Color.rgb(42, 44, 52))
            setTextIsSelectable(true)
        }, matchWrap())
    }

    private fun formatDate(timestampMs: Long): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000L
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        private const val MAX_OCR_CHARS = 2_000
        private const val EXTRA_ID = "media_store_id"
        private const val EXTRA_URI = "content_uri"
        private const val EXTRA_MIME = "mime_type"
        private const val EXTRA_MODIFIED = "date_modified"
        private const val EXTRA_SIZE = "size_bytes"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PERSON = "person"
        private const val EXTRA_OCR = "ocr"
        private const val EXTRA_CONTENT_CLASS = "media_content_class"
        private const val EXTRA_CAPTURE = "capture"
        private const val EXTRA_LOCATION = "location"
        private const val EXTRA_LOCATION_NAME = "location_name"

        fun intent(context: Context, media: GalleryMedia): Intent =
            Intent(context, MediaDetailActivity::class.java).apply {
                putExtra(EXTRA_ID, media.mediaStoreId)
                putExtra(EXTRA_URI, media.contentUri)
                putExtra(EXTRA_MIME, media.mimeType)
                putExtra(EXTRA_MODIFIED, media.dateModifiedSeconds)
                putExtra(EXTRA_SIZE, media.sizeBytes)
                putExtra(EXTRA_WIDTH, media.width)
                putExtra(EXTRA_HEIGHT, media.height)
                putExtra(EXTRA_DURATION, media.durationMs)
                putExtra(EXTRA_PERSON, media.personLabel)
                putExtra(EXTRA_OCR, media.ocrText)
                putExtra(EXTRA_CONTENT_CLASS, media.contentClass.wireName)
                putExtra(EXTRA_CAPTURE, media.dateTakenMs ?: -1L)
                putExtra(EXTRA_LOCATION, media.location)
                putExtra(EXTRA_LOCATION_NAME, media.locationName)
            }

        private fun mediaFromIntent(intent: Intent): GalleryMedia? {
            val uri = intent.getStringExtra(EXTRA_URI)?.takeIf(String::isNotBlank) ?: return null
            return GalleryMedia(
                mediaStoreId = intent.getLongExtra(EXTRA_ID, -1L),
                contentUri = uri,
                mimeType = intent.getStringExtra(EXTRA_MIME).orEmpty(),
                displayName = "",
                dateModifiedSeconds = intent.getLongExtra(EXTRA_MODIFIED, 0L),
                sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
                width = intent.getIntExtra(EXTRA_WIDTH, 0),
                height = intent.getIntExtra(EXTRA_HEIGHT, 0),
                durationMs = intent.getLongExtra(EXTRA_DURATION, 0L),
                personLabel = intent.getStringExtra(EXTRA_PERSON),
                ocrText = intent.getStringExtra(EXTRA_OCR).orEmpty(),
                contentClass = MediaContentClass.fromWireName(
                    intent.getStringExtra(EXTRA_CONTENT_CLASS),
                ),
                dateTakenMs = intent.getLongExtra(EXTRA_CAPTURE, -1L).takeIf { it > 0L },
                location = intent.getStringExtra(EXTRA_LOCATION),
                locationName = intent.getStringExtra(EXTRA_LOCATION_NAME),
            )
        }
    }
}
