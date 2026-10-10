package io.github.jaypetez.ideaforge.core.js

// JS regular-expression semantics on the JVM.
//
// The engine's patterns are written for JS, and three of their building blocks mean
// something different in Java: `\s` (JS includes U+00A0, U+FEFF, U+2028 and the U+2000
// spaces; Java's default does not), `\b` (JS without the u flag uses ASCII word characters;
// Java's boundary is Unicode-aware) and `/i` (JS folds per character; Java folds ASCII only
// unless asked). Patterns here are spelled with these constants so they match exactly what
// the JS matches.

/** The JS `\s` class, as a Java character class body. */
const val JS_S: String = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** JS `.` without the s flag: one UTF-16 unit, anything but \n \r U+2028 U+2029. Java's `.` takes a
 *  whole code point and also refuses U+0085. Astral code points are left out because a JS `.`
 *  can never match a whole surrogate pair in one step. */
const val JS_DOT: String = "[^\\n\\r\\u2028\\u2029\\x{10000}-\\x{10FFFF}]"

/** JS `.` for use inside a run such as `.*`: anything but \n \r U+2028 U+2029. A Java class
 *  consumes a whole code point, which is right for a run (JS takes both surrogate halves one
 *  at a time). Java's own `.` also refuses U+0085, which JS accepts. */
const val JS_DOT_RUN: String = "[^\\n\\r\\u2028\\u2029]"

/** JS `^` under the m flag: start of input or just after \n \r U+2028 U+2029. Java's MULTILINE
 *  `^` also breaks after U+0085 and treats \r\n as one terminator. */
const val JS_ML_START: String = "(?<![^\\n\\r\\u2028\\u2029])"

/** JS `$` under the m flag: end of input or just before \n \r U+2028 U+2029. */
const val JS_ML_END: String = "(?![^\\n\\r\\u2028\\u2029])"

/** JS `$` without the m flag: the very end. Java's `$` also matches before a final terminator. */
const val JS_END: String = "\\z"

/** JS `\s+`. */
val JS_SPACES: Regex = Regex("[$JS_S]+")

/** JS `\b` without the u flag: a boundary between [A-Za-z0-9_] and anything else. */
const val JS_B: String = "(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))"

/**
 * A case-insensitive JS-style pattern, folding ASCII only. Kotlin's RegexOption.IGNORE_CASE
 * also sets UNICODE_CASE, which lets the Kelvin sign (U+212A) match `k` and the long s
 * (U+017F) match `s`; JS `/i` without the u flag refuses both. The engine's patterns are
 * ASCII, so ASCII folding is exactly the JS non-unicode behaviour for them.
 */
fun jsRegexI(pattern: String): Regex =
    java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).toRegex()

/** JS `str.split(/\s+/)`, keeping leading and trailing empty strings as JS does. */
fun jsSplitSpaces(s: String): List<String> = s.split(JS_SPACES)

/** JS `str.padEnd(n)`, in UTF-16 code units. */
fun jsPadEnd(s: String, n: Int): String = if (s.length >= n) s else s + " ".repeat(n - s.length)

/**
 * JSON.stringify of a string: quotes, the short escapes, other control characters as
 * lowercase \u00XX, and lone surrogates escaped (well-formed JSON.stringify, ES2019).
 */
fun jsonQuote(s: String): String {
    val out = StringBuilder(s.length + 2).append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                out.append(c).append(s[i + 1]); i++
            }
            c.isSurrogate() -> out.append("\\u").append(String.format("%04x", c.code))
            else -> out.append(c)
        }
        i++
    }
    return out.append('"').toString()
}

/** JSON.stringify of an array of strings, compact, as the turn prompt prints the move list. */
fun jsonStringArray(items: List<String>): String = items.joinToString(",", "[", "]") { jsonQuote(it) }
