package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.bool
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsSplitSpaces
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// The synthesis pass: the one call at the end of an interview that turns the transcript into
// the refined prompt. The Kotlin port of src/core/synthesis.js. Synthesis must not invent: a
// gap becomes an open question, never a plausible-sounding guess.

/** Caps on what we accept back, so one runaway field cannot dominate the export. */
const val MAX_ASSUMPTIONS = 6
const val MAX_OPEN_QUESTIONS = 8
const val MAX_TITLE_WORDS = 8

private fun coverageDigest(session: JsonObject): String = DIMENSION_IDS.joinToString("\n") { id ->
    val c = (session["coverage"] as JsonObject)[id]
    val d = getDimension(id)
    val probing = stringOf(c["status"]) == "probing"
    val state = if (!probing) jsString(c["status"]) else jsString(c["level"])
    val rank = LEVEL_RANK[stringOf(c["level"])]
    val note = if (probing && rank != null && rank < 2) {
        if (truthy(c["gap"])) jsString(c["gap"]) else "unspecified gap"
    } else {
        if (truthy(c["evidence"])) jsString(c["evidence"]) else "—"
    }
    "${jsString(d["section"])} | $state | $note"
}

/** The synthesis prompt, split at its cache boundary; `prefix` is the turn prompts' prefix. */
fun buildSynthesisPromptParts(session: JsonObject, budget: Int = BUDGET_BYTES): PromptParts {
    val transcript = buildTranscriptBlock(session, budget).text
    val opening = session["opening"]
    val prefix = listOf(
        "=== THE IDEA (their opening statement, verbatim) ===",
        if (truthy(opening)) jsString(opening) else "(not given)",
        "",
        "=== FACTS ESTABLISHED ===",
        serializeFacts(session["facts"]),
        "",
        "=== TRANSCRIPT ===",
        "Answers marked [voice] are dictated: read for intent, tolerate transcription errors.",
        transcript,
    ).filter { it != "" }.joinToString("\n")

    val tail = listOf(
        "=== COVERAGE AT WRAP ===",
        "Format: section | level | evidence or remaining gap",
        coverageDigest(session),
        "",
        "=== OUTPUT — return this JSON object and nothing else ===",
        "{",
        "  \"title\": string,",
        "  \"prompt\": string,                       // markdown, the refined prompt itself",
        "  \"assumptions\": [string, ...],           // <=$MAX_ASSUMPTIONS, [] if none",
        "  \"open_questions\": [{\"dimension\": string, \"question\": string, \"why_it_matters\": string}]",
        "}",
    ).joinToString("\n")

    return PromptParts(SYNTH_RULES, prefix, tail)
}

/** The complete synthesis prompt. Deterministic for a session state. */
fun buildSynthesisPrompt(session: JsonObject, budget: Int = BUDGET_BYTES): String =
    buildSynthesisPromptParts(session, budget).joined()

private fun trimmed(e: JsonElement?): String = stringOf(e)?.let(::jsTrim) ?: ""

private val TRAILING_PUNCTUATION = Regex("[.!?,;:$JS_S]+$")

/**
 * Validate a synthesis reply. The model is assumed to lie about shape: every field is
 * optional, and a missing prompt is a failure the caller falls back from.
 */
fun parseSynthesisResult(raw: JsonElement?): JsonObject {
    val warnings = mutableListOf<String>()
    val o = raw as? JsonObject ?: JsonObject(emptyMap())

    val text = trimmed(o["prompt"])
    if (text.isEmpty()) warnings += "no prompt"

    // JS `$` without the m flag anchors at the very end only; Java's also matches before a
    // final line terminator, so the find is checked to end at the true end of the string.
    var title = trimmed(o["title"]).let { t ->
        TRAILING_PUNCTUATION.find(t)?.takeIf { it.range.last == t.length - 1 }?.let { t.substring(0, it.range.first) } ?: t
    }
    if (jsSplitSpaces(title).count { it.isNotEmpty() } > MAX_TITLE_WORDS) {
        title = jsSplitSpaces(title).take(MAX_TITLE_WORDS).joinToString(" ")
        warnings += "title truncated"
    }

    val assumptionsRaw = o["assumptions"]
    val assumptions = (assumptionsRaw as? JsonArray)
        ?.map(::trimmed)?.filter { it.isNotEmpty() }?.take(MAX_ASSUMPTIONS)
        ?: emptyList()
    if (truthy(assumptionsRaw) && assumptionsRaw !is JsonArray) warnings += "assumptions not an array"

    val openQuestions = (o["open_questions"] as? JsonArray)
        ?.filter { q -> truthy(q) && (q is JsonObject || q is JsonArray) && trimmed(q["question"]).isNotEmpty() }
        ?.map { q ->
            obj(
                "dimension" to (stringOf(q["dimension"])?.takeIf { it in DIMENSION_IDS }?.let(::str) ?: JsonNull),
                "question" to str(trimmed(q["question"])),
                "why_it_matters" to (trimmed(q["why_it_matters"]).ifEmpty { null }?.let(::str) ?: JsonNull),
            )
        }?.take(MAX_OPEN_QUESTIONS)
        ?: emptyList()

    return obj(
        "ok" to bool(text.isNotEmpty()),
        "title" to str(title),
        "text" to str(text),
        "assumptions" to arr(assumptions.map(::str)),
        "openQuestions" to arr(openQuestions),
        "warnings" to arr(warnings.map(::str)),
    )
}
