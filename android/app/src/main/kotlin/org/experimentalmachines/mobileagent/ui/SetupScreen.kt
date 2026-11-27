package org.experimentalmachines.mobileagent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.model.RecommendedModel
import java.util.Locale

fun formatSize(bytes: Long) = if (bytes < 1_000_000_000L) "${bytes / 1_000_000} MB" else String.format(Locale.US, "%.2f GB", bytes / 1e9)

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = MobileAgentApp.get(context)
    val grants by produceState(readGrants(context)) { while (true) { value = readGrants(context); delay(750) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TopBar("Your agent", onBack)
        Text("A small setup.\nA model of your own.", fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.8).sp)
        Text("Download once. Then run tasks on your phone, even offline.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelInstallCard()
        Section("02   Connect your phone") {
            Text("You choose what the agent can access. You can pause or stop a task at any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PermissionRows(grants)
        }
        Text("This is an experimental agent. It can make mistakes or stop before a task is finished. Keep an eye on its actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun ModelInstallCard() {
    val app = MobileAgentApp.get(LocalContext.current)
    val context = LocalContext.current
    val download by app.installer.state.collectAsState()
    val entries by app.models.entries.collectAsState()
    val settings by app.settings.values.collectAsState()
    val runtime by app.models.runtime.state.collectAsState()
    val checks by app.models.checks.collectAsState()
    onResumeValue { app.models.refresh() }
    val selected = entries.firstOrNull { it.sidecar.name == settings.modelSidecar } ?: entries.firstOrNull()
    val installed = selected != null && selected.problem() == null
    var consent by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    if (consent) AlertDialog(onDismissRequest = { consent = false },
        title = { Text("Download your model?") },
        text = { Text("Download 2.76 GB from Hugging Face over Wi-Fi. Keep at least 3.1 GB free. Hugging Face receives a normal download request; your tasks and screenshots are never sent. The model uses the LFM Open License v1.0.") },
        confirmButton = { TextButton(onClick = { consent = false; app.installer.start() }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Not now") } })
    Section("01   Get the model", trailing = { if (installed) LabelPill("INSTALLED") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Glyphs.Spark, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Mobile Agent", style = MaterialTheme.typography.titleLarge)
                Text("LFM3 · 2B parameters · int8", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("The engine behind your agent. Reads the screen and chooses the next step, entirely on your device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (download.busy || download.phase == "cancelling") {
            val verifying = download.phase == "verifying"
            Text(if (verifying) "Checking model integrity…" else if (download.phase == "cancelling") "Cancelling…" else "${formatSize(download.bytes)} / 2.76 GB", style = MaterialTheme.typography.labelLarge)
            if (verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { (download.bytes.toFloat() / RecommendedModel.BYTES).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (download.message.isNotEmpty()) Text(download.message, style = MaterialTheme.typography.bodySmall)
            if (!verifying && download.phase != "cancelling") TextButton(onClick = { app.installer.cancel() }) { Text("Cancel download") }
        } else if (!installed) {
            Button(onClick = { consent = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Glyphs.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(if (download.phase == "failed") "Try download again" else "Download model · 2.76 GB")
            }
            Text("From Hugging Face · Wi-Fi only · verified before use", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Check, null, Modifier.size(20.dp), tint = LocalStatus.current.ok)
                Text("Stored on this phone · works offline", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (download.phase == "ready") Text("Download verified with SHA-256.", style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.ok)
        }
        if (download.phase == "failed") Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (download.phase == "idle" && download.message.isNotEmpty()) Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/${RecommendedModel.REPO}"))) }) { Text("Model & license ↗") }
            if (selected != null) TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Details") }
        }
        if (advanced && selected != null) {
            HorizontalDivider()
            Text(selected.spec?.name ?: selected.sidecar.name, style = MaterialTheme.typography.titleSmall)
            selected.problem()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            selected.spec?.let { Text(screenGridCheck(context, it).first, style = MaterialTheme.typography.bodySmall) }
            Text(when (val s = runtime) {
                is LlmRuntime.State.Loading -> "Loading model…"
                is LlmRuntime.State.Failed -> "Load failed: ${s.message}"
                is LlmRuntime.State.Ready -> "Loaded · ${s.loaded.backends}"
                else -> "Loads automatically when you start a task."
            }, style = MaterialTheme.typography.bodySmall)
            checks[selected.sidecar.name]?.let { Text("Checksum: $it", style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = { if (runtime is LlmRuntime.State.Ready) app.models.unloadAsync() else app.models.loadAsync() }, enabled = installed && runtime !is LlmRuntime.State.Loading && !app.controller.running) { Text(if (runtime is LlmRuntime.State.Ready) "Unload" else "Load model") }
                TextButton(onClick = { app.models.verifyAsync(selected) }, enabled = installed) { Text("Verify") }
                if (entries.size > 1) Box {
                    TextButton(onClick = { choose = true }) { Text("Change") }
                    DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                        entries.forEach { e -> DropdownMenuItem(text = { Text(e.spec?.name ?: e.sidecar.name) }, onClick = { choose = false; app.settings.update { it.copy(modelSidecar = e.sidecar.name) } }) }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRows(g: Grants) {
    val context = LocalContext.current
    // After "don't ask again" Android denies at once without a prompt: send the user to the settings page instead.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    var disclosure by remember { mutableStateOf(false) }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Let your agent use the screen") },
        text = { Text("Mobile Agent uses Android Accessibility to capture your screen, read visible controls, and tap, type or scroll during a task you start. Screenshots and task history are stored on this phone, with no upload. It asks before actions it identifies as sensitive; the guard can miss things. You can stop a task or disable access in Android settings at any time.") },
        confirmButton = { TextButton(onClick = { disclosure = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Continue to settings") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Not now") } })
    PermissionRow("Phone access", if (g.accessibilityEnabledNotBound) "Enabled, connecting…" else "See the screen and carry out your task", g.accessibility) { disclosure = true }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    PermissionRow("Floating controls", "Pause or stop while using other apps", g.overlay) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    PermissionRow("Notifications", "Task status and controls · optional", g.notifications) {
        if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    if (!g.accessibility) Text("If Android blocks access: App info → ⋮ → Allow restricted settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PermissionRow(title: String, subtitle: String, ready: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ready) Icon(Glyphs.Check, "$title enabled", Modifier.padding(12.dp).size(24.dp), tint = LocalStatus.current.ok)
        else TextButton(onClick = onClick) { Text("Enable") }
    }
}
