package org.experimentalmachines.mobileagent.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.experimentalmachines.mobileagent.model.LlmRuntime
import java.io.File

/** One model call of a step (a step has two when the first reply cannot be parsed: one retry, as in the benchmarks). */
@Serializable
data class Attempt(
    val text: String,
    val thought: String,
    val modelMs: Long,
    val firstPieceMs: Long?,
    val lastPieceMs: Long? = null,
    val bench: LlmRuntime.Bench?,
    val lenientOk: Boolean,
    val strictOk: Boolean,
)

/** One step: what the model saw and said, what was done, and how long each part took (ms). */
@Serializable
data class StepRecord(
    val step: Int,
    val intent: String,
    /** Canonical action JSON (agent_format.action_to_str), or null when the reply could not be parsed. */
    val action: String?,
    val outcome: String,
    val attempts: List<Attempt>,
    val screenshotMs: Long,
    val resizeMs: Long,
    val pngMs: Long,
    /** Engine creation for this step (engine-per-step mode, or the first step's load); null if the engine was reused. */
    val engineMs: Long?,
    /** All model calls of the step. */
    val modelMs: Long,
    /** LiteRT-LM's time to first token, first call. */
    val ttftMs: Long?,
    val prefillTokens: Int?,
    val prefillTokensPerS: Double?,
    val decodeTokens: Int?,
    val decodeTokensPerS: Double?,
    val actMs: Long,
    val settleMs: Long,
    val stepMs: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    /** Executed point in screen pixels, for taps. */
    val tapX: Int? = null,
    val tapY: Int? = null,
    /** The safety guard's reason and the user's decision ("allowed", "skipped", "stopped", "timeout", "not asked"). */
    val guard: String? = null,
    /** Loop-guard rules (G1/G2) that would have fired; diagnostics only. */
    val loopRules: List<String> = emptyList(),
)

@Serializable
data class RunRecord(
    val runId: String,
    val goal: String,
    val model: String,
    val modelFile: String,
    val imageGrid: String,
    val promptVersion: String,
    val backends: String,
    val enginePerStep: Boolean,
    val enterAfterTyping: Boolean,
    val loadMs: Long?,
    val maxSteps: Int,
    /** complete, infeasible, answered, max_steps, stopped, needs_user, error. */
    val status: String,
    val answer: String? = null,
    val error: String? = null,
    val device: String,
    val soc: String,
    val android: String,
    val litertLm: String,
    val installedApps: List<String>,
    val steps: List<StepRecord>,
    val startedAtMs: Long,
    val endedAtMs: Long,
)

/** Runs on disk: runs/<id>/run.json (+ a small JPEG per step) in the app's external files folder. */
class RunStore(private val root: File) {
    val dir: File get() = root.apply { mkdirs() }
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun runDir(id: String): File = File(dir, id).apply { mkdirs() }

    fun save(record: RunRecord): File = File(runDir(record.runId), "run.json").apply {
        writeText(json.encodeToString(RunRecord.serializer(), record))
    }

    fun load(id: String): RunRecord? = runCatching {
        json.decodeFromString(RunRecord.serializer(), File(File(dir, id), "run.json").readText())
    }.getOrNull()

    fun file(id: String): File = File(File(dir, id), "run.json")

    /** Newest first. */
    fun list(limit: Int = 50): List<RunRecord> = dir.listFiles().orEmpty().filter { it.isDirectory }
        .sortedByDescending { it.name }.asSequence().mapNotNull { load(it.name) }.take(limit).toList()

    fun delete(id: String) {
        File(dir, id).deleteRecursively()
    }
}
