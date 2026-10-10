package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.endsWithTrigger
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.matchAffirmation
import io.github.jaypetez.ideaforge.core.normalizeTrigger
import io.github.jaypetez.ideaforge.core.parseSpeech
import io.github.jaypetez.ideaforge.core.spokenExamples
import io.github.jaypetez.ideaforge.core.stripTrigger
import io.github.jaypetez.ideaforge.core.triggerWarning
import io.github.jaypetez.ideaforge.core.COMMANDS
import io.github.jaypetez.ideaforge.core.DEFAULT_TRIGGER
import io.github.jaypetez.ideaforge.core.DRIVING_MAX_MISSES
import io.github.jaypetez.ideaforge.core.DRIVING_SETTLE_MS
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** JS `String(raw == null ? '' : raw)`: the recorded cases include `null` and the number 7. */
private fun jsArg(e: JsonElement?): String? = if (e == null || e is JsonNull) null else jsString(e)

/** A recorded transcript; every case records a string. */
private fun text(c: Case): String? = stringOf(c.args.getOrNull(0))

/** A positional trigger argument: the JS default when it was not passed. */
private fun triggerArg(c: Case): String? =
    if (c.args.size > 1) stringOf(c.args[1]) else DEFAULT_TRIGGER

/** parseSpeech's `{trigger}` option, defaulted only when the key is missing, as JS does. */
private fun triggerOpt(c: Case): String? {
    val opts = c.args.getOrNull(1) as? JsonObject ?: return DEFAULT_TRIGGER
    return if ("trigger" in opts) stringOf(opts["trigger"]) else DEFAULT_TRIGGER
}

/** The `speech` family: src/core/driving.js, ported in Driving.kt. */
val SPEECH_PORTS: Map<String, Port> = mapOf(
    "normalizeTrigger" to { c -> json(normalizeTrigger(jsArg(c.args.getOrNull(0)))) },
    "triggerWarning" to { c -> json(triggerWarning(jsArg(c.args.getOrNull(0)))) },
    "parseSpeech" to { c ->
        val p = parseSpeech(text(c), triggerOpt(c))
        JsonObject(mapOf("kind" to json(p.kind), "text" to json(p.text), "stopped" to json(p.stopped)))
    },
    "endsWithTrigger" to { c -> json(endsWithTrigger(text(c), triggerArg(c))) },
    "stripTrigger" to { c -> json(stripTrigger(text(c), triggerArg(c))) },
    "matchAffirmation" to { c -> json(matchAffirmation(text(c))) },
    "spokenExamples" to { c ->
        // A non-array is JS's empty list; a non-string entry is filtered, as JS's typeof does.
        val chips = (c.args.getOrNull(0) as? JsonArray)?.map { stringOf(it) }
        val max = c.args.getOrNull(1)["maxWords"]?.let { toNumber(it).toInt() }
        json(if (max == null) spokenExamples(chips) else spokenExamples(chips, max))
    },
)

/** driving.js's exported constants, replayed through the constants family. */
val DRIVING_CONSTANTS: Map<String, JsonElement> = mapOf(
    "COMMANDS" to obj(COMMANDS.associate { (kind, phrases) -> kind to arr(phrases.map(::str)) }),
    "DRIVING" to obj("trigger" to str(DEFAULT_TRIGGER), "settleMs" to num(DRIVING_SETTLE_MS), "maxMisses" to num(DRIVING_MAX_MISSES)),
)
