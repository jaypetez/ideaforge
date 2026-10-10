package io.github.jaypetez.ideaforge.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
}
