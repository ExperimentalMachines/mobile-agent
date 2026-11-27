package org.experimentalmachines.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.experimentalmachines.mobileagent.agent.Decision
import org.experimentalmachines.mobileagent.agent.RunState

/** Four tiny icons drawn here (no icon library). */
object Glyphs {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    val Spark = icon("agent") { moveTo(12f, 2f); lineTo(14.7f, 9.3f); lineTo(22f, 12f); lineTo(14.7f, 14.7f); lineTo(12f, 22f); lineTo(9.3f, 14.7f); lineTo(2f, 12f); lineTo(9.3f, 9.3f); close() }
    val Download = icon("download") { moveTo(11f, 3f); horizontalLineTo(13f); verticalLineTo(13f); lineTo(17f, 9f); lineTo(18.4f, 10.4f); lineTo(12f, 16.8f); lineTo(5.6f, 10.4f); lineTo(7f, 9f); lineTo(11f, 13f); close(); moveTo(4f, 17f); horizontalLineTo(6f); verticalLineTo(20f); horizontalLineTo(18f); verticalLineTo(17f); horizontalLineTo(20f); verticalLineTo(22f); horizontalLineTo(4f); close() }
    val Check = icon("check") { moveTo(4f, 11f); lineTo(9f, 16f); lineTo(20f, 5f); lineTo(21.5f, 6.5f); lineTo(9f, 19f); lineTo(2.5f, 12.5f); close() }
    val Clock = icon("activity") { moveTo(12f, 2f); arcTo(10f,10f,0f,true,true,11.99f,2f); close(); moveTo(12f, 4f); arcTo(8f,8f,0f,true,false,12.01f,4f); close(); moveTo(11f,6f); horizontalLineTo(13f); verticalLineTo(11.5f); lineTo(17f,14f); lineTo(16f,15.6f); lineTo(11f,12.5f); close() }
    val Settings = icon("settings") { moveTo(4f,6f); horizontalLineTo(20f); verticalLineTo(8f); horizontalLineTo(4f); close(); moveTo(4f,16f); horizontalLineTo(20f); verticalLineTo(18f); horizontalLineTo(4f); close(); moveTo(8f,3f); horizontalLineTo(10f); verticalLineTo(11f); horizontalLineTo(8f); close(); moveTo(14f,13f); horizontalLineTo(16f); verticalLineTo(21f); horizontalLineTo(14f); close() }
    val Pause = icon("pause") { moveTo(7f, 5f); horizontalLineTo(10f); verticalLineTo(19f); horizontalLineTo(7f); close(); moveTo(14f, 5f); horizontalLineTo(17f); verticalLineTo(19f); horizontalLineTo(14f); close() }
    val Play = icon("play") { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
    val Stop = icon("stop") { moveTo(6.5f, 6.5f); horizontalLineTo(17.5f); verticalLineTo(17.5f); horizontalLineTo(6.5f); close() }
    val Close = icon("close") {
        moveTo(6.4f, 5f); lineTo(12f, 10.6f); lineTo(17.6f, 5f); lineTo(19f, 6.4f); lineTo(13.4f, 12f); lineTo(19f, 17.6f)
        lineTo(17.6f, 19f); lineTo(12f, 13.4f); lineTo(6.4f, 19f); lineTo(5f, 17.6f); lineTo(10.6f, 12f); lineTo(5f, 6.4f); close()
    }
    val Back = icon("back") {
        moveTo(20f, 11f); horizontalLineTo(7.8f); lineTo(13.4f, 5.4f); lineTo(12f, 4f); lineTo(4f, 12f); lineTo(12f, 20f)
        lineTo(13.4f, 18.6f); lineTo(7.8f, 13f); horizontalLineTo(20f); close()
    }
}

/** The floating control's content: one compact card. */
@Composable
fun RunControl(
    state: RunState,
    onPause: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDecide: (Decision) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(8.dp).widthIn(min = 220.dp, max = 340.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            when (state) {
                is RunState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mark(18.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.step == 0) state.phase else "Step ${state.step}/${state.maxSteps} · ${if (state.paused) "Paused" else state.phase}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                            Text(
                                state.intent ?: state.goal,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onPause(!state.paused) }, modifier = Modifier.size(40.dp)) {
                            Icon(if (state.paused) Glyphs.Play else Glyphs.Pause, if (state.paused) "Resume" else "Pause", Modifier.size(20.dp))
                        }
                        FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                            Icon(Glyphs.Stop, "Stop", Modifier.size(18.dp))
                        }
                    }
                    state.confirm?.let { reason ->
                        Text(
                            "Allow this step? $reason",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDecide(Decision.ALLOW) }) { Text("Allow") }
                            OutlinedButton(onClick = { onDecide(Decision.SKIP) }) { Text("Skip") }
                            TextButton(onClick = { onDecide(Decision.STOP) }) { Text("Stop") }
                        }
                    }
                }
                is RunState.Finished -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Mark(18.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Done · ${state.status} · ${state.steps} steps", style = MaterialTheme.typography.labelLarge)
                        (state.answer ?: state.error)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Glyphs.Close, "Close", Modifier.size(18.dp)) }
                }
                RunState.Idle -> Unit
            }
        }
    }
}
