package io.github.jaypetez.ideaforge.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Which platform recogniser to build. */
enum class RecognizerMode {
    /** On-device when the platform offers it (API 31+), otherwise the default service. */
    AUTO,
    /** createOnDeviceSpeechRecognizer: finals at confidence 0, no UNSTABLE_TEXT, a slower cold start. */
    ON_DEVICE,
    /** createSpeechRecognizer: the default service, which flushes a final on stop. */
    DEFAULT,
}

/** The partial-results extras the emulator spike saw; neither has a public constant. */
private const val EXTRA_FINAL_RESULT = "final_result"
private const val EXTRA_UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"

/** Picks the recogniser for [mode]. Call on the main thread. */
fun androidRecognizerFactory(context: Context, mode: RecognizerMode = RecognizerMode.AUTO): RecognizerEngineFactory {
    val app = context.applicationContext
    return RecognizerEngineFactory {
        val onDevice = mode != RecognizerMode.DEFAULT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        AndroidRecognizerEngine(app, onDevice)
    }
}

/**
 * [RecognizerEngine] on android.speech.SpeechRecognizer. Main thread only, as the platform
 * class is. The microphone is held by the recognition service, never by this app.
 */
class AndroidRecognizerEngine(context: Context, onDevice: Boolean) : RecognizerEngine {
    private val app = context.applicationContext
    private val recognizer: SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        } else {
            SpeechRecognizer.createSpeechRecognizer(app)
        }

    override fun setListener(listener: RecognizerEngine.Listener) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) = listener.onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onEndOfSpeech()
            override fun onError(error: Int) = listener.onError(error)
            override fun onResults(results: Bundle?) = listener.onResults(best(results))
            override fun onPartialResults(partialResults: Bundle?) = listener.onPartial(
                best(partialResults),
                unstable(partialResults),
                partialResults?.getBoolean(EXTRA_FINAL_RESULT, false) == true,
            )
            override fun onSegmentResults(segmentResults: Bundle) = listener.onSegment(best(segmentResults))
            override fun onEndOfSegmentedSession() = listener.onEndOfSegmentedSession()
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    override fun start(language: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        recognizer.startListening(intent)
    }

    override fun stop() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun unstable(bundle: Bundle?): String? =
        bundle?.getStringArrayList(EXTRA_UNSTABLE_TEXT)?.firstOrNull()
}
