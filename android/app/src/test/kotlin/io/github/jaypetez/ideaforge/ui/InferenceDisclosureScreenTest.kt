package io.github.jaypetez.ideaforge.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.usableRing
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.ui.settings.MemoryDataStore
import io.github.jaypetez.ideaforge.ui.settings.SettingsTags
import io.github.jaypetez.ideaforge.voice.DriveServiceControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

private class DelayedAdmissionPreferences : DataStore<Preferences> {
    private val memory = MemoryDataStore()
    @Volatile var hold: CompletableDeferred<Unit>? = null
    @Volatile var waiting = false
    override val data: Flow<Preferences> get() = flow {
        hold?.let {
            waiting = true
            try { it.await() } finally { waiting = false }
        }
        emitAll(memory.data)
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        memory.updateData(transform)
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InferenceDisclosureScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val ownedGraphs = mutableListOf<AppGraph>()

    @After
    fun close() {
        scenario?.close()
        runBlocking {
            for (graph in ownedGraphs) {
                graph.appScope.coroutineContext[Job]?.cancelAndJoin()
                graph.mainScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
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
    fun onboardingWarnsBeforePlainHttpLeavesTheDeviceButNotForHttpsOrLoopback() {
        val check = FakeCheck()
        val graph = installTestGraph(check, preferences = PrefsStore(MemoryDataStore()))
        ownedGraphs += graph
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag("provider-ollama"), 10_000)
        compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
        compose.onNodeWithTag("base-url").performTextReplacement("http://192.168.1.20:11434/v1")
        compose.waitUntilAtLeastOneExists(hasTestTag("local-http-warning"), 10_000)
        compose.onNodeWithTag("local-http-warning").performScrollTo().assertIsDisplayed()
            .assertTextContains("not encrypted", substring = true)
        compose.onNodeWithTag("inference-consent").performScrollTo().assertIsOff()
        assertTrue("warning does not contact a server", check.calls.isEmpty())
        assertTrue("warning does not seed an interview", graph.sessionStore.sessions.value.isEmpty())

        for (address in listOf("https://192.168.1.20:11434/v1", "http://localhost:11434/v1")) {
            compose.onNodeWithTag("base-url").performScrollTo().performTextReplacement(address)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("local-http-warning").fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun settingsWarnsForTheSavedLanAddressAndTracksTheEditedTransport() {
        val check = FakeCheck()
        val ring = withCreds(emptyKeyring(), "ollama", JsonObject(mapOf(
            "baseUrl" to JsonPrimitive("http://192.168.1.20:11434/v1"),
            "model" to JsonPrimitive("scripted-local-model"),
        )))
        val graph = installTestGraph(check, ring = ring, preferences = PrefsStore(MemoryDataStore()))
        ownedGraphs += graph
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 10_000)
        compose.onNode(hasText("Settings") and hasClickAction() and hasAnyAncestor(hasTestTag(NAVIGATION)))
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("local-http-warning"), 10_000)
        compose.onNodeWithTag("local-http-warning").performScrollTo().assertIsDisplayed()
            .assertTextContains("not encrypted", substring = true)
        assertTrue("viewing the warning sends no request", check.calls.isEmpty())
        for (address in listOf("https://192.168.1.20:11434/v1", "http://127.0.0.1:11434/v1")) {
            compose.onNodeWithTag(SettingsTags.BASE_URL).performScrollTo().performTextReplacement(address)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("local-http-warning").fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h818dp", fontScale = 1f)
    fun aCancelledLateContinueCannotLaunchPermissionsFromANewerRationale() {
        val preferences = DelayedAdmissionPreferences()
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true,
            preferences = PrefsStore(preferences))
        ownedGraphs += graph
        val starts = AtomicInteger()
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts.incrementAndGet() }
            override fun stop() {}
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 10_000)
        compose.onNode(hasText("New idea") and hasClickAction() and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-voice"), 10_000)
        compose.onNodeWithTag("new-idea-voice").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-mic-continue"), 10_000)
        val held = CompletableDeferred<Unit>()
        preferences.hold = held
        try {
            compose.onNodeWithTag("voice-mic-continue").performClick()
            compose.waitUntil(10_000) {
                var waiting = false
                scenario!!.onActivity { waiting = preferences.waiting }
                waiting
            }
            preferences.hold = null
            compose.onNodeWithText("Not now").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
            compose.onNodeWithTag("voice-start").assertIsDisplayed().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("voice-mic-continue"), 10_000)
            held.complete(Unit)
            compose.waitUntil(10_000) {
                var finished = false
                scenario!!.onActivity { finished = !preferences.waiting }
                finished
            }
            scenario!!.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
            compose.onNodeWithTag("voice-mic-rationale").assertIsDisplayed()
            assertEquals(0, starts.get())
            compose.onNodeWithText("Not now").performClick()
        } finally {
            preferences.hold = null
            held.complete(Unit)
        }
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
        assertTrue(mic.contains("screen off", ignoreCase = true))
        val notifications = app.getString(R.string.voice_notifications_title)
        assertTrue(notifications.contains("notification", ignoreCase = true) && notifications.contains("optional", ignoreCase = true))
        val controls = app.getString(R.string.voice_notifications_rationale)
        assertTrue(listOf("Pause", "Resume", "Exit").all { controls.contains(it) })
        assertTrue(controls.contains("does not block voice", ignoreCase = true))
        val local = app.getString(R.string.onboarding_local_card, "Ollama")
        assertFalse(local.contains("nothing leaves your network", ignoreCase = true))
        assertTrue(local.contains("hosted", ignoreCase = true))
    }

    private fun delayedAdmission(agreed: Boolean, newer: String) {
        val preferences = DelayedAdmissionPreferences()
        installTestGraph(ring = usableRing(), inferenceAgreed = agreed, preferences = PrefsStore(preferences))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 10_000)
        val hold = CompletableDeferred<Unit>()
        preferences.hold = hold
        try {
            compose.onNode(hasText("New idea") and hasClickAction() and hasAnyAncestor(hasTestTag(NAVIGATION)))
                .performClick()
            compose.waitUntil(10_000) { preferences.waiting }
            compose.onNode(hasText(newer) and hasClickAction() and hasAnyAncestor(hasTestTag(NAVIGATION)))
                .performClick()
            hold.complete(Unit)
            compose.waitForIdle()
            if (newer == "Settings") {
                compose.waitUntilAtLeastOneExists(hasTestTag("settings-active"), 10_000)
            } else {
                compose.waitUntilAtLeastOneExists(hasText("v${io.github.jaypetez.ideaforge.BuildConfig.VERSION_NAME}"), 10_000)
                compose.onNodeWithTag("settings-active").assertDoesNotExist()
            }
            compose.onNodeWithTag("question").assertDoesNotExist()
        } finally {
            hold.complete(Unit)
        }
    }

    @Test
    fun aDelayedSuccessfulNewIdeaCannotReplaceNewerSettingsNavigation() = delayedAdmission(true, "Settings")

    @Test
    fun aDelayedDeniedNewIdeaCannotReplaceANewerIdeasTap() = delayedAdmission(false, "Ideas")
}
