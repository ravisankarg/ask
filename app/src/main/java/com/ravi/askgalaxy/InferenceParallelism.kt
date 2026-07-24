package com.ravi.askgalaxy

import kotlin.math.min

/** Conservative CPU parallelism: separate interpreter instances, not shared ones. */
object InferenceParallelism {
    fun workerCount(): Int = min(2, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
}
