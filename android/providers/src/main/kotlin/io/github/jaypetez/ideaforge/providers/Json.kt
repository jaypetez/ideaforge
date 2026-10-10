package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.JS_S
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.serialization.json.Json
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
        val parsed = runCatching { Json.parseToJsonElement(candidate) }.getOrNull()
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
