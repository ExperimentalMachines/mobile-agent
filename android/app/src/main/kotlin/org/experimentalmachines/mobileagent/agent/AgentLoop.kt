package org.experimentalmachines.mobileagent.agent

import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.protocol.AgentFormat

/**
 * The step loop, as the benchmarks ran the model (research repo, eval/androidworld/lfm_agent.py `step()`), with no
 * Android dependency so a unit test can drive it with a fake screen and a fake model:
 *
 * - one screenshot per step, the prompt from the goal, the installed apps and the last 8 steps;
 * - one retry when the reply cannot be parsed; an unparseable step enters the history as
 *   `("(invalid output)", content[:80])` and ends there (no action, no wait);
 * - the intent is the first line of the thought; the step enters the history as (intent, canonical action JSON);
 * - `status` ends the run; `answer` ends it after its (no-op) execution; otherwise the action runs, then 1 s and a quiet
 *   screen; the step limit ends the run.
 *
 * Additions the harness does not have, each recorded in the step: the safety guard (ask; Skip / Stop), the time-of-use
 * re-check right before the gesture, and redaction of typed secrets in what is written down. A step that did not run
 * (skipped by the user, or held back because the screen changed) enters the history with a suffix in the style of the
 * harness's opt-in runtime feedback (`[failed to execute]`): `... [skipped by the user]` / `... [not executed: the
 * screen changed]`, so the model does not believe it ran.
 */
class AgentLoop(private val env: Env, private val clock: () -> Long) {

    interface Env {
        suspend fun awaitResumed()
        fun screenUnavailable(): String?
        fun update(change: (RunState.Running) -> RunState.Running)
        suspend fun observe(step: Int): Screen
        fun foreground(): String?
        fun live(x: Int?, y: Int?): Guard.Live
        /** A new engine (engine-per-step mode); returns its creation time in ms. */
        suspend fun newEngine(): Long
        suspend fun reply(png: ByteArray, system: String, user: String, step: Int, onThought: (String) -> Unit): LlmRuntime.Reply
        /** Asks the user about a guarded step; null when nobody answers in time. */
        suspend fun ask(reason: String): Decision?
        /** Runs [block] with the floating control off screen. */
        suspend fun <T> hidden(block: suspend () -> T): T
        suspend fun execute(a: AgentFormat.Action, w: Int, h: Int, tap: Pair<Int, Int>?, enterAfterTyping: Boolean): String
        fun showTap(x: Int, y: Int)
        /** lfm_agent.py's wait_after_action (1 s), then a quiet screen. */
        suspend fun settle()
        fun log(line: String)
    }

    /** One observation: the model's input and the screen it came from. */
    class Screen(
        val png: ByteArray,
        val width: Int,
        val height: Int,
        val thumb: FloatArray,
        val shotMs: Long,
        val resizeMs: Long,
        val pngMs: Long,
    )

    data class Config(
        val goal: String,
        val promptVersion: String,
        val apps: List<String>?,
        /** The model file's fixed input grid (height, width). */
        val grid: Pair<Int, Int>,
        val maxSteps: Int,
        val enginePerStep: Boolean,
        val confirmRisky: Boolean,
        val enterAfterTyping: Boolean,
        /** Engine creation time to put on step 1 (the run's own load), or null. */
        val firstEngineMs: Long? = null,
    )

    /** Filled as the run goes, so a stopped or failed run still keeps its steps. */
    class Outcome {
        var status = "error"
        var answer: String? = null
        val steps = mutableListOf<StepRecord>()
        val redactor = Redactor()
        /** smart_resize of the screen (height x width) and whether it equals the model file's grid. */
        var screenGrid: String? = null
        var gridMatches: Boolean? = null
        /** The history the model saw last (for tests and the run log). */
        val history = mutableListOf<AgentFormat.Step>()
    }

    suspend fun run(c: Config, out: Outcome, onStep: (StepRecord) -> Unit = {}) {
        val history = out.history
        val loopGuard = LoopGuard()
        out.status = "max_steps"
        for (step in 1..c.maxSteps) {
            env.awaitResumed()
            env.screenUnavailable()?.let { reason ->
                out.status = "needs_user"
                out.answer = reason
                return
            }
            val s0 = clock()
            env.update { it.copy(step = step, phase = "Looking") }
            // 1. Screenshot (without the floating control), resized to the model's grid.
            val screen = env.observe(step)
            val foregroundAtShot = env.foreground()
            val (w, h) = screen.width to screen.height
            val grid = AgentFormat.smartResize(h, w)
            if (out.screenGrid == null || (grid != c.grid && out.gridMatches == true)) {
                out.screenGrid = "${grid.first}x${grid.second}"
                out.gridMatches = grid == c.grid
                if (grid != c.grid) {
                    env.log("screen ${w}x$h: smart_resize ${grid.first}x${grid.second} differs from the model's grid ${c.grid.first}x${c.grid.second}; the screenshot is stretched")
                }
            }

            // 2. The model: a new conversation (and, in engine-per-step mode, a new engine).
            var engineMs: Long? = if (step == 1) c.firstEngineMs else null
            if (c.enginePerStep && step > 1) {
                env.update { it.copy(phase = "Loading the model") }
                engineMs = env.newEngine()
            }
            val (system, user) = AgentFormat.messages(c.promptVersion, c.goal, history, c.apps)
            env.update { it.copy(phase = "Thinking", intent = null, action = null) }
            val attempts = mutableListOf<Attempt>()
            var reply: LlmRuntime.Reply? = null
            var action: AgentFormat.Action? = null
            for (k in 0..MAX_PARSE_RETRIES) {
                val r = env.reply(screen.png, system, user, step) { thought ->
                    val i = AgentFormat.intent(thought, "")
                    if (i.isNotEmpty()) env.update { st -> if (st.intent == i) st else st.copy(intent = i) }
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
                    step = step, intent = intent, action = actionStr, outcome = outcome, attempts = attempts.toList(),
                    screenshotMs = screen.shotMs, resizeMs = screen.resizeMs, pngMs = screen.pngMs, engineMs = engineMs, modelMs = modelMs,
                    ttftMs = b0?.ttftMs, prefillTokens = b0?.prefillTokens, prefillTokensPerS = b0?.prefillTokensPerS,
                    decodeTokens = b0?.decodeTokens, decodeTokensPerS = b0?.decodeTokensPerS, actMs = actMs, settleMs = settleMs,
                    stepMs = clock() - s0, screenWidth = w, screenHeight = h, tapX = tap?.first, tapY = tap?.second,
                    guard = guard, loopRules = rules, historyEntry = history.lastOrNull()?.let { "${it.intent} -> ${it.action}" },
                )
                val clean = scrub(rec, out.redactor)
                out.steps += clean
                onStep(clean)
            }

            if (action == null) {
                history += AgentFormat.invalidStep(r.text)
                record("(invalid output)", null, "invalid output", 0, 0)
                env.update { it.copy(intent = "(invalid output)", action = r.text.take(60)) }
                continue
            }
            val intent = AgentFormat.intent(r.thought, r.text)
            val actionStr = AgentFormat.actionToStr(action)
            history += AgentFormat.Step(intent, actionStr)
            val rules = loopGuard.check(action, intent, screen.thumb)
            loopGuard.record(action, screen.thumb)
            env.update { it.copy(intent = intent.ifEmpty { null }, action = actionStr) }

            if (action.type == "status") {
                out.status = action.goalStatus ?: "complete"
                record(intent, actionStr, "finished: ${out.status}", 0, 0, rules = rules)
                return
            }

            // 3. The safety guard, on the live screen.
            env.awaitResumed()
            val tap = if (action.type in AgentFormat.POINT_TYPES) AgentFormat.normToPx(action.x!!, action.y!!, w, h) else null
            var guardNote: String? = null
            val decided = env.live(tap?.first, tap?.second)
            val risk = Guard.risk(action.type, decided, c.enterAfterTyping)
            if (action.type == "input_text" && (risk != null || decided.focusedIsPassword)) out.redactor.add(action.text)
            if (risk != null) {
                env.log("MOBILEAGENT_GUARD step $step")
                if (c.confirmRisky) {
                    val d = env.ask(risk)
                    guardNote = "$risk: ${d?.name?.lowercase() ?: "timeout"}"
                    when (d) {
                        Decision.ALLOW -> Unit
                        Decision.SKIP -> {
                            history[history.size - 1] = AgentFormat.Step(intent, "$actionStr [skipped by the user]")
                            record(intent, actionStr, "skipped by the user", 0, 0, tap, guardNote, rules)
                            continue
                        }
                        Decision.STOP, null -> {
                            out.status = "needs_user"
                            out.answer = "Stopped before: $risk"
                            record(intent, actionStr, "stopped at the guard", 0, 0, tap, guardNote, rules)
                            return
                        }
                    }
                } else {
                    guardNote = "$risk: not asked (setting off)"
                }
            }

            // 4. Act, unless the screen changed under the action since it was decided (time of check vs use).
            env.update { it.copy(phase = "Acting") }
            val a0 = clock()
            val outcome = env.hidden {
                val now = env.live(tap?.first, tap?.second)
                if (action.type == "input_text" && now.focusedIsPassword) out.redactor.add(action.text)
                val changed = Guard.changed(action.type, foregroundAtShot, decided, now, guarded = risk != null, enterAfterTyping = c.enterAfterTyping)
                if (changed != null) "not executed: $changed" else env.execute(action, w, h, tap, c.enterAfterTyping)
            }
            val executed = !outcome.startsWith("not executed")
            if (!executed) history[history.size - 1] = AgentFormat.Step(intent, "$actionStr [not executed: the screen changed]")
            if (executed && tap != null) env.showTap(tap.first, tap.second)
            val actMs = clock() - a0
            if (action.type == "answer") {
                out.status = "answered"
                out.answer = out.redactor.scrub(action.text)
                record(intent, actionStr, outcome, actMs, 0, tap, guardNote, rules)
                return
            }
            // 5. wait_after_action (1 s), then a quiet screen.
            env.update { it.copy(phase = "Waiting for the screen") }
            val q0 = clock()
            env.settle()
            record(intent, actionStr, outcome, actMs, clock() - q0, tap, guardNote, rules)
        }
    }

    companion object {
        /** One retry when the reply cannot be parsed (lfm_agent.py max_parse_retries). */
        const val MAX_PARSE_RETRIES = 1

        /** [r] with the run's secrets replaced in every text field. */
        fun scrub(r: StepRecord, red: Redactor): StepRecord = if (!red.active) r else r.copy(
            intent = red.scrub(r.intent)!!, action = red.scrub(r.action), outcome = red.scrub(r.outcome)!!, guard = red.scrub(r.guard),
            historyEntry = red.scrub(r.historyEntry),
            attempts = r.attempts.map { it.copy(text = red.scrub(it.text)!!, thought = red.scrub(it.thought)!!) },
        )
    }
}
