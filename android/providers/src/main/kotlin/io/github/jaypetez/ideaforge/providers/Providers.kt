package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.Core

/**
 * The Kotlin port of `src/providers` lands here (#78, #79). It depends on :core and never the
 * other way round, mirroring the web app's layering.
 */
object Providers {
    /** Requests are built for sessions of this schema; it comes from :core, not a copy. */
    val sessionSchema: Int get() = Core.SESSION_SCHEMA
}
