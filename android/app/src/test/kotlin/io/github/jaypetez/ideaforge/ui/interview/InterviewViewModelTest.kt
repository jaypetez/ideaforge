package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.setSessionTags
import io.github.jaypetez.ideaforge.core.addContentReport
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.data.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    fun dimensionChoicesUseTheReducersWithoutChangingTheQuestionFactsDraftOrOtherMetadataAndReopen() {
        val model = ScriptedModel()
        val vm = h.vm(model)
        idle()
        answer(vm, ANSWERS[0])
        val before = stored()!!
        val output = reportableOutput(before, "question", "t2")!!
        h.writes.launch {
            h.store.save(addContentReport(setSessionTags(renameSession(before, str("My own title"), num(5)),
                JsonArray(listOf(str("retained"))), num(6)), str("r_keep"), output, str("other"), str("Keep my report."), num(7)))
        }
        idle()
        vm.onAnswerChange("Unfinished user draft.")
        vm.openDimension("audience")
        vm.chooseDimension(DimensionAction.Waive)
        vm.dimensionReason("Not part of this idea.")
        assertEquals("probing", stringOf(stored()!!["coverage"]["audience"]["status"]))
        vm.confirmDimension()
        idle()
        assertTrue(vm.state.value.dimensionEdit!!.saved)
        val waived = stored()!!
        assertEquals("waived", stringOf(waived["coverage"]["audience"]["status"]))
        assertEquals("Not part of this idea.", stringOf(waived["coverage"]["audience"]["waivedReason"]))
        for (key in listOf("turns", "facts", "zeroGainStreak", "opening", "synthesis")) assertEquals(key, before[key], waived[key])
        for (key in listOf("level", "gap", "evidence", "turnsSpent", "lastTurn"))
            assertEquals(key, before["coverage"]["audience"][key], waived["coverage"]["audience"][key])
        assertEquals("Unfinished user draft.", stringOf(waived["draftAnswer"]))
        assertEquals("My own title", stringOf(waived["name"]))
        assertEquals("retained", stringOf((waived["tags"] as JsonArray).single()))
        assertEquals("Keep my report.", stringOf((waived["contentReports"] as JsonArray).single()["note"]))
        assertTrue(buildExport(waived).contains("Not part of this idea."))
        vm.dismissDimension()
        vm.openDimension("constraints")
        vm.chooseDimension(DimensionAction.Defer)
        vm.confirmDimension()
        idle()
        assertEquals("deferred", stringOf(stored()!!["coverage"]["constraints"]["status"]))
        assertTrue(buildExport(stored()!!).contains("a decision still to make"))
        assertEquals("dimension controls do not run inference", 1, model.requests.size)

        h.restart()
        val reopenedModel = ScriptedModel()
        val reopened = h.vm(reopenedModel)
        idle()
        assertEquals("waived", stringOf(reopened.state.value.session!!["coverage"]["audience"]["status"]))
        reopened.openDimension("audience")
        assertEquals("Not part of this idea.", reopened.state.value.dimensionEdit!!.storedReason)
        reopened.chooseDimension(DimensionAction.Defer)
        assertNull("a waived dimension offers Resume, not another misleading removal", reopened.state.value.dimensionEdit!!.action)
        reopened.chooseDimension(DimensionAction.Resume)
        reopened.confirmDimension()
        idle()
        assertEquals("probing", stringOf(stored()!!["coverage"]["audience"]["status"]))
        assertEquals("null", stored()!!["coverage"]["audience"]["waivedReason"].toString())
        assertEquals(before["coverage"]["audience"]["level"], stored()!!["coverage"]["audience"]["level"])
        assertEquals("Unfinished user draft.", reopened.state.value.answer)
        assertTrue(reopenedModel.requests.isEmpty())
    }

    @Test
    fun aDimensionChoiceWaitsOutAHeldTurnAndIsNotLostToAnotherEditorsOlderSnapshot() {
        val model = ScriptedModel()
        val vm = h.vm(model)
        idle()
        answer(vm, ANSWERS[0])
        model.gate = CompletableDeferred()
        answer(vm, ANSWERS[1])
        assertEquals(Busy.Thinking, vm.state.value.busy)
        vm.openDimension("constraints")
        vm.chooseDimension(DimensionAction.Defer)
        vm.confirmDimension()
        h.scheduler.runCurrent()
        assertTrue(vm.state.value.dimensionEdit!!.busy)
        model.gate!!.complete(Unit)
        idle()
        assertEquals(3, turns(stored()).size)
        assertEquals(ANSWERS[1], stringOf(turns(stored())[1]["answer"]))
        assertEquals("deferred", stringOf(stored()!!["coverage"]["constraints"]["status"]))
        assertEquals(stored()!!["coverage"], vm.state.value.session!!["coverage"])
        vm.dismissDimension()
        val stale = h.vm(model)
        idle()
        vm.openDimension("audience")
        vm.chooseDimension(DimensionAction.Waive)
        vm.dimensionReason("An explicit scope choice.")
        vm.confirmDimension()
        idle()
        val level = stored()!!["coverage"]["audience"]["level"]
        answer(stale, ANSWERS[2])
        assertEquals("an older editor must merge durable dimension choices before running its turn",
            "waived", stringOf(stored()!!["coverage"]["audience"]["status"]))
        assertEquals("An explicit scope choice.", stringOf(stored()!!["coverage"]["audience"]["waivedReason"]))
        assertEquals("the model cannot score a waived dimension", level, stored()!!["coverage"]["audience"]["level"])
        assertEquals("deferred", stringOf(stored()!!["coverage"]["constraints"]["status"]))
    }

    @Test
    fun cancelAndFailedDimensionSaveKeepExistingDataAndTheReasonCanBeRetried() {
        var reject = false
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            if (reject) throw java.io.IOException("Controlled dimension write failure.")
            SessionStore.write(file, bytes)
        })
        val vm = h.vm(ScriptedModel())
        idle()
        vm.onAnswerChange("Keep this draft.")
        idle()
        val before = stored()
        vm.openDimension("outcome")
        vm.chooseDimension(DimensionAction.Waive)
        vm.dimensionReason("My optional reason.")
        vm.dismissDimension()
        assertEquals(before, stored())
        assertEquals("Keep this draft.", vm.state.value.answer)
        vm.openDimension("outcome")
        vm.chooseDimension(DimensionAction.Waive)
        vm.dimensionReason("My optional reason.")
        reject = true
        vm.confirmDimension()
        idle()
        assertEquals(DimensionFailure.Save, vm.state.value.dimensionEdit!!.failure)
        assertEquals("My optional reason.", vm.state.value.dimensionEdit!!.reason)
        assertEquals(before, stored())
        reject = false
        vm.confirmDimension()
        idle()
        assertTrue(vm.state.value.dimensionEdit!!.saved)
        assertNull("successful retry must remove the previous failure", vm.state.value.dimensionEdit!!.failure)
        assertEquals("", vm.state.value.dimensionEdit!!.detail)
        assertEquals("My optional reason.", stringOf(stored()!!["coverage"]["outcome"]["waivedReason"]))
        val saved = stored()
        vm.confirmDimension()
        idle()
        assertEquals("a completed confirmation is not submitted twice", saved, stored())
    }

    @Test
    fun anotherEditorsNewerDimensionChoiceIsNotOverwrittenByAnOldDialog() {
        val vm = h.vm(ScriptedModel())
        idle()
        vm.onAnswerChange("Keep a persisted idea.")
        idle()
        val other = h.vm(ScriptedModel())
        idle()
        vm.openDimension("outcome")
        other.openDimension("outcome")
        other.chooseDimension(DimensionAction.Defer)
        other.confirmDimension()
        idle()
        val newer = stored()
        vm.chooseDimension(DimensionAction.Waive)
        vm.dimensionReason("A stale choice.")
        vm.confirmDimension()
        idle()
        assertEquals(DimensionFailure.Changed, vm.state.value.dimensionEdit!!.failure)
        assertEquals(newer, stored())
        assertEquals("A stale choice.", vm.state.value.dimensionEdit!!.reason)
        vm.dismissDimension()
        vm.openDimension("outcome")
        assertEquals("deferred", vm.state.value.dimensionEdit!!.status)
        vm.chooseDimension(DimensionAction.Waive)
        assertNull(vm.state.value.dimensionEdit!!.action)
        vm.chooseDimension(DimensionAction.Resume)
        vm.confirmDimension()
        idle()
        assertEquals("probing", stringOf(stored()!!["coverage"]["outcome"]["status"]))
    }

    @Test
    fun aDimensionSaveKeepsAndDisplaysAnotherEditorsNewerDurableDraft() {
        val vm = h.vm(ScriptedModel())
        idle()
        vm.onAnswerChange("Original draft.")
        idle()
        val other = h.vm(ScriptedModel())
        idle()
        vm.openDimension("outcome")
        vm.chooseDimension(DimensionAction.Defer)
        other.onAnswerChange("Newer durable draft from the other editor.")
        idle()
        vm.confirmDimension()
        idle()
        assertTrue(vm.state.value.dimensionEdit!!.saved)
        assertEquals("Newer durable draft from the other editor.", stringOf(stored()!!["draftAnswer"]))
        assertEquals("Newer durable draft from the other editor.", vm.state.value.answer)
    }

    @Test
    fun aQueuedDimensionSaveCannotResurrectADeletedIdeaOrChangeAnotherIdea() {
        val vm = h.vm(ScriptedModel())
        idle()
        vm.onAnswerChange("Persist this idea first.")
        idle()
        val other = h.vm(ScriptedModel(), "s_other_dimension")
        idle()
        other.onAnswerChange("Another idea stays untouched.")
        idle()
        val otherBefore = stored("s_other_dimension")
        val release = CompletableDeferred<Unit>()
        h.writes.launch { h.worker.exclusive("s_test_00000001") { release.await() } }
        h.scheduler.runCurrent()
        vm.openDimension("outcome")
        vm.chooseDimension(DimensionAction.Waive)
        vm.confirmDimension()
        h.scheduler.runCurrent()
        h.worker.retire("s_test_00000001")
        h.writes.launch { h.worker.exclusive("s_test_00000001") { h.store.delete("s_test_00000001") } }
        release.complete(Unit)
        idle()
        assertNull(stored())
        assertEquals(DimensionFailure.Retired, vm.state.value.dimensionEdit!!.failure)
        assertEquals(otherBefore, stored("s_other_dimension"))
    }

    @Test
    fun aConfirmedDimensionSaveOutlivesNavigationButRemainsBoundToItsOriginalIdea() {
        val (owner, vm) = ownedVm()
        idle()
        vm.onAnswerChange("The originating draft.")
        idle()
        val release = CompletableDeferred<Unit>()
        h.writes.launch { h.worker.exclusive("s_test_00000001") { release.await() } }
        h.scheduler.runCurrent()
        vm.openDimension("outcome")
        vm.chooseDimension(DimensionAction.Defer)
        vm.confirmDimension()
        h.scheduler.runCurrent()
        owner.clear()
        val other = h.vm(ScriptedModel(), "s_next_dimension")
        idle()
        release.complete(Unit)
        idle()
        assertEquals("deferred", stringOf(stored()!!["coverage"]["outcome"]["status"]))
        assertEquals("The originating draft.", stringOf(stored()!!["draftAnswer"]))
        assertEquals("probing", stringOf(other.state.value.session!!["coverage"]["outcome"]["status"]))
        assertNull(vm.state.value.dimensionEdit)
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
