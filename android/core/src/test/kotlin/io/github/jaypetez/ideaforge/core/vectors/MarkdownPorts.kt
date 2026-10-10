package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.availableTags
import io.github.jaypetez.ideaforge.core.buildExport
import io.github.jaypetez.ideaforge.core.coveragePercent
import io.github.jaypetez.ideaforge.core.exportFilename
import io.github.jaypetez.ideaforge.core.filterSessions
import io.github.jaypetez.ideaforge.core.forSpeech
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.opt
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.libraryCard
import io.github.jaypetez.ideaforge.core.renderBlocks
import io.github.jaypetez.ideaforge.core.sessionLibraryStatus
import io.github.jaypetez.ideaforge.core.sessionSearchText
import io.github.jaypetez.ideaforge.core.slug
import io.github.jaypetez.ideaforge.core.speechChunks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Adapters from the recorded JS calls to the markdown and library ports. Option objects use
// `opt`, so a JS destructuring default applies only to a missing key.

private fun Case.arg(i: Int): JsonElement? = args.getOrNull(i)
private fun strings(items: List<String>): JsonElement = JsonArray(items.map { JsonPrimitive(it) })

val BUILD_EXPORT_PORT: Port = { c ->
    val o = c.arg(1)
    val note = o.opt("note", null)
    json(buildExport(
        c.arg(0),
        mode = stringOf(o.opt("mode", str("claude"))) ?: "",
        note = if (truthy(note)) jsString(note) else null,
    ))
}

val MARKDOWN_PORTS: Map<String, Port> = mapOf(
    "coveragePercent" to { c -> json(coveragePercent(c.arg(0))) },
    "exportFilename" to { c -> json(exportFilename(c.arg(0))) },
    "buildExport" to BUILD_EXPORT_PORT,
    "libraryCard" to { c -> libraryCard(c.arg(0)) },
    "sessionLibraryStatus" to { c -> json(sessionLibraryStatus(c.arg(0))) },
    "sessionSearchText" to { c -> json(sessionSearchText(c.arg(0))) },
    "slug" to { c ->
        val fallback = c.arg(1)
        json(if (fallback == null) slug(c.arg(0)) else slug(c.arg(0), jsString(fallback)))
    },
    "renderBlocks" to { c -> JsonArray(renderBlocks(c.arg(0))) },
    "forSpeech" to { c ->
        json(forSpeech(c.arg(0), toNumber(c.arg(1).opt("maxChars", num(3000))).toInt()))
    },
    "speechChunks" to { c ->
        strings(speechChunks(c.arg(0), toNumber(c.arg(1).opt("maxChars", num(350))).toInt()))
    },
    "availableTags" to { c -> JsonArray(availableTags(c.arg(0))) },
    "filterSessions" to { c ->
        val o = c.arg(1)
        JsonArray(filterSessions(
            c.arg(0),
            query = stringOf(o.opt("query", str(""))) ?: "",
            status = stringOf(o.opt("status", str("active"))) ?: "",
            tag = stringOf(o.opt("tag", str(""))) ?: "",
        ))
    },
)
