package com.ravi.askgalaxy

import java.util.concurrent.atomic.AtomicBoolean

/** Prevents the multi-GB Gemma 4 engine from competing with document indexing. */
object DocumentIndexRuntimeGate {
    private val active = AtomicBoolean(false)

    fun begin() {
        active.set(true)
        GemmaRuntime.releaseResident()
    }

    fun end() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}
