package io.github.jaypetez.ideaforge.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ManualAudioOwnershipTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun manualInputPausesASurvivingOtherIdeasDriveAndNotificationResumeRetiresItBeforeSpeech() {
        val rig = DriveRig(tmp.newFolder())
        val manual = ManualDictation(rig.main, { rig.recognizer }, rig.inference,
            beforeCapture = { rig.controller.pauseFor("Manual dictation requested.") })
        rig.beforeVoiceStart = manual::cancel
        try {
            rig.scripts += listOf(At(100, Ev.Partial("captured for the driving idea")))
            rig.controller.start(rig.seeded, "typed")
            assertTrue(rig.until { rig.state.listening })
            rig.advance(200)
            val permit = rig.main.async { rig.inference.require() }
            rig.scheduler.runCurrent()
            val capture = manual.start(runBlocking { permit.await() })
            val result = rig.main.async { capture.await() }
            rig.scheduler.runCurrent()
            assertEquals(VoiceMode.PAUSED, rig.state.mode)
            assertEquals(rig.id, rig.state.sessionId)
            assertEquals("captured for the driving idea", rig.state.caption)
            assertEquals(DictationPhase.LISTENING, capture.state.value.phase)
            assertTrue("manual capture never starts another service", rig.service.starts == 1)
            rig.controller.resume()
            rig.scheduler.runCurrent()
            assertTrue("notification Resume cancels the old manual handle before any output", result.isCancelled)
            assertTrue(rig.factory.engines.first().destroyed)
            assertTrue(rig.until { rig.state.listening })
            assertEquals(VoiceMode.ACTIVE, rig.state.mode)
            assertEquals("captured for the driving idea", rig.draft())
            assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
        } finally { manual.cancel(); rig.close() }
    }

    @Test fun anOldScreensDisposalCannotReleaseANewerManualCapturesRecognizer() {
        val rig = DriveRig(tmp.newFolder())
        val manual = ManualDictation(rig.main, { rig.recognizer }, rig.inference)
        try {
            val permit = rig.main.async { rig.inference.require() }
            rig.scheduler.runCurrent()
            val allowed = runBlocking { permit.await() }
            val old = manual.start(allowed)
            rig.scheduler.runCurrent()
            val current = manual.start(allowed)
            rig.scheduler.runCurrent()
            assertEquals(2, rig.factory.engines.size)
            old.cancel()
            assertFalse("only the handle that owns a recognizer may release it", rig.factory.engines.last().destroyed)
            assertEquals(DictationPhase.LISTENING, current.state.value.phase)
            current.cancel()
            assertTrue(rig.factory.engines.last().destroyed)
            assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
        } finally { manual.cancel(); rig.close() }
    }

    @Test fun exitingTheOtherPausedDriveCannotReleaseOrDiscardAnOwnedManualCapture() {
        val rig = DriveRig(tmp.newFolder())
        val manual = ManualDictation(rig.main, { rig.recognizer }, rig.inference,
            beforeCapture = { rig.controller.pauseFor("Manual dictation requested.") })
        rig.beforeVoiceStart = manual::cancel
        try {
            rig.scripts += listOf(At(100, Ev.Partial("the driving idea's words")))
            rig.controller.start(rig.seeded, "typed")
            assertTrue(rig.until { rig.state.listening })
            rig.advance(200)
            val permit = rig.main.async { rig.inference.require() }
            rig.scheduler.runCurrent()
            rig.scripts += listOf(At(100, Ev.Partial("the manual idea's words over", final = true)))
            val capture = manual.start(runBlocking { permit.await() })
            val result = rig.main.async { capture.await() }
            rig.advance(200)
            assertEquals(VoiceMode.PAUSED, rig.state.mode)
            rig.controller.exit()
            rig.scheduler.runCurrent()
            assertFalse("Exit belongs only to the paused drive's resources", result.isCompleted)
            assertEquals("the manual idea's words over", capture.state.value.caption)
            capture.stop()
            rig.advance(1000)
            assertEquals("the manual idea's words over", runBlocking { result.await() }.text)
            assertEquals("the driving idea's words", rig.draft())
            assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
        } finally { manual.cancel(); rig.close() }
    }

    @Test fun aBlockedDriveWhisperReadReleasesItsMicrophoneBeforeManualNativeInputCanOpen() {
        val rig = DriveRig(tmp.newFolder())
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val open = AtomicBoolean()
        val microphone = object : PcmSource {
            override val sampleRate = 16_000
            override fun start(): Boolean { open.set(true); return true }
            override fun read(buffer: ShortArray): Int {
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS)) { "owned blocked input was never released" }
                return -1
            }
            override fun stop() { open.set(false); released.countDown() }
        }
        val manual = ManualDictation(rig.main, {
            check(!open.get()) { "replacement native input overlapped the old Whisper microphone" }
            rig.recognizer
        }, rig.inference, beforeCapture = { rig.controller.pauseFor("Manual dictation requested.") })
        rig.beforeVoiceStart = manual::cancel
        rig.recognitionAvailable = false
        rig.whisper = WhisperListener({ microphone }, { _, _ -> error("blocked recording must never upload") }, io = Dispatchers.IO)
        try {
            val permit = rig.main.async { rig.inference.require() }
            rig.scheduler.runCurrent()
            rig.controller.start(rig.seeded, "typed")
            assertTrue(rig.until { rig.state.listening })
            assertTrue("the real read boundary was reached", entered.await(5, TimeUnit.SECONDS))
            val capture = manual.start(runBlocking { permit.await() })
            val result = rig.main.async { runCatching { capture.await() } }
            rig.scheduler.runCurrent()
            assertFalse("handoff releases owned audio synchronously, not after coroutine cancellation settles", open.get())
            assertEquals(DictationPhase.LISTENING, capture.state.value.phase)
            assertFalse(result.isCompleted)
            assertEquals(VoiceMode.PAUSED, rig.state.mode)
        } finally {
            microphone.stop()
            manual.cancel()
            rig.close()
        }
    }
}
