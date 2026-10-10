package io.github.jaypetez.ideaforge.ui.interview

import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
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

    companion object {
        val FromKeyring = ProviderSource { ring ->
            val active = stringOf(ring["active"]).orEmpty()
            if (active.isEmpty()) throw ProviderException(ProviderErrorCode.CONFIG, "no provider is set up")
            val creds = credsFor(ring, active)
            fun field(name: String) = stringOf(creds[name])?.takeIf { it.isNotEmpty() }
            createProvider(
                ProviderConfig(
                    kind = active,
                    apiKey = field("apiKey").orEmpty(),
                    baseUrl = field("baseUrl"),
                    model = field("model"),
                    wrapModel = field("wrapModel"),
                ),
            )
        }
    }
}
