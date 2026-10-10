package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_B
import io.github.jaypetez.ideaforge.core.js.JS_END
import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.jsLower
import io.github.jaypetez.ideaforge.core.js.jsRegexI
import io.github.jaypetez.ideaforge.core.js.jsSlice
import io.github.jaypetez.ideaforge.core.js.jsSplitSpaces
import io.github.jaypetez.ideaforge.core.js.jsTrim

// src/core/driving.js: reading intent out of a dictated utterance. Has the speaker finished,
// and did they mean it as an answer at all. Replayed against the `speech` golden vectors.
//
// Three rules, each earned by a failure it prevents (driving.js says why at length):
//
//   1. The trigger is TERMINAL ONLY. "Over" is an ordinary word (over budget, over the
//      years), almost never the last one. Matched anywhere, every one truncates an answer.
//   2. A command must be the WHOLE utterance. "I'd skip this one if I could" is an answer.
//   3. A command carries NO answer text. That is what stops it reaching submitAnswer, where
//      RE_REFUSAL would read "skip this one" as a refusal and cap the dimension's coverage.
//
// The patterns are spelled with the JsRegex constants because JS `\s`, `\b` and `$` are not
// Java's: a transcript with a no-break space or a trailing newline must split and end where
// the web app says it does.

/** `DRIVING.trigger`: the word that ends a spoken answer when none is chosen. */
const val DEFAULT_TRIGGER = "over"

/** `DRIVING.maxMisses`: consecutive captures with nothing usable before the question is re-read. */
const val DRIVING_MAX_MISSES = 2

/** `DRIVING.settleMs`: how long a trigger seen in an interim result must stand before it counts. */
const val DRIVING_SETTLE_MS = 600

/** What one utterance meant. `kind` and `stopped` are orthogonal: "skip this one, over" is both. */
data class SpeechParse(
    /** `repeat`, `skip`, `wrap`, `scratch`, `pause`, `exit` or `answer`. */
    val kind: String,
    /** The original transcript minus the trigger; empty for every command (rule 3). */
    val text: String,
    val stopped: Boolean,
)

/**
 * `COMMANDS`: every phrase that means each command, matched against the whole utterance and
 * nothing less. Ordered as the JS object is, because the first kind whose list holds the
 * phrase wins.
 */
val COMMANDS: List<Pair<String, List<String>>> = listOf(
    "repeat" to listOf(
        "repeat", "repeat that", "repeat the question", "say that again", "say again",
        "come again", "what was that", "can you repeat that", "could you repeat that",
    ),
    "skip" to listOf(
        "skip", "skip it", "skip this", "skip that", "skip this one",
        "skip this question", "next question", "next one", "pass", "move on",
    ),
    "wrap" to listOf(
        "wrap it up", "wrap up", "wrap this up", "write it up", "thats enough",
        "that is enough", "finish up", "im done", "i am done",
    ),
    "scratch" to listOf(
        "scratch that", "scratch", "scratch that answer", "strike that", "ignore that",
        "forget that", "start over", "let me try again", "try again",
    ),
    "pause" to listOf("pause voice", "pause"),
    "exit" to listOf("exit voice"),
)

/** `RISKY_TRIGGERS`: words people say mid-answer often enough to be cut off by them. */
private val RISKY_TRIGGERS: Set<String> =
    "the and a an i is it ok no yes so that this right one to of in on for".split(' ').toSet()

private val NOT_TRIGGER_CHAR = Regex("[^a-z$JS_S'’]")
private val JS_SPACE_RUN = Regex("[$JS_S]+")

/** Filler a speaker trails after the trigger without meaning anything by it. */
private const val TAIL_FILLER = "(?:um+|uh+|erm?|ah+|yeah|yep|ok|okay|right|then|please|and[$JS_S]+out|out)"

// Composable ("yeah, do it", "no, keep going"), but each alternative must consume the whole
// utterance, so "no idea" stays an answer. `\W` is JS's ASCII class, spelled out.
private val RE_YES = jsRegexI(
    "^(?:(?:yes|yeah|yep|yup|sure|ok|okay|do it|go ahead|please do|go on|correct)[^A-Za-z0-9_]*)+$JS_END",
)
private val RE_NO = jsRegexI(
    "^(?:(?:no|nope|not yet|keep going|carry on|more questions|not done|keep asking|wait)[^A-Za-z0-9_]*)+$JS_END",
)

/** Politeness a driver prefixes to a command without meaning it as part of one. */
private val RE_LEAD = jsRegexI("^(ok|okay|hey|please|app|ideaforge)[$JS_S]+")

private val NOT_MATCH_CHAR = Regex("[^a-z0-9$JS_S]")
private val TRAILING_CHIP_PUNCT = Regex("[.,;:]+$JS_END")

/**
 * Lowercase, strip punctuation, collapse whitespace, for MATCHING only. Never the answer
 * text: dropping apostrophes is what lets "i'm done" and "im done" reach one COMMANDS entry.
 */
private fun normalize(text: String): String =
    jsTrim(JS_SPACE_RUN.replace(NOT_MATCH_CHAR.replace(jsLower(text), ""), " "))

/** JS `escapeRe`: the JS metacharacters, which are also every Java one outside a class. */
private fun escapeRe(s: String): String = buildString {
    for (c in s) {
        if (c in ".*+?^\${}()|[]\\") append('\\')
        append(c)
    }
}

/** A multi-word trigger tolerates any punctuation between its words and an optional
 *  apostrophe inside them, because a transcriber inserts both unpredictably. */
private fun triggerPattern(word: String): String =
    jsSplitSpaces(jsTrim(word)).joinToString("[^A-Za-z0-9]+") { t ->
        escapeRe(t).replace(Regex("['’]"), "['’]?")
    }

/**
 * The trigger, plus trailing filler and punctuation, at the very end. The leading class omits
 * `.` `!` `?`: a comma before the trigger is debris it caused, a full stop is the speaker's
 * own sentence ending and belongs to the answer.
 */
private fun tailRe(trigger: String): Regex = jsRegexI(
    "[$JS_S,;:—–-]*$JS_B${triggerPattern(trigger)}$JS_B" +
        "(?:[$JS_S,.;:!?—–-]+$TAIL_FILLER)*[$JS_S,.;:!?\"'’]*$JS_END",
)

/** Did this utterance end with the trigger word? Rule 1: terminal only. */
fun endsWithTrigger(text: String?, trigger: String? = DEFAULT_TRIGGER): Boolean {
    val t = text ?: ""
    if (jsTrim(t).isEmpty() || jsTrim(trigger ?: "").isEmpty()) return false
    return tailRe(trigger!!).containsMatchIn(t)
}

/** The utterance with a terminal trigger and its filler removed. Unchanged (trimmed) if none. */
fun stripTrigger(text: String?, trigger: String? = DEFAULT_TRIGGER): String {
    val t = text ?: ""
    if (!endsWithTrigger(t, trigger)) return jsTrim(t)
    return jsTrim(tailRe(trigger!!).replaceFirst(t, ""))
}

private fun commandFor(normalized: String): String? {
    val body = jsTrim(RE_LEAD.replaceFirst(normalized, ""))
    if (body.isEmpty()) return null
    return COMMANDS.firstOrNull { (_, phrases) -> body in phrases }?.first
}

/**
 * Read one captured utterance, trigger word and all.
 *
 * `text` is the ORIGINAL transcript minus the trigger, case and punctuation intact, because it
 * is what gets recorded as the answer. It is empty for every command (rule 3).
 */
fun parseSpeech(text: String?, trigger: String? = DEFAULT_TRIGGER): SpeechParse {
    val raw = text ?: ""
    val stopped = endsWithTrigger(raw, trigger)
    val body = stripTrigger(raw, trigger)
    // The stripped body first, so "scratch that, over" scratches; then the full text, so
    // "start over" is not mistaken for a bare "start".
    val kind = commandFor(normalize(body)) ?: commandFor(normalize(raw))
    if (kind != null) return SpeechParse(kind, "", stopped)       // rule 3
    return SpeechParse("answer", body, stopped)
}

/**
 * Yes, no, or neither, for the one closed question the app asks. Null rather than a guess:
 * wrapping up against an unclear answer ends the interview and cannot be undone by voice.
 */
fun matchAffirmation(text: String?): Boolean? {
    val t = normalize(text ?: "")
    if (t.isEmpty()) return null
    if (RE_YES.containsMatchIn(t)) return true
    if (RE_NO.containsMatchIn(t)) return false
    return null
}

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

/**
 * The suggestion chips, as a sentence to read out: hands-free is the one mode where the
 * screen is deliberately not being looked at. Examples, not a menu.
 *
 * A word budget rather than a chip count: four fourteen-word chips outlast the speech
 * watchdog on their own. The first chip is always kept, however long, because a budget that
 * can return nothing turns a long chip into silence. The drive loop says this as its OWN
 * utterance, after the question; joining the two is the bug this exists to prevent.
 *
 * [chips] null is JS's non-array; a null entry is any non-string, which JS filters out.
 */
fun spokenExamples(chips: List<String?>?, maxWords: Int = 34): String {
    val kept = (chips ?: emptyList())
        .filterNotNull()
        .filter { jsTrim(it).isNotEmpty() }
        .map { TRAILING_CHIP_PUNCT.replaceFirst(jsTrim(it), "") }
        .toMutableList()
    if (kept.isEmpty()) return ""

    fun words(list: List<String>) = jsSplitSpaces(list.joinToString(" ")).size
    while (kept.size > 1 && words(kept) > maxWords) kept.removeAt(kept.size - 1)

    val list = if (kept.size == 1) kept[0]
    else "${kept.dropLast(1).joinToString(", ")}, or ${kept.last()}"
    return "For example: $list."
}
