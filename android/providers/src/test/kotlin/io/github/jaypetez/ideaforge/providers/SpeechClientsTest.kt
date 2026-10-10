package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.Keyring
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.core.withTts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import okhttp3.ResponseBody.Companion.asResponseBody
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hosted speech has its own secret (CLAUDE.md, "Hosted speech needs its own permission and
 * secret"): the transcriber and the speech provider each sign with their own keyring record,
 * never the inference key and never each other's, and a missing speech key is a `config`
 * error before anything is sent. Requests are captured by an interceptor; no network.
 */
class SpeechClientsTest {
    private val captured = mutableListOf<Request>()
    private var failTransport = false

    private val stub = Interceptor { chain ->
        val req = chain.request()
        captured += req
        if (failTransport) throw IOException("connection reset")
        val (type, body) = if (req.url.encodedPath.endsWith("/audio/speech")) {
            "audio/wav" to WAV
        } else {
            "application/json" to """{"text":"heard"}""".toByteArray()
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("content-type", type).body(body.toResponseBody(type.toMediaType())).build()
    }
    private val clients = ClientFactory { ProviderHttpClients.create(it).newBuilder().addInterceptor(stub).build() }

    private fun ring(stt: Pair<String, String>? = null, tts: Triple<String, String, String>? = null): Keyring {
        var r = withCreds(emptyKeyring(), "openai", obj("apiKey" to str(INFERENCE_KEY)))
        if (stt != null) r = withStt(r, obj("kind" to str(stt.first), "apiKey" to str(stt.second)))
        if (tts != null) r = withTts(r, obj("kind" to str(tts.first), "apiKey" to str(tts.second), "voice" to str(tts.third)))
        return r
    }

    private fun authOf(req: Request) = req.header("authorization")

    @Test
    fun eachClientSignsWithItsOwnRecord() = runBlocking {
        // All three on OpenAI: the vendor is the same, the secrets are not.
        val r = ring(stt = "openai" to "stt-key", tts = Triple("openai", "tts-key", "cedar"))

        assertEquals("heard", transcriberFromKeyring(r, clients = clients, retryDelay = {}).transcribe(AUDIO, "audio/webm"))
        val spoken = speechFromKeyring(r, clients = clients).synthesize("Hello there.")
        assertEquals(24000, spoken.sampleRate)

        val (stt, tts) = captured
        assertEquals("https://api.openai.com/v1/audio/transcriptions", stt.url.toString())
        assertEquals("Bearer stt-key", authOf(stt))
        assertEquals("https://api.openai.com/v1/audio/speech", tts.url.toString())
        assertEquals("Bearer tts-key", authOf(tts))
        assertTrue(Buffer().also { tts.body!!.writeTo(it) }.readUtf8().contains("\"voice\":\"cedar\""))
        for (req in captured) assertTrue(INFERENCE_KEY !in req.headers.toString(), "the inference key never rides on speech")
    }

    @Test
    fun theTranscriberGoesToItsOwnPresetHost() = runBlocking {
        transcriberFromKeyring(ring(stt = "groq" to " gsk-key "), clients = clients, retryDelay = {}).transcribe(AUDIO, "audio/ogg")
        val req = captured.single()
        assertEquals("api.groq.com", req.url.host)
        assertEquals("Bearer gsk-key", authOf(req), "the keyring's key, trimmed")
    }

    @Test
    fun aMissingTranscriptionKeyIsAConfigErrorEvenWithAnInferenceKey() {
        for (r in listOf(ring(), ring(stt = "openai" to ""), ring(stt = "groq" to "   "))) {
            val err = assertFailsWith<ProviderException> { transcriberFromKeyring(r, clients = clients) }
            assertEquals(ProviderErrorCode.CONFIG, err.code)
        }
        // No stt record at all reads as no kind: the preset check fires first, as in the JS.
        assertEquals(
            "transcription needs a base URL and a model",
            assertFailsWith<ProviderException> { transcriberFromKeyring(ring(), clients = clients) }.message,
        )
        assertEquals(
            "transcription needs an API key",
            assertFailsWith<ProviderException> { transcriberFromKeyring(ring(stt = "openai" to ""), clients = clients) }.message,
        )
        assertTrue(captured.isEmpty())
    }

    @Test
    fun aMissingSpeechKeyIsAConfigErrorEvenWithTheOtherTwoKeys() {
        val noKey = ring(stt = "openai" to "stt-key", tts = Triple("openai", "", "marin"))
        val err = assertFailsWith<ProviderException> { speechFromKeyring(noKey, clients = clients) }
        assertEquals(ProviderErrorCode.CONFIG, err.code)
        assertEquals("OpenAI speech needs its own API key.", err.message)

        // Browser speech is the default: a ring that never opted in has no hosted provider,
        // whatever keys it holds for anything else.
        for (r in listOf(ring(stt = "openai" to "stt-key"), ring(tts = Triple("browser", "tts-key", "marin")))) {
            assertEquals(ProviderErrorCode.CONFIG, assertFailsWith<ProviderException> { speechFromKeyring(r, clients = clients) }.code)
        }
        assertTrue(captured.isEmpty())
    }

    @Test
    fun transcriptionRetriesATransportFailureAndSpeechNeverDoes() {
        failTransport = true
        val stt = assertFailsWith<ProviderException> {
            runBlocking { createTranscriber(TranscriberConfig("groq", "k"), clients, retryDelay = {}).transcribe(AUDIO, "audio/webm") }
        }
        assertEquals(ProviderErrorCode.NETWORK, stt.code)
        assertEquals(3, captured.size, "withRetry: three attempts")

        captured.clear()
        val tts = assertFailsWith<ProviderException> { runBlocking { createTtsProvider("k", clients = clients).synthesize("Hi.") } }
        assertEquals(ProviderErrorCode.NETWORK, tts.code)
        assertEquals(1, captured.size, "a paid speech request is never retried")
    }

    @Test
    fun theSpeechDeadlineIsItsOwn() {
        val captureTimeout = mutableListOf<Int>()
        val probe = ClientFactory { policy ->
            ProviderHttpClients.create(policy).newBuilder().addInterceptor { chain ->
                captureTimeout += chain.call().timeout().timeoutNanos().let { (it / 1_000_000).toInt() }
                stub.intercept(chain)
            }.build()
        }
        runBlocking { createTtsProvider("k", clients = probe).synthesize("Hi.") }
        runBlocking { createTtsProvider("k", deadlineMs = 5000, clients = probe).synthesize("Hi.") }
        assertEquals(listOf(TTS_DEADLINE_MS.toInt(), 5000), captureTimeout)
    }

    /**
     * The 8 MB response bound is enforced while streaming, not after buffering the whole body:
     * a reply that never ends fails just past the bound. The source refuses to go past 64 MB
     * and the call has a timeout, so a regression to read-everything fails instead of hanging
     * or exhausting memory.
     */
    @Test
    fun anEndlessSpeechBodyFailsJustPastTheBound() {
        var served = 0L
        val endless = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (served >= 64L * 1024 * 1024) throw IOException("read far past the bound")
                val n = minOf(byteCount, 8192L)
                sink.write(ByteArray(n.toInt()))
                served += n
                return n
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }
        val endlessClients = ClientFactory { policy ->
            ProviderHttpClients.create(policy).newBuilder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("content-type", "audio/wav")
                    .body(endless.buffer().asResponseBody("audio/wav".toMediaType(), -1)).build()
            }.build()
        }
        val err = assertFailsWith<ProviderException> {
            runBlocking { withTimeout(20_000) { createTtsProvider("k", clients = endlessClients).synthesize("Hi.") } }
        }
        assertEquals(ProviderErrorCode.BAD_RESPONSE, err.code)
        assertEquals("Speech audio exceeds the response size limit.", err.message)
        assertTrue(served > MAX_TTS_AUDIO_BYTES, "it read up to the bound ($served)")
        assertTrue(served <= MAX_TTS_AUDIO_BYTES + 256L * 1024, "it stopped just past the bound, not later ($served)")
    }

    @Test
    fun oversizedAudioNeverStartsAnUpload() {
        val err = assertFailsWith<ProviderException> {
            runBlocking { createTranscriber(TranscriberConfig("groq", "k"), clients, retryDelay = {}).transcribe(ByteArray(MAX_AUDIO_BYTES + 1), "audio/webm") }
        }
        assertEquals(ProviderErrorCode.CONFIG, err.code)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun usvReplacesOnlyLoneSurrogates() {
        assertEquals("a�b", usv("a\uD83Cb"))
        assertEquals("a�", usv("a\uDF0C"))
        assertEquals("🌌", usv("🌌"))
        assertEquals("25.0", toFixed1((MAX_AUDIO_BYTES + 1) / 1048576.0))
        assertEquals(JsonPrimitive("x"), JsonPrimitive(usv("x")))
    }

    private companion object {
        const val INFERENCE_KEY = "sk-inference-key"
        val AUDIO = "fake opus".toByteArray()

        /** 480 bytes of 24 kHz 16-bit mono PCM: 10 ms. */
        val WAV: ByteArray = run {
            val data = 480
            val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            b.array()
        }
    }
}
