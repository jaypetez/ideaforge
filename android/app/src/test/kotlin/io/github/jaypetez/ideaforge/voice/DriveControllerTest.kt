package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.ui.interview.InterviewViewModel
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.library.LibraryNotice
import io.github.jaypetez.ideaforge.ui.voice.beginVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The hands-free drive end to end on virtual time: DriveLoop, the Recognizer and the turn work
 * are real; the recogniser engine, the speaker and the service are scripted fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rig: DriveRig
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.newFolder()
        rig = DriveRig(dir)
    }

    @After
    fun tearDown() = rig.close()

    @Test
    fun threeTurnsAreDrivenHandsFreeAndEachIsPersisted() {
        repeat(3) { rig.scripts += spoken("answer number ${it + 1} over") }
        rig.controller.start(rig.seeded, "typed")
        assertTrue("the service starts after asynchronous consent admission", rig.until { rig.service.starts == 1 })
        assertEquals(1, rig.service.starts)

        assertTrue(
            "three answers then listening for the fourth; state=${rig.state} said=${rig.speaker.said}",
            rig.until { rig.answered().size == 3 && rig.state.listening },
        )
        val answered = rig.answered()
        // The trigger is stripped and never reaches the answer; the words are the user's own.
        assertEquals(listOf("answer number 1", "answer number 2", "answer number 3"), answered.map { stringOf(it["answer"]) })
        assertTrue(answered.all { stringOf(it["answerSource"]) == "voice" })
        // One model call per answered turn, and the question after each was read aloud first.
        assertEquals(3, rig.model.turnPrompts.size)
        for (t in rig.turns().take(4)) {
            val q = stringOf(t["question"])!!
            assertTrue("read aloud: $q", rig.speaker.said.any { it.endsWith(q) })
        }
        // Nothing is still marked in flight: every turn settled and was saved.
        assertNull(rig.saved()!!["pending"].let { stringOf(it?.get("kind")) })
        assertEquals(0, rig.service.stops)
        assertEquals(VoiceMode.ACTIVE, rig.state.mode)
    }

    @Test
    fun pauseKeepsTheCapturedWordsAndStartsNothingAfterward() {
        // The engine would end this session at 5 s and, were the capture still alive, restart.
        rig.scripts += listOf(
            At(300, Ev.Ready), At(400, Ev.Rms(3f)), At(800, Ev.Partial("half an", unstable = " answer")),
            At(5000, Ev.Error(RecognizerError.NO_MATCH)),
        )
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("answer") })

        val starts = rig.starts()
        val said = rig.speaker.said.size
        rig.controller.pause()
        assertEquals(VoiceMode.PAUSED, rig.state.mode)
        // At once, not when the loop next looks: the microphone is released and speech cancelled.
        rig.scheduler.runCurrent()
        assertEquals(CaptureState.IDLE, rig.recognizer.state.value)
        assertEquals(1, rig.speaker.cancels)
        assertTrue("draft=${rig.draft()}", rig.until { rig.draft() == "half an answer" })
        assertEquals("the paused stage keeps the same parsed words as the saved draft", "half an answer", rig.state.caption)

        rig.advance(60_000)
        assertEquals("no capture after Pause", starts, rig.starts())
        assertEquals("nothing said after Pause", said, rig.speaker.said.size)
        assertTrue(rig.engine().cancels > 0)
        assertTrue(rig.answered().isEmpty())
        // The service stays up while paused: its notification is where Resume lives.
        assertEquals(0, rig.service.stops)

        // Resume is a tap; the kept words lead the answer that follows.
        rig.scripts += spoken("and the rest over")
        rig.controller.resume()
        assertTrue(rig.until { rig.answered().size == 1 })
        assertEquals("half an answer and the rest", stringOf(rig.answered()[0]["answer"]))
        assertEquals("voice", stringOf(rig.answered()[0]["answerSource"]))
    }

    @Test
    fun aReportedQuestionReplacementUpdatesAPausedStageWithoutStartingAnyEffects() {
        rig.scripts += spoken("neutral first answer over")
        rig.scripts += listOf(At(300, Ev.Ready), At(800, Ev.Partial("neutral retained words")))
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.answered().size == 1 && rig.state.caption == "neutral retained words" })
        rig.controller.pause()
        rig.advance(500)
        val before = rig.saved()!!
        val output = io.github.jaypetez.ideaforge.core.reportableOutput(before, "question", "t2")!!
        val reports = io.github.jaypetez.ideaforge.ui.reporting.ContentReportController(
            rig.store, rig.worker, rig.clock, rig.writes, rig.main,
            changed = { session, _ -> rig.controller.refreshReportedSession(session) },
        )
        val starts = rig.starts()
        val said = rig.speaker.said.size
        reports.open(before, output)
        reports.category("other")
        reports.save()
        rig.advance(500)
        reports.replace()
        rig.advance(500)
        assertTrue(reports.state.value.replaced)
        assertEquals(VoiceMode.PAUSED, rig.state.mode)
        assertNotEquals(stringOf(output["question"]), rig.state.question)
        assertEquals("neutral retained words", rig.state.caption)
        assertEquals(before["coverage"], rig.saved()!!["coverage"])
        assertEquals(1, rig.answered().size)
        rig.advance(30_000)
        assertEquals(starts, rig.starts())
        assertEquals(said, rig.speaker.said.size)
    }

    @Test
    fun aReplacementStillRefreshesTheGraphDriveWhenTheReportingViewModelScopeIsCleared() {
        rig.scripts += spoken("neutral first answer over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.answered().size == 1 && rig.state.listening })
        val before = rig.saved()!!
        val ui = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + rig.dispatcher)
        val reports = io.github.jaypetez.ideaforge.ui.reporting.ContentReportController(
            rig.store, rig.worker, rig.clock, rig.writes, ui, drive = { rig.controller },
        )
        reports.open(before, io.github.jaypetez.ideaforge.core.reportableOutput(before, "question", "t2")!!)
        reports.category("other")
        reports.save()
        rig.advance(500)
        assertTrue(reports.state.value.saved)
        val speeches = rig.speaker.said.size
        reports.replace()
        ui.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        rig.advance(500)
        val replacement = openTurn(rig.saved()!!)!!
        assertEquals("replacement", stringOf(replacement["questionSource"]))
        assertEquals("the graph state must follow its durable replacement even after the dialog's UI dies",
            stringOf(replacement["question"]), rig.state.question)
        assertEquals(VoiceMode.PAUSED, rig.state.mode)
        assertEquals(before["coverage"], rig.saved()!!["coverage"])
        assertEquals(speeches, rig.speaker.said.size)
    }

    @Test
    fun exitReturnsToTheTypedInterviewWithoutWrappingUp() {
        rig.scripts += spoken("my first answer over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.answered().size == 1 && rig.state.listening })
        val starts = rig.starts()
        val said = rig.speaker.said.size

        rig.controller.exit()
        assertEquals(VoiceExit.TYPED, rig.state.exit)
        assertFalse(rig.state.attached)
        assertEquals(1, rig.service.stops)

        rig.advance(60_000)
        assertEquals(starts, rig.starts())
        assertEquals(said, rig.speaker.said.size)
        // No write-up was asked for and the idea is not finished.
        assertTrue(rig.model.requests.none { it.first == "complex" })
        assertNotEquals("done", stringOf(rig.saved()!!["status"]))

        // The typed screen reads the idea back and offers the open question.
        val vm = InterviewViewModel(
            rig.id, rig.store, rig.worker, rig.clock, ring = { emptyKeyring() },
            providers = ProviderSource { rig.model }, writes = rig.writes, inference = rig.inference,
        )
        rig.advance(1_000)
        val open = vm.state.value.openTurn
        assertEquals(stringOf(openTurn(rig.saved()!!)!!["question"]), stringOf(open!!["question"]))
        assertFalse(vm.state.value.finished)
        rig.controller.exitShown()
        assertNull(rig.state.sessionId)
    }

    @Test
    fun aMissingLevelIsReportedNotFabricated() {
        // Ready, then nothing at all for a second and a half; then three identical readings.
        rig.scripts += listOf(
            At(300, Ev.Ready), At(1500, Ev.Rms(-2f)), At(1600, Ev.Rms(-2f)), At(1700, Ev.Rms(-2f)),
        )
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.listening })
        rig.advance(500)
        assertTrue(rig.state.listening)
        assertTrue("no reading, no bars", rig.state.levels.isEmpty())
        assertTrue(rig.state.noLevel)

        rig.advance(1_500)
        // Each reading is a sample, even when it repeats the last one exactly.
        assertEquals(listOf(-2f, -2f, -2f), rig.state.levels)
        assertFalse(rig.state.noLevel)
    }

    @Test
    fun aDeafRecogniserIsSurfaced() {
        repeat(3) { rig.scripts += deafSession() }
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until(maxMs = 20_000) { rig.state.deaf })
    }

    @Test
    fun aDeniedMicrophoneStandsDownHavingStartedNothing() {
        val started = beginVoice(rig.controller, rig.seeded to "typed", micGranted = false, deniedMessage = "Microphone access was denied.")
        assertFalse(started)
        assertEquals(VoiceMode.BLOCKED, rig.state.mode)
        assertEquals("Microphone access was denied.", rig.state.detail)
        rig.advance(30_000)
        assertFalse("no recogniser was even built", rig.recognizerBuilt)
        assertEquals(0, rig.service.starts)
        assertTrue(rig.speaker.said.isEmpty())
    }

    @Test
    fun theReportedBackendIsTheLastSuccessfulOutputNotAConfiguredOrCancelledAttempt() {
        rig.speaker.next = { SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.HOSTED) }
        rig.controller.start(rig.seeded, "typed")
        assertNull("nothing has spoken yet", rig.state.lastSpeech)
        assertTrue(rig.until { rig.state.listening })
        assertEquals(SpeechBackend.HOSTED, rig.state.lastSpeech?.backend)
        rig.controller.pause()
        assertEquals("Pause does not invent a fallback", SpeechBackend.HOSTED, rig.state.lastSpeech?.backend)

        val reason = "Scripted hosted failure; the device voice spoke instead."
        rig.speaker.next = { SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.NATIVE, fallback = reason) }
        rig.controller.resume()
        assertTrue(rig.until { rig.state.listening && rig.state.lastSpeech?.fallback == reason })
        assertEquals(SpeechBackend.NATIVE, rig.state.lastSpeech?.backend)

        rig.controller.pause()
        rig.speaker.next = { SpeakResult(SpeakStatus.CANCELLED, backend = SpeechBackend.HOSTED) }
        rig.controller.resume()
        rig.advance(5_000)
        assertEquals("a cancelled attempt never claims it spoke", SpeechBackend.NATIVE, rig.state.lastSpeech?.backend)
        assertEquals(reason, rig.state.lastSpeech?.fallback)
    }

    @Test
    fun pausingDuringTurnWorkKeepsItsQuestionAndSubmittedCaptionWithoutRecreatingADraft() {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.model.gate = hold
        rig.scripts += spoken("my submitted answer over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.phase == io.github.jaypetez.ideaforge.core.runtime.DrivePhase.PROCESSING })
        val question = rig.state.question
        assertTrue(question.isNotEmpty())
        assertEquals("my submitted answer", rig.state.caption)
        rig.controller.pause()
        assertEquals(question, rig.state.question)
        assertEquals("my submitted answer", rig.state.caption)
        hold.complete(Unit)
        rig.advance(5_000)
        assertTrue(rig.draft().isNullOrEmpty())
        assertEquals(VoiceMode.PAUSED, rig.state.mode)
        assertEquals("my submitted answer", rig.state.caption)
        assertEquals(1, rig.answered().size)
    }

    @Test
    fun aSpokenPauseIsNotDisplayedOrAnnouncedAsARetainedAnswer() {
        rig.scripts += spoken("pause")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.mode == VoiceMode.PAUSED })
        assertEquals("a command has no answer words", "", rig.state.caption)
        assertTrue(rig.draft().isNullOrEmpty())
        assertTrue(rig.answered().isEmpty())
    }

    @Test
    fun aSpokenPauseKeepsTheExistingTypedPrefixWithoutIncludingTheCommand() {
        val id = "s_drive_00000002"
        rig.scripts += spoken("pause")
        rig.controller.start(rig.unsaved(id, "typed words"), "typed")
        assertTrue(rig.until { rig.state.mode == VoiceMode.PAUSED })
        assertEquals("typed words", rig.state.caption)
        assertTrue(rig.until { stringOf(rig.store.get(id)?.get("draftAnswer")) == "typed words" })
    }

    @Test
    fun aFatalSpeechFailureStandsDownAndStopsTheService() {
        rig.speaker.next = { SpeakResult(SpeakStatus.FAILED, SpeakFailure.ENGINE_ERROR, "engine error") }
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.mode == VoiceMode.BLOCKED })
        assertTrue(rig.state.detail, rig.state.detail.contains("engine error"))
        assertEquals(1, rig.service.stops)
        val said = rig.speaker.said.size
        rig.advance(30_000)
        assertEquals(said, rig.speaker.said.size)
        assertFalse(rig.recognizerBuilt && rig.starts() > 0)
    }

    private val listeningWithWords = listOf(
        At(300, Ev.Ready), At(400, Ev.Rms(3f)), At(800, Ev.Partial("half an", unstable = " answer")),
    )

    @Test
    fun deletingTheActiveIdeaFromTheLibraryStopsItsEffectsBeforeDeletionCanFinish() {
        rig.writes.launch { rig.store.save(rig.seeded) }
        rig.advance(0)
        assertEquals(rig.seeded, rig.saved())
        rig.scripts += listeningWithWords + At(5000, Ev.Error(RecognizerError.NO_MATCH))
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("answer") })

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.writes.launch {
            rig.worker.exclusive(rig.id) {
                entered.complete(Unit)
                release.await()
            }
        }
        rig.advance(0)
        assertTrue(entered.isCompleted)
        val starts = rig.starts()
        val said = rig.speaker.said.size
        try {
            rig.library.delete(rig.id)
            assertEquals("capture stops before deletion can acquire its lock", CaptureState.IDLE, rig.recognizer.state.value)
            assertTrue(rig.engine().destroyed)
            assertFalse(rig.state.attached)
            assertNull("deletion must not reopen the discarded idea", rig.state.exit)
            assertNull(rig.controller.reportingSession())
            assertEquals(1, rig.speaker.cancels)
            assertEquals(1, rig.service.stops)
            assertEquals("storage is still waiting, not the microphone", rig.seeded, rig.saved())
        } finally {
            release.complete(Unit)
        }
        assertTrue(rig.until { rig.saved() == null && rig.library.notice.value == LibraryNotice.Deleted })
        rig.controller.resume()
        rig.advance(12_000)
        assertEquals("no recognition restart or stale Resume", starts, rig.starts())
        assertEquals("no output after deletion", said, rig.speaker.said.size)
        assertTrue(rig.model.requests.isEmpty())
        assertNull(rig.saved())
        assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
    }

    @Test
    fun deletingAnotherIdeaKeepsTheDriveAndItsSubmittedTurn() {
        val other = "s_drive_00000002"
        rig.writes.launch { rig.store.save(rig.unsaved(other, "another idea")) }
        rig.advance(0)
        val hold = CompletableDeferred<Unit>()
        rig.model.gate = hold
        rig.scripts += spoken("keep my submitted answer over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.model.requests.size == 1 })

        rig.library.delete(other)
        assertEquals(VoiceMode.ACTIVE, rig.state.mode)
        assertEquals(rig.id, rig.state.sessionId)
        assertEquals(0, rig.service.stops)
        assertEquals(0, rig.speaker.cancels)
        assertTrue(rig.until { rig.store.get(other) == null })

        hold.complete(Unit)
        assertTrue(rig.until { rig.answered().size == 1 && rig.state.listening })
        assertEquals("keep my submitted answer", stringOf(rig.answered().single()["answer"]))
        assertEquals(0, rig.service.stops)
        assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
    }

    @Test
    fun aDeletedDrivesLateRecognitionAndModelResultCannotDisturbItsReplacement() {
        val hold = CompletableDeferred<Unit>()
        rig.model.gate = hold
        rig.scripts += spoken("the discarded answer over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.model.requests.size == 1 })
        val oldEngine = rig.engine()
        rig.library.delete(rig.id)
        assertFalse(rig.state.attached)
        assertEquals(1, rig.service.stops)

        val other = "s_drive_00000002"
        val replacement = rig.unsaved(other, "new typed draft")
        rig.writes.launch { rig.store.save(replacement) }
        rig.scripts += listOf(At(300, Ev.Ready), At(500, Ev.Partial("new live words")))
        rig.controller.start(replacement, "typed")
        assertTrue(rig.until { rig.state.caption == "new live words" })
        val current = rig.state
        val starts = rig.starts()
        val said = rig.speaker.said.size

        oldEngine.fire(Ev.Results("late discarded answer over"))
        oldEngine.fire(Ev.Error(RecognizerError.NO_MATCH))
        hold.complete(Unit)
        rig.advance(1000)
        assertEquals(current, rig.state)
        assertEquals(replacement, rig.controller.reportingSession())
        assertEquals(replacement, rig.store.get(other))
        assertNull(rig.saved())
        assertEquals(starts, rig.starts())
        assertEquals(said, rig.speaker.said.size)
        assertEquals(1, rig.model.requests.size)
        assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
    }

    @Test
    fun failedDeletionReportsFailureWithoutRestartingTheOwnersVoice() {
        rig.writes.launch { rig.store.save(rig.seeded) }
        rig.advance(0)
        rig.scripts += listeningWithWords
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("answer") })
        val file = File(dir, "${rig.id}.json")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "held").writeText("controlled deletion failure")
        val starts = rig.starts()
        val said = rig.speaker.said.size

        rig.library.delete(rig.id)
        assertEquals(CaptureState.IDLE, rig.recognizer.state.value)
        assertTrue(rig.until { rig.library.notice.value == LibraryNotice.Failed })
        assertEquals(rig.seeded, rig.saved())
        assertTrue(File(file, "held").exists())
        assertFalse(rig.state.attached)
        assertEquals(1, rig.service.stops)
        rig.controller.resume()
        rig.advance(12_000)
        assertEquals(starts, rig.starts())
        assertEquals(said, rig.speaker.said.size)
        assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
    }

    @Test
    fun deletionBeforeDriveAdmissionPreventsTheServiceAndMicrophoneStarting() {
        rig.writes.launch { rig.store.save(rig.seeded) }
        rig.advance(0)
        rig.controller.start(rig.seeded, "typed")
        rig.library.delete(rig.id)
        rig.advance(12_000)
        assertNull(rig.saved())
        assertFalse(rig.state.attached)
        assertEquals(0, rig.service.starts)
        assertFalse(rig.recognizerBuilt)
        assertTrue(rig.speaker.said.isEmpty())
        assertTrue(rig.crashes.toString(), rig.crashes.isEmpty())
    }

    @Test
    fun deletingTheIdeaMidDriveThenPausingAndResumingWritesNothingAndDoesNotCrash() {
        rig.scripts += listeningWithWords
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("answer") })
        rig.controller.pause()
        assertTrue(rig.until { rig.draft() == "half an answer" })

        rig.delete()
        assertTrue(rig.until { rig.saved() == null })
        val starts = rig.starts()
        rig.controller.resume()
        rig.advance(30_000)
        assertNull("the drive stood down", rig.state.mode)
        assertEquals("no capture for a deleted idea", starts, rig.starts())

        // A drive still active when the idea is deleted, then paused: the draft is dropped.
        rig.scripts += listeningWithWords
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("answer") })
        rig.delete()
        rig.advance(100)
        rig.controller.pause()
        rig.advance(5_000)
        assertNull(rig.saved())
        assertTrue("crashed: ${rig.crashes}", rig.crashes.isEmpty())
    }

    @Test
    fun exitAfterTheIdeaWasDeletedWritesNothingAndDoesNotCrash() {
        rig.scripts += listeningWithWords
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.caption.contains("answer") })
        rig.controller.pause()
        assertTrue(rig.until { rig.draft() == "half an answer" })
        rig.delete()
        assertTrue(rig.until { rig.saved() == null })
        rig.controller.exit()
        rig.advance(5_000)
        assertNull(rig.saved())
        assertTrue("crashed: ${rig.crashes}", rig.crashes.isEmpty())
    }

    @Test
    fun theRecogniserIsReleasedWhenTheDriveEndsAndNeverBuiltJustToRelease() {
        rig.scripts += listeningWithWords
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.state.listening })
        rig.controller.exit()
        rig.advance(100)
        assertTrue("released", rig.engine().destroyed)
    }

    @Test
    fun aStandDownWithNoRecogniserDoesNotBuildOneToRelease() {
        beginVoice(rig.controller, rig.seeded to "typed", micGranted = false, deniedMessage = "denied")
        rig.controller.exit()
        rig.advance(1_000)
        assertFalse(rig.recognizerBuilt)
    }

    @Test
    fun aNewDriveKeepsItsTypedDraftWhateverTheLastDriveCaptured() {
        // The first drive ends while capturing its second question (t2).
        rig.scripts += spoken("my first answer over")
        rig.controller.start(rig.seeded, "typed")
        assertTrue(rig.until { rig.answered().size == 1 && rig.state.listening })
        rig.controller.exit()
        rig.controller.exitShown()

        // A different, never-saved idea whose box held typed words: paused before it listens.
        val other = "s_drive_00000002"
        rig.controller.start(rig.unsaved(other, "typed words"), "typed")
        rig.controller.pause()
        assertTrue(
            "draft=${stringOf(rig.store.get(other)?.get("draftAnswer"))}",
            rig.until { stringOf(rig.store.get(other)?.get("draftAnswer")) == "typed words" },
        )
    }
}
