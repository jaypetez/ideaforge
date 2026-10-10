package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.RoundingMode

// The Kotlin twin of src/voice/transcribe.js: speech to text as one OpenAI-shaped multipart
// POST to Groq's or OpenAI's `/audio/transcriptions`. The request is held to the web's by the
// `transcribe` golden vectors (SpeechVectorTest): the same parts, in the same order, with the
// same filename, content type and bounds.
//
// Native differences, all deliberate:
//
// - Only the two presets. The web's adapter takes a base URL, but no screen ever gives it
//   one other than a preset's, and a native client has no CSP to stop a typed address from
//   receiving the key. Each preset is a NetworkPolicy.Hosted client on its own https origin.
// - A request that gets no response at all is `network` (and retried), not the web's
//   "blocked before a reply came back — usually a rejected key". That guess exists because a
//   browser hides a rejected key behind an opaque CORS failure; natively the 401 arrives.
// - A 2xx body that is not JSON is `bad_response`, where the web's `res.json()` rejects with
//   an uncoded SyntaxError.

/** 25 MB is the documented limit on both services. Opus at 24 kbps reaches it around 2h. */
const val MAX_AUDIO_BYTES: Int = 25 * 1024 * 1024

/** The prompt primes the recogniser with the question just asked; longer is not better. */
const val MAX_TRANSCRIPTION_PROMPT_CHARS: Int = 800

const val TRANSCRIPTION_LABEL = "transcription"
const val TRANSCRIPTION_KEY_HINT = "check the transcription key in Settings"

data class SttPreset(val label: String, val baseUrl: String, val model: String, val note: String)

val STT_PRESETS: Map<String, SttPreset> = linkedMapOf(
    "groq" to SttPreset(
        label = "Groq Whisper",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "whisper-large-v3-turbo",
        note = "About a penny per interview. Free tier, no card.",
    ),
    "openai" to SttPreset(
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini-transcribe",
        note = "Around 0.3 cents a minute.",
    ),
)

/**
 * What one transcriber is built from. [apiKey] is the keyring's `stt` record, never an
 * inference key; [transcriberFromKeyring] is the only production route to one.
 */
data class TranscriberConfig(
    val kind: String?,
    val apiKey: String,
    val model: String? = null,
    val language: String? = null,
    val prompt: String? = null,
)

class Transcriber internal constructor(
    val id: String,
    val label: String,
    val model: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val language: String?,
    private val prompt: String?,
    private val http: ProviderHttp,
    private val retryDelay: suspend (Long) -> Unit,
) {
    /**
     * @param mimeType the recording's MIME type, as a Blob reports it; it picks the
     *   filename, which is how a Whisper endpoint chooses its decoder
     * @return the transcript, '' when nothing intelligible was said
     */
    suspend fun transcribe(audio: ByteArray, mimeType: String?): String =
        withRetry(delay = retryDelay) { once(audio, mimeType) }

    private suspend fun once(audio: ByteArray, mimeType: String?): String {
        // Before anything is built: an oversized recording never starts an upload.
        if (audio.size > MAX_AUDIO_BYTES) {
            throw ProviderException(
                ProviderErrorCode.CONFIG,
                "that recording is ${toFixed1(audio.size / 1048576.0)} MB, over the 25 MB limit",
            )
        }
        val type = blobType(mimeType)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", filenameFor(type),
                audio.toRequestBody(type.ifEmpty { OCTET_STREAM }.toMediaTypeOrNull() ?: OCTET_STREAM.toMediaType()),
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        if (!language.isNullOrEmpty()) form.addFormDataPart("language", usv(language))
        if (!prompt.isNullOrEmpty()) form.addFormDataPart("prompt", usv(prompt.take(MAX_TRANSCRIPTION_PROMPT_CHARS)))

        // No content-type header: the multipart body writes its own boundary.
        val request = http.request("$baseUrl/audio/transcriptions", AUTH_BEARER, apiKey).post(form.build()).build()
        val text = http.executeText(request, TRANSCRIPTION_LABEL, TRANSCRIPTION_KEY_HINT)
        val body = runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw ProviderException(ProviderErrorCode.BAD_RESPONSE, "$TRANSCRIPTION_LABEL returned a reply that is not JSON")
        }
        // JS `String((body && body.text) || '').trim()`.
        val said = (body as? JsonObject)?.get("text")
        return if (truthy(said)) jsTrim(jsString(said)) else ""
    }
}

/**
 * Build a transcriber. The base URL and network policy are the preset's; [clients] and
 * [retryDelay] are test seams.
 */
fun createTranscriber(
    config: TranscriberConfig,
    clients: ClientFactory = ClientFactory.DEFAULT,
    retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): Transcriber {
    val preset = config.kind?.let(STT_PRESETS::get)
    val model = config.model?.ifEmpty { null } ?: preset?.model
    if (preset == null || model == null) {
        throw ProviderException(ProviderErrorCode.CONFIG, "transcription needs a base URL and a model")
    }
    if (config.apiKey.isEmpty()) throw ProviderException(ProviderErrorCode.CONFIG, "transcription needs an API key")
    val policy = NetworkPolicy.Hosted(preset.baseUrl)
    return Transcriber(
        id = config.kind, label = preset.label, model = model, baseUrl = trimSlash(preset.baseUrl),
        apiKey = config.apiKey, language = config.language, prompt = config.prompt,
        http = ProviderHttp(policy, clients.create(policy)), retryDelay = retryDelay,
    )
}

/**
 * The transcriber the keyring's `stt` record describes, and only that record: the inference
 * provider's key is never a fallback, because a user who pasted one key for questions has
 * not agreed to send their voice to that vendor. A missing record is a `config` error.
 */
fun transcriberFromKeyring(
    ring: JsonElement?,
    language: String? = null,
    prompt: String? = null,
    clients: ClientFactory = ClientFactory.DEFAULT,
    retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): Transcriber {
    val stt = ring["stt"]
    return createTranscriber(
        TranscriberConfig(
            kind = stringOf(stt["kind"]),
            apiKey = stringOf(stt["apiKey"])?.let(::jsTrim).orEmpty(),
            language = language,
            prompt = prompt,
        ),
        clients, retryDelay,
    )
}

private const val OCTET_STREAM = "application/octet-stream"

/** A Blob's `type`: lowercased, or '' when it holds anything outside printable ASCII. */
internal fun blobType(mime: String?): String {
    val t = mime.orEmpty()
    return if (t.any { it < ' ' || it > '~' }) "" else t.lowercase()
}

/** Whisper endpoints dispatch on the extension, so the name has to match the codec. */
internal fun filenameFor(mime: String?): String {
    val type = mime.orEmpty().lowercase()
    return when {
        "ogg" in type -> "answer.ogg"
        "mp4" in type || "m4a" in type || "aac" in type -> "answer.mp4"
        "wav" in type -> "answer.wav"
        "mpeg" in type || "mp3" in type -> "answer.mp3"
        else -> "answer.webm"
    }
}

/**
 * FormData values are USVStrings: a lone surrogate (a slice through an emoji) is sent as
 * U+FFFD, where UTF-8 encoding in Kotlin would write '?'.
 */
internal fun usv(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { out.append(c).append(s[i + 1]); i++ }
            c.isSurrogate() -> out.append('�')
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** JS `n.toFixed(1)`: the exact binary value, rounded half up. */
internal fun toFixed1(n: Double): String = BigDecimal(n).setScale(1, RoundingMode.HALF_UP).toPlainString()
