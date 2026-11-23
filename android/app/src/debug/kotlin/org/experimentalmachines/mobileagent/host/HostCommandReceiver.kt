package org.experimentalmachines.mobileagent.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.experimentalmachines.mobileagent.BuildConfig
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.Settings
import org.experimentalmachines.mobileagent.agent.AgentController
import org.experimentalmachines.mobileagent.model.LlmRuntime

/**
 * Test hooks over adb, in debug builds only (this file and its manifest entry live in the debug source set). The
 * receiver needs DUMP, which adb's shell holds; some privileged system apps hold it too, and it can be granted with
 * `pm grant`, so a debug build is a test build. The hooks cannot switch the safety guard off: there is no `confirm`
 * override, and a guard question still needs ALLOW / SKIP. Every command (`-a org.experimentalmachines.mobileagent.<NAME>`):
 *
 *   RUN_GOAL --es goal "Open the Clock app" [--ei max_steps 3] [--ez engine_per_step false]
 *            [--ez enter true|false] [--ez verbose_logs true|false]
 *            [--es script '["<reply 1>", {"action_type":...}, ...]']   (debug builds: fixed replies instead of the model)
 *   EXEC --es action '{"action_type":"click","point_2d":[500,500]}' [--ez enter B]   (debug builds: one action, no guard)
 *   LOGS --ez verbose true|false        (goal / intents / actions / answers in the logcat lines)
 *   STOP | PAUSE | RESUME | ALLOW | SKIP  (ALLOW / SKIP answer the safety guard's question)
 *   LOAD | UNLOAD                       (backends from the settings; override with --es backend gpu|cpu --es vision_backend gpu|cpu)
 *   BENCH --es image bench.png [--ei n 5] [--es goal "..."] [--ez new_engine false] [--ei max_tokens 512]
 *         [--ez litert_benchmark true]  (false: engine without LiteRT-LM's benchmark counters; app timings only)
 *         [--es filter_thought true|false|default]  (LiteRT-LM's filterChannelContentFromKvCache for later requests)
 *         [--ei history N]  (N earlier steps in the prompt, as in a long run; the window shows the last 8)
 *   RENDER [--es goal "..."]            (the runtime's rendering of the prompt, saved to render.json)
 *   WINDOWS                             (logs the windows the accessibility service sees)
 *   PARITY --es rows parity/rows.jsonl [--es out parity/out.jsonl] [--ei limit N] [--ez new_engine_per_row false]
 *          (rows {id, system, user, image}; the agent's exact model call per row; writes {id, content, reasoning, seconds})
 *
 *   adb shell am broadcast -n org.experimentalmachines.mobileagent/.host.HostCommandReceiver \
 *       -a org.experimentalmachines.mobileagent.BENCH --es image bench.png --ei n 5
 */
class HostCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = MobileAgentApp.get(context)
        val action = intent.action?.substringAfterLast('.') ?: return
        Log.i(TAG, "host command $action")
        val backend = intent.getStringExtra("backend")
        val vision = intent.getStringExtra("vision_backend")
        if (backend != null || vision != null) {
            app.settings.update { s ->
                s.copy(
                    textBackend = backend?.takeIf { it == "cpu" || it == "gpu" } ?: s.textBackend,
                    visionBackend = vision?.takeIf { it == "cpu" || it == "gpu" } ?: s.visionBackend,
                )
            }
        }
        val models = app.models
        when (action) {
            "LOAD" -> models.loadAsync()
            "UNLOAD" -> models.unloadAsync()
            "BENCH" -> {
                val image = intent.getStringExtra("image") ?: "bench.png"
                val n = intent.getIntExtra("n", 5)
                val goal = intent.getStringExtra("goal") ?: "Open the Clock app"
                val fresh = intent.getBooleanExtra("new_engine", false)
                val maxTokens = intent.getIntExtra("max_tokens", 512)
                val litertBench = intent.getBooleanExtra("litert_benchmark", true)
                val historySteps = intent.getIntExtra("history", 0)
                if (intent.hasExtra("filter_thought")) {
                    models.runtime.filterThoughtFromKvCache = intent.getStringExtra("filter_thought")?.toBooleanStrictOrNull()
                }
                models.exclusive {
                    runCatching { Bench.run(context.applicationContext, models, app.settings, image, n, goal, fresh, maxTokens, litertBench, historySteps) }
                        .onFailure { Log.e(TAG, "MOBILEAGENT_BENCH_ERROR ${it.javaClass.simpleName}: ${it.message}", it) }
                }
            }
            "PARITY" -> {
                val rows = intent.getStringExtra("rows") ?: "parity/rows.jsonl"
                val outName = intent.getStringExtra("out") ?: "parity/out-${System.currentTimeMillis()}.jsonl"
                val limit = intent.getIntExtra("limit", Int.MAX_VALUE)
                val perRow = intent.getBooleanExtra("new_engine_per_row", false)
                val maxTokens = intent.getIntExtra("max_tokens", AgentController.MAX_OUTPUT_TOKENS)
                models.exclusive {
                    runCatching { Parity.run(context.applicationContext, models, rows, outName, limit, perRow, maxTokens) }
                        .onFailure { Log.e(TAG, "MOBILEAGENT_PARITY_ERROR ${it.javaClass.simpleName}: ${it.message}", it) }
                }
            }
            "RENDER" -> models.exclusive {
                runCatching { Bench.render(context.applicationContext, models, intent.getStringExtra("goal") ?: "Open the Clock app") }
                    .onFailure { Log.e(TAG, "render failed: ${it.message}", it) }
            }
            else -> handleRun(app, action, intent)
        }
    }

    private fun handleRun(app: MobileAgentApp, action: String, intent: Intent) {
        val c = app.controller
        when (action) {
            "RUN_GOAL" -> {
                val goal = intent.getStringExtra("goal")
                if (goal.isNullOrBlank()) return Log.w(TAG, "RUN_GOAL needs --es goal \"...\"").let { }
                val s = app.settings.current
                val script = intent.getStringExtra("script")?.let { raw ->
                    if (!BuildConfig.DEBUG) return Log.w(TAG, "RUN_GOAL script: debug builds only").let { }
                    runCatching { Json.parseToJsonElement(raw).jsonArray.map { e -> (e as? JsonPrimitive)?.content ?: e.toString() } }
                        .getOrElse { return Log.w(TAG, "RUN_GOAL script must be a JSON array of reply strings or action objects: ${it.message}").let { } }
                }
                val o = AgentController.Options(
                    maxSteps = intent.getIntExtra("max_steps", s.maxSteps).coerceIn(1, Settings.MAX_STEPS_CAP),
                    enginePerStep = intent.getBooleanExtra("engine_per_step", s.enginePerStep),
                    confirmRisky = s.confirmRisky,
                    enterAfterTyping = intent.getBooleanExtra("enter", s.enterAfterTyping),
                    verboseLogs = intent.getBooleanExtra("verbose_logs", s.verboseLogs),
                    script = script,
                )
                if (!c.start(goal, o)) Log.w(TAG, "RUN_GOAL ignored: a run is going (or the goal is empty)")
            }
            "EXEC" -> {
                if (!BuildConfig.DEBUG) return Unit.also { Log.w(TAG, "EXEC: debug builds only") }
                val json = intent.getStringExtra("action") ?: return Unit.also { Log.w(TAG, "EXEC needs --es action '<json>'") }
                c.exec(json, intent.getBooleanExtra("enter", app.settings.current.enterAfterTyping))
            }
            "LOGS" -> app.settings.update { it.copy(verboseLogs = intent.getBooleanExtra("verbose", it.verboseLogs)) }
                .also { Log.i(TAG, "verbose logs: ${app.settings.current.verboseLogs}") }
            "WINDOWS" -> org.experimentalmachines.mobileagent.agent.AgentService.instance?.describeWindows()?.forEach { Log.i(TAG, "window $it") }
                ?: Log.w(TAG, "accessibility service not connected")
            "STOP" -> c.stop()
            "ALLOW" -> c.decide(org.experimentalmachines.mobileagent.agent.Decision.ALLOW)
            "SKIP" -> c.decide(org.experimentalmachines.mobileagent.agent.Decision.SKIP)
            "PAUSE" -> c.setPaused(true)
            "RESUME" -> c.setPaused(false)
            else -> Log.w(TAG, "unknown host command $action")
        }
    }

    private companion object {
        const val TAG = LlmRuntime.TAG
    }
}
