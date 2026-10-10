package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.normalizeTrigger
import io.github.jaypetez.ideaforge.core.triggerWarning
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** JS `String(raw == null ? '' : raw)`: the recorded cases include `null` and the number 7. */
private fun jsArg(e: JsonElement?): String? = if (e == null || e is JsonNull) null else jsString(e)

/**
 * The part of the `speech` family (src/core/driving.js) the settings screen needs (#88). The
 * rest of the family stays owed to the hands-free port (#77).
 */
val SPEECH_PORTS: Map<String, Port> = mapOf(
    "normalizeTrigger" to { c -> json(normalizeTrigger(jsArg(c.args.getOrNull(0)))) },
    "triggerWarning" to { c -> json(triggerWarning(jsArg(c.args.getOrNull(0)))) },
)
