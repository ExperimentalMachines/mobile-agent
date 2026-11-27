package org.experimentalmachines.mobileagent.agent

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.experimentalmachines.mobileagent.BuildConfig
import org.experimentalmachines.mobileagent.Settings
import org.experimentalmachines.mobileagent.image.ImagePrep
import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.model.ModelManager
import org.experimentalmachines.mobileagent.protocol.AgentFormat
import org.experimentalmachines.mobileagent.run.RunOverlay
import org.experimentalmachines.mobileagent.run.RunService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RunState {
    data object Idle : RunState

    data class Running(
        val goal: String,
        val step: Int,
        val maxSteps: Int,
        /** What the loop is doing now: "Looking", "Thinking", "Acting", "Waiting for the screen". */
        val phase: String,
        val intent: String?,
        val action: String?,
        val paused: Boolean,
        /** Set while the safety guard waits for the user: the reason. */
        val confirm: String?,
    ) : RunState

    data class Finished(
        val runId: String,
        val goal: String,
        val status: String,
        val answer: String?,
        val steps: Int,
        val secondsPerStep: Double?,
        val error: String?,
    ) : RunState
}

/** The user's answer to a guarded step. */
enum class Decision { ALLOW, SKIP, STOP }

/**
 * Runs a goal the way the benchmarks ran the model (research repo, eval/androidworld/lfm_agent.py): each step one
 * screenshot resized to the model's grid, the v4 prompt with the last 8 steps, greedy decoding with thinking on, one
 * retry when the reply cannot be parsed, the intent from the thought, the action executed as AndroidWorld executes it
 * (actuation.py), then 1 s and a quiet screen before the next screenshot. `status` ends the run, `answer` ends it
 * after showing the answer, and the step limit ends it otherwise.
 */
class AgentController(
    private val context: Context,
    private val settings: Settings,
    private val models: ModelManager,
) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()
    private val paused = MutableStateFlow(false)
    @Volatile private var job: Job? = null
    @Volatile private var pendingDecision: CompletableDeferred<Decision>? = null
    val runs = RunStore(File(context.getExternalFilesDir(null), "runs"))
    val overlay = RunOverlay(context, this)
    private val line = Json { encodeDefaults = true }

    val running: Boolean get() = job?.isActive == true

    data class Options(
        val maxSteps: Int,
        val enginePerStep: Boolean,
        val confirmRisky: Boolean,
        val enterAfterTyping: Boolean,
        /** Goal, intents, actions and answers in the logcat lines (redacted secrets excepted). */
        val verboseLogs: Boolean,
        /** Debug builds only: fixed model replies, one per step, instead of the model (tests the loop itself). */
        val script: List<String>? = null,
    )

    /** Where step 1 begins when the run is started from Mobile Agent's own screen. */
    sealed interface StartFrom {
        /** The default launcher: what every training and benchmark episode starts on. */
        data object Home : StartFrom
        /** The app the user came from, as they left it ("Continue in ..."). */
        data class App(val packageName: String, val label: String) : StartFrom
    }

    fun start(goal: String, options: Options? = null, from: StartFrom = StartFrom.Home): Boolean {
        val g = goal.trim()
        if (g.isEmpty() || running) return false
        val s = settings.current
        val o = options ?: Options(s.maxSteps, s.enginePerStep, s.confirmRisky, s.enterAfterTyping, s.verboseLogs)
        paused.value = false
        _state.value = RunState.Running(g, 0, o.maxSteps, "Starting", null, null, false, null)
        RunService.start(context)
        overlay.show()
        job = models.exclusive { run(g, o, from) }
        return true
    }

    /**
     * Leaves Mobile Agent's own screen before step 1: the model has never seen it, and on it the model taps our
     * buttons (11 of 12 goals tapped where Start sits; during a run, Pause and Stop are there). A run started while
     * another app is in front (notification, debug hook) begins on that app. Returns what happened, for run.json.
     */
    private suspend fun prepareStart(service: AgentService, from: StartFrom): String {
        val front = service.frontPackage()
        if (from is StartFrom.App) {
            if (front == from.packageName) return "app: ${from.packageName}"
            if (service.resumeApp(from.packageName) && awaitFront(service) { it == from.packageName }) {
                service.awaitQuiet()
                return "app: ${from.packageName}"
            }
            Log.w(TAG, "could not return to ${from.packageName}; starting from the home screen")
        } else if (front != null && front != context.packageName) {
            return "as is: $front"
        }
        val homes = Launchers.homePackages(context)
        repeat(2) {
            (AgentService.instance ?: service).home()
            if (awaitFront(service) { it in homes }) {
                (AgentService.instance ?: service).awaitQuiet()
                return "home: ${(AgentService.instance ?: service).frontPackage()}"
            }
        }
        val now = (AgentService.instance ?: service).frontPackage()
        // The model must never start on our own controls (it taps them; Stop is among them).
        if (now == null || now == context.packageName) {
            throw IllegalStateException("Could not leave Mobile Agent for the home screen. Go to your home screen and start the task from the notification, or try again.")
        }
        return "home (not confirmed; in front: $now)"
    }

    /** Polls the active window until [ok] holds for its package, up to [START_WAIT_MS] (survives a service rebind). */
    private suspend fun awaitFront(service: AgentService, ok: (String) -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + START_WAIT_MS
        while (SystemClock.elapsedRealtime() < end) {
            (AgentService.instance ?: service).frontPackage()?.let { if (ok(it)) return true }
            delay(100)
        }
        return false
    }

    fun stop() {
        pendingDecision?.complete(Decision.STOP)
        job?.cancel()
    }

    fun setPaused(p: Boolean) {
        paused.value = p
        (state.value as? RunState.Running)?.let { _state.value = it.copy(paused = p) }
    }

    fun decide(d: Decision) {
        pendingDecision?.complete(d)
    }

    fun dismissResult() {
        if (!running) _state.value = RunState.Idle
    }

    private fun update(change: (RunState.Running) -> RunState.Running) {
        (state.value as? RunState.Running)?.let { _state.value = change(it) }
    }

    private suspend fun awaitResumed() {
        if (paused.value) {
            update { it.copy(phase = "Paused") }
            paused.first { !it }
        }
    }

    private suspend fun run(goal: String, o: Options, from: StartFrom = StartFrom.Home) {
        val started = System.currentTimeMillis()
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(started))
        val s = settings.current
        val out = AgentLoop.Outcome()
        var error: String? = null
        var loaded: LlmRuntime.Loaded? = null
        var firstLoadMs: Long? = null
        var appNames = emptyList<String>()
        var startedFrom: String? = null
        try {
            val service = AgentService.instance ?: throw IllegalStateException("Turn on Mobile Agent in Accessibility settings first.")
            update { it.copy(phase = "Loading the model") }
            if (o.script != null) {
                loaded = models.selectedSpec() // scripted replies: the model's grid and prompt, no engine
            } else {
                // A new engine for every run (LiteRT-LM #3165: a conversation can inherit state from earlier ones on the
                // same engine), unless the loaded one has not served a request yet; engine-per-step makes one per step.
                val l = models.engineForRun()
                loaded = l.first
                firstLoadMs = l.second
            }
            val spec = loaded.spec
            appNames = InstalledApps.promptNames(InstalledApps.list(context))
            Log.i(TAG, "run $runId: ${appNames.size} installed apps in the prompt")
            update { it.copy(phase = if (from is StartFrom.App) "Opening ${from.label}" else "Going to the home screen") }
            startedFrom = prepareStart(AgentService.instance ?: service, from)
            Log.i(TAG, "run $runId starts from $startedFrom")
            val env = Env(service, o, runId, spec, Launchers.homePackages(context))
            val config = AgentLoop.Config(
                goal = goal, promptVersion = spec.promptVersion, apps = if (spec.promptVersion == "v4") appNames else null,
                grid = spec.imageHeight to spec.imageWidth, maxSteps = o.maxSteps, enginePerStep = o.enginePerStep && o.script == null,
                confirmRisky = o.confirmRisky, enterAfterTyping = o.enterAfterTyping, firstEngineMs = firstLoadMs,
            )
            AgentLoop(env, SystemClock::elapsedRealtime).run(config, out) { rec ->
                Log.i(TAG, "MOBILEAGENT_STEP " + line.encodeToString(StepLine.serializer(), StepLine.of(rec, o.verboseLogs)))
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                out.status = "stopped"
            } else {
                out.status = "error"
                error = LlmRuntime.describe(t)
                Log.e(TAG, "run failed", t)
            }
        } finally {
            pendingDecision = null
            if (o.enginePerStep) withContext(kotlinx.coroutines.NonCancellable) { models.runtime.close() }
        }
        val red = out.redactor
        val l = loaded
        val record = RunRecord(
            runId = runId, goal = red.scrub(goal)!!, model = l?.spec?.name ?: "-", modelFile = l?.file?.name ?: "-",
            imageGrid = l?.spec?.let { "${it.imageHeight}x${it.imageWidth}" } ?: "-", promptVersion = l?.spec?.promptVersion ?: "-",
            backends = if (o.script != null) "scripted (no model)" else l?.backends ?: "${s.textBackend}/${s.visionBackend}",
            enginePerStep = o.enginePerStep, enterAfterTyping = o.enterAfterTyping,
            loadMs = firstLoadMs, maxSteps = o.maxSteps, status = out.status, answer = red.scrub(out.answer), error = red.scrub(error),
            device = "${Build.MANUFACTURER} ${Build.MODEL}", soc = Build.SOC_MODEL, android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            litertLm = BuildConfig.LITERT_LM_VERSION, installedApps = appNames, screenGrid = out.screenGrid, gridMatches = out.gridMatches,
            startedFrom = startedFrom,
            // Scrubbed again: a secret typed late in the run may appear in earlier steps' intents and replies.
            steps = out.steps.map { AgentLoop.scrub(it, red) }, startedAtMs = started, endedAtMs = System.currentTimeMillis(),
        )
        val file = runs.save(record)
        runs.prune(settings.current.keepRuns)
        val perStep = record.steps.takeIf { it.isNotEmpty() }?.let { st -> st.sumOf { it.stepMs } / 1000.0 / st.size }
        Log.i(TAG, "MOBILEAGENT_RESULT " + line.encodeToString(ResultLine.serializer(), ResultLine.of(record, file, perStep, o.verboseLogs)))
        _state.value = RunState.Finished(runId, record.goal, record.status, record.answer, record.steps.size, perStep, record.error)
    }

    /** The loop's view of the phone: the accessibility service, the overlay, the model, the user. */
    private inner class Env(
        @Suppress("UNUSED_PARAMETER") firstService: AgentService,
        private val o: Options,
        private val runId: String,
        private val spec: org.experimentalmachines.mobileagent.model.ModelSpec,
        private val homes: Set<String>,
    ) : AgentLoop.Env {
        /**
         * The current accessibility service. Android can rebind it in the middle of a run (seen when another app's
         * permission changed on the test phone); the old instance is then dead, so every call looks it up again.
         */
        private val service: AgentService
            get() = AgentService.instance ?: kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(RECONNECT_MS) {
                    while (AgentService.instance == null) delay(100)
                    AgentService.instance
                }
            } ?: throw IllegalStateException("The accessibility service disconnected during the run.")

        override suspend fun awaitResumed() = this@AgentController.awaitResumed()
        override fun screenUnavailable(): String? = this@AgentController.screenUnavailable()
        override fun update(change: (RunState.Running) -> RunState.Running) = this@AgentController.update(change)

        override suspend fun observe(step: Int): AgentLoop.Screen {
            val s0 = SystemClock.elapsedRealtime()
            val shot = StallWatch.phase("screenshot (step $step)", 30_000) { overlay.whileHidden { service.screenshot() } }
            val shotMs = SystemClock.elapsedRealtime() - s0
            val prep = ImagePrep.toModelPng(shot, spec.imageHeight, spec.imageWidth)
            val thumb = grayThumb(shot)
            saveThumb(shot, File(runs.runDir(runId), "step-%02d.jpg".format(step)))
            val screen = AgentLoop.Screen(prep.png, shot.width, shot.height, thumb, shotMs, prep.resizeMs, prep.encodeMs)
            shot.recycle()
            return screen
        }

        override fun foreground(): String? = service.foreground()
        override fun isHome(pkg: String?): Boolean = pkg != null && pkg in homes
        override fun live(x: Int?, y: Int?): Guard.Live = service.live(x, y)

        override suspend fun newEngine(): Long {
            val l = models.runtime.loaded ?: error("no engine")
            return models.runtime.load(l.spec, l.file, l.textBackend, l.visionBackend).loadMs
        }

        override suspend fun reply(png: ByteArray, system: String, user: String, step: Int, onThought: (String) -> Unit): LlmRuntime.Reply =
            o.script?.let { scripted(it, step) } ?: StallWatch.phase("model (step $step)", 300_000) {
                models.runtime.generate(png, system, user, MAX_OUTPUT_TOKENS, spec.enableThinking) { thought, _ -> onThought(thought) }
            }

        override suspend fun ask(reason: String): Decision? = this@AgentController.ask(reason)
        override suspend fun <T> hidden(block: suspend () -> T): T = StallWatch.phase("act", 30_000) { overlay.whileHidden { block() } }
        override suspend fun execute(a: AgentFormat.Action, w: Int, h: Int, tap: Pair<Int, Int>?, enterAfterTyping: Boolean): String =
            this@AgentController.execute(service, a, w, h, tap, enterAfterTyping)

        override fun showTap(x: Int, y: Int) {
            if (settings.current.showTaps) service.showTap(x, y)
        }

        override suspend fun settle() {
            delay(WAIT_AFTER_ACTION_MS)
            service.awaitQuiet()
        }

        override fun log(line: String) {
            Log.i(TAG, line)
        }
    }

    /** A scripted reply for [step] (debug runs); past the script's end the run is told to finish as infeasible. */
    private fun scripted(script: List<String>, step: Int): LlmRuntime.Reply {
        val text = script.getOrNull(step - 1) ?: """{"action_type":"status","goal_status":"infeasible"}"""
        return LlmRuntime.Reply(text, "Scripted step $step", 0, null, null, 0, null)
    }

    /**
     * Debug builds only (adb `EXEC`): one action JSON through the same executor a run uses, on the current screen,
     * without the model and without the guard. Logs `MOBILEAGENT_EXEC`.
     */
    fun exec(json: String, enterAfterTyping: Boolean) {
        if (running) {
            Log.w(TAG, "EXEC ignored: a run is going")
            return
        }
        models.exclusive {
            val service = AgentService.instance ?: return@exclusive Unit.also { Log.w(TAG, "EXEC: accessibility service not connected") }
            val action = AgentFormat.parseAction(json) ?: return@exclusive Unit.also { Log.w(TAG, "MOBILEAGENT_EXEC unparseable: $json") }
            val shot = service.screenshot()
            val (w, h) = shot.width to shot.height
            shot.recycle()
            val tap = if (action.type in AgentFormat.POINT_TYPES) AgentFormat.normToPx(action.x!!, action.y!!, w, h) else null
            val t0 = SystemClock.elapsedRealtime()
            val outcome = runCatching { execute(service, action, w, h, tap, enterAfterTyping) }.getOrElse { "failed: ${it.message}" }
            if (tap != null && settings.current.showTaps) service.showTap(tap.first, tap.second)
            Log.i(TAG, "MOBILEAGENT_EXEC " + line.encodeToString(ExecLine.serializer(), ExecLine(AgentFormat.actionToStr(action), outcome, tap?.toList(), w, h, enterAfterTyping, SystemClock.elapsedRealtime() - t0)))
        }
    }

    @Serializable
    data class ExecLine(val action: String, val outcome: String, val tap: List<Int>?, val screenWidth: Int, val screenHeight: Int, val enterAfterTyping: Boolean, val ms: Long)

    /** Pauses the run until the user answers on the floating control (or [CONFIRM_TIMEOUT_MS] passes: null). */
    private suspend fun ask(reason: String): Decision? {
        val d = CompletableDeferred<Decision>()
        pendingDecision = d
        update { it.copy(confirm = reason, phase = "Waiting for you") }
        overlay.show()
        val result = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { d.await() }
        pendingDecision = null
        update { it.copy(confirm = null) }
        return result
    }

    /** One action, executed as android_world/env/actuation.py does; returns what happened in a few words. */
    private suspend fun execute(service: AgentService, a: AgentFormat.Action, w: Int, h: Int, tap: Pair<Int, Int>?, enterAfterTyping: Boolean): String =
        when (a.type) {
            "click" -> {
                service.tap(tap!!.first, tap.second, TAP_MS)
                "tapped (${tap.first},${tap.second})"
            }
            "long_press" -> {
                service.tap(tap!!.first, tap.second, LONG_PRESS_MS) // adb: input swipe x y x y 1000
                "long-pressed (${tap.first},${tap.second})"
            }
            "input_text" -> {
                val text = a.text.orEmpty()
                if (text.isEmpty()) {
                    "no text (nothing typed)"
                } else {
                    val typed = service.typeText(text)
                    if (enterAfterTyping && typed == "typed") "$typed, ${service.pressEnter()}" else typed
                }
            }
            "scroll" -> {
                // Whole screen: from the centre to the edge opposite the content direction (scroll down -> finger up).
                val cx = w / 2
                val cy = h / 2
                val (ex, ey) = when (a.direction) {
                    "down" -> cx to 0
                    "up" -> cx to h - 1
                    "right" -> 0 to cy
                    else -> w - 1 to cy
                }
                service.swipe(cx, cy, ex, ey, SWIPE_MS)
                "scrolled ${a.direction}"
            }
            "open_app" -> service.openApp(a.appName.orEmpty())
            "navigate_back" -> if (service.back()) "went back" else "back failed"
            "navigate_home" -> if (service.home()) "went home" else "home failed"
            "keyboard_enter" -> service.pressEnter()
            "wait" -> {
                delay(WAIT_ACTION_MS)
                "waited"
            }
            "answer" -> "answered"
            else -> "unsupported ${a.type}"
        }

    private fun screenUnavailable(): String? {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return when {
            !power.isInteractive -> "The screen is off. Turn it on and unlock the phone, then run again."
            keyguard.isKeyguardLocked -> "The phone is locked. Unlock it, then run again."
            else -> null
        }
    }

    /** 72 x 160 grey thumbnail (the loop guard's screen-change measure). */
    private fun grayThumb(b: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(b, 72, 160, true)
        val px = IntArray(72 * 160)
        small.getPixels(px, 0, 72, 0, 0, 72, 160)
        small.recycle()
        return FloatArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000f
        }
    }

    private fun saveThumb(b: Bitmap, f: File) = runCatching {
        val small = Bitmap.createScaledBitmap(b, b.width / 4, b.height / 4, true)
        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        small.recycle()
    }

    /** The per-step logcat line (one line, short fields). */
    @Serializable
    data class StepLine(
        val step: Int, val intent: String, val action: String?, val outcome: String, val shotMs: Long, val prepMs: Long,
        val engineMs: Long?, val modelMs: Long, val ttftMs: Long?, val prefillTokens: Int?, val prefillTps: Double?,
        val decodeTokens: Int?, val decodeTps: Double?, val attempts: Int, val actMs: Long, val settleMs: Long, val stepMs: Long,
        val guard: String?, val loop: List<String>,
    ) {
        companion object {
            /** Without [verbose]: no intent, the action's type only, the outcome's first words, the guard as "asked". */
            fun of(r: StepRecord, verbose: Boolean) = StepLine(
                r.step,
                if (verbose) r.intent.take(160) else "",
                if (verbose) r.action?.take(200) else r.action?.let { AgentFormat.parseAction(it)?.type ?: "?" },
                if (verbose) r.outcome.take(120) else r.outcome.substringBefore(' '),
                r.screenshotMs, r.resizeMs + r.pngMs, r.engineMs,
                r.modelMs, r.ttftMs, r.prefillTokens, r.prefillTokensPerS?.let { Math.round(it * 10) / 10.0 }, r.decodeTokens,
                r.decodeTokensPerS?.let { Math.round(it * 10) / 10.0 }, r.attempts.size, r.actMs, r.settleMs, r.stepMs,
                if (verbose) r.guard else r.guard?.let { "asked" }, r.loopRules,
            )
        }
    }

    /** The final logcat line: the run's summary and where its full JSON is. */
    @Serializable
    data class ResultLine(
        val runId: String, val goal: String, val status: String, val answer: String?, val error: String?, val steps: Int,
        val installedApps: Int, val screenGrid: String?, val gridMatches: Boolean?,
        val secondsPerStep: Double?, val medianModelMs: Long?, val loadMs: Long?, val backends: String, val enginePerStep: Boolean,
        val modelFile: String, val device: String, val file: String,
    ) {
        companion object {
            /** Without [verbose]: no goal and no answer. */
            fun of(r: RunRecord, f: File, perStep: Double?, verbose: Boolean): ResultLine {
                val m = r.steps.map { it.modelMs }.sorted()
                return ResultLine(
                    r.runId, if (verbose) r.goal.take(200) else "", r.status, if (verbose) r.answer?.take(200) else null,
                    if (verbose) r.error?.take(300) else r.error?.substringBefore(':'), r.steps.size, r.installedAppCount, r.screenGrid, r.gridMatches,
                    perStep?.let { Math.round(it * 100) / 100.0 }, m.getOrNull(m.size / 2), r.loadMs, r.backends, r.enginePerStep,
                    r.modelFile, r.device, f.absolutePath,
                )
            }
        }
    }

    companion object {
        private const val TAG = "MobileAgent"
        /** max_tokens of the benchmark harness. */
        const val MAX_OUTPUT_TOKENS = 512
        /** lfm_agent.py wait_after_action. */
        const val WAIT_AFTER_ACTION_MS = 1_000L
        /** AndroidWorld's `wait` action: time.sleep(1.0). */
        const val WAIT_ACTION_MS = 1_000L
        const val TAP_MS = 50L
        const val LONG_PRESS_MS = 1_000L
        /** `adb shell input swipe` without a duration. */
        const val SWIPE_MS = 300L
        const val CONFIRM_TIMEOUT_MS = 120_000L
        /** How long a run waits for the accessibility service to come back after Android rebinds it. */
        const val RECONNECT_MS = 5_000L
        /** How long the start (home screen or the previous app) may take to come to the front. */
        const val START_WAIT_MS = 3_000L
    }
}
