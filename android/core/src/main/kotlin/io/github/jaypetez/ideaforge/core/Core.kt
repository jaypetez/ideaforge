package io.github.jaypetez.ideaforge.core

/**
 * The Kotlin port of `src/core` and `src/runtime` lands here slice by slice (#72 onwards),
 * each one proven against the golden vectors in `test/vectors/` rather than by reading.
 */
object Core {
    /** The session schema this port understands; `src/core/session.js` owns the number. */
    const val SESSION_SCHEMA = 2
}
