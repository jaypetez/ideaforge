package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.DIMENSIONS
import io.github.jaypetez.ideaforge.core.DIMENSION_IDS
import io.github.jaypetez.ideaforge.core.LEVELS
import io.github.jaypetez.ideaforge.core.LEVEL_RANK
import io.github.jaypetez.ideaforge.core.MAX_SESSION_NAME
import io.github.jaypetez.ideaforge.core.MAX_TAGS
import io.github.jaypetez.ideaforge.core.MAX_TAG_LENGTH
import io.github.jaypetez.ideaforge.core.MOVES
import io.github.jaypetez.ideaforge.core.MOVE_IDS
import io.github.jaypetez.ideaforge.core.SCHEMA
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.STATUSES
import io.github.jaypetez.ideaforge.core.addFacts
import io.github.jaypetez.ideaforge.core.answerQuestion
import io.github.jaypetez.ideaforge.core.applyCoverage
import io.github.jaypetez.ideaforge.core.askQuestion
import io.github.jaypetez.ideaforge.core.copySession
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.isLowConfidence
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.opt
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.migrate
import io.github.jaypetez.ideaforge.core.normalizeSessionName
import io.github.jaypetez.ideaforge.core.normalizeTags
import io.github.jaypetez.ideaforge.core.reopen
import io.github.jaypetez.ideaforge.core.resumeDimension
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.core.skipQuestion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Adapters from a recorded JS call (positional args, option objects with JS defaults) to the
// Kotlin port. JS destructuring defaults apply only to a MISSING key, which is what `opt`
// reproduces; an explicit null stays null.

private fun Case.arg(i: Int): JsonElement? = args.getOrNull(i)
private fun JsonElement?.s(): JsonObject = this as JsonObject
private fun JsonElement?.orNow(): JsonElement = this ?: num(0)

val SESSION_PORTS: Map<String, Port> = mapOf(
    "createSession" to { c ->
        val o = c.arg(0)
        val id = o["id"]
        if (!truthy(id)) createSession(null)
        createSession(stringOf(id) ?: jsString(id), o.opt("now", num(0))!!, o.opt("device", str("desktop"))!!)
    },
    "applyCoverage" to { c ->
        val o = c.arg(2)
        applyCoverage(
            c.arg(0).s(), c.arg(1),
            lowConfidenceDimension = o.opt("lowConfidenceDimension", JsonNull),
            cappedDimensions = o.opt("cappedDimensions", arr(emptyList())),
            now = o.opt("now", num(0)),
        )
    },
    "addFacts" to { c -> addFacts(c.arg(0).s(), c.arg(1), c.arg(2).orNow()) },
    "skipQuestion" to { c -> skipQuestion(c.arg(0).s(), c.arg(1).opt("now", num(0))) },
    "answerQuestion" to { c ->
        val o = c.arg(1)
        answerQuestion(
            c.arg(0).s(), o["text"], o.opt("source", str("typed")), o.opt("classification", JsonNull),
            o.opt("now", num(0)),
        )
    },
    "askQuestion" to { c ->
        val o = c.arg(1)
        askQuestion(
            c.arg(0).s(), o["question"], o["dimension"], o.opt("move", JsonNull), o.opt("chips", arr(emptyList())),
            o.opt("bridge", JsonNull), o.opt("source", str("model")), o.opt("now", num(0)),
        )
    },
    "isLowConfidence" to { c -> JsonPrimitive(isLowConfidence(c.arg(0))) },
    "resumeDimension" to { c -> resumeDimension(c.arg(0).s(), stringOf(c.arg(1)), c.arg(2).orNow()) },
    "copySession" to { c ->
        val o = c.arg(1)
        copySession(c.arg(0).s(), o["id"], o.opt("name", JsonNull), o.opt("now", num(0)))
    },
    "reopen" to { c -> reopen(c.arg(0).s(), c.arg(1).orNow()) },
    "normalizeSessionName" to { c -> str(normalizeSessionName(c.arg(0))) },
    "normalizeTags" to { c -> arr(normalizeTags(c.arg(0)).map(::str)) },
    "sessionDisplayTitle" to { c ->
        str(if (c.args.size > 1) sessionDisplayTitle(c.arg(0), stringOf(c.arg(1))!!) else sessionDisplayTitle(c.arg(0)))
    },
    "migrate" to { c -> migrate(c.arg(0)) ?: JsonNull },
)

/** The constants this slice owns, by exported name; the rest are owed to later slices. */
val CONSTANTS_PORTED: Map<String, JsonElement> = mapOf(
    "DIMENSIONS" to DIMENSIONS,
    "DIMENSION_IDS" to arr(DIMENSION_IDS.map(::str)),
    "LEVELS" to arr(LEVELS.map(::str)),
    "LEVEL_RANK" to obj(LEVEL_RANK.map { (k, v) -> k to num(v) }.toMap()),
    "MOVES" to MOVES,
    "MOVE_IDS" to arr(MOVE_IDS.map(::str)),
    "SEED_QUESTION" to str(SEED_QUESTION),
    "STATUSES" to arr(STATUSES.map(::str)),
    "SCHEMA" to num(SCHEMA),
    "MAX_SESSION_NAME" to num(MAX_SESSION_NAME),
    "MAX_TAGS" to num(MAX_TAGS),
    "MAX_TAG_LENGTH" to num(MAX_TAG_LENGTH),
) + BACKUP_CONSTANTS

val CONSTANT_PORT: Port = { c ->
    val name = stringOf(c.arg(0))!!
    (CONSTANTS_PORTED + ENGINE_CONSTANTS)[name] ?: error("constant $name is not ported")
}

/** Constants still owed, by the case name tools/vectors.mjs gives them. */
val CONSTANTS_PENDING: Map<String, String> = buildMap {
    fun owe(issue: String, path: String, vararg names: String) = names.forEach { put("$path $it", issue) }
    owe("#77", "src/core/driving.js", "COMMANDS", "DRIVING")
    owe("#74", "src/core/synthesis.js", "MAX_ASSUMPTIONS", "MAX_OPEN_QUESTIONS", "MAX_TITLE_WORDS")
    owe("#74", "src/runtime/turn.js", "CAPPING_CLASSES")
}

