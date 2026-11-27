package org.experimentalmachines.mobileagent.model

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.experimentalmachines.mobileagent.Settings
import java.io.File
import java.util.concurrent.Executors

/**
 * Which model file is selected, whether it is there, and the one LiteRT-LM runtime. All model work runs on one
 * "inference" thread ([dispatcher]); runs and the benchmark take turns on it.
 */
class ModelManager(private val context: Context, private val settings: Settings) {
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "mobileagent-inference") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val runtime = LlmRuntime(
        File(context.cacheDir, "litertlm").apply { mkdirs() },
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem,
    )

    /**
     * One user of the model at a time (agent run, benchmark, parity, load): their coroutines share the inference thread
     * and would otherwise interleave while a reply streams, e.g. a run reloading the engine under a benchmark.
     */
    val busy = Mutex()

    /** Runs [block] on the inference thread, after any other model work has finished. */
    fun exclusive(block: suspend () -> Unit) = scope.launch { busy.withLock { block() } }

    private val externalFiles: File get() = context.getExternalFilesDir(null)!!
    val modelsDir: File get() = ModelStore.dir(externalFiles).apply { mkdirs() }

    private val _entries = MutableStateFlow<List<ModelEntry>>(emptyList())
    val entries: StateFlow<List<ModelEntry>> = _entries.asStateFlow()
    private val _orphans = MutableStateFlow<List<File>>(emptyList())
    val orphans: StateFlow<List<File>> = _orphans.asStateFlow()

    /** Last checksum check: sidecar name to "ok" / "mismatch ..." / "checking". */
    private val _checks = MutableStateFlow<Map<String, String>>(emptyMap())
    val checks: StateFlow<Map<String, String>> = _checks.asStateFlow()

    fun refresh() {
        val list = ModelStore.list(externalFiles)
        _entries.value = list
        _orphans.value = ModelStore.orphans(externalFiles, list)
    }

    /** The sidecar the settings name, else model.json, else the first one. */
    fun selected(): ModelEntry? {
        refresh()
        val list = _entries.value
        val want = settings.current.modelSidecar
        return list.firstOrNull { it.sidecar.name == want } ?: list.firstOrNull()
    }

    /** Why this device cannot run [entry] at all (too little memory), or null. */
    fun memoryProblem(entry: ModelEntry?): String? =
        entry?.modelFile?.takeIf { it.isFile }?.let { LlmRuntime.memoryProblem(runtime.totalMemBytes, it.length()) }

    /** Loads the selected model with the settings' backends unless it is already loaded that way. Inference thread only. */
    fun ensureLoaded(): LlmRuntime.Loaded {
        val entry = selected() ?: throw IllegalStateException("No model: put the .litertlm file and its model.json sidecar in $modelsDir")
        entry.problem()?.let { throw IllegalStateException(it) }
        val s = settings.current
        runtime.loaded?.let { l ->
            if (runtime.isLoaded && l.file == entry.modelFile && l.textBackend == s.textBackend && l.visionBackend == s.visionBackend) return l
        }
        return runtime.load(entry.spec!!, entry.modelFile!!, s.textBackend, s.visionBackend)
    }

    /**
     * The engine a run starts with: a new one, unless the loaded engine matches the settings and has not served a
     * request yet (then its load counts for this run). Returns the engine and the load time this run paid (null: none).
     */
    fun engineForRun(): Pair<LlmRuntime.Loaded, Long?> {
        val before = runtime.loaded
        val l = ensureLoaded()
        return when {
            l !== before -> l to l.loadMs
            runtime.requestsSinceLoad == 0 -> l to l.loadMs
            else -> loadFresh().let { it to it.loadMs }
        }
    }

    /** A new engine for the selected model even if one is loaded (engine-per-step runs). Inference thread only. */
    fun loadFresh(): LlmRuntime.Loaded {
        val entry = selected() ?: throw IllegalStateException("No model: put the .litertlm file and its model.json sidecar in $modelsDir")
        entry.problem()?.let { throw IllegalStateException(it) }
        val s = settings.current
        return runtime.load(entry.spec!!, entry.modelFile!!, s.textBackend, s.visionBackend)
    }

    /** The selected model's spec and file without loading it (scripted debug runs need the grid and prompt only). */
    fun selectedSpec(): LlmRuntime.Loaded {
        val entry = selected() ?: throw IllegalStateException("No model sidecar in $modelsDir")
        val spec = entry.spec ?: throw IllegalStateException(entry.problem() ?: "bad sidecar")
        return LlmRuntime.Loaded(spec, entry.modelFile!!, "none", "none", 0)
    }

    /** Load from the UI or adb, off the caller's thread. */
    fun loadAsync() {
        exclusive { runCatching { ensureLoaded() }.onFailure { Log.e(LlmRuntime.TAG, "load failed: ${it.message}") } }
    }

    fun unloadAsync() {
        exclusive { runtime.close() }
    }

    fun verifyAsync(entry: ModelEntry) {
        val key = entry.sidecar.name
        val file = entry.modelFile ?: return
        _checks.value = _checks.value + (key to "checking")
        scope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ModelStore.sha256(file) }.fold(
                    { got -> if (entry.spec?.sha256 == null) "sha256 $got (sidecar has none)" else if (got.equals(entry.spec.sha256, true)) "ok" else "mismatch: $got" },
                    { "failed: ${it.message}" },
                )
            }
            Log.i(LlmRuntime.TAG, "checksum ${file.name}: $result")
            _checks.value = _checks.value + (key to result)
        }
    }
}
