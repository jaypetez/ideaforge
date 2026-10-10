package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.core.runtime.runSynthesis
import io.github.jaypetez.ideaforge.core.runtime.runTurn
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import io.github.jaypetez.ideaforge.core.setStatusField
import io.github.jaypetez.ideaforge.core.setWrapOffered
import io.github.jaypetez.ideaforge.core.skipQuestion
import io.github.jaypetez.ideaforge.core.wrapAdvisory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The `walkthrough` family: the whole scripted interview, the loop the interview screen runs
 * (tools/fixtures/vectors.mjs, `interview`). This replays it through :core alone; the app
 * module replays the same cases through InterviewViewModel itself, so a screen that strings
 * the same calls together differently (a missed setWrapOffered, a chip sent as typed, a draft
 * write that lands after the answer) fails there rather than here.
 */
val WALKTHROUGH_PORTS: Map<String, Port> = mapOf("interview" to ::interview)

/** The scripted model of tools/fixtures/walkthrough.mjs: replies keyed by `Turn number:`. */
private class Walkthrough(script: JsonElement?) : Provider {
    private val turns = script["turns"] as JsonObject
    private val synthesis = script["synthesis"]!!
    val requests = mutableListOf<JsonElement>()

    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        requests += obj("modelTier" to str(modelTier), "promptHash" to str(promptHash(parts.joined())))
        if (modelTier == "complex") return SampleResult(synthesis, modelTierApplied = modelTier)
        val n = Regex("Turn number: (\\d+)").find(parts.tail)?.groupValues?.get(1)
        val reply = n?.let { turns[it] } ?: throw ProviderException("bad_response", "no scripted reply")
        return SampleResult(reply, modelTierApplied = modelTier)
    }
}

private const val NO_PROMPT_NOTE = "The refined prompt could not be generated, but nothing else was lost."

private fun interview(c: Case): JsonElement = runBlocking {
    val input = c.input
    val now = input["now"]!!
    val provider = Walkthrough(c.script)
    var s = seedTurn(createSession(stringOf(input["id"]), now, input["device"]!!), now)
    var stoppedBy = "answers"
    var asked = 1
    for (step in input["steps"] as JsonArray) {
        val open = openTurn(s)!!
        s = when {
            truthy(step["skip"]) -> skipQuestion(s, now)
            step["chip"] != null -> submitAnswer(s, (open["chips"] as JsonArray)[toNumber(step["chip"]).toInt()], "chip", now)
            else -> submitAnswer(s, step["text"], now = now)
        }
        val out = runTurn(s, provider, now)
        s = out.session
        if (out.turn != null) asked++
        val advisory = if (out.aborted) null else wrapAdvisory(s, out.wrap)
        if (advisory != null) s = setWrapOffered(s, true, now)
        if (out.turn == null) { stoppedBy = "no turn: ${out.wrap}"; break }
        if (advisory != null) { stoppedBy = "offer: ${out.wrap}"; break }
    }
    val synth = runSynthesis(s, provider, now)
    s = if (synth.ok || synth.aborted) synth.session else setStatusField(synth.session, "done", now)
    val hasPrompt = truthy(s["synthesis"]["text"])
    val markdown = buildExport(s, if (hasPrompt) "claude" else "checklist", if (hasPrompt) null else NO_PROMPT_NOTE)
    obj(
        "session" to s, "export" to str(markdown), "asked" to num(asked), "stoppedBy" to str(stoppedBy),
        "requests" to arr(provider.requests),
    )
}
