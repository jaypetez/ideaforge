package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsEq
import io.github.jaypetez.ideaforge.core.js.jsNumber
import io.github.jaypetez.ideaforge.core.js.jsNumberString
import io.github.jaypetez.ideaforge.core.js.jsSlice
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.jsonQuote
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull

// Portable, key-free backups for the local ideas library: a port of src/core/backup.js.
//
// A backup contains sessions and nothing else: no credential, no device preference. The
// file a phone writes must be byte-identical to the one the web app writes, so the JSON
// here is printed and parsed by hand with JavaScript's rules (JSON.stringify(x, null, 2),
// JSON.parse) rather than by kotlinx, whose number formatting and parsing differ.

const val BACKUP_FORMAT = "ideaforge-backup"
const val BACKUP_VERSION = 1
const val MAX_BACKUP_BYTES = 10 * 1024 * 1024
const val MAX_BACKUP_SESSIONS = 500

private const val IMPORTED_SUFFIX = " (imported)"

/**
 * How deeply a backup may nest. The reader, the printer and the canonical comparison all
 * recurse, so without a bound a hostile file would end in StackOverflowError rather than an
 * error the user can read. A session nests about six deep; V8's JSON.parse has no such limit,
 * so a file deeper than this is one the web app would read and this port refuses. The bound
 * is small because Android worker threads have far less stack than a desktop JVM's main one.
 */
const val MAX_JSON_DEPTH = 256

/** Thrown by the printer and the canonical comparison for input deeper than [MAX_JSON_DEPTH]. */
class JsonTooDeepException : IllegalArgumentException("The backup is nested too deeply.")

/** A canonical array index ("0" to "4294967294"): JS objects list these keys first, ascending. */
private fun isArrayIndex(k: String): Boolean =
    k.isNotEmpty() && k.length <= 10 && k.all { it in '0'..'9' } && (k == "0" || k[0] != '0') &&
        k.toLong() < 4294967295L

/** The order a JS object enumerates these keys in: array indices ascending, then insertion order. */
private fun jsKeyOrder(keys: Collection<String>): List<String> {
    val (indices, names) = keys.partition(::isArrayIndex)
    return if (indices.isEmpty()) names else indices.sortedBy { it.toLong() } + names
}

/** UTF-8 length, counting a lone surrogate as the three bytes the JS counts it as. */
private fun utf8Bytes(value: String): Long {
    var bytes = 0L
    var i = 0
    while (i < value.length) {
        val code = value.codePointAt(i)
        bytes += when {
            code <= 0x7f -> 1
            code <= 0x7ff -> 2
            code <= 0xffff -> 3
            else -> 4
        }
        i += Character.charCount(code)
    }
    return bytes
}

/** A JSON number literal as JSON.stringify writes it; NaN and the infinities become null. */
private fun numberText(p: JsonPrimitive): String {
    val d = p.content.toDoubleOrNull() ?: Double.NaN
    return if (d.isNaN() || d.isInfinite()) "null" else jsNumberString(d)
}

private fun primitiveText(e: JsonElement): String = when {
    e is JsonNull -> "null"
    e is JsonPrimitive && e.isString -> jsonQuote(e.content)
    e is JsonPrimitive && e.booleanOrNull != null -> e.content
    e is JsonPrimitive -> numberText(e)
    else -> error("not a primitive")
}

/** Sorted-key compact JSON: the identity two sessions share when only key order differs. */
private fun canonical(value: JsonElement, out: StringBuilder, depth: Int = 0) {
    when (value) {
        is JsonArray -> {
            if (depth >= MAX_JSON_DEPTH) throw JsonTooDeepException()
            out.append('[')
            value.forEachIndexed { i, item ->
                if (i > 0) out.append(',')
                canonical(item, out, depth + 1)
            }
            out.append(']')
        }
        // JS's default sort compares UTF-16 code units, which is what String.compareTo does.
        is JsonObject -> {
            if (depth >= MAX_JSON_DEPTH) throw JsonTooDeepException()
            out.append('{')
            value.keys.sorted().forEachIndexed { i, key ->
                if (i > 0) out.append(',')
                out.append(jsonQuote(key)).append(':')
                canonical(value.getValue(key), out, depth + 1)
            }
            out.append('}')
        }
        else -> out.append(primitiveText(value))
    }
}

private fun canonical(value: JsonElement?): String = StringBuilder().also { canonical(value ?: JsonNull, it) }.toString()

fun sessionsEqual(a: JsonElement?, b: JsonElement?): Boolean = canonical(a) == canonical(b)

/** JSON.stringify(value, null, 2). */
private fun pretty(value: JsonElement, indent: String, out: StringBuilder, depth: Int = 0) {
    val inner = "$indent  "
    if ((value is JsonArray || value is JsonObject) && depth >= MAX_JSON_DEPTH) throw JsonTooDeepException()
    when (value) {
        is JsonArray -> if (value.isEmpty()) out.append("[]") else {
            out.append("[\n")
            value.forEachIndexed { i, item ->
                if (i > 0) out.append(",\n")
                out.append(inner)
                pretty(item, inner, out, depth + 1)
            }
            out.append('\n').append(indent).append(']')
        }
        is JsonObject -> if (value.isEmpty()) out.append("{}") else {
            out.append("{\n")
            var first = true
            for (key in jsKeyOrder(value.keys)) {
                if (!first) out.append(",\n")
                first = false
                out.append(inner).append(jsonQuote(key)).append(": ")
                pretty(value.getValue(key), inner, out, depth + 1)
            }
            out.append('\n').append(indent).append('}')
        }
        else -> out.append(primitiveText(value))
    }
}

/**
 * `new Date(now || 0).toISOString().slice(0, 10)`, for a numeric clock. An out-of-range time
 * is the RangeError the JS throws.
 */
fun backupFilename(now: JsonElement? = null): String {
    val t = if (truthy(now)) toNumber(now) else 0.0
    if (t.isNaN() || t.isInfinite() || Math.abs(t) > 8.64e15) throw IllegalArgumentException("Invalid time value")
    val ms = if (t < 0) Math.ceil(t).toLong() else Math.floor(t).toLong()
    val utc = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC)
    val year = utc.year
    val y = if (year in 0..9999) String.format(java.util.Locale.ROOT, "%04d", year)
    else (if (year < 0) "-" else "+") + String.format(java.util.Locale.ROOT, "%06d", Math.abs(year))
    val iso = y + String.format(java.util.Locale.ROOT, "-%02d-%02dT", utc.monthValue, utc.dayOfMonth)
    return "ideaforge-backup-${iso.substring(0, 10)}.json"
}

fun backupFilename(now: Long): String = backupFilename(num(now))

/** The backup file's exact text: two-space JSON and a trailing newline, as the web writes it. */
fun buildBackup(sessions: JsonElement?, now: JsonElement = num(0)): String {
    val doc = obj(
        "format" to str(BACKUP_FORMAT),
        "version" to num(BACKUP_VERSION),
        "exportedAt" to now,
        "sessions" to (sessions as? JsonArray ?: arr(emptyList())),
    )
    val out = StringBuilder()
    pretty(doc, "", out)
    return out.append('\n').toString()
}

/** Thrown with the message the web app shows for the same file. */
class BackupException(message: String) : IllegalArgumentException(message)

/**
 * Validate and migrate a backup file. Returns `{sessions, skipped}`; rows without a usable id,
 * or written by a newer schema, are skipped and counted rather than given an invented identity.
 */
fun parseBackup(
    source: JsonElement?,
    maxBytes: Double = MAX_BACKUP_BYTES.toDouble(),
    maxSessions: Double = MAX_BACKUP_SESSIONS.toDouble(),
): JsonObject {
    val raw = stringOf(source) ?: ""
    if (jsTrim(raw).isEmpty()) throw BackupException("The backup file is empty.")
    if (utf8Bytes(raw) > maxBytes) {
        val mb = Math.floor(maxBytes / 1024 / 1024 + 0.5)
        throw BackupException("The backup is larger than ${jsNumberString(mb)} MB.")
    }

    val parsed = try {
        JsJson(raw).parseDocument()
    } catch (_: JsJson.SyntaxError) {
        throw BackupException("The selected file is not valid JSON.")
    }
    if (parsed !is JsonObject) throw BackupException("The selected file is not an IdeaForge backup.")
    if (!jsEq(parsed["format"], BACKUP_FORMAT)) throw BackupException("The selected file is not an IdeaForge backup.")
    if (!jsEq(parsed["version"], BACKUP_VERSION)) {
        throw BackupException("This backup was written by a newer or unsupported IdeaForge version.")
    }
    val list = parsed["sessions"] as? JsonArray ?: throw BackupException("The backup has no session list.")
    if (list.size > maxSessions) {
        throw BackupException("The backup contains more than ${jsNumberString(maxSessions)} sessions.")
    }

    val sessions = mutableListOf<JsonElement>()
    var skipped = 0
    for (stored in list) {
        val id = stringOf(stored["id"])
        if (!truthy(stored) || id == null || jsTrim(id).isEmpty()) {
            skipped++
            continue
        }
        val session = migrate(stored)
        if (session == null) {
            skipped++
            continue
        }
        sessions += session
    }
    return obj("sessions" to arr(sessions), "skipped" to num(skipped))
}

fun parseBackup(source: String): JsonObject = parseBackup(str(source))

/**
 * Decide how imported sessions join the local library, without touching storage: a port of
 * mergeImported. `existing` is the readable library; `reservedIds` is every stored id,
 * including rows a newer schema wrote that migrate rejects. An id in neither is imported as
 * is; an exact duplicate of a readable row (canonical comparison) is skipped; any other
 * collision is kept as a copy under a fresh id named "<title> (imported)". `newId` is called
 * once per copy and nowhere else.
 *
 * Returns `{writes, summary: {imported, copied, duplicates}}`; `writes` is every session to
 * save, in order. No write replaces a different stored row only if `reservedIds` lists every
 * stored id and `newId` mints ids the store does not hold; this cannot check either.
 */
fun mergeImported(
    existing: JsonElement?,
    incoming: JsonElement?,
    newId: () -> JsonElement,
    now: JsonElement = num(0),
    reservedIds: JsonElement? = null,
): JsonObject {
    val reserved: Set<JsonElement?> = (reservedIds as? JsonArray)?.toSet() ?: emptySet()
    val local = HashMap<JsonElement?, JsonElement>()
    for (session in existing as? JsonArray ?: emptyList()) local[session["id"]] = session
    val writes = mutableListOf<JsonElement>()
    var imported = 0
    var copied = 0
    var duplicates = 0

    for (element in incoming as? JsonArray ?: emptyList()) {
        val session = element as? JsonObject ?: throw IllegalArgumentException("an imported session must be an object")
        val current = local[session["id"]]
        if (current == null && session["id"] !in reserved) {
            writes += session
            local[session["id"]] = session
            imported++
            continue
        }
        // An unreadable row cannot be compared, so a collision with one is always a copy.
        if (current != null && sessionsEqual(current, session)) {
            duplicates++
            continue
        }

        val id = newId()
        val baseName = jsSlice(sessionDisplayTitle(session), MAX_SESSION_NAME - IMPORTED_SUFFIX.length)
        val copy = copySession(session, id, str("$baseName$IMPORTED_SUFFIX"), now)
        writes += copy
        local[id] = copy
        copied++
    }

    return obj(
        "writes" to arr(writes),
        "summary" to obj("imported" to num(imported), "copied" to num(copied), "duplicates" to num(duplicates)),
    )
}

/**
 * JSON.parse: only the four JSON whitespace characters, no trailing commas or content, no
 * leading zeros, raw control characters refused, lone surrogate escapes kept. A repeated key
 * keeps its first position and its last value, and array-index keys come first in ascending
 * order, as they do in a JS object. Numbers become JS doubles (1.0 is 1, -0 is 0).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private class JsJson(private val s: String) {
    class SyntaxError : Exception()

    private var i = 0
    private var depth = 0

    private fun enter() {
        if (++depth > MAX_JSON_DEPTH) fail()
    }

    fun parseDocument(): JsonElement {
        val v = value()
        space()
        if (i != s.length) fail()
        return v
    }

    private fun fail(): Nothing = throw SyntaxError()

    private fun space() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun value(): JsonElement {
        space()
        if (i >= s.length) fail()
        return when (s[i]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> JsonPrimitive(string())
            't' -> literal("true", JsonPrimitive(true))
            'f' -> literal("false", JsonPrimitive(false))
            'n' -> literal("null", JsonNull)
            else -> number()
        }
    }

    private fun literal(word: String, v: JsonElement): JsonElement {
        if (!s.startsWith(word, i)) fail()
        i += word.length
        return v
    }

    private fun objectValue(): JsonElement {
        enter()
        i++
        val map = LinkedHashMap<String, JsonElement>()
        space()
        if (i < s.length && s[i] == '}') {
            i++
            depth--
            return JsonObject(map)
        }
        while (true) {
            space()
            if (i >= s.length || s[i] != '"') fail()
            val key = string()
            space()
            if (i >= s.length || s[i] != ':') fail()
            i++
            map[key] = value()
            space()
            if (i >= s.length) fail()
            when (s[i++]) {
                ',' -> continue
                '}' -> break
                else -> fail()
            }
        }
        depth--
        val ordered = LinkedHashMap<String, JsonElement>()
        for (k in jsKeyOrder(map.keys)) ordered[k] = map.getValue(k)
        return JsonObject(ordered)
    }

    private fun arrayValue(): JsonElement {
        enter()
        i++
        val items = mutableListOf<JsonElement>()
        space()
        if (i < s.length && s[i] == ']') {
            i++
            depth--
            return JsonArray(items)
        }
        while (true) {
            items += value()
            space()
            if (i >= s.length) fail()
            when (s[i++]) {
                ',' -> continue
                ']' -> break
                else -> fail()
            }
        }
        depth--
        return JsonArray(items)
    }

    private fun string(): String {
        i++
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail()
            val c = s[i++]
            when {
                c == '"' -> return out.toString()
                c < ' ' -> fail()
                c == '\\' -> {
                    if (i >= s.length) fail()
                    when (s[i++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail()
                            val hex = s.substring(i, i + 4)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> fail()
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): JsonElement {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) fail()
        if (s[i] == '0') i++
        else if (s[i] in '1'..'9') while (i < s.length && s[i] in '0'..'9') i++
        else fail()
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || s[i] !in '0'..'9') fail()
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') fail()
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val d = s.substring(start, i).toDouble()
        // JSON.parse keeps an overflowing literal as Infinity (so migrate rejects a schema of
        // 1e400); jsNumber would make it null. Printing still writes null, as JSON.stringify does.
        return if (d.isInfinite()) JsonUnquotedLiteral(if (d > 0) "Infinity" else "-Infinity") else jsNumber(d)
    }
}
