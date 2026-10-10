package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.core.withTts
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.providers.ClientFactory
import io.github.jaypetez.ideaforge.providers.ProviderHttpClients
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The app's three routes to a hosted speech request (live speech, the Settings check, the
 * Whisper fallback) each sign with their own key and never with the inference key: the #80
 * SpeechClientsTest assertions, one layer up, through a stub interceptor. No socket opens.
 */
class HostedKeysTest {
    private val captured = mutableListOf<Request>()
    private val clients = ClientFactory {
        ProviderHttpClients.create(it).newBuilder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            captured += req
            val (type, body) = if (req.url.encodedPath.endsWith("/audio/speech")) {
                "audio/wav" to pcmWav(ByteArray(3200), 16_000)
            } else {
                "application/json" to """{"text":"heard over"}""".toByteArray()
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("content-type", type).body(body.toResponseBody(type.toMediaType())).build()
        }).build()
    }

    private fun obj(vararg p: Pair<String, Any>) =
        JsonObject(p.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    private val ring = withStt(
        withTts(
            withCreds(emptyKeyring(), "openai", obj("apiKey" to INFERENCE)),
            obj("kind" to "openai", "apiKey" to SPEECH, "voice" to "cedar", "verified" to true),
        ),
        obj("kind" to "openai", "apiKey" to STT),
    )
    private val prefs = Prefs(hostedSpeechAllowed = true, hostedTranscriptionAllowed = true)

    private fun auth() = captured.map { it.header("authorization") }

    @Test
    fun liveSpeechSignsWithTheSpeechKeyOnly() = runBlocking {
        hostedSynth(ring, prefs, clients)!!.synthesize("Hello.", 1.0)
        assertEquals(listOf("Bearer $SPEECH"), auth())
        assertFalse(captured.any { INFERENCE in it.headers.toString() })
    }

    @Test
    fun theCheckSignsWithTheKeyBeingChecked() = runBlocking {
        checkSynth("sk-new-speech-key", "marin", 1.0, clients).synthesize("Hello.", 1.0)
        assertEquals(listOf("Bearer sk-new-speech-key"), auth())
    }

    @Test
    fun theWhisperFallbackSignsWithTheTranscriptionKeyOnly() = runBlocking {
        val mic = FakePcm(speech())
        val text = whisperFrom(ring, prefs, clients) { mic }!!.listenForDrive("over", 8000)
        assertEquals("heard over", text)
        assertEquals(listOf("Bearer $STT"), auth())
        assertFalse(captured.any { INFERENCE in it.headers.toString() || SPEECH in it.headers.toString() })
    }

    private companion object {
        const val INFERENCE = "sk-inference-key"
        const val SPEECH = "sk-speech-key"
        const val STT = "sk-transcription-key"
    }
}
