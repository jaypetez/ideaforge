package io.github.jaypetez.ideaforge.ui.result

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.usableRing
import io.github.jaypetez.ideaforge.ux.UxEvidence
import io.github.jaypetez.ideaforge.ux.UxFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h320dp-land", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun aShortEnlargedRealResultKeepsItsDocumentAndAllActionsReachable() {
        requireRealResultLayout(inline = true)
    }

    @Test
    @Config(qualifiers = "w411dp-h818dp", fontScale = 2f)
    fun aNormalHeightRealResultKeepsTheExistingActionDock() {
        requireRealResultLayout(inline = false)
    }

    private fun requireRealResultLayout(inline: Boolean) {
        val fixtures = UxFixtures.parse(File(System.getProperty("ideaforge.fixtures"), "ux.json").readText())
        val done = fixtures.datasets.getValue("long").single { stringOf(it["id"]) == "ux-long-done" }
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true)
        runBlocking { assertTrue(graph.sessionStore.insert(done)) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            compose.onNodeWithTag(LibraryTags.SEARCH).performTextReplacement(stringOf(done["name"])!!)
            val card = hasTestTag(LibraryTags.CARD) and hasText(stringOf(done["name"])!!)
            compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST))).performScrollToNode(card)
            compose.onNodeWithTag(LibraryTags.more(stringOf(done["id"])!!)).performClick()
            compose.onNodeWithTag(LibraryTags.details(stringOf(done["id"])!!)).performClick()
            compose.onNode(hasText("View result") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.OUTPUT), 5_000)
            scenario.onActivity { activity ->
                activity.window.decorView.dispatchApplyWindowInsets(
                    android.view.WindowInsets.Builder()
                        .setInsets(android.view.WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, 48, 0, 0))
                        .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 48))
                        .build(),
                )
            }
            compose.waitForIdle()
            val inContent = hasAnyAncestor(hasTestTag(ResultTags.CONTENT))
            compose.onNodeWithTag(ResultTags.SHARE).assert(if (inline) inContent else !inContent)

            val scrollMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and hasAnyAncestor(hasTestTag(ResultTags.SCREEN))
            val viewport = compose.onNode(scrollMatcher).fetchSemanticsNode()
            assertTrue("the real Result document viewport must not collapse to zero", viewport.boundsInWindow.height > 0f)
            val output = compose.onNodeWithTag(ResultTags.OUTPUT).fetchSemanticsNode()
            assertTrue("the export retains actual visible content height", output.boundsInWindow.height > 0f)
            fun textNodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                listOf(node).filter { it.config.contains(SemanticsActions.GetTextLayoutResult) } + node.children.flatMap(::textNodes)
            val text = textNodes(output)
            assertTrue("the full export is rendered, not a placeholder", text.size > 10)
            for (node in text) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.runOnUiThread { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
                for (layout in layouts) {
                    val lines = (0 until layout.lineCount).toList()
                    UxEvidence.requireLabel("Result document", lines.maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f,
                        lines.maxOfOrNull(layout::getLineBottom) ?: 0f, layout.size.width, layout.size.height,
                        layout.multiParagraph.didExceedMaxLines || lines.any(layout::isLineEllipsized))
                }
            }
            for (node in listOf(text.first(), text.last())) {
                compose.onNode(SemanticsMatcher("export text ${node.id}") { it.id == node.id }).performScrollTo().assertIsDisplayed()
            }
            for (tag in listOf(ResultTags.SHARE, ResultTags.COPY, ResultTags.SAVE, ResultTags.ASK_MORE)) {
                val action = compose.onNodeWithTag(tag)
                val node = action.fetchSemanticsNode()
                if (node.boundsInWindow.height < node.layoutInfo.coordinates.size.height) action.performScrollTo()
                action.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
        }
    }
}
