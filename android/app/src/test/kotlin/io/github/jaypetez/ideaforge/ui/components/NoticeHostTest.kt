package io.github.jaypetez.ideaforge.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h818dp", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aLongNativeNoticeKeepsItsWholeMessageScrollableAndDismissibleWithoutCoveringTheNextControl() {
        val state = SnackbarHostState()
        val message = "Something went wrong: " + "/data/user/0/io.github.jaypetez.ideaforge.debug/files/full-error-path/".repeat(12) +
            "open failed: ENOTDIR (Not a directory)"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var outcome: SnackbarResult? = null
        try {
            compose.setContent {
                IdeaForgeTheme(dynamicColor = false) {
                    Column(Modifier.fillMaxSize()) {
                        IdeaForgeSnackbarHost(state)
                        IdeaForgeButton({}, Modifier.testTag("notice-next")) { Text("Continue") }
                    }
                }
            }
            compose.runOnIdle { scope.launch { outcome = state.showSnackbar(message, duration = SnackbarDuration.Indefinite) } }
            compose.waitUntilAtLeastOneExists(hasTestTag("notice-message"), 5_000)
            val notice = compose.onNodeWithTag("notice-message").fetchSemanticsNode()
            assertEquals("not truncated, summarized or replaced", message, notice.config.getOrNull(SemanticsProperties.Text)!!.single().text)
            assertTrue("the enlarged message has a bounded viewport", notice.boundsInWindow.height / notice.layoutInfo.density.density <= 128f)
            assertTrue("the full text has a native scroll action", notice.config.contains(SemanticsActions.ScrollBy))
            compose.onNodeWithTag("notice-message").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            val range = compose.onNodeWithTag("notice-message").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!
            assertTrue("the message can be read to its real end", range.value() >= range.maxValue() - 1f)
            compose.onNodeWithTag("notice-next").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("notice-dismiss").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { outcome != null }
            assertEquals(SnackbarResult.Dismissed, outcome)
        } finally {
            scope.cancel()
        }
    }
}
