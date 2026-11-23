package org.experimentalmachines.mobileagent.agent

import org.experimentalmachines.mobileagent.protocol.AgentFormat
import kotlin.math.abs

/**
 * The research repo's loop guards (train/agent_guards.py, v4.1 follow-up), as diagnostics only: the sealed v4
 * benchmarks ran with guards off, and the guards' fix (a grammar-constrained re-ask) needs constrained decoding this
 * app does not use. The run log records when a rule would have fired.
 *
 *  G1: the reply is `wait` but its intent does not mention waiting or loading.
 *  G2: the reply repeats the action of each of the last two steps (taps within 30 grid units) and the screen now
 *      equals the screen of two steps ago (mean absolute difference of a 72x160 grey thumbnail < 1.0).
 */
class LoopGuard {
    private val screens = mutableListOf<FloatArray>()
    private val actions = mutableListOf<AgentFormat.Action>()

    fun check(action: AgentFormat.Action, intent: String, screen: FloatArray): List<String> {
        val rules = mutableListOf<String>()
        if (action.type == "wait" && !WAITY.containsMatchIn(intent)) rules += "G1"
        if (actions.size >= 2 && actions.takeLast(2).all { same(action, it) } && diff(screens[screens.size - 2], screen) < SCREEN_DIFF) rules += "G2"
        return rules
    }

    fun record(action: AgentFormat.Action, screen: FloatArray) {
        actions += action
        screens += screen
    }

    companion object {
        private val WAITY = Regex("""\b(wait|waiting|load|loading|loads|loaded)\b""", RegexOption.IGNORE_CASE)
        private const val REPEAT_RADIUS = 30.0
        private const val SCREEN_DIFF = 1.0f

        fun same(a: AgentFormat.Action, b: AgentFormat.Action): Boolean {
            if (a.type != b.type) return false
            if (a.type in AgentFormat.POINT_TYPES) return abs(a.x!! - b.x!!) <= REPEAT_RADIUS && abs(a.y!! - b.y!!) <= REPEAT_RADIUS
            return a.text == b.text && a.direction == b.direction && a.appName == b.appName && a.goalStatus == b.goalStatus
        }

        fun diff(a: FloatArray, b: FloatArray): Float {
            var s = 0.0
            for (i in a.indices) s += abs(a[i] - b[i])
            return (s / a.size).toFloat()
        }
    }
}
