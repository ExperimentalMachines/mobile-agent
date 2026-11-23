package org.experimentalmachines.mobileagent.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.agent.RunRecord
import org.experimentalmachines.mobileagent.agent.StepRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TopBar(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Glyphs.Back, "Back", Modifier.size(22.dp)) }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private fun secs(ms: Long?) = ms?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "–"

@Composable
fun LogScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = MobileAgentApp.get(LocalContext.current)
    val state by app.controller.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val runs = remember(state, version) { app.controller.runs.list() }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all runs?") },
            text = { Text("Deletes every run log and its screenshots from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    app.controller.runs.deleteAll()
                    confirmDelete = false
                    version++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("Run log", onBack, trailing = {
            TextButton(onClick = { confirmDelete = true }, enabled = runs.isNotEmpty() && !app.controller.running) { Text("Delete all") }
        })
        Text(
            "Each run keeps its log (model replies, actions, timings) and a small screenshot per step in this app's storage; " +
                "the last ${app.settings.current.keepRuns} runs are kept. Typed passwords are redacted.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (runs.isEmpty()) Text("No runs yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        runs.forEach { r ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(r.runId) }.padding(vertical = 6.dp)) {
                Text(r.goal, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val perStep = r.steps.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.stepMs } / s.size }
                Text(
                    "${SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(r.startedAtMs))} · ${r.status} · ${r.steps.size} steps · ${secs(perStep)}/step",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun RunDetailScreen(runId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = MobileAgentApp.get(context)
    val r: RunRecord? = remember(runId) { app.controller.runs.load(runId) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("Run", onBack, trailing = {
            TextButton(onClick = {
                val f = app.controller.runs.file(runId)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // ClipData carries the read grant to the share sheet itself (its preview) as well as to the target.
                send.clipData = android.content.ClipData.newUri(context.contentResolver, "run.json", uri)
                context.startActivity(Intent.createChooser(send, "Export run.json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }, enabled = r != null) { Text("Export JSON") }
        })
        if (r == null) {
            Text("This run's log could not be read.")
            return@Column
        }
        Section(r.goal) {
            Text("${r.status} · ${r.steps.size} of ${r.maxSteps} steps" + (r.answer?.let { " · answer: $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            if (r.gridMatches == false) {
                Text("This screen's size (${r.screenGrid}) differs from the model's grid (${r.imageGrid}): screenshots were stretched.", style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn)
            }
            Text("${r.installedAppCount} installed apps in the prompt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(
                "${r.model} · ${r.backends} · ${if (r.enginePerStep) "engine per step" else "one engine"}" + (r.loadMs?.let { " · load ${secs(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${r.device} (${r.soc}) · Android ${r.android} · LiteRT-LM ${r.litertLm}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        r.steps.forEach { StepItem(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepItem(s: StepRecord) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${s.step} · ${s.intent.ifEmpty { "(no intent)" }}", style = MaterialTheme.typography.bodyLarge)
        Text(s.action ?: s.attempts.lastOrNull()?.text?.take(200) ?: "", style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s.outcome + (s.guard?.let { " · guard: $it" } ?: "") + (if (s.loopRules.isNotEmpty()) " · loop ${s.loopRules.joinToString("+")}" else ""), style = MaterialTheme.typography.bodySmall)
        val tps = s.decodeTokensPerS?.let { String.format(Locale.US, "%.0f tok/s", it) }
        Text(
            listOfNotNull(
                "shot ${s.screenshotMs} ms",
                "prep ${s.resizeMs + s.pngMs} ms",
                s.engineMs?.let { "engine ${secs(it)}" },
                "model ${secs(s.modelMs)}" + (if (s.attempts.size > 1) " (${s.attempts.size} calls)" else ""),
                s.ttftMs?.let { "TTFT ${secs(it)}" },
                tps?.let { "decode $it" },
                "act ${s.actMs} ms",
                "step ${secs(s.stepMs)}",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
