package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.js.stringOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance (#93): Whisper is used only when the recogniser is unavailable, and the two are
 * never open at once. The drive, the loop and the turn work are real; the microphone and the
 * transcription vendor are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhisperFallbackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rig: DriveRig
    private val mics = mutableListOf<FakePcm>()
    private val heard = ArrayDeque<String>()
    private var recognizerOpenWhileRecording = false

    @Before
    fun setUp() {
        rig = DriveRig(tmp.newFolder())
        rig.whisper = WhisperListener(
            source = {
                recognizerOpenWhileRecording = recognizerOpenWhileRecording ||
                    rig.factory.engines.any { !it.destroyed }
                FakePcm(speech()).also { mics += it }
            },
            transcribe = { _, _ -> heard.removeFirstOrNull() ?: "" },
            io = rig.dispatcher,
        )
    }

    @After
    fun tearDown() = rig.close()

    @Test
    fun withoutARecogniserAnswersAreRecordedAndTranscribed() {
        rig.recognitionAvailable = false
        heard += "it should be cheap over"
        rig.controller.start(rig.seeded, "typed")
        assertTrue("answered by Whisper; state=${rig.state}", rig.until { rig.answered().isNotEmpty() })
        assertEquals("it should be cheap", stringOf(rig.answered().first()["answer"]))
        assertTrue(rig.whisperAsked > 0)
        assertTrue("no recogniser was ever created", rig.factory.engines.isEmpty())
        assertTrue("every recording was closed", mics.dropLast(1).all { !it.open })
    }

    @Test
    fun withARecogniserWhisperIsNeverAskedFor() {
        rig.scripts += spoken("answer number 1 over")
        heard += "should never be heard over"
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.answered().isNotEmpty() })
        assertEquals("answer number 1", stringOf(rig.answered().first()["answer"]))
        assertEquals("Whisper is not even built while the recogniser works", 0, rig.whisperAsked)
        assertTrue(mics.isEmpty())
    }

    @Test
    fun aRecogniserThatReportsItselfUnavailableHandsOverAfterBeingReleased() {
        rig.scripts += listOf(At(300, Ev.Error(RecognizerError.LANGUAGE_UNAVAILABLE)))
        heard += "a recorded answer over"
        rig.controller.start(rig.seeded, "typed")
        assertTrue("state=${rig.state}", rig.until { rig.answered().isNotEmpty() })
        assertEquals("a recorded answer", stringOf(rig.answered().first()["answer"]))
        assertFalse("the recogniser was released before recording began", recognizerOpenWhileRecording)
    }

    @Test
    fun withoutConsentOrAKeyThereIsNoFallbackAndTheDriveSaysWhy() {
        rig.recognitionAvailable = false
        rig.whisper = null
        rig.controller.start(rig.seeded, "typed")
        assertTrue("stands down; state=${rig.state}", rig.until { rig.state.detail.contains("transcription") })
        assertTrue(rig.answered().isEmpty())
    }

    // Acceptance: a live hosted failure is visible on the voice stage, not silent.
    @Test
    fun aHostedFallbackIsShownOnTheStage() {
        val reason = "OpenAI speech could not be reached. Using the device voice for the rest of this drive."
        rig.speaker.next = { SpeakResult(SpeakStatus.SPOKEN, fallback = reason) }
        rig.scripts += spoken("answer number 1 over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue("state=${rig.state}", rig.until { rig.state.detail == reason })
    }
    // Review #93: the controller outlives a drive; one LanguageUnavailable must not send every
    // later drive to Whisper.
    @Test
    fun aLaterDriveAsksTheRecogniserAgain() {
        rig.scripts += listOf(At(300, Ev.Error(RecognizerError.LANGUAGE_UNAVAILABLE)))
        heard += "a recorded answer over"
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.answered().isNotEmpty() })
        rig.controller.exit()
        val asked = rig.whisperAsked
        rig.scripts.clear()
        rig.scripts += spoken("answer number 2 over")
        rig.controller.start(rig.saved()!!, "typed")
        assertTrue("state=${rig.state}", rig.until { rig.answered().size == 2 })
        assertEquals("answer number 2", stringOf(rig.answered()[1]["answer"]))
        assertEquals("the second drive never fell back", asked, rig.whisperAsked)
    }

    // Review #93: Pause during a Whisper answer keeps the segments already transcribed.
    @Test
    fun pauseKeepsWhatWhisperAlreadyTranscribed() {
        rig.recognitionAvailable = false
        val second = kotlinx.coroutines.CompletableDeferred<String>()
        var uploads = 0
        rig.whisper = WhisperListener(
            source = { FakePcm(listOf(600L to 40, 1500L to 8000, 4000L to 40, 1500L to 8000)).also { mics += it } },
            transcribe = { _, _ -> if (++uploads == 1) "the first part" else second.await() },
            io = rig.dispatcher,
        )
        rig.controller.start(rig.seeded, "typed")
        assertTrue("second upload in flight", rig.until { uploads == 2 })
        assertTrue("the phase reflects a real pending upload", rig.state.transcribing)
        assertEquals("the accumulated segment is the live caption", "the first part", rig.state.caption)
        assertTrue("recorded input does not fabricate recognizer levels", rig.state.levels.isEmpty())
        rig.controller.pause()
        assertFalse(rig.state.transcribing)
        assertEquals("the first part", rig.state.caption)
        assertTrue("draft=${rig.draft()}", rig.until { rig.draft() == "the first part" })
        second.complete("retired words over")
        rig.advance(30_000)
        assertEquals("a retired upload cannot replace the paused caption", "the first part", rig.state.caption)
        assertEquals("the first part", rig.draft())
        assertTrue(rig.answered().isEmpty())
    }

    @Test
    fun libraryDeletionReleasesRecordingBeforeARetiredUploadCanComplete() {
        rig.recognitionAvailable = false
        val reply = CompletableDeferred<String>()
        var returned = false
        rig.whisper = WhisperListener(
            source = { FakePcm(speech()).also { mics += it } },
            transcribe = { _, _ ->
                // A completed HTTP callback can arrive after cancellation was requested.
                withContext(NonCancellable) { reply.await().also { returned = true } }
            },
            io = rig.dispatcher,
        )
        rig.writes.launch { rig.store.save(rig.seeded) }
        rig.advance(0)
        try {
            rig.controller.start(rig.seeded, "typed")
            assertTrue(rig.until { rig.state.transcribing })
            assertTrue(mics.single().open)
            rig.library.delete(rig.id)
            assertFalse("release cannot wait for the pending upload", mics.single().open)
            assertFalse(rig.state.attached)
            assertEquals(1, rig.service.stops)

            rig.recognitionAvailable = true
            val replacement = rig.unsaved("s_drive_00000002", "new typed words")
            rig.scripts += listOf(At(300, Ev.Ready), At(500, Ev.Partial("new live words")))
            rig.controller.start(replacement, "typed")
            assertTrue(rig.until { rig.state.caption == "new live words" })
            val current = rig.state
            val starts = rig.starts()
            val said = rig.speaker.said.size
            reply.complete("deleted words over")
            rig.advance(1000)
            assertTrue("the adversarial late reply actually completed", returned)
            assertEquals(current, rig.state)
            assertEquals(replacement, rig.controller.reportingSession())
            assertTrue(rig.saved() == null)
            assertEquals(1, mics.single().stops)
            assertEquals(starts, rig.starts())
            assertEquals(said, rig.speaker.said.size)
            assertTrue(rig.model.requests.isEmpty())
            assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
        } finally {
            reply.complete("deleted words over")
            rig.advance(0)
        }
    }

}
