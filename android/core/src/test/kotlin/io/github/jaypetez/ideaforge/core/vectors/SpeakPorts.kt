package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.speechChunksForRate
import io.github.jaypetez.ideaforge.core.speechTimeoutMs
import io.github.jaypetez.ideaforge.core.splitSpeechText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// Adapters for the `speak` family (src/voice/speak.js, #90). A JS number becomes a Double;
// anything else (a string, null) becomes NaN, which fails the same validation JS applies to
// a non-number. A missing argument or key takes the JS default.

private fun jsNumberArg(e: JsonElement?, default: Double): Double = when {
    e == null -> default
    e is JsonPrimitive && !e.isString && e.doubleOrNull != null -> e.doubleOrNull!!
    else -> Double.NaN
}

private fun strings(items: List<String>): JsonElement = JsonArray(items.map { JsonPrimitive(it) })

val SPEAK_PORTS: Map<String, Port> = mapOf(
    "splitSpeechText" to { c ->
        val o = c.args.getOrNull(1) as? JsonObject
        strings(splitSpeechText(
            c.args.getOrNull(0),
            jsNumberArg(o?.get("maxChars"), 350.0),
            jsNumberArg(o?.get("maxWords"), Double.POSITIVE_INFINITY),
        ))
    },
    "speechChunksForRate" to { c ->
        strings(speechChunksForRate(c.args.getOrNull(0), jsNumberArg(c.args.getOrNull(1), 1.0)))
    },
    "speechTimeoutMs" to { c ->
        json(speechTimeoutMs(jsString(c.args[0]), jsNumberArg(c.args.getOrNull(1), 1.0)))
    },
)
