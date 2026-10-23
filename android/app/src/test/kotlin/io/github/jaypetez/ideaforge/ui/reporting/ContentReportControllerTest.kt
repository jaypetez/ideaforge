package io.github.jaypetez.ideaforge.ui.reporting

import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.ui.interview.InterviewHarness
import io.github.jaypetez.ideaforge.ui.interview.InterviewViewModel
import io.github.jaypetez.ideaforge.ui.interview.ScriptedModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContentReportControllerTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var h: InterviewHarness
    private lateinit var model: ScriptedModel
    private lateinit var vm: InterviewViewModel
    private val id = "s_report_test"
    private val clock = Clock { 1_760_000_000_000 }

    @Before fun start() {
        h = InterviewHarness(files.newFolder("sessions"))
        Dispatchers.setMain(h.dispatcher)
        model = ScriptedModel()
        vm = h.vm(model, id)
        idle()
        answer("A neutral test idea with enough detail to start an interview.")
    }

    @After fun stop() { Dispatchers.resetMain() }
    private fun idle() = h.scheduler.advanceUntilIdle()
    private fun stored(): Session = h.store.get(id)!!
    private fun reports(s: Session = stored()) = (s["contentReports"] as? JsonArray).orEmpty()
    private fun answer(text: String) { vm.onAnswerChange(text); vm.send(); idle() }
    private fun controller() = ContentReportController(h.store, h.worker, clock, h.writes, h.writes)
    private fun open(c: ContentReportController, s: Session = stored(), turn: String = "t2") {
        c.open(s, reportableOutput(s, "question", turn)!!)
        c.category("inaccurate")
        c.note("Neutral private note marker.")
    }

    @Test fun aReportQueuedBehindAModelCallKeepsTheOriginalOutputAndTheSettledAnswer() {
        val c = controller()
        open(c)
        val original = c.state.value.output
        val gate = CompletableDeferred<Unit>()
        model.gate = gate
        answer("Neutral submitted answer, not part of the report payload.")
        c.save()
        h.scheduler.runCurrent()
        assertTrue(c.state.value.busy)
        assertTrue(reports().isEmpty())
        gate.complete(Unit)
        idle()
        assertTrue(c.state.value.saved)
        assertEquals(original, reports().single()["output"])
        assertEquals("t2", stringOf(reports().single()["output"]["turnId"]))
        assertEquals(3, (stored()["turns"] as JsonArray).size)
        assertEquals("Neutral submitted answer, not part of the report payload.",
            stringOf((stored()["turns"] as JsonArray)[1]["answer"]))
        assertFalse(c.state.value.canReplace)
        assertTrue(model.requests.none { it.second.joined().contains("Neutral private note marker.") })
    }

    @Test fun aStaleManualDraftAndThenASettledTurnKeepAnotherScreensDurableReport() {
        val snapshot = vm.state.value.session!!
        val c = controller()
        open(c)
        c.save()
        idle()
        val durable = reports()
        assertTrue("the interview still held its older snapshot", snapshot["contentReports"] == null)
        vm.onAnswerChange("Neutral draft after another screen saved a report.")
        idle()
        assertEquals(durable, reports())
        vm.send()
        idle()
        assertEquals(durable, reports())
        assertTrue(model.requests.none { it.second.joined().contains("Neutral private note marker.") })
    }

    @Test fun aFailedSaveKeepsTheDraftAndRetrySavesExactlyOnce() {
        var reject = false
        val failing = SessionStore(files.newFolder("controlled"), io = h.dispatcher, writer = { file, bytes ->
            if (reject) throw IOException("Neutral controlled report write failure.")
            SessionStore.write(file, bytes)
        })
        h.writes.launch { failing.save(stored()) }
        idle()
        val c = ContentReportController(failing, h.worker, clock, h.writes, h.writes)
        open(c, failing.get(id)!!)
        reject = true
        c.save()
        idle()
        assertEquals(ReportFailure.Save, c.state.value.failure)
        assertFalse(c.state.value.saved)
        assertEquals("inaccurate", c.state.value.category)
        assertEquals("Neutral private note marker.", c.state.value.note)
        assertTrue(reports(failing.get(id)!!).isEmpty())
        reject = false
        c.save()
        idle()
        assertTrue(c.state.value.saved)
        assertEquals(1, reports(failing.get(id)!!).size)
        c.save()
        idle()
        assertEquals(1, reports(failing.get(id)!!).size)
    }

    @Test fun cancelWritesNothingAndAWaitingSaveCannotBeAttachedToANewIdeaOrResurrectADeletedOne() {
        val c = controller()
        open(c)
        c.dismiss()
        assertNull(c.state.value.output)
        assertTrue(reports().isEmpty())
        open(c)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.writes.launch { h.worker.exclusive(id) { entered.complete(Unit); release.await() } }
        idle()
        assertTrue(entered.isCompleted)
        c.save()
        h.scheduler.runCurrent()
        val other = io.github.jaypetez.ideaforge.core.copySession(stored(),
            io.github.jaypetez.ideaforge.core.js.str("s_other"))
        c.open(other, reportableOutput(other, "question", "t2")!!)
        assertEquals(id, c.state.value.sessionId)
        h.worker.retire(id)
        h.writes.launch { h.worker.exclusive(id) { h.store.delete(id) } }
        release.complete(Unit)
        idle()
        assertNull(h.store.get(id))
        assertFalse(c.state.value.saved)
        assertEquals(ReportFailure.Retired, c.state.value.failure)
        assertTrue("deletion is not resurrected", h.store.sessions.value.isEmpty())
    }

    @Test fun replacementSavesTheManualDraftWithoutAnyAnswerOrCoverageChange() {
        vm.onAnswerChange("Neutral unsent draft.")
        val before = vm.state.value.session!!
        val output = reportableOutput(before, "question", "t2")!!
        vm.reports.open(before, output)
        vm.reports.category("other")
        vm.reports.save()
        idle()
        vm.replaceReportedQuestion(fromVoice = false)
        idle()
        val after = stored()
        assertTrue(vm.reports.state.value.replaced)
        assertEquals(before["coverage"], after["coverage"])
        assertEquals(before["facts"], after["facts"])
        assertEquals(before["zeroGainStreak"], after["zeroGainStreak"])
        val open = io.github.jaypetez.ideaforge.core.openTurn(after)!!
        assertEquals("t2", stringOf(open["id"]))
        assertEquals("", stringOf(open["answer"]))
        assertEquals("false", open["skipped"].toString())
        assertEquals("replacement", stringOf(open["questionSource"]))
        assertEquals("Neutral unsent draft.", stringOf(after["draftAnswer"]))
        assertEquals("Neutral unsent draft.", vm.state.value.answer)
        assertNotEquals(stringOf(output["question"]), stringOf(open["question"]))
        assertEquals(1, model.requests.size)
    }

    @Test fun aQuestionAnsweredOnAnotherScreenCannotBeReplacedAfterTheReportWasSaved() {
        val c = controller()
        open(c)
        c.save()
        idle()
        assertTrue(c.state.value.canReplace)
        answer("A neutral answer submitted before the replacement tap.")
        val settled = stored()
        c.replace()
        idle()
        assertEquals(ReportFailure.Replace, c.state.value.failure)
        assertFalse(c.state.value.replaced)
        assertEquals(settled, stored())
        assertEquals(1, reports().size)
    }
}
