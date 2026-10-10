package io.github.jaypetez.ideaforge.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** Speech, for an assistant: what both the engine and the focus request declare. */
internal val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * [TtsEngine] on the platform TextToSpeech. Created lazily on the first speak, so an app
 * that never talks never binds the engine. A failed initialisation is reported (awaitReady
 * returns false) and dropped, so the next speak tries a fresh engine rather than failing
 * for the life of the process.
 */
class AndroidTtsEngine(
    context: Context,
    /** Below the Speaker's own bound, so a hung init is cleaned up here first. */
    private val initTimeoutMs: Long = 4000L,
    private val main: CoroutineContext = Dispatchers.Main,
    private val create: (Context, TextToSpeech.OnInitListener) -> TextToSpeech = ::TextToSpeech,
) : TtsEngine {
    private val app = context.applicationContext
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    @Volatile private var listener: TtsEngine.Listener? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) { listener?.onStart(utteranceId) }
        override fun onDone(utteranceId: String) { listener?.onDone(utteranceId) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) { listener?.onError(utteranceId, TextToSpeech.ERROR) }
        override fun onError(utteranceId: String, errorCode: Int) { listener?.onError(utteranceId, errorCode) }
        override fun onStop(utteranceId: String, interrupted: Boolean) { listener?.onStop(utteranceId) }
    }

    override fun setListener(listener: TtsEngine.Listener) { this.listener = listener }

    override suspend fun awaitReady(): Boolean {
        val pending = withContext(main) {
            synchronized(lock) {
                ready ?: CompletableDeferred<Boolean>().also { d ->
                    ready = d
                    // The callback only reports: it may belong to an engine already dropped,
                    // or fire before `create` has returned.
                    tts = create(app) { status -> d.complete(status == TextToSpeech.SUCCESS) }
                }
            }
        }
        // Bounded here, with the cleanup in `finally`: a caller's timeout cancels this await,
        // and a cleanup after it would never run, leaving `ready` pending for ever and the
        // half-built TextToSpeech leaked. A failed, hung or abandoned init is dropped, so the
        // next speak builds a fresh engine.
        var ok = false
        try {
            ok = withTimeoutOrNull(initTimeoutMs) { pending.await() } ?: false
            if (ok) {
                val current = synchronized(lock) { tts.takeIf { ready === pending } }
                if (current == null) {
                    ok = false
                } else {
                    current.setOnUtteranceProgressListener(progress)
                    current.setAudioAttributes(SPEECH_ATTRIBUTES)
                }
            }
        } finally {
            if (!ok) drop(pending)
        }
        return ok
    }

    private fun drop(only: CompletableDeferred<Boolean>? = null) {
        synchronized(lock) {
            if (only != null && ready !== only) return
            runCatching { tts?.shutdown() }
            tts = null
            ready = null
        }
    }

    private fun engine(): TextToSpeech? = synchronized(lock) { tts.takeIf { ready?.isCompleted == true } }

    override fun voices(): List<String> =
        engine()?.voices?.mapNotNull { it?.name }.orEmpty()

    override fun setVoice(id: String): Boolean {
        val e = engine() ?: return false
        val v = e.voices?.firstOrNull { it?.name == id } ?: return false
        return e.setVoice(v) == TextToSpeech.SUCCESS
    }

    override fun useDefaultVoice() {
        val e = engine() ?: return
        e.defaultVoice?.let { e.setVoice(it) }
    }

    override fun setRate(rate: Float) { engine()?.setSpeechRate(rate) }

    /** A refusal drops the engine: a dead service (updated or killed) never recovers. */
    override fun speak(text: String, utteranceId: String): Boolean {
        val ok = runCatching {
            engine()?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!ok) drop()
        return ok
    }

    override fun stop() { runCatching { engine()?.stop() } }

    override fun reset() = drop()

    override fun shutdown() = drop()
}

/** AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: navigation and music duck under a question. */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    @Volatile private var onLoss: (() -> Unit)? = null
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        // A ringing call or a navigation prompt takes focus: stop rather than talk over it.
        // LOSS_TRANSIENT_CAN_DUCK is left to the system, which ducks us.
        .setOnAudioFocusChangeListener { change -> focusChanged(change) }
        .build()

    internal fun focusChanged(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onLoss?.invoke()
        }
    }

    override fun request(onLoss: () -> Unit): Boolean {
        this.onLoss = onLoss
        val granted = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) this.onLoss = null
        return granted
    }

    override fun abandon() {
        onLoss = null
        audio?.abandonAudioFocusRequest(request)
    }
}
