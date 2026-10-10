package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_B
import io.github.jaypetez.ideaforge.core.js.JS_DOT
import io.github.jaypetez.ideaforge.core.js.jsTypeof
import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.bool
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsLower
import io.github.jaypetez.ideaforge.core.js.jsPadEnd
import io.github.jaypetez.ideaforge.core.js.jsRegexI
import io.github.jaypetez.ideaforge.core.js.jsSplitSpaces
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.jsonQuote
import io.github.jaypetez.ideaforge.core.js.jsonStringArray
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The interview engine: what to ask next, how to ask for it, and how to read the answer.
// The Kotlin port of src/core/engine.js. The shape of the interview is deterministic here;
// the model only ever WRITES the question.

const val SOFT_TURN_CEILING = 18
const val HARD_TURN_CEILING = 25

// ─────────────────────────────────────────────── answer classification

private val RE_IDK = jsRegexI(
    "${JS_B}(i[$JS_S]*don'?t[$JS_S]*know|no[$JS_S]+idea|not[$JS_S]+sure|haven'?t[$JS_S]+thought|dunno|" +
        "you[$JS_S]+tell[$JS_S]+me|whatever[$JS_S]+you[$JS_S]+think)$JS_B",
)
private val RE_REFUSAL = jsRegexI(
    "${JS_B}(stop[$JS_S]+asking|move[$JS_S]+on|next[$JS_S]+question|skip[$JS_S]+(this|that)|" +
        "enough[$JS_S]+about|don'?t[$JS_S]+care[$JS_S]+about|already[$JS_S]+(said|told))$JS_B",
)
private val RE_TERSE_WORD = jsRegexI("^(yes|no|sure|maybe|idk|both|either|whatever|none|n/a)[^A-Za-z0-9_]*$")
private val STOPWORDS: Set<String> = (
    "the a an and or but if then of to in on for with is are was were be been it this that these those " +
        "what how why who when where which you your my i we they them at as by from so do does did can could would should will " +
        "about into over under out up down not no yes its it's there here more most some any all one two"
    ).split(' ').toSet()

private val NON_CONTENT = Regex("[^a-z0-9$JS_S']")

fun contentWords(text: JsonElement?): List<String> {
    val s = if (truthy(text)) jsString(text) else ""
    return jsSplitSpaces(jsLower(s).replace(NON_CONTENT, " "))
        .filter { it.length > 2 && it !in STOPWORDS }
}

/**
 * Dictated answers are longer and more meandering than typed ones, so the terse and dodge
 * thresholds are not applied to voice.
 */
fun classifyAnswer(text: JsonElement?, question: JsonElement? = str(""), source: String = "typed", chips: List<String> = emptyList()): String {
    val t = jsTrim(if (truthy(text)) jsString(text) else "")
    if (t.isEmpty()) return "terse"
    if (RE_REFUSAL.containsMatchIn(t)) return "refusal"
    if (RE_IDK.containsMatchIn(t)) return "idk"

    val words = jsSplitSpaces(t).filter { it.isNotEmpty() }
    val isChipVerbatim = chips.any { jsLower(jsTrim(it)) == jsLower(t) }
    if (RE_TERSE_WORD.containsMatchIn(t) || (words.size <= 4 && source != "voice") || isChipVerbatim) return "terse"

    if (source != "voice" && words.size >= 15) {
        val qWords = contentWords(question).toSet()
        val overlap = contentWords(str(t)).count { it in qWords }
        if (qWords.size >= 3 && overlap < 2) return "dodge"
    }
    return "substantive"
}

// ─────────────────────────────────────────────── dimension selection

private fun turnsOf(s: JsonObject): List<JsonElement> = s["turns"] as? JsonArray ?: emptyList()
private fun coverageOf(s: JsonObject): JsonObject = s["coverage"] as JsonObject

/** The classification of the most recent answered turn. */
fun lastAnswerClass(session: JsonObject): String {
    val last = turnsOf(session).lastOrNull { truthy(it["answer"]) || truthy(it["skipped"]) } ?: return "substantive"
    if (truthy(last["skipped"])) return "refusal"
    return if (truthy(last["classification"])) jsString(last["classification"]) else "substantive"
}

/** Lowest-covered dimension, weighted; ties broken by fewest turns, then fixed order. */
fun selectNextDimension(session: JsonObject): String? {
    val coverage = coverageOf(session)
    var best: JsonObject? = null
    var bestScore = Double.POSITIVE_INFINITY
    for (d in DIMENSIONS) {
        d as JsonObject
        val id = stringOf(d["id"])!!
        val c = coverage[id]
        if (stringOf(c["status"]) != "probing") continue
        val score = (LEVEL_RANK[stringOf(c["level"])]?.toDouble() ?: Double.NaN) / toNumber(d["weight"])
        val bestTurns = best?.let { toNumber(coverage[stringOf(it["id"])!!]["turnsSpent"]) }
        if (score < bestScore - 1e-9 ||
            (Math.abs(score - bestScore) < 1e-9 && toNumber(c["turnsSpent"]) < (bestTurns ?: Double.NaN))
        ) {
            best = d; bestScore = score
        }
    }
    return best?.let { stringOf(it["id"]) }
}

/** Five of the seven dimensions covered is the bar; min() keeps that honest with waivers. */
const val READY_COVERED_TARGET = 5

fun isReadyToWrap(session: JsonObject): Boolean {
    val coverage = coverageOf(session)
    val probing = DIMENSION_IDS.filter { stringOf(coverage[it]["status"]) == "probing" }
    if (probing.isEmpty()) return true
    val allPartial = probing.all { (LEVEL_RANK[stringOf(coverage[it]["level"])] ?: -1) >= 1 }
    val coveredCount = probing.count { stringOf(coverage[it]["level"]) == "covered" }
    return allPartial && coveredCount >= minOf(READY_COVERED_TARGET, probing.size)
}

/** 'coverage' | 'soft_ceiling' | 'hard_ceiling' | 'exhausted' | null */
fun shouldOfferWrap(session: JsonObject): String? {
    val n = turnsOf(session).size
    if (n >= HARD_TURN_CEILING) return "hard_ceiling"
    if (n >= SOFT_TURN_CEILING) return "soft_ceiling"
    if (toNumber(session["zeroGainStreak"]) >= 2) return "exhausted"
    if (isReadyToWrap(session)) return "coverage"
    return null
}

private val WRAP_ADVISORY = mapOf(
    "coverage" to "There is enough here to write it up whenever you like.",
    "exhausted" to "The last couple of answers did not add much — worth wrapping up.",
    "soft_ceiling" to "That is $SOFT_TURN_CEILING questions. Wrap up whenever you like.",
    "hard_ceiling" to "That is the last question.",
)

/** What to say when the interview is ready to end, or null if it has been said already. */
fun wrapAdvisory(session: JsonElement?, reason: String?): String? {
    if (reason.isNullOrEmpty() || (truthy(session) && truthy(session["wrapOffered"]))) return null
    return WRAP_ADVISORY[reason]
}

// ─────────────────────────────────────────────── move policy

/** The legal moves this turn; the model picks one and names it. */
fun legalMoves(session: JsonObject, targetId: String, lastClass: String = "substantive"): List<String> {
    val n = turnsOf(session).size + 1
    val cov = coverageOf(session)[targetId] ?: obj("level" to str("thin"), "turnsSpent" to JsonPrimitive(0))
    val thin = stringOf(cov["level"]) == "thin"
    val set: List<String> = when {
        n == 1 -> listOf("concretize", "scope_cut", "analogue")
        lastClass == "terse" || lastClass == "idk" || lastClass == "dodge" -> listOf("menu", "concretize", "sample_output")
        toNumber(cov["turnsSpent"]) >= 2 -> listOf("challenge", "tradeoff", "boundary")
        else -> when (targetId) {
            "voice" -> listOf("sample_output", "analogue", "define_term")
            "bar" -> listOf("premortem", "anti_goal", "concretize")
            "constraints" -> listOf("scope_cut", "tradeoff", "boundary")
            "audience" -> listOf("concretize", "who_else", "boundary")
            "references" -> listOf("analogue", "define_term")
            "outcome" -> if (thin) listOf("concretize", "scope_cut", "boundary") else listOf("scope_cut", "premortem", "challenge")
            else -> if (thin) listOf("concretize", "scope_cut", "boundary") else listOf("boundary", "tradeoff", "challenge", "define_term")
        }
    }
    val history = (session["moveHistory"] as? JsonArray ?: JsonArray(emptyList())).map { jsString(it) }
    val last = history.lastOrNull()
    val counts = history.groupingBy { it }.eachCount()
    // premortem and challenge land badly cold.
    val out = set.filter { m ->
        m != last && (counts[m] ?: 0) < 3 && !(n < 4 && (m == "premortem" || m == "challenge"))
    }.toMutableList()
    if (n >= 4 && (counts["premortem"] ?: 0) == 0 && "premortem" !in out) out += "premortem"
    return out.ifEmpty { listOf("concretize", "boundary") }
}

// ─────────────────────────────────────────────── the turn prompt
// MUST be a pure function of session state: no timestamps, no randomness. An interrupted
// call is recovered by re-issuing the byte-identical prompt.

private fun coverageBlock(session: JsonObject): String = DIMENSION_IDS.joinToString("\n") { id ->
    val c = coverageOf(session)[id]
    val d = getDimension(id)
    "$id | ${jsString(d["label"])} | ${jsString(c["level"])} | ${jsString(c["status"])} | ${if (truthy(c["gap"])) jsString(c["gap"]) else "—"}"
}

/** The turn prompt, split at its cache boundaries. */
data class PromptParts(val system: String, val prefix: String, val tail: String) {
    /** The one definition of "the prompt": system, prefix and tail joined by newlines. */
    fun joined(): String = "$system\n$prefix\n$tail"
}

/**
 * `system` never changes, `prefix` only grows at its end (idea, facts, transcript) so a
 * provider can cache it, and `tail` holds the volatile parts and must come last.
 */
fun buildTurnPromptParts(
    session: JsonObject, target: String?, moves: List<String>, injected: String? = null, budget: Int = BUDGET_BYTES,
): PromptParts {
    val t = getDimension(target)
    val lastClass = lastAnswerClass(session)
    val transcript = buildTranscriptBlock(session, budget).text
    val moveMenu = moves.joinToString("\n") { m -> "  ${jsPadEnd(m, 14)} ${MOVES[m]?.let { jsString(it) } ?: "undefined"}" }
    val opening = session["opening"]

    val prefix = listOf(
        "=== THE IDEA (their opening statement, verbatim) ===",
        if (truthy(opening)) jsString(opening) else "(not yet given)",
        "",
        "=== FACTS ESTABLISHED SO FAR ===",
        "Treat every one of these as ALREADY KNOWN. Asking about one is the worst error you can make.",
        serializeFacts(session["facts"]),
        "",
        "=== TRANSCRIPT ===",
        "Answers marked [voice] are dictated: read for intent, tolerate transcription errors,",
        "and never ask them to clarify a mis-transcription.",
        transcript,
    ).filter { it != "" }.joinToString("\n")

    val cov = coverageOf(session)[target!!]
    // Array.join prints null as empty, unlike String(null).
    val history = (session["moveHistory"] as? JsonArray ?: JsonArray(emptyList())).joinToString(", ") { if (it is JsonNull) "" else jsString(it) }
    val tail = listOf(
        "=== COVERAGE MAP ===",
        "Format: id | label | level | status | gap",
        coverageBlock(session),
        "",
        "=== THIS TURN ===",
        "Turn number: ${turnsOf(session).size + 1}",
        "Target dimension: $target (${jsString(t["label"])})",
        "What \"covered\" means here: ${jsString(t["covered"])}",
        "Known gap: ${if (truthy(cov["gap"])) jsString(cov["gap"]) else "(nothing established yet)"}",
        "Turns already spent on it: ${jsString(cov["turnsSpent"])}",
        "Last answer quality: $lastClass",
        "Moves already used: ${history.ifEmpty { "(none)" }}",
        "",
        "Pick exactly ONE move from this list and name it. Do not invent or blend moves.",
        moveMenu,
        if (!injected.isNullOrEmpty()) "\nADDITIONAL DIRECTIVE FOR THIS TURN:\n$injected" else "",
        "",
        "=== OUTPUT — return this JSON object and nothing else ===",
        "{",
        "  \"bridge\": string | null,",
        "  \"question\": string,",
        "  \"move\": one of ${jsonStringArray(moves)},",
        "  \"dimension\": string,",
        "  \"chips\": [string, ...],",
        "  \"new_facts\": [{\"dimension\": string, \"fact\": string}],   // <=3, from their LAST answer only",
        "  \"coverage\": { \"<dimension_id>\": {\"level\": \"thin\"|\"partial\"|\"covered\",",
        "                                   \"gap\": string|null, \"evidence\": string|null} },",
        "  \"suggest_wrap\": boolean,",
        "  \"wrap_reason\": string | null",
        "}",
    ).filter { it != "" }.joinToString("\n")

    return PromptParts(RULES, prefix, tail)
}

/** The complete prompt. Deterministic for a given session state. */
fun buildTurnPrompt(session: JsonObject, target: String?, moves: List<String>, injected: String? = null, budget: Int = BUDGET_BYTES): String =
    buildTurnPromptParts(session, target, moves, injected, budget).joined()

/**
 * The no-model fallback: the first bank question not yet asked, preferring the target and
 * then any other dimension still being probed. Deterministic, so a degraded interview
 * replays identically.
 */
fun pickBankQuestion(session: JsonObject, targetId: String?): Pair<String, String>? {
    val coverage = coverageOf(session)
    val asked = turnsOf(session).map { it["question"] }.toSet()
    val rest = DIMENSION_IDS.filter { id -> id != targetId && coverage[id] != null && stringOf(coverage[id]["status"]) == "probing" }
    for (id in listOf(targetId) + rest) {
        if (id.isNullOrEmpty() || coverage[id] == null) continue
        val bank = getDimension(id)["bank"] as JsonArray
        val question = bank.firstOrNull { q -> asked.none { it is JsonPrimitive && it.isString && it.content == stringOf(q) } }
        if (question != null) return stringOf(question)!! to id
    }
    return null
}

// ─────────────────────────────────────────────── result validation
// The model's JSON validates NOTHING. Never trust the shape.

private val RE_GENERIC = jsRegexI("target audience|go${JS_DOT}to${JS_DOT}market|value prop|at scale|monetiz|user persona|pain point|north star")
private val BANNED_BRIDGE = jsRegexI(
    "${JS_B}(great|interesting|love|smart|excellent|awesome|perfect|helpful|insightful|good point|makes sense|absolutely|totally)$JS_B",
)

private fun isPlainObject(e: JsonElement?): Boolean = e is JsonObject
private fun isObjectish(e: JsonElement?): Boolean = e is JsonObject || e is JsonArray

/** One coverage claim, every field optional and nothing trusted. */
private fun readClaim(c: JsonElement?): JsonObject = obj(
    "level" to (stringOf(c["level"])?.takeIf { it in LEVELS }?.let(::str) ?: JsonNull),
    "gap" to (stringOf(c["gap"])?.let { str(jsTrim(it)) } ?: JsonNull),
    "evidence" to (stringOf(c["evidence"])?.let { str(jsTrim(it)) } ?: JsonNull),
)

private fun wordCount(s: String): Int = jsSplitSpaces(s).size

/** Validate a turn result from the model. Returns the same shape as the JS. */
fun parseTurnResult(raw: JsonElement?, moves: List<String> = MOVE_IDS, target: String? = null): JsonObject {
    val warnings = mutableListOf<String>()
    val o: JsonObject = raw as? JsonObject ?: JsonObject(emptyMap())

    val question = stringOf(o["question"])?.let(::jsTrim) ?: ""
    if (question.isEmpty()) warnings += "no question"

    var bridge: String? = stringOf(o["bridge"])?.let(::jsTrim)
    if (!bridge.isNullOrEmpty() && (BANNED_BRIDGE.containsMatchIn(bridge) || wordCount(bridge) > 15)) {
        bridge = null                       // silently drop; never regenerate for this
        warnings += "bridge dropped"
    }

    val oMove = o["move"]
    val moveOk = stringOf(oMove)?.let { it in moves } ?: false
    val move = if (moveOk) stringOf(oMove) else moves.firstOrNull()
    if (truthy(oMove) && !moveOk) warnings += "illegal move ${stringOf(oMove) ?: jsTypeof(oMove)}"

    val dimension = stringOf(o["dimension"])?.takeIf { it in DIMENSION_IDS } ?: target

    var chips = (o["chips"] as? JsonArray)
        ?.mapNotNull { stringOf(it) }?.filter { jsTrim(it).isNotEmpty() }?.map(::jsTrim)?.take(4)
        ?: emptyList()
    chips = chips.filter { wordCount(it) <= 14 }

    val facts = (o["new_facts"] as? JsonArray)
        ?.filter { f -> truthy(f) && stringOf(f["fact"])?.let { jsTrim(it).isNotEmpty() } == true }
        ?.map { f ->
            obj(
                "dimension" to (stringOf(f["dimension"])?.takeIf { it in DIMENSION_IDS }?.let(::str) ?: JsonNull),
                "fact" to str(jsTrim(stringOf(f["fact"])!!)),
            )
        }?.take(3)
        ?: emptyList()

    val coverage = LinkedHashMap<String, JsonElement>()
    val oc = o["coverage"]
    if (truthy(oc) && isObjectish(oc)) {
        for (id in DIMENSION_IDS) {
            val c = if (oc is JsonObject) oc[id] else null
            if (!truthy(c) || !isObjectish(c)) continue
            coverage[id] = readClaim(c)
        }
        // A smaller model often gets the KEY wrong while getting the judgement right; a single
        // unkeyed claim can only be about this turn's target, and the ratchet still applies.
        if (coverage.isEmpty() && target != null) {
            val entries: List<Pair<String, JsonElement>> = when (oc) {
                is JsonObject -> oc.entries.map { it.key to it.value }
                is JsonArray -> oc.mapIndexed { i, v -> i.toString() to v }
                else -> emptyList()
            }
            val claims = entries.filter { (_, c) -> truthy(c) && isPlainObject(c) }
            if (claims.size == 1) {
                coverage[target] = readClaim(claims[0].second)
                warnings += "coverage keyed by ${jsonQuote(claims[0].first)}, read as $target"
            }
        }
    }

    return obj(
        "ok" to bool(question.isNotEmpty()),
        "bridge" to (bridge?.let(::str) ?: JsonNull),
        "question" to str(question),
        "move" to (move?.let(::str) ?: JsonNull),
        "dimension" to (dimension?.let(::str) ?: JsonNull),
        "chips" to arr(chips.map(::str)),
        "facts" to arr(facts),
        "coverage" to JsonObject(coverage),
        "suggestWrap" to bool(o["suggest_wrap"].let { it is JsonPrimitive && !it.isString && it.content == "true" }),
        "wrapReason" to (stringOf(o["wrap_reason"])?.let { str(jsTrim(it)) } ?: JsonNull),
        "warnings" to arr(warnings.map(::str)),
    )
}

/** Tripwires. A hit means regenerate ONCE with a corrective directive. */
fun questionTripwire(question: JsonElement?, session: JsonObject): String? {
    val q = if (truthy(question)) jsString(question) else ""
    if (RE_GENERIC.containsMatchIn(q)) return "generic"
    if (q.count { it == '?' } > 1) return "compound"
    // Two interrogatives joined by and/or is compound at ANY length.
    val interrogatives = INTERROGATIVE.findAll(q).count()
    if (interrogatives >= 2 && AND_OR.containsMatchIn(q)) return "compound"
    if (jsSplitSpaces(q).size > 20 && AND_OR.containsMatchIn(q)) return "compound"
    val asked = turnsOf(session).map { contentWords(it["question"]).toSet() }
    val now = contentWords(str(q))
    if (now.size >= 3) {
        for (prev in asked) {
            if (prev.isEmpty()) continue
            val overlap = now.count { it in prev }.toDouble() / now.size
            if (overlap > 0.6) return "repeat"
        }
    }
    return null
}

private val INTERROGATIVE = jsRegexI("${JS_B}(what|why|how|who|when|where|which)$JS_B")
private val AND_OR = jsRegexI("${JS_B}(and|or)$JS_B")

val TRIPWIRE_DIRECTIVE: Map<String, String> = linkedMapOf(
    "generic" to "Your previous question was generic MBA-speak. Ask something specific that only this person could answer.",
    "compound" to "Your previous question was compound. Ask ONE thing, in fewer than 20 words.",
    "repeat" to "Your previous question repeated one already asked. Ask about something genuinely new.",
)
