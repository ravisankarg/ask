package com.ravi.askgalaxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import kotlin.math.roundToInt

/** Creates one bounded visual input for every private record used by E2B. */
internal class DocumentPageRenderer(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun render(match: DocumentMatch, maxDimension: Int): Bitmap? {
        val chunk = match.chunk
        if (chunk.source != DocumentSource.FILES) return null
        val sourcePreview = when {
            chunk.title.endsWith(".pdf", ignoreCase = true) -> renderPdfPage(chunk, maxDimension)
            else -> loadProviderPreview(chunk, maxDimension)
        }
        // Never turn flattened extraction back into a synthetic page. If the
        // provider cannot render a non-PDF file, leave it unavailable rather
        // than leaking extracted file text into the visual answer path.
        return sourcePreview
    }

    private fun renderPdfPage(chunk: DocumentChunk, maxDimension: Int): Bitmap? = runCatching {
        val uri = Uri.parse(requireNotNull(chunk.uri))
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return@runCatching null
        descriptor.use {
            PdfRenderer(it).use { renderer ->
                val pageIndex = ((chunk.page ?: 1) - 1).coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(
                        maxDimension.toFloat() / page.width.coerceAtLeast(1),
                        maxDimension.toFloat() / page.height.coerceAtLeast(1),
                    )
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    private fun loadProviderPreview(chunk: DocumentChunk, maxDimension: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching null
        val uri = Uri.parse(requireNotNull(chunk.uri))
        resolver.loadThumbnail(
            uri,
            Size(maxDimension, maxDimension),
            CancellationSignal(),
        )
    }.getOrNull()

}
