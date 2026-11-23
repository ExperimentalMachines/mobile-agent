package org.experimentalmachines.mobileagent.host

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.experimentalmachines.mobileagent.BuildConfig
import org.experimentalmachines.mobileagent.Settings
import org.experimentalmachines.mobileagent.agent.InstalledApps
import org.experimentalmachines.mobileagent.image.ImagePrep
import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.model.ModelManager
import org.experimentalmachines.mobileagent.protocol.AgentFormat
import java.io.File

/**
 * The speed benchmark: N model calls on one fixed PNG from the app's files folder, with the agent's real prompt
 * (system prompt, goal, installed apps, empty history), timed by the app and by LiteRT-LM. Logcat gets one
 * `MOBILEAGENT_BENCH_CALL` line per call and a `MOBILEAGENT_BENCH` summary; the summary is also saved as JSON.
 */
object Bench {
    @Serializable
    data class Call(
        val i: Int,
        val engineMs: Long?,
        val totalMs: Long,
        val firstPieceMs: Long?,
        val lastPieceMs: Long?,
        val ttftMs: Long?,
        val prefillTokens: Int?,
        val prefillTokensPerS: Double?,
        val decodeTokens: Int?,
        val decodeTokensPerS: Double?,
        val pieces: Int,
        val thought: String,
        val text: String,
        val action: String?,
        val error: String? = null,
        val marksMs: Map<String, Long> = emptyMap(),
    )

    @Serializable
    data class Result(
        val device: String,
        val soc: String,
        val android: String,
        val litertLm: String,
        val modelFile: String,
        val modelBytes: Long,
        val backends: String,
        val loadMs: Long?,
        val newEnginePerCall: Boolean,
        val image: String,
        val imageSize: String,
        val modelInput: String,
        val resizeMs: Long,
        val pngEncodeMs: Long,
        val pngBytes: Int,
        val goal: String,
        /** Earlier steps in the prompt (the window shows the last 8). */
        val historySteps: Int = 0,
        val calls: List<Call>,
        val medianTotalMs: Long?,
        val medianTtftMs: Long?,
        val medianPrefillTokensPerS: Double?,
        val medianDecodeTokensPerS: Double?,
        val medianEngineMs: Long?,
        /** PowerManager thermal status (0 none ... 6 shutdown) and headroom (1.0 = throttling) before and after. */
        val thermalStart: Int? = null,
        val thermalEnd: Int? = null,
        val headroomStart: Float? = null,
        val headroomEnd: Float? = null,
        /** CPU policy and GPU frequencies (kHz / Hz) before and after, where the app may read them. */
        val freqStart: Map<String, Long> = emptyMap(),
        val freqEnd: Map<String, Long> = emptyMap(),
    )

    private val json = Json { encodeDefaults = true }
    private val pretty = Json { encodeDefaults = true; prettyPrint = true }

    /** Runs on the inference thread (blocks it). [image] is a file name in the app's external files folder. */
    suspend fun run(
        context: Context,
        models: ModelManager,
        settings: Settings,
        image: String,
        n: Int,
        goal: String,
        newEnginePerCall: Boolean,
        maxOutputTokens: Int,
        litertBenchmark: Boolean = true,
        historySteps: Int = 0,
    ): Result {
        val dir = context.getExternalFilesDir(null)!!
        val file = File(dir, image).takeIf { it.isFile } ?: File(dir, "bench/$image")
        require(file.isFile) { "No image $image in $dir" }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
            ?: error("Cannot decode $file")
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val thermalStart = power.currentThermalStatus
        val headroomStart = power.getThermalHeadroom(0)
        val freqStart = frequencies()
        var loaded = models.ensureLoaded()
        if (!litertBenchmark) loaded = models.runtime.load(loaded.spec, loaded.file, loaded.textBackend, loaded.visionBackend, benchmark = false)
        val spec = loaded.spec
        val prep = ImagePrep.toModelPng(bitmap, spec.imageHeight, spec.imageWidth)
        val apps = if (spec.promptVersion == "v4") InstalledApps.promptNames(InstalledApps.list(context)) else null
        val (system, user) = AgentFormat.messages(spec.promptVersion, goal, syntheticHistory(historySteps), apps)
        Log.i(TAG, "BENCH start: ${file.name} ${bitmap.width}x${bitmap.height} -> ${prep.width}x${prep.height} (resize ${prep.resizeMs} ms, png ${prep.encodeMs} ms, ${prep.png.size} B), $n calls, ${loaded.backends}, new engine per call: $newEnginePerCall")
        val calls = mutableListOf<Call>()
        for (i in 1..n) {
            var engineMs: Long? = null
            if (newEnginePerCall && i > 1) {
                val t0 = SystemClock.elapsedRealtime()
                loaded = models.runtime.load(loaded.spec, loaded.file, loaded.textBackend, loaded.visionBackend, litertBenchmark)
                engineMs = SystemClock.elapsedRealtime() - t0
            }
            val call = try {
                val r = models.runtime.generate(prep.png, system, user, maxOutputTokens, spec.enableThinking)
                val action = AgentFormat.parseAction(r.text)?.let(AgentFormat::actionToStr)
                Call(
                    i, engineMs, r.totalMs, r.firstPieceMs, r.lastPieceMs, r.bench?.ttftMs, r.bench?.prefillTokens, r.bench?.prefillTokensPerS,
                    r.bench?.decodeTokens, r.bench?.decodeTokensPerS, r.pieces, r.thought.take(300), r.text.take(300), action,
                    marksMs = r.bench?.marksMs.orEmpty(),
                )
            } catch (t: Throwable) {
                Call(i, engineMs, 0, null, null, null, null, null, null, null, 0, "", "", null, "${t.javaClass.simpleName}: ${t.message}")
            }
            calls += call
            Log.i(TAG, "MOBILEAGENT_BENCH_CALL " + json.encodeToString(Call.serializer(), call))
        }
        bitmap.recycle()
        val ok = calls.filter { it.error == null }
        val result = Result(
            device = "${Build.MANUFACTURER} ${Build.MODEL}", soc = Build.SOC_MODEL, android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            litertLm = BuildConfig.LITERT_LM_VERSION, modelFile = loaded.file.name, modelBytes = loaded.file.length(), backends = loaded.backends,
            loadMs = loaded.loadMs, newEnginePerCall = newEnginePerCall, image = file.name, imageSize = "${bitmap.width}x${bitmap.height}",
            modelInput = "${prep.width}x${prep.height}", resizeMs = prep.resizeMs, pngEncodeMs = prep.encodeMs, pngBytes = prep.png.size, goal = goal,
            historySteps = historySteps,
            calls = calls,
            medianTotalMs = median(ok.map { it.totalMs.toDouble() })?.toLong(),
            medianTtftMs = median(ok.mapNotNull { it.ttftMs?.toDouble() })?.toLong(),
            medianPrefillTokensPerS = median(ok.mapNotNull { it.prefillTokensPerS }),
            medianDecodeTokensPerS = median(ok.mapNotNull { it.decodeTokensPerS }),
            medianEngineMs = median(ok.mapNotNull { it.engineMs?.toDouble() })?.toLong(),
            thermalStart = thermalStart, thermalEnd = power.currentThermalStatus,
            headroomStart = headroomStart.takeIf { it.isFinite() }, headroomEnd = power.getThermalHeadroom(0).takeIf { it.isFinite() },
            freqStart = freqStart, freqEnd = frequencies(),
        )
        val out = File(dir, "bench").apply { mkdirs() }
        File(out, "bench-${System.currentTimeMillis()}.json").writeText(pretty.encodeToString(Result.serializer(), result))
        Log.i(TAG, "MOBILEAGENT_BENCH " + json.encodeToString(Result.serializer(), result.copy(calls = emptyList())))
        return result
    }

    /** The runtime's rendering of the agent prompt, saved to render.json (to compare with the training template). */
    fun render(context: Context, models: ModelManager, goal: String): File {
        val loaded = models.ensureLoaded()
        val spec = loaded.spec
        val apps = if (spec.promptVersion == "v4") InstalledApps.promptNames(InstalledApps.list(context)) else null
        val (system, user) = AgentFormat.messages(spec.promptVersion, goal, emptyList(), apps)
        val png = tinyPng()
        val (preface, turn) = models.runtime.render(png, system, user, spec.enableThinking)
        val f = File(context.getExternalFilesDir(null), "render.json")
        f.writeText(pretty.encodeToString(RenderOut.serializer(), RenderOut(system, user, preface, turn)))
        Log.i(TAG, "MOBILEAGENT_RENDER ${f.absolutePath}")
        return f
    }

    @Serializable
    data class RenderOut(val system: String, val user: String, val preface: String, val userTurn: String)

    private fun tinyPng(): ByteArray {
        val b = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    /** [n] plausible earlier steps (intent and action), so a sustained bench sees a full history window. */
    private fun syntheticHistory(n: Int): List<AgentFormat.Step> {
        val sample = listOf(
            AgentFormat.Step("Open the Clock app", """{"action_type":"open_app","app_name":"Clock"}"""),
            AgentFormat.Step("Click on the Alarm tab", """{"action_type":"click","point_2d":[100,60]}"""),
            AgentFormat.Step("Click on the + button", """{"action_type":"click","point_2d":[500,898]}"""),
            AgentFormat.Step("Scroll down to see more", """{"action_type":"scroll","direction":"down"}"""),
            AgentFormat.Step("Type the label", """{"action_type":"input_text","text":"Morning run"}"""),
        )
        return List(n) { sample[it % sample.size] }
    }

    /** scaling_cur_freq of each CPU policy and the Adreno GPU clock, where SELinux lets the app read them. */
    private fun frequencies(): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }.orEmpty().sortedBy { it.name }.forEach { p ->
            runCatching { File(p, "scaling_cur_freq").readText().trim().toLong() }.getOrNull()?.let { out["cpu_${p.name}_khz"] = it }
        }
        runCatching { File("/sys/class/kgsl/kgsl-3d0/gpuclk").readText().trim().toLong() }.getOrNull()?.let { out["gpu_hz"] = it }
        return out
    }

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private const val TAG = LlmRuntime.TAG
}
