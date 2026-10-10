package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.BUDGET_BYTES
import io.github.jaypetez.ideaforge.core.HARD_TURN_CEILING
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.TRIPWIRE_DIRECTIVE
import io.github.jaypetez.ideaforge.core.addFacts
import io.github.jaypetez.ideaforge.core.answerQuestion
import io.github.jaypetez.ideaforge.core.applyCoverage
import io.github.jaypetez.ideaforge.core.askQuestion
import io.github.jaypetez.ideaforge.core.buildTurnPrompt
import io.github.jaypetez.ideaforge.core.buildTurnPromptParts
import io.github.jaypetez.ideaforge.core.classifyAnswer
import io.github.jaypetez.ideaforge.core.clearPending
import io.github.jaypetez.ideaforge.core.isLowConfidence
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.lastAnswerClass
import io.github.jaypetez.ideaforge.core.legalMoves
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.parseTurnResult
import io.github.jaypetez.ideaforge.core.pickBankQuestion
import io.github.jaypetez.ideaforge.core.promptHash
import io.github.jaypetez.ideaforge.core.questionTripwire
import io.github.jaypetez.ideaforge.core.reopen
import io.github.jaypetez.ideaforge.core.selectNextDimension
import io.github.jaypetez.ideaforge.core.setPending
import io.github.jaypetez.ideaforge.core.setZeroGainStreak
import io.github.jaypetez.ideaforge.core.shouldOfferWrap
import io.github.jaypetez.ideaforge.core.js.toNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// One turn, end to end: the Kotlin port of src/runtime/turn.js. The provider and `now` are
// injected; everything this calls is pure.
//
//   runTurn  selectNextDimension → legalMoves → buildTurnPromptParts → sampleJson
//            → parseTurnResult → questionTripwire → applyCoverage + addFacts
//            + setZeroGainStreak → askQuestion
//
// runTurn bumps rev several times. Persist the session it returns, once.

/** Answer classes that cap the dimension the answer served at `partial`. */
val CAPPING_CLASSES: Set<String> = linkedSetOf("dodge", "idk", "refusal")

private const val JSON_REPAIR_DIRECTIVE =
    "Your previous reply was not a single valid JSON object. Return only the JSON object " +
        "described above, with no prose, no explanation and no code fence."

/** The outcome of a turn, in the shape the JS returns. */
data class TurnResult(
    val session: JsonObject,
    val turn: JsonObject?,
    val degraded: Boolean,
    val error: ProviderException?,
    val wrap: String?,
    val aborted: Boolean,
    val warnings: List<String>,
    val calls: Int,
)

/** Turn 1 is hardcoded: no model call, so it paints instantly and cannot fail. */
fun seedTurn(session: JsonObject, now: JsonElement = num(0)): JsonObject =
    askQuestion(session, question = str(SEED_QUESTION), dimension = str("outcome"), source = str("seed"), now = now)

/**
 * Record the user's answer to the open turn. The runtime classifies, not the UI: the
 * `source` must reach classifyAnswer raw, because that is what exempts dictation from the
 * terse and dodge thresholds.
 */
fun submitAnswer(session: JsonObject, text: JsonElement?, source: String = "typed", now: JsonElement = num(0)): JsonObject {
    val open = openTurn(session) ?: throw IllegalStateException("no open turn to answer")
    val base = if (stringOf(session["status"]) == "done") reopen(session, now) else session
    val chips = (open["chips"] as? JsonArray)?.mapNotNull { stringOf(it) } ?: emptyList()
    val classification = classifyAnswer(text, open["question"], source, chips)
    return answerQuestion(base, text, str(source), str(classification), now)
}

/**
 * Ask the next question. With no provider the interview runs on the question bank and
 * makes no calls at all.
 */
suspend fun runTurn(
    session: JsonObject, provider: Provider?, now: JsonElement = num(0), modelTier: String = "default",
    budget: Int = BUDGET_BYTES,
): TurnResult {
    if (openTurn(session) != null) throw IllegalStateException("a question is already open")
    val warnings = mutableListOf<String>()
    fun done(s: JsonObject, turn: JsonObject?, wrap: String?) =
        TurnResult(s, turn, false, null, wrap, false, warnings, 0)

    // The hard ceiling is only *reported* by shouldOfferWrap; something has to enforce it.
    if (turnsOf(session).size >= HARD_TURN_CEILING) return done(session, null, "hard_ceiling")

    val target = selectNextDimension(session) ?: return done(session, null, "coverage")

    val prev = turnsOf(session).lastOrNull { truthy(it["answer"]) || truthy(it["skipped"]) } as? JsonObject
    val lastClass = lastAnswerClass(session)
    val moves = legalMoves(session, target, lastClass)

    if (provider == null) return bankFallback(session, target, now, warnings, null, 0)

    val turnId = "t${turnsOf(session).size + 1}"
    var working = session
    var calls = 0
    var retried = false
    var injected: String? = null

    while (true) {
        val parts = buildTurnPromptParts(working, target, moves, injected, budget)
        // Pending is set before the call and carries the prompt hash, so an interrupted turn
        // can prove on resume that the recomputed prompt is the same one.
        working = setPending(
            working,
            obj("kind" to str("turn"), "turnId" to str(turnId), "promptHash" to str(promptHash(parts.joined())), "startedAt" to now),
            now,
        )

        val result = try {
            provider.sampleJson(parts, modelTier, cache = true).also { calls++ }
        } catch (err: ProviderException) {
            calls++
            if (err.code == "aborted") {
                return TurnResult(clearPending(working, now), null, false, null, null, true, warnings, calls)
            }
            warnings += "provider ${err.code ?: "error"}"
            return bankFallback(working, target, now, warnings, err, calls)
        }

        val parsed = parseTurnResult(result.json, moves, target)
        (parsed["warnings"] as JsonArray).forEach { warnings += stringOf(it)!! }

        val ok = truthy(parsed["ok"])
        val reason = if (!ok) "bad_json" else questionTripwire(parsed["question"], working)

        if (reason != null && !retried) {
            // Exactly one regeneration per turn, shared between a bad parse and a tripwire.
            retried = true
            injected = if (reason == "bad_json") JSON_REPAIR_DIRECTIVE else TRIPWIRE_DIRECTIVE.getValue(reason)
            continue
        }

        if (reason != null) {
            // A generic question is a quality miss, not a correctness failure: accept it. The
            // others waste the user's turn, so fall back to a question we know is legal.
            if (reason == "generic") {
                warnings += "generic question accepted"
            } else {
                warnings += "unfixable $reason"
                return bankFallback(working, target, now, warnings, null, calls)
            }
        }

        return applyTurn(working, parsed, prev, lastClass, now, warnings, calls)
    }
}

/** Apply a good result. Order matters: coverage before the gain measurement. */
private fun applyTurn(
    session: JsonObject, parsed: JsonObject, prev: JsonObject?, lastClass: String, now: JsonElement,
    warnings: List<String>, calls: Int,
): TurnResult {
    val before = levelSnapshot(session)

    // The coverage claim judges the answer just read, so the provenance cap belongs to the
    // dimension THAT answer served: prev.dimension, not the dimension we are about to ask
    // about. Getting this backwards caps a dimension nothing has touched.
    val lowConfidenceDimension = if (prev != null && isLowConfidence(prev)) prev["dimension"] ?: JsonNull else JsonNull
    val cappedDimensions = if (prev != null && lastClass in CAPPING_CLASSES) arr(listOf(prev["dimension"] ?: JsonNull)) else arr(emptyList())

    var s = applyCoverage(session, parsed["coverage"], lowConfidenceDimension, cappedDimensions, now)
    s = addFacts(s, parsed["facts"], now)
    s = setZeroGainStreak(s, if (isZeroGain(before, levelSnapshot(s))) toNumber(s["zeroGainStreak"]).toInt() + 1 else 0, now)
    s = askQuestion(
        s, question = parsed["question"], dimension = parsed["dimension"], move = parsed["move"] ?: JsonNull,
        chips = parsed["chips"] ?: arr(emptyList()), bridge = parsed["bridge"] ?: JsonNull, source = str("model"), now = now,
    )
    s = clearPending(s, now)
    return TurnResult(s, openTurn(s), false, null, shouldOfferWrap(s), false, warnings, calls)
}

/**
 * The interview must never dead-end on a network error, so a failed turn falls back to the
 * static question bank. zeroGainStreak is left untouched here, in both directions:
 * incrementing would let two network blips offer the exit for a reason that never
 * happened; resetting would erase a real exhaustion signal a transient failure interrupted.
 */
private fun bankFallback(
    session: JsonObject, target: String, now: JsonElement, warnings: List<String>, error: ProviderException?, calls: Int,
): TurnResult {
    val picked = pickBankQuestion(session, target)
    val s0 = if (truthy(session["pending"])) clearPending(session, now) else session
    if (picked == null) return TurnResult(s0, null, true, error, "exhausted", false, warnings, calls)
    val s = askQuestion(s0, question = str(picked.first), dimension = str(picked.second), source = str("bank"), now = now)
    return TurnResult(s, openTurn(s), true, error, shouldOfferWrap(s), false, warnings, calls)
}

/**
 * Resume a turn interrupted mid-flight. The turnId check is the correctness guarantee (never
 * create the same turn twice); the prompt hash is only a cache-replay optimisation.
 */
suspend fun resumeTurn(
    session: JsonObject, provider: Provider?, now: JsonElement = num(0), modelTier: String = "default",
    budget: Int = BUDGET_BYTES,
): TurnResult {
    val pending = session["pending"]
    if (!truthy(pending) || stringOf(pending["kind"]) != "turn") {
        return TurnResult(session, openTurn(session), false, null, shouldOfferWrap(session), false, emptyList(), 0)
    }
    // The crash landed between askQuestion and clearPending: the work is done and only the
    // bookkeeping was lost. Calling the model again would duplicate the turn.
    if (turnsOf(session).any { stringOf(it["id"]) == stringOf(pending["turnId"]) && it["id"] != null }) {
        val s = clearPending(session, now)
        return TurnResult(s, openTurn(s), false, null, shouldOfferWrap(s), false, listOf("recovered a completed turn"), 0)
    }
    val target = selectNextDimension(session)
    val warnings = mutableListOf<String>()
    if (target != null) {
        val moves = legalMoves(session, target, lastAnswerClass(session))
        val hash = promptHash(buildTurnPrompt(session, target, moves, null, budget))
        if (hash != stringOf(pending["promptHash"])) {
            warnings += "prompt changed since the interrupted call; starting the turn fresh"
        }
    }
    val out = runTurn(clearPending(session, now), provider, now, modelTier, budget)
    return out.copy(warnings = warnings + out.warnings)
}

private fun turnsOf(s: JsonObject): List<JsonElement> = s["turns"] as? JsonArray ?: emptyList()
