package io.github.jaypetez.ideaforge.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Kotlin twin of tools/lint-purity.mjs. :core is the port of src/core and src/runtime,
 * and inherits their rule: no clock, no randomness, no network, no platform. The turn
 * prompt must be a pure function of session state, and an interrupted turn is recovered by
 * re-issuing the byte-identical prompt; one stray timestamp breaks that silently.
 *
 * Being a JVM module already keeps `android.*` out; this catches what would still compile.
 */
class CorePurityTest {
    private val banned = listOf(
        "System.currentTimeMillis", "System.nanoTime", "Instant.now", "LocalDate.now",
        "LocalDateTime.now", "Clock.system", "Random", "UUID", "SecureRandom", "Math.random",
        "java.net", "okhttp", "android.", "Thread.sleep", "Dispatchers.IO", "File(",
    )

    @Test
    fun coreSourcesUseNoClockRandomnessNetworkOrPlatform() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "core/src/main/kotlin") }
            .first { it.isDirectory }
        val offences = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex()
                // Comments may name the rule they keep; code may not break it.
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .flatMap { (i, line) ->
                    banned.filter { line.contains(it) }.map { "${file.name}:${i + 1}: $it" }
                }
        }.toList()
        assertTrue(offences.isEmpty(), "impure code in :core:\n${offences.joinToString("\n")}")
    }
}
