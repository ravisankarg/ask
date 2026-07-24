package com.ravi.askgalaxy

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Executes the required FaceNet identity encoder.
 *
 * YuNet is responsible for finding a face and its landmarks. The caller must
 * pass a square, landmark-aligned face crop here; this class only resizes that
 * crop to the model's fixed input and returns a normalized identity vector.
 */
class FaceNetEncoder private constructor(
    private val model: LiteRtModel,
) : Closeable {
    private val inputTensor = model.inputTensor()
    private val outputTensor = model.outputTensor()
    private val inputBuffer = ByteBuffer
        .allocateDirect(inputTensor.numBytes())
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer
        .allocateDirect(outputTensor.numBytes())
        .order(ByteOrder.nativeOrder())

    init {
        require(model.inputCount == 1) { "FaceNet model must have one input" }
        require(model.outputCount >= 1) { "FaceNet model has no output" }
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "FaceNet input must be FLOAT32, got ${inputTensor.dataType()}"
        }
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "FaceNet output must be FLOAT32, got ${outputTensor.dataType()}"
        }
        require(inputTensor.shape().contentEquals(intArrayOf(1, IMAGE_SIZE, IMAGE_SIZE, 3))) {
            "Expected FaceNet input [1,160,160,3], got ${inputTensor.shape().contentToString()}"
        }
        require(outputTensor.numElements() == EMBEDDING_DIMENSION) {
            "Expected FaceNet output of 512 values, got ${outputTensor.numElements()}"
        }
    }

    /** Encodes a detector-aligned RGB face crop into a normalized 512-D vector. */
    fun encode(alignedFace: Bitmap): FloatArray {
        val resized = if (alignedFace.width == IMAGE_SIZE && alignedFace.height == IMAGE_SIZE) {
            alignedFace
        } else {
            Bitmap.createScaledBitmap(alignedFace, IMAGE_SIZE, IMAGE_SIZE, true)
        }
        try {
            inputBuffer.clear()
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            for (pixel in pixels) {
                inputBuffer.putFloat(((pixel shr 16 and 0xff) - 127.5f) / 127.5f)
                inputBuffer.putFloat(((pixel shr 8 and 0xff) - 127.5f) / 127.5f)
                inputBuffer.putFloat(((pixel and 0xff) - 127.5f) / 127.5f)
            }
            model.run(inputBuffer, outputBuffer)
            return FloatArray(EMBEDDING_DIMENSION) { outputBuffer.float }.also(::l2Normalize)
        } finally {
            if (resized !== alignedFace) resized.recycle()
        }
    }

    override fun close() {
        model.close()
    }

    companion object {
        const val IMAGE_SIZE = 160
        const val EMBEDDING_DIMENSION = 512

        fun open(context: Context): FaceNetEncoder =
            FaceNetEncoder(LiteRtModel.open(ModelCatalog.faceEmbedder.file(context), threads = 2))

        private fun l2Normalize(values: FloatArray) {
            var sum = 0.0
            for (value in values) sum += value.toDouble() * value.toDouble()
            val norm = sqrt(sum).toFloat().coerceAtLeast(1.0e-12f)
            for (index in values.indices) values[index] /= norm
        }
    }
}
