package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.BUDGET_BYTES
import io.github.jaypetez.ideaforge.core.buildSynthesisPrompt
import io.github.jaypetez.ideaforge.core.buildSynthesisPromptParts
import io.github.jaypetez.ideaforge.core.clearPending
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.parseSynthesisResult
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.setPending
import io.github.jaypetez.ideaforge.core.setStatusField
import io.github.jaypetez.ideaforge.core.setSynthesis
import io.github.jaypetez.ideaforge.core.setTitle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The wrap-up call: the Kotlin port of src/runtime/synthesize.js. Failure is survivable:
// the export renders a complete document with no synthesis, so a failed wrap-up costs the
// refined prompt and never the transcript.

data class SynthesisResult(
    val session: JsonObject,
    val ok: Boolean,
    val error: ProviderException?,
    val aborted: Boolean,
    val warnings: List<String>,
    val calls: Int,
)

suspend fun runSynthesis(
    session: JsonObject, provider: Provider?, now: JsonElement = num(0), modelTier: String = "complex",
    budget: Int = BUDGET_BYTES,
): SynthesisResult {
    val warnings = mutableListOf<String>()
    if (provider == null) {
        return SynthesisResult(session, false, null, false, listOf("no provider; the export will use the checklist layout"), 0)
    }
    // A resumed wrap-up arrives already 'synthesizing'; restoring THAT on failure would strand
    // the session mid-wrap across reloads, so fall back to the state a wrap-up starts from.
    val before = if (stringOf(session["status"]) == "synthesizing") "interviewing" else stringOf(session["status"]) ?: "interviewing"
    val parts = buildSynthesisPromptParts(session, budget)
    var working = setPending(
        session,
        obj("kind" to str("synthesis"), "promptHash" to str(promptHash(buildSynthesisPrompt(session, budget))), "startedAt" to now),
        now,
    )
    working = setStatusField(working, "synthesizing", now)

    val result = try {
        provider.sampleJson(parts, modelTier, cache = true)
    } catch (err: ProviderException) {
        val restored = setStatusField(clearPending(working, now), before, now)
        return if (err.code == "aborted") SynthesisResult(restored, false, null, true, warnings, 1)
        else SynthesisResult(restored, false, err, false, warnings, 1)
    }

    val parsed = parseSynthesisResult(result.json)
    (parsed["warnings"] as JsonArray).forEach { warnings += stringOf(it)!! }

    if (!truthy(parsed["ok"])) {
        val restored = setStatusField(clearPending(working, now), before, now)
        return SynthesisResult(restored, false, null, false, warnings + "the model returned no usable prompt", 1)
    }

    var s = setSynthesis(
        working, parsed["text"], str(result.modelTierApplied?.takeIf { it.isNotEmpty() } ?: modelTier),
        parsed["assumptions"]!!, parsed["openQuestions"]!!, now,
    )
    if (truthy(parsed["title"]) && !truthy(session["title"])) s = setTitle(s, parsed["title"], now)
    s = clearPending(s, now)
    return SynthesisResult(s, true, null, false, warnings, 1)
}

/** Resume an interrupted wrap-up. Synthesis is idempotent, so re-running it is always safe. */
suspend fun resumeSynthesis(session: JsonObject, provider: Provider?, now: JsonElement = num(0), modelTier: String = "complex"): SynthesisResult {
    val pending = session["pending"]
    if (!truthy(pending) || stringOf(pending["kind"]) != "synthesis") {
        return SynthesisResult(session, false, null, false, emptyList(), 0)
    }
    return runSynthesis(clearPending(session, now), provider, now, modelTier)
}
