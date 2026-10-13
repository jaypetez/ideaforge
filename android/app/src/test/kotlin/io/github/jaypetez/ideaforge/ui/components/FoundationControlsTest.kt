package io.github.jaypetez.ideaforge.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.ui.library.ExportPreview
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoundationControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val buttonTags = listOf("primary", "outlined", "tonal", "quiet", "disabled", "busy", "destructive", "voice")
    private val actionTags = buttonTags + listOf("suggestion", "filter")

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            IdeaForgeTheme(dark = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(IdeaForgeSpacing.content),
                        verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) { content() }
                }
            }
        }
    }

    @Composable
    private fun controls(label: String = "Action", click: () -> Unit = {}) {
        val selected = androidx.compose.runtime.remember { mutableStateOf(false) }
        IdeaForgeButton(click, Modifier.testTag("primary")) { Text(label) }
        IdeaForgeOutlinedButton(click, Modifier.testTag("outlined")) { Text(label) }
        IdeaForgeTonalButton(click, Modifier.testTag("tonal")) { Text(label) }
        IdeaForgeTextButton(click, Modifier.testTag("quiet")) { Text(label) }
        IdeaForgeButton(click, Modifier.testTag("disabled"), enabled = false) { Text("Unavailable") }
        IdeaForgeButton(click, Modifier.testTag("busy"), enabled = false, busy = true) { Text("Checking the connection…") }
        IdeaForgeOutlinedButton(click, Modifier.testTag("destructive"), contentColor = MaterialTheme.colorScheme.error) { Text("Delete") }
        IdeaForgeTonalButton(click, Modifier.testTag("voice"), voice = true) { Text("Start voice") }
        IdeaForgeSuggestionChip(click, { Text(label) }, Modifier.testTag("suggestion"))
        IdeaForgeFilterChip(selected.value, { selected.value = !selected.value }, { Text(label) }, Modifier.testTag("filter"))
        IdeaForgeField("", {}, Modifier.fillMaxWidth().testTag("field"), label = { Text("Server address") },
            placeholder = { Text("Enter an address") }, supportingText = { Text("Required connection details") })
        IdeaForgeStatusText("The connection could not be checked.", Modifier.testTag("error"), StatusTone.Error)
        IdeaForgeStatusText("The checklist is answering this question.", Modifier.testTag("warning"), StatusTone.Warning)
    }

    @Test
    fun consumedMaterialControlsHaveRealTargetsRolesNamesAndStateWithoutBypassingDisabledActions() {
        var clicks = 0
        show { controls(click = { clicks++ }) }
        for (tag in actionTags + "field") {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            node.assertHeightIsAtLeast(if (tag == "voice") 64.dp else 48.dp).assertWidthIsAtLeast(48.dp)
            assertLabelsFit(node.fetchSemanticsNode())
        }
        for (tag in buttonTags) {
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
        compose.onNodeWithTag("busy").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working"))
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
            .performTouchInput { click() }
        assertEquals(0, clicks)
        compose.onNodeWithTag("primary").performScrollTo().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithTag("filter").performScrollTo().performClick().assertIsSelected()
        for ((tag, label) in listOf("error" to "Error:", "warning" to "Warning:")) {
            compose.onNodeWithTag(tag).assertTextContains(label, substring = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
    fun longLabelsAtTwoHundredPercentReflowAndScrollWithoutClippingOrShrinking() {
        show { controls("A deliberately long action label whose complete wording must remain readable") }
        for (tag in actionTags + listOf("field", "error", "warning")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertLabelsFit(node)
            val bounds = node.boundsInWindow
            assertTrue("$tag fits the compact content width", bounds.width <= 328f * node.layoutInfo.density.density + 1f)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 1.3f)
    fun longLabelsAtOneHundredThirtyPercentRetainFullTextAndComfortTargets() {
        show { controls("A longer native action label that wraps when the window requires it") }
        for (tag in actionTags) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            node.assertHeightIsAtLeast(if (tag == "voice") 64.dp else 48.dp)
            assertLabelsFit(node.fetchSemanticsNode())
        }
    }

    @Test
    fun buttonPaintUsesTheEightDpControlShapeInsteadOfMaterialsDefaultPill() {
        var primary = Color.Unspecified
        show {
            primary = MaterialTheme.colorScheme.primary
            IdeaForgeButton({}, Modifier.width(160.dp).testTag("shape")) { Text("Continue") }
        }
        val node = compose.onNodeWithTag("shape")
        val density = node.fetchSemanticsNode().layoutInfo.density.density
        val image = node.captureToImage().toPixelMap()
        assertEquals("12dp from the corner is inside an 8dp control, outside a pill",
            primary.toArgb(), image[(12 * density).roundToInt(), density.roundToInt()].toArgb())
        assertTrue("the actual corner remains rounded", image[0, 0].toArgb() != primary.toArgb())
    }

    @Test
    fun keyboardFocusIsVisibleAndNativePressStillPaintsWithoutChangingTheClickPath() {
        var clicks = 0
        var input: InputModeManager? = null
        var owner: android.view.View? = null
        show {
            input = LocalInputModeManager.current
            owner = LocalView.current
            IdeaForgeTextButton({ clicks++ }, Modifier.width(180.dp).testTag("focus")) { Text("Continue") }
        }
        val node = compose.onNodeWithTag("focus")
        val rest = node.captureToImage().toPixelMap()
        compose.runOnIdle {
            assertTrue("native input mode permits keyboard focus", checkNotNull(input).requestInputMode(InputMode.Keyboard))
            checkNotNull(owner).requestFocus()
        }
        compose.waitForIdle()
        node.performSemanticsAction(SemanticsActions.RequestFocus) {
            assertTrue("button focus request in ${checkNotNull(input).inputMode}", it())
        }
        node.assertIsFocused()
        compose.waitForIdle()
        val focused = node.captureToImage().toPixelMap()
        val ink = focused[focused.width / 2, 1]
        val background = rest[rest.width / 2, 1]
        assertTrue("the focused edge is operative, not a faint state wash", contrastRatio(ink, background) >= 3f)
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(150)
        val pressed = node.captureToImage().toPixelMap()
        val changed = (4 until pressed.height - 4).any { y ->
            (4 until pressed.width - 4).any { x -> pressed[x, y] != rest[x, y] }
        }
        assertTrue("Material's pressed indication still paints", changed)
        assertEquals("a press is not a premature click", 0, clicks)
        node.performTouchInput { up() }
        assertEquals(1, clicks)
    }

    @Test
    fun actualButtonTextAndDisabledPaintStayOpaqueAndReadableOnARaisedSurface() {
        show { controls() }
        for (tag in buttonTags) {
            val node = compose.onNodeWithTag(tag).performScrollTo()
            val image = node.captureToImage().toPixelMap()
            val background = image[image.width / 2, image.height - 4]
            for (layout in textLayouts(node.fetchSemanticsNode())) {
                val ink = layout.layoutInput.style.color
                assertTrue("$tag exposes its resolved native text color", ink != Color.Unspecified)
                assertEquals("$tag is not alpha-faded", 1f, ink.alpha, 0f)
                assertTrue("$tag measured text/background contrast", contrastRatio(ink, background) >= 4.5f)
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun theSharedDocumentConsumesItsReadingMeasureWithoutRewritingTheExport() {
        val markdown = "## Refined prompt\n\nA complete document which retains all of its original words."
        show { ExportPreview(markdown, Modifier.fillMaxWidth().testTag("reading")) }
        compose.onNodeWithTag("reading").assertWidthIsEqualTo(720.dp)
        assertLabelsFit(compose.onNodeWithTag("reading").fetchSemanticsNode())
    }

    private fun textLayouts(node: SemanticsNode): List<TextLayoutResult> {
        val results = mutableListOf<TextLayoutResult>()
        fun collect(current: SemanticsNode) {
            current.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            current.children.forEach(::collect)
        }
        compose.runOnUiThread { collect(node) }
        assertTrue("the guard must inspect real rendered text", results.isNotEmpty())
        return results
    }

    private fun assertLabelsFit(node: SemanticsNode) {
        for (layout in textLayouts(node)) {
            assertFalse("no ellipsized or truncated label", layout.multiParagraph.didExceedMaxLines)
            // MultiParagraph.width retains its max constraint even for intrinsic-width
            // text. Inspect painted lines, as the device recorder does, not that flag.
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("${layout.layoutInput.text}: painted width fits ${layout.size.width}",
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                assertTrue("${layout.layoutInput.text}: painted height fits ${layout.size.height}",
                    layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
    }
}
