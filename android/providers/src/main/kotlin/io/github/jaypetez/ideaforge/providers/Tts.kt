package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsNumber
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okio.Buffer
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

// The Kotlin twin of src/providers/tts.js: hosted speech as one buffered WAV from OpenAI's
// fixed `/audio/speech` endpoint, with a fixed model and two stock voices. Held to the web by
// the `tts` golden vectors (SpeechVectorTest): the request bytes, every bound, and the outcome
// for each scripted reply.
//
// No retries: a failed paid request must not silently spend again. A failed response's body
// and reason phrase are never read, because providers echo credentials in them.
//
// Native differences, all deliberate:
//
// - A transport failure is callFailure's `network` ("could not be reached"), not the web's
//   hint that a CORS-blocked response may be hiding the real error; there is no CORS here.
// - Cancelling the coroutine propagates as cancellation (the ProviderHttp rule), where the
//   web raises `aborted` "Speech cancelled.".
// - A redirect is never followed (no provider client follows one), so it arrives as its 3xx
//   status through the same error path; the web's `redirect: 'error'` fails the fetch.

const val MAX_TTS_INPUT_CHARS: Int = 4096
const val MAX_TTS_AUDIO_BYTES: Int = 8 * 1024 * 1024
const val MAX_TTS_AUDIO_SECONDS: Int = 120
const val TTS_DEADLINE_MS: Long = 30_000
const val TTS_KEY_HINT = "check the speech key in Settings"

data class TtsPreset(
    val label: String,
    val baseUrl: String,
    val url: String,
    val auth: AuthDescriptor,
    val model: String,
    val voices: List<String>,
    val responseFormat: String,
    val maxChars: Int,
)

val OPENAI_TTS = TtsPreset(
    label = "OpenAI speech",
    baseUrl = "https://api.openai.com/v1",
    url = "https://api.openai.com/v1/audio/speech",
    auth = AUTH_BEARER,
    model = "gpt-4o-mini-tts",
    voices = listOf("marin", "cedar"),
    responseFormat = "wav",
    maxChars = MAX_TTS_INPUT_CHARS,
)

val TTS_PRESETS: Map<String, TtsPreset> = linkedMapOf("openai" to OPENAI_TTS)

/** What validation learned about a WAV. [duration] is in seconds. */
data class SpeechInfo(val duration: Double, val sampleRate: Int, val channels: Int)

/** One synthesized utterance: a complete, validated, sized PCM WAV. */
class SpeechAudio(val audio: ByteArray, val contentType: String, val info: SpeechInfo) {
    val duration get() = info.duration
    val sampleRate get() = info.sampleRate
    val channels get() = info.channels
}

private val ACCEPTED_TYPES = setOf("audio/wav", "audio/wave", "audio/x-wav", "audio/vnd.wave", "application/octet-stream")
private const val UNKNOWN_WAV_SIZE = 0xffffffffL
private val DIGITS = Regex("^\\d+$")

private fun badAudio(message: String) = ProviderException(ProviderErrorCode.BAD_RESPONSE, message)

private fun rateFor(value: Double): Double {
    if (!value.isFinite() || value < 0.25 || value > 4) {
        throw ProviderException(ProviderErrorCode.CONFIG, "OpenAI speech rate must be a number from 0.25 to 4.")
    }
    return value
}

class TtsProvider internal constructor(
    private val apiKey: String,
    val voice: String,
    val rate: Double,
    private val http: ProviderHttp,
    private val now: () -> Long,
) {
    val id = "openai"

    /** One request, one buffered WAV. [rate] overrides the provider's for this call. */
    suspend fun synthesize(text: String, rate: Double = this.rate): SpeechAudio {
        if (jsTrim(text).isEmpty() || text.length > MAX_TTS_INPUT_CHARS) {
            throw ProviderException(ProviderErrorCode.CONFIG, "Speech input must contain 1 to $MAX_TTS_INPUT_CHARS characters.")
        }
        rateFor(rate)
        val body = buildJsonObject {
            put("model", OPENAI_TTS.model)
            put("input", text)
            put("voice", voice)
            put("response_format", OPENAI_TTS.responseFormat)
            put("speed", jsNumber(rate))
        }
        val request = http.request(
            OPENAI_TTS.url, OPENAI_TTS.auth, apiKey,
            linkedMapOf("content-type" to "application/json", "accept" to "audio/wav"),
        ).post(jsonBody(body)).build()
        return http.executeReading(request, OPENAI_TTS.label, TTS_KEY_HINT, onError = ::failure) { res ->
            res.use(::readSpeech)
        }
    }

    /** httpError's message for an empty body and a fixed reason phrase: nothing the server said. */
    private fun failure(res: Response): ProviderException {
        val code = codeForStatus(res.code)
        val hint = if (code == ProviderErrorCode.AUTH) " — $TTS_KEY_HINT" else ""
        return ProviderException(
            code, "${OPENAI_TTS.label} ${res.code}: speech request failed$hint",
            status = res.code, retryAfterMs = retryAfterMs(res.headers, now()),
        )
    }
}

private fun readSpeech(res: Response): SpeechAudio {
    val type = jsTrim((res.header("content-type") ?: "").split(';')[0]).lowercase()
    if (type !in ACCEPTED_TYPES) throw badAudio("Speech returned an unsupported audio content type.")
    val audio = readBounded(res, MAX_TTS_AUDIO_BYTES)
    val (info, sizes) = inspectSpeechAudio(audio, completedBody = true)
    // Nonseekable WAV encoders leave RIFF/data sizes at UINT32_MAX. Rewrite only those fields
    // after a complete body, full-frame alignment and every size check.
    val view = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
    for ((offset, size) in sizes) view.putInt(offset, size.toInt())
    return SpeechAudio(audio, "audio/wav", info)
}

/** The body, never more than [limit] bytes of it in memory, checked against its declared length. */
private fun readBounded(res: Response, limit: Int): ByteArray {
    val declared = res.header("content-length")
    var expected: Long? = null
    if (declared != null) {
        if (!DIGITS.matches(declared)) throw badAudio("Speech returned an invalid audio length.")
        val n = BigInteger(declared)
        if (n > BigInteger.valueOf(limit.toLong())) throw badAudio("Speech audio exceeds the response size limit.")
        expected = n.toLong()
    }
    val source = res.body.source()
    val buf = Buffer()
    while (source.read(buf, 64 * 1024L) != -1L) {
        if (buf.size > limit) throw badAudio("Speech audio exceeds the response size limit.")
    }
    if (expected != null && buf.size != expected) throw badAudio("Speech audio was truncated.")
    if (buf.size == 0L) throw badAudio("Speech returned empty audio.")
    return buf.readByteArray()
}

/**
 * Strict sized-PCM validation before anything decodes it: some decoders accept truncated
 * files. Only the adapter may normalise streaming size sentinels, after a complete body.
 */
fun validateSpeechAudio(audio: ByteArray): SpeechInfo = inspectSpeechAudio(audio, completedBody = false).first

private fun inspectSpeechAudio(audio: ByteArray, completedBody: Boolean): Pair<SpeechInfo, List<Pair<Int, Long>>> {
    if (audio.size < 44) throw badAudio("Speech returned empty or malformed WAV audio.")
    if (audio.size > MAX_TTS_AUDIO_BYTES) throw badAudio("Speech audio exceeds the response size limit.")
    val data = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
    val length = audio.size.toLong()
    fun tag(at: Int) = String(audio, at, minOf(4, audio.size - at), Charsets.ISO_8859_1)
    fun u32(at: Int) = data.getInt(at).toLong() and 0xffffffffL
    fun u16(at: Int) = data.getShort(at).toInt() and 0xffff
    if (tag(0) != "RIFF" || tag(8) != "WAVE") throw badAudio("Speech returned an unsupported audio format.")
    val sizes = mutableListOf<Pair<Int, Long>>()
    val riffSize = u32(4)
    if (riffSize + 8 != length) {
        if (!completedBody || riffSize != UNKNOWN_WAV_SIZE) {
            throw badAudio("Speech returned truncated or unknown-length WAV audio.")
        }
        sizes += 4 to length - 8
    }
    var format: IntArray? = null   // encoding, channels, sampleRate, byteRate, blockAlign, bits
    var samples: Long? = null
    var at = 12L
    while (at < length) {
        if (at + 8 > length) throw badAudio("Speech returned a truncated WAV chunk.")
        val pos = at.toInt()
        val name = tag(pos)
        var size = u32(pos + 4)
        val start = at + 8
        if (completedBody && name == "data" && size == UNKNOWN_WAV_SIZE) {
            if (format == null) throw badAudio("Streaming WAV audio is missing its preceding PCM format.")
            size = length - start
            sizes += (pos + 4) to size
        }
        val end = start + size
        if (end + (size % 2) > length) throw badAudio("Speech returned a truncated WAV chunk.")
        val s = start.toInt()
        if (name == "fmt ") {
            if (format != null || size < 16) throw badAudio("Speech returned a malformed WAV format.")
            // sampleRate and byteRate are u32; anything past Int range fails the checks below anyway.
            format = intArrayOf(
                u16(s), u16(s + 2), u32(s + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                u32(s + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), u16(s + 12), u16(s + 14),
            )
        } else if (name == "data") {
            if (samples != null) throw badAudio("Speech returned multiple WAV data chunks.")
            samples = size
        }
        at = end + (size % 2)
    }
    val n = samples
    if (format == null || n == null || n == 0L) throw badAudio("Speech returned no playable WAV samples.")
    val (encoding, channels, sampleRate, byteRate, blockAlign, bits) = format.let { Six(it) }
    if (encoding != 1 || channels !in setOf(1, 2) || bits !in setOf(8, 16, 24, 32) ||
        sampleRate < 8000 || sampleRate > 96000 || blockAlign.toDouble() != channels * bits / 8.0 ||
        byteRate.toLong() != sampleRate.toLong() * blockAlign || n % blockAlign != 0L
    ) {
        throw badAudio("Speech returned an unsupported or malformed PCM WAV format.")
    }
    val duration = n.toDouble() / byteRate
    if (duration > MAX_TTS_AUDIO_SECONDS) throw badAudio("Speech audio exceeds the duration limit.")
    return SpeechInfo(duration, sampleRate, channels) to sizes
}

/** Destructuring for the six fmt fields. */
private class Six(private val v: IntArray) {
    operator fun component1() = v[0]
    operator fun component2() = v[1]
    operator fun component3() = v[2]
    operator fun component4() = v[3]
    operator fun component5() = v[4]
    operator fun component6() = v[5]
}

/**
 * Only the fixed endpoint, model and stock voices. [clients] and [now] are test seams, not
 * settings; [deadlineMs] is the whole-call bound, 30s unless a caller asks for less or more
 * within 1..120000.
 */
fun createTtsProvider(
    apiKey: String?,
    voice: String = "marin",
    rate: Double = 1.0,
    deadlineMs: Long = TTS_DEADLINE_MS,
    clients: ClientFactory = ClientFactory.DEFAULT,
    now: () -> Long = System::currentTimeMillis,
): TtsProvider {
    val key = jsTrim(apiKey.orEmpty())
    if (key.isEmpty()) throw ProviderException(ProviderErrorCode.CONFIG, "OpenAI speech needs its own API key.")
    if (voice !in OPENAI_TTS.voices) throw ProviderException(ProviderErrorCode.CONFIG, "Choose the Marin or Cedar speech voice.")
    rateFor(rate)
    if (deadlineMs < 1 || deadlineMs > 120_000) {
        throw ProviderException(ProviderErrorCode.CONFIG, "Speech requests need a deadline between 1 and 120000 ms.")
    }
    val policy = NetworkPolicy.Hosted(OPENAI_TTS.baseUrl)
    val client = clients.create(policy).newBuilder()
        .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
        .connectTimeout(minOf(ProviderHttpClients.CONNECT_TIMEOUT_MS, deadlineMs), TimeUnit.MILLISECONDS)
        .build()
    return TtsProvider(key, voice, rate, ProviderHttp(policy, client, deadlineMs, now), now)
}

/**
 * The speech provider the keyring's `tts` record describes, and only that record: never the
 * inference key, never the transcription key. Hosted speech is an explicit opt-in, so a ring
 * whose speech is still 'browser' is a `config` error, as is a missing key. Whether the
 * record has passed its preview check is the caller's gate, because the check itself needs
 * a provider.
 */
fun speechFromKeyring(
    ring: JsonElement?,
    rate: Double = 1.0,
    clients: ClientFactory = ClientFactory.DEFAULT,
    now: () -> Long = System::currentTimeMillis,
): TtsProvider {
    val tts = ring["tts"]
    if (stringOf(tts["kind"]) != "openai") {
        throw ProviderException(ProviderErrorCode.CONFIG, "Hosted speech is off; choose OpenAI speech in Settings.")
    }
    return createTtsProvider(
        apiKey = stringOf(tts["apiKey"]), voice = stringOf(tts["voice"]) ?: "marin",
        rate = rate, clients = clients, now = now,
    )
}
