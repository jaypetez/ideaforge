package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The adapters against a real socket (MockWebServer on 127.0.0.1) and the registry against a
 * capturing interceptor. No public network and no real sleeping: retries use an injected delay.
 */
class AdaptersTest {
    private lateinit var server: MockWebServer
    private val parts = PromptParts("SYS", "PRE", "TAIL")

    @BeforeTest fun start() { server = MockWebServer(); server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
    @AfterTest fun stop() { server.close() }

    private val root get() = "http://127.0.0.1:${server.port}/v1"

    /** Production local-client settings with a policy that lets the plain-http mock carry a key. */
    private fun hostedLikeHttp(): ProviderHttp {
        val policy = object : NetworkPolicy() {
            override val carriesCredentials = true
            override fun check(url: HttpUrl) {}
        }
        return ProviderHttp(policy, ProviderHttpClients.create(NetworkPolicy.Local))
    }

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun openAiReply(content: String = "{\"ok\":true}", finish: String = "stop") =
        reply("""{"choices":[{"message":{"content":${JsonPrimitive(content)}},"finish_reason":"$finish"}],"model":"served"}""")
    private fun bodyOf(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body!!.utf8()).jsonObject

    private fun compat(preset: String, http: ProviderHttp = hostedLikeHttp(), apiKey: String = "sk-test", tiers: Tiers? = null) =
        OpenAiCompatProvider(baseUrl = root, http = http, apiKey = apiKey, preset = preset, tiers = tiers, retryDelay = {})

    @Test
    fun theOutputBudgetParameterIsPerVendor() = runBlocking {
        for ((preset, param, budget) in listOf(
            Triple("openai", "max_completion_tokens", 8192), Triple("groq", "max_tokens", 4096),
            Triple("openrouter", "max_tokens", 4096), Triple("ollama", "max_tokens", 8192), Triple("lmstudio", "max_tokens", 8192),
        )) {
            openAiReply()
            val tiers = if (preset == "ollama" || preset == "lmstudio") Tiers("m", "m", "m") else null
            compat(preset, tiers = tiers).sampleJson(parts, "default")
            val body = bodyOf(server.takeRequest())
            assertEquals(budget, body[param]?.jsonPrimitive?.content?.toInt(), "$preset sends $param")
            val other = if (param == "max_tokens") "max_completion_tokens" else "max_tokens"
            assertNull(body[other], "$preset never sends $other")
        }
    }

    @Test
    fun theWireCarriesExactlyApplicationJson() = runBlocking {
        openAiReply()
        compat("groq").sampleJson(parts, "quick")
        val r = server.takeRequest()
        // Not "application/json; charset=utf-8", which the String body overload would send.
        assertEquals("application/json", r.headers["content-type"])
        assertEquals("Bearer sk-test", r.headers["authorization"])
        assertEquals("/v1/chat/completions", r.url.encodedPath)
        assertEquals("""{"type":"json_object"}""", bodyOf(r)["response_format"].toString())
        assertEquals("""[{"role":"system","content":"SYS"},{"role":"user","content":"PRE\nTAIL"}]""", bodyOf(r)["messages"].toString())
    }

    @Test
    fun aReplyCutOffByTheTokenLimitIsABadResponseAndNotRetried() {
        openAiReply(content = "{\"half", finish = "length")
        val e = assertFailsWith<ProviderException> { runBlocking { compat("openai").sampleJson(parts, "default") } }
        assertEquals("bad_response", e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anAnthropicRefusalIsABadResponse() {
        reply("""{"content":[{"type":"text","text":"{}"}],"stop_reason":"refusal"}""")
        val p = AnthropicProvider(apiKey = "sk-ant", http = hostedLikeHttp(), baseUrl = root, retryDelay = {})
        val e = assertFailsWith<ProviderException> { runBlocking { p.sampleJson(parts, "default") } }
        assertEquals("bad_response", e.code)
        assertEquals("the model declined to answer this turn", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anthropicSendsItsHeadersAndJoinsTextBlocks() = runBlocking {
        reply("""{"content":[{"type":"thinking","text":"x"},{"type":"text","text":"{\"a\":"},{"type":"text","text":"1}"}],"stop_reason":"end_turn"}""")
        val p = AnthropicProvider(apiKey = "sk-ant", http = hostedLikeHttp(), baseUrl = root, retryDelay = {})
        val out = p.sampleJson(parts, "complex")
        assertEquals("""{"a":1}""", out.json.toString())
        assertEquals("claude-sonnet-5", out.modelTierApplied)
        val r = server.takeRequest()
        assertEquals("/v1/messages", r.url.encodedPath)
        assertEquals("sk-ant", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertNull(r.headers["authorization"])
        assertEquals("claude-sonnet-5", bodyOf(r)["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRetryableFailureIsRetriedWithoutSleeping() = runBlocking {
        reply("""{"error":{"message":"busy"}}""", code = 529)
        openAiReply()
        val waits = mutableListOf<Long>()
        val p = OpenAiCompatProvider(baseUrl = root, http = hostedLikeHttp(), apiKey = "k", preset = "groq", retryDelay = { waits += it })
        assertEquals("""{"ok":true}""", p.sampleJson(parts, "default").json.toString())
        assertEquals(2, server.requestCount)
        assertEquals(1, waits.size)
    }

    @Test
    fun validateKeyAndListModelsUseGetModels() = runBlocking {
        reply("""{"data":[{"id":"b"},{"id":"a"}]}""")
        reply("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}""")
        val p = compat("openai")
        assertTrue(p.validateKey())
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/v1/models", r.url.encodedPath)
        assertEquals("Bearer sk-test", r.headers["authorization"])
        assertEquals(listOf("a", "b"), p.listModels())
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aBadKeyIsReportedAsAuthWithItsStatus() {
        // In a browser this 401 arrives with no CORS headers and has to be guessed at; natively
        // the status is always visible, so it is simply `auth`.
        reply("""{"error":{"message":"Incorrect API key"}}""", code = 401)
        val e = assertFailsWith<ProviderException> { runBlocking { compat("openai").validateKey() } }
        assertEquals("auth", e.code)
        assertEquals(401, e.status)
    }

    @Test
    fun aLocalServerThroughTheRegistryIsKeylessEvenWhenAKeyIsPasted() = runBlocking {
        reply("""{"models":[{"name":"qwen"}]}""")
        val p = createProvider(ProviderConfig(kind = "ollama", baseUrl = root, apiKey = "pasted", model = "qwen"), retryDelay = {})
        assertTrue(p.validateKey())
        val r = server.takeRequest()
        assertEquals("/v1/models", r.url.encodedPath)
        assertNull(r.headers["authorization"], "a local provider never carries a key")
    }

    // ---- the registry, against a capturing interceptor ----

    private class Capture(private val status: Int = 200) {
        val requests = mutableListOf<Request>()
        val clients = ClientFactory { policy ->
            ProviderHttpClients.create(policy).newBuilder().addInterceptor(Interceptor { chain ->
                val req = chain.request()
                requests += req
                val body = if (req.url.host == "api.anthropic.com") """{"content":[{"type":"text","text":"{}"}],"stop_reason":"end_turn"}"""
                else """{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}"""
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("Scripted response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }).build()
        }
        fun model(i: Int = requests.lastIndex): String {
            val buf = Buffer().also { requests[i].body!!.writeTo(it) }
            return Json.parseToJsonElement(buf.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content
        }
    }

    @Test
    fun inferenceAuthorizationIsRequiredBeforeDispatchButNotForAConnectionCheck() = runBlocking {
        for (kind in listOf("anthropic", "openai", "groq")) {
            val capture = Capture()
            var checks = 0
            val provider = createProvider(ProviderConfig(kind, apiKey = "scripted"), capture.clients,
                beforeSample = {
                    checks++
                    throw ProviderException(ProviderErrorCode.CONSENT, "scripted missing agreement")
                })
            val denied = assertFailsWith<ProviderException> { provider.sampleJson(parts, "default") }
            assertEquals("consent", denied.code)
            assertEquals(0, capture.requests.size, "$kind never dispatches private prompt parts")
            assertTrue(provider.validateKey())
            assertEquals(1, capture.requests.size, "$kind connection checks remain independent")
            assertEquals(1, checks, "$kind checks never invoke inference authorization")
            val body = capture.requests.single().body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            assertFalse(body.contains("SYS") || body.contains("PRE") || body.contains("TAIL"))
        }
    }

    @Test
    fun everyInferenceRetryRechecksItsBoundPermission() = runBlocking {
        for (kind in listOf("anthropic", "openai", "groq")) {
            val capture = Capture(529)
            var allowed = true
            var checks = 0
            val provider = createProvider(ProviderConfig(kind, apiKey = "scripted"), capture.clients,
                retryDelay = { allowed = false },
                beforeSample = {
                    checks++
                    if (!allowed) throw ProviderException(ProviderErrorCode.CONSENT, "scripted withdrawal")
                })
            val denied = assertFailsWith<ProviderException> { provider.sampleJson(parts, "default") }
            assertEquals("consent", denied.code)
            assertEquals(1, capture.requests.size, "$kind must not send its old prompt again")
            assertEquals(2, checks, "$kind rechecks after the retry delay")
        }
    }

    @Test
    fun aQuestionModelOverrideNeverReachesTheComplexTier() = runBlocking {
        for ((kind, strong) in listOf("anthropic" to "claude-sonnet-5", "openai" to "gpt-5-mini",
            "groq" to "openai/gpt-oss-120b", "openrouter" to "openai/gpt-5-mini")) {
            val c = Capture()
            val p = createProvider(ProviderConfig(kind = kind, apiKey = "k", model = "cheap-question-model"), c.clients, retryDelay = {})
            p.sampleJson(parts, "quick")
            assertEquals("cheap-question-model", c.model(), "$kind quick")
            p.sampleJson(parts, "default")
            assertEquals("cheap-question-model", c.model(), "$kind default")
            p.sampleJson(parts, "complex")
            assertEquals(strong, c.model(), "$kind: the wrap-up keeps the preset's strong model")
        }
    }

    @Test
    fun aWrapModelWinsTheComplexTierAndALocalServerFallsThroughToTheQuestionModel() = runBlocking {
        val c = Capture()
        createProvider(ProviderConfig(kind = "openai", apiKey = "k", model = "q", wrapModel = "w"), c.clients).sampleJson(parts, "complex")
        assertEquals("w", c.model())
        createProvider(ProviderConfig(kind = "custom", baseUrl = "http://127.0.0.1:8080/v1", model = "only"), c.clients).sampleJson(parts, "complex")
        assertEquals("only", c.model())
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", c.requests.last().url.toString())
    }

    @Test
    fun anEmptyModelBoxOnACustomServerIsRefused() {
        for (model in listOf(null, "")) {
            val e = assertFailsWith<ProviderException> {
                createProvider(ProviderConfig(kind = "custom", baseUrl = "http://127.0.0.1:8080/v1", model = model))
            }
            assertEquals("config", e.code)
            assertTrue(e.message!!.contains("needs a model name"))
        }
        // The model-list read switches the requirement off deliberately, and only it can.
        createProvider(ProviderConfig(kind = "custom", baseUrl = "http://127.0.0.1:8080/v1", modelRequired = false))
        assertFailsWith<ProviderException> { createProvider(ProviderConfig(kind = "ollama")) }
        createProvider(ProviderConfig(kind = "ollama", modelRequired = false))
    }

    @Test
    fun theRegistryRefusesWhatThePolicyRefuses() {
        fun refused(config: ProviderConfig) = assertFailsWith<ProviderException> { createProvider(config) }.code
        assertEquals("config", refused(ProviderConfig(kind = null)))
        assertEquals("config", refused(ProviderConfig(kind = "github")))
        assertEquals("config", refused(ProviderConfig(kind = "copilot")))
        assertEquals("config", refused(ProviderConfig(kind = "anthropic")))          // no key
        assertEquals("config", refused(ProviderConfig(kind = "groq")))               // no key
        assertEquals("config", refused(ProviderConfig(kind = "custom", baseUrl = "http://localhost.evil.com/v1", model = "m")))
        assertEquals("config", refused(ProviderConfig(kind = "ollama", baseUrl = "http://8.8.8.8:11434/v1", model = "m")))
        // A hosted preset only talks to its own origin, whatever is typed.
        assertEquals("config", refused(ProviderConfig(kind = "openai", apiKey = "k", baseUrl = "https://evil.example/v1")))
        assertEquals("config", refused(ProviderConfig(kind = "anthropic", apiKey = "k", baseUrl = "http://127.0.0.1:1/v1")))
        // A LAN server is fine natively, where the web app would demand loopback.
        createProvider(ProviderConfig(kind = "ollama", baseUrl = "http://192.168.1.20:11434/v1", model = "m"))
    }

    @Test
    fun theChoicesNeverReAddRetiredOrUnsanctionedProviders() {
        val ids = PROVIDER_CHOICES.map { it.id }
        assertEquals(listOf("anthropic", "openai", "groq", "openrouter", "ollama", "lmstudio", "custom"), ids)
        assertFalse(ids.any { "github" in it || "copilot" in it })
        assertTrue(PROVIDER_CHOICES.filter { it.local }.none { it.needsKey })
        assertEquals(listOf("claude-haiku-4-5", "claude-sonnet-5"), presetModels(PROVIDER_CHOICES.first()))
    }
}
