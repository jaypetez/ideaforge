package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.parseSpeech
import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import io.github.jaypetez.ideaforge.providers.MAX_AUDIO_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// Record-and-transcribe for hands-free, used ONLY when the native recogniser is unavailable
// (SpeechRecognizer.isRecognitionAvailable is false, or a capture reported the recogniser or
// its language unavailable). The Android twin of the web's record path in src/voice/index.js
// with the DRIVING_GATE from src/voice/vad.js.
//
// What it keeps:
//  - The same DriveIo.listen contract as Recognizer.listenForDrive: the RAW transcript,
//    trigger included; "" for a miss (nothing said before firstWordMs); a ListenFailure for a
//    failure; coroutine cancellation propagates and always stops the recording.
//  - Segment accumulation: the trigger word ends the answer, not the gate. A segment ends on
//    silence and is transcribed; the answer ends once the accumulated text ends on the
//    trigger or is a whole command. After a word, a pause never ends the answer.
//  - Bounds, as the web: one segment is at most DRIVING_GATE.maxMs (45 s), one upload at most
//    MAX_AUDIO_BYTES, and one answer at most DEFAULTS.maxMs (120 s) of recording, after which
//    what was heard is returned (the watchdog's "resolve, never reject").
//  - Time is counted in samples, not read from a clock, so the gate is deterministic in tests.

/** A microphone, as far as the listener needs one. Only [WhisperMicrophone] opens a real one. */
interface PcmSource {
    val sampleRate: Int
    /** Start capturing; false when the microphone could not be opened. */
    fun start(): Boolean
    /** Blocking read of up to buffer.size samples; negative on failure. */
    fun read(buffer: ShortArray): Int
    fun stop()
}

/** src/voice/vad.js DRIVING_GATE, the numbers the web records hands-free answers with. */
data class SegmentGateConfig(
    val silenceMs: Long = 2500,
    val minSpeechMs: Long = 900,
    val maxMs: Long = 45_000,
    val speechFactor: Double = 2.8,
    val minThreshold: Double = 0.012,
    val calibrateMs: Long = 500,
    /** Give up when nothing was said this long; 0 disables it. */
    val noSpeechMs: Long = 0,
)

enum class GateVerdict { CONTINUE, DONE, NO_SPEECH }

/**
 * vad.js's gate over 20 ms frames: a running-minimum noise floor (speech has gaps at word
 * boundaries, so the minimum finds the room), speech above max(floor × factor, minThreshold).
 */
class SegmentGate(private val cfg: SegmentGateConfig) {
    private var elapsed = 0L
    private var speechMs = 0L
    private var silentMs = 0L
    private var floor = Double.MAX_VALUE
    val heard: Boolean get() = speechMs >= cfg.minSpeechMs

    fun push(rms: Double, frameMs: Long): GateVerdict {
        elapsed += frameMs
        floor = minOf(floor, rms)
        if (elapsed <= cfg.calibrateMs) return GateVerdict.CONTINUE
        val threshold = maxOf(cfg.minThreshold, floor * cfg.speechFactor)
        if (rms > threshold) {
            speechMs += frameMs
            silentMs = 0
        } else {
            silentMs += frameMs
        }
        return when {
            elapsed >= cfg.maxMs -> GateVerdict.DONE
            heard && silentMs >= cfg.silenceMs -> GateVerdict.DONE
            speechMs == 0L && cfg.noSpeechMs > 0 && elapsed >= cfg.noSpeechMs -> GateVerdict.NO_SPEECH
            else -> GateVerdict.CONTINUE
        }
    }
}

/** Mono 16-bit PCM as a WAV, in memory. */
fun pcmWav(samples: ByteArray, sampleRate: Int): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.ISO_8859_1)); putInt(36 + samples.size)
        put("WAVE".toByteArray(Charsets.ISO_8859_1)); put("fmt ".toByteArray(Charsets.ISO_8859_1))
        putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.ISO_8859_1)); putInt(samples.size)
    }
    return header.array() + samples
}

class WhisperListener(
    private val source: () -> PcmSource,
    /** The keyring's stt transcriber (transcriberFromKeyring): never an inference key. */
    private val transcribe: suspend (audio: ByteArray, mime: String) -> String,
    private val gate: SegmentGateConfig = SegmentGateConfig(),
    private val maxAnswerMs: Long = 120_000,
    private val maxBytes: Int = MAX_AUDIO_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Re-read before every upload: false once Forget ran or the audio consent was withdrawn.
     * The capture then stops as a cancellation (AbortError), and nothing more is sent.
     */
    private val allowed: suspend () -> Boolean = { true },
) {
    /** [onText] receives the accumulated transcript after each segment: the live caption. */
    suspend fun listenForDrive(trigger: String, firstWordMs: Long, onText: (String) -> Unit = {}): String {
        val mic = source()
        if (!mic.start()) throw ListenFailure("The microphone is unavailable.", name = "AudioCaptureError")
        val frame = ShortArray(mic.sampleRate / 50) // 20 ms
        val frameMs = 20L
        var answer = ""
        var recorded = 0L
        try {
            while (recorded < maxAnswerMs) {
                val g = SegmentGate(gate.copy(noSpeechMs = if (answer.isEmpty()) firstWordMs else 0))
                val pcm = ByteArrayOutputStream()
                var verdict = GateVerdict.CONTINUE
                withContext(io) {
                    while (verdict == GateVerdict.CONTINUE && recorded < maxAnswerMs) {
                        ensureActive()
                        val n = mic.read(frame)
                        if (n < 0) throw ListenFailure("The microphone stopped delivering audio.", name = "AudioCaptureError")
                        if (n == 0) continue
                        if (pcm.size() + n * 2 + 44 > maxBytes) {
                            verdict = GateVerdict.DONE
                            break
                        }
                        var sum = 0.0
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val v = frame[i]
                            sum += (v / 32768.0) * (v / 32768.0)
                            bytes[2 * i] = (v.toInt() and 0xff).toByte()
                            bytes[2 * i + 1] = (v.toInt() shr 8 and 0xff).toByte()
                        }
                        pcm.write(bytes)
                        val ms = n * 1000L / mic.sampleRate
                        recorded += ms
                        verdict = g.push(sqrt(sum / n), if (ms > 0) ms else frameMs)
                    }
                }
                if (!g.heard) {
                    // Nothing said in this segment: before the first word that is the miss the
                    // recovery ladder handles; after it, keep listening for the trigger.
                    if (answer.isEmpty()) return ""
                    continue
                }
                if (!allowed()) throw ListenFailure("Transcription was turned off.", name = "AbortError")
                val text = try {
                    transcribe(pcmWav(pcm.toByteArray(), mic.sampleRate), "audio/wav").trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ListenFailure(e.message ?: "Transcription failed.", name = "TranscriptionError")
                }
                if (text.isNotEmpty()) answer = if (answer.isEmpty()) text else "$answer $text"
                onText(answer)
                val parsed = parseSpeech(answer, trigger)
                if (parsed.stopped || (answer.isNotEmpty() && parsed.kind != "answer")) return answer
            }
            return answer
        } finally {
            mic.stop()
        }
    }
}
