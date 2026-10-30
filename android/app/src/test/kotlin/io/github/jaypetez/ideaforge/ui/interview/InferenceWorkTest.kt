package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.setPending
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.data.inferenceConfig
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.testInferenceRing
import io.github.jaypetez.ideaforge.providers.inferenceDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InferenceWorkTest {
    @get:Rule val files = TemporaryFolder()
    private var harness: InterviewHarness? = null

    private fun fixture(agreed: Boolean): InterviewHarness =
        InterviewHarness(files.newFolder(), agreed = agreed).also {
            harness = it
            Dispatchers.setMain(it.dispatcher)
        }

    @After
    fun close() {
        harness?.writes?.cancel()
        harness?.scheduler?.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun missingConsentCannotSeedANewInterviewOrBuildAProvider() {
        val h = fixture(false)
        val model = ScriptedModel()
        var built = 0
        val vm = h.vm(model, source = ProviderSource { built++; model })
        h.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.session)
        assertTrue(vm.state.value.consentRequired)
        assertEquals(0, built)
        assertTrue(model.requests.isEmpty())
        assertTrue(h.store.sessions.value.isEmpty())
    }

    @Test
    fun aPendingTurnIsReadableButIsNotReplayedWithoutConsent() {
        val h = fixture(false)
        val id = "s_test_00000001"
        val answered = submitAnswer(seedTurn(createSession(id, num(1), str("android")), num(1)), str("Keep this private draft"), "typed", num(2))
        val pending = withPendingTurn(answered, num(3))
        h.writes.launch { h.store.save(pending) }
        h.scheduler.advanceUntilIdle()
        val model = ScriptedModel()
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        assertNotNull(vm.state.value.session)
        assertTrue(vm.state.value.consentRequired)
        assertFalse(vm.state.value.finished)
        assertEquals(pending, h.store.get(id))
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun withdrawingFromACachedInterviewKeepsTheDraftAndCannotFallBack() {
        val h = fixture(true)
        val model = ScriptedModel()
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        vm.onAnswerChange("Retain these original words")
        h.inference.withdraw()
        vm.send()
        h.scheduler.advanceUntilIdle()
        assertEquals("Retain these original words", vm.state.value.answer)
        assertTrue(vm.state.value.consentRequired)
        assertNotNull(openTurn(vm.state.value.session!!))
        assertTrue(model.requests.isEmpty())
        assertTrue(vm.state.value.notice is InterviewNotice.Consent)
    }

    @Test
    fun withdrawalDuringFailedAdmissionKeepsTheAnswerWithoutAllowingAnUnconsentedRetry() {
        val h = fixture(true)
        var reject = true
        h.store = SessionStore(h.dir, io = h.dispatcher, writer = { file, bytes ->
            if (reject) throw IOException("controlled admission failure after withdrawal")
            SessionStore.write(file, bytes)
        })
        val model = ScriptedModel()
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        val text = "These words must remain editable if consent and storage both fail."
        vm.onAnswerChange(text)
        vm.send()
        h.inference.withdraw()
        h.scheduler.runCurrent()
        assertTrue(vm.state.value.notice is InterviewNotice.Failed)
        assertEquals(text, vm.state.value.answer)
        assertNotNull(vm.state.value.openTurn)
        assertTrue(model.requests.isEmpty())
        assertTrue(h.store.sessions.value.isEmpty())

        reject = false
        vm.send()
        h.scheduler.runCurrent()
        assertTrue(vm.state.value.consentRequired)
        assertEquals(text, vm.state.value.answer)
        assertTrue(model.requests.isEmpty())
        h.writes.launch { h.inference.accept(inferenceDestination(inferenceConfig(testInferenceRing()))) }
        h.scheduler.advanceUntilIdle()
        vm.send()
        h.scheduler.advanceUntilIdle()
        assertEquals(2, vm.state.value.turnCount)
        assertEquals(1, model.requests.size)
        assertEquals(text, stringOf((h.store.get(vm.sessionId)!!["turns"] as kotlinx.serialization.json.JsonArray).first()["answer"]))
    }

    @Test
    fun aPendingSynthesisIsNotReplayedOrMarkedDoneWithoutConsent() {
        val h = fixture(false)
        val id = "s_test_00000001"
        val answered = submitAnswer(seedTurn(createSession(id, num(1), str("android")), num(1)),
            str("Keep this write-up request private"), "typed", num(2))
        val pending = setPending(answered, obj("kind" to str("synthesis"), "startedAt" to num(3)), num(3))
        h.writes.launch { h.store.save(pending) }
        h.scheduler.advanceUntilIdle()
        val model = ScriptedModel()
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        assertEquals(pending, h.store.get(id))
        assertTrue(model.requests.isEmpty())
        assertFalse(vm.state.value.finished)
        assertTrue(vm.state.value.consentRequired)
    }

    @Test
    fun aPermissionCallbackCannotBorrowANewerGrantAfterWithdrawal() {
        val h = fixture(true)
        val model = ScriptedModel()
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        val old = h.inference.state.value.permit!!
        h.inference.withdraw()
        var oldAccepted = true
        var freshAccepted = false
        h.writes.launch {
            h.inference.accept(inferenceDestination(inferenceConfig(testInferenceRing())))
            oldAccepted = vm.authorizeVoice(old)
            freshAccepted = vm.authorizeVoice()
        }
        h.scheduler.advanceUntilIdle()
        assertFalse("late microphone callbacks belong to their original grant", oldAccepted)
        assertTrue("a new deliberate voice action may use the new grant", freshAccepted)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun aConsentRejectionFromTheProviderCannotBecomeABankQuestion() {
        val h = fixture(true)
        val model = ScriptedModel(fail = ProviderException("consent", "scripted per-request denial"))
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        vm.onAnswerChange("Keep this submitted answer without creating a checklist substitute")
        vm.send()
        h.scheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.turnCount)
        assertTrue(vm.state.value.consentRequired)
        assertTrue(vm.state.value.notice is InterviewNotice.Consent)
        assertEquals("Keep this submitted answer without creating a checklist substitute",
            stringOf((h.store.get("s_test_00000001")!!["turns"] as kotlinx.serialization.json.JsonArray)[0]["answer"]))
    }

    @Test
    fun aSynthesisConsentRejectionCannotMasqueradeAsAFinishedWriteUp() {
        val h = fixture(true)
        val model = ScriptedModel(failSynthesis = ProviderException("consent", "scripted write-up denial"))
        val vm = h.vm(model)
        h.scheduler.advanceUntilIdle()
        vm.writeUp()
        h.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.finished)
        assertTrue(vm.state.value.consentRequired)
        assertTrue(vm.state.value.notice is InterviewNotice.Consent)
        assertEquals("interviewing", stringOf(vm.state.value.session!!["status"]))
    }
}
