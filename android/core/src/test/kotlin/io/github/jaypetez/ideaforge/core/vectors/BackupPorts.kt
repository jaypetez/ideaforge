package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.BACKUP_FORMAT
import io.github.jaypetez.ideaforge.core.BACKUP_VERSION
import io.github.jaypetez.ideaforge.core.MAX_BACKUP_BYTES
import io.github.jaypetez.ideaforge.core.MAX_BACKUP_SESSIONS
import io.github.jaypetez.ideaforge.core.backupFilename
import io.github.jaypetez.ideaforge.core.buildBackup
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.opt
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.mergeImported
import io.github.jaypetez.ideaforge.core.parseBackup
import io.github.jaypetez.ideaforge.core.sessionsEqual
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private fun Case.arg(i: Int): JsonElement? = args.getOrNull(i)

val BACKUP_PORTS: Map<String, Port> = mapOf(
    "sessionsEqual" to { c -> JsonPrimitive(sessionsEqual(c.arg(0), c.arg(1))) },
    "backupFilename" to { c -> str(backupFilename(c.arg(0))) },
    "buildBackup" to { c -> str(buildBackup(c.arg(0), c.arg(1).opt("now", num(0)))) },
    "parseBackup" to { c ->
        val o = c.arg(1)
        parseBackup(
            c.arg(0),
            maxBytes = toNumber(o.opt("maxBytes", num(MAX_BACKUP_BYTES))),
            maxSessions = toNumber(o.opt("maxSessions", num(MAX_BACKUP_SESSIONS))),
        )
    },
    // The recorder's wrapper hands out `ids` in order, one per copy, and throws when they run
    // out, so a port that mints an id for a duplicate or a fresh import fails here.
    "mergeImported" to { c ->
        val o = c.arg(2)
        val queue = ArrayDeque((o.opt("ids", JsonArray(emptyList())) as JsonArray).toList())
        mergeImported(c.arg(0), c.arg(1), newId = {
            queue.removeFirstOrNull() ?: throw IllegalStateException("newId: no ids left")
        }, now = o.opt("now", num(0)))
    },
)

val BACKUP_CONSTANTS: Map<String, JsonElement> = mapOf(
    "BACKUP_FORMAT" to str(BACKUP_FORMAT),
    "BACKUP_VERSION" to num(BACKUP_VERSION),
    "MAX_BACKUP_BYTES" to num(MAX_BACKUP_BYTES),
    "MAX_BACKUP_SESSIONS" to num(MAX_BACKUP_SESSIONS),
)
