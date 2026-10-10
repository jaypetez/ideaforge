package io.github.jaypetez.ideaforge

import android.content.Context
import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Debug-only provider overrides, chosen at launch:
 *
 *     adb shell am start -n io.github.jaypetez.ideaforge.debug/io.github.jaypetez.ideaforge.MainActivity \
 *         --es provider scripted
 *
 * `scripted` replays the README walkthrough (tools/fixtures/walkthrough.mjs) with no key and
 * no network. The release source set has a DebugProviders that returns null, so none of this
 * (nor the walkthrough asset, which only the debug variant packages) ships.
 */
object DebugProviders {
    fun fromLaunch(context: Context, name: String?): ProviderSource? = when (name) {
        "scripted" -> {
            val script = ScriptedProvider.loadScript(context)
            ProviderSource { ScriptedProvider(script) }
        }
        else -> null
    }
}

/**
 * The scripted model of tools/fixtures/walkthrough.mjs, read from the `walkthrough` golden
 * vector the build copies into the debug assets, so it is the fixture itself rather than a
 * Kotlin transcription of it. Replies are keyed by the prompt's `Turn number:` line; the
 * `complex` tier gets the wrap-up. A turn the script has no reply for fails as a bad
 * response, which the runtime answers from the question bank, as the web harness does.
 */
class ScriptedProvider(script: JsonElement) : Provider {
    private val turns = script["turns"] as JsonObject
    private val synthesis = script["synthesis"]!!

    /** Every request this provider was sent, in order: the model tier and the prompt. */
    val requests = mutableListOf<Pair<String, PromptParts>>()

    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        synchronized(requests) { requests += modelTier to parts }
        if (modelTier == "complex") return SampleResult(synthesis, modelTierApplied = modelTier)
        val n = TURN_NUMBER.find(parts.tail)?.groupValues?.get(1)
        val reply = n?.let { turns[it] } ?: throw ProviderException("bad_response", "no scripted reply for turn $n")
        return SampleResult(reply, modelTierApplied = modelTier)
    }

    companion object {
        const val ASSET = "walkthrough.json"
        private val TURN_NUMBER = Regex("Turn number: (\\d+)")

        /** The `script` of the vector's first case; every case shares it. */
        fun loadScript(context: Context): JsonElement {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            val cases = Json.parseToJsonElement(text)["cases"] as JsonArray
            return cases.first()["script"]!!
        }
    }
}
