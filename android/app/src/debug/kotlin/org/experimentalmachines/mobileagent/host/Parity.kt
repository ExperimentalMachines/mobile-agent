package org.experimentalmachines.mobileagent.host

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.model.ModelManager
import java.io.File

/**
 * Parity rows through the agent's exact model call (debug builds, adb `PARITY`): a fresh engine on the GPU (text and
 * vision), thinking on, greedy, at most 512 new tokens, thought filtering off, one new conversation per row; the
 * row's PNG bytes go in unchanged (the set's PNGs are already at the model's 576 x 1280 grid). Output: one JSON line
 * per row, `{id, content, reasoning, seconds}` plus timings, written as it goes; logcat gets progress and a
 * `MOBILEAGENT_PARITY_DONE` summary.
 */
object Parity {
    @Serializable
    data class Row(
        val id: String,
        val content: String,
        val reasoning: String,
        val seconds: Double,
        val firstPieceSeconds: Double?,
        val prefillTokens: Int?,
        val decodeTokens: Int?,
        val error: String? = null,
    )

    @Serializable
    data class Done(
        val rows: Int, val errors: Int, val out: String, val backends: String, val newEnginePerRow: Boolean, val loadMs: Long,
        val medianSeconds: Double?, val thermalStart: Int, val thermalEnd: Int, val minutes: Double,
    )

    private val json = Json { encodeDefaults = true }

    fun run(context: Context, models: ModelManager, rowsPath: String, outPath: String, limit: Int, newEnginePerRow: Boolean, maxTokens: Int) {
        val dir = context.getExternalFilesDir(null)!!
        val rowsFile = File(dir, rowsPath)
        require(rowsFile.isFile) { "no rows file $rowsFile" }
        val out = File(dir, outPath).apply { parentFile?.mkdirs() }
        val power = context.getSystemService(PowerManager::class.java)
        val thermalStart = power.currentThermalStatus
        val t0 = SystemClock.elapsedRealtime()
        val base = models.selectedSpec()
        var loaded = models.runtime.load(base.spec, base.file, "gpu", "gpu")
        val loadMs = loaded.loadMs
        val lines = rowsFile.readLines().filter { it.isNotBlank() }.take(limit)
        Log.i(TAG, "MOBILEAGENT_PARITY start: ${lines.size} rows from $rowsFile -> $out, ${loaded.backends}, load $loadMs ms, new engine per row: $newEnginePerRow")
        val times = mutableListOf<Double>()
        var errors = 0
        out.bufferedWriter().use { w ->
            lines.forEachIndexed { i, ln ->
                val o = Json.parseToJsonElement(ln).jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                val row = try {
                    if (newEnginePerRow && i > 0) loaded = models.runtime.load(loaded.spec, loaded.file, "gpu", "gpu")
                    val png = File(rowsFile.parentFile, o["image"]!!.jsonPrimitive.content).readBytes()
                    val r = kotlinx.coroutines.runBlocking {
                        models.runtime.generate(png, o["system"]!!.jsonPrimitive.content, o["user"]!!.jsonPrimitive.content, maxTokens, loaded.spec.enableThinking)
                    }
                    times += r.totalMs / 1000.0
                    Row(id, r.text, r.thought, r.totalMs / 1000.0, r.firstPieceMs?.div(1000.0), r.bench?.prefillTokens, r.bench?.decodeTokens)
                } catch (t: Throwable) {
                    errors++
                    Row(id, "", "", 0.0, null, null, null, "${t.javaClass.simpleName}: ${t.message}")
                }
                w.write(json.encodeToString(Row.serializer(), row))
                w.newLine()
                w.flush()
                if ((i + 1) % 10 == 0 || i == lines.size - 1) Log.i(TAG, "MOBILEAGENT_PARITY ${i + 1}/${lines.size}")
            }
        }
        val sorted = times.sorted()
        val done = Done(
            lines.size, errors, out.absolutePath, loaded.backends, newEnginePerRow, loadMs, sorted.getOrNull(sorted.size / 2),
            thermalStart, power.currentThermalStatus, (SystemClock.elapsedRealtime() - t0) / 60000.0,
        )
        Log.i(TAG, "MOBILEAGENT_PARITY_DONE " + json.encodeToString(Done.serializer(), done))
    }

    private const val TAG = LlmRuntime.TAG
}
