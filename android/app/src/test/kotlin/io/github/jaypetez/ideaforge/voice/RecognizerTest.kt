package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Recognizer against scripted callback timelines on virtual time: no SpeechRecognizer,
 * no microphone, and a five-second silent session costs nothing. Timelines are shaped on the
 * #89 emulator excerpts (FINDINGS-emulator.md), re-based to the startListening that began
 * each session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerTest {
    private class Rig(scope: CoroutineScope, scripts: List<List<At>>, onDevice: Boolean = false) {
        val factory = FakeFactory { FakeRecognizerEngine(scope, ArrayDeque(scripts), onDevice) }
        val recognizer = Recognizer(factory, scope)
        val engine get() = factory.engines.first()
    }

    private fun TestScope.rig(vararg scripts: List<At>, onDevice: Boolean = false) =
        Rig(backgroundScope, scripts.toList(), onDevice)

    private val handsFree = ListenOptions(CaptureMode.HANDS_FREE)
    private val pushToTalk = ListenOptions(CaptureMode.PRESS_TO_TALK)

    // ---- the trigger ------------------------------------------------------------------

    // Default recogniser, verbatim shape: unstable "we went over" for 684 ms, then the next
    // partial extends it. Ending on the first terminal-looking partial truncates the answer.
    @Test
    fun weWentOverBudgetDoesNotEndTheAnswer() = runTest {
        val r = rig(listOf(
            At(700, Ev.Ready), At(1000, Ev.Begin),
            At(1815, Ev.Partial("", "we went over")),
            At(2499, Ev.Partial("we went over", " budget last")),
            At(3876, Ev.End),
            At(3890, Ev.Partial("we went over budget last year", final = true)),
            At(7595, Ev.Results(null)),
        ))
        val capture = r.recognizer.start(handsFree)
        advanceTimeBy(30_000)
        assertTrue("still listening after a pause", capture.isActive)
        assertEquals("we went over budget last year", r.recognizer.caption.value)
        assertEquals(0, r.engine.stops)
        capture.cancel()
    }

    @Test
    fun weWentOverBudgetOnDeviceDoesNotEndTheAnswer() = runTest {
        val r = rig(listOf(
            At(1800, Ev.Ready),
            At(4465, Ev.Partial("We went over")),
            At(4599, Ev.Partial("We went over")),
            At(4731, Ev.Partial("We went over bud")),
            At(5960, Ev.End),
            At(6035, Ev.Partial("We went over budget last year", final = true)),
            At(9600, Ev.Results(null)),
        ), onDevice = true)
        val capture = r.recognizer.start(handsFree)
        advanceTimeBy(30_000)
        assertTrue(capture.isActive)
        assertEquals("We went over budget last year", r.recognizer.caption.value)
        capture.cancel()
    }

    // "…like Tetris over": the final arrives ~300 ms after the last partial and ends it.
    @Test
    fun aTerminalOverEndsTheAnswer() = runTest {
        val r = rig(listOf(
            At(700, Ev.Ready),
            At(5590, Ev.Partial("I want to make a game like Tetris", " over")),
            At(5890, Ev.Partial("I want to make a game like Tetris over", final = true)),
        ))
        val result = async { r.recognizer.start(handsFree).await() }
        advanceTimeBy(5891)
        runCurrent()
        assertEquals(1, r.engine.stops)
        advanceTimeBy(2000)
        val got = result.await()
        assertEquals(CaptureEnd.TRIGGER, got.end)
        assertEquals("I want to make a game like Tetris over", got.text)
        assertEquals("answer", got.speech().kind)
        assertEquals("I want to make a game like Tetris", got.speech().text)
    }

    // A terminal trigger with no final behind it must stand settleMs before it counts, and
    // the on-device ERROR_CLIENT that answers our stop must keep the partial.
    @Test
    fun aTerminalOverWithNoFinalEndsAfterSettleMsAndKeepsThePartial() = runTest {
        val r = rig(listOf(At(1800, Ev.Ready), At(3000, Ev.Partial("Like Tetris over"))), onDevice = true)
        val result = async { r.recognizer.start(handsFree).await() }
        advanceTimeBy(3000 + 599)
        runCurrent()
        assertEquals("not before settleMs", 0, r.engine.stops)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, r.engine.stops)
        advanceTimeBy(100)
        val got = result.await()
        assertEquals(CaptureEnd.TRIGGER, got.end)
        assertEquals("Like Tetris over", got.text)
        assertTrue("well before finishMs: the ERROR_CLIENT ended it", currentTime < 3000 + 600 + 800)
    }

    @Test
    fun aRepeatedPartialDoesNotPostponeTheTrigger() = runTest {
        val r = rig(listOf(
            At(500, Ev.Ready),
            At(1000, Ev.Partial("That is all over")),
            At(1300, Ev.Partial("That is all over")),
        ), onDevice = true)
        val capture = r.recognizer.start(handsFree)
        advanceTimeBy(1601)
        runCurrent()
        assertEquals("settle counts from the first appearance", 1, r.engine.stops)
        capture.cancel()
    }

    @Test
    fun aCommandReturnsNoAnswerText() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(2000, Ev.Partial("skip this over", final = true))))
        val result = async { r.recognizer.start(handsFree).await() }
        advanceTimeBy(5000)
        val said = result.await().speech()
        assertEquals("skip", said.kind)
        assertEquals("", said.text)
    }

    // ---- restarts ----------------------------------------------------------------------

    @Test
    fun restartsHappenInsideTheCallbackAndJoinWithoutDuplicates() = runTest {
        val r = rig(
            listOf(
                At(700, Ev.Ready),
                At(1500, Ev.Partial("I want to make")),
                At(2500, Ev.Partial("I want to make a game", final = true)),
                At(6000, Ev.Results(null)),
            ),
            listOf(
                At(70, Ev.Ready),
                At(900, Ev.Partial("make a game like")),
                At(1800, Ev.Partial("make a game like Tetris over", final = true)),
            ),
        )
        val result = async { r.recognizer.start(handsFree).await() }
        advanceTimeBy(20_000)
        val got = result.await()
        assertEquals("I want to make a game like Tetris over", got.text)
        assertEquals(1, r.engine.startsInsideCallback)
        assertEquals("one recogniser for the whole capture", 1, r.factory.engines.size)
    }

    @Test
    fun anEmptyOnResultsIsNotAMiss() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(2000, Ev.Partial("a quiz app")), At(4000, Ev.Results(null))))
        val capture = r.recognizer.start(handsFree.copy(firstWordMs = 8000))
        advanceTimeBy(20_000)
        assertTrue("words were heard: the first-word bound is spent", capture.isActive)
        assertEquals("a quiz app", r.recognizer.caption.value)
        capture.cancel()
    }

    // ---- firstWordMs -------------------------------------------------------------------

    // The engine is never silent: it ends a session every ~5 s and is restarted. A bound that
    // a restart resets would never fire, and a deaf hands-free capture would listen for ever.
    @Test
    fun firstWordMsBoundsHandsFreeAndIsNotResetByRestarts() = runTest {
        val r = rig()
        val result = async { r.recognizer.listenForDrive(firstWordMs = 12_000) }
        advanceTimeBy(11_999)
        runCurrent()
        assertTrue("two restarts happened first", r.engine.starts >= 3)
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals("a miss resolves empty, for the recovery ladder", "", result.await())
        assertEquals(12_000L, currentTime - 1)
    }

    @Test
    fun aMissIsReportedAsMissed() = runTest {
        val r = rig()
        val result = async { r.recognizer.start(handsFree.copy(firstWordMs = 7000)).await() }
        advanceTimeBy(8000)
        val got = result.await()
        assertEquals(CaptureEnd.MISSED, got.end)
        assertEquals("", got.text)
        assertEquals(0, got.deafSessions)
    }

    @Test
    fun pressToTalkHasNoFirstWordBound() = runTest {
        val r = rig()
        val capture = r.recognizer.start(pushToTalk.copy(firstWordMs = 5000))
        advanceTimeBy(60_000)
        assertTrue(capture.isActive)
        capture.cancel()
    }

    // ---- press-to-talk -----------------------------------------------------------------

    @Test
    fun releaseOnDefaultTakesTheFlushedFinal() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(2000, Ev.Partial("we went over", " budget"))))
        val capture = r.recognizer.start(pushToTalk)
        advanceTimeBy(2500)
        capture.stop()
        advanceTimeBy(1000)
        val got = capture.await()
        assertEquals(CaptureEnd.STOPPED, got.end)
        assertEquals("we went over budget", got.text)
    }

    // On-device answers a mid-sentence stop with ERROR_CLIENT and the words are lost (2 of 2
    // on the emulator): release must keep the last partial and must not fail.
    @Test
    fun releaseOnDeviceKeepsTheLastPartialThroughErrorClient() = runTest {
        val r = rig(listOf(At(1800, Ev.Ready), At(3000, Ev.Partial("We went over budget"))), onDevice = true)
        val capture = r.recognizer.start(pushToTalk)
        advanceTimeBy(3500)
        capture.stop()
        advanceTimeBy(1000)
        val got = capture.await()
        assertEquals(CaptureEnd.STOPPED, got.end)
        assertEquals("We went over budget", got.text)
    }

    @Test
    fun releaseWithNoFinalAndNoAnswerKeepsThePartialAfterFinishMs() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(2000, Ev.Partial("half a thought"))))
        val capture = r.recognizer.start(pushToTalk)
        r.engine.onStop = { emptyList() }
        advanceTimeBy(2500)
        capture.stop()
        advanceTimeBy(799)
        runCurrent()
        assertTrue(capture.isActive)
        advanceTimeBy(2)
        assertEquals("half a thought", capture.await().text)
    }

    // ---- errors ------------------------------------------------------------------------

    private suspend fun TestScope.failureFor(code: Int): Throwable? {
        val r = rig(listOf(At(1600, Ev.Error(code))))
        val result = async { runCatching { r.recognizer.start(handsFree).await() } }
        advanceTimeBy(1601)
        runCurrent()
        return if (result.isCompleted) result.await().exceptionOrNull() else null
    }

    @Test
    fun permissionIsFatalAndNamedDenied() = runTest {
        val e = failureFor(RecognizerError.INSUFFICIENT_PERMISSIONS) as ListenFailure
        assertEquals("NotAllowedError", e.name)
        assertEquals(true, e.fatal)
        assertTrue(e.message!!.contains("denied"))
    }

    @Test
    fun noMatchAndSpeechTimeoutAreMissesThatRestart() = runTest {
        for (code in listOf(RecognizerError.NO_MATCH, RecognizerError.SPEECH_TIMEOUT)) {
            val r = rig(listOf(At(1000, Ev.Error(code))))
            val capture = r.recognizer.start(handsFree)
            advanceTimeBy(1001)
            runCurrent()
            assertTrue("$code keeps listening", capture.isActive)
            assertEquals("$code restarts on the same recogniser", 2, r.engine.starts)
            assertEquals(1, r.engine.startsInsideCallback)
            capture.cancel()
        }
    }

    @Test
    fun busyIsRecoverableOnTheSameRecogniser() = runTest {
        for (code in listOf(RecognizerError.RECOGNIZER_BUSY, RecognizerError.TOO_MANY_REQUESTS, RecognizerError.CLIENT)) {
            val r = rig(listOf(At(100, Ev.Error(code))))
            val capture = r.recognizer.start(handsFree)
            advanceTimeBy(101)
            runCurrent()
            assertEquals("$code backs off before retrying", 1, r.engine.starts)
            advanceTimeBy(300)
            runCurrent()
            assertEquals(2, r.engine.starts)
            assertEquals(1, r.factory.engines.size)
            assertTrue(capture.isActive)
            capture.cancel()
        }
    }

    // Destroying and recreating inside the callback fails within 50 ms with the very error it
    // was meant to cure. The recreate must come later, from outside.
    @Test
    fun serverDisconnectedRecreatesOutsideTheCallback() = runTest {
        val r = rig(listOf(At(100, Ev.Error(RecognizerError.SERVER_DISCONNECTED))))
        val capture = r.recognizer.start(handsFree)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, r.factory.engines.size)
        val first = r.factory.engines[0]
        assertTrue(first.destroyed)
        assertFalse(first.destroyedInsideCallback)
        assertEquals(1, r.factory.engines[1].starts)
        assertTrue(capture.isActive)
        capture.cancel()
    }

    @Test
    fun persistentBusyBecomesARecoverableFailure() = runTest {
        val busy = listOf(At(100, Ev.Error(RecognizerError.RECOGNIZER_BUSY)))
        val factory = FakeFactory { FakeRecognizerEngine(backgroundScope, ArrayDeque(), after = { busy }) }
        val recognizer = Recognizer(factory, backgroundScope)
        val result = async { runCatching { recognizer.start(handsFree).await() } }
        advanceTimeBy(10_000)
        val e = result.await().exceptionOrNull() as ListenFailure
        assertEquals(true, e.recoverable)
        assertNull(e.fatal)
    }

    @Test
    fun audioAndLanguageFailuresAreNamed() = runTest {
        val audio = failureFor(RecognizerError.AUDIO) as ListenFailure
        assertTrue("DriveLoop's RE_FATAL reads this as a lost microphone", audio.message!!.contains("microphone is unavailable"))
        val lang = failureFor(RecognizerError.LANGUAGE_UNAVAILABLE) as ListenFailure
        assertEquals(false, lang.recoverable)
    }

    @Test
    fun aFailureAfterWordsKeepsThem() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(1000, Ev.Partial("an app for")), At(1500, Ev.Error(RecognizerError.INSUFFICIENT_PERMISSIONS))))
        val result = async { r.recognizer.start(handsFree).await() }
        advanceTimeBy(2000)
        val got = result.await()
        assertEquals(CaptureEnd.INTERRUPTED, got.end)
        assertEquals("an app for", got.text)
    }

    // ---- deafness and the meter --------------------------------------------------------

    @Test
    fun aSessionPinnedToTheFloorIsDeaf() = runTest {
        val factory = FakeFactory { FakeRecognizerEngine(backgroundScope, ArrayDeque(), after = { deafSession() }) }
        val recognizer = Recognizer(factory, backgroundScope)
        val result = async { recognizer.start(handsFree.copy(firstWordMs = 12_000)).await() }
        advanceTimeBy(5011)
        runCurrent()
        assertEquals(CaptureState.DEAF, recognizer.state.value)
        assertEquals(RMS_FLOOR_DB, recognizer.level.value)
        advanceTimeBy(8000)
        val got = result.await()
        assertEquals(CaptureEnd.DEAF, got.end)
        assertEquals(2, got.deafSessions)
        assertEquals("", got.text)
    }

    @Test
    fun lowNoiseIsNotDeafness() = runTest {
        val r = rig()
        val capture = r.recognizer.start(handsFree)
        advanceTimeBy(5100)
        runCurrent()
        assertEquals(CaptureState.LISTENING, r.recognizer.state.value)
        assertEquals(-1.5f, r.recognizer.level.value)
        capture.cancel()
    }

    // ---- watchdog and cancellation -----------------------------------------------------

    @Test
    fun anEngineThatFallsSilentResolvesRatherThanHanging() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(1000, Ev.Partial("a garden planner"))))
        val result = async { r.recognizer.start(handsFree).await() }
        advanceTimeBy(1000 + 6001)
        val got = result.await()
        assertEquals(CaptureEnd.WATCHDOG, got.end)
        assertEquals("a garden planner", got.text)
    }

    @Test
    fun cancelStopsAtOnce() = runTest {
        val r = rig(listOf(At(700, Ev.Ready), At(1000, Ev.Partial("something"))))
        val capture = r.recognizer.start(handsFree)
        advanceTimeBy(1500)
        capture.cancel()
        assertFalse(capture.isActive)
        assertEquals(CaptureEnd.CANCELLED, capture.await().end)
        assertTrue(r.engine.cancels >= 1)
        assertEquals(0, r.engine.stops)
    }

    @Test
    fun coroutineCancellationCancelsTheCapture() = runTest {
        val r = rig()
        val job = launch { r.recognizer.listen(pushToTalk) }
        advanceTimeBy(1000)
        job.cancel()
        runCurrent()
        assertTrue(r.engine.cancels >= 1)
        assertEquals(CaptureState.IDLE, r.recognizer.state.value)
    }

    @Test
    fun aCancelledDriveListenIsAnAbort() = runTest {
        val r = rig()
        val result = async { runCatching { r.recognizer.listenForDrive(firstWordMs = 20_000) } }
        advanceTimeBy(1000)
        r.recognizer.release()
        runCurrent()
        val e = result.await().exceptionOrNull() as ListenFailure
        assertEquals("DriveLoop treats AbortError as Pause/Exit", "AbortError", e.name)
    }
}
