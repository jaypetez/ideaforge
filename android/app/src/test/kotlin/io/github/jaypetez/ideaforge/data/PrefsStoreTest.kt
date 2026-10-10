package io.github.jaypetez.ideaforge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrefsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // A fresh DataStore over the same file stands in for a process restart.
    private fun <T> withStore(file: File, block: suspend (PrefsStore) -> T): T {
        // DataStore refuses two live instances on one file, so the old one must be fully
        // shut down (cancelAndJoin, not just cancel) before the "restarted" one opens.
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return runBlocking {
            try {
                block(PrefsStore.create(scope) { file })
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private fun file() = File(tmp.root, "prefs.preferences_pb")

    @Test
    fun defaultsMatchTheWebApp() {
        val p = withStore(file()) { it.current() }
        assertEquals(Prefs(trigger = "", handsFree = false, speechVoice = "", speechRate = 1.0, hostedSpeechAllowed = false), p)
        assertEquals("over", p.effectiveTrigger)
    }

    @Test
    fun everyPreferencePersistsAcrossARestart() {
        val f = file()
        withStore(f) { s ->
            assertTrue(
                s.update {
                    it.copy(trigger = "done", handsFree = true, speechVoice = "en-GB-x", speechRate = 1.2, hostedSpeechAllowed = true)
                },
            )
        }
        val p = withStore(f) { it.current() }
        assertEquals(Prefs("done", true, "en-GB-x", 1.2, true), p)
        assertEquals("done", p.effectiveTrigger)
    }

    // prefs.js does not clamp: a rate outside 0.75-1.25 (or not finite) falls back to 1.
    // One write per file: DataStore replaces its file by rename, which a Windows JVM refuses
    // over an existing file, so a second write to one file fails on a Windows host (not on
    // Android). Each case writes once, then a fresh instance reads it back.
    @Test
    fun anOutOfRangeRateFallsBackToOne() {
        val cases = listOf(0.5 to 1.0, 1.26 to 1.0, Double.NaN to 1.0, Double.POSITIVE_INFINITY to 1.0,
            0.75 to 0.75, 1.25 to 1.25, 1.1 to 1.1)
        for ((i, case) in cases.withIndex()) {
            val f = File(tmp.root, "rate$i.preferences_pb")
            assertTrue(withStore(f) { s -> s.update { it.copy(speechRate = case.first) } })
            assertEquals("rate ${case.first}", case.second, withStore(f) { it.current() }.speechRate, 0.0)
        }
    }

    @Test
    fun aVoiceIdIsCappedAt512() {
        val f = file()
        withStore(f) { s -> s.update { it.copy(speechVoice = "v".repeat(600)) } }
        assertEquals(512, withStore(f) { it.current() }.speechVoice.length)
    }

    // A corrupt file used to fail every read and every update for ever. The web falls back
    // to defaults and its next save overwrites; so must this.
    @Test
    fun aCorruptFileFallsBackToDefaultsAndIsOverwritten() {
        val f = file()
        f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42) + "not a proto".toByteArray())
        assertEquals(Prefs(), withStore(f) { it.current() })
        // Replacing the file is a rename over an existing one, which a Windows JVM refuses
        // (Android does not); the write-back half runs on the Linux CI legs.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        assertTrue(withStore(File(f.path)) { s -> s.update { it.copy(handsFree = true, trigger = "done") } })
        val p = withStore(f) { it.current() }
        assertEquals(true, p.handsFree)
        assertEquals("done", p.trigger)
    }
}
