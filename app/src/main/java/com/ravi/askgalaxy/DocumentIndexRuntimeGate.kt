package com.ravi.askgalaxy

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CopyOnWriteArrayList

/** Prevents the multi-GB Gemma 4 engine from competing with document indexing. */
object DocumentIndexRuntimeGate {
    private val active = AtomicBoolean(false)
    private val workerLock = ReentrantLock(true)
    private val releaseListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addReleaseListener(listener: () -> Unit) {
        releaseListeners += listener
    }

    fun removeReleaseListener(listener: () -> Unit) {
        releaseListeners -= listener
    }

    fun begin() {
        // LiteRT GPU CompiledModel owns process/device resources that must not
        // be initialized by two WorkManager workers at the same time.
        workerLock.lock()
        active.set(true)
        GemmaRuntime.releaseResident()
    }

    fun end() {
        active.set(false)
        workerLock.unlock()
        releaseListeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    fun isActive(): Boolean = active.get()
}
