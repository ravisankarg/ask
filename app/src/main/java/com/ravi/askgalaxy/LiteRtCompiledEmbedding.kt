package com.ravi.askgalaxy

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.CompiledModel.GpuOptions
import java.io.Closeable

/** Fixed-shape EmbeddingGemma runner using LiteRT's modern compiled GPU API. */
class LiteRtCompiledEmbedding private constructor(
    private val compiled: CompiledModel,
    private val input: com.google.ai.edge.litert.TensorBuffer,
    private val output: com.google.ai.edge.litert.TensorBuffer,
) : Closeable {
    fun run(ids: IntArray): FloatArray {
        require(ids.size == EmbeddingGemmaLiteRt.MAX_TOKENS)
        val started = SystemClock.elapsedRealtimeNanos()
        input.writeInt(ids)
        compiled.run(listOf(input), listOf(output), 0)
        val values = output.readFloat()
        val wallMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        Log.i(TAG, "CompiledModel GPU encode wallMs=$wallMs charsTokens=${ids.size}")
        return values
    }

    override fun close() = compiled.close()

    companion object {
        private const val TAG = "AskGalaxyEmbedding"

        fun open(modelFile: java.io.File): LiteRtCompiledEmbedding {
            check(modelFile.isFile) { "LiteRT model is not installed: ${modelFile.absolutePath}" }
            // Match the official EmbeddingGemma semantic-similarity sample:
            // GPU compilation is requested with explicit FP32 precision.
            val options = CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = GpuOptions(precision = GpuOptions.Precision.FP32)
            }
            val compiled = CompiledModel.create(modelFile.absolutePath, options)
            val inputs = compiled.createInputBuffers(0)
            val outputs = compiled.createOutputBuffers(0)
            check(inputs.size == 1) { "EmbeddingGemma must have one input" }
            check(outputs.size == 1) { "EmbeddingGemma must have one output" }
            return LiteRtCompiledEmbedding(compiled, inputs[0], outputs[0])
        }
    }
}
