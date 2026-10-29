package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.js.jsNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

// The Kotlin twin of src/providers/json.js: getting from a string to *some* object, so that
// parseTurnResult's hardening against a wrong shape gets a chance to run. Models fence their
// output, prepend "Here is the JSON:", emit a trailing comma, or think out loud first.

private val FENCE = Regex("```(?:json|JSON)?[$JS_S]*([\\s\\S]*?)```")
private val REASONING_BLOCK = Regex("<(think|thinking|reasoning)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
private val REASONING_CLOSE = Regex("</(?:think|thinking|reasoning)>", RegexOption.IGNORE_CASE)
private val TRAILING_COMMA = Regex(",([$JS_S]*[}\\]])")

/**
 * @return the parsed object
 * @throws ProviderException code `bad_response` when nothing object-shaped can be recovered
 */
fun extractJson(text: String?): JsonObject {
    val raw = jsTrim(text.orEmpty())
    if (raw.isEmpty()) throw ProviderException(ProviderErrorCode.BAD_RESPONSE, "model returned an empty response")
    for (candidate in candidates(raw)) {
        val parsed = runCatching { parseStrictJson(candidate) }.getOrNull()
        if (parsed is JsonObject) return parsed
    }
    throw ProviderException(
        ProviderErrorCode.BAD_RESPONSE,
        "model did not return a JSON object (got ${raw.length} chars starting \"${raw.take(60)}\")",
    )
}

private fun candidates(raw: String): Sequence<String> = sequence {
    yieldAll(shapes(raw))
    // Only worth a second pass when there was reasoning to remove.
    val plain = withoutReasoning(raw)
    if (plain.isNotEmpty() && plain != raw) yieldAll(shapes(plain))
}

private fun shapes(raw: String): Sequence<String> = sequence {
    yield(raw)
    FENCE.find(raw)?.let { yield(jsTrim(it.groupValues[1])) }
    // Outermost braces: survives a preamble, a sign-off, or both.
    val first = raw.indexOf('{')
    val last = raw.lastIndexOf('}')
    if (first != -1 && last > first) {
        val slice = raw.substring(first, last + 1)
        yield(slice)
        yield(slice.replace(TRAILING_COMMA, "$1"))
    }
}

/**
 * Drop a reasoning model's narration (qwen3, deepseek-r1: `<think>` blocks routinely contain
 * braces, which defeats the outermost-brace rule). A reply whose opening tag was swallowed
 * still has its closing tag; anything before the last one is narration either way.
 */
private fun withoutReasoning(raw: String): String {
    var out = jsTrim(raw.replace(REASONING_BLOCK, ""))
    val last = REASONING_CLOSE.findAll(out).lastOrNull()
    if (last != null) out = jsTrim(out.substring(last.range.last + 1))
    return out
}

/**
 * JSON.parse, exactly as strict. kotlinx's parseToJsonElement is a lenient reader here: it
 * takes `{"a": partial}`, `True`, `NaN` and raw newlines inside strings, so a reply the JS
 * rejects (and answers from the question bank) would be accepted as a turn. This reader
 * refuses everything ECMA-404 refuses, keeps the last of duplicate keys in the first key's
 * position as a JS object does, and gives numbers their JS values (`1e5` is 100000, `-0` is 0).
 */
internal fun parseStrictJson(text: String): JsonElement = StrictJson(text).parse()

private class StrictJson(private val s: String) {
    private var i = 0

    fun parse(): JsonElement {
        val v = value()
        space()
        if (i != s.length) bad()
        return v
    }

    private fun bad(): Nothing = throw IllegalArgumentException("not JSON at $i")

    // JSON whitespace is these four and nothing else.
    private fun space() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

    private fun value(): JsonElement {
        space()
        if (i >= s.length) bad()
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonPrimitive(string())
            't' -> word("true", JsonPrimitive(true))
            'f' -> word("false", JsonPrimitive(false))
            'n' -> word("null", JsonNull)
            else -> number()
        }
    }

    private fun word(w: String, v: JsonElement): JsonElement {
        if (!s.startsWith(w, i)) bad()
        i += w.length
        return v
    }

    private fun obj(): JsonObject {
        i++
        val out = LinkedHashMap<String, JsonElement>()
        space()
        if (i < s.length && s[i] == '}') { i++; return JsonObject(out) }
        while (true) {
            space()
            if (i >= s.length || s[i] != '"') bad()
            val key = string()
            space()
            if (i >= s.length || s[i] != ':') bad()
            i++
            out[key] = value()
            space()
            if (i >= s.length) bad()
            when (s[i++]) {
                ',' -> continue
                '}' -> return JsonObject(out)
                else -> bad()
            }
        }
    }

    private fun arr(): JsonArray {
        i++
        val out = ArrayList<JsonElement>()
        space()
        if (i < s.length && s[i] == ']') { i++; return JsonArray(out) }
        while (true) {
            out += value()
            space()
            if (i >= s.length) bad()
            when (s[i++]) {
                ',' -> continue
                ']' -> return JsonArray(out)
                else -> bad()
            }
        }
    }

    private fun string(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) bad()
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c < ' ' -> bad()   // a raw control character, newline and tab included
                c == '\\' -> {
                    if (i >= s.length) bad()
                    when (s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) bad()
                            val hex = s.substring(i, i + 4)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) bad()
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> bad()
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun number(): JsonElement {
        val m = NUMBER.matchAt(s, i) ?: bad()
        i += m.value.length
        return jsNumber(m.value.toDouble())
    }

    private companion object {
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
