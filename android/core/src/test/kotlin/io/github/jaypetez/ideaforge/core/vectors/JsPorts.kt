package io.github.jaypetez.ideaforge.core.vectors

import io.github.jaypetez.ideaforge.core.js.jsNumber
import io.github.jaypetez.ideaforge.core.js.jsNumberString
import io.github.jaypetez.ideaforge.core.js.jsPlus
import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.toNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A JS number as the vectors record it: the number when finite, else its String(). */
private fun numberOut(d: Double): JsonElement = if (d.isFinite()) jsNumber(d) else str(jsNumberString(d))

/** JsCompat against the semantics recorded from Node (the `js` vector family). */
val JS_PORTS: Map<String, Port> = mapOf(
    "String" to { c -> str(jsString(c.args.getOrNull(0))) },
    "Number" to { c -> numberOut(toNumber(c.args.getOrNull(0))) },
    "plusOne" to { c ->
        val a = c.args.getOrNull(0)
        val r = jsPlus(a, 1)
        if (r is JsonNull) numberOut(toNumber(a) + 1) else r
    },
)
