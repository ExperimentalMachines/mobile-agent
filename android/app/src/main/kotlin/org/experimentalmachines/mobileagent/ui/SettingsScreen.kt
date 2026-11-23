package org.experimentalmachines.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.experimentalmachines.mobileagent.BuildConfig
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.Settings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = MobileAgentApp.get(LocalContext.current)
    val s by app.settings.values.collectAsState()
    val set: ((Settings.Values) -> Settings.Values) -> Unit = { app.settings.update(it) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar("Settings", onBack)
        Section("Model") {
            Choice("Text decoder", s.textBackend) { v -> set { it.copy(textBackend = v) } }
            Choice("Vision encoder", s.visionBackend) { v -> set { it.copy(visionBackend = v) } }
            Text("A change applies at the next load.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle(
                "New engine every step",
                "Off: one engine for the whole run. On: a fresh engine per step (slower; avoids LiteRT-LM issue #3165, where a new conversation can carry state over from the previous one).",
                s.enginePerStep,
            ) { v -> set { it.copy(enginePerStep = v) } }
        }
        Section("Run") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Step limit", style = MaterialTheme.typography.bodyLarge)
                    Text("The run stops after this many steps. Default ${Settings.DEFAULT_MAX_STEPS}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { set { it.copy(maxSteps = it.maxSteps - 1) } }, enabled = s.maxSteps > 1) { Text("−") }
                Text("${s.maxSteps}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                OutlinedButton(onClick = { set { it.copy(maxSteps = it.maxSteps + 1) } }, enabled = s.maxSteps < Settings.MAX_STEPS_CAP) { Text("+") }
            }
            Toggle("Ask before risky steps", "Pauses on installs, permission prompts, passwords, payments and deleting, and asks you.", s.confirmRisky) { v ->
                set { it.copy(confirmRisky = v) }
            }
            Toggle("Show taps", "A short ring where each tap lands.", s.showTaps) { v -> set { it.copy(showTaps = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Runs kept", style = MaterialTheme.typography.bodyLarge)
                    Text("Older run logs and their screenshots are deleted. Default ${Settings.DEFAULT_KEEP_RUNS}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { set { it.copy(keepRuns = it.keepRuns - 10) } }, enabled = s.keepRuns > 10) { Text("−") }
                Text("${s.keepRuns}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                OutlinedButton(onClick = { set { it.copy(keepRuns = it.keepRuns + 10) } }, enabled = s.keepRuns < Settings.KEEP_RUNS_CAP) { Text("+") }
            }
            Toggle(
                "Press Enter after typing",
                "On: typing ends with Enter, as in the benchmarks the model was measured on. Off: Enter only when the model asks for it.",
                s.enterAfterTyping,
            ) { v -> set { it.copy(enterAfterTyping = v) } }
        }
        Text(
            "LiteRT-LM ${BuildConfig.LITERT_LM_VERSION} · app ${BuildConfig.VERSION_NAME}\nModels: ${app.models.modelsDir.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Choice(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            listOf("gpu" to "GPU", "cpu" to "CPU").forEachIndexed { i, (v, name) ->
                SegmentedButton(selected = value == v, onClick = { onChange(v) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(name) }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, why: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
