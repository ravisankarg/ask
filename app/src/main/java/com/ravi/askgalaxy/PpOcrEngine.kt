package com.ravi.askgalaxy

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class OcrTextResult(
    val text: String,
    val lineCount: Int,
)

/** CPU-decoded PP-OCRv5 pipeline backed by the pinned LiteRT detector/recognizer. */
class PpOcrEngine private constructor(
    private val detector: LiteRtModel,
    private val recognizer: LiteRtModel,
    private val dictionary: List<String>,
) : Closeable {
    private val detectorInput = ByteBuffer
        .allocateDirect(detector.inputTensor().numBytes())
        .order(ByteOrder.nativeOrder())
    private val detectorOutput = ByteBuffer
        .allocateDirect(detector.outputTensor().numBytes())
        .order(ByteOrder.nativeOrder())
    private val recognizerInput = ByteBuffer
        .allocateDirect(recognizer.inputTensor().numBytes())
        .order(ByteOrder.nativeOrder())
    private val recognizerOutput = ByteBuffer
        .allocateDirect(recognizer.outputTensor().numBytes())
        .order(ByteOrder.nativeOrder())

    init {
        require(detector.inputCount == 1 && detector.outputCount >= 1) {
            "PP-OCR detector must have one input and one output"
        }
        require(detector.inputTensor().dataType() == DataType.FLOAT32) {
            "PP-OCR detector input must be FLOAT32"
        }
        require(detector.outputTensor().dataType() == DataType.FLOAT32) {
            "PP-OCR detector output must be FLOAT32"
        }
        require(detector.inputTensor().shape().contentEquals(intArrayOf(1, 3, DETECTOR_SIZE, DETECTOR_SIZE))) {
            "Expected PP-OCR detector input [1,3,640,640]"
        }
        require(detector.outputTensor().numElements() == DETECTOR_SIZE * DETECTOR_SIZE) {
            "Expected PP-OCR detector output [1,1,640,640]"
        }
        require(recognizer.inputCount == 1 && recognizer.outputCount >= 1) {
            "PP-OCR recognizer must have one input and one output"
        }
        require(recognizer.inputTensor().dataType() == DataType.FLOAT32) {
            "PP-OCR recognizer input must be FLOAT32"
        }
        require(recognizer.outputTensor().dataType() == DataType.FLOAT32) {
            "PP-OCR recognizer output must be FLOAT32"
        }
        require(recognizer.inputTensor().shape().contentEquals(intArrayOf(1, 3, RECOGNIZER_HEIGHT, RECOGNIZER_WIDTH))) {
            "Expected PP-OCR recognizer input [1,3,48,320]"
        }
        require(dictionary.size == DICTIONARY_LINES) {
            "Expected $DICTIONARY_LINES OCR dictionary entries, got ${dictionary.size}"
        }
        val outputShape = recognizer.outputTensor().shape()
        require(outputShape.size == 3 && outputShape[0] == 1 && outputShape[2] == VOCAB_SIZE) {
            "Expected PP-OCR recognizer output [1,T,$VOCAB_SIZE], got ${outputShape.contentToString()}"
        }
    }

    fun read(bitmap: Bitmap): OcrTextResult {
        if (bitmap.width < 2 || bitmap.height < 2) return OcrTextResult("", 0)
        val detectorBitmap = if (bitmap.width == DETECTOR_SIZE && bitmap.height == DETECTOR_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, DETECTOR_SIZE, DETECTOR_SIZE, true)
        }
        try {
            writeDetectorInput(detectorBitmap)
            detector.run(detectorInput, detectorOutput)
            val regions = detectRegions(detectorOutput)
            val lines = regions.take(MAX_LINES).mapNotNull { recognize(bitmap, it) }
            return OcrTextResult(
                text = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim(),
                lineCount = lines.size,
            )
        } finally {
            if (detectorBitmap !== bitmap) detectorBitmap.recycle()
        }
    }

    override fun close() {
        recognizer.close()
        detector.close()
    }

    private fun writeDetectorInput(bitmap: Bitmap) {
        detectorInput.clear()
        val pixels = IntArray(DETECTOR_SIZE * DETECTOR_SIZE)
        bitmap.getPixels(pixels, 0, DETECTOR_SIZE, 0, 0, DETECTOR_SIZE, DETECTOR_SIZE)
        for (channel in 0..2) {
            for (pixel in pixels) {
                val component = when (channel) {
                    0 -> pixel shr 16 and 0xff
                    1 -> pixel shr 8 and 0xff
                    else -> pixel and 0xff
                }
                val normalized = (component / 255f - MEAN[channel]) / STD[channel]
                detectorInput.putFloat(normalized)
            }
        }
    }

    private fun detectRegions(output: ByteBuffer): List<TextRegion> {
        val active = BooleanArray(DETECTOR_SIZE * DETECTOR_SIZE)
        for (index in active.indices) {
            active[index] = floatAt(output, index) >= DETECTION_THRESHOLD
        }
        val queue = IntArray(active.size)
        val regions = ArrayList<TextRegion>()
        for (origin in active.indices) {
            if (!active[origin]) continue
            active[origin] = false
            var head = 0
            var tail = 0
            queue[tail++] = origin
            var minX = origin % DETECTOR_SIZE
            var maxX = minX
            var minY = origin / DETECTOR_SIZE
            var maxY = minY
            var count = 0
            var probabilitySum = 0.0
            fun enqueue(index: Int) {
                if (!active[index]) return
                active[index] = false
                queue[tail++] = index
            }
            while (head < tail) {
                val index = queue[head++]
                val x = index % DETECTOR_SIZE
                val y = index / DETECTOR_SIZE
                count += 1
                probabilitySum += floatAt(output, index).toDouble()
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                if (x > 0) enqueue(index - 1)
                if (x + 1 < DETECTOR_SIZE) enqueue(index + 1)
                if (y > 0) enqueue(index - DETECTOR_SIZE)
                if (y + 1 < DETECTOR_SIZE) enqueue(index + DETECTOR_SIZE)
            }
            val width = maxX - minX + 1
            val height = maxY - minY + 1
            val mean = probabilitySum / count.coerceAtLeast(1)
            if (width < MIN_REGION_SIZE || height < MIN_REGION_SIZE || mean < MEAN_THRESHOLD) continue
            val padding = (0.35f * min(width, height)).roundToInt().coerceIn(2, MAX_PADDING)
            regions += TextRegion(
                left = (minX - padding).coerceAtLeast(0),
                top = (minY - padding).coerceAtLeast(0),
                right = (maxX + padding + 1).coerceAtMost(DETECTOR_SIZE),
                bottom = (maxY + padding + 1).coerceAtMost(DETECTOR_SIZE),
            )
        }
        return regions.sortedWith(compareBy<TextRegion> { it.top }.thenBy { it.left })
    }

    private fun recognize(source: Bitmap, region: TextRegion): String? {
        val left = (region.left * source.width / DETECTOR_SIZE).coerceIn(0, source.width - 1)
        val top = (region.top * source.height / DETECTOR_SIZE).coerceIn(0, source.height - 1)
        val right = (region.right * source.width / DETECTOR_SIZE).coerceIn(left + 1, source.width)
        val bottom = (region.bottom * source.height / DETECTOR_SIZE).coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        if (width < 2 || height < 2) return null
        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val resizedWidth = (RECOGNIZER_HEIGHT * width.toFloat() / height)
            .roundToInt()
            .coerceIn(1, RECOGNIZER_WIDTH)
        val resized = Bitmap.createScaledBitmap(crop, resizedWidth, RECOGNIZER_HEIGHT, true)
        try {
            writeRecognizerInput(resized, resizedWidth)
            recognizer.run(recognizerInput, recognizerOutput)
            return decodeCtc(recognizerOutput)
        } finally {
            if (resized !== crop) resized.recycle()
            crop.recycle()
        }
    }

    private fun writeRecognizerInput(bitmap: Bitmap, contentWidth: Int) {
        recognizerInput.clear()
        val pixels = IntArray(contentWidth * RECOGNIZER_HEIGHT)
        bitmap.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, RECOGNIZER_HEIGHT)
        for (channel in 0..2) {
            for (y in 0 until RECOGNIZER_HEIGHT) {
                for (x in 0 until RECOGNIZER_WIDTH) {
                    val value = if (x < contentWidth) {
                        val pixel = pixels[y * contentWidth + x]
                        val component = when (channel) {
                            0 -> pixel shr 16 and 0xff
                            1 -> pixel shr 8 and 0xff
                            else -> pixel and 0xff
                        }
                        component / 127.5f - 1f
                    } else {
                        -1f
                    }
                    recognizerInput.putFloat(value)
                }
            }
        }
    }

    private fun decodeCtc(output: ByteBuffer): String {
        val timeSteps = recognizer.outputTensor().numElements() / VOCAB_SIZE
        val result = StringBuilder()
        var previous = 0
        for (time in 0 until timeSteps) {
            var bestId = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (id in 0 until VOCAB_SIZE) {
                val value = floatAt(output, time * VOCAB_SIZE + id)
                if (value > bestValue) {
                    bestValue = value
                    bestId = id
                }
            }
            if (bestId != 0 && bestId != previous) {
                result.append(if (bestId <= dictionary.size) dictionary[bestId - 1] else " ")
            }
            previous = bestId
        }
        return result.toString().trim()
    }

    private fun floatAt(buffer: ByteBuffer, index: Int): Float = buffer.getFloat(index * Float.SIZE_BYTES)

    private data class TextRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    companion object {
        private const val DETECTOR_SIZE = 640
        private const val RECOGNIZER_HEIGHT = 48
        private const val RECOGNIZER_WIDTH = 320
        private const val DICTIONARY_LINES = 18_383
        private const val VOCAB_SIZE = DICTIONARY_LINES + 2
        private const val DETECTION_THRESHOLD = 0.30f
        private const val MEAN_THRESHOLD = 0.50
        private const val MIN_REGION_SIZE = 6
        private const val MAX_PADDING = 24
        private const val MAX_LINES = 32
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        fun open(context: Context): PpOcrEngine {
            check(ModelCatalog.ocrDetector.isInstalled(context)) {
                "Install ${ModelCatalog.ocrDetector.relativePath} before OCR"
            }
            check(ModelCatalog.ocrRecognizer.isInstalled(context)) {
                "Install ${ModelCatalog.ocrRecognizer.relativePath} before OCR"
            }
            val dictionary = ModelCatalog.ocrDictionary.file(context).bufferedReader().use { reader ->
                reader.readLines()
            }
            return PpOcrEngine(
                detector = LiteRtModel.open(ModelCatalog.ocrDetector.file(context), threads = 2),
                recognizer = LiteRtModel.open(ModelCatalog.ocrRecognizer.file(context), threads = 2),
                dictionary = dictionary,
            )
        }
    }
}
