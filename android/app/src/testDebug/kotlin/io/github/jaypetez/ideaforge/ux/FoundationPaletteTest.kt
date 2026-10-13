package io.github.jaypetez.ideaforge.ux

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTonalButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton
import io.github.jaypetez.ideaforge.ui.theme.MaterialStateLayer
import io.github.jaypetez.ideaforge.ui.theme.contrastRatio
import io.github.jaypetez.ideaforge.ui.theme.controlPalette
import io.github.jaypetez.ideaforge.ui.theme.controlSurfaces
import io.github.jaypetez.ideaforge.ui.theme.readableColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoundationPaletteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun supplementalPalettesAreExplicitDistinctAndRejectUnknownRequests() {
        assertEquals(3, UxFoundationPalette.entries.size)
        assertEquals("focus-studio-fallback", UxFoundationPalette.FocusStudio.colourSource)
        assertEquals("simulated-dynamic-style", UxFoundationPalette.Ocean.colourSource)
        assertEquals("simulated-dynamic-style", UxFoundationPalette.Plum.colourSource)
        assertNotEquals(UxFoundationPalette.Ocean.scheme(false, lightColorScheme()).primary,
            UxFoundationPalette.Plum.scheme(false, lightColorScheme()).primary)
        assertThrows(IllegalStateException::class.java) { UxFoundationPalette.from("actual-wallpaper-ocean") }
    }

    @Test
    fun bothSimulatedSchemesMeetTheComposedTextEdgeAndStateContractInBothThemes() {
        for (palette in listOf(UxFoundationPalette.Ocean, UxFoundationPalette.Plum)) {
            for (dark in listOf(false, true)) {
                val scheme = palette.scheme(dark, lightColorScheme())
                val colors = scheme.controlPalette()
                for (surface in scheme.controlSurfaces()) {
                    for (ink in listOf(scheme.onSurface, scheme.onSurfaceVariant, colors.quiet, scheme.readableColor(scheme.error))) {
                        assertTrue("$palette/$dark text on $surface", contrastRatio(ink, surface) >= 4.5f)
                    }
                    assertTrue("$palette/$dark operative edge", contrastRatio(colors.edge, surface) >= 3f)
                    assertTrue("$palette/$dark focus", contrastRatio(colors.focus, surface) >= 3f)
                    val state = colors.quiet.copy(alpha = MaterialStateLayer).compositeOver(surface)
                    assertTrue("$palette/$dark state text", contrastRatio(colors.quiet, state) >= 4.5f)
                    assertTrue("$palette/$dark state edge", contrastRatio(colors.edge, state) >= 3f)
                    assertTrue("$palette/$dark state focus", contrastRatio(colors.focus, state) >= 3f)
                }
                assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= 4.5f)
                assertTrue(contrastRatio(colors.onTonal, colors.tonal) >= 4.5f)
                assertTrue(contrastRatio(colors.onDisabled, colors.disabled) >= 4.5f)
            }
        }
    }

    @Test
    fun theSupplementalCompositionRendersProductionControlsAndNeverRecoloursTheSelectedScheme() {
        val palette = mutableStateOf(UxFoundationPalette.FocusStudio)
        val dark = mutableStateOf(false)
        var renderedPrimary = Color.Unspecified
        var requestedPrimary = Color.Unspecified
        compose.setContent {
            IdeaForgeTheme(dark = dark.value, dynamicColor = false) {
                val scheme = palette.value.scheme(dark.value, MaterialTheme.colorScheme)
                MaterialTheme(colorScheme = scheme) {
                    val current = MaterialTheme.colorScheme
                    SideEffect { requestedPrimary = scheme.primary; renderedPrimary = current.primary }
                    Surface { UxFoundationContent(palette.value) }
                }
            }
        }
        for (requested in UxFoundationPalette.entries) {
            for (night in listOf(false, true)) {
                compose.runOnIdle { palette.value = requested; dark.value = night }
                compose.waitForIdle()
                assertEquals(requestedPrimary, renderedPrimary)
                val provenance = compose.onNodeWithTag("ux-foundation-provenance").fetchSemanticsNode()
                val text = provenance.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().joinToString { it.text }
                assertTrue(text.contains(if (requested == UxFoundationPalette.FocusStudio) "dynamicColor=false" else "not actual wallpaper"))
                for (tag in listOf("foundation-primary", "foundation-tonal", "foundation-outlined", "foundation-disabled", "foundation-busy")) {
                    val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    val image = node.captureToImage().toPixelMap()
                    val background = image[image.width / 2, image.height - 4]
                    val layouts = layouts(node.fetchSemanticsNode())
                    assertTrue("inspect actual production text", layouts.isNotEmpty())
                    for (layout in layouts) {
                        val ink = layout.layoutInput.style.color
                        assertEquals("$requested/$night/$tag is not faded", 1f, ink.alpha, 0f)
                        assertTrue("$requested/$night/$tag rendered contrast", contrastRatio(ink, background) >= 4.5f)
                    }
                }
                for (tag in listOf("foundation-voice-pause", "foundation-voice-exit")) {
                    compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
                }
            }
        }
    }

    @Test
    fun theActualFilledPrimaryHasAContrastingPaintedKeyboardFocusEdgeInEveryPalette() {
        requireFilledFocusPaint(primary = true)
    }

    @Test
    fun theConsumedVoiceTonalFillHasAContrastingPaintedKeyboardFocusEdgeInEveryPalette() {
        requireFilledFocusPaint(primary = false)
    }

    private fun requireFilledFocusPaint(primary: Boolean) {
        val palette = mutableStateOf(UxFoundationPalette.FocusStudio)
        val dark = mutableStateOf(false)
        var input: InputModeManager? = null
        var owner: android.view.View? = null
        var clicks = 0
        compose.setContent {
            IdeaForgeTheme(dark = dark.value, dynamicColor = false) {
                val scheme = palette.value.scheme(dark.value, MaterialTheme.colorScheme)
                MaterialTheme(colorScheme = scheme) {
                    input = LocalInputModeManager.current
                    owner = LocalView.current
                    Surface(color = scheme.surfaceContainerHigh) {
                        Column(Modifier.padding(16.dp)) {
                            if (primary) {
                                IdeaForgeButton({ clicks++ }, Modifier.width(180.dp).testTag("filled-focus")) { Text("Continue") }
                            } else {
                                IdeaForgeTonalButton({ clicks++ }, Modifier.width(180.dp).testTag("filled-focus"), voice = true) { Text("Resume") }
                            }
                            IdeaForgeTextButton({}, Modifier.testTag("focus-away")) { Text("Focus elsewhere") }
                        }
                    }
                }
            }
        }
        for (requested in UxFoundationPalette.entries) {
            for (night in listOf(false, true)) {
                compose.runOnIdle {
                    palette.value = requested
                    dark.value = night
                }
                compose.waitForIdle()
                compose.runOnIdle {
                    assertTrue("native keyboard input mode", checkNotNull(input).requestInputMode(InputMode.Keyboard))
                    checkNotNull(owner).requestFocus()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("focus-away")
                    .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue("real focus moves to another control", it()) }
                    .assertIsFocused()
                compose.waitForIdle()
                val node = compose.onNodeWithTag("filled-focus").assertIsDisplayed().assertIsNotFocused()
                val density = node.fetchSemanticsNode().layoutInfo.density.density
                val rest = node.captureToImage().toPixelMap()
                node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue("real button focus request", it()) }
                    .assertIsFocused()
                compose.waitForIdle()
                val focused = node.captureToImage().toPixelMap()
                val x = focused.width / 2
                val edgeY = density.roundToInt()
                val fillY = (5 * density).roundToInt()
                val edge = focused[x, edgeY]
                val fill = focused[x, fillY]
                val restingEdge = rest[x, edgeY]
                val context = "$requested/dark=$night/${if (primary) "primary" else "voice-tonal"}"
                val adjacentContrast = contrastRatio(edge, fill)
                val changeContrast = contrastRatio(edge, restingEdge)
                println("$context restingEdge=${restingEdge.toArgb()} focusedEdge=${edge.toArgb()} adjacentFill=${fill.toArgb()} adjacentContrast=$adjacentContrast changeContrast=$changeContrast")
                assertTrue("$context focused edge contrasts its adjacent painted fill: $adjacentContrast", adjacentContrast >= 3f)
                assertTrue("$context unfocused/focused edge visibly differs: $changeContrast", changeContrast >= 3f)
            }
        }
        assertEquals("focus never invokes an action", 0, clicks)
    }

    private fun layouts(node: SemanticsNode): List<TextLayoutResult> {
        val results = mutableListOf<TextLayoutResult>()
        fun read(current: SemanticsNode) {
            current.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            current.children.forEach(::read)
        }
        compose.runOnUiThread { read(node) }
        return results
    }
}
