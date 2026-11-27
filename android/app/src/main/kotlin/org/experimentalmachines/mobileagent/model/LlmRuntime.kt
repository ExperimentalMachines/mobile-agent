package org.experimentalmachines.mobileagent.model

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The model on LiteRT-LM, after Google's where_to_put sample (litert-samples, samples/litert_lm/where_to_put):
 * `Engine(EngineConfig(path, backend, visionBackend, maxNumImages = 1, cacheDir))`, `initialize()` once, and a new
 * `Conversation` per request with thinking on; the thought arrives in the "thought" channel, the answer as text.
 *
 * Decoding is greedy (top-k 1), as the benchmarks ran the model (temperature 0).
 */
class LlmRuntime(private val cacheDir: File, val totalMemBytes: Long = 0) {
    data class Loaded(val spec: ModelSpec, val file: File, val textBackend: String, val visionBackend: String, val loadMs: Long, val weightCache: Boolean = true) {
        val backends: String get() = "text ${textBackend.uppercase()}, vision ${visionBackend.uppercase()}"
    }

    sealed interface State {
        data object NotLoaded : State
        data class Loading(val what: String) : State
        data class Ready(val loaded: Loaded) : State
        data class Failed(val message: String) : State
    }

    /** LiteRT-LM's own counters for one request (`Conversation.getBenchmarkInfo`). */
    @Serializable
    data class Bench(
        val ttftMs: Long,
        val prefillTokens: Int,
        val prefillTokensPerS: Double,
        val decodeTokens: Int,
        val decodeTokensPerS: Double,
        val marksMs: Map<String, Long> = emptyMap(),
    )

    data class Reply(
        val text: String,
        val thought: String,
        /** From sending the message to the end of the reply. */
        val totalMs: Long,
        /** From sending to the first streamed piece (thought or text), measured by the app. */
        val firstPieceMs: Long?,
        /** From sending to the last streamed piece. */
        val lastPieceMs: Long?,
        val pieces: Int,
        val bench: Bench?,
    )

    private val _state = MutableStateFlow<State>(State.NotLoaded)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var engine: Engine? = null

    /**
     * LiteRT-LM's `filterChannelContentFromKvCache`. Every request here is a fresh conversation that is thrown away
     * afterwards, so the cache is never reused; with the runtime's default (null, which behaves like true) each
     * request spent about 3.5 s after its last token rewriting the cache (SM8975: 5.9 s -> 2.3 s per call with false,
     * same tokens). The BENCH hook can set it back ("default") to measure.
     */
    @Volatile var filterThoughtFromKvCache: Boolean? = false
    @Volatile var loaded: Loaded? = null
        private set

    val isLoaded: Boolean get() = engine != null

    /** Requests (conversations) the current engine has served since it was created. */
    @Volatile var requestsSinceLoad = 0
        private set

    /** Creates and initializes an engine (a few seconds); closes the previous one first. Throws on failure. */
    @Synchronized
    fun load(spec: ModelSpec, file: File, textBackend: String, visionBackend: String, benchmark: Boolean = true): Loaded {
        close()
        _state.value = State.Loading("${spec.name} (text ${textBackend.uppercase()}, vision ${visionBackend.uppercase()})")
        ExperimentalFlags.enableBenchmark = benchmark // read when an Engine is created; gives TTFT and token rates
        val t0 = SystemClock.elapsedRealtime()
        var cache = cacheDir.absolutePath
        try {
            memoryProblem(totalMemBytes, file.length())?.let { throw IllegalStateException(it) }
            cache = weightCacheDir(cacheDir, file, textBackend == "cpu" || visionBackend == "cpu", cacheDir.usableSpace)
            if (cache == NO_CACHE) Log.w(TAG, "weight cache off: ${cacheDir.usableSpace / 1_000_000} MB free in $cacheDir")
            val e = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend(textBackend),
                    visionBackend = backend(visionBackend),
                    maxNumImages = 1,
                    cacheDir = cache,
                ),
            )
            e.initialize()
            engine = e
            requestsSinceLoad = 0
        } catch (t: Throwable) {
            val msg = describe(t)
            Log.e(TAG, "engine load failed ($textBackend/$visionBackend): $msg", t)
            _state.value = State.Failed(msg)
            throw t
        }
        val l = Loaded(spec, file, textBackend, visionBackend, SystemClock.elapsedRealtime() - t0, weightCache = cache != NO_CACHE)
        loaded = l
        _state.value = State.Ready(l)
        Log.i(TAG, "engine loaded: ${spec.name}, ${l.backends}, ${l.loadMs} ms")
        return l
    }

    @Synchronized
    fun close() {
        engine?.let { e ->
            runCatching { e.close() }.onFailure { Log.w(TAG, "engine close: ${it.message}") }
        }
        engine = null
        loaded = null
        if (_state.value !is State.Failed) _state.value = State.NotLoaded
    }

    /**
     * One request: [png] (already at the model's grid) and [user] text after the [system] instruction, in a new
     * conversation. Blocks the calling thread until the reply ends; [onPiece] sees the stream (thought, text).
     */
    suspend fun generate(
        png: ByteArray,
        system: String,
        user: String,
        maxOutputTokens: Int,
        enableThinking: Boolean,
        onPiece: (thought: String, text: String) -> Unit = { _, _ -> },
    ): Reply {
        val e = engine ?: error("The model is not loaded.")
        val extra = mapOf<String, Any>("enable_thinking" to enableThinking)
        ExperimentalFlags.filterChannelContentFromKvCache = filterThoughtFromKvCache // read when a Conversation is created
        requestsSinceLoad++
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Content.Text(system)),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
                extraContext = extra,
                maxOutputToken = maxOutputTokens,
            ),
        )
        try {
            val text = StringBuilder()
            val thought = StringBuilder()
            var pieces = 0
            var first: Long? = null
            var last: Long? = null
            val t0 = SystemClock.elapsedRealtime()
            conversation.sendMessageAsync(Contents.of(Content.ImageBytes(png), Content.Text(user)), extra).collect { m ->
                val dText = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val dThought = m.channels["thought"].orEmpty()
                if (dText.isEmpty() && dThought.isEmpty()) return@collect
                last = SystemClock.elapsedRealtime() - t0
                if (first == null) first = last
                pieces++
                text.append(dText)
                thought.append(dThought)
                onPiece(thought.toString(), text.toString())
            }
            val total = SystemClock.elapsedRealtime() - t0
            val bench = runCatching { conversation.getBenchmarkInfo() }.getOrNull()?.let { b ->
                Bench(
                    ttftMs = (b.timeToFirstTokenInSecond * 1000).toLong(),
                    prefillTokens = b.lastPrefillTokenCount,
                    prefillTokensPerS = b.lastPrefillTokensPerSecond,
                    decodeTokens = b.lastDecodeTokenCount,
                    decodeTokensPerS = b.lastDecodeTokensPerSecond,
                    marksMs = b.markDurationsInSecond.mapValues { (it.value * 1000).toLong() },
                )
            }
            return Reply(text.toString(), thought.toString(), total, first, last, pieces, bench)
        } catch (t: Throwable) {
            runCatching { conversation.cancelProcess() }
            throw t
        } finally {
            runCatching { conversation.close() }
        }
    }

    /** The prompt as the runtime's chat template renders it: (system preface, user turn with [png]), for checking. */
    fun render(png: ByteArray, system: String, user: String, enableThinking: Boolean): Pair<String, String> {
        val e = engine ?: error("The model is not loaded.")
        val extra = mapOf<String, Any>("enable_thinking" to enableThinking)
        requestsSinceLoad++
        val c = e.createConversation(ConversationConfig(systemInstruction = Contents.of(Content.Text(system)), extraContext = extra))
        try {
            val msg = Message.user(Contents.of(Content.ImageBytes(png), Content.Text(user)))
            return c.renderPrefaceIntoString() to c.renderMessageIntoString(msg, extra)
        } finally {
            runCatching { c.close() }
        }
    }

    private fun backend(name: String): Backend = if (name == "cpu") Backend.CPU() else Backend.GPU()

    companion object {
        const val TAG = "MobileAgent"

        /** LiteRT-LM's sentinel (engine_settings.cc, v0.19.1): no weight or program cache files. */
        const val NO_CACHE = ":nocache"
        /** Room kept for the CPU (XNNPack) weight packs beyond the model's own size: the vision pack alone is ~0.4 GB. */
        const val PACK_HEADROOM = 1_000_000_000L
        /** Memory beyond the model file that Android, the app and the KV cache need at the least. */
        const val RAM_HEADROOM = 1_500_000_000L

        /**
         * On CPU, XNNPack writes repacked weights next to [cacheDir] on first use, and LiteRT aborts the whole process
         * (SIGABRT, "cannot append buffer to cache file") when the disk fills up mid-write. Without room for the packs
         * still missing, run uncached (slower load, more RAM) and drop this model's packs: they may be half-written.
         */
        fun weightCacheDir(cacheDir: File, model: File, cpu: Boolean, freeBytes: Long): String {
            if (!cpu) return cacheDir.absolutePath // GPU-only: compiled programs, small
            // LiteRT names them "<file>_<mtime>_<bytes>.<section>.xnnpack_cache": match this file's name and size exactly.
            val mine = Regex("^" + Regex.escape(model.name) + "_\\d+_" + model.length() + "\\.")
            val packs = cacheDir.listFiles { f -> mine.containsMatchIn(f.name) }.orEmpty()
            val missing = model.length() + PACK_HEADROOM - packs.sumOf { it.length() }
            if (freeBytes >= missing) return cacheDir.absolutePath
            packs.forEach { it.delete() }
            return NO_CACHE
        }

        /** Our own checks throw IllegalStateException with a sentence for people; anything else keeps its class name. */
        fun describe(t: Throwable): String =
            if (t is IllegalStateException && !t.message.isNullOrBlank()) t.message!! else "${t.javaClass.simpleName}: ${t.message}"

        /** A device that cannot hold the weights at all gets a message instead of being killed mid-load. */
        fun memoryProblem(totalMemBytes: Long, modelBytes: Long): String? {
            val need = modelBytes + RAM_HEADROOM
            if (totalMemBytes <= 0 || totalMemBytes >= need) return null
            return String.format(java.util.Locale.US, "Not enough memory: this device has %.1f GB, the model needs at least %.1f GB.", totalMemBytes / 1e9, need / 1e9)
        }
    }
}
