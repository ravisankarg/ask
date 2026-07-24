package com.ravi.askgalaxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.atan2

data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class FaceDetection(
    val box: FaceBox,
    val landmarks: FloatArray,
    val score: Float,
)

/** LiteRT decoder for the pinned 640x640 BGR YuNet model. */
class YuNetFaceDetector private constructor(
    private val model: LiteRtModel,
) : Closeable {
    private val inputTensor = model.inputTensor()
    private val inputBuffer = ByteBuffer
        .allocateDirect(inputTensor.numBytes())
        .order(ByteOrder.nativeOrder())
    private val outputBuffers = (0 until model.outputCount).map { index ->
        ByteBuffer
            .allocateDirect(model.outputTensor(index).numBytes())
            .order(ByteOrder.nativeOrder())
    }

    init {
        require(model.inputCount == 1) { "YuNet must have one input" }
        require(inputTensor.shape().contentEquals(intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE))) {
            "Expected YuNet input [1,3,640,640], got ${inputTensor.shape().contentToString()}"
        }
        require(model.outputCount == OUTPUT_COUNT) {
            "Expected YuNet to expose 12 outputs, got ${model.outputCount}"
        }
        outputBuffers.forEachIndexed { index, _ ->
            require(model.outputTensor(index).dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                "YuNet output $index must be FLOAT32"
            }
        }
    }

    fun detect(bitmap: Bitmap, maxFaces: Int = 32): List<FaceDetection> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val letterbox = letterbox(bitmap)
        try {
            writeBgrInput(letterbox.bitmap)
            model.runMultiple(inputBuffer, outputBuffers)
            val candidates = ArrayList<FaceDetection>()
            STRIDES.forEachIndexed { level, stride ->
                val cells = (INPUT_SIZE / stride) * (INPUT_SIZE / stride)
                val cls = outputBuffers[level]
                val obj = outputBuffers[3 + level]
                val bbox = outputBuffers[6 + level]
                val landmarks = outputBuffers[9 + level]
                for (index in 0 until cells) {
                    val score = floatAt(cls, index) * floatAt(obj, index)
                    if (score < SCORE_THRESHOLD) continue
                    val priorX = (index % (INPUT_SIZE / stride)) * stride
                    val priorY = (index / (INPUT_SIZE / stride)) * stride
                    val centerX = floatAt(bbox, index * 4) * stride + priorX
                    val centerY = floatAt(bbox, index * 4 + 1) * stride + priorY
                    val width = exp(floatAt(bbox, index * 4 + 2).toDouble())
                        .toFloat() * stride
                    val height = exp(floatAt(bbox, index * 4 + 3).toDouble())
                        .toFloat() * stride
                    val modelBox = FaceBox(
                        left = centerX - width / 2f,
                        top = centerY - height / 2f,
                        right = centerX + width / 2f,
                        bottom = centerY + height / 2f,
                    )
                    val modelLandmarks = FloatArray(LANDMARK_VALUES)
                    for (point in 0 until LANDMARK_COUNT) {
                        modelLandmarks[point * 2] = floatAt(landmarks, index * LANDMARK_VALUES + point * 2) * stride + priorX
                        modelLandmarks[point * 2 + 1] = floatAt(landmarks, index * LANDMARK_VALUES + point * 2 + 1) * stride + priorY
                    }
                    candidates += FaceDetection(
                        box = mapToSource(modelBox, letterbox, bitmap.width, bitmap.height),
                        landmarks = mapLandmarksToSource(modelLandmarks, letterbox, bitmap.width, bitmap.height),
                        score = score,
                    )
                }
            }
            return nonMaximumSuppression(candidates)
                .take(maxFaces.coerceIn(1, 64))
        } finally {
            if (letterbox.bitmap !== bitmap) letterbox.bitmap.recycle()
            if (letterbox.scaled !== letterbox.bitmap && letterbox.scaled !== bitmap) {
                letterbox.scaled.recycle()
            }
        }
    }

    override fun close() {
        model.close()
    }

    private fun writeBgrInput(bitmap: Bitmap) {
        inputBuffer.clear()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (channel in 0..2) {
            for (pixel in pixels) {
                val value = when (channel) {
                    0 -> pixel and 0xff
                    1 -> pixel shr 8 and 0xff
                    else -> pixel shr 16 and 0xff
                }
                inputBuffer.putFloat(value.toFloat())
            }
        }
    }

    private fun letterbox(source: Bitmap): Letterbox {
        val scale = min(
            INPUT_SIZE.toFloat() / source.width,
            INPUT_SIZE.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).roundToInt().coerceIn(1, INPUT_SIZE)
        val scaledHeight = (source.height * scale).roundToInt().coerceIn(1, INPUT_SIZE)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val canvasBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                scaled,
                ((INPUT_SIZE - scaledWidth) / 2f),
                ((INPUT_SIZE - scaledHeight) / 2f),
                PAINT,
            )
        }
        return Letterbox(
            bitmap = canvasBitmap,
            scaled = scaled,
            scale = scale,
            padX = (INPUT_SIZE - scaledWidth) / 2f,
            padY = (INPUT_SIZE - scaledHeight) / 2f,
        )
    }

    private fun mapToSource(
        box: FaceBox,
        letterbox: Letterbox,
        sourceWidth: Int,
        sourceHeight: Int,
    ): FaceBox = FaceBox(
        left = ((box.left - letterbox.padX) / letterbox.scale).coerceIn(0f, sourceWidth.toFloat()),
        top = ((box.top - letterbox.padY) / letterbox.scale).coerceIn(0f, sourceHeight.toFloat()),
        right = ((box.right - letterbox.padX) / letterbox.scale).coerceIn(0f, sourceWidth.toFloat()),
        bottom = ((box.bottom - letterbox.padY) / letterbox.scale).coerceIn(0f, sourceHeight.toFloat()),
    )

    private fun mapLandmarksToSource(
        points: FloatArray,
        letterbox: Letterbox,
        sourceWidth: Int,
        sourceHeight: Int,
    ): FloatArray = FloatArray(points.size) { index ->
        val coordinate = if (index % 2 == 0) {
            ((points[index] - letterbox.padX) / letterbox.scale).coerceIn(0f, sourceWidth.toFloat())
        } else {
            ((points[index] - letterbox.padY) / letterbox.scale).coerceIn(0f, sourceHeight.toFloat())
        }
        coordinate
    }

    private fun floatAt(buffer: ByteBuffer, index: Int): Float = buffer.getFloat(index * Float.SIZE_BYTES)

    private fun nonMaximumSuppression(candidates: List<FaceDetection>): List<FaceDetection> {
        val remaining = candidates.sortedByDescending { it.score }.toMutableList()
        val selected = ArrayList<FaceDetection>()
        while (remaining.isNotEmpty() && selected.size < MAX_DETECTIONS) {
            val best = remaining.removeAt(0)
            if (best.box.width >= MIN_FACE_SIZE && best.box.height >= MIN_FACE_SIZE) {
                selected += best
                remaining.removeAll { intersectionOverUnion(best.box, it.box) >= NMS_THRESHOLD }
            }
        }
        return selected
    }

    private fun intersectionOverUnion(first: FaceBox, second: FaceBox): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = first.width * first.height + second.width * second.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private data class Letterbox(
        val bitmap: Bitmap,
        val scaled: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    companion object {
        private const val INPUT_SIZE = 640
        private const val OUTPUT_COUNT = 12
        private const val LANDMARK_COUNT = 5
        private const val LANDMARK_VALUES = LANDMARK_COUNT * 2
        private const val SCORE_THRESHOLD = 0.60f
        private const val NMS_THRESHOLD = 0.45f
        private const val MIN_FACE_SIZE = 12f
        private const val MAX_DETECTIONS = 64
        private val STRIDES = intArrayOf(8, 16, 32)
        private val PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        fun open(context: Context): YuNetFaceDetector = YuNetFaceDetector(
            LiteRtModel.open(ModelCatalog.faceDetector.file(context), threads = 2),
        )
    }
}

object FaceCropper {
    fun crop(bitmap: Bitmap, detection: FaceDetection): Bitmap? {
        val box = detection.box
        val side = (max(box.width, box.height) * 1.7f).roundToInt().coerceAtLeast(1)
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f
        val left = (centerX - side / 2f).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (centerY - side / 2f).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (left + side).coerceAtMost(bitmap.width)
        val bottom = (top + side).coerceAtMost(bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 2 || height < 2) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        if (detection.landmarks.size < 4) return crop
        val rightEyeX = detection.landmarks[0] - left
        val rightEyeY = detection.landmarks[1] - top
        val leftEyeX = detection.landmarks[2] - left
        val leftEyeY = detection.landmarks[3] - top
        val angle = Math.toDegrees(atan2((leftEyeY - rightEyeY).toDouble(), (leftEyeX - rightEyeX).toDouble()))
        if (kotlin.math.abs(angle) < 1.0) return crop
        val rotated = Bitmap.createBitmap(
            crop,
            0,
            0,
            crop.width,
            crop.height,
            Matrix().apply { postRotate(-angle.toFloat(), crop.width / 2f, crop.height / 2f) },
            true,
        )
        if (rotated !== crop) crop.recycle()
        return rotated
    }
}
