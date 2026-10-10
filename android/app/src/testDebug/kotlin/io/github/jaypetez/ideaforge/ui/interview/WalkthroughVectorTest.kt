package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.ScriptedProvider
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.vectors.Case
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.data.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The `walkthrough` golden vector, replayed through InterviewViewModel itself with the debug
 * build's ScriptedProvider (the very asset `--es provider scripted` loads). The JS run and this
 * one must reach the same session byte for byte: a screen that strings the core calls together
 * differently (a chip sent as typed, a wrap offer not recorded, a draft write landing after
 * the answer, an extra write between turns) fails here even though every core call is right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalkthroughVectorTest {
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

    @Test
    fun typedAnswers() = replay("typed answers")

    @Test
    fun aChipAnswer() = replay("a chip answer")

    @Test
    fun aSkippedQuestion() = replay("a skipped question")

    @Test
    fun everyRecordedCaseIsReplayedHere() {
        assertEquals(
            setOf("typed answers", "a chip answer", "a skipped question"),
            Vectors.load("walkthrough").cases.map { it.name }.toSet(),
        )
    }

    /**
     * The scripted model never fails and the store is a private temp folder, so any Failed or
     * Fallback notice means something underneath threw. Without this, a turn whose save threw
     * only showed up as an interview two turns short (#138); with it, the failure names the
     * exception and carries its stack.
     */
    private fun assertNoFailure(vm: InterviewViewModel, name: String, where: String) {
        val notice = vm.state.value.notice
        if (notice is InterviewNotice.Failed || notice is InterviewNotice.Fallback) {
            val cause = notice.cause
            throw AssertionError(
                "$name: ${notice::class.simpleName} notice $where: ${notice.detail}" +
                    (cause?.let { " [${it::class.qualifiedName}]" } ?: ""),
                cause,
            )
        }
    }

    private fun replay(name: String) {
        val case: Case = Vectors.load("walkthrough").cases.single { it.name == name }
        val input = case.input
        val expected = case.expect["value"]!!
        val model = ScriptedProvider(ScriptedProvider.loadScript(RuntimeEnvironment.getApplication()))
        val id = stringOf(input["id"])!!
        val now = toNumber(input["now"]).toLong()
        val vm = h.vm(model, id = id, clock = Clock { now })
        h.scheduler.advanceUntilIdle()
        assertNoFailure(vm, name, "opening")

        for (step in input["steps"] as JsonArray) {
            val open = vm.state.value.openTurn!!
            when {
                truthy(step["skip"]) -> vm.skip()
                step["chip"] != null -> {
                    vm.onChip(stringOf((open["chips"] as JsonArray)[toNumber(step["chip"]).toInt()])!!)
                    vm.send()
                }
                else -> {
                    // Typed in one go and sent before the debounce: no draft write.
                    vm.onAnswerChange(stringOf(step["text"])!!)
                    vm.send()
                }
            }
            h.scheduler.advanceUntilIdle()
            assertNoFailure(vm, name, "after answering turn ${vm.state.value.turnCount}")
            val st = vm.state.value
            if (st.finished || st.openTurn == null || st.wrapOffer != null) break
        }
        // Accept the offer, or press "Write it up" when the answers ran out first.
        if (!vm.state.value.finished) vm.writeUp()
        h.scheduler.advanceUntilIdle()
        assertNoFailure(vm, name, "the write-up")

        assertTrue(vm.state.value.finished)
        val saved = h.store.get(id)!!
        assertEquals("$name: the canonical session", expected["session"], saved)
        assertEquals("$name: what the screen holds is what was saved", saved, vm.state.value.session)
        val hasPrompt = truthy(saved["synthesis"]["text"])
        assertEquals(stringOf(expected["export"]), buildExport(saved, if (hasPrompt) "claude" else "checklist", null))
        val requests = (expected["requests"] as JsonArray).map { stringOf(it["modelTier"]) to stringOf(it["promptHash"]) }
        assertEquals("$name: every prompt, byte for byte", requests, model.requests.map { (tier, parts) -> tier to promptHash(parts.joined()) })
    }
}
