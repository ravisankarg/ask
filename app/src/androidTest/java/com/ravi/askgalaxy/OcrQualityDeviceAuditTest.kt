package com.ravi.askgalaxy

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression corpus from the device audit that motivated the OCR migration.
 * It logs lengths and confidence only; recognized personal text is never
 * copied into test output.
 */
class OcrQualityDeviceAuditTest {
    @Test
    fun representativeCorpusMetrics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val rows = database.findByMediaStoreIds(REPRESENTATIVE_IDS)
                .associateBy(GalleryMedia::mediaStoreId)
            MlKitOcrEngine.open().use { engine ->
                OcrMediaReader(context).use { reader ->
                    REPRESENTATIVE_IDS.forEach { id ->
                        val media = requireNotNull(rows[id]) { "Missing audited media id $id" }
                        val result = reader.read(media, engine)
                        Log.i(
                            TAG,
                            "OCR_CORPUS|id=$id|oldChars=${media.ocrText.length}|" +
                                "newChars=${result.text.length}|" +
                                "lines=${result.lineCount}|" +
                                "candidates=${result.candidateLineCount}|" +
                                "confidence=${"%.3f".format(result.meanConfidence)}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun naturalScenesDoNotBecomeDocuments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val rows = database.findByMediaStoreIds(TRUE_NO_TEXT_IDS)
                .associateBy(GalleryMedia::mediaStoreId)
            MlKitOcrEngine.open().use { engine ->
                OcrMediaReader(context).use { reader ->
                    TRUE_NO_TEXT_IDS.forEach { id ->
                        val media = requireNotNull(rows[id]) { "Missing audited media id $id" }
                        val result = reader.read(media, engine)
                        Log.i(
                            TAG,
                            "OCR_AUDIT|id=$id|kind=natural_scene|" +
                                "oldChars=${media.ocrText.length}|" +
                                "newChars=${result.text.length}|" +
                                "lines=${result.lineCount}|" +
                                "candidates=${result.candidateLineCount}|" +
                                "confidence=${"%.3f".format(result.meanConfidence)}",
                        )
                        assertEquals(
                            "Natural scene $id retained a false OCR document label",
                            "",
                            result.text,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun realSmallEquipmentLabelIsRecovered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val media = database.findByMediaStoreIds(longArrayOf(SMALL_LABEL_ID)).single()
            MlKitOcrEngine.open().use { engine ->
                OcrMediaReader(context).use { reader ->
                    val result = reader.read(media, engine)
                    Log.i(
                        TAG,
                        "OCR_AUDIT|id=$SMALL_LABEL_ID|kind=small_real_label|" +
                            "oldChars=${media.ocrText.length}|" +
                            "newChars=${result.text.length}|" +
                            "lines=${result.lineCount}|" +
                            "candidates=${result.candidateLineCount}|" +
                            "confidence=${"%.3f".format(result.meanConfidence)}",
                    )
                    assertTrue(
                        "Visible equipment label was not recovered",
                        result.text.contains("west", ignoreCase = true),
                    )
                }
            }
        }
    }

    @Test
    fun longScreenshotRetainsReadableText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val media = database.findByMediaStoreIds(longArrayOf(LONG_SCREENSHOT_ID)).single()
            MlKitOcrEngine.open().use { engine ->
                OcrMediaReader(context).use { reader ->
                    val result = reader.read(media, engine)
                    Log.i(
                        TAG,
                        "OCR_AUDIT|id=$LONG_SCREENSHOT_ID|kind=long_screenshot|" +
                            "oldChars=${media.ocrText.length}|" +
                            "newChars=${result.text.length}|" +
                            "lines=${result.lineCount}|" +
                            "candidates=${result.candidateLineCount}|" +
                            "confidence=${"%.3f".format(result.meanConfidence)}",
                    )
                    assertTrue(
                        "Long screenshot still lost its readable document text",
                        result.text.length >= MIN_EXPECTED_DOCUMENT_CHARACTERS,
                    )
                    assertTrue(
                        "Long screenshot produced too little document structure",
                        result.lineCount >= MIN_EXPECTED_DOCUMENT_LINES,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "AskGalaxyOcrAudit"
        val TRUE_NO_TEXT_IDS = longArrayOf(38_121L, 29_197L)
        const val SMALL_LABEL_ID = 32_398L
        const val LONG_SCREENSHOT_ID = 57_129L
        const val MIN_EXPECTED_DOCUMENT_CHARACTERS = 100
        const val MIN_EXPECTED_DOCUMENT_LINES = 5
        val REPRESENTATIVE_IDS = longArrayOf(
            1_212L,
            7_878L,
            10_605L,
            10_808L,
            13_231L,
            13_433L,
            15_151L,
            16_393L,
            16_587L,
            16_969L,
            17_266L,
            17_557L,
            17_654L,
            29_197L,
            32_398L,
            38_121L,
            57_129L,
        )
    }
}
