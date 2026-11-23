package org.experimentalmachines.mobileagent.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The Kotlin port against golden output of the research repo's train/agent_format.py (tools/gen_golden.py).
 * Every string is compared exactly: the model saw these bytes in training and in the benchmarks.
 */
class AgentFormatGoldenTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/golden/agent_format.json")!!.readBytes().toString(Charsets.UTF_8),
    ).jsonObject

    private fun str(o: JsonObject, k: String): String? = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun history(a: JsonArray) = a.map { val p = it.jsonArray; AgentFormat.Step(p[0].jsonPrimitive.content, p[1].jsonPrimitive.content) }

    @Test
    fun systemPrompts() {
        assertThat(AgentFormat.SYSTEM_PROMPT_V1).isEqualTo(str(golden, "system_v1"))
        assertThat(AgentFormat.SYSTEM_PROMPT_V4).isEqualTo(str(golden, "system_v4"))
        assertThat(AgentFormat.HISTORY_STEPS).isEqualTo(golden["history_steps"]!!.jsonPrimitive.int)
    }

    @Test
    fun userText() {
        for (c in golden["user_text"]!!.jsonArray.map { it.jsonObject }) {
            val apps = c["apps"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }
            val got = AgentFormat.userText(str(c, "goal")!!, history(c["history"]!!.jsonArray), str(c, "step_instruction"), apps)
            assertWithMessage("goal=${str(c, "goal")}").that(got).isEqualTo(str(c, "expected"))
            assertThat(got.toByteArray(Charsets.UTF_8)).isEqualTo(str(c, "expected")!!.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun messagesFollowBuildMessagesRule() {
        val (system, user) = AgentFormat.messages("v4", "Open the Clock app", emptyList(), listOf("Clock"))
        assertThat(system).isEqualTo(AgentFormat.SYSTEM_PROMPT_V4)
        assertThat(user).isEqualTo("Goal: Open the Clock app\nInstalled apps: Clock\nPrevious steps:\nNone\nWhat is the next action?")
        val v4WithoutApps = runCatching { AgentFormat.messages("v4", "g", emptyList(), null) }
        assertThat(v4WithoutApps.isFailure).isTrue()
        val v1WithApps = runCatching { AgentFormat.messages("v1", "g", emptyList(), listOf("Clock")) }
        assertThat(v1WithApps.isFailure).isTrue()
    }

    @Test
    fun parseActionLenientAndStrict() {
        for (c in golden["parse"]!!.jsonArray.map { it.jsonObject }) {
            val text = str(c, "text")!!
            for (mode in listOf("lenient", "strict")) {
                val a = AgentFormat.parseAction(text, strict = mode == "strict")
                assertWithMessage("$mode: $text").that(a?.let(AgentFormat::actionToStr)).isEqualTo(str(c, mode))
                c["${mode}_point"]?.jsonArray?.let { p ->
                    assertWithMessage("$mode point: $text").that(a!!.x).isEqualTo(p[0].jsonPrimitive.double)
                    assertWithMessage("$mode point: $text").that(a.y).isEqualTo(p[1].jsonPrimitive.double)
                }
            }
        }
    }

    @Test
    fun intentFromThoughtOrInlineThink() {
        for (c in golden["intent"]!!.jsonArray.map { it.jsonObject }) {
            assertWithMessage("reasoning=${str(c, "reasoning")} content=${str(c, "content")}")
                .that(AgentFormat.intent(str(c, "reasoning")!!, str(c, "content")!!)).isEqualTo(str(c, "expected"))
        }
        for (c in golden["invalid_history"]!!.jsonArray.map { it.jsonObject }) {
            assertThat(AgentFormat.invalidStep(str(c, "content")!!)).isEqualTo(AgentFormat.Step("(invalid output)", str(c, "expected")!!))
        }
    }

    @Test
    fun smartResize() {
        for (c in golden["smart_resize"]!!.jsonArray.map { it.jsonObject }) {
            val e = c["expected"]!!.jsonArray
            assertWithMessage("$c").that(AgentFormat.smartResize(c["h"]!!.jsonPrimitive.int, c["w"]!!.jsonPrimitive.int))
                .isEqualTo(e[0].jsonPrimitive.int to e[1].jsonPrimitive.int)
        }
        // The 20:9 test phone (1440 x 3200) gets the converted file's grid: 1280 high x 576 wide, 720 image tokens.
        assertThat(AgentFormat.smartResize(3200, 1440)).isEqualTo(1280 to 576)
    }

    @Test
    fun normToPx() {
        for (c in golden["norm_to_px"]!!.jsonArray.map { it.jsonObject }) {
            val e = c["expected"]!!.jsonArray
            val got = AgentFormat.normToPx(c["x"]!!.jsonPrimitive.double, c["y"]!!.jsonPrimitive.double, c["w"]!!.jsonPrimitive.int, c["h"]!!.jsonPrimitive.int)
            assertWithMessage("$c").that(got).isEqualTo(e[0].jsonPrimitive.int to e[1].jsonPrimitive.int)
        }
    }
}
