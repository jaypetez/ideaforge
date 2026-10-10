package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject

// The Kotlin twin of src/providers/openaiCompat.js: one adapter for every OpenAI-shaped
// `/chat/completions` endpoint — OpenAI, Groq, OpenRouter, and a local Ollama or LM Studio.
// They differ in base URL, model ids, auth and the name of the output-budget parameter, all
// of which are data on the preset rather than branches in here.
//
// What is NOT ported is the JS `opaqueFailure`. In a browser, api.openai.com rejects an
// invalid key from an auth layer that sends no CORS headers, so the page sees an opaque
// `TypeError` with no status and has to guess "probably the key". A native client has no
// CORS: the 401 always arrives with its status and body, and httpError reports it as `auth`
// directly. A failure with no response at all really is the network (or the local guard),
// which callFailure already says.

/**
 * One preset. `tokenParam` is per-preset because the output-budget parameter's name is a
 * per-vendor fact: every GPT-5-era OpenAI model rejects `max_tokens` outright and wants
 * `max_completion_tokens`, while Groq, Ollama and LM Studio only know the old name.
 * OpenRouter normalises it across everything it fronts.
 *
 * `maxTokens` is doubled where a model may spend budget before writing a character (OpenAI's
 * reasoning models; a local model that thinks out loud): a reply cut off by the cap arrives
 * as finish_reason 'length', which is a `bad_response`, which is the question bank.
 */
data class OpenAiCompatPreset(
    val label: String,
    val baseUrl: String,
    val tokenParam: String = "max_tokens",
    val maxTokens: Int = 4096,
    val local: Boolean = false,
    val discoverModels: Boolean = true,
    val modelRequired: Boolean = false,
    val tiers: Tiers?,
    val keyUrl: String? = null,
    val note: String? = null,
    val auth: AuthDescriptor = AUTH_BEARER,
)

val OPENAI_COMPAT_PRESETS: Map<String, OpenAiCompatPreset> = linkedMapOf(
    "openai" to OpenAiCompatPreset(
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        tokenParam = "max_completion_tokens",
        maxTokens = 8192,
        tiers = Tiers(quick = "gpt-5-nano", default = "gpt-5-nano", complex = "gpt-5-mini"),
        keyUrl = "https://platform.openai.com/api-keys",
    ),
    "groq" to OpenAiCompatPreset(
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        tiers = Tiers(quick = "openai/gpt-oss-20b", default = "openai/gpt-oss-20b", complex = "openai/gpt-oss-120b"),
        keyUrl = "https://console.groq.com/keys",
        note = "Free tier, no card required.",
    ),
    "openrouter" to OpenAiCompatPreset(
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        tiers = Tiers(quick = "openai/gpt-5-nano", default = "openai/gpt-5-nano", complex = "openai/gpt-5-mini"),
        keyUrl = "https://openrouter.ai/keys",
    ),
    // No tier map for a local server, deliberately: a guessed model id 404s for everyone who
    // has not pulled exactly that model, and the installed list is one GET /models away.
    "ollama" to OpenAiCompatPreset(
        label = "Ollama (local)",
        baseUrl = "http://localhost:11434/v1",
        maxTokens = 8192,
        local = true,
        modelRequired = true,
        tiers = null,
        note = "Pick a model below. To reach Ollama on another computer, it has to listen on the network (OLLAMA_HOST=0.0.0.0).",
    ),
    "lmstudio" to OpenAiCompatPreset(
        label = "LM Studio (local)",
        baseUrl = "http://localhost:1234/v1",
        maxTokens = 8192,
        local = true,
        modelRequired = true,
        tiers = null,
        note = "Start LM Studio's server, then pick a model below.",
    ),
)

/**
 * Never trust the list. Ollama and LM Studio answer `/v1/models` with `{data:[{id}]}`,
 * Ollama's native `/api/tags` answers `{models:[{name}]}`, and a proxy in between could answer
 * anything. Keep only what survives: trimmed, non-empty, at most 120 UTF-16 units, deduped,
 * at most 100, sorted by UTF-16 code unit as JS `sort()` does.
 */
fun parseModelList(body: JsonElement?): List<String> {
    val rows: List<JsonElement> = when {
        body is JsonArray -> body
        body is JsonObject && body["data"] is JsonArray -> body["data"] as JsonArray
        body is JsonObject && body["models"] is JsonArray -> body["models"] as JsonArray
        else -> emptyList()
    }
    val names = ArrayList<String>()
    for (row in rows) {
        val raw = stringOf(row) ?: (row as? JsonObject)?.let { stringOf(it["id"]) ?: stringOf(it["name"]) } ?: ""
        val name = jsTrim(raw)
        if (name.isNotEmpty() && name.length <= 120 && name !in names) names += name
        if (names.size >= 100) break
    }
    return names.sorted()
}

private fun stringOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * @param baseUrl the root `/chat/completions` and `/models` are appended to
 * @param preset a key of [OPENAI_COMPAT_PRESETS], or null for a hand-typed server
 * @param model a direct caller's override of every tier; [createProvider] never passes it
 * @param local whether this is a local server; defaults to the preset's flag. A local adapter
 *   needs no key, and its [ProviderHttp] policy drops one if given.
 * @param modelRequired null means the preset's default (on for Ollama and LM Studio)
 */
class OpenAiCompatProvider(
    baseUrl: String,
    private val http: ProviderHttp,
    private val apiKey: String = "",
    preset: String? = null,
    tiers: Tiers? = null,
    private val model: String? = null,
    local: Boolean? = null,
    modelRequired: Boolean? = null,
    tokenParam: String? = null,
    maxTokens: Int? = null,
    private val retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : ModelProvider {
    private val p: OpenAiCompatPreset? = preset?.let {
        OPENAI_COMPAT_PRESETS[it] ?: throw ProviderException(ProviderErrorCode.CONFIG, "unknown preset: $it")
    }
    private val root = trimSlash(baseUrl.ifEmpty { p?.baseUrl.orEmpty() })
    private val tiers: Tiers = tiers ?: p?.tiers ?: Tiers()
    private val auth = p?.auth ?: AUTH_BEARER
    private val tokenParam = tokenParam ?: p?.tokenParam ?: "max_tokens"
    private val maxTokens = maxTokens ?: p?.maxTokens ?: 4096
    private val hostLabel = hostOf(root)

    override val id = preset ?: "openai-compat"
    override val label = p?.label ?: root
    val note: String? = p?.note

    init {
        if (root.isEmpty()) throw ProviderException(ProviderErrorCode.CONFIG, "OpenAI-compatible provider needs a base URL")
        val isLocal = local ?: p?.local ?: isLoopback(root)
        if (apiKey.isEmpty() && !isLocal) throw ProviderException(ProviderErrorCode.CONFIG, "this provider needs an API key")
        // `tiers.default` and not just `model`: createProvider resolves the user's choices into
        // a tier map and stops forwarding the scalar, so the model a user typed for a local
        // server arrives here as the default tier.
        if ((modelRequired ?: p?.modelRequired ?: false) && model.orAbsent() == null && this.tiers.default.orAbsent() == null) {
            throw ProviderException(
                ProviderErrorCode.CONFIG,
                "$label needs a model name — pick one in Settings, or press Check the connection to read the list off the server.",
            )
        }
    }

    /** The request body, in the JS key order (the vectors compare the bytes). */
    internal fun body(parts: PromptParts, resolved: String?, json: Boolean): JsonObject = buildJsonObject {
        resolved?.let { put("model", it) }
        put(tokenParam, maxTokens)
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", parts.system) }
            addJsonObject { put("role", "user"); put("content", "${parts.prefix}\n${parts.tail}") }
        }
        // json_object rather than a strict json_schema: the turn schema keys `coverage` by
        // dimension id, and strict mode would force the model to restate all seven every turn.
        // parseTurnResult does the real validation anyway.
        if (json) putJsonObject("response_format") { put("type", "json_object") }
    }

    private suspend fun getJson(path: String): JsonElement {
        val request = http.request("$root$path", auth, apiKey).get().build()
        val text = http.executeText(request, hostLabel)
        return runCatching { Json.parseToJsonElement(text) }.getOrDefault(JsonObject(emptyMap()))
    }

    private suspend fun call(parts: PromptParts, modelTier: String, json: Boolean): Sampled {
        val resolved = model.orAbsent() ?: tiers[modelTier].orAbsent() ?: tiers.default.orAbsent()
        val request = http.request("$root/chat/completions", auth, apiKey, mapOf("content-type" to "application/json"))
            .post(jsonBody(body(parts, resolved, json)))
            .build()
        val body = readJsonReply(http.executeText(request, hostLabel), hostLabel)

        val choice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = (choice?.get("message") as? JsonObject)?.get("content")
        val text = stringOf(content) ?: ""
        if (stringOf(choice?.get("finish_reason")) == "length") {
            throw ProviderException(ProviderErrorCode.BAD_RESPONSE, "the reply was cut off by the token limit")
        }
        return Sampled(text, stringOf(body["model"]).orAbsent() ?: resolved)
    }

    override suspend fun sample(parts: PromptParts, modelTier: String): Sampled =
        withRetry(delay = retryDelay) { call(parts, modelTier, json = false) }

    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        val out = withRetry(delay = retryDelay) { call(parts, modelTier, json = true) }
        return SampleResult(extractJson(out.text), out.text, out.modelTierApplied)
    }

    /** What the server will actually answer to, rather than what we guessed it might. */
    override suspend fun listModels(): List<String> = parseModelList(getJson("/models"))

    /** `GET /models`: proves the key (or the local server) without spending a token. */
    override suspend fun validateKey(): Boolean {
        getJson("/models")
        return true
    }
}
