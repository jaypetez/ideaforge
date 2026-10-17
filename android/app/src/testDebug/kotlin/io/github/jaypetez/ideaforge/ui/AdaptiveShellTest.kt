package io.github.jaypetez.ideaforge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.interview.ScriptedModel
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.usableRing
import io.github.jaypetez.ideaforge.ux.UxFixtures
import io.github.jaypetez.ideaforge.voice.DriveServiceControl
import io.github.jaypetez.ideaforge.voice.Speaker
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.VoiceMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h818dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveShellTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val graphs = mutableListOf<AppGraph>()

    @After
    fun closeScopes() {
        runBlocking {
            for (graph in graphs) {
                graph.appScope.coroutineContext[Job]?.cancelAndJoin()
                graph.mainScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        compose.waitForIdle()
    }

    private val fixtures by lazy {
        UxFixtures.parse(File(System.getProperty("ideaforge.fixtures"), "ux.json").readText())
    }
    private fun fixture(id: String): Session =
        fixtures.datasets.getValue("long").single { stringOf(it["id"]) == id }

    private fun graph(vararg sessions: Session): AppGraph {
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true)
        graphs += graph
        graph.providerSource = ProviderSource { ScriptedModel() }
        runBlocking { sessions.forEach { assertTrue(graph.sessionStore.insert(it)) } }
        return graph
    }

    private fun await(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 10_000)
    private fun ids(graph: AppGraph) = runBlocking { graph.sessionStore.knownIds() }

    private fun nav(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(
            hasTestTag(BOTTOM_BAR) or hasTestTag("navigation-rail"),
        ))

    private fun select(session: Session, open: Boolean = true) {
        await(LibraryTags.SEARCH)
        val title = sessionDisplayTitle(session)
        compose.onNodeWithTag(LibraryTags.SEARCH).performTextReplacement(title)
        val card = hasTestTag(LibraryTags.CARD) and hasText(title)
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
            .performScrollToNode(card)
        compose.onNodeWithTag(LibraryTags.more(stringOf(session["id"])!!)).performClick()
        compose.onNodeWithTag(LibraryTags.details(stringOf(session["id"])!!)).performClick()
        if (open) {
            val label = if (stringOf(session["status"]) == "done") "View result" else "Continue"
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp")
    fun compactIdeasShowsThreeWholeRowsWithoutFocusingSearchOrStartingVoice() {
        val graph = graph(*fixtures.sessions(fixtures.state("library")).toTypedArray())
        var starts = 0
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts++ }
            override fun stop() {}
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            await(LibraryTags.CARD)
            compose.waitForIdle()
            compose.onNodeWithTag(LibraryTags.SEARCH).assertIsNotFocused()
            val viewport = compose.onNodeWithTag(LibraryTags.LIST).getUnclippedBoundsInRoot()
            val rows = compose.onAllNodesWithTag(LibraryTags.CARD)
            assertTrue("the real compact shell composes at least three ideas", rows.fetchSemanticsNodes().size >= 3)
            for (index in 0..2) {
                val bounds = rows[index].getUnclippedBoundsInRoot()
                assertTrue("idea $index is completely inside the list viewport: $bounds in $viewport",
                    bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            }
            assertEquals("opening Ideas does not start the microphone service", 0, starts)
        }
    }

    @Test
    fun aCompactWindowKeepsOneWorkingBottomNavigationSet() {
        graph()
        ActivityScenario.launch(MainActivity::class.java).use {
            await(BOTTOM_BAR)
            compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
            nav("Ideas").assertIsSelected()
            val bar = compose.onNodeWithTag(BOTTOM_BAR).fetchSemanticsNode().boundsInWindow
            assertTrue("compact navigation is horizontal", bar.width > bar.height)
            nav("Settings").performClick()
            compose.onNodeWithTag("settings-version").assertExists()
            nav("Settings").assertIsSelected()
        }
    }

    @Test
    @Config(qualifiers = "w700dp-h960dp")
    fun aMediumWindowUsesALabelledRailAndContextualInterviewBack() {
        val graph = graph()
        var starts = 0
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts++ }
            override fun stop() {}
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            await("navigation-rail")
            compose.onNodeWithTag(BOTTOM_BAR).assertDoesNotExist()
            val rail = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInWindow
            assertTrue("medium navigation is vertical", rail.height > rail.width)
            for (label in listOf("Ideas", "New idea", "Settings")) nav(label).assertIsDisplayed()
            nav("New idea").performClick()
            await("interview-back")
            compose.onNodeWithTag("interview-title").assertTextEquals("New idea")
            nav("New idea").assertIsSelected()
            compose.onNodeWithTag("interview-back").performClick()
            await(LibraryTags.ZERO)
            assertEquals("navigation must not start the microphone service", 0, starts)
            assertEquals(null, graph.driveController.state.value.mode)
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun resizeAndRecreationKeepTheSelectedLibraryIdeaAndDoNotFocusSearch() {
        val active = fixture("ux-long-active")
        val graph = graph(active, fixture("ux-long-done"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("navigation-rail")
            compose.onNodeWithTag(LibraryTags.SEARCH).assertIsNotFocused()
            select(active, open = false)
            val selected = hasTestTag(LibraryTags.DETAIL) and hasAnyDescendant(hasText(sessionDisplayTitle(active)))
            compose.onNode(selected).assertExists()
            RuntimeEnvironment.setQualifiers("w411dp-h818dp")
            scenario.recreate()
            await(BOTTOM_BAR)
            compose.onNode(selected).assertExists()
            compose.onNode(hasText("Continue") and hasClickAction()).performScrollTo().performClick()
            await("interview-title")
            compose.onNodeWithTag("interview-title").assertTextEquals(sessionDisplayTitle(active))
            assertEquals(setOf("ux-long-active", "ux-long-done"), ids(graph))
        }
    }

    @Test
    fun resizeAndRecreationKeepTheOpenIdeaDraftAndFullLiteralTitle() {
        val active = fixture("ux-long-active")
        val graph = graph(active)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(BOTTOM_BAR)
            select(active)
            await("answer")
            val draft = "A draft that must survive both the rail transition and activity recreation."
            compose.onNodeWithTag("answer").performTextReplacement(draft)
            RuntimeEnvironment.setQualifiers("w1280dp-h900dp")
            scenario.recreate()
            await("navigation-rail")
            await("answer")
            compose.onNodeWithTag("interview-title").assertTextEquals(sessionDisplayTitle(active))
            assertEquals(draft, compose.onNodeWithTag("answer").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text)
            assertEquals(setOf("ux-long-active"), ids(graph))
            assertEquals(draft, stringOf(graph.sessionStore.get("ux-long-active")!!["draftAnswer"]))
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp")
    fun aCompletedIdeaKeepsItsContextAndAskMoreReopensThatSameSession() {
        val done = fixture("ux-long-done")
        val graph = graph(done)
        ActivityScenario.launch(MainActivity::class.java).use {
            await("navigation-rail")
            select(done)
            await(ResultTags.OUTPUT)
            compose.onNodeWithTag(ResultTags.TITLE).assertTextEquals(sessionDisplayTitle(done))
            nav("Ideas").assertIsSelected()
            compose.onNodeWithTag(ResultTags.ASK_MORE).assertIsDisplayed().performClick()
            await("interview-title")
            await("answer")
            compose.onNodeWithTag("interview-title").assertTextEquals(sessionDisplayTitle(done))
            assertEquals(setOf("ux-long-done"), ids(graph))
            assertEquals("interviewing", stringOf(graph.sessionStore.get("ux-long-done")!!["status"]))
        }
    }

    @Test
    @Config(qualifiers = "w700dp-h960dp", fontScale = 2f)
    fun anAttachedVoiceStageHidesNavigationUntilTheRealExit() {
        val active = fixture("ux-long-active")
        val graph = graph(active)
        var starts = 0
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts++ }
            override fun stop() {}
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            await("navigation-rail")
            select(active)
            await("answer")
            compose.runOnUiThread {
                graph.driveController.standDown(active, "typed", "No microphone was started.")
            }
            await("voice-stage")
            compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
            compose.onNodeWithTag(BOTTOM_BAR).assertDoesNotExist()
            compose.onNodeWithTag("interview-back").assertDoesNotExist()
            compose.onNodeWithTag("voice-exit").assertIsDisplayed().performClick()
            await("navigation-rail")
            await("answer")
            assertEquals(setOf("ux-long-active"), ids(graph))
            assertEquals(0, starts)
        }
    }

    @Test
    fun manualNavigationAndDismissedRationaleStartNothingAndDeniedMicStandsDown() {
        val active = fixture("ux-long-active")
        val graph = graph(active)
        var starts = 0
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts++ }
            override fun stop() {}
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(active)
            await("voice-start")
            scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
            assertNull(graph.driveController.state.value.mode)
            compose.onNodeWithTag("voice-start").assertIsDisplayed().performClick()
            await("voice-mic-rationale")
            scenario.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
            compose.onNode(hasText("Not now")).performClick()
            assertEquals(0, starts)
            assertNull(graph.driveController.state.value.mode)
            compose.onNodeWithTag("voice-start").assertIsDisplayed().performClick()
            await("voice-mic-continue")
            compose.onNodeWithTag("voice-mic-continue").performClick()
            compose.waitUntil(10_000) {
                var requested = false
                scenario.onActivity { requested = shadowOf(it).lastRequestedPermission != null }
                requested
            }
            scenario.onActivity {
                val request = shadowOf(it).lastRequestedPermission
                assertNotNull("only Continue requests permission", request)
                assertTrue(Manifest.permission.RECORD_AUDIO in request.requestedPermissions)
                it.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
            }
            await("voice-stage")
            assertEquals(VoiceMode.BLOCKED, graph.driveController.state.value.mode)
            assertEquals(0, starts)
            compose.onNodeWithTag("voice-mic-settings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("voice-exit").assertIsDisplayed()
        }
    }

    @Test
    fun notificationDenialDoesNotBlockTheRealControllerOrAskAgainOnResume() {
        val active = fixture("ux-long-active")
        val graph = graph(active)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var starts = 0
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts++ }
            override fun stop() {}
        }
        graph.driveSpeaker = object : Speaker {
            override suspend fun speak(text: String): SpeakResult = awaitCancellation()
            override fun cancel() {}
            override fun shutdown() {}
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(active)
            compose.onNodeWithTag("voice-start").assertIsDisplayed().performClick()
            await("voice-notification-rationale")
            compose.onNodeWithTag("voice-mic-continue").performClick()
            compose.waitUntil(10_000) {
                var requested = false
                scenario.onActivity { requested = shadowOf(it).lastRequestedPermission != null }
                requested
            }
            scenario.onActivity {
                val request = shadowOf(it).lastRequestedPermission
                assertNotNull(request)
                it.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    request.requestedPermissions.map { p ->
                        if (p == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    }.toIntArray())
            }
            await("voice-stage")
            assertEquals(VoiceMode.ACTIVE, graph.driveController.state.value.mode)
            assertEquals(1, starts)
            compose.onNodeWithTag("voice-notifications-off").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("voice-pause").performClick()
            compose.onNodeWithTag("voice-resume").performClick()
            compose.onNodeWithTag("voice-mic-rationale").assertDoesNotExist()
            assertEquals(VoiceMode.ACTIVE, graph.driveController.state.value.mode)
            compose.onNodeWithTag("voice-exit").performClick()
            await("answer")
        }
    }
}
