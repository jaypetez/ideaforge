package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.vectors.ProvidersFamily
import io.github.jaypetez.ideaforge.core.vectors.Port
import io.github.jaypetez.ideaforge.core.vectors.Vectors
import io.github.jaypetez.ideaforge.core.vectors.json
import io.github.jaypetez.ideaforge.core.vectors.replay
import io.github.jaypetez.ideaforge.core.vectors.str
import io.github.jaypetez.ideaforge.core.PromptParts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `providers` golden vectors, replayed here because :core cannot depend on this module.
 * :core registers the family and lists these functions as ported in :providers through the
 * shared [ProvidersFamily]; this test asserts the two lists are the same set, so a function
 * cannot be dropped from one side and silently skipped by both.
 */
class ProvidersVectorTest {
    private val family = Vectors.load("providers")

    @TestFactory
    fun portedCases(): List<DynamicTest> =
        family.cases.filter { it.fn in PORTS }.map { case ->
            DynamicTest.dynamicTest(case.name) {
                if (case.fn == "request") replayRequest(case) else replay(PORTS.getValue(case.fn), case)
            }
        }

    @Test
    fun portsExactlyWhatCoreSaysIsPortedHere() {
        assertEquals(ProvidersFamily.IN_PROVIDERS_MODULE, PORTS.keys)
    }

    @Test
    fun everyRecordedFunctionIsPortedHereOrOwedToAnIssue() {
        val fns = family.cases.map { it.fn }.toSet()
        assertEquals(fns, PORTS.keys + ProvidersFamily.PENDING.keys, "recorded vs ported + pending")
        assertTrue((PORTS.keys intersect ProvidersFamily.PENDING.keys).isEmpty())
    }

    @Test
    fun everyPortedFunctionHasCases() {
        for (fn in PORTS.keys) assertTrue(family.cases.any { it.fn == fn }, "$fn has no recorded case")
    }
}

private fun auth(e: JsonElement?): AuthDescriptor? {
    val o = e as? JsonObject ?: return null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.content
    val scheme = when (s("scheme")) {
        "bearer" -> AuthScheme.BEARER
        "header" -> AuthScheme.HEADER
        "query" -> AuthScheme.QUERY
        else -> AuthScheme.NONE
    }
    val extra = (o["extraHeaders"] as? JsonObject)?.mapValues { str(it.value) } ?: emptyMap()
    return AuthDescriptor(scheme, header = s("header"), prefix = s("prefix"), param = s("param"), extraHeaders = extra)
}

private val PORTS: Map<String, Port> = mapOf(
    "isLoopback" to { c -> json(isLoopback(str(c.args[0]))) },
    "applyAuth" to { c ->
        val opts = c.args.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        val headers = (opts["headers"] as? JsonObject)?.mapValues { str(it.value) } ?: emptyMap()
        val out = applyAuth(str(c.args[0]), auth(opts["auth"]), str(opts["apiKey"]), headers)
        JsonObject(mapOf("url" to json(out.url), "headers" to JsonObject(out.headers.mapValues { json(it.value) })))
    },
    "codeForStatus" to { c -> json(codeForStatus(c.args[0].jsonPrimitive.int)) },
    "isRetryableProviderError" to { c -> json(isRetryable(ProviderException(str(c.args[0]), "x"))) },
    // The JS passes a plain object shaped like an error; anything not a ProviderException is never retried.
    "isRetryable" to { c -> json(isRetryable(c.args[0])) },
    "retryAfterMs" to { c ->
        val headers = c.args[0] as JsonObject
        json(retryAfterMs(headers["retry-after"]?.let(::str), nowMs = 0))
    },
    "extractJson" to { c -> extractJson(c.args.getOrNull(0)?.let { if (it is JsonPrimitive && it.isString) it.content else null }) },
    "resolveTiers" to { c ->
        val choices = c.args.getOrNull(1) as? JsonObject
        tiersJson(resolveTiers(tiers(c.args[0]), choices?.get("model")?.let(::str), choices?.get("wrapModel")?.let(::str)))
    },
    "presetModels" to { c -> JsonArray(presetModels(tiers((c.args[0] as? JsonObject)?.get("models"))).map(::JsonPrimitive)) },
    "parseModelList" to { c -> JsonArray(parseModelList(c.args.getOrNull(0)).map(::JsonPrimitive)) },
    // Replayed by replayRequest, which compares the captured request as well as the result.
    "request" to { c -> error("${c.name} is replayed by replayRequest") },
)

private fun tiers(e: JsonElement?): Tiers? {
    val o = e as? JsonObject ?: return null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return Tiers(s("quick"), s("default"), s("complex"))
}

/** A JS tier map as JSON.stringify writes it: an `undefined` tier is no key at all. */
private fun tiersJson(t: Tiers): JsonObject = JsonObject(
    buildMap {
        t.quick?.let { put("quick", JsonPrimitive(it)) }
        t.default?.let { put("default", JsonPrimitive(it)) }
        t.complex?.let { put("complex", JsonPrimitive(it)) }
    },
)

/**
 * Cases whose JS refusal message names a browser rule the native app replaces. `request 10`
 * types https://example.com for a custom server: the web app refuses it with assertLoopback's
 * "is not on this machine"; natively the rule is LocalNetworkPolicy's "private network", so the
 * code (`config`) and the absence of any request are asserted, and the message is the native one.
 */
private val NATIVE_REFUSALS: Map<String, String> = mapOf(
    "request 10 custom " to LocalNetworkPolicy.refusal("example.com"),
)

/**
 * One whole provider call, as tools/fixtures/vectors.mjs records it: the registry builds the adapter
 * from `config`, the adapter's request is captured by an application interceptor (so the
 * real policy checks still run and nothing reaches a socket), the interceptor answers with the
 * reply the JS stub gave, and both the request and the result are compared.
 */
private fun replayRequest(case: io.github.jaypetez.ideaforge.core.vectors.Case) {
    val input = case.input as JsonObject
    val cfg = input["config"] as JsonObject
    fun s(k: String) = (cfg[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val config = ProviderConfig(
        kind = s("kind"), apiKey = s("apiKey").orEmpty(), baseUrl = s("baseUrl"),
        model = s("model"), wrapModel = s("wrapModel"),
        modelRequired = (cfg["modelRequired"] as? JsonPrimitive)?.booleanOrNull,
    )
    val parts = (input["parts"] as JsonObject).let { PromptParts(str(it["system"]), str(it["prefix"]), str(it["tail"])) }
    val tier = str(input["modelTier"])

    val captured = mutableListOf<Request>()
    val stub = Interceptor { chain ->
        val req = chain.request()
        captured += req
        val reply = if (req.url.host == "api.anthropic.com") ANTHROPIC_REPLY else OPENAI_REPLY
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply.toResponseBody("application/json".toMediaType())).build()
    }
    val clients = ClientFactory { policy -> ProviderHttpClients.create(policy).newBuilder().addInterceptor(stub).build() }
    val outcome = runCatching {
        runBlocking { createProvider(config, clients, retryDelay = {}).sampleJson(parts, tier) }
    }

    val throws = case.expect["throws"] as? JsonObject
    if (throws != null) {
        val err = assertIs<ProviderException>(outcome.exceptionOrNull(), "${case.name}: expected a throw")
        assertEquals(str(throws["code"]), err.code, case.name)
        val native = NATIVE_REFUSALS.entries.firstOrNull { case.name.startsWith(it.key) }?.value
        assertEquals(native ?: str(throws["message"]), err.message, case.name)
        assertTrue(captured.isEmpty(), "${case.name}: the JS sent no request")
        return
    }
    val result = outcome.getOrThrow()
    val expected = case.expect["request"] as JsonObject
    val req = captured.single()
    assertEquals(str(expected["url"]), req.url.toString(), "${case.name}: url")
    assertEquals(str(expected["method"]), req.method, "${case.name}: method")
    // Ordered and case-exact: at the application-interceptor level OkHttp has added nothing
    // (user-agent, content-length and accept-encoding come later, in its bridge), so the
    // request carries exactly the headers the JS sent, in the same order.
    val wantHeaders = (expected["headers"] as JsonObject).map { (k, v) -> k to str(v) }
    assertEquals(wantHeaders, req.headers.map { it.first to it.second }, "${case.name}: headers")
    if (s("kind") in setOf("ollama", "lmstudio", "custom")) {
        assertTrue(req.headers.names().none { it.lowercase() in CREDENTIAL_HEADERS }, "${case.name}: a local request carries no key")
    }
    // The bytes, not just the structure: key order and number spelling are part of the shape.
    val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
    assertEquals(Json.encodeToString(JsonElement.serializer(), expected["body"]!!), body, "${case.name}: body")

    val wantResult = case.expect["result"] as? JsonObject
    if (wantResult != null) {
        val got = JsonObject(mapOf("json" to (result.json ?: JsonNull), "modelTierApplied" to json(result.modelTierApplied)))
        assertEquals(wantResult, got, "${case.name}: result")
    }
}

// What the JS stub in tools/fixtures/vectors.mjs answered.
private const val ANTHROPIC_REPLY = """{"content":[{"type":"text","text":"{\"ok\":true}"}],"stop_reason":"end_turn"}"""
private const val OPENAI_REPLY = """{"choices":[{"message":{"content":"{\"ok\":true}"},"finish_reason":"stop"}],"model":"served-model"}"""
