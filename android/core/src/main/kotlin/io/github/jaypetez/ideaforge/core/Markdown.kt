package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_DOT_RUN
import io.github.jaypetez.ideaforge.core.js.JS_END
import io.github.jaypetez.ideaforge.core.js.JS_ML_END
import io.github.jaypetez.ideaforge.core.js.JS_ML_START
import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.bool
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsSlice
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.floor

// Port of src/core/markdown.js. The export is pure — no model involved — and works even when
// synthesis failed. Regexes are translated from JS semantics; see JsRegex.kt for the pieces.

private const val LOW_CONF_NOTE = "_(drafted by Claude and submitted unedited — treat as unconfirmed)_"

/** JS `new Date(ms || 0).toISOString().slice(0, 10)`: the UTC calendar day. */
private fun isoDay(ms: JsonElement?): String {
    val t = if (truthy(ms)) toNumber(ms).toLong() else 0L
    return DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC))
}

private fun dimLabel(id: JsonElement?): String =
    jsString(getDimension(stringOf(id) ?: jsString(id))["label"])

fun coveragePercent(session: JsonElement?): Int {
    val probing = DIMENSION_IDS.filter { stringOf(session["coverage"][it]["status"]) == "probing" }
    if (probing.isEmpty()) return 100
    val got = probing.sumOf { LEVEL_RANK[stringOf(session["coverage"][it]["level"])] ?: 0 }
    // JS Math.round is floor(x + 0.5).
    return floor(got.toDouble() / (probing.size * 2) * 100 + 0.5).toInt()
}

private val NON_SLUG = Regex("[^a-z0-9]+")
private val EDGE_DASHES = Regex("^-+|-+$JS_END")

fun slug(text: JsonElement?, fallback: String = "idea"): String {
    val raw = if (truthy(text)) jsString(text) else ""
    val s = jsSlice(raw.lowercase().replace(NON_SLUG, "-").replace(EDGE_DASHES, ""), 48)
    return s.ifEmpty { fallback }
}

fun exportFilename(session: JsonElement?): String =
    "ideaforge-${slug(str(sessionDisplayTitle(session, "idea")))}-${isoDay(session["updatedAt"])}.md"

private fun turnsOf(session: JsonElement?): List<JsonElement> = (session["turns"] as? JsonArray) ?: emptyList()

private fun answeredOrSkipped(t: JsonElement?): Boolean = truthy(t["answer"]) || truthy(t["skipped"])

private fun transcriptSection(session: JsonElement?): String {
    val answered = turnsOf(session).filter(::answeredOrSkipped)
    if (answered.isEmpty()) return "_No exchanges recorded._"
    return answered.joinToString("\n\n") { t ->
        val dim = if (truthy(t["dimension"])) dimLabel(t["dimension"]) else "Opening"
        val move = if (truthy(t["move"])) "  `${jsString(t["move"])}`" else ""
        val head = "### ${jsString(t["n"])} · $dim$move"
        if (truthy(t["skipped"])) {
            "$head\n\n**Q:** ${jsString(t["question"])}\n\n**A:** _(skipped)_"
        } else {
            val note = if (isLowConfidence(t)) "\n\n$LOW_CONF_NOTE" else ""
            val voice = if (stringOf(t["answerSource"]) == "voice") " _(dictated)_" else ""
            "$head\n\n**Q:** ${jsString(t["question"])}\n\n**A:**$voice ${jsString(t["answer"])}$note"
        }
    }
}

private fun openQuestionsSection(session: JsonElement?): String {
    val items = mutableListOf<String>()
    val open = session["openQuestions"]
    for (q in (if (truthy(open)) (open as? JsonArray) else null) ?: emptyList()) {
        val id = q["dimension"]
        val dim = if (truthy(id) && stringOf(id) in DIMENSION_IDS) dimLabel(id) else null
        val text = if (truthy(q["question"])) q["question"] else q["text"]
        val why = if (truthy(q["why_it_matters"])) "\n  _Why it matters: ${jsString(q["why_it_matters"])}_" else ""
        items.add("- ${if (dim != null) "**$dim** — " else ""}${jsString(text)}$why")
    }
    // Deliberate omissions are not failures — say so.
    for (id in DIMENSION_IDS) {
        val c = session["coverage"][id]
        val label = jsString(getDimension(id)["label"])
        when (stringOf(c["status"])) {
            "waived" -> {
                val reason = if (truthy(c["waivedReason"])) " (“${jsString(c["waivedReason"])}”)" else ""
                items.add("- **$label** — deliberately left out$reason.")
            }
            "deferred" -> items.add("- **$label** — a decision still to make.")
        }
    }
    return if (items.isNotEmpty()) items.joinToString("\n") else "_Nothing outstanding._"
}

private val LEVEL_MARK = mapOf("thin" to "○ thin", "partial" to "◐ partial", "covered" to "● covered")

private fun coverageTable(session: JsonElement?): String {
    val rows = DIMENSION_IDS.map { id ->
        val c = session["coverage"][id]
        val mark = if (stringOf(c["status"]) != "probing") jsString(c["status"])
        else LEVEL_MARK[stringOf(c["level"])] ?: "undefined"
        val gap = if (truthy(c["gap"])) jsString(c["gap"]) else "—"
        "| ${jsString(getDimension(id)["label"])} | $mark | $gap |"
    }
    return (listOf("| Dimension | Coverage | Gap |", "|---|---|---|") + rows).joinToString("\n")
}

/** `note` is the JS option after its truthiness test: null when the JS would skip it. */
fun buildExport(session: JsonElement?, mode: String = "claude", note: String? = null): String {
    val title = sessionDisplayTitle(session)
    val turns = turnsOf(session)
    val answered = turns.count(::answeredOrSkipped)
    val lowConf = turns.count { isLowConfidence(it) }

    val meta = listOf(
        isoDay(session["updatedAt"]),
        "$answered question${if (answered == 1) "" else "s"}",
        "coverage ${coveragePercent(session)}%",
        if (mode == "checklist") "assembled locally" else "synthesised by Claude",
    ).joinToString(" · ")

    val parts = mutableListOf("# $title", "_${meta}_")
    if (!note.isNullOrEmpty()) parts.add("> $note")
    if (lowConf > 0 && answered > 0 && lowConf.toDouble() / answered > 0.5) {
        parts.add("> **A lot of this was drafted for you rather than written by you.** " +
            "Worth a read before you rely on it.")
    }
    val synthesis = session["synthesis"]
    if (truthy(synthesis["stale"])) {
        parts.add("> The refined prompt below predates the most recent answers — regenerate it.")
    }
    // A bank-answered turn is generic rather than grounded; this document outlives the
    // session, so it has to say so (see the JS for the full reasoning).
    val banked = turns.count { stringOf(it["questionSource"]) == "bank" }
    if (banked > 0) {
        parts.add("> **$banked of these questions came from the built-in checklist, not " +
            "from the model** — the model could not be reached for those turns, so they " +
            "are generic rather than grounded in your answers.")
    }

    parts.add("## Refined prompt")
    parts.add(if (truthy(synthesis["text"])) jsString(synthesis["text"])
    else "_Not generated. The transcript and open questions below are still complete._")

    val assumptions = synthesis["assumptions"] as? JsonArray
    if (assumptions != null && assumptions.isNotEmpty()) {
        parts.add("### Assumptions made during synthesis")
        parts.add(assumptions.joinToString("\n") { "- ${jsString(it)}" })
    }

    parts.add("## Open questions")
    parts.add(openQuestionsSection(session))
    parts.add("## Coverage")
    parts.add(coverageTable(session))
    parts.add("## Transcript")
    if (truthy(session["opening"])) parts.add("**The idea, as first stated:** ${jsString(session["opening"])}")
    parts.add(transcriptSection(session))
    parts.add("---")
    parts.add("_Forged with IdeaForge._")
    return parts.joinToString("\n\n") + "\n"
}

// ---- renderBlocks: the export as data a view can draw. Not a general markdown parser; it
// covers what buildExport emits plus enough inline handling for synthesis.text. Anything
// unrecognised falls through as a paragraph rather than being dropped.

private const val S = "[$JS_S]"
private const val NS = "[^$JS_S]"
private val CRLF = Regex("\r\n?")
private val HR = Regex("^$S*(?:---|\\*\\*\\*|___)$S*$JS_END")
private val HEAD = Regex("^(#{1,6})$S+($JS_DOT_RUN*)$JS_END")
private val FENCE = Regex("^$S*```")
private val QUOTE = Regex("^$S*>")
private val QUOTE_MARK = Regex("^$S*>$S?")
private val ROW = Regex("^$S*\\|$JS_DOT_RUN*\\|$S*$JS_END")
private val EDGE_PIPES = Regex("^\\||\\|$JS_END")
private val SEPARATOR = Regex("^[$JS_S|:-]+$JS_END")
private val BREAK_START = Regex("^(#{1,6}$S|$S*>|$S*```)")
private val BULLET = Regex("^$S*([-*+]|\\d+[.)])$S+")
private val ITEM = Regex("^$S*(?:[-*+]|\\d+[.)])$S+($JS_DOT_RUN*)$JS_END")
private val DIGIT = Regex("\\d")
private val CONTINUATION = Regex("^$S+$NS")
private val META = Regex("^_[^_]+_$JS_END")
private val INLINE = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|_([^_]+)_|\\*([^*]+)\\*")

fun renderBlocks(markdown: JsonElement?): List<JsonObject> {
    val src = if (truthy(markdown)) jsString(markdown) else ""
    val lines = src.replace(CRLF, "\n").split("\n")
    val blocks = mutableListOf<JsonObject>()
    var i = 0

    fun isRow(line: String) = ROW.containsMatchIn(line)
    fun cells(line: String) = jsTrim(line).replace(EDGE_PIPES, "").split("|").map { jsTrim(it) }
    fun breaks(line: String) = BREAK_START.containsMatchIn(line) || HR.containsMatchIn(line) ||
        BULLET.containsMatchIn(line)
    fun spans(list: List<String>) = arr(list.map { inline(it) })

    while (i < lines.size) {
        val line = lines[i]
        if (jsTrim(line).isEmpty()) { i++; continue }

        if (HR.containsMatchIn(line)) { blocks.add(obj("type" to str("hr"))); i++; continue }

        val head = HEAD.find(line)
        if (head != null) {
            val level = minOf(head.groupValues[1].length, 3)
            blocks.add(obj("type" to str("h$level"), "spans" to inline(head.groupValues[2])))
            i++
            continue
        }

        // A fence, which a model will sometimes wrap a prompt in.
        if (FENCE.containsMatchIn(line)) {
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !FENCE.containsMatchIn(lines[i])) body.add(lines[i++])
            i++
            blocks.add(obj("type" to str("pre"), "text" to str(body.joinToString("\n"))))
            continue
        }

        if (QUOTE.containsMatchIn(line)) {
            val body = mutableListOf<String>()
            while (i < lines.size && QUOTE.containsMatchIn(lines[i])) body.add(lines[i++].replaceFirst(QUOTE_MARK, ""))
            blocks.add(obj("type" to str("quote"), "spans" to inline(body.joinToString(" "))))
            continue
        }

        // A table needs its separator row to be one. A single pipe in prose does not.
        val next = lines.getOrNull(i + 1)
        if (isRow(line) && next != null && isRow(next) && SEPARATOR.containsMatchIn(next)) {
            val heads = spans(cells(line))
            i += 2
            val rows = mutableListOf<JsonElement>()
            while (i < lines.size && isRow(lines[i])) rows.add(spans(cells(lines[i++])))
            blocks.add(obj("type" to str("table"), "head" to heads, "rows" to arr(rows)))
            continue
        }

        val bullet = BULLET.find(line)
        if (bullet != null) {
            val ordered = DIGIT.containsMatchIn(bullet.groupValues[1])
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val m = ITEM.find(lines[i])
                if (m != null) { items.add(m.groupValues[1]); i++; continue }
                // An indented continuation belongs to the item above it. openQuestionsSection
                // writes its "Why it matters" line exactly that way.
                if (CONTINUATION.containsMatchIn(lines[i]) && items.isNotEmpty()) {
                    items[items.size - 1] = items.last() + " " + jsTrim(lines[i])
                    i++
                    continue
                }
                break
            }
            blocks.add(obj("type" to str("list"), "ordered" to bool(ordered), "items" to spans(items)))
            continue
        }

        val para = mutableListOf<String>()
        while (i < lines.size && jsTrim(lines[i]).isNotEmpty() && !breaks(lines[i])) para.add(jsTrim(lines[i++]))
        val text = para.joinToString(" ")
        // A paragraph that is one whole emphasis run is the meta line or a placeholder.
        val meta = META.containsMatchIn(text)
        blocks.add(obj(
            "type" to str("p"),
            "meta" to bool(meta),
            "spans" to inline(if (meta) text.substring(1, text.length - 1) else text),
        ))
    }
    return blocks
}

/** Inline emphasis, as spans. Code is matched first so a `**` inside a code span stays literal. */
private fun inline(text: String): JsonArray {
    val out = mutableListOf<JsonElement>()
    var at = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > at) out.add(obj("text" to str(text.substring(at, m.range.first))))
        val g = m.groups
        out.add(when {
            g[1] != null -> obj("text" to str(g[1]!!.value), "code" to bool(true))
            g[2] != null -> obj("text" to str(g[2]!!.value), "bold" to bool(true))
            else -> obj("text" to str((g[3] ?: g[4])!!.value), "italic" to bool(true))
        })
        at = m.range.last + 1
    }
    if (at < text.length) out.add(obj("text" to str(text.substring(at))))
    return arr(out.ifEmpty { listOf(obj("text" to str(""))) })
}

// ---- forSpeech / speechChunks. Note the heading pattern's `\s+` can cross a newline, as in JS.

private const val ML = JS_ML_START
private const val MLE = JS_ML_END
private val SP_FENCE = Regex("```[\\s\\S]*?```")
private val SP_CODE = Regex("`([^`]*)`")
private val SP_IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
private val SP_LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
private val SP_HEADING = Regex("$ML$S{0,3}#{1,6}$S+($JS_DOT_RUN*)$MLE")
private val SP_QUOTE = Regex("$ML$S{0,3}>$S?")
private val SP_BULLET = Regex("$ML$S{0,3}[-*+]$S+")
private val SP_NUMBERED = Regex("$ML$S{0,3}\\d+[.)]$S+")
private val SP_RULE = Regex("$ML$S{0,3}([-*_])($S*\\1){2,}$S*$MLE")
private val SP_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
private val SP_EMPH = Regex("([*_])([^*_]+)\\1")
private val SP_BLANKS = Regex("[ \\t]+")
private val SP_ENDS = Regex("[.!?:]$JS_END")
private val SP_BEFORE_PUNCT = Regex("$S+([.,!?;:])")
private val SP_DOTS = Regex("\\.{2,}")
private val SENTENCE_GAP = Regex("(?<=[.!?])$S+")

/** The same text, said out loud: markers out, structure carried by sentence breaks. */
fun forSpeech(markdown: JsonElement?, maxChars: Int = 3000): String {
    var t = if (truthy(markdown)) jsString(markdown) else ""
    t = t.replace(SP_FENCE, " ")
    t = t.replace(SP_CODE, "$1")
    t = t.replace(SP_IMAGE, " ")
    t = t.replace(SP_LINK, "$1")
    t = t.replace(SP_HEADING, "$1.")
    t = t.replace(SP_QUOTE, "")
    t = t.replace(SP_BULLET, "")
    t = t.replace(SP_NUMBERED, "")
    t = t.replace(SP_RULE, " ")
    t = t.replace(SP_BOLD, "$1")
    t = t.replace(SP_EMPH, "$2")
    t = t.replace(SP_BLANKS, " ")
    t = t.split("\n").map { jsTrim(it) }.filter { it.isNotEmpty() }
        .joinToString(" ") { if (SP_ENDS.containsMatchIn(it)) it else "$it." }
    t = jsTrim(t.replace(SP_BEFORE_PUNCT, "$1").replace(SP_DOTS, "."))

    if (t.length <= maxChars) return t
    // Cut on a sentence boundary: trailing off mid-clause sounds like a crash.
    val cut = jsSlice(t, maxChars)
    val stop = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
    return jsTrim(if (stop > maxChars * 0.5) cut.substring(0, stop + 1) else cut)
}

/** Split spoken text into pieces a synthesiser will actually finish. */
fun speechChunks(text: JsonElement?, maxChars: Int = 350): List<String> {
    val out = mutableListOf<String>()
    var current = ""
    val src = if (truthy(text)) jsString(text) else ""
    for (piece in src.split(SENTENCE_GAP)) {
        if (piece.isEmpty()) continue
        if (current.isNotEmpty() && current.length + piece.length + 1 > maxChars) {
            out.add(current)
            current = piece
        } else {
            current = if (current.isNotEmpty()) "$current $piece" else piece
        }
    }
    if (current.isNotEmpty()) out.add(current)
    return out
}
