package io.github.jaypetez.ideaforge.ui.theme

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FoundationThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun themes(check: (Boolean, ColorScheme) -> Unit) {
        val dark = mutableStateOf(false)
        var scheme: ColorScheme? = null
        compose.setContent {
            IdeaForgeTheme(dark = dark.value, dynamicColor = false) {
                val current = MaterialTheme.colorScheme
                SideEffect { scheme = current }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.waitForIdle()
            check(night, checkNotNull(scheme))
        }
    }

    @Test
    fun theRenderedFallbackUsesFocusStudioAndOpaqueCompositesRatherThanOldTokens() = themes { dark, scheme ->
        val expected = if (dark) listOf(
            0xFF11161B, 0xFF1B222A, 0xFF27313B, 0xFF313E49, 0xFFEEF3F8, 0xFFB4C0CB,
            0xFF91A1B2, 0xFF7AD5C7, 0xFF0A332E, 0xFFF3AAA0,
        ) else listOf(
            0xFFF5F7FA, 0xFFFFFFFF, 0xFFE8EDF2, 0xFFDEE6EE, 0xFF17212B, 0xFF526171,
            0xFF718091, 0xFF0F6963, 0xFFFFFFFF, 0xFF983632,
        )
        val actual = listOf(
            scheme.background, scheme.surfaceContainerLowest, scheme.surfaceContainer, scheme.surfaceContainerHigh,
            scheme.onSurface, scheme.onSurfaceVariant, scheme.outline, scheme.primary, scheme.onPrimary, scheme.error,
        )
        assertEquals(expected.map(Long::toInt), actual.map(Color::toArgb))
        assertEquals(scheme.background, scheme.surface)
        assertEquals(scheme.background, scheme.surfaceContainerLow)
        assertEquals(scheme.primary, scheme.secondary)
        assertEquals(scheme.primary, scheme.tertiary)
        assertEquals(scheme.primary, scheme.surfaceTint)
        requireContrast("$dark/inverse text", scheme.inverseOnSurface, scheme.inverseSurface, 4.5f)
        requireContrast("$dark/inverse action", scheme.inversePrimary, scheme.inverseSurface, 4.5f)
        assertEquals(scheme.primary.copy(alpha = 0.10f).compositeOver(scheme.background), scheme.primaryContainer)
        assertEquals(scheme.error.copy(alpha = 0.12f).compositeOver(scheme.background), scheme.errorContainer)
        assertEquals(scheme.onSurface.copy(alpha = 0.16f).compositeOver(scheme.background), scheme.outlineVariant)
        for (color in listOf(scheme.primaryContainer, scheme.errorContainer, scheme.outlineVariant)) assertEquals(1f, color.alpha, 0f)
    }

    @Test
    fun textAndOperativeEdgesPassOnEveryComposedFallbackSurfaceIncludingNativeStateLayers() = themes { dark, scheme ->
        val surfaces = scheme.controlSurfaces()
        val control = scheme.controlPalette()
        for ((name, ink) in mapOf("body" to scheme.onSurface, "metadata" to scheme.onSurfaceVariant)) {
            for (surface in surfaces) requireContrast("$dark/$name", ink, surface, 4.5f)
        }
        for (surface in surfaces) {
            requireContrast("$dark/quiet", control.quiet, surface, 4.5f)
            requireContrast("$dark/quiet native state", control.quiet, control.quiet.copy(alpha = MaterialStateLayer).compositeOver(surface), 4.5f)
            requireContrast("$dark/edge", control.edge, surface, 3f)
            requireContrast("$dark/focus", control.focus, surface, 3f)
            val pressed = scheme.primary.copy(alpha = MaterialStateLayer).compositeOver(surface)
            requireContrast("$dark/pressed edge", control.edge, pressed, 3f)
            requireContrast("$dark/pressed focus", control.focus, pressed, 3f)
            val quietPressed = control.quiet.copy(alpha = MaterialStateLayer).compositeOver(surface)
            requireContrast("$dark/quiet pressed edge", control.edge, quietPressed, 3f)
            requireContrast("$dark/quiet pressed focus", control.focus, quietPressed, 3f)
            requireContrast("$dark/error", scheme.readableColor(scheme.error), surface, 4.5f)
            requireContrast("$dark/success", if (dark) ExtraColors.successDark else ExtraColors.successLight, surface, 4.5f)
            requireContrast("$dark/warning", if (dark) ExtraColors.warningDark else ExtraColors.warningLight, surface, 4.5f)
        }
        requireContrast("$dark/selected pressed edge", control.focus,
            control.onTonal.copy(alpha = MaterialStateLayer).compositeOver(control.tonal), 3f)
        for ((name, pair) in mapOf(
            "primary" to (control.onPrimary to control.primary),
            "tonal" to (control.onTonal to control.tonal),
            "disabled" to (control.onDisabled to control.disabled),
        )) {
            requireContrast("$dark/$name", pair.first, pair.second, 4.5f)
            if (name != "disabled") requireContrast("$dark/$name native state", pair.first,
                pair.first.copy(alpha = MaterialStateLayer).compositeOver(pair.second), 4.5f)
        }
        assertTrue("outlineVariant is a separator, not an operative edge", contrastRatio(scheme.outlineVariant, scheme.surface) < 3f)
    }

    @Test
    fun materialShapesAndEveryTypeRoleReachTheComposition() {
        var shapes: Shapes? = null
        compose.setContent {
            IdeaForgeTheme(dynamicColor = false) {
                val current = MaterialTheme.shapes
                SideEffect { shapes = current }
                assertEquals(IdeaForgeTypography, MaterialTheme.typography)
            }
        }
        compose.waitForIdle()
        val actual = checkNotNull(shapes)
        for ((shape, radius) in listOf(
            actual.extraSmall to 8f, actual.small to 8f, actual.medium to 12f,
            actual.large to 16f, actual.extraLarge to 16f,
        )) {
            assertEquals(radius, shape.topStart.toPx(Size(100f, 100f), Density(1f)), 0f)
            assertEquals(radius, shape.bottomEnd.toPx(Size(100f, 100f), Density(1f)), 0f)
        }
        val t = IdeaForgeTypography
        val roles = listOf(t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall)
        assertEquals(15, roles.size)
        for (role in roles) {
            assertEquals(FontFamily.Default, role.fontFamily)
            assertTrue("no faint tiny metadata role", role.fontSize.value >= 14f)
        }
        for (body in listOf(t.bodyLarge, t.bodyMedium)) {
            assertEquals(16f, body.fontSize.value, 0f)
            assertEquals(25.6f, body.lineHeight.value, 0.001f)
        }
        for (title in listOf(t.headlineLarge, t.headlineMedium, t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall)) {
            assertEquals(FontWeight.SemiBold, title.fontWeight)
        }
        assertEquals(listOf(4, 8, 12, 16, 24, 32, 48, 64).map { it.dp },
            listOf(IdeaForgeSpacing.micro, IdeaForgeSpacing.tight, IdeaForgeSpacing.related, IdeaForgeSpacing.content,
                IdeaForgeSpacing.section, IdeaForgeSpacing.spacious, IdeaForgeSpacing.control, IdeaForgeSpacing.voice))
        assertEquals(520.dp, IdeaForgeWidths.answer)
        assertTrue(IdeaForgeWidths.reading <= IdeaForgeWidths.work)
    }

    @Test
    fun androidTwelvePlusUsesTheEntirePlatformSchemeWithoutRecolouringAnyRole() {
        val dark = mutableStateOf(false)
        var expected: ColorScheme? = null
        var actual: ColorScheme? = null
        compose.setContent {
            val context = LocalContext.current
            val platform = if (dark.value) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            IdeaForgeTheme(dark = dark.value) {
                val current = MaterialTheme.colorScheme
                SideEffect { expected = platform; actual = current }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.waitForIdle()
            // Color is an inline value: its public JVM getters return a packed Long.
            // Compare every role, including future Material additions, not just the accent.
            fun roles(scheme: ColorScheme) = ColorScheme::class.java.methods
                .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
                .associate { it.name to it.invoke(scheme) }
            val wanted = roles(checkNotNull(expected))
            assertTrue("a whole-scheme comparison must not be empty", wanted.size >= 35)
            assertEquals(wanted, roles(checkNotNull(actual)))
        }
    }

    private fun requireContrast(label: String, ink: Color, surface: Color, minimum: Float) {
        val ratio = contrastRatio(ink, surface)
        assertTrue("$label contrast $ratio must be >= $minimum", ratio >= minimum)
    }
}
