package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.data.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Typed answers from tools/fixtures/walkthrough.mjs, as the vector records them. */
private val ANSWERS: List<String> by lazy {
    val steps = io.github.jaypetez.ideaforge.core.vectors.Vectors.load("walkthrough").cases.first().input["steps"] as JsonArray
    steps.map { stringOf(it["text"])!! }
}

class InterviewViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var h: InterviewHarness

    @Before
    fun setUp() {
        h = InterviewHarness(tmp.newFolder("sessions"))
        Dispatchers.setMain(h.dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun idle() = h.scheduler.advanceUntilIdle()

    private fun stored(id: String = "s_test_00000001"): JsonObject? = h.store.get(id)

    private fun turns(s: JsonObject?): List<JsonObject> = (s?.get("turns") as? JsonArray).orEmpty().map { it as JsonObject }

    private fun answer(vm: InterviewViewModel, text: String) {
        vm.onAnswerChange(text)
        vm.send()
        idle()
    }

    @Test
    fun aNewIdeaOpensOnTheSeedQuestionWithNoModelCallAndNoWrite() {
        val model = ScriptedModel()
        val vm = h.vm(model)
        idle()
        assertEquals(SEED_QUESTION, stringOf(vm.state.value.openTurn?.get("question")))
        assertTrue("turn 1 never calls the model", model.requests.isEmpty())
        assertNull("nothing is written for an idea with no answer yet", stored())
    }

    @Test
    fun aFailedFirstSaveKeepsTheAnswerEditableAndRetrySubmitsItOnce() {
        val gate = CompletableDeferred<Unit>()
        val model = ScriptedModel(gate = gate)
        val vm = h.vm(model)
        idle()
        val question = vm.state.value.openTurn
        val text = "  A workshop tracker for volunteers doing bicycle repairs.  "
        vm.onAnswerChange(text)
        assertTrue(h.dir.delete())
        assertTrue(h.dir.createNewFile())
        vm.send()
        h.scheduler.runCurrent()
        val failed = vm.state.value
        assertTrue(failed.notice is InterviewNotice.Failed)
        assertNull("failed admission cannot create a durable row", stored())
        assertTrue("no inference before durable admission", model.requests.isEmpty())

        assertTrue(h.dir.delete())
        assertTrue(h.dir.mkdir())
        vm.noticeShown()
        vm.send()
        vm.send()
        h.scheduler.runCurrent()
        assertTrue("the failed answer must be editable, not just shown in history", failed.canAnswer)
        assertEquals(question, failed.openTurn)
        assertEquals(text, failed.answer)
        assertEquals("typed", failed.answerSource)
        assertEquals("one deliberate retry, despite a second tap", 1, model.requests.size)
        val pending = stored()!!
        assertEquals(text.trim(), stringOf(turns(pending).single()["answer"]))
        assertEquals("typed", stringOf(turns(pending).single()["answerSource"]))
        assertEquals("turn", stringOf(pending["pending"]["kind"]))
        assertEquals(promptHash(model.turnPrompts.single().joined()), stringOf(pending["pending"]["promptHash"]))

        gate.complete(Unit)
        idle()
        assertEquals(2, turns(stored()).size)
        assertEquals(1, turns(stored()).count { stringOf(it["answer"]) == text.trim() })
        assertTrue(vm.state.value.canAnswer)
        assertEquals("", vm.state.value.answer)
        assertEquals("", stringOf(stored()!!["draftAnswer"]))
        assertEquals("the retry does not duplicate inference", 1, model.requests.size)
    }

    @Test
    fun aFailedChipSaveKeepsItsQuestionAndUneditedSourceForRetry() {
        var reject = false
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            if (reject) throw IOException("controlled admission failure")
            SessionStore.write(file, bytes)
        })
        val model = ScriptedModel()
        val vm = h.vm(model)
        idle()
        answer(vm, ANSWERS[0])
        val question = vm.state.value.openTurn!!
        val chip = stringOf((question["chips"] as JsonArray).first())!!
        vm.onChip(chip)
        reject = true
        vm.send()
        h.scheduler.runCurrent()
        assertEquals(question, vm.state.value.openTurn)
        assertEquals(chip, vm.state.value.answer)
        assertEquals("chip", vm.state.value.answerSource)
        assertTrue(vm.state.value.canAnswer)
        assertEquals(1, model.requests.size)

        reject = false
        vm.send()
        idle()
        assertEquals(chip, stringOf(turns(stored())[1]["answer"]))
        assertEquals("chip", stringOf(turns(stored())[1]["answerSource"]))
        assertEquals(3, turns(stored()).size)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun anEditArrivingBeforeTheFailedSaveSettlesWinsOverTheSubmittedText() {
        var reject = true
        lateinit var vm: InterviewViewModel
        val edited = "A newer answer about repairing bicycles together."
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            if (reject) {
                vm.onAnswerChange(edited)
                throw IOException("controlled late admission failure")
            }
            SessionStore.write(file, bytes)
        })
        val model = ScriptedModel()
        vm = h.vm(model)
        idle()
        vm.onAnswerChange("The original words before the later edit.")
        vm.send()
        h.scheduler.runCurrent()
        assertTrue(vm.state.value.canAnswer)
        assertEquals(edited, vm.state.value.answer)
        assertTrue(model.requests.isEmpty())

        reject = false
        vm.send()
        idle()
        assertEquals(edited, stringOf(turns(stored()).first()["answer"]))
        assertEquals(2, turns(stored()).size)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun aFailedSaveForARetiredOwnerCannotRestoreOrFlushItsOldAnswer() {
        lateinit var vm: InterviewViewModel
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { _, _ ->
            h.worker.retire(vm.sessionId)
            throw IOException("controlled retired admission failure")
        })
        val model = ScriptedModel()
        vm = h.vm(model)
        idle()
        vm.onAnswerChange("An answer owned by the retired interview.")
        vm.send()
        h.scheduler.runCurrent()
        assertFalse("a retired submit cannot reopen its question", vm.state.value.canAnswer)
        assertEquals("", vm.state.value.answer)
        vm.flushDraft()
        idle()
        assertNull(stored())
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun aRecoveredDraftCannotFlushOverANewerOwnersDraft() {
        var reject = true
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            if (reject) throw IOException("controlled admission failure")
            SessionStore.write(file, bytes)
        })
        val model = ScriptedModel()
        val vm = h.vm(model)
        idle()
        vm.onAnswerChange("The rejected answer belongs to the old editor.")
        vm.send()
        h.scheduler.runCurrent()
        assertTrue(vm.state.value.canAnswer)
        reject = false
        h.worker.retire(vm.sessionId)
        val replacement = h.vm(model)
        h.scheduler.runCurrent()
        val newer = "The reopened editor owns these newer words."
        replacement.onAnswerChange(newer)
        replacement.flushDraft()
        h.scheduler.runCurrent()
        assertEquals(newer, stringOf(stored()!!["draftAnswer"]))

        vm.flushDraft()
        idle()
        assertEquals("a late navigation flush must keep its original work tag",
            newer, stringOf(stored()!!["draftAnswer"]))
        assertEquals(newer, replacement.state.value.answer)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun aSettledSaveFailureCannotReopenAnAlreadyAdmittedAnswer() {
        var writes = 0
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            if (++writes == 2) throw IOException("controlled settled save failure")
            SessionStore.write(file, bytes)
        })
        val model = ScriptedModel()
        val vm = h.vm(model)
        idle()
        answer(vm, ANSWERS[0])
        assertTrue(vm.state.value.notice is InterviewNotice.Failed)
        assertFalse("only admission failure can restore an unsent answer", vm.state.value.canAnswer)
        assertEquals("", vm.state.value.answer)
        assertEquals(ANSWERS[0], stringOf(turns(stored()).single()["answer"]))
        assertEquals("turn", stringOf(stored()!!["pending"]["kind"]))
        vm.send()
        idle()
        assertEquals("a settled-write failure must not submit or infer twice", 1, model.requests.size)
    }

    // A tapped chip is the model's wording. It must reach submitAnswer as source 'chip', or
    // the ratchet takes it as the user's own words and lets it reach `covered`.
    @Test
    fun anUneditedChipIsRecordedAsChipAndCappedAtPartial() {
        val vm = h.vm(ScriptedModel())
        idle()
        // Questions 1-3 typed; question 4 (constraints) answered with its first chip.
        for (a in ANSWERS.take(3)) answer(vm, a)
        val chips = (vm.state.value.openTurn!!["chips"] as JsonArray).map { stringOf(it)!! }
        vm.onChip(chips[0])
        assertEquals("chip", vm.state.value.answerSource)
        vm.send()
        idle()
        // Reply 5 claims constraints `covered`, with evidence; the chip caps it at partial.
        answer(vm, ANSWERS[4])
        val s = stored()!!
        assertEquals("chip", stringOf(turns(s)[3]["answerSource"]))
        assertEquals(chips[0], stringOf(turns(s)[3]["answer"]))
        assertEquals("partial", stringOf(s["coverage"]["constraints"]["level"]))
    }

    @Test
    fun theTypedTwinOfThatRunReachesCovered() {
        val vm = h.vm(ScriptedModel())
        idle()
        for (a in ANSWERS.take(5)) answer(vm, a)
        assertEquals("typed", stringOf(turns(stored())[3]["answerSource"]))
        assertEquals("covered", stringOf(stored()!!["coverage"]["constraints"]["level"]))
    }

    @Test
    fun editingAChipMakesItTheUsersWords() {
        val vm = h.vm(ScriptedModel())
        idle()
        answer(vm, ANSWERS[0])
        val chip = stringOf((vm.state.value.openTurn!!["chips"] as JsonArray)[0])!!
        vm.onChip(chip)
        vm.onAnswerChange("$chip, give or take")
        assertEquals("typed", vm.state.value.answerSource)
        vm.send()
        idle()
        assertEquals("typed", stringOf(turns(stored())[1]["answerSource"]))
    }

    // The web restores `chip` when the saved draft is still exactly one of the chips.
    @Test
    fun aChipLeftInTheBoxIsStillAChipAfterReopening() {
        val vm = h.vm(ScriptedModel())
        idle()
        answer(vm, ANSWERS[0])
        val chip = stringOf((vm.state.value.openTurn!!["chips"] as JsonArray)[1])!!
        vm.onChip(chip)
        h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS + 1)
        idle()
        assertEquals(chip, stringOf(stored()!!["draftAnswer"]))

        val again = h.vm(ScriptedModel())
        idle()
        assertEquals(chip, again.state.value.answer)
        assertEquals("chip", again.state.value.answerSource)
    }

    @Test
    fun draftWritesAreDebounced() {
        val vm = h.vm(ScriptedModel())
        idle()
        answer(vm, ANSWERS[0])
        val rev = stringOf(stored()!!["rev"]) ?: stored()!!["rev"].toString()
        for ((i, text) in listOf("P", "Pr", "Pro", "Prob").withIndex()) {
            vm.onAnswerChange(text)
            h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS / 2)
            h.scheduler.runCurrent()
            assertEquals("no write while typing (keystroke $i)", rev, stored()!!["rev"].toString())
        }
        h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS)
        h.scheduler.runCurrent()
        assertEquals("one write after the pause, with the latest text", "Prob", stringOf(stored()!!["draftAnswer"]))
        assertEquals((rev.toInt() + 1).toString(), stored()!!["rev"].toString())
    }

    @Test
    fun revertingWhileACancelledDraftWriteFinishesStillSavesTheFinalText() {
        var duringWrite: (() -> Unit)? = null
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            SessionStore.write(file, bytes)
            // Cancel after the bytes land, while save still owes its non-cancellable index update.
            val interleave = duringWrite
            duringWrite = null
            interleave?.invoke()
        })
        for (flush in listOf(false, true)) {
            val id = "s_reverted_$flush"
            val vm = h.vm(ScriptedModel(), id)
            idle()
            answer(vm, ANSWERS[0])
            vm.onAnswerChange("Neutral draft A")
            idle()
            assertEquals("Neutral draft A", stringOf(stored(id)!!["draftAnswer"]))

            duringWrite = { vm.onAnswerChange("Neutral draft A") }
            vm.onAnswerChange("Neutral draft B")
            h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS + 1)
            h.scheduler.runCurrent()
            assertNull("the reversion ran inside an actual durable save", duringWrite)
            assertEquals("Neutral draft B", stringOf(stored(id)!!["draftAnswer"]))
            assertEquals("Neutral draft A", vm.state.value.answer)
            assertEquals("the cancelled write never updated this older UI snapshot",
                "Neutral draft A", stringOf(vm.state.value.session!!["draftAnswer"]))

            if (flush) vm.flushDraft()
            idle()
            assertEquals("the final text survives ${if (flush) "flush" else "debounce"}",
                "Neutral draft A", stringOf(stored(id)!!["draftAnswer"]))
            val reopened = h.vm(ScriptedModel(), id)
            idle()
            assertEquals("Neutral draft A", reopened.state.value.answer)
        }
    }

    // The bug this guards: a draft timer still queued when the answer is sent fires once the
    // next question is open, and writes the answered text into that question's draft. Reopen
    // the idea and the old answer is back in the box for a question it never answered.
    @Test
    fun theQueuedDraftWriteIsClearedBeforeSubmit() {
        val vm = h.vm(ScriptedModel())
        idle()
        answer(vm, ANSWERS[0])
        vm.onAnswerChange(ANSWERS[1])
        vm.send()
        // The scripted model answers at once, so question 3 is open long before the timer.
        h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS * 3)
        idle()
        assertEquals(3, turns(stored()).size)
        assertEquals("", stringOf(stored()!!["draftAnswer"]))
        assertEquals("", h.vm(ScriptedModel()).also { idle() }.state.value.answer)
    }

    @Test
    fun theQueuedDraftWriteIsClearedBeforeSkip() {
        val vm = h.vm(ScriptedModel())
        idle()
        answer(vm, ANSWERS[0])
        vm.onAnswerChange("half a thought")
        vm.skip()
        h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS * 3)
        idle()
        assertTrue(turns(stored())[1]["skipped"].toString() == "true")
        assertEquals("", stringOf(stored()!!["draftAnswer"]))
    }

    // Recovery is a re-issue of the byte-identical prompt: the provider's cache replays it.
    // A prompt that differs by a byte silently misses the cache and pays for the call twice.
    @Test
    fun anInterruptedTurnResumesWithTheIdenticalPrompt() {
        val first = ScriptedModel(gate = CompletableDeferred())
        val vm = h.vm(first)
        idle()
        answer(vm, ANSWERS[0])
        assertEquals("the turn is in flight", 1, first.turnPrompts.size)
        val original = first.turnPrompts.single().joined()

        // What is on disk while the call is out: the answer, and a marker naming the prompt.
        val mid = stored()!!
        assertEquals("turn", stringOf(mid["pending"]["kind"]))
        assertEquals("t2", stringOf(mid["pending"]["turnId"]))
        assertEquals(promptHash(original), stringOf(mid["pending"]["promptHash"]))

        // The process dies with the call unanswered. A new one opens the idea.
        h.restart()
        val second = ScriptedModel()
        val reopened = h.vm(second)
        idle()
        assertEquals(1, second.turnPrompts.size)
        assertEquals(original, second.turnPrompts.single().joined())
        val s = stored()!!
        assertEquals(2, turns(s).size)
        assertEquals("model", stringOf(turns(s)[1]["questionSource"]))
        assertTrue("the marker is cleared", s["pending"].toString() == "null")
        assertNotNull(reopened.state.value.openTurn)
    }

    @Test
    fun theWrapOfferIsShownOnceAndAcceptingWritesItUp() {
        val vm = h.vm(ScriptedModel())
        idle()
        var asked = 1
        while (vm.state.value.wrapOffer == null && asked < 12) {
            answer(vm, ANSWERS[asked - 1])
            asked++
        }
        val offer = vm.state.value.wrapOffer
        assertNotNull("the walkthrough reaches the coverage offer", offer)
        assertEquals("There is enough here to write it up whenever you like.", offer)
        assertEquals("true", stored()!!["wrapOffered"].toString())

        vm.writeUp()
        idle()
        assertTrue(vm.state.value.finished)
        assertFalse(vm.state.value.writeUpFailed)
        val s = stored()!!
        assertEquals("done", stringOf(s["status"]))
        assertTrue(stringOf(s["synthesis"]["text"])!!.startsWith("## Task"))
    }

    @Test
    fun decliningTheWrapOfferKeepsGoingAndItIsNotMadeAgain() {
        val vm = h.vm(ScriptedModel())
        idle()
        var asked = 1
        while (vm.state.value.wrapOffer == null) {
            answer(vm, ANSWERS[asked - 1])
            asked++
        }
        vm.declineWrap()
        assertNull(vm.state.value.wrapOffer)
        answer(vm, "One more thing about the teammates.")
        assertNull("said once, not after every answer", vm.state.value.wrapOffer)
        assertFalse(vm.state.value.finished)
    }

    @Test
    fun aFailedWriteUpStillFinishesAndSaysSo() {
        val vm = h.vm(ScriptedModel(failSynthesis = ProviderException("network", "offline")))
        idle()
        for (a in ANSWERS.take(4)) answer(vm, a)
        vm.writeUp()
        idle()
        assertTrue(vm.state.value.finished)
        assertTrue("the result is told the prompt is missing", vm.state.value.writeUpFailed)
        assertEquals("done", stringOf(stored()!!["status"]))
    }

    // A failed call falls back to the question bank. Correct, and invisible unless flagged:
    // the web marks it on #turnline; here the state says degraded and a notice explains.
    @Test
    fun aBankFallbackTurnIsFlaggedDegraded() {
        val model = ScriptedModel(fail = ProviderException("network", "offline"))
        val vm = h.vm(model)
        idle()
        assertFalse(vm.state.value.degraded)
        answer(vm, ANSWERS[0])
        val st = vm.state.value
        assertEquals("bank", stringOf(st.openTurn?.get("questionSource")))
        assertTrue(st.degraded)
        assertTrue(st.notice is InterviewNotice.Fallback)
        assertEquals("offline", st.notice!!.detail)
    }

    // A busy flag is not an identity guard: deleting the idea mid-turn retires its epoch,
    // and the turn's result must not be written back.
    @Test
    fun aTurnResultForARetiredEpochIsDropped() {
        val model = ScriptedModel(gate = CompletableDeferred())
        val vm = h.vm(model)
        idle()
        answer(vm, ANSWERS[0])
        h.worker.retire("s_test_00000001")
        model.gate!!.complete(Unit)
        idle()
        assertEquals("the stale question is never saved", 1, turns(stored()).size)
        assertNull(openTurn(vm.state.value.session!!))
        assertNull(vm.state.value.busy)
    }

    // viewModelScope is cancelled before onCleared runs, so a flush that asks whether the
    // debounce job is still active always finds it dead. A draft typed just before Back or
    // "New idea" was then lost unless ON_STOP happened to fire first.
    @Test
    fun aDraftTypedJustBeforeLeavingIsWrittenWhenTheViewModelIsCleared() {
        val owner = ViewModelStore()
        val vm = ViewModelProvider.create(owner, viewModelFactory { initializer { h.vm(ScriptedModel()) } })[InterviewViewModel::class]
        idle()
        answer(vm, ANSWERS[0])
        vm.onAnswerChange("Probably thirty")
        h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS / 3)
        h.scheduler.runCurrent()
        owner.clear()
        idle()
        assertEquals("Probably thirty", stringOf(stored()!!["draftAnswer"]))
    }

    private fun ownedVm(): Pair<ViewModelStore, InterviewViewModel> {
        val owner = ViewModelStore()
        return owner to ViewModelProvider.create(owner, viewModelFactory { initializer { h.vm(ScriptedModel()) } })[InterviewViewModel::class]
    }

    // Back right after Send clears the ViewModel. A turn queued on viewModelScope was
    // cancelled before it ran, and the answer went with it: it is started in the app scope,
    // synchronously, instead.
    @Test
    fun anAnswerSentJustBeforeLeavingIsStillSaved() {
        val (owner, vm) = ownedVm()
        idle()
        answer(vm, ANSWERS[0])
        vm.onAnswerChange(ANSWERS[1])
        vm.send()
        owner.clear()
        idle()
        assertEquals(3, turns(stored()).size)
        assertEquals(ANSWERS[1], stringOf(turns(stored())[1]["answer"]))
        assertEquals("", stringOf(stored()!!["draftAnswer"]))
    }

    // An answer already sent is not a draft any more. Leaving once the next question is up
    // must not flush it into that question's draft.
    @Test
    fun leavingAfterTheNextQuestionArrivesDoesNotWriteTheOldAnswerAsItsDraft() {
        val (owner, vm) = ownedVm()
        idle()
        answer(vm, ANSWERS[0])
        vm.onAnswerChange(ANSWERS[1])
        vm.send()
        h.scheduler.advanceTimeBy(DRAFT_DEBOUNCE_MS / 3)
        h.scheduler.runCurrent()
        assertNotNull("question 3 is already open", vm.state.value.openTurn)
        owner.clear()
        idle()
        assertEquals("", stringOf(stored()!!["draftAnswer"]))
    }
}
