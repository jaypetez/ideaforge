package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import io.github.jaypetez.ideaforge.data.InferenceConsent
import io.github.jaypetez.ideaforge.data.InferencePermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DictationPhase { PREPARING, PERMISSION, LISTENING, STOPPING, TRANSCRIBING }

data class ManualCaptureState(val phase: DictationPhase = DictationPhase.PREPARING, val caption: String = "")

/**
 * One foreground, tap-stopped capture, never a drive or a session writer. The graph shares
 * this owner with the drive so a notification Resume can retire manual input synchronously.
 * Each handle owns only its resources: an old screen's disposal cannot stop a newer capture.
 */
class ManualDictation(
    private val main: CoroutineScope,
    private val recognizer: () -> Recognizer,
    private val inference: InferenceConsent,
    private val beforeCapture: () -> Unit = {},
    private val recognitionAvailable: () -> Boolean = { true },
    private val whisper: suspend () -> WhisperListener? = { null },
) {
    private var active: Capture? = null
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    /** The visible manual tap retires older work before its authorization can suspend. */
    fun reserve(): Long {
        cancel()
        val intent = _revision.value
        beforeCapture()
        return intent
    }

    fun current(intent: Long): Boolean = intent == _revision.value

    fun start(permit: InferencePermit, intent: Long = reserve()): Capture {
        if (!current(intent)) throw CancellationException("Dictation intent was retired")
        active?.cancel()
        return Capture(permit, intent).also { active = it; it.begin() }
    }

    fun cancel() {
        _revision.value++
        active?.cancel()
    }

    fun cancel(intent: Long) { if (current(intent)) cancel() }

    inner class Capture internal constructor(private val permit: InferencePermit, private val intent: Long) {
        private val _state = MutableStateFlow(ManualCaptureState())
        val state = _state.asStateFlow()
        private var job: Deferred<CaptureResult>? = null
        private var watch: Job? = null
        private var ownedRecognizer: Recognizer? = null
        private var native: Recognizer.Capture? = null
        private var recording: WhisperListener.ManualCapture? = null
        private var stopped = false

        internal fun begin() {
            job = main.async {
                try {
                    inference.require(permit)
                    ensureActive()
                    if (active !== this@Capture || stopped || !current(intent)) throw CancellationException("Dictation cancelled")
                    val captured = if (recognitionAvailable()) {
                        try {
                            val rec = recognizer().also { ownedRecognizer = it }
                            native = rec.start(ListenOptions(CaptureMode.PRESS_TO_TALK))
                            _state.value = ManualCaptureState(DictationPhase.LISTENING)
                            watch = launch {
                                rec.caption.collect { text -> _state.value = _state.value.copy(caption = text) }
                            }
                            native!!.await()
                        } catch (failure: ListenFailure) {
                            if (failure.name != "LanguageUnavailable") throw failure
                            releaseNative()
                            transcribed()
                        }
                    } else transcribed()
                    ensureActive()
                    inference.require(permit)
                    captured
                } finally {
                    releaseNative()
                    recording?.cancel()
                    recording = null
                    if (active === this@Capture) active = null
                }
            }
        }

        private suspend fun transcribed(): CaptureResult {
            inference.require(permit)
            val listener = whisper() ?: throw ListenFailure(
                "Speech recognition is unavailable. Transcription needs its own key and audio-transfer agreement in Settings.",
                name = "RecognizerUnavailable", recoverable = false,
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val capture = listener.manualCapture().also { recording = it }
            if (stopped) capture.stop()
            _state.value = ManualCaptureState(DictationPhase.LISTENING)
            val text = capture.await(
                authorize = { inference.require(permit) },
                onTranscribing = { if (it) _state.value = _state.value.copy(phase = DictationPhase.TRANSCRIBING) },
            )
            return CaptureResult(text, CaptureEnd.STOPPED)
        }

        suspend fun await(): CaptureResult = checkNotNull(job).await()

        fun stop() {
            if (job?.isCompleted != false || stopped) return
            stopped = true
            _state.value = _state.value.copy(phase = DictationPhase.STOPPING)
            native?.stop()
            recording?.stop()
            if (native == null && recording == null) cancel()
        }

        fun cancel() {
            stopped = true
            job?.cancel()
            releaseNative()
            recording?.cancel()
            recording = null
            if (active === this) active = null
        }

        private fun releaseNative() {
            watch?.cancel()
            watch = null
            native?.cancel()
            native = null
            val rec = ownedRecognizer
            ownedRecognizer = null
            rec?.release()
        }
    }
}
