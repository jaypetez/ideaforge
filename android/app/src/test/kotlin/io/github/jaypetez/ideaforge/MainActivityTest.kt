package io.github.jaypetez.ideaforge

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import io.github.jaypetez.ideaforge.voice.DriveServiceControl
import io.github.jaypetez.ideaforge.voice.Speaker
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var graph: AppGraph
    private val serviceStarts = AtomicInteger()

    // A returning user: with no usable provider the app opens on onboarding instead
    // (OnboardingFlowTest covers that path).
    @Before
    fun launch() {
        graph = installTestGraph(ring = usableRing(), inferenceAgreed = true)
        graph.driveService = object : DriveServiceControl {
            override fun start() { serviceStarts.incrementAndGet() }
            override fun stop() {}
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("v${BuildConfig.VERSION_NAME}"), timeoutMillis = 10_000)
    }

    @After
    fun close() {
        scenario.onActivity { graph.driveController.exit() }
        scenario.close()
        runBlocking {
            graph.appScope.coroutineContext[Job]?.cancelAndJoin()
            graph.mainScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    // The library header also has "New idea"; the bar's items are found inside the bar.
    private fun navItem(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(BOTTOM_BAR)))

    private fun choose(voice: Boolean) {
        navItem("New idea").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-choice"), 10_000)
        compose.onNodeWithTag(if (voice) "new-idea-voice" else "new-idea-type").performClick()
    }

    private fun awaitRows(count: Int) = compose.waitUntil(10_000) {
        var seen = 0
        scenario.onActivity { seen = graph.sessionStore.sessions.value.size }
        seen == count
    }

    @Test
    fun launchesToTheLibraryWithTheBottomBar() {
        compose.onNodeWithText("Your ideas").assertExists()
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertExists()
        for (label in listOf("Ideas", "New idea", "Settings")) navItem(label).assertExists()
    }

    @Test
    fun newIdeaOffersVoiceAndTypingWithoutCreatingOrStartingAnything() {
        navItem("New idea").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-choice"), 10_000)
        compose.onNodeWithTag("new-idea-voice").assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithTag("new-idea-type").assertIsDisplayed()
        assertTrue(graph.sessionStore.sessions.value.isEmpty())
        scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
        assertNull(graph.driveController.state.value.mode)
        assertEquals(0, serviceStarts.get())
        scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-choice"), 10_000)
        assertTrue(graph.sessionStore.sessions.value.isEmpty())
        assertEquals(0, serviceStarts.get())
        compose.onNodeWithTag("new-idea-cancel").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Your ideas"), 10_000)
        assertTrue(graph.sessionStore.sessions.value.isEmpty())
    }

    @Test
    fun theBottomBarNavigatesBetweenDestinations() {
        navItem("Settings").performClick()
        compose.onNodeWithText("Hands-free").assertExists()
        navItem("New idea").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-type"), 10_000)
        compose.onNodeWithTag("new-idea-type").performClick()
        // The interview opens on the hardcoded seed question: no model call, no network.
        compose.waitUntilAtLeastOneExists(hasText(SEED_QUESTION), 5_000)
        navItem("Ideas").performClick()
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertExists()
    }

    @Test
    fun typedChoicesRetainDistinctIdeaIdsAndNeverRequestTheMicrophone() {
        choose(false)
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        compose.onNodeWithTag("answer").performTextInput("First retained typed draft")
        navItem("Ideas").performClick()
        awaitRows(1)
        val first = stringOf(graph.sessionStore.sessions.value.single()["id"])
        choose(false)
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        compose.onNodeWithTag("answer").performTextInput("Second retained typed draft")
        scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        awaitRows(2)
        val ids = graph.sessionStore.sessions.value.map { stringOf(it["id"]) }.toSet()
        assertEquals(2, ids.size)
        assertTrue(first in ids)
        assertEquals(ids, graph.sessionStore.sessions.value.map { stringOf(it["id"]) }.toSet())
        scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
        assertEquals(0, serviceStarts.get())
    }

    @Test
    fun cancellingTheVoiceRationaleLeavesTheSameUsableManualInterview() {
        choose(true)
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-mic-rationale"), 10_000)
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        compose.onNodeWithText("Voice was not started.", substring = true).assertExists()
        compose.onNodeWithTag("answer").performTextInput("I can keep typing after declining voice")
        navItem("Ideas").performClick()
        awaitRows(1)
        scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
        assertEquals(0, serviceStarts.get())
    }

    @Test
    fun microphoneDenialAfterTheExplicitVoiceChoiceReturnsToTyping() {
        choose(true)
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-mic-continue"), 10_000)
        compose.onNodeWithTag("voice-mic-continue").performClick()
        compose.waitUntil(10_000) {
            var requested = false
            scenario.onActivity { requested = shadowOf(it).lastRequestedPermission != null }
            requested
        }
        scenario.onActivity {
            val request = shadowOf(it).lastRequestedPermission
            it.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        compose.onNodeWithText("Microphone access was not granted.", substring = true).assertExists()
        assertEquals(0, serviceStarts.get())
        assertNull(graph.driveController.state.value.mode)
    }

    @Test
    fun recreationRetiresPendingVoiceSetupWithoutRepeatingItsPermissionEffect() {
        choose(true)
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-mic-rationale"), 10_000)
        scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        compose.onNodeWithTag("voice-mic-rationale").assertDoesNotExist()
        compose.onNodeWithText("Voice setup was interrupted.", substring = true).assertExists()
        scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
        assertEquals(0, serviceStarts.get())
    }

    @Test
    fun backgroundingRetiresThePendingRationaleAndReturnsToUsableTyping() {
        choose(true)
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-mic-rationale"), 10_000)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
        compose.onNodeWithTag("voice-mic-rationale").assertDoesNotExist()
        compose.onNodeWithText("Voice setup was interrupted.", substring = true).assertExists()
        scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
        assertEquals(0, serviceStarts.get())
    }

    @Test
    fun grantedVoiceChoiceStartsOneRealControllerRunAndDoesNotReplayAfterRecreation() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val spoken = AtomicInteger()
        graph.driveSpeaker = object : Speaker {
            override suspend fun speak(text: String): SpeakResult {
                spoken.incrementAndGet()
                return awaitCancellation()
            }
            override fun cancel() {}
            override fun shutdown() {}
        }
        choose(true)
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-stage"), 10_000)
        compose.waitUntil(10_000) {
            var seen = 0
            scenario.onActivity { seen = spoken.get() }
            seen == 1
        }
        assertEquals(1, serviceStarts.get())
        scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasTestTag("voice-stage"), 10_000)
        scenario.onActivity {
            assertEquals(1, spoken.get())
            assertNull(shadowOf(it).lastRequestedPermission)
        }
        assertEquals(1, serviceStarts.get())
        compose.onNodeWithTag("voice-exit").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 10_000)
    }

    // The version is read from package.json at build time, not copied; if the two ever
    // disagree the Android build has started keeping its own version.
    @Test
    fun versionNameComesFromPackageJson() {
        val pkg = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "package.json") }
            .first { it.isFile && it.readText().contains("\"name\": \"ideaforge\"") }
            .readText()
        val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg)!!.groupValues[1]
        // `check` runs this for both build types; only debug carries the suffixes.
        val suffix = if (BuildConfig.DEBUG) "-debug" else ""
        assertEquals("$version$suffix", BuildConfig.VERSION_NAME)
        val (major, minor, patch) = version.split('.').map(String::toInt)
        assertEquals(major * 10_000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
        assertEquals(BuildConfig.DEBUG, BuildConfig.APPLICATION_ID.endsWith(".debug"))
    }
}
