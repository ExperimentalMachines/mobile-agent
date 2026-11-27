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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.agent.AgentController
import org.experimentalmachines.mobileagent.agent.AgentService
import org.experimentalmachines.mobileagent.agent.InstalledApps
import androidx.compose.material3.FilterChip
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
fun HomeScreen(onOpenLog: (String?) -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val app = MobileAgentApp.get(context)
    val grants by produceState(readGrants(context)) { while (true) { value = readGrants(context); delay(750) } }
    val run by app.controller.state.collectAsState()
    val settings by app.settings.values.collectAsState()
    val entries by app.models.entries.collectAsState()
    val download by app.installer.state.collectAsState()
    var goal by rememberSaveable { mutableStateOf("") }
    val selected = entries.firstOrNull { it.sidecar.name == settings.modelSidecar } ?: entries.firstOrNull()
    val installed = selected != null && selected.problem() == null
    val memory = remember(selected) { app.models.memoryProblem(selected) }
    // The app the user was in before opening Mobile Agent, offered as "Continue in ..." (home screen by default).
    val previous = onResumeValue {
        AgentService.instance?.previousApp?.let { pkg ->
            InstalledApps.list(context).firstOrNull { it.packageName == pkg }?.let { AgentController.StartFrom.App(pkg, it.label) }
        }
    }
    var continueThere by rememberSaveable(previous?.packageName) { mutableStateOf(false) }
    val ready = installed && grants.ready && memory == null

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary) {
                Mark(36.dp, Modifier.padding(5.dp), color = MaterialTheme.colorScheme.onPrimary, accent = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Text("mobile agent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LabelPill("RESEARCH PREVIEW")
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A little help.\nAll on your phone.", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.4).sp)
            Text("Give your phone a task. Your agent takes it\none step at a time, with you in control.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(onClick = onSetup, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ready) Glyphs.Check else Glyphs.Download, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when { ready -> "Your agent is ready"; download.busy -> "Setting up your agent"; memory != null -> "This device can’t run the model"; installed -> "One more step"; else -> "Meet your on-device agent" }, style = MaterialTheme.typography.titleSmall)
                    Text(when { ready -> "Local model · phone access enabled"; download.phase == "verifying" -> "Verifying the model…"; download.busy -> "Downloading · ${formatSize(download.bytes)} of 2.76\u00A0GB"; memory != null -> memory; installed -> "Enable phone access to get started"; else -> "Download the model to get started · 2.76\u00A0GB" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("↗", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        GoalCard(goal, { goal = it }, run, ready && !app.controller.running,
            "${settings.maxSteps} steps max · " + if (settings.confirmRisky) "asks before risky actions" else "risky-step check off",
            { app.controller.start(goal, from = previous?.takeIf { continueThere } ?: AgentController.StartFrom.Home) },
            { app.controller.setPaused(it) }, { app.controller.stop() },
            { onOpenLog(it) }, { app.controller.dismissResult() },
            selected?.spec?.let { screenGridCheck(context, it) }?.takeIf { !it.second }?.first,
            blocked = if (memory != null) "Try a phone with more memory." else "Download the model and enable phone access to start.",
            startFrom = { StartFromRow(previous?.label, continueThere) { continueThere = it } })
        if (run !is RunState.Running) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A FEW THINGS TO TRY", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("Set an alarm" to "Set an alarm for 7:30 AM", "Find a setting" to "Open Settings and show me About phone").forEachIndexed { i, (title, task) ->
                    Row(Modifier.fillMaxWidth().clickable { goal = task }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (i == 0) Glyphs.Clock else Glyphs.Settings, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        Text("↗", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Text("Private by design. Tasks and screenshots stay here.\nExperimental: check the result before relying on it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun LabelPill(text: String) {
    Surface(shape = RoundedCornerShape(50), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = .6.sp)
    }
}

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

/**
 * Where the task starts. The home screen by default (where the model was trained to start); when the user came from
 * another app, that app as they left it, for tasks about it ("reply to this", "save this page").
 */
@Composable
private fun StartFromRow(previous: String?, continueThere: Boolean, onChange: (Boolean) -> Unit) {
    if (previous == null) {
        Text("Starts on your home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Starts on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilterChip(selected = !continueThere, onClick = { onChange(false) }, label = { Text("Home screen") })
        FilterChip(
            selected = continueThere, onClick = { onChange(true) },
            label = { Text(previous, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun GoalCard(
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
    blocked: String = "Download the model and enable phone access to start.",
    startFrom: (@Composable () -> Unit)? = null,
) {
    Section("What would you like to do?") {
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
                    placeholder = { Text("Try ‘Set an alarm for 7:30 AM’") },
                    minLines = 2,
                    maxLines = 4,
                )
                startFrom?.invoke()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(limitLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Button(onClick = onRun, enabled = canRun && goal.isNotBlank()) { Text("Start task  ↗") }
                }
                if (!canRun) Text(blocked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
