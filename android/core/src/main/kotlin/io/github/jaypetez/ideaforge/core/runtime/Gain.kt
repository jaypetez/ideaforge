package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.DIMENSION_IDS
import io.github.jaypetez.ideaforge.core.LEVEL_RANK
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import kotlinx.serialization.json.JsonObject

// Zero-gain arithmetic: the Kotlin port of src/runtime/gain.js.

/** Dimension id → level, before or after a turn. */
fun levelSnapshot(session: JsonObject): Map<String, String?> =
    DIMENSION_IDS.associateWith { stringOf((session["coverage"] as JsonObject)[it]["level"]) }

/**
 * A turn gained nothing when no dimension sits at a higher level after the ratchet than
 * before it. Measured on the APPLIED result, never on the model's claim: a model asserting
 * `covered` while the ratchet clamps it is making no progress, which is exactly what the
 * exhaustion exit exists to catch.
 */
fun isZeroGain(before: Map<String, String?>, after: Map<String, String?>): Boolean =
    DIMENSION_IDS.all { id ->
        val a = LEVEL_RANK[after[id]]
        val b = LEVEL_RANK[before[id]]
        // JS compares undefined ranks as false, so an unknown level is never "not higher".
        a != null && b != null && a <= b
    }
