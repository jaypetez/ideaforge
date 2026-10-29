package io.github.jaypetez.ideaforge.ui.settings

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.MAX_BACKUP_BYTES
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.usableRing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    private lateinit var graph: AppGraph

    @Before
    fun launch() {
        graph = installTestGraph(ring = usableRing())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), timeoutMillis = 10_000)
    }

    @After
    fun close() = scenario.close()

    @Test
    fun theBackupButtonsCanBeScrolledIntoViewOnAShortWindow() {
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
        compose.onNodeWithTag(BackupTags.IMPORT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(BackupTags.EXPORT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anOversizeBackupShowsItsErrorWithoutLaunchingTheDocumentPicker() {
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
        runBlocking { graph.sessionStore.save(backupSizedIdea(MAX_BACKUP_BYTES + 1, graph.clock.now())) }
        compose.onNodeWithTag(BackupTags.EXPORT).performScrollTo().performClick()
        compose.waitUntil(10_000) { graph.backupJobs.state.value.failed }
        compose.onNodeWithTag(BackupTags.STATUS).performScrollTo().assertIsDisplayed()
        assertTrue(graph.backupJobs.state.value.message.orEmpty().contains("No backup was created"))
        scenario.onActivity { assertNull("SAF must not create an empty file", shadowOf(it).nextStartedActivityForResult) }
        assertFalse(graph.backupJobs.state.value.busy)
    }

    @Test
    @Config(qualifiers = "w400dp-h900dp", fontScale = 1f)
    fun navigationRetiresHeldBackupPreparationBeforeTheOutgoingCompositionIsDisposed() {
        val io = StandardTestDispatcher()
        val vm = BackupViewModel(graph.sessionStore, FakeDocuments(), graph.clock, graph.idGen, graph.backupJobs, io)
        var retained = false
        var origin: Lifecycle? = null
        scenario.onActivity { activity ->
            activity.setContent {
                val nav = rememberNavController()
                MaterialTheme {
                    NavHost(nav, startDestination = "backup", exitTransition = { fadeOut(tween(1000)) }) {
                        composable("backup") {
                            val owner = LocalLifecycleOwner.current
                            DisposableEffect(owner) {
                                origin = owner.lifecycle
                                retained = true
                                onDispose { retained = false }
                            }
                            Column {
                                BackupSection(vm)
                                Button(onClick = { nav.navigate("other") }, modifier = Modifier.testTag("backup-leave")) {
                                    Text("Leave backup")
                                }
                            }
                        }
                        composable("other") {
                            Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("backup-return")) {
                                Text("Return to backup")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, origin?.currentState)
        compose.onNodeWithTag(BackupTags.EXPORT).performClick()
        assertTrue("serialization is held before the picker", vm.state.value.busy)
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("backup-leave").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertTrue("the old coroutine scope has not been disposed", retained)
            assertFalse("navigation has retired the destination", checkNotNull(origin).currentState.isAtLeast(Lifecycle.State.RESUMED))

            io.scheduler.runCurrent()
            compose.waitUntil(10_000) { !vm.state.value.busy }
            scenario.onActivity {
                assertNull("an outgoing destination must not open SAF", shadowOf(it).nextStartedActivityForResult)
            }

            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("backup-return").performClick()
            compose.onNodeWithTag(BackupTags.EXPORT).performClick()
            io.scheduler.runCurrent()
            compose.waitUntil(10_000) { !vm.state.value.busy }
            scenario.onActivity {
                val request = shadowOf(it).nextStartedActivityForResult
                assertNotNull("a fresh deliberate request still opens the picker", request)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            vm.export(null)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h900dp", fontScale = 1f)
    fun duplicateActivationCannotHidePreparationFromPauseAndResume() {
        val io = StandardTestDispatcher()
        val vm = BackupViewModel(graph.sessionStore, FakeDocuments(), graph.clock, graph.idGen, graph.backupJobs, io)
        scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { BackupSection(vm) } }
        }
        compose.onNodeWithTag(BackupTags.EXPORT).performSemanticsAction(SemanticsActions.OnClick) { click ->
            click()
            click()
        }
        assertTrue(vm.state.value.busy)

        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        io.scheduler.runCurrent()
        compose.waitUntil(10_000) { !vm.state.value.busy }
        scenario.onActivity {
            assertNull("Pause must retire the original request even after duplicate activation", shadowOf(it).nextStartedActivityForResult)
        }

        compose.onNodeWithTag(BackupTags.EXPORT).performClick()
        io.scheduler.runCurrent()
        compose.waitUntil(10_000) { !vm.state.value.busy }
        scenario.onActivity {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(it).nextStartedActivityForResult?.intent?.action)
        }
        vm.export(null)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
    fun enlargedConnectionAndBackupActionsReflowWithFullLabelsAndNativeTargets() {
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
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
