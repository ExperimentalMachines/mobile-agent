package org.experimentalmachines.mobileagent.protocol

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The model's protocol, ported line by line from the research repo's `train/agent_format.py` (the prompt and action
 * format v1-v4 were trained and benchmarked with). Unit tests compare every string here byte for byte with golden
 * output of the Python code (`tools/gen_golden.py`).
 *
 * Model output: `<think>short intent</think>` and one compact JSON action; coordinates on a 0-1000 grid.
 */
object AgentFormat {
    const val HISTORY_STEPS = 8

    val SYSTEM_PROMPT_V1 = """You are a phone-use agent operating an Android phone to complete the user's goal.
Each turn you see the goal, your previous steps, and the current screenshot. Reply with ONE next action as JSON.
Coordinates are integers normalized to 0-1000 (x from left, y from top) of the screenshot.
Actions:
{"action_type":"click","point_2d":[x,y]}
{"action_type":"long_press","point_2d":[x,y]}
{"action_type":"input_text","text":"..."}  (types into the focused field)
{"action_type":"scroll","direction":"up|down|left|right"}  (down = reveal content below)
{"action_type":"open_app","app_name":"..."}
{"action_type":"navigate_back"}
{"action_type":"navigate_home"}
{"action_type":"keyboard_enter"}
{"action_type":"wait"}
{"action_type":"answer","text":"..."}  (reply to a question about the screen)
{"action_type":"status","goal_status":"complete|infeasible"}  (finish the task)"""

    val SYSTEM_PROMPT_V4 = """You are a phone-use agent operating an Android phone to complete the user's goal.
Each turn you see the goal, the installed apps, your previous steps, and the current screenshot. Reply with ONE next action as JSON.
Coordinates are integers normalized to 0-1000 (x from left, y from top) of the screenshot.
Actions:
{"action_type":"click","point_2d":[x,y]}
{"action_type":"long_press","point_2d":[x,y]}
{"action_type":"input_text","text":"..."}  (types into the focused field)
{"action_type":"scroll","direction":"up|down|left|right"}  (down = reveal content below)
{"action_type":"open_app","app_name":"..."}  (app_name must be one of the installed apps)
{"action_type":"navigate_back"}
{"action_type":"navigate_home"}
{"action_type":"keyboard_enter"}
{"action_type":"wait"}
{"action_type":"answer","text":"..."}  (if the goal asks a question: the final reply, as short as possible and in the requested format; ends the task)
{"action_type":"status","goal_status":"complete|infeasible"}  (finish a goal that asks for no information; never use it instead of answering a question)"""

    val PROMPTS = mapOf("v1" to SYSTEM_PROMPT_V1, "v4" to SYSTEM_PROMPT_V4)

    val VALID_TYPES = setOf(
        "click", "long_press", "input_text", "scroll", "open_app", "navigate_back",
        "navigate_home", "keyboard_enter", "wait", "answer", "status",
    )
    val POINT_TYPES = setOf("click", "long_press")
    private val DIRECTIONS = setOf("up", "down", "left", "right")

    /** One earlier step: the model's intent (may be empty) and the canonical action JSON (or the invalid reply). */
    data class Step(val intent: String, val action: String)

    // ---- Prompt ---------------------------------------------------------------------------------------------------

    /** `history_text`: the last [HISTORY_STEPS] steps, numbered from their real position, or "None". */
    fun historyText(history: List<Step>): String {
        if (history.isEmpty()) return "None"
        val start = maxOf(0, history.size - HISTORY_STEPS)
        return history.subList(start, history.size).mapIndexed { k, s ->
            val i = start + 1 + k
            if (s.intent.isNotEmpty()) "$i. ${s.intent} -> ${s.action}" else "$i. ${s.action}"
        }.joinToString("\n")
    }

    fun appsLine(apps: List<String>): String = "Installed apps: " + apps.joinToString(", ")

    /** `user_text`: apps == null is the v1-v3 format, a list is v4 (the app line right after the goal). */
    fun userText(goal: String, history: List<Step>, stepInstruction: String? = null, apps: List<String>? = null): String {
        var s = "Goal: $goal\n" + (if (apps != null) appsLine(apps) + "\n" else "") + "Previous steps:\n${historyText(history)}"
        if (!stepInstruction.isNullOrEmpty()) s += "\nCurrent step hint: $stepInstruction"
        return s + "\nWhat is the next action?"
    }

    /** `PROMPTS[version]` and `user_text(...)`, with build_messages' rule: v4 needs an app list, v1 must not have one. */
    fun messages(version: String, goal: String, history: List<Step>, apps: List<String>?): Pair<String, String> {
        require((version == "v4") == (apps != null)) { "prompt v4 requires an installed-app list and v1 must not have one" }
        val system = PROMPTS[version] ?: error("unknown prompt version $version")
        return system to userText(goal, history, apps = apps)
    }

    // ---- Actions --------------------------------------------------------------------------------------------------

    /**
     * A parsed action. [x], [y] are the clamped point as floats (the executed point); [raw] is the JSON object as the
     * model wrote it, with the fields the lenient parser normalised.
     */
    data class Action(
        val type: String,
        val x: Double? = null,
        val y: Double? = null,
        val text: String? = null,
        val direction: String? = null,
        val appName: String? = null,
        val goalStatus: String? = null,
    )

    /** `action_to_str`: canonical compact JSON (stable key order, no spaces, integer point by truncation). */
    fun actionToStr(a: Action): String {
        val t = a.type
        val sb = StringBuilder("{\"action_type\":").append(PyJsonWriter.string(t))
        when {
            t in POINT_TYPES -> sb.append(",\"point_2d\":[").append(PyText.int(a.x!!)).append(',').append(PyText.int(a.y!!)).append(']')
            t == "input_text" || t == "answer" -> sb.append(",\"text\":").append(PyJsonWriter.string(a.text!!))
            t == "scroll" -> sb.append(",\"direction\":").append(PyJsonWriter.string(a.direction!!))
            t == "open_app" -> sb.append(",\"app_name\":").append(PyJsonWriter.string(a.appName!!))
            t == "status" -> sb.append(",\"goal_status\":").append(PyJsonWriter.string(a.goalStatus ?: "complete"))
        }
        return sb.append('}').toString()
    }

    /**
     * `parse_action(text, strict)`: the final answer (after `</think>`) as an action, or null.
     * strict = the answer is exactly one JSON object with the documented keys; lenient (the agent's) = the first JSON
     * object holding an action, key aliases, coordinate clamping.
     */
    fun parseAction(text: String?, strict: Boolean = false): Action? {
        var t = text ?: return null
        val close = t.indexOf("</think>")
        if (close >= 0) {
            t = t.substring(close + "</think>".length)
        } else if (t.contains("<think>")) {
            return null // unfinished reasoning: an action inside it is not a final answer
        }
        return if (strict) parseStrict(t) else parseLenient(t)
    }

    private fun parseLenient(text: String): Action? {
        var a: LinkedHashMap<String, PyValue>? = null
        val parser = PyJsonParser(text)
        var i = text.indexOf('{')
        while (i != -1 && a == null) {
            try {
                val (obj, _) = parser.rawDecode(i)
                if (obj is PyValue.Dict && ("action_type" in obj.map || "action" in obj.map)) a = obj.map
            } catch (_: PyJsonError) {
            }
            i = text.indexOf('{', i + 1)
        }
        if (a == null) return null
        // t = a.get("action_type") or a.get("action")
        val tv = a["action_type"]?.takeIf { it.truthy } ?: a["action"] ?: PyValue.None
        var t = (tv as? PyValue.Str)?.value ?: return null
        if (t == "type") t = "input_text"
        if (t !in VALID_TYPES) return null
        return when (t) {
            in POINT_TYPES -> {
                // p = a.get("point_2d") or a.get("point") or a.get("coordinate")
                var p: PyValue? = listOf("point_2d", "point").firstNotNullOfOrNull { k -> a[k]?.takeIf { it.truthy } } ?: a["coordinate"]
                if ((p == null || p == PyValue.None) && "x" in a && "y" in a) p = PyValue.List(listOf(a["x"]!!, a["y"]!!))
                val (px, py) = pointPair(p) ?: return null
                if (!px.isFinite() || !py.isFinite()) return null
                Action(t, x = clamp(px), y = clamp(py))
            }
            "scroll" -> {
                val d = (a["direction"] as? PyValue.Str)?.value
                if (d == null || d !in DIRECTIONS) null else Action(t, direction = d)
            }
            "input_text", "answer" -> (a["text"] as? PyValue.Str)?.let { Action(t, text = it.value) }
            "open_app" -> {
                val name = (a["app_name"] as? PyValue.Str)?.value
                    ?: listOf("label", "name", "text", "app").firstNotNullOfOrNull { k -> (a[k] as? PyValue.Str)?.value }
                    ?: return null
                Action(t, appName = name)
            }
            "status" -> when (val gs = a["goal_status"]) {
                null, PyValue.None -> Action(t, goalStatus = "complete") // lenient: omitted status means done
                is PyValue.Str -> if (gs.value == "complete" || gs.value == "infeasible") Action(t, goalStatus = gs.value) else null
                else -> null
            }
            else -> Action(t)
        }
    }

    /** `[float(p[0]), float(p[1])]` with Python's indexing rules (a string indexes its characters). */
    private fun pointPair(p: PyValue?): Pair<Double, Double>? {
        val (a, b) = when (p) {
            is PyValue.List -> if (p.items.size < 2) return null else p.items[0] to p.items[1]
            is PyValue.Str -> {
                val cps = p.value.codePoints().toArray()
                if (cps.size < 2) return null
                PyValue.Str(String(Character.toChars(cps[0]))) to PyValue.Str(String(Character.toChars(cps[1])))
            }
            else -> return null
        }
        val x = PyText.toFloat(a) ?: return null
        val y = PyText.toFloat(b) ?: return null
        return x to y
    }

    /** `min(max(v, 0), 1000)`; the result's value is all that matters here (int vs float only affects printing). */
    private fun clamp(v: Double): Double = if (v < 0) 0.0 else if (v > 1000) 1000.0 else v

    private fun parseStrict(text: String): Action? {
        val obj = try {
            PyJsonParser(PyText.strip(text), allowConstants = false, rejectDuplicates = true).loads()
        } catch (_: PyJsonError) {
            return null
        }
        val a = (obj as? PyValue.Dict)?.map ?: return null
        val t = (a["action_type"] as? PyValue.Str)?.value ?: return null
        val allowed = mapOf(
            "click" to setOf("point_2d"), "long_press" to setOf("point_2d"), "input_text" to setOf("text"),
            "answer" to setOf("text"), "scroll" to setOf("direction"), "open_app" to setOf("app_name"),
            "status" to setOf("goal_status"), "navigate_back" to emptySet(), "navigate_home" to emptySet(),
            "keyboard_enter" to emptySet(), "wait" to emptySet(),
        )
        val keys = allowed[t] ?: return null
        if (a.keys - "action_type" != keys) return null
        return when (t) {
            in POINT_TYPES -> {
                val p = a["point_2d"] as? PyValue.List ?: return null
                if (p.items.size != 2) return null
                val v = p.items.map { (it as? PyValue.Int)?.value ?: return null }
                if (v.any { it.signum() < 0 || it > java.math.BigInteger.valueOf(1000) }) return null
                Action(t, x = v[0].toDouble(), y = v[1].toDouble())
            }
            "input_text", "answer" -> (a["text"] as? PyValue.Str)?.let { Action(t, text = it.value) }
            "open_app" -> (a["app_name"] as? PyValue.Str)?.let { Action(t, appName = it.value) }
            "scroll" -> (a["direction"] as? PyValue.Str)?.value?.takeIf { it in DIRECTIONS }?.let { Action(t, direction = it) }
            "status" -> (a["goal_status"] as? PyValue.Str)?.value?.takeIf { it == "complete" || it == "infeasible" }
                ?.let { Action(t, goalStatus = it) }
            else -> Action(t)
        }
    }

    // ---- Model reply ----------------------------------------------------------------------------------------------

    /**
     * The step's intent, as `lfm_agent.py` takes it: the first line of the reasoning (the runtime's thought channel),
     * or, when that is empty, of the text before `</think>` in the reply; at most 200 code points.
     */
    fun intent(reasoning: String, content: String): String {
        var r = reasoning
        if (PyText.strip(r).isEmpty() && content.contains("</think>")) {
            r = content.substring(0, content.indexOf("</think>")).replace("<think>", "")
        }
        val s = PyText.strip(r)
        return if (s.isEmpty()) "" else PyText.take(PyText.firstLine(s), 200)
    }

    /** The history entry for an unparseable reply: `("(invalid output)", content[:80])`. */
    fun invalidStep(content: String): Step = Step("(invalid output)", PyText.take(content, 80))

    // ---- Image and coordinates ------------------------------------------------------------------------------------

    const val MAX_PIXELS = 880 * 880
    const val MIN_PIXELS = 256 * 256
    const val RESIZE_FACTOR = 32

    /** `smart_resize`: LFM3-VL's image-processor size for a screen; returns (height, width). */
    fun smartResize(height: Int, width: Int, factor: Int = RESIZE_FACTOR, minPixels: Int = MIN_PIXELS, maxPixels: Int = MAX_PIXELS): Pair<Int, Int> {
        var hBar = PyText.round(height.toDouble() / factor) * factor
        var wBar = PyText.round(width.toDouble() / factor) * factor
        if (hBar * wBar > maxPixels) {
            val beta = sqrt((height.toDouble() * width) / maxPixels)
            hBar = maxOf(factor.toLong(), floor(height / beta / factor).toLong() * factor)
            wBar = maxOf(factor.toLong(), floor(width / beta / factor).toLong() * factor)
        } else if (hBar * wBar < minPixels) {
            val beta = sqrt(minPixels / (height.toDouble() * width))
            hBar = ceil(height * beta / factor).toLong() * factor
            wBar = ceil(width * beta / factor).toLong() * factor
        }
        return hBar.toInt() to wBar.toInt()
    }

    /** `norm_to_px`: a 0-1000 point to screen pixels (round half to even, as Python's round). */
    fun normToPx(x: Double, y: Double, width: Int, height: Int): Pair<Int, Int> =
        PyText.round(x / 1000 * (width - 1)).toInt() to PyText.round(y / 1000 * (height - 1)).toInt()
}
