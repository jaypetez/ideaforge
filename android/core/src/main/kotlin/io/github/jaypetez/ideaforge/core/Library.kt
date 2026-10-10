package io.github.jaypetez.ideaforge.core

import io.github.jaypetez.ideaforge.core.js.JS_SPACES
import io.github.jaypetez.ideaforge.core.js.arr
import io.github.jaypetez.ideaforge.core.js.bool
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.jsTrim
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.obj
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.Collator
import java.util.Locale

// Port of src/core/library.js: pure helpers for the on-device ideas library.

private fun text(value: JsonElement?): String = stringOf(value) ?: ""
private fun normalize(value: JsonElement?): String = jsTrim(text(value).lowercase().replace(JS_SPACES, " "))
private fun itemsOf(value: JsonElement?): List<JsonElement> = (value as? JsonArray) ?: emptyList()

fun sessionLibraryStatus(session: JsonElement?): String = when {
    truthy(session["archivedAt"]) -> "archived"
    stringOf(session["status"]) == "done" -> "completed"
    else -> "active"
}

fun sessionSearchText(session: JsonElement?): String {
    val synthesis = session["synthesis"]
    val parts = mutableListOf<JsonElement?>(
        str(sessionDisplayTitle(session, "")),
        session["name"], session["title"], session["opening"], session["draftAnswer"],
    )
    parts += itemsOf(session["tags"])
    for (turn in itemsOf(session["turns"])) { parts += turn["question"]; parts += turn["answer"] }
    for (fact in itemsOf(session["facts"])) parts += fact["fact"]
    parts += if (truthy(synthesis)) synthesis["text"] else synthesis
    return normalize(str(parts.joinToString(" ") { text(it) }))
}

private fun answeredOrSkipped(t: JsonElement?): Boolean = truthy(t["answer"]) || truthy(t["skipped"])

fun libraryCard(session: JsonElement?): JsonObject {
    val turns = itemsOf(session["turns"])
    return obj(
        "id" to session["id"],
        "title" to str(sessionDisplayTitle(session)),
        "status" to str(sessionLibraryStatus(session)),
        "updatedAt" to (if (truthy(session["updatedAt"])) session["updatedAt"] else num(0)),
        "questionCount" to num(turns.count(::answeredOrSkipped)),
        "coverage" to num(coveragePercent(session)),
        "tags" to ((session["tags"] as? JsonArray) ?: arr(emptyList())),
        "canExport" to bool(turns.any(::answeredOrSkipped)),
    )
}

/** JS `localeCompare` in an English locale; the browser's default collation for this app. */
private val COLLATOR: Collator = Collator.getInstance(Locale.ENGLISH)

fun availableTags(sessions: JsonElement?): List<JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    for (session in itemsOf(sessions)) {
        for (tag in itemsOf(session["tags"])) {
            val key = normalize(tag)
            if (key.isNotEmpty() && key !in out) out[key] = tag
        }
    }
    return out.values.sortedWith { a, b -> COLLATOR.compare(stringOf(a) ?: "", stringOf(b) ?: "") }
}

fun filterSessions(
    sessions: JsonElement?,
    query: String = "",
    status: String = "active",
    tag: String = "",
): List<JsonElement> {
    val needle = normalize(str(query))
    val tagNeedle = normalize(str(tag))
    fun updated(s: JsonElement?) = if (truthy(s["updatedAt"])) toNumber(s["updatedAt"]) else 0.0
    return itemsOf(sessions)
        .filter { status == "all" || sessionLibraryStatus(it) == status }
        .filter { tagNeedle.isEmpty() || itemsOf(it["tags"]).any { v -> normalize(v) == tagNeedle } }
        .filter { needle.isEmpty() || sessionSearchText(it).contains(needle) }
        .sortedWith { a, b -> updated(b).compareTo(updated(a)) }
}
