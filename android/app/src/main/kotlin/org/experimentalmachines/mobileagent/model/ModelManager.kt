package org.experimentalmachines.mobileagent.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val runtime = LlmRuntime(File(context.cacheDir, "litertlm").apply { mkdirs() })

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

    /** A new engine for the selected model even if one is loaded (engine-per-step runs). Inference thread only. */
    fun loadFresh(): LlmRuntime.Loaded {
        val entry = selected() ?: throw IllegalStateException("No model: put the .litertlm file and its model.json sidecar in $modelsDir")
        entry.problem()?.let { throw IllegalStateException(it) }
        val s = settings.current
        return runtime.load(entry.spec!!, entry.modelFile!!, s.textBackend, s.visionBackend)
    }

    /** Load from the UI or adb, off the caller's thread. */
    fun loadAsync() {
        scope.launch { runCatching { ensureLoaded() }.onFailure { Log.e(LlmRuntime.TAG, "load failed: ${it.message}") } }
    }

    fun unloadAsync() {
        scope.launch { runtime.close() }
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
