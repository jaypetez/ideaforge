package io.github.jaypetez.ideaforge.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import io.github.jaypetez.ideaforge.usableRing
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A phone in landscape (or at a large font scale) has less height than the settings screen
 * needs. Without a scrolling container the backup buttons sat below the bottom bar where no
 * gesture could reach them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h320dp-land", fontScale = 1.5f)
class SettingsScrollTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        installTestGraph(ring = usableRing())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(BOTTOM_BAR), timeoutMillis = 10_000)
    }

    @After
    fun close() = scenario.close()

    @Test
    fun theBackupButtonsCanBeScrolledIntoViewOnAShortWindow() {
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(BOTTOM_BAR))).performClick()
        compose.onNodeWithTag(BackupTags.IMPORT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(BackupTags.EXPORT).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
    fun enlargedConnectionAndBackupActionsReflowWithFullLabelsAndNativeTargets() {
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(BOTTOM_BAR))).performClick()
        for (tag in listOf(SettingsTags.PROVIDER, SettingsTags.CHECK, SettingsTags.SAVE, BackupTags.EXPORT, BackupTags.IMPORT)) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnUiThread { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
            assertTrue("$tag must expose its actual text layout", layouts.isNotEmpty())
            for (layout in layouts) {
                assertFalse(layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse("$tag label is not ellipsized", layout.isLineEllipsized(line))
                    assertTrue("$tag painted width fits", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                    assertTrue("$tag painted height fits", layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
    }
}
