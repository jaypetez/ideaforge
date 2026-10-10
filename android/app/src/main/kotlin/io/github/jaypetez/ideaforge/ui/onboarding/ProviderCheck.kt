package io.github.jaypetez.ideaforge.ui.onboarding

import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.createProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one network call onboarding makes, as a seam: production builds the real adapter, a
 * test hands in a fake that answers (or throws a [io.github.jaypetez.ideaforge.core.runtime.ProviderException])
 * without a socket.
 *
 * Returns the models the server lists, empty when the provider has no list to read. Throws
 * when the key or the server does not work.
 */
fun interface ProviderCheck {
    suspend fun check(choice: ProviderChoice, config: ProviderConfig): List<String>

    companion object {
        /**
         * The web's "Check" button (src/ui/app.js, `b-check`), exactly: a provider with a model
         * list is checked by reading it, because listModels and validateKey probe the same
         * `GET /models` and one call answers both, with the catalogue as a side effect. Only a
         * provider without a list (Anthropic) spends the cheap validateKey round trip.
         *
         * `modelRequired = false` for the list read, as the web's `buildProvider({requireModel:
         * false})`: the point of reading the list is to find out what to put in the model box,
         * so refusing to build an adapter until the box is filled would be circular.
         */
        val Default = ProviderCheck { choice, config ->
            withContext(Dispatchers.IO) {
                if (choice.discoverModels) {
                    createProvider(config.copy(modelRequired = false)).listModels()
                } else {
                    createProvider(config).validateKey()
                    emptyList()
                }
            }
        }
    }
}
