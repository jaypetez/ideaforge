package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.BUDGET_BYTES
import io.github.jaypetez.ideaforge.core.FULL_VERBATIM_UNTIL_TURN
import io.github.jaypetez.ideaforge.core.HARD_TURN_CEILING
import io.github.jaypetez.ideaforge.core.MAX_ANSWER_CHARS
import io.github.jaypetez.ideaforge.core.MAX_INPUT_BYTES
import io.github.jaypetez.ideaforge.core.MOVE_IDS
import io.github.jaypetez.ideaforge.core.READY_COVERED_TARGET
import io.github.jaypetez.ideaforge.core.SOFT_TURN_CEILING
import io.github.jaypetez.ideaforge.core.TRIPWIRE_DIRECTIVE
import io.github.jaypetez.ideaforge.core.WINDOW_STEPS
import io.github.jaypetez.ideaforge.core.buildTranscriptBlock
import io.github.jaypetez.ideaforge.core.buildTurnPrompt
import io.github.jaypetez.ideaforge.core.buildTurnPromptParts
import io.github.jaypetez.ideaforge.core.classifyAnswer
import io.github.jaypetez.ideaforge.core.contentWords
import io.github.jaypetez.ideaforge.core.isReadyToWrap
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.lastAnswerClass
import io.github.jaypetez.ideaforge.core.legalMoves
import io.github.jaypetez.ideaforge.core.parseTurnResult
import io.github.jaypetez.ideaforge.core.pickBankQuestion
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.questionTripwire
import io.github.jaypetez.ideaforge.core.selectNextDimension
import io.github.jaypetez.ideaforge.core.serializeFacts
import io.github.jaypetez.ideaforge.core.shouldOfferWrap
import io.github.jaypetez.ideaforge.core.wrapAdvisory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun Case.arg(i: Int): JsonElement? = args.getOrNull(i)
private fun JsonElement?.s(): JsonObject = this as JsonObject
private fun strOrNull(s: String?): JsonElement = s?.let(::str) ?: JsonNull
private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { stringOf(it) } ?: emptyList()

/** `{target, moves, injected = null, budget = BUDGET_BYTES}` from a recorded options object. */
private data class PromptOpts(val target: String?, val moves: List<String>, val injected: String?, val budget: Int)

private fun promptOpts(o: JsonElement?) = PromptOpts(
    stringOf(o["target"]), strings(o["moves"]), stringOf(o["injected"]),
    o["budget"]?.let { toNumber(it).toInt() } ?: BUDGET_BYTES,
)

val ENGINE_PORTS: Map<String, Port> = mapOf(
    "selectNextDimension" to { c -> strOrNull(selectNextDimension(c.arg(0).s())) },
    "lastAnswerClass" to { c -> str(lastAnswerClass(c.arg(0).s())) },
    "isReadyToWrap" to { c -> JsonPrimitive(isReadyToWrap(c.arg(0).s())) },
    "shouldOfferWrap" to { c -> strOrNull(shouldOfferWrap(c.arg(0).s())) },
    "pickBankQuestion" to { c ->
        pickBankQuestion(c.arg(0).s(), stringOf(c.arg(1)))
            ?.let { (q, d) -> obj("question" to str(q), "dimension" to str(d)) } ?: JsonNull
    },
    "legalMoves" to { c -> arr(legalMoves(c.arg(0).s(), stringOf(c.arg(1))!!, stringOf(c.arg(2)) ?: "substantive").map(::str)) },
    "wrapAdvisory" to { c -> strOrNull(wrapAdvisory(c.arg(0), stringOf(c.arg(1)))) },
    "classifyAnswer" to { c ->
        val o = c.arg(2)
        str(classifyAnswer(c.arg(0), c.arg(1) ?: str(""), stringOf(o["source"]) ?: "typed", strings(o["chips"])))
    },
    "contentWords" to { c -> arr(contentWords(c.arg(0)).map(::str)) },
    "questionTripwire" to { c -> strOrNull(questionTripwire(c.arg(0), c.arg(1).s())) },
    "parseTurnResult" to { c ->
        val o = c.arg(1)
        parseTurnResult(c.arg(0), o["moves"]?.let(::strings) ?: MOVE_IDS, stringOf(o["target"]))
    },
)

val TURN_PROMPT_PORTS: Map<String, Port> = mapOf(
    "buildTurnPromptParts" to { c ->
        val o = promptOpts(c.arg(1))
        val p = buildTurnPromptParts(c.arg(0).s(), o.target, o.moves, o.injected, o.budget)
        obj("system" to str(p.system), "prefix" to str(p.prefix), "tail" to str(p.tail))
    },
    "turnPromptHash" to { c ->
        val o = promptOpts(c.arg(1))
        str(promptHash(buildTurnPrompt(c.arg(0).s(), o.target, o.moves, o.injected, o.budget)))
    },
    "buildTranscriptBlock" to { c ->
        val b = buildTranscriptBlock(c.arg(0).s(), c.arg(1)?.let { toNumber(it).toInt() } ?: BUDGET_BYTES)
        obj("text" to str(b.text), "windowSize" to (b.windowSize?.let { num(it) } ?: JsonNull), "truncated" to JsonPrimitive(b.truncated))
    },
    "serializeFacts" to { c -> str(serializeFacts(c.arg(0))) },
)

val ENGINE_CONSTANTS: Map<String, JsonElement> = mapOf(
    "HARD_TURN_CEILING" to num(HARD_TURN_CEILING),
    "SOFT_TURN_CEILING" to num(SOFT_TURN_CEILING),
    "READY_COVERED_TARGET" to num(READY_COVERED_TARGET),
    "TRIPWIRE_DIRECTIVE" to obj(TRIPWIRE_DIRECTIVE.mapValues { str(it.value) }),
    "BUDGET_BYTES" to num(BUDGET_BYTES),
    "FULL_VERBATIM_UNTIL_TURN" to num(FULL_VERBATIM_UNTIL_TURN),
    "MAX_ANSWER_CHARS" to num(MAX_ANSWER_CHARS),
    "MAX_INPUT_BYTES" to num(MAX_INPUT_BYTES),
    "WINDOW_STEPS" to arr(WINDOW_STEPS.map { num(it) }),
)
