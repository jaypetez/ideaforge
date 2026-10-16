package io.github.jaypetez.ideaforge.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InferenceDriveTest {
    @get:Rule val files = TemporaryFolder()
    private var rig: DriveRig? = null

    @After
    fun close() { rig?.close() }

    @Test
    fun aDeniedDriveStartsNoServiceRecognizerSpeechOrModel() {
        val r = DriveRig(files.newFolder(), agreed = false).also { rig = it }
        r.controller.start(r.seeded, "typed")
        r.advance(1000)
        assertEquals(VoiceMode.BLOCKED, r.state.mode)
        assertEquals(0, r.service.starts)
        assertFalse(r.recognizerBuilt)
        assertTrue(r.speaker.said.isEmpty())
        assertTrue(r.model.requests.isEmpty())
    }

    @Test
    fun withdrawalStopsCaptureAndTheServiceAndRefusesResume() {
        val r = DriveRig(files.newFolder()).also { rig = it }
        r.scripts += listOf(At(300, Ev.Ready), At(600, Ev.Partial("Words kept in the draft")))
        r.controller.start(r.seeded, "typed")
        assertTrue(r.until { r.state.listening })
        r.advance(700)
        r.inference.withdraw()
        r.advance(1000)
        assertEquals(VoiceMode.BLOCKED, r.state.mode)
        assertEquals(r.service.starts, r.service.stops)
        val starts = r.starts()
        val services = r.service.starts
        r.controller.resume()
        r.advance(2000)
        assertEquals(starts, r.starts())
        assertEquals(services, r.service.starts)
        assertTrue(r.model.requests.isEmpty())
        assertTrue(r.crashes.joinToString(), r.crashes.isEmpty())
    }
}
