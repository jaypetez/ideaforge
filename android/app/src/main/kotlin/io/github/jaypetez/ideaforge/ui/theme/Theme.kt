package io.github.jaypetez.ideaforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ideaforge.data.Prefs
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Focus Studio fallback. Wallpaper schemes remain the platform's schemes, not recoloured
// copies of this palette.
private data class Tokens(
    val ground: Color, // --surface-container-low
    val lowest: Color, // --surface-container-lowest
    val container: Color, // --surface-container
    val high: Color, // --surface-container-high
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val error: Color,
)

private val LightTokens = Tokens(
    ground = Color(0xFFF5F7FA), lowest = Color(0xFFFFFFFF), container = Color(0xFFE8EDF2),
    high = Color(0xFFDEE6EE), onSurface = Color(0xFF17212B), onSurfaceVariant = Color(0xFF526171),
    outline = Color(0xFF718091), primary = Color(0xFF0F6963),
    onPrimary = Color(0xFFFFFFFF), error = Color(0xFF983632),
)

private val DarkTokens = Tokens(
    ground = Color(0xFF11161B), lowest = Color(0xFF1B222A), container = Color(0xFF27313B),
    high = Color(0xFF313E49), onSurface = Color(0xFFEEF3F8), onSurfaceVariant = Color(0xFFB4C0CB),
    outline = Color(0xFF91A1B2), primary = Color(0xFF7AD5C7),
    onPrimary = Color(0xFF0A332E), error = Color(0xFFF3AAA0),
)

/** Custom roles the web has and Material 3 does not (DESIGN.md: `--success`, `--warning`). */
object ExtraColors {
    val successLight = Color(0xFF306445)
    val successDark = Color(0xFFA0C9A9)
    val warningLight = Color(0xFF765C20)
    val warningDark = Color(0xFFE7C882)
}

private fun Tokens.scheme(dark: Boolean): ColorScheme {
    // "primary at 10%" / "error at 12%" are translucent on the web; Compose wants opaque
    // roles, so composite them over the ground they sit on.
    val primaryContainer = primary.copy(alpha = 0.10f).compositeOver(ground)
    val errorContainer = error.copy(alpha = 0.12f).compositeOver(ground)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // The baseline's secondary and tertiary are Material purple; the web has one accent, so
    // they follow primary. NavigationBar's indicator is secondaryContainer.
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onSurface,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = primaryContainer, onSecondaryContainer = onSurface,
        tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = primaryContainer, onTertiaryContainer = onSurface,
        background = ground, onBackground = onSurface,
        surface = ground, onSurface = onSurface,
        surfaceVariant = container, onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = lowest, surfaceContainerLow = ground,
        surfaceContainer = container, surfaceContainerHigh = high, surfaceContainerHighest = high,
        surfaceBright = lowest, surfaceDim = container,
        surfaceTint = primary,
        inverseSurface = if (dark) LightTokens.high else DarkTokens.high,
        inverseOnSurface = if (dark) LightTokens.onSurface else DarkTokens.onSurface,
        inversePrimary = if (dark) LightTokens.primary else DarkTokens.primary,
        outline = outline, outlineVariant = onSurface.copy(alpha = 0.16f).compositeOver(ground),
        error = error, onError = onPrimary, errorContainer = errorContainer, onErrorContainer = onSurface,
    )
}

/**
 * Every Material role uses the system family. Body copy is 16sp; supporting copy and
 * metadata are at least 14sp, including Material's otherwise smaller default roles.
 */
private val AppFont = FontFamily.Default

val IdeaForgeTypography: Typography = Typography().let { t ->
    fun TextStyle.role(size: Float, line: Float, weight: FontWeight) =
        copy(fontFamily = AppFont, fontSize = size.sp, lineHeight = (size * line).sp, fontWeight = weight)
    t.copy(
        displayLarge = t.displayLarge.role(57f, 1.2f, FontWeight.SemiBold),
        displayMedium = t.displayMedium.role(45f, 1.2f, FontWeight.SemiBold),
        displaySmall = t.displaySmall.role(34f, 1.2f, FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.role(32f, 1.25f, FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.role(28f, 1.25f, FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.role(28f, 1.25f, FontWeight.SemiBold),
        titleLarge = t.titleLarge.role(22f, 1.35f, FontWeight.SemiBold),
        titleMedium = t.titleMedium.role(18f, 1.35f, FontWeight.SemiBold),
        titleSmall = t.titleSmall.role(16f, 1.4f, FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.role(16f, 1.6f, FontWeight.Normal),
        bodyMedium = t.bodyMedium.role(16f, 1.6f, FontWeight.Normal),
        bodySmall = t.bodySmall.role(14f, 1.45f, FontWeight.Normal),
        labelLarge = t.labelLarge.role(14f, 1.35f, FontWeight.Medium),
        labelMedium = t.labelMedium.role(14f, 1.45f, FontWeight.Medium),
        labelSmall = t.labelSmall.role(14f, 1.45f, FontWeight.Medium),
    )
}

/** Light/dark follow the system; dynamic colour from the wallpaper on Android 12+. */
@Composable
fun IdeaForgeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkTokens.scheme(dark = true)
        else -> LightTokens.scheme(dark = false)
    }
    MaterialTheme(colorScheme = scheme, typography = IdeaForgeTypography, shapes = IdeaForgeShapes, content = content)
}

/** Whether [theme] (a [Prefs.theme] value) renders dark, given what the system asks for. */
fun isDarkTheme(theme: String, systemDark: Boolean): Boolean = when (theme) {
    Prefs.THEME_DARK -> true
    Prefs.THEME_LIGHT -> false
    else -> systemDark
}

/**
 * [IdeaForgeTheme] driven by the stored theme choice. Nothing renders until the preferences
 * have been read once (a local file, so a frame or two): rendering the system theme first
 * would flash light before a dark choice lands. A failed read yields the defaults (see
 * PrefsStore.prefs), so this never waits for ever.
 */
@Composable
fun PrefsTheme(
    prefs: Flow<Prefs>,
    onDark: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val current by prefs.collectAsStateWithLifecycle(initialValue = null)
    val theme = current?.theme ?: return
    val dark = isDarkTheme(theme, isSystemInDarkTheme())
    LaunchedEffect(dark) { onDark(dark) }
    IdeaForgeTheme(dark = dark, content = content)
}
