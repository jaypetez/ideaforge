package io.github.jaypetez.ideaforge.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

object IdeaForgeSpacing {
    val micro = 4.dp
    val tight = 8.dp
    val related = 12.dp
    val content = 16.dp
    val section = 24.dp
    val spacious = 32.dp
    val control = 48.dp
    val voice = 64.dp
}

object IdeaForgeWidths {
    val form = 720.dp
    val work = 1120.dp
    val settings = 1000.dp
    val reading = 720.dp
    val answer = 520.dp
}

object IdeaForgeShape {
    val control = RoundedCornerShape(8.dp)
    val surface = RoundedCornerShape(12.dp)
    val dialog = RoundedCornerShape(16.dp)
    val status = CircleShape
}

val IdeaForgeShapes = Shapes(
    extraSmall = IdeaForgeShape.control,
    small = IdeaForgeShape.control,
    medium = IdeaForgeShape.surface,
    large = IdeaForgeShape.dialog,
    extraLarge = IdeaForgeShape.dialog,
)

data class StatusColors(val success: Color, val warning: Color)

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val ink = foreground.compositeOver(background).luminance()
    val ground = background.luminance()
    return (max(ink, ground) + 0.05f) / (min(ink, ground) + 0.05f)
}

// Shared controls also occur on cards, selected surfaces, callouts and the elevated dock.
// Check the drawn surfaces, not only background. Separators may still use outlineVariant.
internal fun ColorScheme.controlSurfaces(): List<Color> = listOf(
    surface, surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
    surfaceContainerHigh, surfaceContainerHighest, primaryContainer, secondaryContainer,
    errorContainer, surfaceColorAtElevation(2.dp),
).distinct()

internal const val MaterialStateLayer = 0.12f

internal fun ColorScheme.readableColor(
    preferred: Color,
    backgrounds: List<Color> = controlSurfaces(),
    minimum: Float = 4.5f,
    stateLayer: Boolean = false,
): Color {
    fun score(ink: Color) = backgrounds.minOf { background ->
        val resting = contrastRatio(ink, background)
        if (stateLayer) min(resting, contrastRatio(ink, ink.copy(alpha = MaterialStateLayer).compositeOver(background)))
        else resting
    }
    val candidates = listOf(preferred, onSurface, Color.Black, Color.White).distinct()
    return candidates.firstOrNull { score(it) >= minimum } ?: candidates.maxBy(::score)
}

internal data class ControlPalette(
    val primary: Color,
    val onPrimary: Color,
    val tonal: Color,
    val onTonal: Color,
    val quiet: Color,
    val edge: Color,
    val focus: Color,
    val disabled: Color,
    val onDisabled: Color,
)

internal fun ColorScheme.controlPalette(): ControlPalette {
    val surfaces = controlSurfaces()
    val quiet = readableColor(primary, stateLayer = true)
    val tonalInk = readableColor(onSecondaryContainer, listOf(secondaryContainer), stateLayer = true)
    val layered = surfaces.flatMap {
        listOf(it, primary.copy(alpha = MaterialStateLayer).compositeOver(it), quiet.copy(alpha = MaterialStateLayer).compositeOver(it))
    } + tonalInk.copy(alpha = MaterialStateLayer).compositeOver(secondaryContainer)
    return ControlPalette(
        primary = primary,
        onPrimary = readableColor(onPrimary, listOf(primary), stateLayer = true),
        tonal = secondaryContainer,
        onTonal = tonalInk,
        quiet = quiet,
        edge = readableColor(outline, layered, minimum = 3f),
        focus = readableColor(primary, layered, minimum = 3f),
        disabled = surfaceContainer,
        onDisabled = readableColor(onSurfaceVariant, listOf(surfaceContainer)),
    )
}

internal val MaterialTheme.controls: ControlPalette
    @Composable get() {
        val scheme = colorScheme
        return remember(scheme) { scheme.controlPalette() }
    }

val MaterialTheme.statusColors: StatusColors
    @Composable get() {
        val scheme = colorScheme
        return remember(scheme) {
            val dark = scheme.background.luminance() < 0.5f
            StatusColors(
                success = scheme.readableColor(if (dark) ExtraColors.successDark else ExtraColors.successLight),
                warning = scheme.readableColor(if (dark) ExtraColors.warningDark else ExtraColors.warningLight),
            )
        }
    }
