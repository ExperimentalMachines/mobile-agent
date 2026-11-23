package org.experimentalmachines.mobileagent.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.agent.AgentController
import org.experimentalmachines.mobileagent.model.LlmRuntime

/**
 * Test hooks over adb. Only adb's shell can send these: the manifest guards the receiver with DUMP, which apps
 * cannot hold. Every command (`-a org.experimentalmachines.mobileagent.<NAME>`):
 *
 *   RUN_GOAL --es goal "Open the Clock app" [--ei max_steps 3] [--ez engine_per_step false] [--ez confirm false]
 *   STOP | PAUSE | RESUME | ALLOW | SKIP  (ALLOW / SKIP answer the safety guard's question)
 *   LOAD | UNLOAD                       (backends from the settings; override with --es backend gpu|cpu --es vision_backend gpu|cpu)
 *   BENCH --es image bench.png [--ei n 5] [--es goal "..."] [--ez new_engine false] [--ei max_tokens 512]
 *         [--ez litert_benchmark true]  (false: engine without LiteRT-LM's benchmark counters; app timings only)
 *         [--es filter_thought true|false|default]  (LiteRT-LM's filterChannelContentFromKvCache for later requests)
 *   RENDER [--es goal "..."]            (the runtime's rendering of the prompt, saved to render.json)
 *   WINDOWS                             (logs the windows the accessibility service sees)
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
                if (intent.hasExtra("filter_thought")) {
                    models.runtime.filterThoughtFromKvCache = intent.getStringExtra("filter_thought")?.toBooleanStrictOrNull()
                }
                models.scope.launch {
                    runCatching { Bench.run(context.applicationContext, models, app.settings, image, n, goal, fresh, maxTokens, litertBench) }
                        .onFailure { Log.e(TAG, "MOBILEAGENT_BENCH_ERROR ${it.javaClass.simpleName}: ${it.message}", it) }
                }
            }
            "RENDER" -> models.scope.launch {
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
                val o = AgentController.Options(
                    maxSteps = intent.getIntExtra("max_steps", s.maxSteps).coerceIn(1, org.experimentalmachines.mobileagent.Settings.MAX_STEPS_CAP),
                    enginePerStep = intent.getBooleanExtra("engine_per_step", s.enginePerStep),
                    confirmRisky = intent.getBooleanExtra("confirm", s.confirmRisky),
                )
                if (!c.start(goal, o)) Log.w(TAG, "RUN_GOAL ignored: a run is going (or the goal is empty)")
            }
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
