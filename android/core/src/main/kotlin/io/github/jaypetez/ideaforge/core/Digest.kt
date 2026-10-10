package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Transcript compaction and the budgeter: the Kotlin port of src/core/digest.js.
//
// A long interview will not fit a model's input, so the transcript is sent verbatim while
// it fits, then as a fact ledger plus a sliding verbatim window. Only what is SENT is
// compacted; the export always keeps the full transcript.

const val MAX_INPUT_BYTES = 64 * 1024
/** Leave headroom for instructions and the output schema. */
const val BUDGET_BYTES = 48 * 1024
/** Any single answer longer than this is elided when serialised for the model. */
const val MAX_ANSWER_CHARS = 1200
const val FULL_VERBATIM_UNTIL_TURN = 8
val WINDOW_STEPS: List<Int> = listOf(6, 3, 0)

/** JS `String(text || '')`. */
private fun textOf(e: JsonElement?): String = if (truthy(e)) jsString(e) else ""

private fun clip(text: JsonElement?, max: Int = MAX_ANSWER_CHARS): String {
    val t = textOf(text)
    return if (t.length <= max) t else t.substring(0, max) + " […elided…]"
}

/** Provenance the model must be able to see, so it can discount its own words. */
private fun provenance(turn: JsonElement?): String =
    if (truthy(turn["answerSource"])) jsString(turn["answerSource"]) else "typed"

fun serializeTurn(turn: JsonElement?): String {
    val move = turn["move"]
    val q = "Q${jsString(turn["n"])}${if (truthy(move)) " [${jsString(move)}]" else ""}: ${jsString(turn["question"])}"
    if (truthy(turn["skipped"])) return "$q\nA${jsString(turn["n"])} [skipped]: (user skipped)"
    return "$q\nA${jsString(turn["n"])} [${provenance(turn)}]: ${clip(turn["answer"])}"
}

fun serializeFacts(facts: JsonElement?): String {
    val list = facts as? JsonArray ?: JsonArray(emptyList())
    if (list.isEmpty()) return "(nothing established yet)"
    return list.joinToString("\n") { f ->
        val d = f["dimension"]
        "- ${if (truthy(d)) "(${jsString(d)}) " else ""}${jsString(f["fact"])}"
    }
}

/** The transcript block for a turn prompt, and how it was compacted to fit. */
data class TranscriptBlock(val text: String, val windowSize: Int?, val truncated: Boolean)

/** Build the transcript block for a turn prompt, shrinking until it fits `budget` bytes. */
fun buildTranscriptBlock(session: JsonObject, budget: Int = BUDGET_BYTES): TranscriptBlock {
    val answered = (session["turns"] as? JsonArray ?: JsonArray(emptyList()))
        .filter { truthy(it["answer"]) || truthy(it["skipped"]) }

    fun render(windowSize: Int?): String {
        val parts = mutableListOf<String>()
        if (windowSize == null) {
            parts += answered.joinToString("\n\n") { serializeTurn(it) }
        } else {
            val recent = if (windowSize > 0) answered.takeLast(windowSize) else emptyList()
            val older = if (windowSize > 0) answered.dropLast(windowSize) else answered
            if (older.isNotEmpty()) {
                parts += "[${older.size} earlier exchange(s) compacted to facts — see FACTS ESTABLISHED]"
            }
            if (recent.isNotEmpty()) parts += recent.joinToString("\n\n") { serializeTurn(it) }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { "(no exchanges yet)" }
    }

    // Verbatim while the interview is short and it fits.
    if (answered.size <= FULL_VERBATIM_UNTIL_TURN) {
        val text = render(null)
        if (utf8Length(text) <= budget) return TranscriptBlock(text, null, false)
    }
    for (windowSize in WINDOW_STEPS) {
        val text = render(windowSize)
        if (utf8Length(text) <= budget) return TranscriptBlock(text, windowSize, windowSize == 0)
    }
    // Even facts-only overflows: hard-clip, in UTF-16 units as the JS slice does. Losing the
    // tail of the SEND is survivable; the export still holds everything verbatim.
    val text = render(0)
    return TranscriptBlock(if (text.length <= budget) text else text.substring(0, budget), 0, true)
}
