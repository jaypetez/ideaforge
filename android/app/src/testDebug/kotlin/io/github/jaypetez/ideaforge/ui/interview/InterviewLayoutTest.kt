package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.core.DIMENSION_IDS
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.usableRing
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import kotlinx.serialization.json.JsonArray
import io.github.jaypetez.ideaforge.ux.UxBounds
import io.github.jaypetez.ideaforge.ux.UxEvidence
import io.github.jaypetez.ideaforge.ux.UxFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h818dp", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterviewLayoutTest {
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

    @Test
    fun anEnlargedCompactComposerLeavesReachableChipsAndWriteUpWithoutClippedLabels() {
        requireLongInterviewReachable()
    }

    @Test
    fun enlargedCoverageDetailsKeepEveryLevelAndTheComposerReachable() {
        requireLongInterviewReachable(coverageDetails = true)
    }

    @Test
    fun enlargedCompactDimensionConfirmationKeepsNativeTargetsAndCompleteLabelsReachable() {
        requireDimensionConfirmationReachable()
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp", fontScale = 2f)
    fun enlargedExpandedDimensionConfirmationKeepsNativeTargetsAndCompleteLabelsReachable() {
        requireDimensionConfirmationReachable()
    }

    private fun requireDimensionConfirmationReachable() {
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true).also { graphs += it }
        graph.providerSource = ProviderSource { ScriptedModel() }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            compose.onNode(hasText("New idea") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-type"), 5_000)
            compose.onNodeWithTag("new-idea-type").performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("coverage-details"), 5_000)
            compose.onNodeWithTag("coverage-details").performScrollTo().performClick()
            for (dimension in DIMENSION_IDS) requireReachable("pill-$dimension")
            compose.onNodeWithTag("pill-outcome").performScrollTo().performClick()
            for (tag in listOf("dimension-waive", "dimension-defer", "dimension-cancel")) requireReachable(tag)
            compose.onNodeWithTag("dimension-waive").performScrollTo().performClick()
            compose.onNodeWithTag("dimension-reason").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(
                    "This belongs to a later project. Keep all original notes and the current question while I choose the scope.")) }
            for (tag in listOf("dimension-reason", "dimension-confirm", "dimension-cancel")) {
                requireReachable(tag)
                val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                val touch = UxEvidence.touchBounds(node)
                val density = node.layoutInfo.density.density
                UxEvidence.requireTarget(tag, touch.width / density, touch.height / density)
            }
            compose.onNodeWithTag("dimension-confirm").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("dimension-saved"), 5_000)
            requireReachable("dimension-done")
            compose.onNodeWithTag("dimension-done").performClick()
            compose.onNodeWithTag("pill-outcome").performScrollTo().performClick()
            requireReachable("dimension-resume")
            requireReachable("dimension-stored-reason", actionRequired = false)
            compose.onNodeWithTag("dimension-cancel").performClick()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 1.3f)
    fun aShortLandscapeKeepsTheWholeSuggestionAndTurnStatusReachable() {
        requireLongInterviewReachable()
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 2f)
    fun anEnlargedShortLandscapeKeepsTheWholeSuggestionAndTurnStatusReachable() {
        requireLongInterviewReachable()
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 2f)
    fun aShortWindowOpensOnItsCurrentTurnWithoutTestDrivenScrolling() {
        requireLongInterviewReachable(requireCurrent = true)
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 2f)
    fun aShortWindowWithAnUnavailableProviderStillOpensOnItsCurrentQuestion() {
        requireLongInterviewReachable(requireCurrent = true, providerUnavailable = true)
    }

    @Test
    @Config(qualifiers = "w640dp-h328dp-land", fontScale = 1f)
    fun normalTextWithRealLandscapeBarClearanceKeepsTheChecklistQuestionAndStatusReachable() {
        // A method's default 1f does not override the class-level 2f in Robolectric.
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
        requireLongInterviewReachable(bank = true)
    }

    private fun requireLongInterviewReachable(
        requireCurrent: Boolean = false, providerUnavailable: Boolean = false, coverageDetails: Boolean = false,
        bank: Boolean = false,
    ) {
        val data = UxFixtures.parse(File(System.getProperty("ideaforge.fixtures"), "ux.json").readText())
        val active = if (bank) data.datasets.getValue("bank").single()
            else data.datasets.getValue("long").single { stringOf(it["id"]) == "ux-long-active" }
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true).also { graphs += it }
        graph.providerSource = ProviderSource {
            if (providerUnavailable) throw ProviderException("config", "Controlled unavailable provider while opening.")
            ScriptedModel()
        }
        runBlocking { assertTrue(graph.sessionStore.insert(active)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            val title = io.github.jaypetez.ideaforge.core.sessionDisplayTitle(active)
            compose.onNodeWithTag(LibraryTags.SEARCH).performTextReplacement(title)
            val card = hasTestTag(LibraryTags.CARD) and hasText(title)
            compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST))).performScrollToNode(card)
            compose.onNodeWithTag(LibraryTags.more(stringOf(active["id"])!!)).performClick()
            compose.onNodeWithTag(LibraryTags.details(stringOf(active["id"])!!)).performClick()
            compose.onNode(hasText("Continue") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("question"), 5_000)
            if (bank) assertEquals(1f, compose.onNodeWithTag("question").fetchSemanticsNode().layoutInfo.density.fontScale, 0f)
            compose.onNodeWithTag("interview-back")
                .assertHeightIsAtLeast(IdeaForgeSpacing.control).assertWidthIsAtLeast(IdeaForgeSpacing.control)
            if (requireCurrent) {
                compose.waitForIdle()
                compose.onNodeWithTag("current-turn").assertIsDisplayed()
                compose.onNodeWithTag("question").assertIsDisplayed()
            }
            assertTrue("the full thread remains composed", compose.onNodeWithTag("thread").fetchSemanticsNode().layoutInfo.coordinates.size.height > 0)
            if (coverageDetails) {
                requireReachable("coverage-details")
                compose.onNodeWithTag("coverage-details").performClick()
                for (dimension in DIMENSION_IDS) requireReachable("pill-$dimension")
                requireReachable("coverage-details")
                compose.onNodeWithTag("coverage-details").performClick()
            }
            val controls = listOf("answer", "send", "skip", "dictation-toggle", "voice-start", "chip-0", "chip-1") +
                if (bank) emptyList() else listOf("write-up", "chip-2")
            for (tag in controls) {
                requireReachable(tag)
            }

            requireReachable("turn-status", actionRequired = false)
            if (bank) return@use
            requireReachable("write-up")
            compose.onNodeWithTag("write-up").assertIsDisplayed().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ResultTags.OUTPUT), 5_000)
            if (providerUnavailable) compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertExists()
            else compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
        }
    }

    @Test
    fun theEnlargedWalkthroughChipTapActuallyFillsTheRealDraftBeforeSend() {
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true).also { graphs += it }
        graph.providerSource = ProviderSource { ScriptedModel() }
        val steps = Vectors.load("walkthrough").cases.single { it.name == "a chip answer" }.input["steps"] as JsonArray
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            compose.onNode(hasText("New idea") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-type"), 5_000)
            compose.onNodeWithTag("new-idea-type").performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("answer"), 5_000)
            for (step in steps.take(3)) {
                val previous = compose.onNodeWithTag("question").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single().text
                compose.onNodeWithTag("answer").performScrollTo().performTextReplacement(stringOf(step["text"])!!)
                compose.onNodeWithTag("send").performScrollTo().assertIsDisplayed().performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasTestTag("question") and hasText(previous)).fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodes(hasTestTag("question")).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(hasTestTag("interview-busy")).fetchSemanticsNodes().isEmpty()
                }
            }
            val chip = compose.onNodeWithTag("chip-0")
            val expected = chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single().text
            chip.performScrollTo().performClick()
            assertEquals("the turn-four chip must be reached and tapped, not just composed outside the thread viewport", expected,
                compose.onNodeWithTag("answer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }

    @Test
    fun compactEnlargedManualListeningLongCaptionAndErrorKeepRealTargetsAndLabelsReachable() {
        requireManualCaptureReachable()
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp", fontScale = 2f)
    fun expandedEnlargedManualListeningLongCaptionAndErrorKeepRealTargetsAndLabelsReachable() {
        requireManualCaptureReachable()
    }

    private fun requireManualCaptureReachable() {
        val graph = installTestGraph(ring = usableRing(), inferenceAgreed = true, recognitionAvailable = { true }).also { graphs += it }
        graph.providerSource = ProviderSource { ScriptedModel() }
        val engines = mutableListOf<io.github.jaypetez.ideaforge.voice.RecognizerEngine.Listener>()
        val words = "Over the years we grew carefully. Keep the complete literal phrase skip this one over, " +
            "including the finish word, for a later edit and explicit Send."
        graph.recognizer = io.github.jaypetez.ideaforge.voice.Recognizer(
            io.github.jaypetez.ideaforge.voice.RecognizerEngineFactory {
                object : io.github.jaypetez.ideaforge.voice.RecognizerEngine {
                    lateinit var callbacks: io.github.jaypetez.ideaforge.voice.RecognizerEngine.Listener
                    override fun setListener(listener: io.github.jaypetez.ideaforge.voice.RecognizerEngine.Listener) {
                        callbacks = listener
                        engines += listener
                    }
                    override fun start(language: String) { callbacks.onReady() }
                    override fun stop() { callbacks.onResults(words) }
                    override fun cancel() = Unit
                    override fun destroy() = Unit
                }
            }, graph.mainScope)
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(NAVIGATION), 5_000)
            compose.onNode(hasText("New idea") and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("new-idea-type"), 5_000)
            compose.onNodeWithTag("new-idea-type").performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("dictation-toggle"), 5_000)
            requireReachable("dictation-toggle")
            compose.onNodeWithTag("dictation-toggle").assertHeightIsAtLeast(IdeaForgeSpacing.voice)
                .assertWidthIsAtLeast(IdeaForgeSpacing.voice).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Listening for dictation.", substring = true), 5_000)
            compose.runOnUiThread { engines.single().onPartial(words, null, true) }
            compose.waitUntilAtLeastOneExists(hasTestTag("dictation-caption"), 5_000)
            for (tag in listOf("dictation-status", "dictation-caption")) requireReachable(tag, actionRequired = false)
            requireReachable("dictation-toggle")
            compose.onNodeWithTag("dictation-toggle").assertHeightIsAtLeast(IdeaForgeSpacing.voice)
                .assertWidthIsAtLeast(IdeaForgeSpacing.voice).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Dictation added to your draft.", substring = true), 5_000)
            assertEquals(words, compose.onNodeWithTag("answer").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text)
            requireReachable("send")
            requireReachable("dictation-toggle")
            compose.onNodeWithTag("dictation-toggle").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Listening for dictation.", substring = true), 5_000)
            compose.runOnUiThread { engines.last().onError(io.github.jaypetez.ideaforge.voice.RecognizerError.AUDIO) }
            compose.waitUntilAtLeastOneExists(hasText("Dictation failed.", substring = true), 5_000)
            requireReachable("dictation-status", actionRequired = false)
            requireReachable("dictation-toggle")
            requireReachable("send")
            assertEquals(words, compose.onNodeWithTag("answer").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }

    private fun requireReachable(tag: String, actionRequired: Boolean = true) {
        val interaction = compose.onNodeWithTag(tag)
        var node = interaction.fetchSemanticsNode()
        fun raw(): UxBounds {
            val coordinates = node.layoutInfo.coordinates
            val top = coordinates.localToWindow(Offset.Zero)
            return UxBounds(top.x, top.y, top.x + coordinates.size.width, top.y + coordinates.size.height)
        }
        fun clipped() = node.boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        if (!clipped().contains(raw())) {
            interaction.performScrollTo()
            node = interaction.fetchSemanticsNode()
        }
        val label = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        UxEvidence.requireControl(tag, raw(), clipped(), label,
            !actionRequired || node.config.contains(SemanticsActions.OnClick) || node.config.contains(SemanticsActions.SetText))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        for (layout in layouts) {
            val lines = (0 until layout.lineCount).toList()
            UxEvidence.requireLabel(tag, lines.maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f,
                lines.maxOfOrNull(layout::getLineBottom) ?: 0f, layout.size.width, layout.size.height,
                layout.multiParagraph.didExceedMaxLines || lines.any(layout::isLineEllipsized))
        }
    }
}
