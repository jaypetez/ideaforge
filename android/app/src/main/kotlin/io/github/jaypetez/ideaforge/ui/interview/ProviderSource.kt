package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.data.inferenceConfig
import io.github.jaypetez.ideaforge.providers.createProvider
import kotlinx.serialization.json.JsonElement

/**
 * Where the interview gets its model: the web's `buildProvider()`, as a seam. Production
 * reads the active provider and its record off the keyring; tests and the debug build's
 * `--es provider scripted` hand in a scripted one.
 *
 * Throws when the saved setup cannot build an adapter. The interview then carries on with no
 * provider, on the built-in question bank, and says so, as the web's resumeInterview does.
 */
fun interface ProviderSource {
    fun build(ring: JsonElement): Provider

    fun buildAuthorized(ring: JsonElement, beforeSample: suspend () -> Unit): Provider {
        val provider = build(ring)
        return object : Provider {
            override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
                beforeSample()
                return provider.sampleJson(parts, modelTier, cache)
            }
        }
    }

    companion object {
        val FromKeyring = object : ProviderSource {
            override fun build(ring: JsonElement): Provider = createProvider(inferenceConfig(ring))

            override fun buildAuthorized(ring: JsonElement, beforeSample: suspend () -> Unit): Provider =
                createProvider(inferenceConfig(ring), beforeSample = beforeSample)
        }
    }
}
