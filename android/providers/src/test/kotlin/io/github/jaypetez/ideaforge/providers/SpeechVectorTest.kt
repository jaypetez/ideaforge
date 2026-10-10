package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.vectors.Case
import io.github.jaypetez.ideaforge.core.vectors.SpeechProvidersFamilies
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.core.vectors.json
import io.github.jaypetez.ideaforge.core.vectors.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.MultipartReader
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `transcribe` and `tts` golden vectors (tools/fixtures/speech-vectors.mjs), replayed
 * against Transcribe.kt and Tts.kt. Requests are captured by an application interceptor, so
 * the real network policy still runs and nothing reaches a socket; the interceptor answers
 * with the reply the JS stub gave. Multipart boundaries are random, so a transcription is
 * compared part by part after parsing its serialized body, never byte for byte.
 */
class SpeechVectorTest {
    private val transcribe = Vectors.load("transcribe")
    private val tts = Vectors.load("tts")

    @TestFactory
    fun transcribeCases(): List<DynamicTest> = transcribe.cases.map { case ->
        DynamicTest.dynamicTest(case.name) { TRANSCRIBE_PORTS.getValue(case.fn)(case) }
    }

    @TestFactory
    fun ttsCases(): List<DynamicTest> = tts.cases.map { case ->
        DynamicTest.dynamicTest(case.name) { TTS_PORTS.getValue(case.fn)(case) }
    }

    @Test
    fun portsExactlyWhatCoreSaysIsPortedHere() {
        assertEquals(SpeechProvidersFamilies.TRANSCRIBE, TRANSCRIBE_PORTS.keys)
        assertEquals(SpeechProvidersFamilies.TTS, TTS_PORTS.keys)
        assertEquals(TRANSCRIBE_PORTS.keys, transcribe.cases.map { it.fn }.toSet(), "transcribe: recorded vs ported")
        assertEquals(TTS_PORTS.keys, tts.cases.map { it.fn }.toSet(), "tts: recorded vs ported")
    }

    /** Every bound is recorded from both sides; a vector set that lost them would pass quietly. */
    @Test
    fun theBoundsAreRecorded() {
        val names = transcribe.cases.map { it.name } + tts.cases.map { it.name }
        for (n in listOf(
            "audio at the 25 MB limit", "audio one byte over the limit", "input at the 4096-char limit",
            "input refused 2", "body at the size limit", "body over the limit with no declared length",
            "duration at 120 s", "duration one frame over 120 s", "prompt over 800 chars",
        )) assertTrue(n in names, "missing case $n")
    }
}

private typealias Replay = (Case) -> Unit

private fun bytesOf(desc: JsonElement?): ByteArray {
    val o = desc as? JsonObject ?: return ByteArray(0)
    o["base64"]?.let { return Base64.getDecoder().decode(str(it)) }
    val out = ByteArray(o["size"]!!.jsonPrimitive.int)
    val prefix = Base64.getDecoder().decode(str(o["prefix"]))
    prefix.copyInto(out)
    return out
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun s(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Answer the n-th request with the n-th scripted reply, recording every request. */
private class Stub(private val replies: List<JsonObject>) {
    val captured = mutableListOf<Request>()
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        captured += req
        val r = replies.getOrNull(captured.size - 1) ?: throw IOException("no scripted reply")
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            .code(r["status"]!!.jsonPrimitive.int).message(s(r, "statusText").orEmpty())
        (r["headers"] as? JsonObject)?.forEach { (k, v) -> b.header(k, str(v)) }
        b.body(bytesOf(r["body"]).toResponseBody(null)).build()
    }
    val clients = ClientFactory { policy -> ProviderHttpClients.create(policy).newBuilder().addInterceptor(interceptor).build() }
}

private fun assertThrows(case: Case, want: JsonObject, err: Throwable?) {
    val e = assertIs<ProviderException>(err, "${case.name}: expected a ProviderException, got $err")
    assertEquals(str(want["code"]), e.code, "${case.name}: code")
    assertEquals(str(want["message"]), e.message, "${case.name}: message")
    assertEquals((want["status"] as? JsonPrimitive)?.int, e.status, "${case.name}: status")
    assertEquals((want["retryAfterMs"] as? JsonPrimitive)?.long, e.retryAfterMs, "${case.name}: retryAfterMs")
}

private fun assertHeaders(case: Case, want: JsonElement?, req: Request) {
    // Ordered and exact: at the application-interceptor level OkHttp has added nothing yet.
    val expected = (want as JsonObject).map { (k, v) -> k to str(v) }
    assertEquals(expected, req.headers.map { it.first to it.second }, "${case.name}: headers")
}

private fun constantPort(values: Map<String, JsonElement>): Replay = { c ->
    val name = str(c.args[0])
    assertEquals(c.expect["value"], values[name] ?: error("${c.name}: no Kotlin constant $name"), c.name)
}

// ─────────────────────────────────────────────────────────────── transcribe

private val TRANSCRIBE_PORTS: Map<String, Replay> = mapOf(
    "constant" to constantPort(
        mapOf(
            "MAX_AUDIO_BYTES" to json(MAX_AUDIO_BYTES),
            "STT_PRESETS" to JsonObject(
                STT_PRESETS.mapValues { (_, p) ->
                    JsonObject(mapOf("label" to json(p.label), "baseUrl" to json(p.baseUrl), "model" to json(p.model), "note" to json(p.note)))
                },
            ),
        ),
    ),
    "transcribe" to ::replayTranscribe,
)

private fun replayTranscribe(case: Case) {
    val input = case.input as JsonObject
    val cfg = input["config"] as JsonObject
    val audio = input["audio"] as JsonObject
    val stub = Stub((input["replies"] as JsonArray).map { it as JsonObject })
    val outcome = runCatching {
        val t = createTranscriber(
            TranscriberConfig(s(cfg, "kind"), s(cfg, "apiKey").orEmpty(), s(cfg, "model"), s(cfg, "language"), s(cfg, "prompt")),
            stub.clients, retryDelay = {},
        )
        runBlocking { t.transcribe(bytesOf(audio["body"]), s(audio, "type")) }
    }

    val wantRequests = case.expect["requests"] as JsonArray
    assertEquals(wantRequests.size, stub.captured.size, "${case.name}: number of requests")
    for ((want, req) in wantRequests.map { it as JsonObject }.zip(stub.captured)) {
        assertEquals(str(want["url"]), req.url.toString(), "${case.name}: url")
        assertEquals(str(want["method"]), req.method, "${case.name}: method")
        assertHeaders(case, want["headers"], req)
        assertEquals(want["parts"], partsOf(req), "${case.name}: parts")
    }

    val throws = case.expect["throws"] as? JsonObject
    if (throws != null) return assertThrows(case, throws, outcome.exceptionOrNull())
    assertEquals(str(case.expect["value"]), outcome.getOrThrow(), "${case.name}: transcript")
}

private val NAME = Regex("""(?:^|;\s*)name="([^"]*)"""")
private val FILENAME = Regex("""(?:^|;\s*)filename="([^"]*)"""")

/** The serialized multipart body, parsed back into the shape the JS recorded off its FormData. */
private fun partsOf(req: Request): JsonArray {
    val body = assertIs<MultipartBody>(req.body)
    assertEquals("multipart/form-data", "${body.contentType().type}/${body.contentType().subtype}")
    val buffer = Buffer().also(body::writeTo)
    val parts = mutableListOf<JsonElement>()
    MultipartReader(buffer, body.boundary).use { reader ->
        while (true) {
            val part = reader.nextPart() ?: break
            val disposition = part.headers["Content-Disposition"].orEmpty()
            val name = NAME.find(disposition)!!.groupValues[1]
            val filename = FILENAME.find(disposition)?.groupValues?.get(1)
            val bytes = part.body.readByteArray()
            parts += if (filename == null) {
                assertNull(part.headers["Content-Type"], "text part $name carries no content type")
                JsonObject(mapOf("name" to json(name), "value" to json(String(bytes, Charsets.UTF_8))))
            } else {
                JsonObject(
                    mapOf(
                        "name" to json(name), "filename" to json(filename),
                        "contentType" to json(part.headers["Content-Type"]),
                        "size" to json(bytes.size), "sha256" to json(sha256(bytes)),
                    ),
                )
            }
        }
    }
    return JsonArray(parts)
}

// ─────────────────────────────────────────────────────────────── tts

private val TTS_PORTS: Map<String, Replay> = mapOf(
    "constant" to constantPort(
        mapOf(
            "MAX_TTS_INPUT_CHARS" to json(MAX_TTS_INPUT_CHARS),
            "MAX_TTS_AUDIO_BYTES" to json(MAX_TTS_AUDIO_BYTES),
            "MAX_TTS_AUDIO_SECONDS" to json(MAX_TTS_AUDIO_SECONDS),
            "TTS_DEADLINE_MS" to json(TTS_DEADLINE_MS),
            "TTS_PRESETS" to JsonObject(
                TTS_PRESETS.mapValues { (_, p) ->
                    JsonObject(
                        mapOf(
                            "label" to json(p.label), "baseUrl" to json(p.baseUrl), "url" to json(p.url),
                            "auth" to JsonObject(mapOf("scheme" to json(p.auth.scheme.name.lowercase()))),
                            "model" to json(p.model), "voices" to JsonArray(p.voices.map(::JsonPrimitive)),
                            "responseFormat" to json(p.responseFormat), "maxChars" to json(p.maxChars),
                        ),
                    )
                },
            ),
        ),
    ),
    "validateSpeechAudio" to { c ->
        val audio = Base64.getDecoder().decode(str(c.args[0]))
        val outcome = runCatching { validateSpeechAudio(audio) }
        val throws = c.expect["throws"]
        if (throws != null) {
            assertEquals(str(throws), assertIs<ProviderException>(outcome.exceptionOrNull(), c.name).message, c.name)
        } else {
            val i = outcome.getOrThrow()
            val got = JsonObject(mapOf("duration" to json(i.duration), "sampleRate" to json(i.sampleRate), "channels" to json(i.channels)))
            assertEquals(c.expect["value"], got, c.name)
        }
    },
    "synthesize" to ::replaySynthesize,
)

/**
 * Configurations the JS refuses that a typed Kotlin call cannot express: a Long deadline
 * cannot be 1.5. Each must be a recorded `config` refusal with no request.
 */
private val TYPED_AWAY = setOf("config refused 10")

/** A JS number argument as a Double; anything else (the string '1') is not a number. */
private fun number(e: JsonElement?, default: Double): Double = when {
    e == null || e is JsonNull -> default
    e is JsonPrimitive && !e.isString -> e.doubleOrNull ?: Double.NaN
    else -> Double.NaN
}

private fun replaySynthesize(case: Case) {
    val input = case.input as JsonObject
    val cfg = input["config"] as JsonObject
    val throws = case.expect["throws"] as? JsonObject
    if (case.name in TYPED_AWAY) {
        assertEquals("config", str(throws?.get("code")), case.name)
        assertTrue(case.expect["request"] is JsonNull, case.name)
        return
    }
    val stub = Stub(listOf(input["reply"] as JsonObject))
    val outcome = runCatching {
        val deadline = cfg["deadlineMs"]?.jsonPrimitive?.long ?: TTS_DEADLINE_MS
        val provider = createTtsProvider(
            apiKey = s(cfg, "apiKey"), voice = s(cfg, "voice") ?: "marin", rate = number(cfg["rate"], 1.0),
            deadlineMs = deadline, clients = stub.clients, now = { 0 },
        )
        assertTrue(!stub.clients.create(NetworkPolicy.Hosted(OPENAI_TTS.baseUrl)).followRedirects)
        val options = input["options"] as? JsonObject
        runBlocking { provider.synthesize(str(input["text"]), number(options?.get("rate"), provider.rate)) }
    }

    val want = case.expect["request"]
    if (want == null || want is JsonNull) {
        assertTrue(stub.captured.isEmpty(), "${case.name}: the JS sent no request")
    } else {
        val w = want as JsonObject
        val req = stub.captured.single()
        assertEquals(str(w["url"]), req.url.toString(), "${case.name}: url")
        assertEquals(str(w["method"]), req.method, "${case.name}: method")
        assertHeaders(case, w["headers"], req)
        // The bytes, not just the structure: key order and number spelling are the shape.
        val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
        assertEquals(Json.encodeToString(JsonElement.serializer(), w["body"]!!), body, "${case.name}: body")
    }

    if (throws != null) return assertThrows(case, throws, outcome.exceptionOrNull())
    val out = outcome.getOrThrow()
    val got = JsonObject(
        mapOf(
            "contentType" to json(out.contentType), "duration" to json(out.duration),
            "sampleRate" to json(out.sampleRate), "channels" to json(out.channels),
            "size" to json(out.audio.size), "sha256" to json(sha256(out.audio)),
        ),
    )
    assertEquals(case.expect["value"], got, "${case.name}: value")
}
