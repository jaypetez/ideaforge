package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.core.utf8Length
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The Kotlin twin of src/providers/anthropic.js: the Messages API with the user's own key.

const val ANTHROPIC_API_ROOT = "https://api.anthropic.com/v1"
private const val MESSAGES_PATH = "/messages"
private const val API_VERSION = "2023-06-01"

/**
 * How Anthropic signs a request. `anthropic-dangerous-direct-browser-access` is a browser
 * fact — the web app's CORS preflight only succeeds when that header NAME is requested — and
 * a native client needs no preflight at all. It is sent anyway because the golden vectors pin
 * the request headers byte for byte, the API accepts it from any client, and one request
 * shape across both apps is worth more than one header.
 */
val ANTHROPIC_AUTH = AuthDescriptor(
    scheme = AuthScheme.HEADER,
    header = "x-api-key",
    extraHeaders = linkedMapOf(
        "anthropic-version" to API_VERSION,
        "anthropic-dangerous-direct-browser-access" to "true",
    ),
)

val ANTHROPIC_TIERS = Tiers(quick = "claude-haiku-4-5", default = "claude-haiku-4-5", complex = "claude-sonnet-5")

/**
 * Below Haiku 4.5's 4,096-token minimum a cache breakpoint is silently ignored, so one is only
 * spent once the prefix is plausibly past it. ~3.6 bytes/token is deliberately conservative.
 * Counted with the JS rule ([utf8Length]), so the marker appears on exactly the same prefixes.
 */
const val MIN_CACHEABLE_BYTES = 16 * 1024

/**
 * @param model a direct caller's override of every tier. [createProvider] never passes it: it
 *   resolves the user's choices into [tiers] so the question model cannot win the wrap-up.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val http: ProviderHttp,
    private val tiers: Tiers = ANTHROPIC_TIERS,
    private val model: String? = null,
    baseUrl: String = ANTHROPIC_API_ROOT,
    private val maxTokens: Int = 4096,
    private val retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val beforeSample: suspend () -> Unit = {},
) : ModelProvider {
    override val id = "anthropic"
    override val label = "Anthropic"

    private val endpoint = "${trimSlash(baseUrl)}$MESSAGES_PATH"

    init {
        if (apiKey.isEmpty()) throw ProviderException(ProviderErrorCode.CONFIG, "Anthropic provider needs an API key")
    }

    /** The request body, in the JS key order (the vectors compare the bytes). */
    internal fun body(parts: PromptParts, resolved: String?): JsonObject = buildJsonObject {
        resolved?.let { put("model", it) }
        put("max_tokens", maxTokens)
        putJsonArray("system") { addJsonObject { put("type", "text"); put("text", parts.system) } }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", parts.prefix)
                        if (utf8Length(parts.prefix) >= MIN_CACHEABLE_BYTES) putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                    addJsonObject { put("type", "text"); put("text", parts.tail) }
                }
            }
        }
    }

    private suspend fun call(parts: PromptParts, modelTier: String): Sampled {
        val resolved = model.orAbsent() ?: tiers[modelTier].orAbsent() ?: tiers.default.orAbsent()
        val request = http.request(endpoint, ANTHROPIC_AUTH, apiKey, mapOf("content-type" to "application/json"))
            .post(jsonBody(body(parts, resolved)))
            .build()
        val body = readJsonReply(http.executeText(request, label), label)

        val content = body["content"] as? JsonArray
        val text = content.orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "text" }
            .joinToString("") { jsText(it["text"]) }

        if ((body["stop_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "refusal") {
            throw ProviderException(ProviderErrorCode.BAD_RESPONSE, "the model declined to answer this turn")
        }
        return Sampled(text, resolved)
    }

    override suspend fun sample(parts: PromptParts, modelTier: String): Sampled =
        withRetry(delay = retryDelay) { beforeSample(); call(parts, modelTier) }

    /** `cache` is ignored, as in the JS: the cache marker is decided by prefix size alone. */
    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        val out = sample(parts, modelTier)
        return SampleResult(extractJson(out.text), out.text, out.modelTierApplied)
    }

    /** Anthropic publishes no catalogue this app reads; the tier map is the answer. */
    override suspend fun listModels(): List<String> = emptyList()

    override suspend fun validateKey(): Boolean {
        call(PromptParts(system = "Reply with the single word ok.", prefix = "ping", tail = "ok"), "quick")
        return true
    }
}
