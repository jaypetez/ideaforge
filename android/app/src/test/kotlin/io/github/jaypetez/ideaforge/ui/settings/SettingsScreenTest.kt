package io.github.jaypetez.ideaforge.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.BuildConfig
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.data.SoftwareKey
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.usableRing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings screen inside the real app shell, with a software keyring key. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun openSettings(ring: JsonObject, key: SoftwareKey = SoftwareKey()): AppGraph {
        val graph = installTestGraph(ring = ring, key = key)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasTestTag(NAVIGATION))
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
        waitFor(hasTestTag(SettingsTags.ACTIVE))
        return graph
    }

    private fun pick(id: String) {
        compose.onNodeWithTag(SettingsTags.PROVIDER).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.provider(id)).performClick()
        compose.waitForIdle()
    }

    private fun localRing() = withCreds(
        emptyKeyring(), "ollama",
        JsonObject(mapOf("baseUrl" to JsonPrimitive("http://10.0.2.2:11434/v1"), "model" to JsonPrimitive("qwen2.5:7b-instruct"))),
    )

    // A local server has no key: the field is not hidden, it is not there at all, so nothing
    // can read a value back out of it. Switching to a hosted provider brings it back.
    @Test
    fun aLocalProviderNeverShowsAKeyField() {
        openSettings(localRing())
        compose.onNodeWithTag(SettingsTags.ACTIVE).assertTextContains("Ollama", substring = true)
        compose.onNodeWithTag(SettingsTags.KEY).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.BASE_URL).assertExists()
        compose.onNodeWithTag(SettingsTags.MODEL).assertExists()

        pick("groq")
        compose.onNodeWithTag(SettingsTags.KEY).assertExists()
        compose.onNodeWithTag(SettingsTags.BASE_URL).assertDoesNotExist()

        pick("custom")
        compose.onNodeWithTag(SettingsTags.KEY).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.BASE_URL).assertExists()
    }

    // The saved key appears masked, never in full.
    @Test
    fun theActiveKeyIsMasked() {
        openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.ACTIVE).assertTextContains("gsk_re…0001", substring = true)
        compose.onNode(hasText("gsk_returning_user_key_0001", substring = true)).assertDoesNotExist()
    }

    @Test
    fun theTriggerWarningAppearsForRiskyWordsAndGoesForSafeOnes() {
        openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.TRIGGER).performScrollTo()
        compose.onNodeWithTag(SettingsTags.TRIGGER_NOTE, useUnmergedTree = true).assertExists()

        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextClearance()
        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextInput("the")
        compose.onNodeWithTag(SettingsTags.TRIGGER_WARNING, useUnmergedTree = true)
            .assertTextContains("is common enough that you will say it mid-answer", substring = true)

        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextClearance()
        compose.onNodeWithTag(SettingsTags.TRIGGER).performTextInput("bananas")
        compose.onNodeWithTag(SettingsTags.TRIGGER_WARNING, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.TRIGGER_NOTE, useUnmergedTree = true).assertExists()
    }

    // Acceptance: Forget all, confirmed, deletes the keyring file and both aliases, and with
    // no provider left the app is back at onboarding with no bottom bar to escape through.
    @Test
    fun forgetAllRoutesToOnboarding() {
        val key = SoftwareKey()
        val graph = openSettings(usableRing(), key)
        key.keys[1] = SoftwareKey.fresh()

        compose.onNodeWithTag(SettingsTags.FORGET).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.FORGET_CONFIRM).performClick()

        waitFor(hasTestTag("provider-groq"))
        compose.onNodeWithTag(NAVIGATION).assertDoesNotExist()
        assertFalse("the keyring file is gone", graph.keyringStore.file.exists())
        assertNull(key.keys[0])
        assertNull(key.keys[1])
    }

    @Test
    fun choosingDarkStoresTheTheme() {
        val graph = openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.theme(Prefs.THEME_DARK)).performScrollTo().performClick()
        // The radio reads the stored value back through the DataStore flow, so it turns on
        // only once the write has landed. (Polling DataStore reads from the test thread
        // instead starves the write.)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(SettingsTags.theme(Prefs.THEME_DARK)) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(Prefs.THEME_DARK, runBlocking { graph.prefsStore.current().theme })
    }

    @Test
    fun theVersionIsShown() {
        openSettings(usableRing())
        compose.onNodeWithTag(SettingsTags.VERSION).performScrollTo().assertIsDisplayed()
            .assertTextContains(BuildConfig.VERSION_NAME, substring = true)
    }
}
