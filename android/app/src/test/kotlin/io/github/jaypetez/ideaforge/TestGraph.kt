package io.github.jaypetez.ideaforge

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.SoftwareKey
import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.robolectric.RuntimeEnvironment

/** A provider check that never opens a socket: records what it was asked, answers [answer]. */
class FakeCheck(var answer: (ProviderChoice, ProviderConfig) -> List<String> = { _, _ -> emptyList() }) : ProviderCheck {
    val calls = mutableListOf<Pair<String, ProviderConfig>>()

    override suspend fun check(choice: ProviderChoice, config: ProviderConfig): List<String> {
        calls += choice.id to config
        return answer(choice, config)
    }
}

/**
 * Replace the application's graph before an activity launches: a software keyring key
 * (Robolectric has no AndroidKeyStore) and a fake provider check. [ring] is saved first, so
 * the app starts as a returning user would.
 */
fun installTestGraph(check: ProviderCheck = FakeCheck(), ring: JsonObject? = null): AppGraph {
    val app = RuntimeEnvironment.getApplication() as IdeaForgeApp
    val graph = AppGraph(app, keyringKey = SoftwareKey(), providerCheck = check)
    runBlocking {
        if (ring != null) graph.keyringStore.save(ring)
        graph.keyringStore.load()
    }
    app.graph = graph
    return graph
}

/** A keyring that can start an interview: Groq with a key. */
fun usableRing(): JsonObject =
    withCreds(emptyKeyring(), "groq", JsonObject(mapOf("apiKey" to JsonPrimitive("gsk_returning_user_key_0001"))))
