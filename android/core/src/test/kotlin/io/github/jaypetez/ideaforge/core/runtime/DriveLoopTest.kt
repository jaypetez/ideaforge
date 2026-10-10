package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.COMMANDS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The sequencing and cancellation cases of test/drive-loop.test.mjs that the golden vectors
// do not already hold. The vectors pin the ordinary runs, effect for effect; these pin what a
// vector cannot express: a stop arriving mid-effect, a late outcome after it, a stop from
// inside an observer, and coroutine cancellation standing in for the JS AbortError.

/** One scripted capture: text, or a throw. `stopAfter` ends the run after the nth listen. */
private data class Outcome(val text: String = "", val throws: Throwable? = null, val stopAfter: Int = 0)

private fun answer(text: String) = Outcome(text)
private val NOTHING = Outcome("")

/**
 * Hold one effect at its completion until the test releases it, as the JS `deferEffect` does.
 * [started] completes when the effect is entered; [release] lets it finish or fail late.
 */
private class Hold(val effect: String, val matches: (String) -> Boolean = { true }) {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    var used = false
}

/** test/drive-loop.test.mjs's scriptedIo: the last outcome repeats, and `log` is everything. */
private class Scripted(
    private val outcomes: List<Outcome>,
    private val turns: Int = 6,
    private val chips: List<String> = emptyList(),
    private val offerAt: Int? = null,
) : DriveIo {
    val log = mutableListOf<List<String>>()
    var live = true
    var answered = 0
    private var i = 0
    var hold: Hold? = null
    var draft = false
    var count: (() -> Int?)? = null
    var failWith: Pair<String, Throwable>? = null
    var onStateHook: ((DrivePhase) -> Unit)? = null
    var notifyHook: ((String) -> Unit)? = null
    var pauseHook: (() -> Unit)? = null
    var exitHook: (() -> Unit)? = null

    fun said() = log.filter { it[0] == "speak" }.map { it[1] }
    fun only(kind: String) = log.filter { it[0] == kind }
    fun kinds() = log.map { it[0] }

    /** Run the effect's own work, then hold or fail it as the test arranged. */
    private suspend fun effect(name: String, arg: String = "", work: () -> Unit) {
        work()
        failWith?.takeIf { it.first == name }?.let { throw it.second }
        val h = hold
        if (h != null && !h.used && h.effect == name && h.matches(arg)) {
            h.used = true
            h.started.complete(Unit)
            try { h.release.await() } finally { h.finished.complete(Unit) }
        }
    }

    override suspend fun speak(text: String) = effect("speak", text) { log += listOf("speak", text) }
    override suspend fun listen(prompt: String, confirm: Boolean): String {
        val o = outcomes[minOf(i, outcomes.size - 1)]
        i += 1
        effect("listen", if (confirm) "confirm" else "") {
            log += listOf("listen")
            if (o.stopAfter != 0 && i >= o.stopAfter) live = false
        }
        o.throws?.let { throw it }
        return o.text
    }
    override fun openTurn(): DriveTurn? =
        if (answered < turns) DriveTurn(id = "t$answered", question = "q$answered", chips = chips) else null
    override suspend fun submit(text: String) = effect("submit", text) { log += listOf("submit", text); answered += 1 }
    override suspend fun skip() = effect("skip") { log += listOf("skip"); answered += 1 }
    override suspend fun wrap() = effect("wrap") { log += listOf("wrap"); live = false }
    override fun offerWrap(): String? = if (offerAt != null && answered >= offerAt) "coverage" else null
    override fun advisory(reason: String) = "There is enough here to write it up whenever you like."
    override fun notify(message: String) {
        notifyHook?.let { it(message); return }
        log += listOf("notify", message)
    }
    override fun running() = live
    override fun answeredCount(): Int? = count?.invoke()
    override fun hasDraft() = draft
    override fun onState(phase: DrivePhase) {
        log += listOf("state", phase.wire)
        onStateHook?.invoke(phase)
    }
    override suspend fun pause() { log += listOf("pause"); pauseHook?.invoke() }
    override suspend fun exit() { log += listOf("exit"); exitHook?.invoke() }
    override suspend fun scratch() = effect("scratch") { log += listOf("scratch") }
}

private fun states(s: Scripted) = s.only("state").map { it[1] }

class DriveLoopTest {
    // ── the question and its suggestions ───────────────────────────────────────

    @Test
    fun theQuestionAndItsSuggestionsAreTwoUtterances() = runBlocking {
        val chips = listOf(
            "maybe thirty names over three days at the conference",
            "honestly I stopped counting after the first evening",
        )
        val s = Scripted(listOf(answer("first over")), turns = 1, chips = chips)
        DriveLoop(s).run()
        assertEquals(listOf("speak", "speak", "listen"), s.kinds().filter { it == "speak" || it == "listen" })
        assertEquals("q0", s.said()[0], "the question is said alone, never with its examples")
        assertTrue(s.said()[1].startsWith("For example: maybe thirty names"), s.said()[1])
        assertFalse("q0" in s.said()[1], "the examples are their own utterance")
    }

    @Test
    fun repeatReadsTheQuestionAndItsSuggestionsAgain() = runBlocking {
        val s = Scripted(listOf(answer("repeat that"), answer("an answer over")), turns = 1, chips = listOf("like a card"))
        DriveLoop(s).run()
        assertEquals(2, s.said().count { it == "q0" })
        assertEquals(2, s.said().count { it.startsWith("For example") }, "repeating half of it is worse")
    }

    // ── commands never become answers ─────────────────────────────────────────

    @Test
    fun noCommandEverReachesSubmit() = runBlocking {
        for ((kind, phrases) in COMMANDS) {
            for (phrase in phrases) {
                for (said in listOf(phrase, "$phrase, over")) {
                    val s = Scripted(listOf(answer(said), answer("an answer over")), turns = 4)
                    DriveLoop(s).run()
                    val submitted = s.only("submit").map { it[1] }
                    assertTrue(submitted.all { it == "an answer" }, "\"$said\" ($kind) was recorded as an answer: $submitted")
                }
            }
        }
    }

    @Test
    fun overBudgetMidSentenceIsSubmittedWhole() = runBlocking {
        val s = Scripted(listOf(answer("we went over budget"), answer("over the years we grew over")), turns = 2)
        assertEquals(DriveResult.DONE, DriveLoop(s).run())
        assertEquals(listOf("we went over budget", "over the years we grew"), s.only("submit").map { it[1] })
    }

    @Test
    fun commandPhrasesInsideAnswersWithAStopTriggerStillSubmit() = runBlocking {
        val answers = listOf(
            "pause voice playback during calls", "the button should say exit voice",
            "we should stop voice playback before listening",
        )
        val s = Scripted(answers.map { answer("$it stop") }, turns = answers.size)
        s.pauseHook = { error("an answer is not a stand-down command") }
        s.exitHook = s.pauseHook
        assertEquals(DriveResult.DONE, DriveLoop(s, trigger = "stop").run())
        assertEquals(answers, s.only("submit").map { it[1] })
    }

    // ── recovery ───────────────────────────────────────────────────────────────

    @Test
    fun boundedEmptyCaptureRecoveryEndsInAnObservableStop() = runBlocking {
        val s = Scripted(listOf(NOTHING), turns = 20)
        assertEquals(DriveResult.STOPPED, DriveLoop(s).run())
        assertEquals(6, s.only("listen").size)
        assertEquals(2, s.only("skip").size)
        assertEquals(6, states(s).count { it == "recovering" })
        assertEquals("stopped", states(s).last())
        assertFalse(Regex("\\btap\\b|\\btype\\b").containsMatchIn(s.said().dropLast(1).joinToString(" ")),
            "a hands-free recovery never asks for a hand until it stands down")
    }

    @Test
    fun repeatedMissesNeverSkipARetainedDraft() = runBlocking {
        for (capture in listOf(NOTHING, Outcome(throws = ListenFailure("temporary network failure")))) {
            val s = Scripted(listOf(capture), turns = 1).apply { draft = true }
            assertEquals(DriveResult.STOPPED, DriveLoop(s).run())
            assertEquals(emptyList(), s.only("skip"))
            assertEquals(emptyList(), s.only("submit"))
            assertTrue(Regex("draft.*still here", RegexOption.IGNORE_CASE).containsMatchIn(s.said().last()))
        }
    }

    @Test
    fun aBareFinishWordSubmitsARetainedDraft() = runBlocking {
        val s = Scripted(listOf(answer("over")), turns = 1).apply { draft = true }
        DriveLoop(s).run()
        assertEquals(listOf(listOf("submit", "")), s.only("submit"))
        assertEquals(emptyList(), s.only("skip"))
    }

    // ── wrap eligibility ───────────────────────────────────────────────────────

    @Test
    fun aResumedLoopUsesSessionHistoryForWrapEligibility() = runBlocking {
        val s = Scripted(listOf(answer("wrap it up"), Outcome("still waiting", stopAfter = 2)))
        s.count = { 4 }
        assertEquals(DriveResult.WRAPPED, DriveLoop(s).run())
        assertEquals(1, s.only("wrap").size)
        assertEquals(emptyList(), s.only("submit"))
    }

    @Test
    fun invalidPersistedProgressCannotBypassTheWrapFloor() {
        val s = Scripted(listOf(answer("wrap it up"), Outcome("", stopAfter = 2)))
        s.count = { -1 }
        val err = assertFailsWith<IllegalStateException> { runBlocking { DriveLoop(s).run() } }
        assertTrue("nonnegative integer" in err.message.orEmpty())
        assertEquals(emptyList(), s.only("wrap"))
        assertEquals("stopped", states(s).last())
    }

    // ── stand-down commands ────────────────────────────────────────────────────

    @Test
    fun pauseAndExitStandDownAndAStoppedInstanceNeverRestarts() = runBlocking {
        for ((text, command, phase) in listOf(
            Triple("pause", "pause", "paused"), Triple("pause voice, over", "pause", "paused"),
            Triple("exit voice", "exit", "stopped"),
        )) {
            val s = Scripted(listOf(answer(text)), turns = 1)
            val loop = DriveLoop(s)
            if (command == "pause") s.pauseHook = { loop.stop() } else s.exitHook = { loop.stop() }
            assertEquals(DriveResult.STOPPED, loop.run())
            assertEquals(listOf("speaking", "listening", phase), states(s), text)
            assertEquals(emptyList(), s.only("submit") + s.only("skip") + s.only("wrap"))
            assertEquals(1, s.only(command).size)
            assertEquals(listOf("q0"), s.said(), "standing down does not start farewell speech")

            val stoppedLog = s.log.toList()
            loop.stop()
            assertEquals(DriveResult.STOPPED, loop.run())
            assertEquals(stoppedLog, s.log, "a stopped instance never restarts")
        }
    }

    // ── external stop, late outcomes, cancellation ─────────────────────────────

    private data class HeldCase(
        val label: String,
        val effect: String,
        val outcomes: List<Outcome> = listOf(answer("an answer over")),
        val chips: List<String> = emptyList(),
        val offerAt: Int? = null,
        val matches: (String) -> Boolean = { true },
    )

    private val held = listOf(
        HeldCase("question speech", "speak"),
        HeldCase("example speech", "speak", chips = listOf("an example"), matches = { it.startsWith("For example:") }),
        HeldCase("answer capture", "listen"),
        HeldCase("answer submission", "submit"),
        HeldCase("skipping", "skip", outcomes = listOf(answer("skip this one"))),
        HeldCase("recovery speech", "speak", outcomes = listOf(NOTHING), matches = { "catch" in it }),
        HeldCase("draft clearing", "scratch", outcomes = listOf(answer("scratch that"))),
        HeldCase("confirmation speech", "speak", offerAt = 1, matches = { "Shall I write it up" in it }),
        HeldCase("confirmation capture", "listen", outcomes = listOf(answer("one over"), answer("yes")),
            offerAt = 1, matches = { it == "confirm" }),
        HeldCase("confirmation retry", "speak", outcomes = listOf(answer("one over"), answer("unclear")),
            offerAt = 1, matches = { it.startsWith("Sorry") }),
        HeldCase("wrapping", "wrap", outcomes = listOf(answer("one over"), answer("yes")), offerAt = 1),
    )

    @Test
    fun anExternalStopMidEffectReturnsAtOnceAndIgnoresTheLateOutcome() {
        for (case in held) for (late in listOf("resolve", "reject")) runBlocking {
            val s = Scripted(case.outcomes, turns = 3, chips = case.chips, offerAt = case.offerAt)
            val hold = Hold(case.effect, case.matches).also { s.hold = it }
            val loop = DriveLoop(s)
            val returned = CompletableDeferred<DriveResult>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { returned.complete(loop.run()) }
            // Every other effect is immediate, so the held one is reached without dispatch; a
            // loop that never makes that effect fails here rather than waiting for it forever.
            if (!hold.started.isCompleted) {
                loop.stop()
                job.join()
                kotlin.test.fail("${case.label}: the run never reached the held effect: ${s.log}")
            }
            loop.stop()

            // run() itself returns while the held effect is still pending. Bounded yields rather
            // than an await, so a loop that waits for the effect fails here instead of hanging
            // (the effect is released below either way, so the run cannot leak).
            repeat(20) { if (!returned.isCompleted) yield() }
            if (!returned.isCompleted) hold.release.complete(Unit)
            assertTrue(returned.isCompleted, "${case.label}: stop must not wait for the abandoned effect")
            assertEquals(DriveResult.STOPPED, returned.await(), case.label)
            assertFalse(hold.finished.isCompleted, "${case.label}: the effect is abandoned, not awaited")
            assertEquals("stopped", states(s).last(), case.label)
            val stoppedLog = s.log.toList()

            if (late == "resolve" || hold.release.isCompleted) hold.release.complete(Unit)
            else hold.release.completeExceptionally(IllegalStateException("late failure"))
            job.join()
            loop.stop()
            assertEquals(DriveResult.STOPPED, loop.run())
            assertEquals(stoppedLog, s.log, "${case.label}/$late: no late speech, capture, command, mutation or state")
        }
    }

    @Test
    fun concurrentRunCallsShareOneHalfDuplexLoop() = runBlocking {
        val s = Scripted(listOf(answer("one over")), turns = 1)
        val hold = Hold("speak").also { s.hold = it }
        val loop = DriveLoop(s)
        val first = async(start = CoroutineStart.UNDISPATCHED) { loop.run() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { loop.run() }
        hold.started.await()
        assertEquals(listOf("state", "notify", "speak"), s.kinds(), "one loop, one effect in flight")
        hold.release.complete(Unit)
        assertEquals(DriveResult.DONE, first.await())
        assertEquals(DriveResult.DONE, second.await())
        assertEquals(1, s.only("submit").size)
    }

    @Test
    fun stoppingBeforeRunIsIdempotentAndNeverInvokesCommandCallbacks() = runBlocking {
        val s = Scripted(listOf(answer("one over")))
        s.pauseHook = { error("stop is not a spoken command") }
        s.exitHook = s.pauseHook
        val loop = DriveLoop(s)
        loop.stop()
        loop.stop()
        assertEquals(DriveResult.STOPPED, loop.run())
        assertEquals(listOf(listOf("state", "stopped")), s.log)
    }

    @Test
    fun stoppingInAnObserverPreventsTheAnnouncedEffect() {
        for (phase in listOf(DrivePhase.SPEAKING, DrivePhase.LISTENING, DrivePhase.PROCESSING, DrivePhase.RECOVERING)) {
            runBlocking {
                val s = Scripted(if (phase == DrivePhase.RECOVERING) listOf(NOTHING) else listOf(answer("one over")))
                val loop = DriveLoop(s)
                var stoppedLog: List<List<String>>? = null
                s.onStateHook = { next -> if (next == phase) { loop.stop(); stoppedLog = s.log.toList() } }
                assertEquals(DriveResult.STOPPED, loop.run())
                assertEquals(stoppedLog, s.log, "stopped while $phase, yet another effect started")
            }
        }
    }

    @Test
    fun stoppingFromASpeechNotificationPreventsPlayback() = runBlocking {
        val s = Scripted(listOf(answer("one over")))
        val loop = DriveLoop(s)
        s.notifyHook = { loop.stop() }
        s.onStateHook = null
        assertEquals(DriveResult.STOPPED, loop.run())
        assertEquals(emptyList(), s.only("speak"))
        assertEquals(listOf("speaking", "stopped"), states(s))
    }

    @Test
    fun aCancellationFromAnEffectStopsWithoutRecoveryOrFallbackSpeech() {
        // CancellationException is the Kotlin AbortError: the UI cancelled the effect.
        for (effect in listOf("speak", "listen", "submit")) runBlocking {
            val s = Scripted(listOf(answer("one over")))
            s.failWith = effect to CancellationException("cancelled")
            assertEquals(DriveResult.STOPPED, DriveLoop(s).run(), effect)
            assertEquals("stopped", states(s).last())
            assertFalse("recovering" in states(s), effect)
            assertEquals(1, s.only(effect).size, effect)
            assertEquals(listOf("q0"), s.said(), effect)
        }
    }

    @Test
    fun anAbortErrorNamedFailureIsCancellationNotAMiss() {
        // drive.js stops on err.name === 'AbortError'. A voice layer that reports Pause/Exit
        // as a named failure rather than a CancellationException must not be answered with
        // "I didn't catch that" and a fresh capture.
        val abort = { ListenFailure("The capture was aborted.", name = "AbortError") }
        runBlocking {
            val s = Scripted(listOf(Outcome(throws = abort()), answer("never heard over")), turns = 2)
            assertEquals(DriveResult.STOPPED, DriveLoop(s).run())
            assertEquals(1, s.only("listen").size, "an aborted capture is not retried")
            assertEquals(listOf("q0"), s.said(), "and nothing is said after it")
            assertFalse("recovering" in states(s))
            assertEquals("stopped", states(s).last())
        }
        for (effect in listOf("speak", "submit")) runBlocking {
            val s = Scripted(listOf(answer("one over")))
            s.failWith = effect to abort()
            assertEquals(DriveResult.STOPPED, DriveLoop(s).run(), "$effect: an abort is not rethrown")
            assertEquals("stopped", states(s).last(), effect)
            assertFalse("recovering" in states(s), effect)
        }
    }

    @Test
    fun cancellingTheCallerStandsDownAndPropagates() = runBlocking {
        val s = Scripted(listOf(answer("one over")))
        val hold = Hold("listen").also { s.hold = it }
        val loop = DriveLoop(s)
        val job = launch { loop.run() }
        hold.started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled, "the caller's cancellation is not swallowed")
        assertEquals("stopped", states(s).last())
        assertTrue(hold.finished.isCompleted, "a cancelled caller takes its effects with it")
        val log = s.log.toList()
        assertEquals(DriveResult.STOPPED, loop.run(), "and the instance stays retired")
        assertEquals(log, s.log)
    }

    @Test
    fun anActiveEffectFailureIsSurfacedWithAnObservableStandDown() {
        for (effect in listOf("speak", "submit", "scratch")) {
            val s = Scripted(listOf(answer(if (effect == "scratch") "scratch that" else "one over")))
            s.failWith = effect to IllegalStateException("effect failed")
            val err = assertFailsWith<IllegalStateException> { runBlocking { DriveLoop(s).run() } }
            assertEquals("effect failed", err.message, effect)
            assertEquals("stopped", states(s).last(), effect)
            assertFalse("recovering" in states(s), effect)
            assertEquals(emptyList(), s.only("skip"), effect)
        }
    }

    @Test
    fun anExplicitlyUnrecoverableCaptureIsNotRetried() {
        for (failure in listOf(ListenFailure("Voice input is not configured.", fatal = true),
            ListenFailure("Voice input is not configured.", recoverable = false))) {
            val s = Scripted(listOf(Outcome(throws = failure)))
            assertFailsWith<ListenFailure> { runBlocking { DriveLoop(s).run() } }
            assertEquals(1, s.only("listen").size)
            assertEquals(emptyList(), s.only("skip"))
            assertEquals("stopped", states(s).last())
        }
    }

    @Test
    fun aDeniedPermissionIsJudgedByNameAsWellAsMessage() = runBlocking {
        val s = Scripted(listOf(Outcome(throws = ListenFailure("permission prompt dismissed", name = "NotAllowedError"))))
        assertEquals(DriveResult.STOPPED, DriveLoop(s).run())
        assertEquals(1, s.only("listen").size, "a denied microphone is not retried")
        assertTrue(s.said().last().contains("lost the microphone"))
    }

    // ── the wrap confirmation ─────────────────────────────────────────────────

    @Test
    fun pauseOrExitDuringConfirmationStandsDownWithoutAnswering() = runBlocking {
        for (command in listOf("pause", "exit")) {
            val s = Scripted(listOf(answer("one over"), answer("$command voice")), turns = 3, offerAt = 1)
            assertEquals(DriveResult.STOPPED, DriveLoop(s).run())
            assertEquals(listOf(listOf("submit", "one")), s.only("submit"))
            assertEquals(emptyList(), s.only("skip") + s.only("wrap"))
            assertEquals(1, s.only(command).size)
            assertEquals(if (command == "pause") "paused" else "stopped", states(s).last())
            assertFalse(s.said().any { "carrying on" in it || "Sorry" in it })
        }
    }

    @Test
    fun scratchDuringConfirmationKeepsTheBoundedExchange() = runBlocking {
        val s = Scripted(listOf(answer("one over"), answer("scratch that"), answer("yes")), turns = 3, offerAt = 1)
        assertEquals(DriveResult.WRAPPED, DriveLoop(s).run())
        val from = s.kinds().indexOf("scratch")
        assertEquals(listOf("scratch", "speak", "listen", "wrap"),
            s.kinds().drop(from).filter { it != "notify" && it != "state" })
    }

    @Test
    fun aTransientConfirmationFailureIsNeverAYes() = runBlocking {
        val s = Scripted(listOf(answer("one over"), Outcome(throws = ListenFailure("network")), answer("two over")),
            turns = 2, offerAt = 1)
        assertEquals(DriveResult.DONE, DriveLoop(s).run())
        assertEquals(listOf("one", "two"), s.only("submit").map { it[1] })
        assertEquals(emptyList(), s.only("wrap"))
        assertEquals(1, s.said().count { "Shall I write it up" in it })
    }

    @Test
    fun theLoopNeverSuspendsOnItsOwnBetweenEffects() = runBlocking {
        // Half-duplex and deterministic: with every effect immediate, one run completes
        // without the test yielding, so nothing in the loop waits on a clock or dispatch.
        val s = Scripted(listOf(answer("one over")), turns = 1)
        val run = async(start = CoroutineStart.UNDISPATCHED) { DriveLoop(s).run() }
        assertTrue(run.isCompleted, "the loop suspended without an effect asking it to")
        yield()
        assertSame(DriveResult.DONE, run.await())
    }
}
