package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

// The Kotlin twin of src/providers/index.js: picking a provider, and describing the choices to
// the settings screen.
//
// Three things deliberately absent:
//
//   GitHub Models (models.github.ai) was retired on 2026-07-30 and now answers 410 Gone.
//
//   The Copilot chat endpoint is not a general-purpose inference API. Its only sanctioned
//   route (GitHub App based Copilot Extensions) was sunset in November 2025, and GitHub's
//   acceptable-use terms name proxy usage as grounds for disabling Copilot access. Groq's
//   free tier fills the same no-cost slot. Do not re-add either.
//
//   The `artifact` choice ("Claude (this viewer)") is the web app running inside a Claude
//   viewer. There is no viewer runtime in a native app, so there is nothing to port.

/** The three model tiers the runtime asks for. A missing tier is `null`, as a JS `undefined`. */
data class Tiers(val quick: String? = null, val default: String? = null, val complex: String? = null) {
    /** JS `tiers[modelTier]`: an unknown tier name has no model. */
    operator fun get(tier: String): String? = when (tier) {
        "quick" -> quick
        "default" -> default
        "complex" -> complex
        else -> null
    }
}

/** JS `||` on a string: empty counts as absent. */
internal fun String?.orAbsent(): String? = this?.takeIf { it.isNotEmpty() }

/**
 * The user's two model choices folded into a preset's tier map.
 *
 * The interesting line is `complex`, and the order in it is the entire reason the settings
 * screen asks twice. It prefers the PRESET's strong tier over the question model, so someone
 * who picks a cheap model to make a dozen questions cheap does not also, silently, lose the
 * strong model on the one call that reads the whole transcript back. Invert those two and
 * every wrap-up quietly runs on whatever was chosen for the turns — a worse export, with
 * nothing anywhere reporting that anything happened.
 *
 * A local preset has no tier map and therefore no strong tier, so there `complex` does fall
 * through to the question model and one filled box is still enough.
 */
fun resolveTiers(baseTiers: Tiers?, model: String? = null, wrapModel: String? = null): Tiers {
    val base = baseTiers ?: Tiers()
    val m = model.orAbsent()
    return Tiers(
        // `model || base.quick`: an empty base entry survives as itself, as in the JS.
        quick = m ?: base.quick,
        default = m ?: base.default,
        complex = wrapModel.orAbsent() ?: base.complex.orAbsent() ?: m,
    )
}

/** What the settings screen renders, in the order offered. */
data class ProviderChoice(
    val id: String,
    val label: String,
    val needsKey: Boolean,
    val local: Boolean,
    val picksModel: Boolean = true,
    val discoverModels: Boolean,
    val defaultBaseUrl: String?,
    val keyUrl: String?,
    val note: String?,
    val models: Tiers?,
)

/**
 * The choices, mirroring `PROVIDER_CHOICES` minus `artifact`. The local notes are native copy:
 * the web notes are about OLLAMA_ORIGINS and LM Studio's CORS switch, which gate a browser
 * origin. A native client sends no Origin header, so neither applies here; reaching a desktop
 * server from a phone is a matter of it listening on the network.
 */
val PROVIDER_CHOICES: List<ProviderChoice> = buildList {
    add(
        ProviderChoice(
            id = "anthropic", label = "Anthropic", needsKey = true, local = false, discoverModels = false,
            defaultBaseUrl = ANTHROPIC_API_ROOT, keyUrl = "https://console.anthropic.com/settings/keys",
            note = "Claude Haiku for questions, Sonnet for the wrap-up. Around 10-20 cents an interview.",
            models = ANTHROPIC_TIERS,
        ),
    )
    for ((id, p) in OPENAI_COMPAT_PRESETS) {
        add(
            ProviderChoice(
                id = id, label = p.label, needsKey = !p.local, local = p.local, discoverModels = p.discoverModels,
                defaultBaseUrl = p.baseUrl, keyUrl = p.keyUrl, note = p.note, models = p.tiers,
            ),
        )
    }
    add(
        ProviderChoice(
            id = "custom", label = "Another local server", needsKey = false, local = true, discoverModels = true,
            defaultBaseUrl = null, keyUrl = null,
            note = "Any OpenAI-compatible server on your own network: this device, a private address or a .local name.",
            models = null,
        ),
    )
}

/**
 * The models a provider choice is already known to run, deduped and sorted — what the settings
 * screen suggests before anyone has pressed Check. A local choice has no tier map and correctly
 * offers nothing.
 */
fun presetModels(tiers: Tiers?): List<String> =
    listOfNotNull(tiers?.quick.orAbsent(), tiers?.default.orAbsent(), tiers?.complex.orAbsent()).distinct().sorted()

fun presetModels(choice: ProviderChoice?): List<String> = presetModels(choice?.models)

data class InferenceDestination(val id: String, val kind: String, val label: String, val local: Boolean, val address: String? = null)

/** Uses the registry's existing local policy, never raw keystrokes or a credential. */
fun inferenceDestination(config: ProviderConfig): InferenceDestination {
    val choice = PROVIDER_CHOICES.firstOrNull { it.id == config.kind }
        ?: throw ProviderException(ProviderErrorCode.CONFIG, "Choose a supported inference provider.")
    if (!choice.local) return InferenceDestination(choice.id, choice.id, choice.label, false)
    val url = parse(config.baseUrl.orAbsent() ?: choice.defaultBaseUrl.orEmpty())
    LocalNetworkPolicy.check(url)
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        throw ProviderException(ProviderErrorCode.CONFIG, "Use a local server address without embedded credentials, query parameters or a fragment.")
    }
    val address = url.toString().trimEnd('/')
    return InferenceDestination("${choice.id}:$address", choice.id, choice.label, true, address)
}

/**
 * What every adapter exposes beyond the runtime's [Provider]: free text, the model list and a
 * key check. `listModels` is present and possibly empty rather than absent, so no call site
 * has to feature-detect.
 */
interface ModelProvider : Provider {
    val id: String
    val label: String

    /** One call returning the raw reply text and the model that actually answered. */
    suspend fun sample(parts: PromptParts, modelTier: String = "default"): Sampled

    suspend fun listModels(): List<String>

    /** Cheapest round trip that proves the configuration works; throws a ProviderException otherwise. */
    suspend fun validateKey(): Boolean
}

data class Sampled(val text: String, val modelTierApplied: String?)

/**
 * The settings, as the JS `createProvider(config)` receives them. `modelRequired` is
 * three-valued on purpose: `null` means "the default", which is on for a custom server;
 * `false` is the deliberate switch-off the model-list read uses to find out what to put in
 * the box.
 */
data class ProviderConfig(
    val kind: String?,
    val apiKey: String = "",
    val baseUrl: String? = null,
    val model: String? = null,
    val wrapModel: String? = null,
    val modelRequired: Boolean? = null,
)

/**
 * The transport seam. Production builds the policy's own client; a test hands back the same
 * client with an interceptor added, so the real policy checks still run.
 */
fun interface ClientFactory {
    fun create(policy: NetworkPolicy): OkHttpClient

    companion object {
        val DEFAULT = ClientFactory { ProviderHttpClients.create(it) }
    }
}

/**
 * Build the adapter for [config].
 *
 * Network policy is decided here, not in the adapters, as in the JS: a hosted preset gets
 * [NetworkPolicy.Hosted] on its own fixed origin and carries its key; Ollama, LM Studio and a
 * custom server get [NetworkPolicy.Local] and never carry one, whatever was pasted.
 *
 * Where the JS refuses a typed base URL with `assertLoopback`, this refuses it with
 * [LocalNetworkPolicy] (and re-checks every resolution through its Dns). The rule is wider —
 * a private network rather than this machine, because a phone's model server is on the
 * desktop — so the refusal message differs from the web app's for the same input.
 */
fun createProvider(
    config: ProviderConfig,
    clients: ClientFactory = ClientFactory.DEFAULT,
    retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    beforeSample: suspend () -> Unit = {},
): ModelProvider {
    val kind = config.kind.orAbsent() ?: throw ProviderException(ProviderErrorCode.CONFIG, "no provider selected")
    val preset = OPENAI_COMPAT_PRESETS[kind]
    val hostedRoot = when {
        kind == "anthropic" -> ANTHROPIC_API_ROOT
        preset != null && !preset.local -> preset.baseUrl
        else -> null
    }
    val typed = config.baseUrl.orAbsent()
    if (typed != null) {
        val url = parse(typed)
        if (hostedRoot != null) NetworkPolicy.Hosted(hostedRoot).check(url) else LocalNetworkPolicy.check(url)
    }

    // `model` and `wrapModel` become a tier map here and are deliberately NOT forwarded. The
    // adapters resolve `model || tiers[modelTier]`, so passing the scalar on as well would let
    // the question model win the wrap-up straight back. One resolution, in one place.
    fun http(policy: NetworkPolicy) = ProviderHttp(policy, clients.create(policy))

    return when {
        kind == "anthropic" -> AnthropicProvider(
            apiKey = config.apiKey,
            tiers = resolveTiers(ANTHROPIC_TIERS, config.model, config.wrapModel),
            http = http(NetworkPolicy.Hosted(ANTHROPIC_API_ROOT)),
            baseUrl = typed ?: ANTHROPIC_API_ROOT,
            retryDelay = retryDelay,
            beforeSample = beforeSample,
        )
        preset != null -> OpenAiCompatProvider(
            baseUrl = typed ?: preset.baseUrl,
            http = http(if (preset.local) NetworkPolicy.Local else NetworkPolicy.Hosted(preset.baseUrl)),
            apiKey = config.apiKey,
            preset = kind,
            tiers = resolveTiers(preset.tiers, config.model, config.wrapModel),
            modelRequired = config.modelRequired,
            retryDelay = retryDelay,
            beforeSample = beforeSample,
        )
        kind == "custom" -> OpenAiCompatProvider(
            baseUrl = typed.orEmpty(),
            http = http(NetworkPolicy.Local),
            apiKey = config.apiKey,
            local = true,
            tiers = resolveTiers(null, config.model, config.wrapModel),
            // The JS needs `rest.modelRequired ?? true` AFTER the spread because an own
            // property holding `undefined` wins a spread, and "Another local server" with an
            // empty model box once POSTed a body with no `model` key. Kotlin has no spread,
            // but the rule is the same: absent means required, only a deliberate `false` (the
            // model-list read) switches it off.
            modelRequired = config.modelRequired ?: true,
            retryDelay = retryDelay,
            beforeSample = beforeSample,
        )
        else -> throw ProviderException(ProviderErrorCode.CONFIG, "unknown provider: $kind")
    }
}

private fun parse(url: String): HttpUrl =
    url.toHttpUrlOrNull() ?: throw ProviderException(ProviderErrorCode.CONFIG, "$url is not an http(s) address")
