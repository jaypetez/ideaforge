package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.DEFAULT_TRIGGER
import io.github.jaypetez.ideaforge.core.DRIVING_SETTLE_MS
import io.github.jaypetez.ideaforge.core.NativeTranscript
import io.github.jaypetez.ideaforge.core.SpeechParse
import io.github.jaypetez.ideaforge.core.TranscriptSnapshot
import io.github.jaypetez.ideaforge.core.nativeTriggerWaitMs
import io.github.jaypetez.ideaforge.core.parseSpeech
import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Native speech capture over android.speech.SpeechRecognizer: the Android twin of
 * listenViaWebSpeech in src/voice/webspeech.js, ported in spirit against what the #89 emulator
 * spike measured rather than what the web's Chrome-on-Android rules assume.
 *
 * The recognition SERVICE holds the microphone; this app never records audio itself, so
 * nothing here may open a second capture of its own (RecognizerAudioTest pins that). The
 * level meter comes from onRmsChanged and nothing else.
 *
 * What it keeps, each a failure that is silent when broken:
 *
 *  - Finals come from onPartialResults with final_result=true (where both recognisers
 *    actually delivered them), onResults and onSegmentResults. An empty onResults is "no new
 *    text", never a miss. Settledness is never judged by confidence: on-device reports 0.000.
 *  - The caption is settled text plus UNSTABLE_TEXT. See NativeTranscript in :core.
 *  - Sessions end about every 5 s of silence with ERROR_NO_MATCH. The capture restarts on the
 *    SAME recogniser, inside that callback (ready again in 45-150 ms). Destroying and
 *    recreating inside a callback fails at once with ERROR_SERVER_DISCONNECTED, so the one
 *    recreate (after a disconnect) happens later, from a coroutine.
 *  - The trigger is TERMINAL ONLY and must stand: a partial ending in it only arms a timer
 *    (settleMs, longer while only unstable words carry it); any partial that changes the
 *    caption re-judges it, so "we went over budget" extends itself and does not end the
 *    answer. A final ending in it ends the answer at once.
 *  - After a word, a pause never ends a hands-free answer: a session ending is a restart.
 *    Only the trigger, stop(), cancel(), the first-word bound or the watchdog end a capture.
 *  - firstWordMs bounds hands-free only, is armed once and is NOT reset by restarts (the
 *    engine is never silent: it restarts every ~5 s), and expiring resolves "" as a miss.
 *    DriveLoop needs that: a timeout escaping as cancellation would stand the loop down.
 *  - The watchdog (no callback at all for deafMs) RESOLVES with what was heard, never rejects.
 *  - On-device answers stopListening with ERROR_CLIENT and no final, discarding the
 *    utterance. That ERROR_CLIENT is not a failure, and the last partial is kept as the draft.
 *  - A whole session whose level never leaves the -2.0 floor is DEAF: the screen-off,
 *    no-foreground-service signature, which raises no error. Reported, not guessed around.
 *
 * Assumptions pending the phone half of #89 (everything above was measured on an API 36
 * emulator, with speech fed through EXTRA_AUDIO_SOURCE rather than a microphone):
 *
 *  - BUSY behaviour. ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS and a disconnect outside
 *    our own recreate never occurred (60+ restarts). Their handling (cancel, back off, retry
 *    on the same recogniser; recreate after a disconnect; a recoverable ListenFailure after
 *    [RecognizerConfig.maxRetries]) is untested against a real service.
 *  - Restarts across real speech. Joining words said either side of a restart, and how a
 *    restarted session replays the last phrase, could not be produced on the emulator. The
 *    replay rule (two or more words, or a whole restated result) is a guess at its shape.
 *  - Real-microphone partial timing. Mid-sentence "over" stood 130-700 ms before the next
 *    word extended it, and a terminal trigger's final came 300-500 ms after its last partial,
 *    on TTS audio. settleMs and NATIVE_UNSTABLE_SETTLE_FACTOR are first guesses against that.
 *  - The -2.0 dB floor as the deafness signal was seen on one emulator image only, and the
 *    on-device service's ERROR_CLIENT-on-stop in two of two trials.
 *
 * Threading: the platform recogniser must be driven from the main thread. [Recognizer] runs
 * its timers on [scope], whose dispatcher must be the thread the engine's callbacks arrive
 * on (Dispatchers.Main in the app, the test dispatcher in tests).
 */
interface RecognizerEngine {
    /** The callbacks of android.speech.RecognitionListener, already unpacked. */
    interface Listener {
        fun onReady()
        fun onBeginningOfSpeech()
        fun onRms(db: Float)
        fun onEndOfSpeech()
        /** RESULTS_RECOGNITION's best text, UNSTABLE_TEXT, and the final_result extra. */
        fun onPartial(text: String?, unstable: String?, final: Boolean)
        fun onResults(text: String?)
        fun onSegment(text: String?)
        fun onEndOfSegmentedSession()
        /** A SpeechRecognizer.ERROR_* code; see [RecognizerError]. */
        fun onError(code: Int)
    }

    fun setListener(listener: Listener)
    fun start(language: String)
    fun stop()
    fun cancel()
    fun destroy()
}

/** Builds a fresh platform recogniser; called lazily, and again only after a disconnect. */
fun interface RecognizerEngineFactory {
    fun create(): RecognizerEngine
}

/** SpeechRecognizer.ERROR_* values, so the logic and its tests need no Android class. */
object RecognizerError {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val RECOGNIZER_BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
}

/** The rmsdB a silenced capture reports, exactly, for a whole session. */
const val RMS_FLOOR_DB = -2.0f
private const val RMS_EPSILON = 0.05f

enum class CaptureMode {
    /** Ends on the trigger word; bounded by firstWordMs before the first word. */
    HANDS_FREE,
    /** Ends when the button is released; unlimited thinking time. */
    PRESS_TO_TALK,
}

data class ListenOptions(
    val mode: CaptureMode,
    val trigger: String = DEFAULT_TRIGGER,
    val settleMs: Long = DRIVING_SETTLE_MS.toLong(),
    /** Hands-free only; 0 disables it. Ignored for press-to-talk. */
    val firstWordMs: Long = 0,
    val language: String = "en-US",
)

enum class CaptureState { IDLE, LISTENING, HEARING, DEAF, FINISHING }

enum class CaptureEnd {
    /** The trigger word ended a hands-free answer. */
    TRIGGER,
    /** stop(): press-to-talk released, or Pause keeping the draft. */
    STOPPED,
    /** Hands-free: no word before firstWordMs. Text is "". */
    MISSED,
    /** As MISSED, but every session in it was deaf (level pinned at the floor). Text is "". */
    DEAF,
    /** The engine stopped calling back at all; what was heard is kept. */
    WATCHDOG,
    /** The engine failed after words were heard; they are kept rather than thrown away. */
    INTERRUPTED,
    /** cancel() or coroutine cancellation. */
    CANCELLED,
}

/** [text] is RAW: trigger included. Stripping is parseSpeech's job, one definition. */
data class CaptureResult(val text: String, val end: CaptureEnd, val deafSessions: Int = 0) {
    fun speech(trigger: String = DEFAULT_TRIGGER): SpeechParse = parseSpeech(text, trigger)
}

data class RecognizerConfig(
    /** No callback of any kind for this long: resolve with what was heard. */
    val deafMs: Long = 6000,
    /** After our own stopListening, how long to wait for the final before keeping the partial. */
    val finishMs: Long = 800,
    /** Recoverable errors (busy, disconnected…) in a row before the capture gives up. */
    val maxRetries: Int = 3,
    val retryBackoffMs: Long = 300,
)

class Recognizer(
    private val factory: RecognizerEngineFactory,
    private val scope: CoroutineScope,
    private val config: RecognizerConfig = RecognizerConfig(),
) {
    private val _level = MutableStateFlow(RMS_FLOOR_DB)
    private val _state = MutableStateFlow(CaptureState.IDLE)
    private val _caption = MutableStateFlow("")

    /** onRmsChanged, as reported. The only meter there is. */
    val level: StateFlow<Float> = _level.asStateFlow()
    val state: StateFlow<CaptureState> = _state.asStateFlow()
    /** Settled text plus unstable words, for display. */
    val caption: StateFlow<String> = _caption.asStateFlow()

    private val lock = Any()
    private var engine: RecognizerEngine? = null
    private var active: Capture? = null

    private val dispatcher: CoroutineContext =
        scope.coroutineContext[ContinuationInterceptor] ?: error("scope needs a dispatcher")

    private fun engine(): RecognizerEngine = engine ?: factory.create().also {
        it.setListener(Relay())
        engine = it
    }

    /** Never from inside an engine callback: that is what ERROR_SERVER_DISCONNECTED is. */
    private fun recreate(): RecognizerEngine {
        engine?.let { runCatching { it.destroy() } }
        engine = null
        return engine()
    }

    /** Callbacks go to whichever capture is active; one arriving after it settled is dropped. */
    private inner class Relay : RecognizerEngine.Listener {
        private fun to(block: Capture.() -> Unit) {
            synchronized(lock) { active?.takeUnless { it.done }?.block() }
        }
        override fun onReady() = to { ready() }
        override fun onBeginningOfSpeech() = to { alive() }
        override fun onRms(db: Float) = to { rms(db) }
        override fun onEndOfSpeech() = to { alive() }
        override fun onPartial(text: String?, unstable: String?, final: Boolean) = to { partial(text, unstable, final) }
        override fun onResults(text: String?) = to { results(text) }
        override fun onSegment(text: String?) = to { segment(text) }
        override fun onEndOfSegmentedSession() = to { sessionEnded() }
        override fun onError(code: Int) = to { error(code) }
    }

    /**
     * Start a capture. Call on the engine's thread. Any capture still running is cancelled
     * first: there is one microphone and one recogniser.
     */
    fun start(options: ListenOptions): Capture = synchronized(lock) {
        active?.cancel()
        Capture(options).also {
            active = it
            it.begin()
        }
    }

    /** Capture to the end. Coroutine cancellation cancels the capture, immediately. */
    suspend fun listen(options: ListenOptions): CaptureResult {
        val capture = withContext(dispatcher) { start(options) }
        try {
            return capture.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable + dispatcher) { capture.cancel() }
            throw e
        }
    }

    /**
     * DriveLoop's DriveIo.listen contract, for #92 to wire: the RAW transcript, trigger
     * included; "" for a miss or a deaf capture (the recovery ladder's business); a
     * ListenFailure for a failure; an AbortError-named ListenFailure for cancel(), which
     * DriveLoop treats as Pause/Exit and never recovers.
     */
    suspend fun listenForDrive(
        trigger: String = DEFAULT_TRIGGER,
        settleMs: Long = DRIVING_SETTLE_MS.toLong(),
        firstWordMs: Long,
    ): String {
        val result = listen(ListenOptions(CaptureMode.HANDS_FREE, trigger, settleMs, firstWordMs))
        return when (result.end) {
            CaptureEnd.MISSED, CaptureEnd.DEAF -> ""
            CaptureEnd.CANCELLED -> throw ListenFailure("Listening was cancelled.", name = "AbortError")
            else -> result.text
        }
    }

    /** Release the platform recogniser. A later start builds a fresh one. */
    fun release() = synchronized(lock) {
        active?.cancel()
        active = null
        engine?.let { runCatching { it.destroy() } }
        engine = null
        _state.value = CaptureState.IDLE
    }

    inner class Capture internal constructor(private val options: ListenOptions) {
        private val result = CompletableDeferred<CaptureResult>()
        private val transcript = NativeTranscript()
        private val handsFree = options.mode == CaptureMode.HANDS_FREE

        internal var done = false
            private set
        private var stopping = false
        private var endReason = CaptureEnd.STOPPED
        private var heardWord = false
        private var retries = 0
        private var deafSessions = 0

        // This recogniser session's level, for the deafness verdict.
        private var sessionLevels = 0
        private var sessionLoud = false
        private var sessionWords = false

        private var armedText: String? = null
        private var settleJob: Job? = null
        private var firstWordJob: Job? = null
        private var watchdogJob: Job? = null
        private var finishJob: Job? = null
        private var retryJob: Job? = null

        suspend fun await(): CaptureResult = result.await()

        val isActive: Boolean get() = !done

        internal fun begin() {
            _caption.value = ""
            _state.value = CaptureState.LISTENING
            startSession()
            if (handsFree && options.firstWordMs > 0) {
                firstWordJob = later(options.firstWordMs) {
                    if (!heardWord) {
                        resolve(if (deafSessions > 0) CaptureEnd.DEAF else CaptureEnd.MISSED, keepText = false)
                    }
                }
            }
        }

        /** Press-to-talk release, or Pause: end now and keep what was said, final or not. */
        fun stop() = synchronized(lock) {
            if (!done) finish(CaptureEnd.STOPPED)
        }

        /** Stop at once. Resolves CANCELLED, with whatever the caption held. */
        fun cancel() = synchronized(lock) {
            if (!done) resolve(CaptureEnd.CANCELLED, keepText = true)
        }

        private fun later(ms: Long, block: () -> Unit): Job = scope.launch(dispatcher) {
            delay(ms)
            synchronized(lock) { if (!done) block() }
        }

        private fun startSession() {
            sessionLevels = 0
            sessionLoud = false
            sessionWords = false
            alive()
            try {
                engine().start(options.language)
            } catch (e: RuntimeException) {
                fail(ListenFailure("Could not start dictation: ${e.message}", name = "StartError", recoverable = true))
            }
        }

        /** Any callback is proof of life; the watchdog counts from the latest. */
        internal fun alive() {
            watchdogJob?.cancel()
            watchdogJob = later(config.deafMs) { resolve(CaptureEnd.WATCHDOG, keepText = true) }
        }

        internal fun ready() {
            alive()
            retries = 0
        }

        internal fun rms(db: Float) {
            alive()
            _level.value = db
            sessionLevels++
            if (db > RMS_FLOOR_DB + RMS_EPSILON) {
                sessionLoud = true
                if (_state.value == CaptureState.DEAF) _state.value = if (heardWord) CaptureState.HEARING else CaptureState.LISTENING
            }
        }

        internal fun partial(text: String?, unstable: String?, final: Boolean) {
            alive()
            if (!text.isNullOrBlank() || !unstable.isNullOrBlank()) sessionWords = true
            if (final) transcript.final(text) else transcript.partial(text, unstable)
            changed()
            if (stopping && final) resolve(endReason, keepText = true)
        }

        internal fun segment(text: String?) {
            alive()
            if (!text.isNullOrBlank()) sessionWords = true
            transcript.final(text)
            changed()
        }

        internal fun results(text: String?) {
            alive()
            if (!text.isNullOrBlank()) sessionWords = true
            transcript.final(text)          // empty: no new text, not a miss
            sessionEnded()
        }

        internal fun error(code: Int) {
            alive()
            if (stopping) {
                // Our own stopListening: on-device answers ERROR_CLIENT and discards the
                // utterance, default may answer NO_MATCH. Either way keep the last partial.
                sessionEnded()
                return
            }
            when (code) {
                RecognizerError.NO_MATCH, RecognizerError.SPEECH_TIMEOUT -> sessionEnded()
                RecognizerError.INSUFFICIENT_PERMISSIONS -> fail(
                    ListenFailure("Microphone access was denied.", name = "NotAllowedError", fatal = true),
                )
                RecognizerError.AUDIO -> fail(
                    ListenFailure("The microphone is unavailable.", name = "AudioCaptureError"),
                )
                RecognizerError.LANGUAGE_NOT_SUPPORTED, RecognizerError.LANGUAGE_UNAVAILABLE -> fail(
                    ListenFailure("Speech recognition does not support ${options.language}.", name = "LanguageUnavailable", recoverable = false),
                )
                else -> recover(code)
            }
        }

        /** A session ended: deaf or not, then restart on the same recogniser, here and now. */
        internal fun sessionEnded() {
            if (!stopping && sessionLevels > 0 && !sessionLoud && !sessionWords) {
                deafSessions++
                _state.value = CaptureState.DEAF
            }
            transcript.endSession()
            changed()
            if (done) return
            if (stopping) {
                resolve(endReason, keepText = true)
                return
            }
            startSession()
        }

        private fun changed() {
            val snap = transcript.snapshot()
            _caption.value = snap.text
            if (snap.hasWords) {
                if (!heardWord) {
                    heardWord = true
                    firstWordJob?.cancel()
                }
                if (_state.value == CaptureState.LISTENING) _state.value = CaptureState.HEARING
            }
            if (handsFree && !stopping && !done) judge(snap)
        }

        private fun judge(snap: TranscriptSnapshot) {
            val wait = nativeTriggerWaitMs(snap, options.trigger, options.settleMs)
            when {
                wait == null -> { settleJob?.cancel(); armedText = null }
                wait == 0L -> finish(CaptureEnd.TRIGGER)
                // A repeat of the same caption does not extend it; only a change re-arms.
                snap.text != armedText -> {
                    settleJob?.cancel()
                    armedText = snap.text
                    settleJob = later(wait) { finish(CaptureEnd.TRIGGER) }
                }
            }
        }

        /** Ask the engine for its final, and keep the partial if it never sends one. */
        private fun finish(end: CaptureEnd) {
            if (stopping) return
            stopping = true
            endReason = end
            settleJob?.cancel()
            _state.value = CaptureState.FINISHING
            if (retryJob?.isActive == true) {
                // Between sessions (backing off): nothing to stop.
                resolve(end, keepText = true)
                return
            }
            runCatching { engine?.stop() }
            finishJob = later(config.finishMs) { resolve(end, keepText = true) }
        }

        /** Busy, disconnected, a network blip: back off and try again, a bounded number of times. */
        private fun recover(code: Int) {
            runCatching { engine?.cancel() }
            transcript.endSession()
            retries++
            if (retries > config.maxRetries) {
                fail(ListenFailure("Speech recognition is unavailable (error $code).", name = "RecognizerBusy", recoverable = true))
                return
            }
            watchdogJob?.cancel()
            retryJob = later(config.retryBackoffMs * retries) {
                if (code == RecognizerError.SERVER_DISCONNECTED) recreate()
                startSession()
            }
        }

        private fun fail(error: ListenFailure) {
            // A failure after words were heard keeps them rather than throwing an answer away.
            if (transcript.snapshot().hasWords) {
                resolve(CaptureEnd.INTERRUPTED, keepText = true)
                return
            }
            settleAll()
            result.completeExceptionally(error)
        }

        private fun resolve(end: CaptureEnd, keepText: Boolean) {
            if (done) return
            transcript.endSession()
            val text = if (keepText) transcript.snapshot().text else ""
            settleAll()
            result.complete(CaptureResult(text, end, deafSessions))
        }

        private fun settleAll() {
            done = true
            for (job in listOf(settleJob, firstWordJob, watchdogJob, finishJob, retryJob)) job?.cancel()
            runCatching { engine?.cancel() }
            _state.value = CaptureState.IDLE
            if (active === this) active = null
        }
    }
}
