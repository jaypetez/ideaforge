package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_END
import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.isJsSpace
import io.github.jaypetez.ideaforge.core.js.jsTrim
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Native speech output's chunking and per-chunk wait: the port of splitSpeechText,
// speechChunksForRate and speechTimeoutMs in src/voice/speak.js (the `speak` vector family).
//
// Why it exists at all: an engine silently drops, or never finishes, an utterance that
// outlasts its own watchdog. So output is never one whole prompt in one utterance; each
// chunk is short enough to finish and gets a timeout scaled to its length, after which the
// speaker gives up on it rather than waiting for ever.

/** Thrown with the JS message, so a recorded throw replays byte for byte. */
class SpeechConfigException(message: String) : IllegalArgumentException(message)

private const val LIMITS_MESSAGE = "Speech chunk limits must be positive integers."
private val NON_SPACE = Regex("[^$JS_S]+")
private val LAST_GAP = Regex("[$JS_S]+[^$JS_S]*$JS_END")
private val ANY_SPACE = Regex("[$JS_S]+")

private fun isInteger(d: Double) = d.isFinite() && d == floor(d)
private fun isHigh(c: Char) = c in '\uD800'..'\uDBFF'
private fun isLow(c: Char) = c in '\uDC00'..'\uDFFF'

/** JS `str.trimStart()`. */
private fun jsTrimStart(s: String): String {
    var i = 0
    while (i < s.length && isJsSpace(s[i])) i++
    return s.substring(i)
}

/** validateSpeechRate in speak.js, with its defaults. A non-number arrives as NaN. */
fun validateSpeechRate(rate: Double, min: Double = 0.1, max: Double = 10.0): Double {
    if (!rate.isFinite() || rate < min || rate > max) {
        throw SpeechConfigException("Speech rate must be a number from ${jsNum(min)} to ${jsNum(max)}.")
    }
    return rate
}

private fun jsNum(d: Double): String = if (d == floor(d)) d.toLong().toString() else d.toString()

/**
 * Keep [speechChunks]' sentence grouping, but enforce the character limit even for one giant
 * sentence or token, never split a UTF-16 surrogate pair, never drop non-space text, and cap
 * each chunk at [maxWords] words. Limits are JS numbers: [maxChars] an integer of at least 2,
 * [maxWords] an integer of at least 1 or infinity.
 */
fun splitSpeechText(
    text: JsonElement?,
    maxChars: Double = 350.0,
    maxWords: Double = Double.POSITIVE_INFINITY,
): List<String> {
    if (!isInteger(maxChars) || maxChars < 2 ||
        (maxWords != Double.POSITIVE_INFINITY && (!isInteger(maxWords) || maxWords < 1))
    ) throw SpeechConfigException(LIMITS_MESSAGE)
    val chars = maxChars.toInt()
    val out = mutableListOf<String>()
    for (sentence in speechChunks(text, chars)) {
        var rest = jsTrim(sentence)
        while (rest.isNotEmpty()) {
            var end = min(chars, rest.length)
            if (end < rest.length && isHigh(rest[end - 1]) && isLow(rest[end])) end--
            val part = rest.substring(0, end)
            val words = NON_SPACE.findAll(part).toList()
            if (words.size > maxWords) {
                end = words[maxWords.toInt()].range.first
            } else if (end < rest.length && !isJsSpace(rest[end])) {
                val boundary = LAST_GAP.find(part)?.range?.first ?: -1
                if (boundary > 0) end = boundary
            }
            val chunk = jsTrim(rest.substring(0, end))
            if (chunk.isNotEmpty()) out.add(chunk)
            rest = jsTrimStart(rest.substring(end))
        }
    }
    return out
}

fun splitSpeechText(text: String, maxChars: Int = 350, maxWords: Int? = null): List<String> =
    splitSpeechText(JsonPrimitive(text), maxChars.toDouble(), maxWords?.toDouble() ?: Double.POSITIVE_INFINITY)

/**
 * The chunks native output actually speaks at [rate]: a word budget that shrinks at slower
 * rates, so one chunk's duration, and so its timeout, stays inside the engine's watchdog.
 */
fun speechChunksForRate(text: JsonElement?, rate: Double = 1.0): List<String> {
    validateSpeechRate(rate)
    val maxWords = max(1.0, floor(24 * min(rate, 1.0)))
    return splitSpeechText(text, maxWords * 5, maxWords)
}

fun speechChunksForRate(text: String, rate: Double = 1.0): List<String> =
    speechChunksForRate(JsonPrimitive(text), rate)

/**
 * How long one chunk may take before the speaker stops it and reports a timeout. Character
 * length catches languages and long tokens without spaces; only slower rates lengthen the
 * budget, because engines clamp fast ones. Capped at thirty seconds.
 */
fun speechTimeoutMs(text: String, rate: Double = 1.0): Long {
    validateSpeechRate(rate)
    val words = jsTrim(text).split(ANY_SPACE).size
    val seconds = max(words / 2.6, text.length / 13.0) / min(rate, 1.0)
    return min(30000.0, 2000 + ceil(seconds * 1000)).toLong()
}
