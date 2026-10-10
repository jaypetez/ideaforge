package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.BUDGET_BYTES
import io.github.jaypetez.ideaforge.core.MAX_ASSUMPTIONS
import io.github.jaypetez.ideaforge.core.MAX_OPEN_QUESTIONS
import io.github.jaypetez.ideaforge.core.MAX_TITLE_WORDS
import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.buildSynthesisPromptParts
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.parseSynthesisResult
import io.github.jaypetez.ideaforge.core.runtime.CAPPING_CLASSES
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.core.runtime.isZeroGain
import io.github.jaypetez.ideaforge.core.runtime.levelSnapshot
import io.github.jaypetez.ideaforge.core.runtime.resumeTurn
import io.github.jaypetez.ideaforge.core.runtime.runSynthesis
import io.github.jaypetez.ideaforge.core.runtime.runTurn
import io.github.jaypetez.ideaforge.core.runtime.seedTurn
import io.github.jaypetez.ideaforge.core.runtime.submitAnswer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun Case.arg(i: Int): JsonElement? = args.getOrNull(i)
private fun JsonElement?.s(): JsonObject = this as JsonObject

/**
 * The scripted provider tools/vectors.mjs used: one entry per call, the last repeating;
 * `{json}` replies, `{error: code}` throws "scripted <code>". Records what it was asked.
 */
private class Scripted(private val script: JsonArray) : Provider {
    val requests = mutableListOf<JsonElement>()
    private var i = 0
    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        requests += obj(
            "parts" to obj("system" to str(parts.system), "prefix" to str(parts.prefix), "tail" to str(parts.tail)),
            "modelTier" to str(modelTier),
        )
        val step = script[minOf(i, script.size - 1)]
        i++
        stringOf(step["error"])?.let { throw ProviderException(it, "scripted $it") }
        return SampleResult(step["json"], modelTierApplied = stringOf(step["tier"]))
    }
}

private fun errorOf(e: ProviderException?): JsonElement =
    e?.let { obj("code" to (it.code?.let(::str) ?: JsonNull), "message" to str(it.message ?: "")) } ?: JsonNull

/** A scenario case compares the outcome AND every request the provider received. */
private fun scenario(c: Case, outcome: JsonObject, provider: Scripted?): JsonElement {
    val expectedRequests = c.expect["requests"] ?: JsonArray(emptyList())
    val actualRequests = JsonArray(provider?.requests ?: emptyList())
    check(expectedRequests == actualRequests) { "${c.name}: provider requests differ" }
    return outcome
}

private fun deps(c: Case): Triple<JsonElement, String?, Int> {
    val d = c.arg(1)
    return Triple(d["now"] ?: num(0), stringOf(d["modelTier"]), d["budget"]?.let { toNumber(it).toInt() } ?: BUDGET_BYTES)
}

val RUNTIME_PORTS: Map<String, Port> = mapOf(
    "runTurn" to { c -> turnScenario(c, resume = false) },
    "resumeTurn" to { c -> turnScenario(c, resume = true) },
    "seedTurn" to { c -> seedTurn(c.arg(0).s(), c.arg(1)["now"] ?: num(0)) },
    "submitAnswer" to { c ->
        val o = c.arg(1)
        submitAnswer(c.arg(0).s(), o["text"], stringOf(o["source"]) ?: "typed", o["now"] ?: num(0))
    },
    "levelSnapshot" to { c -> obj(levelSnapshot(c.arg(0).s()).mapValues { (_, v) -> v?.let(::str) }) },
    "isZeroGain" to { c ->
        fun snap(e: JsonElement?) = (e as JsonObject).mapValues { (_, v) -> stringOf(v) }
        JsonPrimitive(isZeroGain(snap(c.arg(0)), snap(c.arg(1))))
    },
)

private fun turnScenario(c: Case, resume: Boolean): JsonElement {
    val provider = (c.script as? JsonArray)?.let(::Scripted)
    val (now, tier, budget) = deps(c)
    val r = runBlocking {
        if (resume) resumeTurn(c.arg(0).s(), provider, now, tier ?: "default", budget)
        else runTurn(c.arg(0).s(), provider, now, tier ?: "default", budget)
    }
    return scenario(c, obj(
        "session" to r.session, "turn" to (r.turn ?: JsonNull), "degraded" to JsonPrimitive(r.degraded),
        "error" to errorOf(r.error), "wrap" to (r.wrap?.let(::str) ?: JsonNull), "aborted" to JsonPrimitive(r.aborted),
        "warnings" to arr(r.warnings.map(::str)), "calls" to num(r.calls),
    ), provider)
}

val SYNTHESIS_PORTS: Map<String, Port> = mapOf(
    "buildSynthesisPromptParts" to { c ->
        val p = buildSynthesisPromptParts(c.arg(0).s(), c.arg(1)["budget"]?.let { toNumber(it).toInt() } ?: BUDGET_BYTES)
        obj("system" to str(p.system), "prefix" to str(p.prefix), "tail" to str(p.tail))
    },
    "parseSynthesisResult" to { c -> parseSynthesisResult(c.arg(0)) },
    "runSynthesis" to { c ->
        val provider = (c.script as? JsonArray)?.let(::Scripted)
        val (now, tier, budget) = deps(c)
        val r = runBlocking { runSynthesis(c.arg(0).s(), provider, now, tier ?: "complex", budget) }
        scenario(c, obj(
            "session" to r.session, "ok" to JsonPrimitive(r.ok), "error" to errorOf(r.error),
            "aborted" to JsonPrimitive(r.aborted), "warnings" to arr(r.warnings.map(::str)), "calls" to num(r.calls),
        ), provider)
    },
)

val RUNTIME_CONSTANTS: Map<String, JsonElement> = mapOf(
    "MAX_ASSUMPTIONS" to num(MAX_ASSUMPTIONS),
    "MAX_OPEN_QUESTIONS" to num(MAX_OPEN_QUESTIONS),
    "MAX_TITLE_WORDS" to num(MAX_TITLE_WORDS),
    "CAPPING_CLASSES" to arr(CAPPING_CLASSES.map(::str)),
)

