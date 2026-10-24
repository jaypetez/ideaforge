package io.github.jaypetez.ideaforge.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
        val manual = ManualDictation(rig.main, { rig.recognizer }, rig.inference, beforeCapture = rig.controller::pause)
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
}
