package com.ravi.askgalaxy

/**
 * Concurrency policy for the independent on-device inference workers.
 *
 * OCR and face inference can use all cores because their workers do not each
 * retain a large vision model. SigLIP is different: every worker owns a full
 * LiteRT interpreter and its tensors, so duplicating it once per CPU core can
 * exhaust phone memory before throughput improves.
 */
object InferenceParallelism {
    fun workerCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun visualWorkerCount(): Int = workerCount().coerceAtMost(2)
}
