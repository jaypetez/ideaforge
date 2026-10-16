package io.github.jaypetez.ideaforge.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.usableRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = IdeaForgeApp::class, qualifiers = "w320dp-h640dp", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacyPolicyRecoveryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val check = FakeCheck()
    private val failure = hasText("Error: Could not open the privacy policy.", substring = true)

    private fun show(settings: Boolean = false, details: Boolean = false) {
        installTestGraph(check, ring = if (settings) usableRing() else null)
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                assertEquals("https://jaypetez.github.io/ideaforge/guide/privacy-policy.html", uri)
                throw SecurityException("external browser blocked")
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                IdeaForgeTheme(dynamicColor = false) { AppShell() }
            }
        }
        if (settings) {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 10_000)
            compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
        } else {
            compose.waitUntilAtLeastOneExists(hasTestTag("provider-groq"), 10_000)
            if (details) compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        }
        compose.waitUntilAtLeastOneExists(hasText("Privacy policy"), 10_000)
    }

    private fun assertWholeFailureVisible() {
        compose.waitForIdle()
        val node = compose.onNode(failure).fetchSemanticsNode()
        val size = node.layoutInfo.coordinates.size
        val visible = node.boundsInWindow
        assertTrue("the entire failure and recovery address must be revealed without another scroll: $size / $visible",
            size.width > 0 && size.height > 0
                && abs(visible.width - size.width) < 1f && abs(visible.height - size.height) < 1f)
    }

    private fun failedTapsRevealTheirFeedback() {
        compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().performClick()
        assertWholeFailureVisible()

        val scroller = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Privacy policy")))
        val bottom = scroller.fetchSemanticsNode().boundsInWindow.bottom
        val link = compose.onNodeWithText("Privacy policy").fetchSemanticsNode().layoutInfo.coordinates
        val offset = link.localToWindow(Offset.Zero).y + link.size.height - bottom
        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, offset) }
        compose.waitForIdle()
        compose.onNodeWithText("Privacy policy").assertIsDisplayed().performClick()
        assertWholeFailureVisible()
        assertTrue("opening the policy never checks a provider", check.calls.isEmpty())
    }

    @Test
    fun providerSelectionRevealsARejectedPolicyOpenWithoutAnotherScroll() {
        show()
        failedTapsRevealTheirFeedback()
    }

    @Test
    fun providerDetailsRevealsARejectedPolicyOpenWithoutAnotherScroll() {
        show(details = true)
        failedTapsRevealTheirFeedback()
    }

    @Test
    fun settingsAboutRevealsARejectedPolicyOpenWithoutAnotherScroll() {
        show(settings = true)
        failedTapsRevealTheirFeedback()
    }
}
