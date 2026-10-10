package io.github.jaypetez.ideaforge.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One RecognitionListener callback, as the #89 emulator logs name them. */
sealed interface Ev {
    data object Ready : Ev
    data object Begin : Ev
    data object End : Ev
    data class Rms(val db: Float) : Ev
    data class Partial(val text: String?, val unstable: String? = null, val final: Boolean = false) : Ev
    data class Results(val text: String?) : Ev
    data class Segment(val text: String?) : Ev
    data object EndSegmented : Ev
    data class Error(val code: Int) : Ev
}

/** [ev] fires [ms] after the startListening (or stopListening) that scheduled it. */
data class At(val ms: Long, val ev: Ev)

/** A session of silence on the emulator microphone: ready, low noise, NO_MATCH at ~5 s. */
fun silence(db: Float = -1.5f): List<At> =
    listOf(At(670, Ev.Ready)) + (1..4).map { At(670L + it * 1000, Ev.Rms(db)) } + At(5045, Ev.Error(RecognizerError.NO_MATCH))

/** The screen-off signature: two level callbacks pinned at -2.0, then an ordinary NO_MATCH. */
fun deafSession(): List<At> =
    listOf(At(500, Ev.Ready), At(1000, Ev.Rms(-2.0f)), At(5000, Ev.Rms(-2.0f)), At(5010, Ev.Error(RecognizerError.NO_MATCH)))

/**
 * Replays scripted callback timelines on virtual time. Each startListening takes the next
 * script (then [after] for every later one). stopListening answers as the recogniser in use
 * does: default flushes a final carrying the last partial ~300 ms later; on-device answers
 * ERROR_CLIENT ~90 ms later and discards the words.
 */
class FakeRecognizerEngine(
    private val scope: CoroutineScope,
    private val scripts: ArrayDeque<List<At>>,
    var onDevice: Boolean = false,
    var after: () -> List<At> = { silence() },
    /** Overrides the stop response when set. */
    var onStop: (() -> List<At>)? = null,
) : RecognizerEngine {
    private lateinit var listener: RecognizerEngine.Listener
    private var job: Job? = null
    private var lastPartial: String? = null
    private var inCallback = false

    var starts = 0
    var startsInsideCallback = 0
    var stops = 0
    var cancels = 0
    var destroyed = false
    var destroyedInsideCallback = false

    override fun setListener(listener: RecognizerEngine.Listener) { this.listener = listener }

    override fun start(language: String) {
        check(!destroyed) { "startListening on a destroyed recogniser" }
        starts++
        if (inCallback) startsInsideCallback++
        lastPartial = null              // a new session has heard nothing yet
        play(scripts.removeFirstOrNull() ?: after())
    }

    override fun stop() {
        stops++
        val heard = lastPartial
        play(onStop?.invoke() ?: when {
            onDevice -> listOf(At(90, Ev.Error(RecognizerError.CLIENT)))
            heard.isNullOrBlank() -> listOf(At(130, Ev.Error(RecognizerError.NO_MATCH)))
            else -> listOf(At(310, Ev.Partial(heard, final = true)))
        })
    }

    override fun cancel() {
        cancels++
        job?.cancel()
    }

    override fun destroy() {
        destroyed = true
        if (inCallback) destroyedInsideCallback = true
        job?.cancel()
    }

    private fun play(script: List<At>) {
        job?.cancel()
        job = scope.launch {
            var t = 0L
            for (at in script) {
                delay(at.ms - t)
                t = at.ms
                fire(at.ev)
            }
        }
    }

    private fun fire(ev: Ev) {
        inCallback = true
        try {
            when (ev) {
                Ev.Ready -> listener.onReady()
                Ev.Begin -> listener.onBeginningOfSpeech()
                Ev.End -> listener.onEndOfSpeech()
                is Ev.Rms -> listener.onRms(ev.db)
                is Ev.Partial -> {
                    lastPartial = listOfNotNull(ev.text, ev.unstable).joinToString("")
                    listener.onPartial(ev.text, ev.unstable, ev.final)
                }
                is Ev.Results -> listener.onResults(ev.text)
                is Ev.Segment -> listener.onSegment(ev.text)
                Ev.EndSegmented -> listener.onEndOfSegmentedSession()
                is Ev.Error -> listener.onError(ev.code)
            }
        } finally {
            inCallback = false
        }
    }
}

/** Builds [FakeRecognizerEngine]s; a disconnect's recreate gets a fresh one. */
class FakeFactory(private val make: () -> FakeRecognizerEngine) : RecognizerEngineFactory {
    val engines = mutableListOf<FakeRecognizerEngine>()
    override fun create(): RecognizerEngine = make().also { engines += it }
}
