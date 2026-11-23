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

    data class Options(val maxSteps: Int, val enginePerStep: Boolean, val confirmRisky: Boolean)

    fun start(goal: String, options: Options? = null): Boolean {
        val g = goal.trim()
        if (g.isEmpty() || running) return false
        val s = settings.current
        val o = options ?: Options(s.maxSteps, s.enginePerStep, s.confirmRisky)
        paused.value = false
        _state.value = RunState.Running(g, 0, o.maxSteps, "Starting", null, null, false, null)
        RunService.start(context)
        overlay.show()
        job = models.scope.launch { run(g, o) }
        return true
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

    private suspend fun run(goal: String, o: Options) {
        val started = System.currentTimeMillis()
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(started))
        val steps = mutableListOf<StepRecord>()
        val history = mutableListOf<AgentFormat.Step>()
        val loopGuard = LoopGuard()
        val s = settings.current
        var status = "error"
        var answer: String? = null
        var error: String? = null
        var loaded: LlmRuntime.Loaded? = null
        var firstLoadMs: Long? = null
        var appNames = emptyList<String>()
        try {
            val service = AgentService.instance ?: throw IllegalStateException("Turn on Mobile Agent in Accessibility settings first.")
            update { it.copy(phase = "Loading the model") }
            if (o.enginePerStep) {
                loaded = models.loadFresh() // step 1's engine; every later step creates its own
                firstLoadMs = loaded.loadMs
            } else {
                val before = models.runtime.loaded
                loaded = models.ensureLoaded()
                firstLoadMs = if (loaded !== before) loaded.loadMs else null // null: the engine was already loaded
            }
            val spec = loaded.spec
            appNames = InstalledApps.promptNames(InstalledApps.list(context))
            val apps = if (spec.promptVersion == "v4") appNames else null
            status = "max_steps"
            for (step in 1..o.maxSteps) {
                awaitResumed()
                screenUnavailable()?.let { reason ->
                    status = "needs_user"
                    answer = reason
                    break
                }
                val s0 = SystemClock.elapsedRealtime()
                update { it.copy(step = step, phase = "Looking") }
                // 1. Screenshot, without the floating control in it.
                val shot = StallWatch.phase("screenshot (step $step)", 30_000) { overlay.whileHidden { service.screenshot() } }
                val shotMs = SystemClock.elapsedRealtime() - s0
                val w = shot.width
                val h = shot.height
                val prep = ImagePrep.toModelPng(shot, spec.imageHeight, spec.imageWidth)
                val thumb = grayThumb(shot)
                saveThumb(shot, File(runs.runDir(runId), "step-%02d.jpg".format(step)))
                shot.recycle()

                // 2. The model: a new conversation (and, in engine-per-step mode, a new engine).
                var engineMs: Long? = if (step == 1) firstLoadMs else null
                if (o.enginePerStep && step > 1) {
                    update { it.copy(phase = "Loading the model") }
                    val l = loaded!!
                    loaded = models.runtime.load(l.spec, l.file, l.textBackend, l.visionBackend)
                    engineMs = loaded.loadMs
                }
                val (system, user) = AgentFormat.messages(spec.promptVersion, goal, history, apps)
                update { it.copy(phase = "Thinking", intent = null, action = null) }
                val attempts = mutableListOf<Attempt>()
                var reply: LlmRuntime.Reply? = null
                var action: AgentFormat.Action? = null
                for (k in 0..MAX_PARSE_RETRIES) {
                    val r = StallWatch.phase("model (step $step)", 300_000) {
                        models.runtime.generate(prep.png, system, user, MAX_OUTPUT_TOKENS, spec.enableThinking) { thought, _ ->
                            val i = AgentFormat.intent(thought, "")
                            if (i.isNotEmpty()) update { st -> if (st.intent == i) st else st.copy(intent = i) }
                        }
                    }
                    reply = r
                    action = AgentFormat.parseAction(r.text)
                    attempts += Attempt(r.text, r.thought, r.totalMs, r.firstPieceMs, r.lastPieceMs, r.bench, action != null, AgentFormat.parseAction(r.text, strict = true) != null)
                    if (action != null) break
                }
                val modelMs = attempts.sumOf { it.modelMs }
                val b0 = attempts.first().bench
                val r = reply!!

                fun record(intent: String, actionStr: String?, outcome: String, actMs: Long, settleMs: Long, tap: Pair<Int, Int>? = null, guard: String? = null, rules: List<String> = emptyList()) {
                    val rec = StepRecord(
                        step = step, intent = intent, action = actionStr, outcome = outcome, attempts = attempts,
                        screenshotMs = shotMs, resizeMs = prep.resizeMs, pngMs = prep.encodeMs, engineMs = engineMs, modelMs = modelMs,
                        ttftMs = b0?.ttftMs, prefillTokens = b0?.prefillTokens, prefillTokensPerS = b0?.prefillTokensPerS,
                        decodeTokens = b0?.decodeTokens, decodeTokensPerS = b0?.decodeTokensPerS, actMs = actMs, settleMs = settleMs,
                        stepMs = SystemClock.elapsedRealtime() - s0, screenWidth = w, screenHeight = h, tapX = tap?.first, tapY = tap?.second,
                        guard = guard, loopRules = rules,
                    )
                    steps += rec
                    Log.i(TAG, "MOBILEAGENT_STEP " + line.encodeToString(StepLine.serializer(), StepLine.of(rec)))
                }

                if (action == null) {
                    history += AgentFormat.invalidStep(r.text)
                    record("(invalid output)", null, "invalid output", 0, 0)
                    update { it.copy(intent = "(invalid output)", action = r.text.take(60)) }
                    continue
                }
                val intent = AgentFormat.intent(r.thought, r.text)
                val actionStr = AgentFormat.actionToStr(action)
                history += AgentFormat.Step(intent, actionStr)
                val rules = loopGuard.check(action, intent, thumb)
                loopGuard.record(action, thumb)
                update { it.copy(intent = intent.ifEmpty { null }, action = actionStr) }

                if (action.type == "status") {
                    status = action.goalStatus ?: "complete"
                    record(intent, actionStr, "finished: $status", 0, 0, rules = rules)
                    break
                }

                // 3. The safety guard, on the live screen.
                awaitResumed()
                val tap = if (action.type in AgentFormat.POINT_TYPES) AgentFormat.normToPx(action.x!!, action.y!!, w, h) else null
                var guardNote: String? = null
                val risk = Guard.risk(action.type, service.live(tap?.first, tap?.second))
                if (risk != null) {
                    if (o.confirmRisky) {
                        val d = ask(risk)
                        guardNote = "$risk: ${d?.name?.lowercase() ?: "timeout"}"
                        when (d) {
                            Decision.ALLOW -> Unit
                            Decision.SKIP -> {
                                record(intent, actionStr, "skipped by the user", 0, 0, tap, guardNote, rules)
                                continue
                            }
                            Decision.STOP, null -> {
                                status = "needs_user"
                                answer = "Stopped before: $risk"
                                record(intent, actionStr, "stopped at the guard", 0, 0, tap, guardNote, rules)
                                break
                            }
                        }
                    } else {
                        guardNote = "$risk: not asked (setting off)"
                    }
                }

                // 4. Act.
                update { it.copy(phase = "Acting") }
                val a0 = SystemClock.elapsedRealtime()
                val outcome = StallWatch.phase("act ${action.type} (step $step)", 30_000) {
                    overlay.whileHidden { execute(service, action, w, h, tap, s.enterAfterTyping) }
                }
                if (tap != null && settings.current.showTaps) service.showTap(tap.first, tap.second)
                val actMs = SystemClock.elapsedRealtime() - a0
                if (action.type == "answer") {
                    status = "answered"
                    answer = action.text
                    record(intent, actionStr, outcome, actMs, 0, tap, guardNote, rules)
                    break
                }
                // 5. wait_after_action (1 s), then a quiet screen.
                update { it.copy(phase = "Waiting for the screen") }
                val q0 = SystemClock.elapsedRealtime()
                delay(WAIT_AFTER_ACTION_MS)
                service.awaitQuiet()
                record(intent, actionStr, outcome, actMs, SystemClock.elapsedRealtime() - q0, tap, guardNote, rules)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                status = "stopped"
            } else {
                error = "${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "run failed", t)
            }
        } finally {
            pendingDecision = null
            if (o.enginePerStep) withContext(kotlinx.coroutines.NonCancellable) { models.runtime.close() }
        }
        val l = loaded
        val record = RunRecord(
            runId = runId, goal = goal, model = l?.spec?.name ?: "-", modelFile = l?.file?.name ?: "-",
            imageGrid = l?.spec?.let { "${it.imageHeight}x${it.imageWidth}" } ?: "-", promptVersion = l?.spec?.promptVersion ?: "-",
            backends = l?.backends ?: "${s.textBackend}/${s.visionBackend}", enginePerStep = o.enginePerStep, enterAfterTyping = s.enterAfterTyping,
            loadMs = firstLoadMs, maxSteps = o.maxSteps, status = status, answer = answer, error = error,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", soc = Build.SOC_MODEL, android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            litertLm = BuildConfig.LITERT_LM_VERSION, installedApps = appNames, steps = steps, startedAtMs = started, endedAtMs = System.currentTimeMillis(),
        )
        val file = runs.save(record)
        val perStep = steps.takeIf { it.isNotEmpty() }?.let { st -> st.sumOf { it.stepMs } / 1000.0 / st.size }
        Log.i(TAG, "MOBILEAGENT_RESULT " + line.encodeToString(ResultLine.serializer(), ResultLine.of(record, file, perStep)))
        _state.value = RunState.Finished(runId, goal, status, answer, steps.size, perStep, error)
    }

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
            fun of(r: StepRecord) = StepLine(
                r.step, r.intent.take(160), r.action?.take(200), r.outcome.take(120), r.screenshotMs, r.resizeMs + r.pngMs, r.engineMs,
                r.modelMs, r.ttftMs, r.prefillTokens, r.prefillTokensPerS?.let { Math.round(it * 10) / 10.0 }, r.decodeTokens,
                r.decodeTokensPerS?.let { Math.round(it * 10) / 10.0 }, r.attempts.size, r.actMs, r.settleMs, r.stepMs, r.guard, r.loopRules,
            )
        }
    }

    /** The final logcat line: the run's summary and where its full JSON is. */
    @Serializable
    data class ResultLine(
        val runId: String, val goal: String, val status: String, val answer: String?, val error: String?, val steps: Int,
        val secondsPerStep: Double?, val medianModelMs: Long?, val loadMs: Long?, val backends: String, val enginePerStep: Boolean,
        val modelFile: String, val device: String, val file: String,
    ) {
        companion object {
            fun of(r: RunRecord, f: File, perStep: Double?): ResultLine {
                val m = r.steps.map { it.modelMs }.sorted()
                return ResultLine(
                    r.runId, r.goal.take(200), r.status, r.answer?.take(200), r.error?.take(300), r.steps.size,
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
        /** One retry when the reply cannot be parsed (lfm_agent.py max_parse_retries). */
        const val MAX_PARSE_RETRIES = 1
        /** lfm_agent.py wait_after_action. */
        const val WAIT_AFTER_ACTION_MS = 1_000L
        /** AndroidWorld's `wait` action: time.sleep(1.0). */
        const val WAIT_ACTION_MS = 1_000L
        const val TAP_MS = 50L
        const val LONG_PRESS_MS = 1_000L
        /** `adb shell input swipe` without a duration. */
        const val SWIPE_MS = 300L
        const val CONFIRM_TIMEOUT_MS = 120_000L
    }
}
