package org.experimentalmachines.mobileagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Placeholder palette: warm greys and one quiet indigo accent (branding comes later). */
private val Light = lightColorScheme(
    primary = Color(0xFF4257C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7FB),
    onPrimaryContainer = Color(0xFF1A2463),
    secondary = Color(0xFF5E6068),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E8F4),
    onSecondaryContainer = Color(0xFF1F2747),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1B1C1F),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = Color(0xFF5E6068),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F1),
    surfaceContainer = Color(0xFFF0F0ED),
    surfaceContainerHigh = Color(0xFFEAEAE6),
    surfaceContainerHighest = Color(0xFFE4E4E0),
    outline = Color(0xFFC6C7C3),
    outlineVariant = Color(0xFFE1E1DD),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAEBBFF),
    onPrimary = Color(0xFF14206B),
    primaryContainer = Color(0xFF2B3672),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFA3A6AD),
    onSecondary = Color(0xFF1B1C1F),
    secondaryContainer = Color(0xFF2B3045),
    onSecondaryContainer = Color(0xFFDDE1F2),
    background = Color(0xFF131518),
    onBackground = Color(0xFFE6E6E3),
    surface = Color(0xFF131518),
    onSurface = Color(0xFFE6E6E3),
    surfaceVariant = Color(0xFF2A2D32),
    onSurfaceVariant = Color(0xFFA3A6AD),
    surfaceContainerLowest = Color(0xFF0E1013),
    surfaceContainerLow = Color(0xFF181A1E),
    surfaceContainer = Color(0xFF1C1F23),
    surfaceContainerHigh = Color(0xFF24272C),
    surfaceContainerHighest = Color(0xFF2C3035),
    outline = Color(0xFF41454B),
    outlineVariant = Color(0xFF2C2F34),
    error = Color(0xFFF2B8B5),
)

@Immutable
data class StatusColors(val ok: Color, val warn: Color, val mark: Color, val markAccent: Color)

val LocalStatus = staticCompositionLocalOf { StatusColors(Color.Green, Color.Yellow, Color.Black, Color.Blue) }

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun MobileAgentTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) Dark else Light
    val status = if (dark) {
        StatusColors(ok = Color(0xFF7FCF9B), warn = Color(0xFFF2B45A), mark = Color(0xFFE6E6E3), markAccent = Color(0xFF8FA6FF))
    } else {
        StatusColors(ok = Color(0xFF2F7D4F), warn = Color(0xFFB26A00), mark = Color(0xFF1D2127), markAccent = Color(0xFF4257C9))
    }
    val base = Typography()
    val type = base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides status) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/**
 * The placeholder mark, drawn: a screen outline with a tap point and its ripple (same geometry as the launcher icon's
 * foreground on its 108-unit canvas).
 */
@Composable
fun Mark(size: Dp, modifier: Modifier = Modifier, color: Color = LocalStatus.current.mark, accent: Color = LocalStatus.current.markAccent) {
    Canvas(modifier.size(size)) {
        // The outline's path runs 37..71 x 29..79 with a 4-unit stroke, so the mark spans 35..73 x 27..81: fit that.
        val unit = minOf(this.size.width / 54f, this.size.height / 54f)
        val ox = (this.size.width - 38f * unit) / 2f
        val oy = (this.size.height - 54f * unit) / 2f
        fun p(x: Float, y: Float) = Offset(ox + (x - 35f) * unit, oy + (y - 27f) * unit)
        drawRoundRect(
            color = color,
            topLeft = p(37f, 29f),
            size = Size(34f * unit, 50f * unit),
            cornerRadius = CornerRadius(8f * unit),
            style = Stroke(width = 4f * unit),
        )
        drawCircle(color = accent.copy(alpha = 0.55f), radius = 9f * unit, center = p(59f, 61f), style = Stroke(width = 2f * unit))
        drawCircle(color = accent, radius = 4.5f * unit, center = p(59f, 61f))
    }
}
