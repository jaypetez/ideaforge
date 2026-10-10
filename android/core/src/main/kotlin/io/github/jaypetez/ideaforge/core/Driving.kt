package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.jsLower
import io.github.jaypetez.ideaforge.core.js.jsSlice
import io.github.jaypetez.ideaforge.core.js.jsTrim

// The trigger-word half of src/core/driving.js: what the settings screen needs to clean up a
// typed finish word and warn about a risky one. The rest of driving.js (parseSpeech, the
// command matcher, spokenExamples) is the hands-free port's, #77, and lands beside these.
// Replayed against the `speech` golden vectors.

/** `DRIVING.trigger`: the word that ends a spoken answer when none is chosen. */
const val DEFAULT_TRIGGER = "over"

/** `RISKY_TRIGGERS`: words people say mid-answer often enough to be cut off by them. */
private val RISKY_TRIGGERS: Set<String> =
    "the and a an i is it ok no yes so that this right one to of in on for".split(' ').toSet()

private val NOT_TRIGGER_CHAR = Regex("[^a-z$JS_S'’]")
private val JS_SPACE_RUN = Regex("[$JS_S]+")

/**
 * Clean up a hand-typed trigger. An empty one falls back rather than disabling the rule.
 * [raw] is JS `String(raw == null ? '' : raw)` already applied by the caller when it is not
 * a string; null is the JS `null`/`undefined`.
 */
fun normalizeTrigger(raw: String?): String {
    val lowered = jsLower(raw ?: "")
    val cleaned = jsTrim(JS_SPACE_RUN.replace(NOT_TRIGGER_CHAR.replace(lowered, " "), " "))
    return jsTrim(jsSlice(cleaned, 24)).ifEmpty { DEFAULT_TRIGGER }
}

/**
 * Why this trigger will misbehave, in words worth showing under the field, or null.
 * Advisory, never enforced: someone who genuinely wants "right" should be able to have it.
 */
fun triggerWarning(word: String?): String? {
    val t = normalizeTrigger(word)
    if (t.length < 2) return "A single letter is too easy to mishear. Try a whole word."
    if (t in RISKY_TRIGGERS) {
        return "“$t” is common enough that you will say it mid-answer and be cut off. " +
            "Something you rarely say works better."
    }
    return null
}
