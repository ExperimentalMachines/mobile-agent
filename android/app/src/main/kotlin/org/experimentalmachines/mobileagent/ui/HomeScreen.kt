package org.experimentalmachines.mobileagent.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.agent.AgentService
import org.experimentalmachines.mobileagent.agent.RunState
import org.experimentalmachines.mobileagent.model.LlmRuntime
import org.experimentalmachines.mobileagent.model.ModelSpec
import org.experimentalmachines.mobileagent.protocol.AgentFormat
import android.view.WindowManager
import java.util.Locale

/**
 * This screen against the model file's grid: training resized each screenshot with smart_resize, so the file matches
 * a screen only when smart_resize gives its grid (1280 x 576: most 20:9 portrait phones). Returns a line for the UI and
 * whether it matches.
 */
fun screenGridCheck(context: Context, spec: ModelSpec): Pair<String, Boolean> {
    val b = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    val (h, w) = AgentFormat.smartResize(b.height(), b.width())
    val ok = h == spec.imageHeight && w == spec.imageWidth
    return (if (ok) "This screen (${b.width()}×${b.height()}) gives the same ${h}×$w grid as training"
    else "This screen (${b.width()}×${b.height()}) would give a ${h}×$w grid in training, not ${spec.imageHeight}×${spec.imageWidth}: screenshots are stretched (accuracy not measured)") to ok
}

/** The three grants the app needs, read live. */
data class Grants(val accessibility: Boolean, val accessibilityEnabledNotBound: Boolean, val overlay: Boolean, val notifications: Boolean) {
    val ready: Boolean get() = accessibility
}

fun readGrants(context: Context): Grants {
    val component = ComponentName(context, AgentService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { it.equals(component, ignoreCase = true) }
    val bound = AgentService.instance != null
    val nm = context.getSystemService(NotificationManager::class.java)
    return Grants(bound, enabled && !bound, Settings.canDrawOverlays(context), nm.areNotificationsEnabled())
}

/** Re-reads [block] every time the screen comes back (the user returns from a settings page). */
@Composable
fun <T> onResumeValue(block: () -> T): T {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return remember(tick) { block() }
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenLog: (String?) -> Unit) {
    val context = LocalContext.current
    val app = MobileAgentApp.get(context)
    val grants = onResumeValue { readGrants(context) }
    val run by app.controller.state.collectAsState()
    val settings by app.settings.values.collectAsState()
    var goal by rememberSaveable { mutableStateOf("") }
    val entries by app.models.entries.collectAsState()
    val spec = (entries.firstOrNull { it.sidecar.name == settings.modelSidecar } ?: entries.firstOrNull())?.spec
    val grid = spec?.let { onResumeValue { screenGridCheck(context, it) } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Mark(30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Mobile Agent", style = MaterialTheme.typography.titleLarge)
                Text("A phone agent that runs on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onOpenLog(null) }) { Text("Log") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        SetupCard(grants)
        ModelCard()
        GoalCard(
            goal = goal,
            onGoal = { goal = it },
            run = run,
            canRun = grants.ready && !app.controller.running,
            limitLine = "Step limit ${settings.maxSteps} · ${if (settings.enginePerStep) "new engine every step" else "new engine per run"}",
            gridWarning = grid?.takeIf { !it.second }?.first,
            onRun = { app.controller.start(goal) },
            onPause = { app.controller.setPaused(it) },
            onStop = { app.controller.stop() },
            onOpenRun = { onOpenLog(it) },
            onDismiss = { app.controller.dismissResult() },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun Dot(ok: Boolean, warn: Boolean = false) {
    val s = LocalStatus.current
    Box(Modifier.padding(top = 6.dp).size(8.dp).background(if (ok) s.ok else if (warn) s.warn else MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun GrantRow(title: String, why: String, ok: Boolean, action: String, onFix: () -> Unit, note: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Dot(ok, warn = !ok)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn) }
        }
        if (!ok) FilledTonalButton(onClick = onFix, modifier = Modifier.padding(start = 8.dp)) { Text(action) }
    }
}

@Composable
private fun SetupCard(g: Grants) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    val all = g.accessibility && g.overlay && g.notifications
    Section("Setup", trailing = { if (all) Text("Ready", style = MaterialTheme.typography.labelLarge, color = LocalStatus.current.ok) }) {
        GrantRow(
            "Accessibility", "Takes screenshots and taps, types and scrolls for you.", g.accessibility, "Turn on",
            onFix = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            note = when {
                g.accessibilityEnabledNotBound -> "Turned on, starting…"
                !g.accessibility -> "If Android calls it a restricted setting: App info › ⋮ › Allow restricted settings."
                else -> null
            },
        )
        GrantRow(
            "Display over other apps", "Shows the Stop and Pause control during a run.", g.overlay, "Allow",
            onFix = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
        )
        GrantRow(
            "Notifications", "Shows the run's status, with Stop, while other apps are open.", g.notifications, "Allow",
            onFix = {
                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
        )
    }
}

private fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1e9)

@Composable
private fun ModelCard() {
    val context = LocalContext.current
    val app = MobileAgentApp.get(context)
    val models = app.models
    onResumeValue { models.refresh() } // re-scan the models folder whenever the screen comes back
    val entries by models.entries.collectAsState()
    val orphans by models.orphans.collectAsState()
    val checks by models.checks.collectAsState()
    val state by models.runtime.state.collectAsState()
    val settings by app.settings.values.collectAsState()
    val selected = entries.firstOrNull { it.sidecar.name == settings.modelSidecar } ?: entries.firstOrNull()
    var pick by remember { mutableStateOf(false) }

    Section("Model", trailing = {
        if (entries.size > 1) {
            Box {
                TextButton(onClick = { pick = true }) { Text("Change") }
                DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                    entries.forEach { e ->
                        DropdownMenuItem(text = { Text(e.spec?.name ?: e.sidecar.name) }, onClick = {
                            pick = false
                            app.settings.update { it.copy(modelSidecar = e.sidecar.name) }
                        })
                    }
                }
            }
        }
    }) {
        if (selected == null) {
            Text("No model yet.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Copy the .litertlm file and its model.json into\n${models.modelsDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            orphans.firstOrNull()?.let { Text("Found ${it.name} without a model.json.", style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn) }
            return@Section
        }
        val spec = selected.spec
        val problem = selected.problem()
        Row(verticalAlignment = Alignment.Top) {
            Dot(problem == null, warn = problem != null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(spec?.name ?: selected.sidecar.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        spec?.file,
                        selected.actualBytes?.let { gb(it) },
                        spec?.let { "image ${it.imageHeight}×${it.imageWidth}" },
                        spec?.let { "ctx ${it.contextTokens}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    problem ?: (if (selected.spec?.bytes != null) "Size matches the sidecar" else "Present (no size in the sidecar)") +
                        (checks[selected.sidecar.name]?.let { " · checksum $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem == null) MaterialTheme.colorScheme.onSurfaceVariant else LocalStatus.current.warn,
                )
                spec?.let { s ->
                    val (line, ok) = onResumeValue { screenGridCheck(context, s) }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else LocalStatus.current.warn)
                }
            }
        }
        val loadedHere = (state as? LlmRuntime.State.Ready)?.loaded?.takeIf { it.file == selected.modelFile }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(loadedHere != null)
            Spacer(Modifier.width(12.dp))
            Text(
                when (val s = state) {
                    is LlmRuntime.State.Loading -> "Loading ${s.what}…"
                    is LlmRuntime.State.Failed -> "Load failed: ${s.message}"
                    is LlmRuntime.State.Ready -> if (loadedHere != null) {
                        "Loaded · ${s.loaded.backends} · ${String.format(Locale.US, "%.1f", s.loaded.loadMs / 1000.0)} s"
                    } else {
                        "Another model is loaded"
                    }
                    LlmRuntime.State.NotLoaded -> "Not loaded · will use text ${settings.textBackend.uppercase()}, vision ${settings.visionBackend.uppercase()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is LlmRuntime.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loadedHere != null) {
                OutlinedButton(onClick = { models.unloadAsync() }, enabled = !app.controller.running) { Text("Unload") }
            } else {
                FilledTonalButton(onClick = { models.loadAsync() }, enabled = problem == null && state !is LlmRuntime.State.Loading) { Text("Load") }
            }
            if (spec?.sha256 != null) TextButton(onClick = { models.verifyAsync(selected) }, enabled = problem == null) { Text("Verify checksum") }
        }
    }
}

@Composable
private fun GoalCard(
    goal: String,
    onGoal: (String) -> Unit,
    run: RunState,
    canRun: Boolean,
    limitLine: String,
    onRun: () -> Unit,
    onPause: (Boolean) -> Unit,
    onStop: () -> Unit,
    onOpenRun: (String) -> Unit,
    onDismiss: () -> Unit,
    gridWarning: String? = null,
) {
    Section("Goal") {
        when (run) {
            is RunState.Running -> {
                Text(run.goal, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (run.step == 0) run.phase else "Step ${run.step} of ${run.maxSteps} · ${if (run.paused) "Paused" else run.phase}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                run.intent?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                run.action?.let { Text(it, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                run.confirm?.let { Text("Waiting for you on the floating control: $it", style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPause(!run.paused) }) { Text(if (run.paused) "Resume" else "Pause") }
                    Button(onClick = onStop) { Text("Stop") }
                }
            }
            else -> {
                OutlinedTextField(
                    value = goal,
                    onValueChange = onGoal,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What should it do? e.g. Open the Clock app") },
                    minLines = 2,
                    maxLines = 4,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(limitLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Button(onClick = onRun, enabled = canRun && goal.isNotBlank()) { Text("Run") }
                }
                if (!canRun) Text("Turn on Accessibility first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                gridWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn) }
                if (run is RunState.Finished) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Last run · ${run.status} · ${run.steps} steps" + (run.secondsPerStep?.let { String.format(Locale.US, " · %.1f s/step", it) } ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(run.goal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            run.answer?.let { Text("Answer: $it", style = MaterialTheme.typography.bodyMedium) }
                            run.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            Row {
                                TextButton(onClick = { onOpenRun(run.runId) }) { Text("View log") }
                                TextButton(onClick = onDismiss) { Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}
