package io.github.jaypetez.ideaforge.ui.interview

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.jaypetez.ideaforge.core.DIMENSION_IDS
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.runTurn
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InterviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val id = "s_screen_00000001"
    private lateinit var store: SessionStore

    private val answers: List<String> by lazy {
        (Vectors.load("walkthrough").cases.first().input["steps"] as JsonArray).map { stringOf(it["text"])!! }
    }

    private fun show(model: ScriptedModel, onFinished: (Boolean) -> Unit = {}): InterviewViewModel {
        store = SessionStore(tmp.newFolder(), io = Dispatchers.Unconfined)
        return show(store, model, onFinished)
    }

    private fun show(store: SessionStore, model: ScriptedModel, onFinished: (Boolean) -> Unit = {}): InterviewViewModel {
        this.store = store
        val writes = CoroutineScope(Dispatchers.Unconfined)
        val vm = InterviewViewModel(
            id, store, SessionWorker(), Clock { 1_760_000_000_000 }, ring = { emptyKeyring() },
            providers = ProviderSource { model }, writes = writes,
            inference = io.github.jaypetez.ideaforge.data.testInferenceConsent(writes),
        )
        compose.setContent { IdeaForgeTheme { InterviewScreen(vm, onFinished) } }
        return vm
    }

    private fun send(text: String) {
        compose.onNodeWithTag("answer").performTextInput(text)
        compose.onNodeWithTag("send").performClick()
        compose.waitForIdle()
    }

    private fun turn(n: Int): JsonObject = (store.get(id)!!["turns"] as JsonArray)[n - 1] as JsonObject

    @Test
    fun theCoverageStripShowsSevenDimensionPills() {
        show(ScriptedModel())
        compose.waitForIdle()
        assertEquals(7, DIMENSION_IDS.size)
        for (d in DIMENSION_IDS) compose.onNodeWithTag("pill-$d").assertExists()
        compose.onNodeWithTag("pill-outcome").assert(hasContentDescription("What we’re making: thin"))
        compose.onNodeWithTag("coverage-percent").assertTextContains("Coverage 0%")
    }

    @Test
    fun aTappedChipIsSentAsAChip() {
        show(ScriptedModel())
        compose.waitForIdle()
        send(answers[0])
        compose.waitUntilAtLeastOneExists(hasTestTag("chip-0"), 5_000)
        compose.onNodeWithTag("bridge").assertIsDisplayed()
        val chip = stringOf((turn(2)["chips"] as JsonArray)[0])!!
        compose.onNodeWithTag("chip-0").performClick()
        compose.onNodeWithTag("answer").assertTextContains(chip)
        compose.onNodeWithTag("send").performClick()
        compose.waitForIdle()
        assertEquals("chip", stringOf(turn(2)["answerSource"]))
        // The thread says so too: the answer is marked as a suggestion taken as offered.
        compose.waitUntilAtLeastOneExists(hasText("a suggestion, as offered"), 5_000)
    }

    @Test
    fun aDegradedTurnIsVisiblyFlagged() {
        show(ScriptedModel(fail = ProviderException("network", "offline")))
        compose.waitForIdle()
        compose.onNodeWithTag("degraded").assertDoesNotExist()
        send(answers[0])
        compose.waitUntilAtLeastOneExists(hasTestTag("degraded"), 5_000)
        compose.onNodeWithTag("degraded").assertTextContains("from the built-in checklist", substring = true)
        assertEquals("bank", stringOf(turn(2)["questionSource"]))
    }

    /** Seven answers in, through the core directly: the eighth earns the coverage offer. */
    private fun sevenAnswersIn(model: ScriptedModel): java.io.File {
        val dir = tmp.newFolder()
        val seeded = runBlocking {
            val now = num(1_760_000_000_000)
            var s = seedTurn(createSession(id, now, str("android")), now)
            for (a in answers.take(7)) {
                s = submitAnswer(s, str(a), now = now)
                s = runTurn(s, model, now).session
            }
            SessionStore(dir).also { it.save(s) }
            s
        }
        assertEquals(8, (seeded["turns"] as JsonArray).size)
        return dir
    }

    /** Answer the eighth question, accept the offer, and return what onFinished was told. */
    private fun acceptTheOffer(model: ScriptedModel): Boolean? {
        val dir = sevenAnswersIn(model)
        var failed: Boolean? = null
        show(SessionStore(dir, io = Dispatchers.Unconfined), model) { failed = it }
        compose.waitForIdle()
        compose.onNodeWithTag("write-up").assertExists()
        send(answers[7])
        compose.waitUntilAtLeastOneExists(hasTestTag("wrap-offer"), 5_000)
        compose.onNodeWithTag("wrap-accept").performClick()
        compose.waitUntil(5_000) { failed != null }
        return failed
    }

    @Test
    fun theWrapOfferAcceptedWritesItUpAndLeaves() {
        assertEquals(false, acceptTheOffer(ScriptedModel()))
        assertEquals("done", stringOf(store.get(id)!!["status"]))
    }

    // The interview leaves in the same moment the write-up fails, so a snackbar here would
    // never be seen. The failure has to travel with the navigation (ResultRoute.synthesisFailed).
    @Test
    fun aFailedWriteUpIsCarriedToTheResult() {
        assertEquals(true, acceptTheOffer(ScriptedModel(failSynthesis = ProviderException("network", "offline"))))
        val s = store.get(id)!!
        assertEquals("done", stringOf(s["status"]))
        assertEquals("", stringOf(s["synthesis"]["text"]))
    }
}
