package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.splitSpeechText
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.providers.ClientFactory
import io.github.jaypetez.ideaforge.providers.MAX_TTS_AUDIO_SECONDS
import io.github.jaypetez.ideaforge.providers.MAX_TTS_INPUT_CHARS
import io.github.jaypetez.ideaforge.providers.STT_PRESETS
import io.github.jaypetez.ideaforge.providers.createTtsProvider
import io.github.jaypetez.ideaforge.providers.speechFromKeyring
import io.github.jaypetez.ideaforge.providers.transcriberFromKeyring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

// Hosted speech output: the Android twin of the 'openai' path in src/voice/output.js.
//
// The rules it keeps, each one silent when broken:
//  - Hosted replies only after consent AND a passing check on the hosted backend itself
//    (hostedSpeechReady). The gate is read at every speak, from the keyring and prefs, so
//    Forget, a revoked consent or a changed key/voice closes it for the very next sentence.
//  - check() uses the hosted backend and nothing else. A device voice that spoke the sample
//    would prove nothing about OpenAI, so a fallback can never pass the check.
//  - A live failure (network, auth, bad audio, playback) falls back VISIBLY to the device
//    voice: the result carries `fallback`, and the rest of the drive stays on the device
//    voice (the web's `downgraded`) instead of paying a 30 s deadline on every sentence.
//  - Cancellation never falls back. A Pause that started the device voice talking would be
//    the opposite of a pause.
//  - Focus is held for the length of one speak, as the native Speaker does; losing it is
//    INTERRUPTED (a call rang), not a hosted failure, so it does not fall back either.
//  - The question and its suggestions stay two utterances: speakQuestion is inherited from
//    Speaker, and every utterance is split into the web's 350-character chunks, one request
//    each, never one whole prompt.

/** One synthesized utterance as a complete WAV. The seam over providers' TtsProvider. */
fun interface SpeechSynth {
    suspend fun synthesize(text: String, rate: Double): ByteArray
}

/** Plays one complete WAV from memory, returning when it has finished. Throws on failure. */
interface WavPlayer {
    suspend fun play(wav: ByteArray)
    /** Stop now; the play in progress returns or throws promptly. */
    fun stop()
}

/**
 * The gate, in one place: hosted speech is chosen, the keyring record says its check passed
 * for the key and voice it holds (withTts clears `verified` without a key), and the consent
 * preference is still on. All three, or the device voice speaks.
 */
fun hostedSpeechReady(ring: JsonElement?, prefs: Prefs): Boolean {
    val tts = ring["tts"]
    return prefs.hostedSpeechAllowed &&
        stringOf(tts["kind"]) == "openai" &&
        tts["verified"] == JsonPrimitive(true) &&
        !stringOf(tts["apiKey"]).isNullOrBlank()
}

/** The sample the Settings check speaks; the web's previewSpeech text. */
const val SPEECH_CHECK_TEXT =
    "IdeaForge is ready. Tell me what you have in mind, and we will take it one question at a time."

/** The web's chunk size for hosted speech: min(350, MAX_TTS_INPUT_CHARS). */
private val HOSTED_CHUNK_CHARS = minOf(350, MAX_TTS_INPUT_CHARS)

class HostedSpeaker(
    private val native: Speaker,
    private val player: WavPlayer,
    private val focus: AudioFocus,
    private val prefs: suspend () -> Prefs,
    /** The checked synthesizer, or null when the gate is closed (see [hostedSpeechReady]). */
    private val synth: suspend () -> SpeechSynth?,
) : Speaker {
    private class Op {
        val stopped = CompletableDeferred<Unit>()
        @Volatile var interrupted = false
    }

    private val active = AtomicReference<Op?>(null)

    /** A live failure this drive: the device voice speaks until [newSession]. */
    @Volatile var downgraded: String? = null
        private set

    override fun newSession() {
        downgraded = null
    }

    override suspend fun speak(text: String): SpeakResult {
        val s = if (downgraded == null) synth() else null
        if (s == null) return native.speak(text)
        val p = prefs()
        return run(text, s, p.speechRate, checking = false, regate = synth)
    }

    /**
     * The Settings check: [synth] (built from the key and voice being checked, not the saved
     * ones) speaks [text] through the hosted backend only. SPOKEN means OpenAI audio played to
     * the end on this device; anything else, the device voice included, is not a pass.
     */
    suspend fun check(synth: SpeechSynth, rate: Double, text: String = SPEECH_CHECK_TEXT): SpeakResult =
        run(text, synth, rate, checking = true)

    /**
     * [regate] is asked again before every chunk after the first: Forget or a withdrawn
     * consent mid-utterance stops the rest (CANCELLED, never a fallback, and never a request
     * signed with a key that has just been deleted).
     */
    private suspend fun run(
        text: String,
        s: SpeechSynth,
        rate: Double,
        checking: Boolean,
        regate: (suspend () -> SpeechSynth?)? = null,
    ): SpeakResult {
        cancel() // a new utterance replaces the one in progress
        val op = Op()
        active.set(op)
        var result = SpeakResult(SpeakStatus.FAILED, rate = rate, backend = SpeechBackend.HOSTED)
        if (text.isBlank()) return result.copy(failure = SpeakFailure.CONFIG, message = "There is no text to speak.")
        val chunks = splitSpeechText(text, HOSTED_CHUNK_CHARS)
        result = result.copy(chunks = chunks.size)
        var focused = false
        try {
            focused = focus.request { interrupt(op) }
            if (!focused) {
                return result.copy(failure = SpeakFailure.FOCUS_DENIED, message = "Another app holds the audio; nothing was said.")
            }
            for ((i, chunk) in chunks.withIndex()) {
                val current = if (i == 0 || regate == null) s else regate() ?: return result.copy(
                    status = SpeakStatus.CANCELLED, message = "Hosted speech was turned off.",
                )
                val outcome: Throwable? = coroutineScope {
                    val work = async {
                        try {
                            player.play(current.synthesize(chunk, rate))
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e
                        }
                    }
                    select<Throwable?> {
                        work.onAwait { it }
                        op.stopped.onAwait {
                            work.cancel()
                            STOPPED
                        }
                    }
                }
                when {
                    outcome === STOPPED || op.stopped.isCompleted -> return stopped(op, result)
                    outcome == null -> result = result.copy(chunksSpoken = i + 1)
                    checking -> return result.copy(
                        failure = SpeakFailure.ENGINE_ERROR,
                        message = outcome.message ?: "OpenAI speech failed.",
                    )
                    else -> {
                        // Live failure: say so, and let the device voice finish the utterance.
                        val reason = (outcome.message ?: "OpenAI speech failed.") +
                            " Using the device voice for the rest of this drive."
                        downgraded = reason
                        runCatching { player.stop() }
                        if (focused) focus.abandon()
                        focused = false
                        active.compareAndSet(op, null)
                        val rest = chunks.drop(i).joinToString(" ")
                        val spoken = native.speak(rest)
                        return spoken.copy(
                            backend = SpeechBackend.NATIVE,
                            fallback = reason,
                            chunksSpoken = result.chunksSpoken + spoken.chunksSpoken,
                            chunks = result.chunksSpoken + spoken.chunks,
                        )
                    }
                }
            }
            return result.copy(status = SpeakStatus.SPOKEN)
        } finally {
            if (active.get() === op) runCatching { player.stop() }
            if (focused) focus.abandon()
            active.compareAndSet(op, null)
        }
    }

    /** Stopped by cancel() (not an error, never a fallback), or by losing audio focus. */
    private fun stopped(op: Op, r: SpeakResult) = if (op.interrupted) {
        r.copy(status = SpeakStatus.FAILED, failure = SpeakFailure.INTERRUPTED, message = "Another app took the audio; speech stopped.")
    } else {
        r.copy(status = SpeakStatus.CANCELLED, failure = null)
    }

    private fun halt(op: Op) {
        op.stopped.complete(Unit)
        runCatching { player.stop() }
    }

    private fun interrupt(op: Op) {
        if (!active.compareAndSet(op, null)) return
        op.interrupted = true
        halt(op)
    }

    override fun cancel() {
        active.getAndSet(null)?.let(::halt)
        native.cancel()
    }

    override fun shutdown() {
        cancel()
        native.shutdown()
    }

    private companion object {
        val STOPPED = CancellationException("stopped")
    }
}

/** The PCM inside a validated WAV: where its samples are and how to play them. */
class WavPcm(val sampleRate: Int, val channels: Int, val bits: Int, val offset: Int, val length: Int) {
    val frames: Int get() = length / (channels * bits / 8)
    val durationMs: Long get() = frames * 1000L / sampleRate
}

/**
 * Find the fmt and data chunks. The provider already validated the WAV (validateSpeechAudio);
 * this re-checks only what playback relies on, and refuses rather than plays noise.
 */
fun wavPcm(wav: ByteArray): WavPcm {
    fun bad(): Nothing = throw IllegalArgumentException("The speech audio could not be played.")
    if (wav.size < 44) bad()
    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(at: Int) = String(wav, at, 4, Charsets.ISO_8859_1)
    if (tag(0) != "RIFF" || tag(8) != "WAVE") bad()
    var at = 12
    var fmt: IntArray? = null
    while (at + 8 <= wav.size) {
        val name = tag(at)
        val size = b.getInt(at + 4).toLong() and 0xffffffffL
        val start = at + 8
        if (name == "fmt " && size >= 16 && start + 16 <= wav.size) {
            fmt = intArrayOf(b.getShort(start).toInt() and 0xffff, b.getShort(start + 2).toInt(), b.getInt(start + 4), b.getShort(start + 14).toInt())
        } else if (name == "data") {
            val f = fmt ?: bad()
            val length = minOf(size, (wav.size - start).toLong()).toInt()
            if (f[0] != 1 || f[1] !in 1..2 || f[3] != 16 || f[2] !in 8000..96000 || length <= 0) bad()
            val pcm = WavPcm(f[2], f[1], f[3], start, length - length % (f[1] * 2))
            if (pcm.durationMs > MAX_TTS_AUDIO_SECONDS * 1000L) bad()
            return pcm
        }
        if (size > wav.size) bad()
        at = start + size.toInt() + (size % 2).toInt()
    }
    bad()
}

/**
 * The synthesizer the speaking path uses: built from the keyring's `tts` record alone
 * (speechFromKeyring never reads the inference or transcription key), and only while
 * [hostedSpeechReady] holds.
 */
fun hostedSynth(
    ring: JsonElement?,
    prefs: Prefs,
    clients: ClientFactory = ClientFactory.DEFAULT,
): SpeechSynth? {
    if (!hostedSpeechReady(ring, prefs)) return null
    val tts = speechFromKeyring(ring, rate = prefs.speechRate, clients = clients)
    return SpeechSynth { text, rate -> tts.synthesize(text, rate).audio }
}

/** A synthesizer for the Settings check: the key and voice being checked, not the saved ones. */
fun checkSynth(apiKey: String, voice: String, rate: Double, clients: ClientFactory = ClientFactory.DEFAULT): SpeechSynth {
    val tts = createTtsProvider(apiKey = apiKey, voice = voice, rate = rate, clients = clients)
    return SpeechSynth { text, r -> tts.synthesize(text, r).audio }
}

/**
 * The Whisper fallback's listener, or null unless the audio-transfer consent is on and the
 * keyring's own `stt` record names a vendor and holds a key. Never the inference key.
 */
fun whisperFrom(
    ring: JsonElement?,
    prefs: Prefs,
    clients: ClientFactory = ClientFactory.DEFAULT,
    /** Re-read before every upload (see WhisperListener.allowed): the live consent and key. */
    stillAllowed: suspend () -> Boolean = { true },
    source: () -> PcmSource,
): WhisperListener? {
    val stt = ring["stt"]
    if (!prefs.hostedTranscriptionAllowed) return null
    if (stringOf(stt["kind"]) !in STT_PRESETS || stringOf(stt["apiKey"]).isNullOrBlank()) return null
    val transcriber = transcriberFromKeyring(ring, clients = clients)
    return WhisperListener(source, transcribe = { audio, mime -> transcriber.transcribe(audio, mime) }, allowed = stillAllowed)
}
