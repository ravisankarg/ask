package com.ravi.askgalaxy

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Executes the exported SigLIP vision encoder.
 *
 * The installed direct SigLIP2 artifact contract is deliberately strict: one
 * [1,3,224,224] FLOAT32 NCHW input and one [1,768] FLOAT32 output. The model
 * uses FP16 weights internally, while the Kotlin boundary remains FLOAT32.
 */
class SigLipImageEncoder private constructor(
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
        require(model.inputCount == 1) { "SigLIP vision model must have one input" }
        require(model.outputCount >= 1) { "SigLIP vision model has no output" }
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "SigLIP input must be FLOAT32, got ${inputTensor.dataType()}"
        }
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "SigLIP output must be FLOAT32, got ${outputTensor.dataType()}"
        }
        require(inputTensor.shape().contentEquals(intArrayOf(1, 3, IMAGE_SIZE, IMAGE_SIZE))) {
            "Expected SigLIP2 input [1,3,224,224], got ${inputTensor.shape().contentToString()}"
        }
        require(outputTensor.numElements() == EMBEDDING_DIMENSION) {
            "Expected SigLIP2 output of 768 values, got ${outputTensor.numElements()}"
        }
    }

    fun encode(bitmap: Bitmap): FloatArray {
        val square = centerCrop(bitmap, IMAGE_SIZE)
        try {
            inputBuffer.clear()
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            square.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            // The direct LiteRT graph is NCHW, so write each RGB plane separately.
            // SigLIP2's processor uses mean=.5 and std=.5 per channel.
            for (channel in 0..2) {
                for (pixel in pixels) {
                    val value = when (channel) {
                        0 -> pixel shr 16 and 0xff
                        1 -> pixel shr 8 and 0xff
                        else -> pixel and 0xff
                    }
                    inputBuffer.putFloat((value / 255f - 0.5f) / 0.5f)
                }
            }
            model.run(inputBuffer, outputBuffer)
            val embedding = FloatArray(EMBEDDING_DIMENSION) { outputBuffer.float }
            l2Normalize(embedding)
            return embedding
        } finally {
            if (square !== bitmap) square.recycle()
        }
    }

    override fun close() {
        model.close()
    }

    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        if (bitmap.width == size && bitmap.height == size) return bitmap
        val scale = maxOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(size)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(size)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val left = (width - size) / 2
        val top = (height - size) / 2
        return Bitmap.createBitmap(scaled, left, top, size, size).also {
            if (scaled !== bitmap && scaled !== it) scaled.recycle()
        }
    }

    companion object {
        const val IMAGE_SIZE = 224
        const val EMBEDDING_DIMENSION = 768

        fun open(context: android.content.Context): SigLipImageEncoder =
            SigLipImageEncoder(LiteRtModel.open(ModelCatalog.siglipVision.file(context)))

        private fun l2Normalize(values: FloatArray) {
            var sum = 0.0
            for (value in values) sum += value.toDouble() * value.toDouble()
            val norm = sqrt(sum).toFloat().coerceAtLeast(1.0e-12f)
            for (index in values.indices) values[index] /= norm
        }
    }
}
