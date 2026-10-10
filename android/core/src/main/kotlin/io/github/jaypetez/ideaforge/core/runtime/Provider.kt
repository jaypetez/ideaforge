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

/** A failed provider call. `code` is the JS ProviderError code (network, auth, aborted...). */
class ProviderException(val code: String?, message: String, cause: Throwable? = null) : Exception(message, cause)
