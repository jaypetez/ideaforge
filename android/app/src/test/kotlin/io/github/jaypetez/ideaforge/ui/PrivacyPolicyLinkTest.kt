package io.github.jaypetez.ideaforge.ui

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivacyPolicyLinkTest {
    @get:Rule
    val compose = createComposeRule()

    private fun failureIsVisibleAndCanBeRetried(error: RuntimeException) {
        var failure: RuntimeException? = error
        val opened = mutableListOf<String>()
        val expected = "https://jaypetez.github.io/ideaforge/guide/privacy-policy.html"
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
                failure?.let { throw it }
            }
        }
        compose.setContent {
            IdeaForgeTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalUriHandler provides handler) { PrivacyPolicyLink() }
            }
        }
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Error: Could not open the privacy policy.", substring = true).assertIsDisplayed()
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        assertEquals(listOf(expected), opened)

        compose.runOnIdle { failure = null }
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Could not open the privacy policy.", substring = true).assertDoesNotExist()
        assertEquals(listOf(expected, expected), opened)
    }

    @Test
    fun aMissingBrowserReportsTheDestinationAndKeepsRetryWorking() {
        failureIsVisibleAndCanBeRetried(ActivityNotFoundException("no browser"))
    }

    @Test
    fun thePlatformUriHandlersWrappedOpenFailureIsNotSwallowed() {
        failureIsVisibleAndCanBeRetried(IllegalArgumentException("cannot open", ActivityNotFoundException()))
    }

    @Test
    fun aBlockedExternalActivityReportsTheDestinationAndKeepsRetryWorking() {
        failureIsVisibleAndCanBeRetried(SecurityException("external activity blocked"))
    }
}
