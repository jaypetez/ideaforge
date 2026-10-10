package io.github.jaypetez.ideaforge.core.js

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// JavaScript semantics, reproduced where Kotlin's differ.
//
// The core is a port of JS that reads untrusted, loosely-typed JSON (sessions written by an
// older app, model output, backups). Where JS coerces, tests truthiness or trims a wider set
// of whitespace than Kotlin does, the port must do exactly the same, or a session that the
// web app accepts is mangled here. Each helper names the JS behaviour it reproduces; the
// golden vectors are what prove it.
//
// Representation: a JS value is a JsonElement, JS `null` is JsonNull, and JS `undefined` is
// a Kotlin `null` (a missing key). Objects keep insertion order, as JS objects do.

/** The characters JS `String.prototype.trim` and the regex class `\s` treat as whitespace. */
fun isJsSpace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ',
    ' ', '　', '﻿' -> true
    in ' '..' ' -> true
    else -> false
}

/** JS `str.trim()`. Kotlin's `trim()` uses a different whitespace set. */
fun jsTrim(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isJsSpace(s[start])) start++
    while (end > start && isJsSpace(s[end - 1])) end--
    return s.substring(start, end)
}

/** JS `str.replace(/\s+/g, ' ')`. */
fun collapseJsSpace(s: String): String {
    val out = StringBuilder(s.length)
    var inRun = false
    for (c in s) {
        if (isJsSpace(c)) {
            if (!inRun) out.append(' ')
            inRun = true
        } else {
            out.append(c)
            inRun = false
        }
    }
    return out.toString()
}

/** JS `str.slice(0, n)`, in UTF-16 code units, which is what Kotlin strings index by too. */
fun jsSlice(s: String, end: Int): String = if (s.length <= end) s else s.substring(0, end)

/** JS `str.toLowerCase()`: locale-independent, which is Locale.ROOT here. */
fun jsLower(s: String): String = s.lowercase()

/** JS truthiness of a JSON value; a missing value (undefined) is falsy. */
fun truthy(e: JsonElement?): Boolean = when (e) {
    null, JsonNull -> false
    is JsonObject, is JsonArray -> true
    is JsonPrimitive -> when {
        e.isString -> e.content.isNotEmpty()
        e.booleanOrNull != null -> e.booleanOrNull!!
        else -> e.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
    }
}

/** JS `typeof v === 'string'`. */
fun isString(e: JsonElement?): Boolean = e is JsonPrimitive && e !is JsonNull && e.isString

/** The string, if `e` is one. */
fun stringOf(e: JsonElement?): String? = if (isString(e)) (e as JsonPrimitive).content else null

/** JS `Number.isFinite(v)`: true only for a finite number, never a numeric string. */
fun isFiniteNumber(e: JsonElement?): Boolean =
    e is JsonPrimitive && e !is JsonNull && !e.isString && e.booleanOrNull == null &&
        e.doubleOrNull?.isFinite() == true

/** The isRecord test used across the core: a non-null, non-array object. */
fun isRecord(e: JsonElement?): Boolean = e is JsonObject

/** JS ToNumber, as used by `<`, `>` and `+` on a number. NaN when it is not a number. */
fun toNumber(e: JsonElement?): Double = when (e) {
    null -> Double.NaN
    JsonNull -> 0.0
    is JsonObject -> Double.NaN
    is JsonArray -> when (e.size) {
        0 -> 0.0
        1 -> toNumber(e[0])
        else -> Double.NaN
    }
    is JsonPrimitive -> when {
        e.isString -> stringToNumber(e.content)
        e.booleanOrNull != null -> if (e.booleanOrNull!!) 1.0 else 0.0
        else -> e.doubleOrNull ?: Double.NaN
    }
}

private fun stringToNumber(s: String): Double {
    val t = jsTrim(s)
    if (t.isEmpty()) return 0.0
    return when {
        t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
        t == "Infinity" || t == "+Infinity" -> Double.POSITIVE_INFINITY
        t == "-Infinity" -> Double.NEGATIVE_INFINITY
        // Kotlin accepts forms JS does not (a trailing 'd' or 'f'), so filter first.
        Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""").matches(t) -> t.toDouble()
        else -> Double.NaN
    }
}

/** JS `a > b` where b is a number. Any comparison with NaN is false. */
fun jsGreater(a: JsonElement?, b: Double): Boolean = toNumber(a) > b

/**
 * A JS number as JSON. JSON.stringify writes integral values without a fraction and turns
 * NaN and the infinities into null, so the port must too.
 */
fun jsNumber(d: Double): JsonElement = when {
    d.isNaN() || d.isInfinite() -> JsonNull
    d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15 -> JsonPrimitive(d.toLong())
    else -> JsonPrimitive(d)
}

/** JS `a + 1` for the counters the core keeps (turnsSpent, rev): a string concatenates. */
fun jsPlus(a: JsonElement?, n: Int): JsonElement =
    if (isString(a)) JsonPrimitive((a as JsonPrimitive).content + n) else jsNumber(toNumber(a) + n)

/** JS `String(v)`. */
fun jsString(e: JsonElement?): String = when (e) {
    null -> "undefined"
    JsonNull -> "null"
    is JsonObject -> "[object Object]"
    is JsonArray -> e.joinToString(",") { if (it is JsonNull) "" else jsString(it) }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.content
        else -> numberToString(e.doubleOrNull ?: Double.NaN, e.content)
    }
}

private fun numberToString(d: Double, raw: String): String = when {
    d.isNaN() -> "NaN"
    d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
    d == Math.rint(d) && Math.abs(d) < 1e21 -> d.toLong().toString()
    else -> raw
}

/** Strict equality against a string, as JS `v === 'x'`. */
fun jsEq(e: JsonElement?, s: String): Boolean = stringOf(e) == s

/** Strict equality against a number, as JS `v === 1`. */
fun jsEq(e: JsonElement?, n: Int): Boolean =
    e is JsonPrimitive && e !is JsonNull && !e.isString && e.booleanOrNull == null && e.doubleOrNull == n.toDouble()

// ── Objects ────────────────────────────────────────────────────────────────

/** `obj[key]`: undefined (null) for a missing key or a non-object. */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * JS `{ ...this, ...patch }`: existing keys keep their position, new keys are appended, and
 * an undefined (null) value is dropped from the JSON while still claiming its position.
 */
fun JsonObject.spread(vararg patch: Pair<String, JsonElement?>): JsonObject = spread(patch.toList())

fun JsonObject.spread(patch: Iterable<Pair<String, JsonElement?>>): JsonObject {
    val out = LinkedHashMap<String, JsonElement?>(this)
    for ((k, v) in patch) out[k] = v
    return obj(out)
}

/** JS `{ ...a, ...b }` for two objects. */
fun JsonObject.spread(other: JsonObject): JsonObject = spread(other.entries.map { it.key to it.value })

/** An object literal; undefined (null) values are left out, as JSON.stringify leaves them. */
fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject = obj(linkedMapOf(*pairs))

fun obj(map: Map<String, JsonElement?>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for ((k, v) in map) if (v != null) out[k] = v
    return JsonObject(out)
}

fun arr(items: List<JsonElement>): JsonArray = JsonArray(items)

fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

fun num(n: Number): JsonElement = jsNumber(n.toDouble())

fun bool(b: Boolean): JsonElement = JsonPrimitive(b)

/** A destructured option with a JS default: the default applies only when the key is missing. */
fun JsonElement?.opt(key: String, default: JsonElement?): JsonElement? {
    val o = this as? JsonObject ?: return default
    return if (key in o) o[key] else default
}
