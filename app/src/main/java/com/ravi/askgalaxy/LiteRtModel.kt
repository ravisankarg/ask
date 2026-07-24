package com.ravi.askgalaxy

import org.tensorflow.lite.InterpreterApi
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.HashMap

/** Small, native-backed LiteRT interpreter wrapper for exported fixed-shape models. */
class LiteRtModel private constructor(
    private val interpreter: InterpreterApi,
) : Closeable {

    val inputCount: Int
        get() = interpreter.inputTensorCount

    val outputCount: Int
        get() = interpreter.outputTensorCount

    fun inputTensor(index: Int = 0) = interpreter.getInputTensor(index)

    fun outputTensor(index: Int = 0) = interpreter.getOutputTensor(index)

    fun run(input: ByteBuffer, output: ByteBuffer) {
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
    }

    fun runMultiple(input: ByteBuffer, outputs: List<ByteBuffer>) {
        input.rewind()
        outputs.forEach(ByteBuffer::rewind)
        val outputMap = HashMap<Int, Any>(outputs.size)
        outputs.forEachIndexed { index, output -> outputMap[index] = output }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputMap)
        outputs.forEach(ByteBuffer::rewind)
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        fun open(file: File, threads: Int = 4): LiteRtModel {
            check(file.isFile) { "LiteRT model is not installed: ${file.absolutePath}" }
            val options = InterpreterApi.Options()
                .setNumThreads(threads)
                .setUseXNNPACK(true)
            return LiteRtModel(InterpreterApi.create(file, options).also { it.allocateTensors() })
        }
    }
}
