package com.ravi.askgalaxy

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Offline Latin-script OCR backed by the model bundled in the application.
 *
 * Each OcrIndexer worker owns one recognizer. Calls are blocking here because
 * indexing already runs off the main thread and WorkManager must persist each
 * completed row before advancing.
 */
class MlKitOcrEngine private constructor(
    private val recognizer: TextRecognizer,
) : Closeable {
    fun readLines(bitmap: Bitmap): List<RecognizedOcrLine> {
        if (bitmap.width < 2 || bitmap.height < 2) return emptyList()
        val result = Tasks.await(
            recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            INFERENCE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.text
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let {
                        val bounds = line.boundingBox
                        RecognizedOcrLine(
                            text = it,
                            confidence = line.confidence,
                            left = bounds?.left ?: -1,
                            top = bounds?.top ?: -1,
                            right = bounds?.right ?: -1,
                            bottom = bounds?.bottom ?: -1,
                        )
                    }
            }
        }
    }

    override fun close() {
        recognizer.close()
    }

    companion object {
        private const val INFERENCE_TIMEOUT_SECONDS = 60L

        fun open(): MlKitOcrEngine = MlKitOcrEngine(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        )
    }
}
