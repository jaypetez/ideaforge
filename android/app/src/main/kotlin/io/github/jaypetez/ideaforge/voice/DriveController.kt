package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.DEFAULT_TRIGGER
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.parseSpeech
import io.github.jaypetez.ideaforge.core.runtime.DriveIo
import io.github.jaypetez.ideaforge.core.runtime.DriveLoop
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.core.runtime.DriveResult
import io.github.jaypetez.ideaforge.core.runtime.DriveTurn
import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.setDraftAnswer
import io.github.jaypetez.ideaforge.core.setWrapOffered
import io.github.jaypetez.ideaforge.core.shouldOfferWrap
import io.github.jaypetez.ideaforge.core.skipQuestion
import io.github.jaypetez.ideaforge.core.wrapAdvisory
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.InferenceConsent
import io.github.jaypetez.ideaforge.data.InferencePermit
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.interview.InterviewWork
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** What the voice stage is doing. Null on [VoiceStageState.mode] means no voice run. */
enum class VoiceMode {
    ACTIVE,
    /** Pause: microphone and speech off, captured words kept as the draft. Resume needs a tap. */
    PAUSED,
    /** Stood down by a failure (a denied microphone, a fatal speech error). Resume needs a tap. */
    BLOCKED,
}

/** Where Exit (or the end of the interview) hands the screen back to. */
enum class VoiceExit { TYPED, RESULT }

/**
 * Everything the voice stage and the notification draw. Presentation only: none of it is
 * persisted, and the session itself is written through [InterviewWork] like a typed turn.
 */
data class VoiceStageState(
    val sessionId: String? = null,
    val mode: VoiceMode? = null,
    val phase: DrivePhase? = null,
    val question: String = "",
    /** What the recogniser has heard in this capture (settled plus unstable words). */
    val caption: String = "",
    /** What is being read aloud right now. */
    val said: String = "",
    /** A status line: why it paused, what went wrong. */
    val detail: String = "",
    val listening: Boolean = false,
    val confirm: Boolean = false,
    /** This capture's level readings, newest last. Never synthesised: empty means none came. */
    val levels: List<Float> = emptyList(),
    /** A whole recogniser session pinned at the level floor: the screen-off signature. */
    val deaf: Boolean = false,
    val writing: Boolean = false,
    /** The word that ends an answer, for the listening hint. */
    val trigger: String = DEFAULT_TRIGGER,
    /** Set once when the run has ended; the screen consumes it with [DriveController.exitShown]. */
    val exit: VoiceExit? = null,
) {
    val attached: Boolean get() = mode != null

    /** Listening, and no level reading has arrived: reported, never animated around. */
    val noLevel: Boolean get() = listening && levels.isEmpty()
}

/** Starts and stops the microphone foreground service. The Android one is [DriveService]. */
interface DriveServiceControl {
    /** Only from a user tap while the app is visible: the platform's rule for type microphone. */
    fun start()
    fun stop()
}

/** DRIVING_GATE.noSpeechMs and CONFIRM_GATE.noSpeechMs in src/voice/vad.js. */
const val DRIVE_FIRST_WORD_MS = 8000L
const val CONFIRM_FIRST_WORD_MS = 6000L
private const val LEVEL_HISTORY = 24

/**
 * Failures that mean the recogniser cannot serve this device at all (not a busy or missed
 * capture, which the recovery ladder owns): only these switch a drive to the Whisper fallback.
 */
private val UNAVAILABLE = setOf("LanguageUnavailable")

/**
 * The hands-free interview: DriveLoop wired to the Recognizer, the Speaker and the same turn
 * work the typed screen uses. The Kotlin form of runHandsFree, pauseVoice, resumeVoice and
 * exitVoice in src/ui/app.js.
 *
 * It lives in the app graph, not a ViewModel, so a drive outlives the screen: [DriveService]
 * keeps the process in the foreground (and the recogniser hearing with the screen off), the
 * loop runs in [main], and the interview screen re-attaches by reading [state].
 *
 * The rules it keeps:
 *  - [epoch] is the identity guard. Pause, Exit, stand-down and every new run bump it; an
 *    effect belonging to an older epoch finds `running()` false and starts nothing.
 *  - Pause stops effects, not history: the loop is stopped, the run's job cancelled (which
 *    cancels the capture and the speech), and the words captured so far become the draft. A
 *    turn already submitted keeps running in the app scope and settles for its idea; Resume
 *    reads the idea back under its lock, so it waits that turn out.
 *  - Nothing here starts the microphone on its own. [start] and [resume] are taps.
 *  - The question and its suggestions are two utterances: DriveLoop says them separately.
 *
 * Every public method must be called on [main]'s thread (the platform recogniser is
 * main-thread only, and the service delivers its actions there).
 */
class DriveController(
    private val store: SessionStore,
    private val worker: SessionWorker,
    private val clock: Clock,
    private val ring: () -> JsonElement,
    private val providers: () -> ProviderSource,
    private val writes: CoroutineScope,
    private val main: CoroutineScope,
    private val recognizerSource: () -> Recognizer,
    private val speaker: () -> Speaker,
    private val service: DriveServiceControl,
    private val inference: InferenceConsent,
    private val trigger: suspend () -> String = { DEFAULT_TRIGGER },
    /** SpeechRecognizer.isRecognitionAvailable: false sends every capture to [whisper]. */
    private val recognitionAvailable: () -> Boolean = { true },
    /**
     * The Whisper fallback (#93), or null when its audio-transfer consent or its own stt key
     * is missing. Asked for only when the recogniser is unavailable, never alongside it.
     */
    private val whisper: suspend () -> WhisperListener? = { null },
) {
    private val _state = MutableStateFlow(VoiceStageState())
    val state: StateFlow<VoiceStageState> = _state.asStateFlow()

    private var epoch = 0L
    private var session: Session? = null
    private var sessionId: String? = null
    private var work: InterviewWork? = null
    private var provider: Provider? = null
    private var providerBuilt = false
    private var providerPermit: InferencePermit? = null
    private var providerRing: JsonElement? = null
    private var permit: InferencePermit? = null
    private var triggerWord = DEFAULT_TRIGGER

    /** The draft that was in the answer box when this capture began, and its source. */
    private var prefix = ""
    private var prefixSource = "typed"
    /** The question the current capture is answering, so a late draft cannot land on the next. */
    private var captureTurnId: String? = null

    private var loop: DriveLoop? = null
    private var job: Job? = null
    private var watch: Job? = null
    private var serviceRunning = false
    private var recognizerBuilt = false
    /** The recogniser said it is unavailable once: the rest of the drive records instead. */
    private var recognizerUnavailable = false
    /**
     * What the Whisper fallback has transcribed of the current answer so far: its caption.
     * Pause and Exit read it, as they read the recogniser's, so segments already transcribed
     * are kept as the draft rather than lost with the capture.
     */
    private var whisperText = ""
    private var whisperListening = false

    /** The caption Pause/Exit keep: the recogniser's, or Whisper's while it is the one listening. */
    private fun liveCaption(): String = if (whisperListening) whisperText else recognizer().caption.value

    private fun recognizer(): Recognizer = recognizerSource().also { recognizerBuilt = true }

    /** The last draft write, for tests and for Exit to be sure it landed. */
    var draftWrite: Job? = null
        private set

    // ─────────────────────────────────────────────────────────── taps

    /**
     * Start driving [session], whose `draftAnswer` is what the answer box held, typed by the
     * user ([draftSource] `typed`) or an unedited chip (`chip`). A user tap, on the visible app.
     */
    fun start(session: Session, draftSource: String) {
        val id = stringOf(session["id"]) ?: return
        if (_state.value.mode == VoiceMode.ACTIVE && sessionId == id) return
        if (_state.value.mode != null) halt()
        adopt(id, session, draftSource)
        _state.value = VoiceStageState(sessionId = id, mode = VoiceMode.ACTIVE, question = questionOf(session))
        epoch++
        val my = epoch
        val revision = inference.state.value.revision
        job = main.launch {
            try {
                val allowed = inference.require()
                if (my != epoch || revision != inference.state.value.revision) return@launch
                permit = allowed
                serviceOn()
                begin(my)
            } catch (error: ProviderException) {
                if (my == epoch) blockForInference(error.message.orEmpty())
            }
        }
    }

    /**
     * The microphone permission was refused: stand down with [message], visibly, having
     * started nothing at all.
     */
    fun standDown(session: Session, draftSource: String, message: String) {
        val id = stringOf(session["id"]) ?: return
        if (_state.value.mode != null) halt()
        adopt(id, session, draftSource)
        epoch++
        _state.value = VoiceStageState(
            sessionId = id, mode = VoiceMode.BLOCKED, phase = DrivePhase.STOPPED,
            question = questionOf(session), detail = message,
        )
    }

    /** Pause, from the stage or the notification. */
    fun pause() = pauseRun(command = false, detail = null)

    /** Pause with a reason: hosted speech or transcription was revoked, or Forget ran. */
    fun pauseFor(detail: String) = pauseRun(command = false, detail = detail)

    fun blockForInference(detail: String) {
        if (_state.value.mode == VoiceMode.ACTIVE) {
            pauseRun(command = false, detail = detail, blocked = true)
        } else if (_state.value.mode != null) {
            epoch++
            stopEffects()
            serviceOff()
            _state.update { it.copy(mode = VoiceMode.BLOCKED, detail = detail) }
        }
    }

    /** Resume after Pause or a stand-down: a tap, from the stage or the notification. */
    fun resume() {
        val st = _state.value
        if (st.mode != VoiceMode.PAUSED && st.mode != VoiceMode.BLOCKED) return
        val id = sessionId ?: return
        _state.update { it.copy(mode = VoiceMode.ACTIVE, phase = null, detail = "", exit = null) }
        epoch++
        val my = epoch
        val revision = inference.state.value.revision
        job = main.launch {
            val allowed = try {
                inference.require()
            } catch (error: ProviderException) {
                if (my == epoch) blockForInference(error.message.orEmpty())
                return@launch
            }
            // Under the idea's lock: a turn the pause left in flight settles first, and what is
            // read is what it saved.
            val stored = worker.exclusive(id) { store.get(id) }
            if (my != epoch) return@launch
            // Deleted while paused: there is nothing to resume, and nothing may be written.
            if (stored == null && id in store.knownIds()) {
                if (my != epoch) return@launch
                halt()
                _state.value = VoiceStageState()
                return@launch
            }
            val latest = stored ?: session
            if (latest != null) {
                session = latest
                prefix = stringOf(latest["draftAnswer"]).orEmpty().trim()
            }
            if (revision != inference.state.value.revision || !inference.current(allowed)) return@launch
            permit = allowed
            serviceOn()
            begin(my)
        }
    }

    /** Exit, from the stage or the notification: back to typing, or the result once written. */
    fun exit() = exitRun(command = false)

    /** The screen has acted on [VoiceStageState.exit]. */
    fun exitShown() {
        if (_state.value.exit != null) _state.value = VoiceStageState()
    }

    // ─────────────────────────────────────────────────────────── the run

    private fun adopt(id: String, s: Session, draftSource: String) {
        if (id != sessionId) {
            provider = null
            providerBuilt = false
        }
        sessionId = id
        session = s
        work = InterviewWork(id, store, worker, clock, writes)
        prefix = stringOf(s["draftAnswer"]).orEmpty().trim()
        prefixSource = draftSource
        // A capture id left by an earlier drive would make keepDraft think this question was
        // already answered and drop the typed draft.
        captureTurnId = null
    }

    private suspend fun begin(my: Long) {
        // Every drive asks the recogniser afresh: one LanguageUnavailable (a language pack
        // missing that afternoon) must not send every later drive to Whisper, or stand it down.
        recognizerUnavailable = false
        val rec = recognizer()
        watch?.cancel()
        watch = main.launch {
            launch {
                rec.caption.collect { c -> if (my == epoch && _state.value.listening) _state.update { it.copy(caption = c) } }
            }
            launch {
                var last = rec.samples.value.seq
                rec.samples.collect { s ->
                    if (s.seq <= last) return@collect
                    last = s.seq
                    if (my != epoch || !_state.value.listening) return@collect
                    val loud = s.db > RMS_FLOOR_DB + 0.05f
                    _state.update { it.copy(levels = (it.levels + s.db).takeLast(LEVEL_HISTORY), deaf = it.deaf && !loud) }
                }
            }
            launch {
                rec.state.collect { cs ->
                    if (my != epoch) return@collect
                    when (cs) {
                        CaptureState.DEAF -> _state.update { it.copy(deaf = true) }
                        CaptureState.HEARING -> _state.update { it.copy(deaf = false) }
                        else -> Unit
                    }
                }
            }
        }
        try {
            runLoop(my)
        } catch (error: ProviderException) {
            if (my == epoch) blockForInference(error.message.orEmpty())
        }
    }

    private suspend fun runLoop(my: Long) {
        val allowed = permit ?: return
        inference.require(allowed)
        val currentRing = ring()
        if (!providerBuilt || providerPermit != allowed || providerRing != currentRing) {
            // No usable provider is the typed screen's NoProvider: questions come from the bank.
            provider = try {
                providers().buildAuthorized(currentRing) { inference.require(allowed) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProviderException) {
                if (error.code == "consent") {
                    blockForInference(error.message.orEmpty())
                    return
                }
                null
            }
            providerBuilt = true
            providerPermit = allowed
            providerRing = currentRing
        }
        triggerWord = trigger()
        if (my != epoch) return
        _state.update { it.copy(trigger = triggerWord) }
        if (session?.let(::openTurn) == null) {
            finish()
            return
        }
        runCatching { speaker().newSession() }
        val drive = DriveLoop(Io(my, allowed, provider), triggerWord)
        loop = drive
        val result = try {
            drive.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (my == epoch) block("Voice stopped: ${e.message ?: e::class.simpleName}")
            return
        }
        if (my != epoch) return
        when (result) {
            DriveResult.DONE, DriveResult.WRAPPED -> finish()
            // The loop stood down on its own (misses, a kept draft, a lost microphone).
            DriveResult.STOPPED -> pauseRun(
                command = true,
                detail = _state.value.said.ifBlank { "Voice paused after input trouble. Resume to retry, or Exit to type." },
            )
        }
    }

    /** Cancel every effect of the current run. The epoch must already have moved on. */
    private fun stopEffects() {
        loop?.stop()
        loop = null
        job?.cancel()
        job = null
        watch?.cancel()
        watch = null
        runCatching { speaker().cancel() }
    }

    private fun halt() {
        epoch++
        stopEffects()
        serviceOff()
        // The run is over (Exit, done, a fatal stand-down): give the recognition service back.
        // Only one this controller actually built; reading the provider would build one.
        if (recognizerBuilt) runCatching { recognizer().release() }
    }

    private fun pauseRun(command: Boolean, detail: String?, blocked: Boolean = false) {
        val st = _state.value
        if (st.mode != VoiceMode.ACTIVE) return
        // Read before the cancel: the caption is the best record of what was said, because
        // the on-device recogniser discards the utterance when it is stopped (#89).
        val captured = if (!command && st.listening && !st.confirm) liveCaption() else ""
        val turnId = captureTurnId
        epoch++
        stopEffects()
        if (blocked) serviceOff()
        _state.update {
            it.copy(
                mode = if (blocked) VoiceMode.BLOCKED else VoiceMode.PAUSED,
                phase = DrivePhase.PAUSED, listening = false, confirm = false, levels = emptyList(),
                caption = "", said = "", question = questionOf(session),
                detail = detail ?: "Microphone off. Your captured words stay here. Tap Resume when you are ready.",
            )
        }
        keepDraft(captured, turnId)
    }

    private fun block(detail: String) {
        pauseRun(command = true, detail = detail, blocked = true)
        serviceOff()
    }

    private fun exitRun(command: Boolean) {
        val st = _state.value
        if (st.mode == null) return
        val captured = if (!command && st.mode == VoiceMode.ACTIVE && st.listening && !st.confirm) liveCaption() else ""
        val turnId = captureTurnId
        val written = st.writing || stringOf(session?.get("status")) == "done"
        halt()
        keepDraft(captured, turnId)
        _state.value = VoiceStageState(sessionId = sessionId, exit = if (written) VoiceExit.RESULT else VoiceExit.TYPED)
    }

    /** The interview has nothing left to ask, or was wrapped up. */
    private fun finish() {
        val done = stringOf(session?.get("status")) == "done"
        halt()
        _state.value = VoiceStageState(sessionId = sessionId, exit = if (done) VoiceExit.RESULT else VoiceExit.TYPED)
    }

    /**
     * The draft is the box's prefix plus what this capture heard (unless that was a command).
     * Written read-modify-write under the idea's lock, against what is on disk, and only while
     * the question it answers is still the open one: a turn submitted before the pause may
     * have settled since, and its answer must not get this draft written over the next question.
     */
    private fun keepDraft(captured: String, turnId: String?) {
        val said = parseSpeech(captured, triggerWord)
        val heard = if (said.kind == "answer") said.text.trim() else ""
        val text = listOf(prefix, heard).filter { it.isNotEmpty() }.joinToString(" ")
        prefix = text
        if (heard.isNotEmpty()) prefixSource = "voice"
        val id = sessionId ?: return
        val w = work ?: return
        val fallback = session
        // Tagged now, at Pause/Exit: deleting the idea retires the tag and the write is dropped.
        val tag = worker.tag(id)
        draftWrite = writes.launch {
            val saved = runCatching {
                worker.run(tag, work = {
                    val stored = store.get(id)
                    // The in-memory copy stands in only for an idea never saved, never for one
                    // deleted (the store refuses that save, and the idea is meant to be gone).
                    val latest = stored ?: fallback?.takeIf { id !in store.knownIds() } ?: return@run null
                    val open = openTurn(latest) ?: return@run null
                    if (turnId != null && stringOf(open["id"]) != turnId) return@run null
                    if (stored != null && stringOf(stored["draftAnswer"]).orEmpty() == text) return@run null
                    setDraftAnswer(latest, str(text), w.now()).also { store.save(it) }
                })
            }.getOrNull()
            val value = (saved as? SessionWorker.Outcome.Committed)?.value
            if (value != null) main.launch { if (sessionId == id) session = value }
        }
    }

    private fun serviceOn() {
        if (serviceRunning) return
        serviceRunning = true
        service.start()
    }

    private fun serviceOff() {
        if (!serviceRunning) return
        serviceRunning = false
        service.stop()
    }

    // ─────────────────────────────────────────────────────────── effects

    private inner class Io(private val my: Long, private val allowed: InferencePermit, private val model: Provider?) : DriveIo {
        override fun running(): Boolean = my == epoch && _state.value.mode == VoiceMode.ACTIVE && inference.current(allowed)

        override suspend fun speak(text: String) {
            if (!running()) return
            inference.require(allowed)
            val result = speaker().speak(text)
            // Hosted speech failed live and the device voice took over: visibly, never silently.
            result.fallback?.let { reason -> if (running()) _state.update { it.copy(detail = reason) } }
            when (result.status) {
                SpeakStatus.SPOKEN -> Unit
                SpeakStatus.CANCELLED -> throw ListenFailure("Speech was cancelled.", name = "AbortError")
                SpeakStatus.FAILED -> when (result.failure) {
                    // A call rang or navigation spoke: pause, keep everything, wait for a tap.
                    SpeakFailure.INTERRUPTED, SpeakFailure.FOCUS_DENIED -> {
                        pauseRun(command = true, detail = "Paused: another app needed the audio. Tap Resume when you are ready.")
                        throw ListenFailure("Speech was interrupted.", name = "AbortError")
                    }
                    else -> throw ListenFailure(
                        result.message ?: "Speech output failed (${result.failure}).", name = "SpeechError", fatal = true,
                    )
                }
            }
        }

        override suspend fun listen(prompt: String, confirm: Boolean): String {
            if (!running()) return ""
            inference.require(allowed)
            captureTurnId = session?.let(::openTurn)?.let { stringOf(it["id"]) }
            _state.update { it.copy(listening = true, confirm = confirm, caption = "", levels = emptyList()) }
            val firstWordMs = if (confirm) CONFIRM_FIRST_WORD_MS else DRIVE_FIRST_WORD_MS
            try {
                if (recognizerUnavailable || !recognitionAvailable()) return transcribed(firstWordMs)
                return try {
                    recognizer().listenForDrive(triggerWord, firstWordMs = firstWordMs)
                } catch (e: ListenFailure) {
                    if (e.name !in UNAVAILABLE) throw e
                    recognizerUnavailable = true
                    transcribed(firstWordMs)
                }
            } finally {
                if (my == epoch) _state.update { it.copy(listening = false, confirm = false) }
            }
        }

        /**
         * Whisper, only because the recogniser is unavailable. The recogniser is released
         * first, so the service's microphone and this app's recording are never open at once.
         */
        private suspend fun transcribed(firstWordMs: Long): String {
            inference.require(allowed)
            if (recognizerBuilt) runCatching { recognizer().release() }
            val w = whisper() ?: throw ListenFailure(
                "Speech recognition is unavailable on this device. To dictate, turn on transcription in Settings " +
                    "with its own key and consent to send audio.",
                name = "RecognizerUnavailable", recoverable = false,
            )
            whisperText = ""
            whisperListening = true
            try {
                return w.listenForDrive(triggerWord, firstWordMs) { heard -> if (my == epoch) whisperText = heard }
            } finally {
                if (my == epoch) whisperListening = false
            }
        }

        override fun openTurn(): DriveTurn? {
            if (!running()) return null
            val t = session?.let(::openTurn) ?: return null
            return DriveTurn(
                id = stringOf(t["id"]),
                question = stringOf(t["question"]).orEmpty(),
                bridge = stringOf(t["bridge"]),
                chips = (t["chips"] as? JsonArray).orEmpty().mapNotNull(::stringOf),
            )
        }

        override suspend fun submit(text: String) {
            if (!running()) return
            inference.require(allowed)
            val s = session ?: return
            val w = work ?: return
            val said = text.trim()
            val body = listOf(prefix, said).filter { it.isNotEmpty() }.joinToString(" ")
            if (body.isEmpty()) return
            advance(submitAnswer(s, str(body), if (said.isNotEmpty()) "voice" else prefixSource, w.now()))
        }

        override suspend fun skip() {
            if (!running()) return
            inference.require(allowed)
            val s = session ?: return
            val w = work ?: return
            advance(skipQuestion(s, w.now()))
        }

        /** One persisted session per turn, through the same work as a typed answer. */
        private suspend fun advance(answered: Session) {
            val w = work ?: return
            session = answered
            prefix = ""
            prefixSource = "typed"
            _state.update { it.copy(caption = "") }
            val outcome = w.turn(answered, {
                inference.require(allowed)
                model
            }, resume = false, recordOffer = false).await()
            val (out, settled, _) = (outcome as? SessionWorker.Outcome.Committed)?.value ?: return
            session = settled
            if (my == epoch) _state.update { it.copy(question = questionOf(settled)) }
            // Nothing left worth asking: write it up, as the typed screen does.
            if (out.turn == null && !out.aborted && running()) writeUpNow()
        }

        override suspend fun wrap() {
            if (running()) writeUpNow()
        }

        private suspend fun writeUpNow() {
            val s = session ?: return
            val w = work ?: return
            _state.update { it.copy(writing = true) }
            val outcome = w.writeUp(s, {
                inference.require(allowed)
                model
            }, resume = false).await()
            (outcome as? SessionWorker.Outcome.Committed)?.value?.let { session = it.second }
        }

        override fun offerWrap(): String? = if (running()) session?.let(::shouldOfferWrap) else null

        // Reading it is also the moment it counts as offered, so a resumed run does not re-ask.
        override fun advisory(reason: String): String? {
            if (!running()) return null
            val s = session ?: return null
            val text = wrapAdvisory(s, reason)
            if (text != null) session = setWrapOffered(s, true, work?.now() ?: str(""))
            return text
        }

        override fun notify(message: String) {
            if (!running()) return
            _state.update { if (it.phase == DrivePhase.SPEAKING) it.copy(said = message) else it.copy(detail = message) }
        }

        override fun answeredCount(): Int =
            (session?.get("turns") as? JsonArray).orEmpty().count { truthy(it["answer"]) || truthy(it["skipped"]) }

        override fun hasDraft(): Boolean = prefix.isNotBlank()

        override fun onState(phase: DrivePhase) {
            if (!running() || phase == DrivePhase.STOPPED || phase == DrivePhase.PAUSED) return
            _state.update {
                it.copy(
                    phase = phase,
                    question = questionOf(session),
                    detail = if (phase == DrivePhase.RECOVERING) it.detail else "",
                )
            }
        }

        override suspend fun pause() = pauseRun(command = true, detail = null)

        override suspend fun exit() = exitRun(command = true)

        override suspend fun scratch() {
            if (!running()) return
            prefix = ""
            keepDraft("", captureTurnId)
        }
    }
}

private fun questionOf(session: Session?): String =
    session?.let(::openTurn)?.let { stringOf(it["question"]) }.orEmpty()
