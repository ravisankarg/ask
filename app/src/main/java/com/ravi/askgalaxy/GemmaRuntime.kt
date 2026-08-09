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
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Resident Gemma 4 runtime shared by planning and answer generation.
 * The model engine is loaded only once per process. Planner and answer turns
 * use separate conversations so planner execution syntax cannot leak into prose;
 * planner and answer system prefixes can be warmed independently, while each
 * query turn remains isolated.
 */
class GemmaRuntime private constructor(
    private val engine: Engine,
) : Closeable {

    private val generationLock = Any()

    data class GenerationProfile(
        val wallMs: Long,
        val prefillMs: Long,
        /** Stable system-prefix work completed before this user turn. */
        val startupPrefillMs: Long = 0L,
        val decodeMs: Long,
        val timeToFirstTokenMs: Long,
        val prefillTokens: Int,
        val decodeTokens: Int,
        val prefillTokensPerSecond: Double,
        val decodeTokensPerSecond: Double,
        val backendPlacement: String,
    ) {
        companion object {
            fun combine(profiles: List<GenerationProfile>): GenerationProfile? {
                if (profiles.isEmpty()) return null
                val first = profiles.first()
                return GenerationProfile(
                    wallMs = profiles.sumOf { it.wallMs },
                    prefillMs = profiles.sumOf { it.prefillMs },
                    startupPrefillMs = profiles.sumOf { it.startupPrefillMs },
                    decodeMs = profiles.sumOf { it.decodeMs },
                    timeToFirstTokenMs = profiles.sumOf { it.timeToFirstTokenMs },
                    prefillTokens = profiles.sumOf { it.prefillTokens },
                    decodeTokens = profiles.sumOf { it.decodeTokens },
                    prefillTokensPerSecond = profiles.sumOf { it.prefillTokens }
                        .toDouble()
                        .div(profiles.sumOf { it.prefillMs }.coerceAtLeast(1L)) * 1_000.0,
                    decodeTokensPerSecond = profiles.sumOf { it.decodeTokens }
                        .toDouble()
                        .div(profiles.sumOf { it.decodeMs }.coerceAtLeast(1L)) * 1_000.0,
                    backendPlacement = profiles.map { it.backendPlacement }.distinct().joinToString(" + "),
                )
            }
        }
    }

    class ConversationSession internal constructor(
        private val owner: GemmaRuntime,
        private val conversation: Conversation?,
        private val traceLabel: String,
        private val rawSession: Session? = null,
        private val rawRenderer: Conversation? = null,
        private val rawStartupPrefillMs: Long = 0L,
        private val rawPreface: String = "",
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        @Volatile
        var lastGenerationProfile: GenerationProfile? = null
            private set

        val isAlive: Boolean
            get() = !closed.get() && (conversation?.isAlive == true || rawSession?.isAlive == true)

        val supportsImages: Boolean
            get() = rawSession == null

        fun generate(prompt: String): String = generate(prompt, emptyList())

        @OptIn(ExperimentalApi::class)
        fun generate(prompt: String, images: List<ByteArray>): String {
            check(isAlive) { "Gemma conversation is no longer alive" }
            require(prompt.isNotBlank()) { "Prompt must not be blank" }
            return synchronized(owner.generationLock) {
                val startedAt = System.nanoTime()
                lastGenerationProfile = null
                if (rawSession != null) {
                    check(images.isEmpty()) { "Raw text session does not support images" }
                    val rendered = rawRenderer?.renderMessageIntoString(Message.user(prompt)) ?: prompt
                    // Conversation.renderMessageIntoString returns the complete
                    // rendered prompt, including the system preface. The raw
                    // Session already contains that preface in KV, so sending
                    // the complete string here causes the 60-70s re-prefill.
                    // Keep only the new user-turn suffix.
                    val delta = if (rawPreface.isNotEmpty() && rendered.startsWith(rawPreface)) {
                        rendered.substring(rawPreface.length)
                    } else {
                        rendered
                    }
                    val queryPrefillStartedAt = System.nanoTime()
                    rawSession.runPrefill(listOf(InputData.Text(delta)))
                    val queryPrefillMs = (System.nanoTime() - queryPrefillStartedAt) / 1_000_000L
                    val decodeStartedAt = System.nanoTime()
                    val rawText = rawSession.runDecode()
                    val decodeMs = (System.nanoTime() - decodeStartedAt) / 1_000_000L
                    val text = rawText.trim()
                    val wallMs = (System.nanoTime() - startedAt) / 1_000_000L
                    lastGenerationProfile = GenerationProfile(
                        wallMs = wallMs,
                        prefillMs = queryPrefillMs,
                        startupPrefillMs = rawStartupPrefillMs,
                        decodeMs = decodeMs,
                        timeToFirstTokenMs = 0L,
                        prefillTokens = 0,
                        decodeTokens = 0,
                        prefillTokensPerSecond = 0.0,
                        decodeTokensPerSecond = 0.0,
                        backendPlacement = GemmaRuntime.backendPlacement(),
                    )
                    Log.i(
                        TAG,
                        "Gemma $traceLabel raw-session generation: " +
                            "startupPrefillMs=$rawStartupPrefillMs, " +
                            "queryPrefillMs=$queryPrefillMs, decodeMs=$decodeMs, wallMs=$wallMs, " +
                            "renderedChars=${rendered.length}, deltaChars=${delta.length}, " +
                            "rawChars=${rawText.length}, trimmedChars=${text.length}, " +
                            "rawOutput=${rawText.replace("\\n", "\\\\n").replace("\\r", "\\\\r")}",
                    )
                    return@synchronized text
                }
                val activeConversation = conversation
                    ?: error("Conversation session has no conversation or raw session")
                val input = ArrayList<Content>(images.size + 1)
                images.forEach { bytes -> input += Content.ImageBytes(bytes) }
                input += Content.Text(prompt)
                val response = activeConversation.sendMessage(Contents.of(input))
                val text = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("\n") { content -> content.text }
                    .trim()
                val wallMs = (System.nanoTime() - startedAt) / 1_000_000L
                runCatching { activeConversation.getBenchmarkInfo() }.getOrNull()?.let { benchmark ->
                    val prefillTokens = benchmark.lastPrefillTokenCount
                    val decodeTokens = benchmark.lastDecodeTokenCount
                    val prefillTps = benchmark.lastPrefillTokensPerSecond
                    val decodeTps = benchmark.lastDecodeTokensPerSecond
                    val prefillMs = if (prefillTps > 0.0) {
                        (prefillTokens / prefillTps * 1_000.0).toLong()
                    } else {
                        0L
                    }
                    val decodeMs = if (decodeTps > 0.0) {
                        (decodeTokens / decodeTps * 1_000.0).toLong()
                    } else {
                        (wallMs - prefillMs).coerceAtLeast(0L)
                    }
                    val ttftMs = (benchmark.timeToFirstTokenInSecond * 1_000.0).toLong()
                    lastGenerationProfile = GenerationProfile(
                        wallMs = wallMs,
                        prefillMs = prefillMs,
                        decodeMs = decodeMs,
                        timeToFirstTokenMs = ttftMs,
                        prefillTokens = prefillTokens,
                        decodeTokens = decodeTokens,
                        prefillTokensPerSecond = prefillTps,
                        decodeTokensPerSecond = decodeTps,
                        backendPlacement = GemmaRuntime.backendPlacement(),
                    )
                    Log.i(
                        TAG,
                        "Gemma $traceLabel profile: wall=${wallMs}ms, " +
                            "prefillTokens=${benchmark.lastPrefillTokenCount}, " +
                            "decodeTokens=${benchmark.lastDecodeTokenCount}, " +
                            "ttftMs=$ttftMs, " +
                            "prefillTps=${"%.1f".format(java.util.Locale.US, benchmark.lastPrefillTokensPerSecond)}, " +
                            "decodeTps=${"%.1f".format(java.util.Locale.US, benchmark.lastDecodeTokensPerSecond)}, " +
                            "images=${images.size}",
                    )
                }
                if (lastGenerationProfile == null) {
                    lastGenerationProfile = GenerationProfile(
                        wallMs = wallMs,
                        prefillMs = 0L,
                        decodeMs = wallMs,
                        timeToFirstTokenMs = 0L,
                        prefillTokens = 0,
                        decodeTokens = 0,
                        prefillTokensPerSecond = 0.0,
                        decodeTokensPerSecond = 0.0,
                        backendPlacement = GemmaRuntime.backendPlacement(),
                    )
                }
                text
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(owner.generationLock) {
                    conversation?.close()
                    rawRenderer?.takeIf { it !== conversation }?.close()
                    rawSession?.close()
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

    /**
     * Experimental true-prefill planner path. The Conversation is used only
     * to obtain LiteRT-LM's exact prompt rendering; the reusable KV state is
     * owned by the lower-level Session.
     */
    @OptIn(ExperimentalApi::class)
    fun createPrefilledPlannerSession(systemInstruction: String): ConversationSession {
        val renderer = engine.createConversation(
            ConversationConfig(
                systemInstruction = systemInstruction
                    .takeIf { it.isNotBlank() }
                    ?.let { Contents.of(it) },
                samplerConfig = PLANNER_SAMPLER_CONFIG,
            ),
        )
        return try {
            val session = engine.createSession(SessionConfig(samplerConfig = PLANNER_SAMPLER_CONFIG))
            val prefillStartedAt = System.nanoTime()
            val preface = renderer.renderPrefaceIntoString()
            session.runPrefill(listOf(InputData.Text(preface)))
            val prefillMs = (System.nanoTime() - prefillStartedAt) / 1_000_000L
            Log.i(
                TAG,
                "Gemma planner startup raw prefill complete: " +
                    "prefillMs=$prefillMs, prefaceChars=${preface.length}",
            )
            ConversationSession(
                this,
                conversation = null,
                traceLabel = "planner-prefilled",
                rawSession = session,
                rawRenderer = renderer,
                rawStartupPrefillMs = prefillMs,
                rawPreface = preface,
            )
        } catch (error: Throwable) {
            renderer.close()
            throw error
        }
    }

    /** Experimental true-prefill path for text-only answer requests. */
    @OptIn(ExperimentalApi::class)
    fun createPrefilledAnswerSession(systemInstruction: String): ConversationSession {
        val renderer = engine.createConversation(
            ConversationConfig(
                systemInstruction = systemInstruction
                    .takeIf { it.isNotBlank() }
                    ?.let { Contents.of(it) },
                samplerConfig = ANSWER_SAMPLER_CONFIG,
            ),
        )
        return try {
            val session = engine.createSession(SessionConfig(samplerConfig = ANSWER_SAMPLER_CONFIG))
            val prefillStartedAt = System.nanoTime()
            val preface = renderer.renderPrefaceIntoString()
            session.runPrefill(listOf(InputData.Text(preface)))
            val prefillMs = (System.nanoTime() - prefillStartedAt) / 1_000_000L
            Log.i(
                TAG,
                "Gemma answer startup raw prefill complete: " +
                    "prefillMs=$prefillMs, prefaceChars=${preface.length}",
            )
            ConversationSession(
                this,
                conversation = null,
                traceLabel = "answer-prefilled",
                rawSession = session,
                rawRenderer = renderer,
                rawStartupPrefillMs = prefillMs,
                rawPreface = preface,
            )
        } catch (error: Throwable) {
            renderer.close()
            throw error
        }
    }

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
        // The answer contract carries the complete selected top-four
        // evidence block. Keep the graph capacity aligned with the product
        // contract; request-level visual inputs remain capped at four.
        private const val E4B_MAX_CONTEXT_TOKENS = 8192
        private const val MAX_IMAGES = 4
        private val residentLock = Any()
        private val prefilledPlannerLock = Any()
        private val prefilledAnswerLock = Any()
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        // Closing a multi-gigabyte native engine can take several seconds.
        // Model selection is a Settings UI action, so native teardown must
        // never run on the main thread.
        private val lifecycleExecutor = Executors.newSingleThreadExecutor()
        private val preloadRequested = AtomicBoolean(false)
        private val plannerPrefillRequested = AtomicBoolean(false)
        private val answerPrefillRequested = AtomicBoolean(false)
        /** Invalidates queued/in-flight planner warmups when answer KV is needed. */
        private val plannerPrefillGeneration = AtomicLong(0L)
        private val answerPrefillGeneration = AtomicLong(0L)

        @Volatile
        private var resident: GemmaRuntime? = null

        private val plannerReady = AtomicBoolean(false)

        /** UI observer for a completed planner KV warmup; never used for work scheduling. */
        @Volatile
        private var plannerWarmupStateListener: ((Boolean) -> Unit)? = null

        @Volatile
        private var backendPlacement = "text CPU/4 threads • vision GPU"

        @Volatile
        private var prefilledPlanner: ConversationSession? = null

        @Volatile
        private var prefilledAnswer: ConversationSession? = null

        /** Completion of the one answer-prefix warmup scheduled after QP. */
        @Volatile
        private var answerPrefillFuture: Future<*>? = null

        fun modelFile(context: Context): File = ModelCatalog.gemma(context).file(context)

        fun isModelInstalled(context: Context): Boolean = ModelCatalog.gemma(context).isInstalled(context)

        fun backendPlacement(): String = backendPlacement

        fun isPlannerReady(): Boolean = plannerReady.get()

        fun setPlannerWarmupStateListener(listener: ((Boolean) -> Unit)?) {
            plannerWarmupStateListener = listener
            listener?.invoke(plannerReady.get())
        }

        private fun notifyPlannerWarmupState(ready: Boolean) {
            runCatching { plannerWarmupStateListener?.invoke(ready) }
                .onFailure { error -> Log.w(TAG, "Planner warmup UI callback failed", error) }
        }

        /** Returns the one resident CPU Gemma engine for the app process. */
        fun shared(context: Context): GemmaRuntime {
            check(!DocumentIndexRuntimeGate.isActive()) {
                "Gemma 4 is paused while personal document indexing is active"
            }
            resident?.let { return it }
            return synchronized(residentLock) {
                resident ?: open(context).also { resident = it }
            }
        }

        /** Starts only engine/model loading without blocking the UI. */
        fun preloadAsync(context: Context) {
            val appContext = context.applicationContext
            if (DocumentIndexRuntimeGate.isActive()) return
            if (!isModelInstalled(appContext) || !preloadRequested.compareAndSet(false, true)) return
            preloadExecutor.execute {
                runCatching { shared(appContext) }
                    .onSuccess { Log.i(TAG, "${GemmaModelSelection.selected(appContext).displayName} engine warmed") }
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
            if (DocumentIndexRuntimeGate.isActive()) return
            if (!isModelInstalled(appContext) ||
                answerPrefillRequested.get() ||
                !plannerPrefillRequested.compareAndSet(false, true)
            ) return
            val requestGeneration = plannerPrefillGeneration.incrementAndGet()
            plannerReady.set(false)
            preloadExecutor.execute {
                runCatching {
                    val session = runCatching {
                        shared(appContext).createPrefilledPlannerSession(plannerSystemInstruction)
                    }.getOrElse { error ->
                        Log.w(TAG, "True planner prefill unavailable; using Conversation fallback", error)
                        shared(appContext).createPlannerConversation(plannerSystemInstruction)
                    }
                    val accepted = synchronized(prefilledPlannerLock) {
                        if (plannerPrefillGeneration.get() != requestGeneration ||
                            answerPrefillRequested.get()
                        ) {
                            false
                        } else {
                            val old = prefilledPlanner
                            prefilledPlanner = session
                            old?.close()
                            true
                        }
                    }
                    if (!accepted) {
                        session.close()
                        plannerPrefillRequested.set(false)
                        plannerReady.set(false)
                        Log.i(TAG, "Discarded planner KV warmup while answer KV was reserved")
                        return@runCatching
                    }
                    plannerReady.set(true)
                    Log.i(TAG, "Gemma 4 planner preface explicitly prefetched into reusable KV session")
                    notifyPlannerWarmupState(true)
                }.onFailure { error ->
                    plannerPrefillRequested.set(false)
                    plannerReady.set(false)
                    Log.e(TAG, "Gemma 4 planner prefill failed", error)
                    notifyPlannerWarmupState(false)
                }
            }
        }

        /** Takes the warmed planner conversation for the next synchronous query. */
        fun takePrefilledPlannerSession(): ConversationSession? =
            synchronized(prefilledPlannerLock) {
                val session = prefilledPlanner
                prefilledPlanner = null
                plannerPrefillRequested.set(false)
                plannerReady.set(false)
                session
            }

        /**
         * Answer generation has priority over the next-query planner cache.
         * Keep only one prefilled KV session resident, and invalidate a queued
         * planner task so it cannot repopulate the cache behind an answer.
         */
        private fun releasePlannerPrefillForAnswer() {
            plannerPrefillGeneration.incrementAndGet()
            plannerPrefillRequested.set(false)
            plannerReady.set(false)
            val stale = synchronized(prefilledPlannerLock) {
                prefilledPlanner.also { prefilledPlanner = null }
            }
            stale?.close()
        }

        private fun releaseAnswerPrefillForPlanner() {
            answerPrefillGeneration.incrementAndGet()
            answerPrefillRequested.set(false)
            answerPrefillFuture?.cancel(false)
            answerPrefillFuture = null
            val stale = synchronized(prefilledAnswerLock) {
                prefilledAnswer.also { prefilledAnswer = null }
            }
            stale?.close()
        }

        fun preloadPlannerAfterAnswerAsync(context: Context, plannerSystemInstruction: String) {
            releaseAnswerPrefillForPlanner()
            preloadPlannerAsync(context, plannerSystemInstruction)
        }

        /**
         * Creates a clean answer conversation while retrieval/context assembly
         * is running. Only the stable answer system prefix is warmed; the
         * question, evidence, and images are still sent on the answer turn.
         */
        fun preloadAnswerAsync(context: Context, answerSystemInstruction: String) {
            val appContext = context.applicationContext
            if (DocumentIndexRuntimeGate.isActive()) return
            if (!isModelInstalled(appContext)) return
            releasePlannerPrefillForAnswer()
            if (!answerPrefillRequested.compareAndSet(false, true)) return
            val requestGeneration = answerPrefillGeneration.incrementAndGet()
            answerPrefillFuture = preloadExecutor.submit {
                runCatching {
                    val session = runCatching {
                        shared(appContext).createPrefilledAnswerSession(answerSystemInstruction)
                    }.getOrElse { error ->
                        Log.w(TAG, "True answer prefill unavailable; using Conversation fallback", error)
                        shared(appContext).createAnswerConversation(answerSystemInstruction)
                    }
                    synchronized(prefilledAnswerLock) {
                        if (answerPrefillGeneration.get() != requestGeneration) {
                            session.close()
                            return@runCatching
                        }
                        val old = prefilledAnswer
                        prefilledAnswer = session
                        old?.close()
                    }
                    Log.i(TAG, "Gemma 4 answer preface/KV session warmed")
                }.onFailure { error ->
                    answerPrefillRequested.set(false)
                    Log.e(TAG, "Gemma 4 answer prefill failed", error)
                }
            }
        }

        /** Takes the warmed answer conversation for the next answer turn. */
        fun takePrefilledAnswerSession(allowTextOnlyRawSession: Boolean = true): ConversationSession? {
            // QP deliberately switches to the answer system context. Do not
            // race that load by falling back to an uncached 12K Conversation;
            // answerAsync is already off the UI thread, so waiting here keeps
            // the costly work outside the submitted answer latency.
            val pending = answerPrefillFuture
            if (pending != null && !pending.isDone) {
                runCatching { pending.get() }
                    .onFailure { error -> Log.w(TAG, "Answer-prefix warmup did not complete", error) }
            }
            return synchronized(prefilledAnswerLock) {
                val session = prefilledAnswer
                prefilledAnswer = null
                answerPrefillFuture = null
                answerPrefillRequested.set(false)
                if (session != null && !allowTextOnlyRawSession && !session.supportsImages) {
                    session.close()
                    null
                } else {
                    session
                }
            }
        }

        /** Releases the engine and any unused planner KV session. */
        fun releaseResident() {
            synchronized(prefilledPlannerLock) {
                prefilledPlanner?.close()
                prefilledPlanner = null
                plannerPrefillRequested.set(false)
            }
            synchronized(prefilledAnswerLock) {
                prefilledAnswer?.close()
                prefilledAnswer = null
                answerPrefillRequested.set(false)
            }
            answerPrefillFuture?.cancel(false)
            answerPrefillFuture = null
            synchronized(residentLock) {
                resident?.close()
                resident = null
                preloadRequested.set(false)
            }
            plannerReady.set(false)
        }

        fun releaseResidentAsync() {
            lifecycleExecutor.execute {
                runCatching { releaseResident() }
                    .onFailure { error -> Log.w(TAG, "Gemma background release failed", error) }
            }
        }

        private fun open(context: Context): GemmaRuntime {
            val model = modelFile(context)
            check(model.isFile) {
                "Gemma model is not installed: ${model.absolutePath}"
            }
            val cacheDir = File(context.filesDir, "models/cache").apply {
                check(mkdirs() || isDirectory) {
                    "Could not create Gemma cache directory: $absolutePath"
                }
            }
            fun initialize(
                language: Backend,
                vision: Backend,
                maxContextTokens: Int,
            ): GemmaRuntime {
                val config = EngineConfig(
                    model.absolutePath,
                    language,
                    vision,
                    // No audio is accepted or requested by Ask Galaxy. Passing
                    // null avoids allocating an audio backend for every engine.
                    null,
                    maxContextTokens,
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
            // Strict E4B configuration: both language and vision run on the
            // phone GPU. A device without a usable GPU backend fails clearly.
            backendPlacement = "E4B full GPU • text + vision"
            return initialize(
                language = Backend.GPU(),
                vision = Backend.GPU(),
                maxContextTokens = E4B_MAX_CONTEXT_TOKENS,
            )
        }
    }
}
