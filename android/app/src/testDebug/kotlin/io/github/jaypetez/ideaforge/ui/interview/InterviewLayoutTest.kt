package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.usableRing
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import kotlinx.serialization.json.JsonArray
import io.github.jaypetez.ideaforge.ux.UxBounds
import io.github.jaypetez.ideaforge.ux.UxEvidence
import io.github.jaypetez.ideaforge.ux.UxFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h818dp", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterviewLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun anEnlargedCompactComposerLeavesReachableChipsAndWriteUpWithoutClippedLabels() {
        requireLongInterviewReachable()
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 1.3f)
    fun aShortLandscapeKeepsTheWholeSuggestionAndTurnStatusReachable() {
        requireLongInterviewReachable()
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 2f)
    fun anEnlargedShortLandscapeKeepsTheWholeSuggestionAndTurnStatusReachable() {
        requireLongInterviewReachable()
    }

    private fun requireLongInterviewReachable() {
        val data = UxFixtures.parse(File(System.getProperty("ideaforge.fixtures"), "ux.json").readText())
        val active = data.datasets.getValue("long").single { stringOf(it["id"]) == "ux-long-active" }
        val graph = installTestGraph(ring = usableRing())
        graph.providerSource = ProviderSource { ScriptedModel() }
        runBlocking { assertTrue(graph.sessionStore.insert(active)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            compose.onNodeWithTag(LibraryTags.SEARCH).performTextReplacement(stringOf(active["name"])!!)
            val card = hasTestTag(LibraryTags.CARD) and hasText(stringOf(active["name"])!!)
            compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST))).performScrollToNode(card)
            compose.onNode(card).performClick()
            compose.onNode(hasText("Continue") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("question"), 5_000)
            assertTrue("the full thread remains composed", compose.onNodeWithTag("thread").fetchSemanticsNode().layoutInfo.coordinates.size.height > 0)
            for (tag in listOf("answer", "send", "skip", "voice-start", "write-up", "chip-0", "chip-1", "chip-2")) {
                requireReachable(tag)
            }

            requireReachable("turn-status", actionRequired = false)
            compose.onNodeWithTag("write-up").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.OUTPUT), 5_000)
            compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
        }
    }

    @Test
    fun theEnlargedWalkthroughChipTapActuallyFillsTheRealDraftBeforeSend() {
        val graph = installTestGraph(ring = usableRing())
        graph.providerSource = ProviderSource { ScriptedModel() }
        val steps = Vectors.load("walkthrough").cases.single { it.name == "a chip answer" }.input["steps"] as JsonArray
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            compose.onNode(hasText("New idea") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 5_000)
            for (step in steps.take(3)) {
                val previous = compose.onNodeWithTag("question").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single().text
                compose.onNodeWithTag("answer").performTextReplacement(stringOf(step["text"])!!)
                compose.onNodeWithTag("send").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasTestTag("question") and hasText(previous)).fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodes(hasTestTag("question")).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(hasTestTag("interview-busy")).fetchSemanticsNodes().isEmpty()
                }
            }
            val chip = compose.onNodeWithTag("chip-0")
            val expected = chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single().text
            chip.performScrollTo().performClick()
            assertEquals("the turn-four chip must be reached and tapped, not just composed outside the thread viewport", expected,
                compose.onNodeWithTag("answer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }

    private fun requireReachable(tag: String, actionRequired: Boolean = true) {
        val interaction = compose.onNodeWithTag(tag)
        var node = interaction.fetchSemanticsNode()
        fun raw(): UxBounds {
            val coordinates = node.layoutInfo.coordinates
            val top = coordinates.localToWindow(Offset.Zero)
            return UxBounds(top.x, top.y, top.x + coordinates.size.width, top.y + coordinates.size.height)
        }
        fun clipped() = node.boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        if (!clipped().contains(raw())) {
            interaction.performScrollTo()
            node = interaction.fetchSemanticsNode()
        }
        val label = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        UxEvidence.requireControl(tag, raw(), clipped(), label,
            !actionRequired || node.config.contains(SemanticsActions.OnClick) || node.config.contains(SemanticsActions.SetText))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        for (layout in layouts) {
            val lines = (0 until layout.lineCount).toList()
            UxEvidence.requireLabel(tag, lines.maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f,
                lines.maxOfOrNull(layout::getLineBottom) ?: 0f, layout.size.width, layout.size.height,
                layout.multiParagraph.didExceedMaxLines || lines.any(layout::isLineEllipsized))
        }
    }
}
