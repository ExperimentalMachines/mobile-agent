package org.experimentalmachines.mobileagent.agent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.protocol.AgentFormat
import org.junit.Test

/**
 * The step loop against the research harness: tools/gen_golden.py replays scripted replies through a copy of
 * eval/androidworld/lfm_agent.py `step()`; here the same replies go through [AgentLoop] with a fake screen and model.
 * Every prompt (user text), every executed action, the final history and the ending must be identical.
 */
class AgentLoopTest {
    private val episodes = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/golden/agent_loop.json")!!.readBytes().toString(Charsets.UTF_8),
    ).jsonArray.map { it.jsonObject }

    /** A phone that always shows the same app and a model that answers from a list. */
    private class FakeEnv(
        private val replies: List<Pair<String, String>>,
        var live: Guard.Live = Guard.Live(windowPackages = listOf("app"), foregroundPackage = "app", targetPackage = "app"),
        var liveAtAct: Guard.Live? = null,
        var decision: Decision? = Decision.ALLOW,
    ) : AgentLoop.Env {
        val calls = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val asked = mutableListOf<String>()
        private var next = 0
        private var acting = false

        override suspend fun awaitResumed() = Unit
        override fun screenUnavailable(): String? = null
        override fun update(change: (RunState.Running) -> RunState.Running) = Unit
        override suspend fun observe(step: Int) = AgentLoop.Screen(ByteArray(1), 1440, 3200, FloatArray(72 * 160), 1, 1, 1)
        override fun foreground(): String? = live.foregroundPackage
        override fun live(x: Int?, y: Int?): Guard.Live = if (acting) liveAtAct ?: live else live
        override suspend fun newEngine(): Long = 0
        override suspend fun reply(png: ByteArray, system: String, user: String, step: Int, onThought: (String) -> Unit): LlmRuntime.Reply {
            assertThat(system).isEqualTo(AgentFormat.SYSTEM_PROMPT_V4)
            calls += user
            val (thought, text) = replies[next++]
            return LlmRuntime.Reply(text, thought, 10, null, null, 1, null)
        }
        override suspend fun ask(reason: String): Decision? = decision.also { asked += reason }
        override suspend fun <T> hidden(block: suspend () -> T): T {
            acting = true
            try {
                return block()
            } finally {
                acting = false
            }
        }
        override suspend fun execute(a: AgentFormat.Action, w: Int, h: Int, tap: Pair<Int, Int>?, enterAfterTyping: Boolean): String {
            executed += AgentFormat.actionToStr(a)
            return "done"
        }
        override fun showTap(x: Int, y: Int) = Unit
        override suspend fun settle() = Unit
        override fun log(line: String) = Unit
    }

    private fun config(goal: String, apps: List<String>, maxSteps: Int) = AgentLoop.Config(
        goal = goal, promptVersion = "v4", apps = apps, grid = 1280 to 576, maxSteps = maxSteps,
        enginePerStep = false, confirmRisky = true, enterAfterTyping = true,
    )

    @Test
    fun sameDecisionsAsTheHarness() = runBlocking {
        for (e in episodes) {
            val name = e["name"]!!.jsonPrimitive.content
            val replies = e["replies"]!!.jsonArray.map { r -> r.jsonObject.let { it["reasoning"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content } }
            val apps = e["apps"]!!.jsonArray.map { it.jsonPrimitive.content }
            val env = FakeEnv(replies)
            val out = AgentLoop.Outcome()
            AgentLoop(env) { 0L }.run(config(e["goal"]!!.jsonPrimitive.content, apps, e["max_steps"]!!.jsonPrimitive.int), out)
            assertWithMessage("$name prompts").that(env.calls).containsExactlyElementsIn(e.strings("calls")).inOrder()
            assertWithMessage("$name executed").that(env.executed).containsExactlyElementsIn(e.strings("executed")).inOrder()
            assertWithMessage("$name status").that(out.status).isEqualTo(e["status"]!!.jsonPrimitive.content)
            assertWithMessage("$name answer").that(out.answer).isEqualTo(e["answer"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content)
            val history = e["history"]!!.jsonArray.map { h -> h.jsonArray.let { AgentFormat.Step(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content) } }
            assertWithMessage("$name history").that(out.history).containsExactlyElementsIn(history).inOrder()
        }
    }

    private fun JsonObject.strings(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }

    private val tapReply = "Tap Delete" to """{"action_type":"click","point_2d":[500,500]}"""
    private val done = "Done" to """{"action_type":"status","goal_status":"complete"}"""

    @Test
    fun skippedStepIsMarkedInTheHistory() = runBlocking {
        val env = FakeEnv(listOf(tapReply, done), live = Guard.Live(listOf("app"), listOf("Delete"), "app", foregroundPackage = "app"), decision = Decision.SKIP)
        val out = AgentLoop.Outcome()
        AgentLoop(env) { 0L }.run(config("Delete it", listOf("App"), 5), out)
        assertThat(env.asked).hasSize(1)
        assertThat(env.executed).isEmpty()
        assertThat(out.steps[0].outcome).isEqualTo("skipped by the user")
        assertThat(out.history[0].action).isEqualTo("""{"action_type":"click","point_2d":[500,500]} [skipped by the user]""")
        assertThat(env.calls[1]).contains("1. Tap Delete -> {\"action_type\":\"click\",\"point_2d\":[500,500]} [skipped by the user]")
        assertThat(out.status).isEqualTo("complete")
    }

    @Test
    fun stopOrNoAnswerEndsTheRun() = runBlocking {
        for (d in listOf(Decision.STOP, null)) {
            val env = FakeEnv(listOf(tapReply, done), live = Guard.Live(listOf("app"), listOf("Delete"), "app", foregroundPackage = "app"), decision = d)
            val out = AgentLoop.Outcome()
            AgentLoop(env) { 0L }.run(config("Delete it", listOf("App"), 5), out)
            assertThat(out.status).isEqualTo("needs_user")
            assertThat(env.executed).isEmpty()
            assertThat(out.steps).hasSize(1)
        }
    }

    @Test
    fun actionHeldBackWhenTheScreenChanged() = runBlocking {
        val env = FakeEnv(listOf(tapReply, done))
        env.liveAtAct = env.live.copy(foregroundPackage = "other", targetPackage = "other")
        val out = AgentLoop.Outcome()
        AgentLoop(env) { 0L }.run(config("Tap", listOf("App"), 5), out)
        assertThat(env.executed).isEmpty()
        assertThat(out.steps[0].outcome).startsWith("not executed: the app in front changed")
        assertThat(out.history[0].action).endsWith("[not executed: the screen changed]")
    }

    @Test
    fun passwordTextIsRedactedInRecordsButNotInThePrompt() = runBlocking {
        val type = "Type hunter2 into the password" to """{"action_type":"input_text","text":"hunter2"}"""
        val env = FakeEnv(listOf(type, done), live = Guard.Live(listOf("app"), focusedIsPassword = true, focusedPackage = "app", foregroundPackage = "app"))
        val out = AgentLoop.Outcome()
        AgentLoop(env) { 0L }.run(config("type hunter2 into the password field", listOf("App"), 5), out)
        assertThat(env.asked.single()).isEqualTo("typing into a password field")
        assertThat(env.executed.single()).contains("hunter2") // the action itself runs (the user allowed it)
        assertThat(env.calls[1]).contains("hunter2")        // and the model's own history keeps it
        val rec = out.steps[0]
        assertThat(rec.toString()).doesNotContain("hunter2")
        assertThat(rec.action).isEqualTo("""{"action_type":"input_text","text":"[redacted]"}""")
        assertThat(rec.attempts.single().thought).isEqualTo("Type [redacted] into the password")
    }

    @Test
    fun gridMismatchIsRecorded() = runBlocking {
        val env = object : AgentLoop.Env by FakeEnv(listOf(done)) {
            override suspend fun observe(step: Int) = AgentLoop.Screen(ByteArray(1), 1080, 2424, FloatArray(72 * 160), 1, 1, 1)
        }
        val out = AgentLoop.Outcome()
        AgentLoop(env) { 0L }.run(config("x", listOf("App"), 2), out)
        assertThat(out.screenGrid).isEqualTo("1312x576")
        assertThat(out.gridMatches).isFalse()
    }
}
