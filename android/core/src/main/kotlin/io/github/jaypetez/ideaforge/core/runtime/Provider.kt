package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.PromptParts
import kotlinx.serialization.json.JsonElement

/**
 * What the runtime needs from a model provider, and nothing more. The real adapters live in
 * :providers; tests and the golden vectors use scripted ones. Injected, never reached for,
 * so the runtime stays pure (the same rule src/runtime follows).
 */
interface Provider {
    /**
     * One structured call. `json` is whatever the reply parsed to, trusted for nothing:
     * parseTurnResult and parseSynthesisResult validate every field.
     *
     * @throws ProviderException for a failed call; code "aborted" means the user cancelled.
     */
    suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean = true): SampleResult
}

data class SampleResult(val json: JsonElement?, val text: String = "", val modelTierApplied: String? = null)

/**
 * Any failure of a provider call as the runtime sees it. The JS catches every error and
 * treats one without a code as 'error'; an adapter that lets an IOException or a decoding
 * error through must still end in a bank question, never a dead end. Cancellation is not
 * a failure: it propagates, so a cancelled scope is never handed a fallback.
 */
internal fun asProviderFailure(e: Exception): ProviderException = when (e) {
    is kotlin.coroutines.cancellation.CancellationException -> throw e
    is ProviderException -> if (e.code == "consent") throw e else e
    else -> ProviderException(null, e.message ?: e.toString(), e)
}

/**
 * A failed provider call. `code` is the JS ProviderError code (network, auth, aborted...);
 * `status` and `retryAfterMs` mirror the JS ProviderError fields of the same names, filled
 * by the HTTP layer in :providers and null everywhere else.
 */
class ProviderException(
    val code: String?,
    message: String,
    cause: Throwable? = null,
    val status: Int? = null,
    val retryAfterMs: Long? = null,
) : Exception(message, cause)
