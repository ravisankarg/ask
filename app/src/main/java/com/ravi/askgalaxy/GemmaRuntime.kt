package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resident CPU Gemma 4 E4B runtime shared by planning and answer generation.
 * The model engine is loaded only once per process. Planner and answer turns
 * use separate conversations so planner execution syntax cannot leak into prose;
 * the planner conversation is closed as soon as planning is complete.
 */
class GemmaRuntime private constructor(
    private val engine: Engine,
) : Closeable {

    private val generationLock = Any()

    class ConversationSession internal constructor(
        private val owner: GemmaRuntime,
        private val conversation: Conversation,
        private val traceLabel: String,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        val isAlive: Boolean
            get() = !closed.get() && conversation.isAlive

        fun generate(prompt: String): String = generate(prompt, emptyList())

        @OptIn(ExperimentalApi::class)
        fun generate(prompt: String, images: List<ByteArray>): String {
            check(isAlive) { "Gemma conversation is no longer alive" }
            require(prompt.isNotBlank()) { "Prompt must not be blank" }
            return synchronized(owner.generationLock) {
                val startedAt = System.nanoTime()
                val input = ArrayList<Content>(images.size + 1)
                images.forEach { bytes -> input += Content.ImageBytes(bytes) }
                input += Content.Text(prompt)
                val response = conversation.sendMessage(Contents.of(input))
                val text = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("\n") { content -> content.text }
                    .trim()
                runCatching { conversation.getBenchmarkInfo() }.getOrNull()?.let { benchmark ->
                    val wallMs = (System.nanoTime() - startedAt) / 1_000_000L
                    Log.i(
                        TAG,
                        "Gemma $traceLabel profile: wall=${wallMs}ms, " +
                            "prefillTokens=${benchmark.lastPrefillTokenCount}, " +
                            "decodeTokens=${benchmark.lastDecodeTokenCount}, " +
                            "ttftMs=${(benchmark.timeToFirstTokenInSecond * 1_000.0).toLong()}, " +
                            "prefillTps=${"%.1f".format(java.util.Locale.US, benchmark.lastPrefillTokensPerSecond)}, " +
                            "decodeTps=${"%.1f".format(java.util.Locale.US, benchmark.lastDecodeTokensPerSecond)}, " +
                            "images=${images.size}",
                    )
                }
                text
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(owner.generationLock) {
                    conversation.close()
                }
            }
        }
    }

    /** Creates a KV-backed conversation, optionally with a prefilled system preface. */
    fun createConversation(
        systemInstruction: String = "",
        samplerConfig: SamplerConfig? = null,
    ): ConversationSession {
        val config = ConversationConfig(
            systemInstruction = systemInstruction
                .takeIf { it.isNotBlank() }
                ?.let { Contents.of(it) },
            samplerConfig = samplerConfig,
        )
        return ConversationSession(this, engine.createConversation(config), "conversation")
    }

    fun createPlannerConversation(systemInstruction: String): ConversationSession =
        ConversationSession(
            this,
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = systemInstruction
                        .takeIf { it.isNotBlank() }
                        ?.let { Contents.of(it) },
                    samplerConfig = PLANNER_SAMPLER_CONFIG,
                ),
            ),
            "planner",
        )

    fun createAnswerConversation(systemInstruction: String): ConversationSession =
        ConversationSession(
            this,
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = systemInstruction
                        .takeIf { it.isNotBlank() }
                        ?.let { Contents.of(it) },
                    samplerConfig = ANSWER_SAMPLER_CONFIG,
                ),
            ),
            "answer",
        )

    fun generate(prompt: String): String = generate(prompt, emptyList())

    fun generate(prompt: String, images: List<ByteArray>): String {
        val session = createConversation()
        return try {
            session.generate(prompt, images)
        } finally {
            session.close()
        }
    }

    override fun close() {
        engine.close()
    }

    companion object {
        private const val MODEL_FILE = "gemma-4-E4B-it.litertlm"
        private const val TAG = "AskGalaxy"
        private const val CPU_THREADS = 4
        // Planner output is a constrained execution expression. Low-temperature
        // sampling reduces malformed execution specs and repair fallbacks.
        private val PLANNER_SAMPLER_CONFIG = SamplerConfig(
            topK = 16,
            topP = 0.90,
            temperature = 0.10,
            seed = 17,
        )
        // Answer prose benefits from modest variation, while a low temperature
        // keeps names, dates, and citations stable on a bounded evidence set.
        private val ANSWER_SAMPLER_CONFIG = SamplerConfig(
            topK = 40,
            topP = 0.92,
            temperature = 0.35,
            seed = 29,
        )
        // The answer path keeps complete selected OCR/metadata. Scenery may
        // additionally send four bounded images, so retain an 8K
        // input+output capacity for the joined multimodal prompt.
        private const val MAX_CONTEXT_TOKENS = 8192
        // LiteRT-LM's Gemma 4 graph must still be created with its compiled
        // capacity of eight even though Ask Galaxy sends at most four
        // downscaled scenery images in one answer request.
        private const val MAX_IMAGES = 8
        private val residentLock = Any()
        private val prefilledPlannerLock = Any()
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        private val preloadRequested = AtomicBoolean(false)
        private val plannerPrefillRequested = AtomicBoolean(false)

        @Volatile
        private var resident: GemmaRuntime? = null

        @Volatile
        private var prefilledPlanner: ConversationSession? = null

        fun modelFile(context: Context): File =
            File(context.filesDir, "models/$MODEL_FILE")

        fun isModelInstalled(context: Context): Boolean = modelFile(context).isFile

        /** Returns the one resident CPU Gemma engine for the app process. */
        fun shared(context: Context): GemmaRuntime {
            resident?.let { return it }
            return synchronized(residentLock) {
                resident ?: open(context).also { resident = it }
            }
        }

        /** Starts only engine/model loading without blocking the UI. */
        fun preloadAsync(context: Context) {
            val appContext = context.applicationContext
            if (!isModelInstalled(appContext) || !preloadRequested.compareAndSet(false, true)) return
            preloadExecutor.execute {
                runCatching { shared(appContext) }
                    .onSuccess { Log.i(TAG, "Gemma 4 E4B engine warmed") }
                    .onFailure { error ->
                        preloadRequested.set(false)
                        Log.e(TAG, "Gemma preload failed", error)
                    }
            }
        }

        /**
         * Loads Gemma 4 and creates the planner conversation while the search
         * field is visible. The system instruction is held in the conversation
         * preface so the first submitted query can reuse that KV prefix.
         */
        fun preloadPlannerAsync(context: Context, plannerSystemInstruction: String) {
            val appContext = context.applicationContext
            if (!isModelInstalled(appContext) ||
                !plannerPrefillRequested.compareAndSet(false, true)
            ) return
            preloadExecutor.execute {
                runCatching {
                    val session = shared(appContext).createPlannerConversation(plannerSystemInstruction)
                    synchronized(prefilledPlannerLock) {
                        val old = prefilledPlanner
                        prefilledPlanner = session
                        old?.close()
                    }
                    Log.i(TAG, "Gemma 4 planner preface/KV session warmed")
                }.onFailure { error ->
                    plannerPrefillRequested.set(false)
                    Log.e(TAG, "Gemma 4 planner prefill failed", error)
                }
            }
        }

        /** Takes the warmed planner conversation for the next synchronous query. */
        fun takePrefilledPlannerSession(): ConversationSession? =
            synchronized(prefilledPlannerLock) {
                val session = prefilledPlanner
                prefilledPlanner = null
                plannerPrefillRequested.set(false)
                session
            }

        /** Releases the engine and any unused planner KV session. */
        fun releaseResident() {
            synchronized(prefilledPlannerLock) {
                prefilledPlanner?.close()
                prefilledPlanner = null
                plannerPrefillRequested.set(false)
            }
            synchronized(residentLock) {
                resident?.close()
                resident = null
                preloadRequested.set(false)
            }
        }

        private fun open(context: Context): GemmaRuntime {
            val model = modelFile(context)
            check(model.isFile) {
                "Gemma model is not installed: ${model.absolutePath}"
            }
            // Keep language generation on CPU so the frozen QP retains its
            // verified numerical path. Move only the image encoder/adapter to
            // GPU: Gemma 4's CPU vision graph otherwise pushes this Samsung
            // above its per-process memory guard before answer decoding begins.
            // LiteRT-LM 0.14 supports the split backend directly.
            val backend = Backend.CPU(CPU_THREADS)
            val visionBackend = Backend.GPU()
            val cacheDir = File(context.filesDir, "models/cache").apply {
                check(mkdirs() || isDirectory) {
                    "Could not create Gemma cache directory: $absolutePath"
                }
            }
            fun initialize(vision: Backend): GemmaRuntime {
                val config = EngineConfig(
                    model.absolutePath,
                    backend,
                    vision,
                    backend,
                    MAX_CONTEXT_TOKENS,
                    MAX_IMAGES,
                    cacheDir.absolutePath,
                )
                val engine = Engine(config)
                return try {
                    engine.initialize()
                    GemmaRuntime(engine)
                } catch (error: Throwable) {
                    runCatching { engine.close() }
                    throw error
                }
            }
            return try {
                initialize(visionBackend)
            } catch (gpuError: RuntimeException) {
                Log.w(
                    TAG,
                    "Gemma GPU vision initialization failed; using CPU vision fallback",
                    gpuError,
                )
                initialize(Backend.CPU(CPU_THREADS))
            }
        }
    }
}
