package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.SpeechConfigException
import io.github.jaypetez.ideaforge.core.speechChunksForRate
import io.github.jaypetez.ideaforge.core.speechTimeoutMs
import io.github.jaypetez.ideaforge.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Native speech output: the Android twin of src/voice/speak.js.
//
// The rules it keeps, each a failure that is silent when broken:
//  - Never one whole prompt in one utterance. Text is spoken in the chunks the web speaks
//    (speechChunksForRate, replayed against the `speak` golden vectors), and every chunk has a
//    length-scaled timeout (speechTimeoutMs). An engine that never calls onDone resolves
//    through that timeout as a reported failure; nothing here waits for ever.
//  - A missing selected voice is reported on the result, never silently swapped.
//  - Cancellation is not an error: cancel() stops the engine and the speak resolves CANCELLED.
//  - The question and its suggestions are two utterances (speakQuestion).

enum class SpeakStatus { SPOKEN, CANCELLED, FAILED }

enum class SpeakFailure {
    /** A chunk outlasted its length-scaled timeout; playback was stopped. */
    TIMEOUT,
    /** The TextToSpeech engine did not initialise, or took too long to. */
    ENGINE_INIT,
    /** The engine reported an error for a chunk. */
    ENGINE_ERROR,
    /** The engine refused to queue a chunk. */
    SPEAK_REJECTED,
    /** Audio focus was refused (a call is in progress, for instance), so nothing was said. */
    FOCUS_DENIED,
    /** Empty text, or a rate outside the validated range. */
    CONFIG,
}

/**
 * What happened, observably. [voice] is the voice actually used ("" is the engine default);
 * [voiceMissing] says the selected [requestedVoice] is not installed, so the default spoke
 * instead. That is the web's `voiceFallback`, reported, never silent.
 */
data class SpeakResult(
    val status: SpeakStatus,
    val failure: SpeakFailure? = null,
    val message: String? = null,
    val requestedVoice: String = "",
    val voice: String = "",
    val voiceMissing: Boolean = false,
    val rate: Double = 1.0,
    /** Chunks the engine finished. */
    val chunksSpoken: Int = 0,
    val chunks: Int = 0,
)

/** The question, then its suggestions as a second utterance (null when there were none). */
data class QuestionSpeech(val question: SpeakResult, val examples: SpeakResult?)

interface Speaker {
    /** Speak [text] with the current voice and pace from prefs. Never throws, except to cancel. */
    suspend fun speak(text: String): SpeakResult

    /**
     * Two speak calls, never one joined string: four long suggestions plus a question clears
     * the engine's watchdog on their own (see spokenExamples in src/core/driving.js). The
     * suggestions are said only once the question was. [examples] is the sentence already
     * built by spokenExamples; that function is the hands-free port's (#77), and callers pass
     * its output here until it lands in :core.
     */
    suspend fun speakQuestion(question: String, examples: String): QuestionSpeech {
        val asked = speak(question)
        if (asked.status != SpeakStatus.SPOKEN || examples.isBlank()) return QuestionSpeech(asked, null)
        return QuestionSpeech(asked, speak(examples))
    }

    /** Stop speaking now. The speak in progress resolves CANCELLED. */
    fun cancel()

    fun shutdown()
}

/** The seam between [TextToSpeechSpeaker] and android.speech.tts.TextToSpeech. */
interface TtsEngine {
    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int)
        /** The engine stopped [utteranceId] before it finished (after [stop]). */
        fun onStop(utteranceId: String)
    }

    fun setListener(listener: Listener)

    /** True once the engine initialised; false if it failed. May suspend until it decides. */
    suspend fun awaitReady(): Boolean

    /** Installed voice identifiers (android.speech.tts.Voice.getName). */
    fun voices(): List<String>

    fun setVoice(id: String): Boolean

    fun useDefaultVoice()

    fun setRate(rate: Float)

    /** Queue [text]; false when the engine refused it. */
    fun speak(text: String, utteranceId: String): Boolean

    fun stop()

    fun shutdown()
}

/** Transient, duckable focus for the length of one speak. */
interface AudioFocus {
    fun request(): Boolean
    fun abandon()
}

private sealed interface ChunkEnd {
    data object Done : ChunkEnd
    data object Cancelled : ChunkEnd
    data class Error(val code: Int) : ChunkEnd
}

class TextToSpeechSpeaker(
    private val engine: TtsEngine,
    private val focus: AudioFocus,
    private val prefs: suspend () -> Prefs,
    private val initTimeoutMs: Long = INIT_TIMEOUT_MS,
) : Speaker {
    companion object {
        const val INIT_TIMEOUT_MS = 5000L
    }

    private class Op {
        val stopped = CompletableDeferred<Unit>()
        @Volatile var utteranceId: String? = null
        @Volatile var chunk: CompletableDeferred<ChunkEnd>? = null
    }

    private val active = AtomicReference<Op?>(null)
    private val ids = AtomicLong()

    init {
        engine.setListener(object : TtsEngine.Listener {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = end(utteranceId, ChunkEnd.Done)
            override fun onError(utteranceId: String, errorCode: Int) = end(utteranceId, ChunkEnd.Error(errorCode))
            override fun onStop(utteranceId: String) = end(utteranceId, ChunkEnd.Cancelled)
        })
    }

    /** Callbacks arrive on the engine's thread; one for an older utterance is ignored. */
    private fun end(utteranceId: String, how: ChunkEnd) {
        val op = active.get() ?: return
        if (op.utteranceId == utteranceId) op.chunk?.complete(how)
    }

    override suspend fun speak(text: String): SpeakResult {
        cancel() // as speak.js: a new utterance replaces the one in progress
        val op = Op()
        active.set(op)
        val p = prefs()
        var result = SpeakResult(SpeakStatus.FAILED, requestedVoice = p.speechVoice, rate = p.speechRate)
        var focused = false
        try {
            if (text.isBlank()) return fail(result, SpeakFailure.CONFIG, "There is no text to speak.")
            val chunks = try {
                speechChunksForRate(text, p.speechRate)
            } catch (e: SpeechConfigException) {
                return fail(result, SpeakFailure.CONFIG, e.message)
            }
            result = result.copy(chunks = chunks.size)

            // Raced against cancel(), and bounded: an engine that never answers its init is a
            // reported failure, not a speak that hangs.
            val ready: Boolean? = coroutineScope {
                val init = async { runCatching { engine.awaitReady() }.getOrDefault(false) }
                val first = withTimeoutOrNull(initTimeoutMs) {
                    select<Boolean?> {
                        init.onAwait { it }
                        op.stopped.onAwait { null }
                    }
                }
                init.cancel()
                if (op.stopped.isCompleted) null else first ?: false
            }
            if (ready == null) return cancelled(result)
            if (!ready) return fail(result, SpeakFailure.ENGINE_INIT, "The speech engine did not start.")

            result = applyVoice(result, p.speechVoice)
            engine.setRate(p.speechRate.toFloat())
            if (op.stopped.isCompleted) return cancelled(result)

            focused = focus.request()
            if (!focused) return fail(result, SpeakFailure.FOCUS_DENIED, "Another app holds the audio; nothing was said.")

            for ((i, chunk) in chunks.withIndex()) {
                val id = "ideaforge-${ids.incrementAndGet()}"
                val end = CompletableDeferred<ChunkEnd>()
                op.utteranceId = id
                op.chunk = end
                if (op.stopped.isCompleted) return cancelled(result)
                if (!engine.speak(chunk, id)) {
                    return fail(result, SpeakFailure.SPEAK_REJECTED, "The speech engine refused the text.")
                }
                // null is the length-scaled timeout, as speak.js: a chunk the engine never
                // finishes is stopped and reported rather than awaited for ever.
                val how = withTimeoutOrNull(speechTimeoutMs(chunk, p.speechRate)) {
                    select<ChunkEnd> {
                        end.onAwait { it }
                        op.stopped.onAwait { ChunkEnd.Cancelled }
                    }
                }
                when (how) {
                    ChunkEnd.Done -> result = result.copy(chunksSpoken = i + 1)
                    ChunkEnd.Cancelled -> return cancelled(result)
                    is ChunkEnd.Error -> {
                        engine.stop()
                        return fail(result, SpeakFailure.ENGINE_ERROR, "Speech failed (engine error ${how.code}).")
                    }
                    null -> {
                        engine.stop()
                        return fail(result, SpeakFailure.TIMEOUT, "Speech did not finish; playback was stopped.")
                    }
                }
            }
            return result.copy(status = SpeakStatus.SPOKEN)
        } finally {
            // Coroutine cancellation lands here too: the engine must not go on talking.
            if (!op.stopped.isCompleted && result.status != SpeakStatus.SPOKEN && active.get() === op) {
                runCatching { engine.stop() }
            }
            if (focused) focus.abandon()
            active.compareAndSet(op, null)
        }
    }

    /** Select by id; a selection that is not installed is reported and the default speaks. */
    private fun applyVoice(result: SpeakResult, requested: String): SpeakResult {
        if (requested.isEmpty()) {
            engine.useDefaultVoice()
            return result
        }
        val installed = runCatching { engine.voices() }.getOrDefault(emptyList())
        if (requested in installed && engine.setVoice(requested)) return result.copy(voice = requested)
        engine.useDefaultVoice()
        return result.copy(
            voiceMissing = true,
            message = "The selected voice is unavailable; the device's default voice is speaking instead.",
        )
    }

    private fun fail(r: SpeakResult, failure: SpeakFailure, message: String?) = r.copy(
        status = SpeakStatus.FAILED,
        failure = failure,
        // Keep the voice warning visible beside the failure that ended the speak.
        message = listOfNotNull(message, r.message.takeIf { r.voiceMissing }).joinToString(" ")
            .ifEmpty { null },
    )

    private fun cancelled(r: SpeakResult) = r.copy(status = SpeakStatus.CANCELLED, failure = null)

    override fun cancel() {
        val op = active.getAndSet(null) ?: return
        op.stopped.complete(Unit)
        op.chunk?.complete(ChunkEnd.Cancelled)
        runCatching { engine.stop() }
    }

    override fun shutdown() {
        cancel()
        runCatching { engine.shutdown() }
    }
}
