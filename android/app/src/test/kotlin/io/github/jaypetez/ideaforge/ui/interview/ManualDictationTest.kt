package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.voice.At
import io.github.jaypetez.ideaforge.voice.DictationPhase
import io.github.jaypetez.ideaforge.voice.Ev
import io.github.jaypetez.ideaforge.voice.FakeFactory
import io.github.jaypetez.ideaforge.voice.FakePcm
import io.github.jaypetez.ideaforge.voice.FakeRecognizerEngine
import io.github.jaypetez.ideaforge.voice.ManualDictation
import io.github.jaypetez.ideaforge.voice.Recognizer
import io.github.jaypetez.ideaforge.voice.RecognizerError
import io.github.jaypetez.ideaforge.voice.WhisperListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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

@OptIn(ExperimentalCoroutinesApi::class)
class ManualDictationTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var h: InterviewHarness
    private val scripts = ArrayDeque<List<At>>()
    private lateinit var factory: FakeFactory
    private lateinit var input: ManualDictation
    private var nativeAvailable = true
    private var fallback: WhisperListener? = null
    private val id = "s_manual_00000001"

    @Before fun setUp() {
        h = InterviewHarness(tmp.newFolder())
        Dispatchers.setMain(h.dispatcher)
        factory = FakeFactory { FakeRecognizerEngine(h.writes, scripts) }
        val recognizer = Recognizer(factory, h.writes)
        input = ManualDictation(h.writes, { recognizer }, h.inference,
            recognitionAvailable = { nativeAvailable }, whisper = { fallback })
    }

    @After fun close() {
        input.cancel()
        h.writes.cancel()
        h.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun vm(model: ScriptedModel = ScriptedModel()) = h.vm(model, id = id, dictation = input).also {
        h.scheduler.advanceUntilIdle()
    }

    private fun request(vm: InterviewViewModel, owns: () -> Boolean = { true }): DictationRequest {
        val pending = h.writes.async { vm.requestDictation(owns) }
        h.scheduler.runCurrent()
        return runBlocking { checkNotNull(pending.await()) }
    }

    private fun permission(vm: InterviewViewModel, request: DictationRequest, granted: Boolean = true) {
        h.writes.launch { vm.dictationPermission(request, granted) }
        h.scheduler.runCurrent()
    }

    private fun finish(vm: InterviewViewModel) {
        vm.stopDictation()
        h.scheduler.advanceUntilIdle()
    }

    @Test fun manualSpeechIsLiteralEditableDraftAndOnlyExplicitSendAdvancesWithVoiceSource() {
        val model = ScriptedModel()
        val vm = vm(model)
        assertTrue("opening Type starts nothing", factory.engines.isEmpty())
        vm.onAnswerChange("An existing draft.")
        scripts += listOf(At(100, Ev.Partial("skip this one over", final = true)))
        permission(vm, request(vm))
        h.scheduler.advanceTimeBy(1500)
        h.scheduler.runCurrent()
        assertEquals("a finish word or command cannot stop manual capture", DictationPhase.LISTENING, vm.state.value.dictation.phase)
        assertEquals("An existing draft.", vm.state.value.answer)
        assertTrue(model.requests.isEmpty())
        finish(vm)
        assertEquals("An existing draft. skip this one over", vm.state.value.answer)
        assertEquals("voice", vm.state.value.answerSource)
        assertEquals(DictationOutcome.ADDED, vm.state.value.dictation.outcome)
        assertEquals(1, vm.state.value.turnCount)
        assertTrue("Stop is never Send", model.requests.isEmpty())
        assertTrue(factory.engines.single().destroyed)
        vm.onAnswerChange(vm.state.value.answer + " Edited before sending.")
        vm.send()
        h.scheduler.advanceUntilIdle()
        val first = (h.store.get(id)!!["turns"] as JsonArray).first()
        assertEquals("An existing draft. skip this one over Edited before sending.", stringOf(first["answer"]))
        assertEquals("voice", stringOf(first["answerSource"]))
        assertEquals(2, vm.state.value.turnCount)
        assertEquals(1, model.requests.size)
    }

    @Test fun denialEmptyCaptureAndEngineFailureKeepTheExistingDraftAndReportDifferentOutcomes() {
        val vm = vm()
        vm.onAnswerChange("Keep this exact text.")
        permission(vm, request(vm), granted = false)
        assertEquals(DictationOutcome.DENIED, vm.state.value.dictation.outcome)
        assertTrue(factory.engines.isEmpty())
        permission(vm, request(vm))
        finish(vm)
        assertEquals(DictationOutcome.EMPTY, vm.state.value.dictation.outcome)
        scripts += listOf(At(100, Ev.Error(RecognizerError.AUDIO)))
        permission(vm, request(vm))
        h.scheduler.advanceUntilIdle()
        assertEquals(DictationOutcome.FAILED, vm.state.value.dictation.outcome)
        assertTrue(vm.state.value.dictation.detail.contains("microphone"))
        assertEquals("Keep this exact text.", vm.state.value.answer)
        assertEquals("typed", vm.state.value.answerSource)
        assertTrue(factory.engines.all { it.destroyed })
    }

    @Test fun aLatePermissionCannotBorrowANewerTapOrNewerEdit() {
        val vm = vm()
        val old = request(vm)
        vm.cancelDictation()
        vm.onAnswerChange("Newer editing.")
        val current = request(vm)
        permission(vm, old)
        assertTrue("the old grant cannot start the newer request", factory.engines.isEmpty())
        assertEquals(DictationPhase.PERMISSION, vm.state.value.dictation.phase)
        permission(vm, current, granted = false)
        assertEquals("Newer editing.", vm.state.value.answer)
        assertEquals(DictationOutcome.DENIED, vm.state.value.dictation.outcome)
    }

    @Test fun navigationAndWithdrawnInferenceRetireTheOriginalPermissionIntent() {
        val vm = vm()
        var visible = true
        val left = request(vm) { visible }
        visible = false
        permission(vm, left)
        assertTrue(factory.engines.isEmpty())
        vm.cancelDictation()
        val retiredPermit = request(vm)
        h.inference.withdraw()
        h.scheduler.advanceUntilIdle()
        permission(vm, retiredPermit)
        assertTrue(factory.engines.isEmpty())
        assertEquals("", vm.state.value.answer)
    }

    @Test fun anErrorAfterSpeechKeepsAvailableWordsButReportsAnInterruptedCapture() {
        val vm = vm()
        scripts += listOf(At(50, Ev.Partial("retained words")), At(100, Ev.Error(RecognizerError.AUDIO)))
        permission(vm, request(vm))
        h.scheduler.advanceUntilIdle()
        assertEquals("retained words", vm.state.value.answer)
        assertEquals(DictationOutcome.INTERRUPTED, vm.state.value.dictation.outcome)
        assertTrue(factory.engines.single().destroyed)
    }

    @Test fun anUnavailableNativeEngineNeedsTheExistingFallbackGrantAndNeverBorrowsTheInferenceKey() {
        nativeAvailable = false
        val vm = vm()
        vm.onAnswerChange("Still editable.")
        permission(vm, request(vm))
        h.scheduler.advanceUntilIdle()
        assertTrue(factory.engines.isEmpty())
        assertEquals(DictationOutcome.FAILED, vm.state.value.dictation.outcome)
        assertTrue(vm.state.value.dictation.detail.contains("own key"))
        assertEquals("Still editable.", vm.state.value.answer)
    }

    @Test fun anOriginalPermissionCannotOpenAudioAfterADurableQuestionReplacementInAnotherView() {
        val model = ScriptedModel()
        val vm = vm(model)
        vm.onAnswerChange("An idea that has a generated follow-up.")
        vm.send()
        h.scheduler.advanceUntilIdle()
        val pending = request(vm)
        val other = h.vm(model, id = id, dictation = input)
        h.scheduler.advanceUntilIdle()
        val session = other.state.value.session!!
        other.reports.open(session, reportableOutput(session, "question", stringOf(other.state.value.openTurn!!["id"]))!!)
        other.reports.category("other")
        other.reports.save()
        h.scheduler.advanceUntilIdle()
        other.replaceReportedQuestion(fromVoice = false)
        h.scheduler.advanceUntilIdle()
        assertTrue(other.reports.state.value.replaced)
        permission(vm, pending)
        assertTrue("a still-visible stale VM cannot authorize the old question", factory.engines.isEmpty())
        assertEquals(DictationOutcome.CANCELLED, vm.state.value.dictation.outcome)
    }

    @Test fun aLateTranscriptionCannotReplaceANewerEditAndAudioIsAlreadyReleasedWhileItWaits() {
        nativeAvailable = false
        val mic = FakePcm(listOf(600L to 40, 2000L to 8000))
        val response = CompletableDeferred<String>()
        fallback = WhisperListener({ mic }, { _, _ -> withContext(NonCancellable) { response.await() } },
            maxAnswerMs = 4000, io = h.dispatcher)
        val vm = vm()
        vm.onAnswerChange("Original.")
        permission(vm, request(vm))
        assertEquals(DictationPhase.TRANSCRIBING, vm.state.value.dictation.phase)
        assertFalse("uploading never keeps the manual microphone open", mic.open)
        vm.onAnswerChange("Newer private editing.")
        response.complete("retired transcript over")
        h.scheduler.advanceUntilIdle()
        assertEquals("Newer private editing.", vm.state.value.answer)
        assertEquals("Newer private editing.", stringOf(h.store.get(id)!!["draftAnswer"]))
        assertEquals(1, vm.state.value.turnCount)
        assertEquals(DictationOutcome.CANCELLED, vm.state.value.dictation.outcome)
    }

    @Test fun deletedIdeasAndOtherEditorsDurableDraftsRejectASettlingCapture() {
        val vm = vm()
        vm.onAnswerChange("Retained draft.")
        h.scheduler.advanceUntilIdle()
        scripts += listOf(At(100, Ev.Partial("late words", final = true)))
        permission(vm, request(vm))
        h.scheduler.advanceTimeBy(200)
        h.scheduler.runCurrent()
        h.writes.launch { h.store.save(setDraftAnswer(h.store.get(id)!!, str("Other editor's newer draft."), num(8))) }
        h.scheduler.runCurrent()
        finish(vm)
        assertEquals("Retained draft.", vm.state.value.answer)
        assertEquals("Other editor's newer draft.", stringOf(h.store.get(id)!!["draftAnswer"]))
        assertEquals(DictationOutcome.CANCELLED, vm.state.value.dictation.outcome)
        permission(vm, request(vm))
        h.worker.retire(id)
        h.writes.launch { h.worker.exclusive(id) { h.store.delete(id) } }
        h.scheduler.runCurrent()
        finish(vm)
        assertNull(h.store.get(id))
        assertTrue(runBlocking { id in h.store.knownIds() })
        assertTrue(factory.engines.all { it.destroyed })
    }

    @Test fun openingAnyGeneratedOutputReportCancelsInputBeforePrivateReportNotesAndReplacement() {
        val model = ScriptedModel()
        val vm = vm(model)
        vm.onAnswerChange("A thoughtful idea to get a generated follow-up.")
        vm.send()
        h.scheduler.advanceUntilIdle()
        vm.onAnswerChange("Keep my draft.")
        scripts += listOf(At(100, Ev.Partial("unfinished words")))
        permission(vm, request(vm))
        h.scheduler.advanceTimeBy(200)
        h.scheduler.runCurrent()
        val session = vm.state.value.session!!
        val output = reportableOutput(session, "question", stringOf(vm.state.value.openTurn!!["id"]))!!
        vm.reports.open(session, output)
        assertTrue("the recognizer is released before the dialog opens", factory.engines.single().destroyed)
        vm.reports.note("Private report note, never an answer.")
        vm.reports.category("other")
        vm.reports.save()
        h.scheduler.advanceUntilIdle()
        assertTrue(vm.reports.state.value.saved)
        vm.replaceReportedQuestion(fromVoice = false)
        h.scheduler.advanceUntilIdle()
        assertTrue(vm.reports.state.value.replaced)
        assertEquals("replacement", stringOf(vm.state.value.openTurn!!["questionSource"]))
        assertEquals("Keep my draft.", vm.state.value.answer)
        assertEquals("Keep my draft.", stringOf(h.store.get(id)!!["draftAnswer"]))
        assertEquals("", stringOf(vm.state.value.openTurn!!["answer"]))
        assertEquals(1, model.requests.size)
        assertNotNull(vm.reports.state.value.report)
    }
}
