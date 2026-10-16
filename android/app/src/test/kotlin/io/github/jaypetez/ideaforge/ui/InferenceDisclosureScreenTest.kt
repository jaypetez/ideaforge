package io.github.jaypetez.ideaforge.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.usableRing
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InferenceDisclosureScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun hostedOnboardingRequiresAnUncheckedProviderNamedAgreement() {
        val check = FakeCheck()
        installTestGraph(check)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag("provider-groq"), 10_000)
        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
        compose.onNodeWithTag("inference-consent").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithText("IdeaForge sends your idea and answers to Groq", substring = true).assertExists()
        compose.onNodeWithTag("check-key").performScrollTo().assertIsNotEnabled()
        assertTrue("no check or transfer is hidden in disclosure", check.calls.isEmpty())
    }

    @Test
    fun aLegacySetupCannotSeedAnInterviewBeforeAgreement() {
        val graph = installTestGraph(ring = usableRing())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 10_000)
        compose.onNode(hasText("New idea") and hasClickAction() and hasAnyAncestor(hasTestTag(NAVIGATION)))
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("settings-active"), 10_000)
        compose.onNodeWithTag("inference-consent").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithTag("question").assertDoesNotExist()
        assertTrue("denial leaves the local library untouched", graph.sessionStore.sessions.value.isEmpty())
    }

    @Test
    fun voiceAndLocalCopyDescribeTheRealTransferBoundaries() {
        val app = RuntimeEnvironment.getApplication()
        val mic = app.getString(R.string.voice_mic_rationale)
        assertTrue(mic.contains("speech service", ignoreCase = true))
        assertTrue(mic.contains("on-device where available", ignoreCase = true))
        assertTrue(mic.contains("stores no audio", ignoreCase = true))
        assertTrue(mic.contains("screen off", ignoreCase = true) && mic.contains("notification", ignoreCase = true))
        val local = app.getString(R.string.onboarding_local_card, "Ollama")
        assertFalse(local.contains("nothing leaves your network", ignoreCase = true))
        assertTrue(local.contains("hosted", ignoreCase = true))
    }
}
