package io.github.jaypetez.ideaforge.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.interview.InterviewScreen
import io.github.jaypetez.ideaforge.ui.interview.InterviewViewModel
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.result.ResultHarness
import io.github.jaypetez.ideaforge.ui.result.ResultScreen
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.ux.UxBounds
import io.github.jaypetez.ideaforge.ux.UxFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w640dp-h320dp-land", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeVisibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val files = TemporaryFolder(File(File("build"), "ux-notice-tests").apply { mkdirs() })

    private val fixtures by lazy {
        UxFixtures.parse(File(System.getProperty("ideaforge.fixtures"), "ux.json").readText())
    }

    @Test
    fun aDelayedSaveFailureIsVisibleAfterTheShortResultReturnsToTheTopOfItsLongExport() {
        val done = fixtures.datasets.getValue("long").single { stringOf(it["id"]) == "ux-long-done" }
        val scheduler = TestCoroutineScheduler()
        val harness = ResultHarness(files.newFolder(), StandardTestDispatcher(scheduler), done)
        harness.sink.fail = IOException("controlled delayed document failure")
        val vm = harness.vm(stringOf(done["id"])!!)
        try {
            compose.setContent {
                IdeaForgeTheme(dynamicColor = false) { ResultScreen(vm, onBack = {}, onAskMore = {}) }
            }
            compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.OUTPUT), 5_000)
            compose.onNodeWithTag(ResultTags.SAVE).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { vm.save(Uri.parse("content://documents/controlled.md")) }
            assertNull("the save has not completed while I/O is held", vm.state.value.notice)
            compose.onNodeWithTag(ResultTags.META).performScrollTo().assertIsDisplayed()
            val content = compose.onNodeWithTag(ResultTags.CONTENT)
            val before = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.mainClock.autoAdvance = false
            scheduler.runCurrent()
            assertVisibleTimedNotice("controlled delayed document failure")
            assertNull("the originating one-shot notice was consumed", vm.state.value.notice)
            assertEquals("a visible region needs no forced document scroll", before,
                content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            assertTrue("the document retains a nonzero viewport", content.fetchSemanticsNode().boundsInWindow.height > 0f)
            compose.mainClock.autoAdvance = true
            for (tag in listOf(ResultTags.SHARE, ResultTags.COPY, ResultTags.SAVE, ResultTags.ASK_MORE)) {
                revealAction(tag)
            }
            compose.onNodeWithTag("notice-dismiss").assertIsDisplayed()
            dismissNotice()
        } finally {
            harness.writes.cancel()
        }
    }

    @Test
    fun aDelayedSubmissionFailureIsVisibleWhileTheShortInterviewIsScrolledToItsComposer() {
        val active = fixtures.datasets.getValue("long").single { stringOf(it["id"]) == "ux-long-active" }
        val store = SessionStore(files.newFolder(), io = Dispatchers.Unconfined)
        runBlocking { store.save(active) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : Provider {
            override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
                entered.complete(Unit)
                release.await()
                throw ProviderException("network", "controlled delayed submission failure")
            }
        }
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = InterviewViewModel(stringOf(active["id"])!!, store, SessionWorker(), Clock { 1_760_000_000_000 },
            ring = { emptyKeyring() }, providers = ProviderSource { provider }, writes = writes)
        try {
            compose.setContent {
                IdeaForgeTheme(dynamicColor = false) { InterviewScreen(vm, onFinished = {}) }
            }
            compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 5_000)
            compose.onNodeWithTag("answer").performScrollTo().performTextReplacement("A substantive controlled answer for this interview.")
            compose.onNodeWithTag("send").performScrollTo().performClick()
            compose.waitUntil(5_000) { entered.isCompleted }
            compose.onNodeWithTag("answer").performScrollTo().assertIsDisplayed()
            val outer = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyAncestor(hasTestTag("interview")) and !hasTestTag("thread") and
                !hasAnyAncestor(hasTestTag("thread")) and !hasAnyAncestor(hasTestTag("answer")) and
                !hasTestTag("answer") and !hasTestTag("notice-message"))
            val before = outer.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("the coverage/old inline host is genuinely scrolled away", before > 0f)
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { release.complete(Unit) }
            assertVisibleTimedNotice("controlled delayed submission failure")
            assertNull("the originating one-shot notice was consumed", vm.state.value.notice)
            assertEquals("notice arrival must not jump back above the composer", before,
                outer.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            assertTrue("the interview retains a nonzero scroll viewport", outer.fetchSemanticsNode().boundsInWindow.height > 0f)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("answer").performScrollTo().assertIsDisplayed()
            assertTrue("the actual editor retains visible space", compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInWindow.height > 0f)
            for (tag in listOf("send", "skip", "write-up")) {
                revealAction(tag)
            }
            compose.onNodeWithTag("notice-dismiss").assertIsDisplayed()
            dismissNotice()
        } finally {
            release.complete(Unit)
            writes.cancel()
        }
    }

    private fun assertVisibleTimedNotice(detail: String) {
        val started = compose.mainClock.currentTime
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) {
            compose.onAllNodes(hasTestTag("notice-message")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(200)
        assertTrue("the assertion runs before the native Short timeout", compose.mainClock.currentTime - started < 2_000)
        val message = compose.onNodeWithTag("notice-message")
        message.assertIsDisplayed()
        val node = message.fetchSemanticsNode()
        assertTrue("the full original error detail is retained",
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(detail) })
        val clipped = node.boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        val root = compose.onRoot().fetchSemanticsNode().boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        assertTrue("the scrollable message has readable onscreen space, not only composed text",
            root.contains(clipped) && clipped.height / node.layoutInfo.density.density >= 16f * node.layoutInfo.density.fontScale)
        val dismiss = compose.onNodeWithTag("notice-dismiss")
        dismiss.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val dismissNode = dismiss.fetchSemanticsNode()
        val coordinates = dismissNode.layoutInfo.coordinates
        val origin = coordinates.localToWindow(Offset.Zero)
        val raw = UxBounds(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        val dismissBounds = dismissNode.boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        assertTrue("the complete dismissal action is inside the real viewport", root.intersect(dismissBounds).contains(raw))
    }

    private fun dismissNotice() {
        compose.onNodeWithTag("notice-dismiss").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("notice-message").assertDoesNotExist()
    }

    private fun revealAction(tag: String) {
        assertEquals("the real $tag action is still composed", 1,
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
