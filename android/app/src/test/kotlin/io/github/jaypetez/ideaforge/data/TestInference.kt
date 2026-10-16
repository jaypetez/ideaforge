package io.github.jaypetez.ideaforge.data

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.providers.inferenceDestination
import io.github.jaypetez.ideaforge.ui.settings.MemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun testInferenceRing(): JsonObject = withCreds(emptyKeyring(), "groq",
    JsonObject(mapOf("apiKey" to JsonPrimitive("scripted-inference-not-a-key"))))

/** Existing model/voice fixtures represent an agreed user; denial cases pass agreed=false. */
fun testInferenceConsent(
    scope: CoroutineScope,
    ring: () -> JsonElement = { testInferenceRing() },
    agreed: Boolean = true,
    onRetire: (String) -> Unit = {},
): InferenceConsent {
    var grants = 0
    val consent = InferenceConsent(PrefsStore(MemoryDataStore()), ring, scope, onRetire,
        newGrant = { "test-inference-grant-${++grants}" })
    if (agreed) runBlocking { consent.accept(inferenceDestination(inferenceConfig(ring()))) }
    return consent
}
