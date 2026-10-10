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
class AndroidTtsEngine(context: Context) : TtsEngine {
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
        val pending = withContext(Dispatchers.Main) {
            synchronized(lock) {
                ready ?: CompletableDeferred<Boolean>().also { d ->
                    ready = d
                    tts = TextToSpeech(app) { status ->
                        val ok = status == TextToSpeech.SUCCESS
                        if (ok) tts?.run {
                            setOnUtteranceProgressListener(progress)
                            setAudioAttributes(SPEECH_ATTRIBUTES)
                        }
                        d.complete(ok)
                    }
                }
            }
        }
        val ok = pending.await()
        if (!ok) synchronized(lock) {
            if (ready === pending) {
                runCatching { tts?.shutdown() }
                tts = null
                ready = null
            }
        }
        return ok
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

    override fun speak(text: String, utteranceId: String): Boolean =
        engine()?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS

    override fun stop() { engine()?.stop() }

    override fun shutdown() {
        synchronized(lock) {
            runCatching { tts?.shutdown() }
            tts = null
            ready = null
        }
    }
}

/** AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: navigation and music duck under a question. */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        .setOnAudioFocusChangeListener { }
        .build()

    override fun request(): Boolean =
        audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() { audio?.abandonAudioFocusRequest(request) }
}
