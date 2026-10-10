package io.github.jaypetez.ideaforge.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // Clocks, from every library a later slice might reach for.
        ".now(", "Clock.System", "Clock.system", "TimeSource", "currentTimeMillis", "nanoTime",
        "Date(", "Calendar.getInstance",
        // Randomness and identity.
        "Random", "UUID", "SecureRandom",
        // Network, files, platform, blocking.
        "java.net", "okhttp", "android.", "File(", "Files.", "Thread.sleep", "Dispatchers.IO",
    )

    @Test
    fun coreSourcesUseNoClockRandomnessNetworkOrPlatform() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "core/src/main/kotlin") }
            .first { it.isDirectory }
        val offences = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            codeOnly(file.readText()).lines().withIndex().flatMap { (i, line) ->
                banned.filter { line.contains(it) }.map { "${file.name}:${i + 1}: $it" }
            }
        }.toList()
        assertTrue(offences.isEmpty(), "impure code in :core:\n${offences.joinToString("\n")}")
    }

    // Comments may name the rule they keep ("no Random here"); code may not break it.
    @Test
    fun commentsAreIgnoredButCodeIsNot() {
        val src = "/** Pure: no Random. */\nval a = 1 // never Random\n/* Clock.System\n */ val b = Random(1)\n"
        val code = codeOnly(src).lines()
        assertEquals(src.lines().size, code.size, "line numbers must survive stripping")
        assertTrue(code.take(3).none { "Random" in it || "Clock" in it }, code.toString())
        assertTrue("Random(1)" in code[3], code.toString())
    }

    /**
     * Blank out comments, keeping newlines so reported line numbers stay right. String
     * literals are left alone: a banned word inside one is still worth a look.
     */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var block = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                block && c == '*' && next == '/' -> { block = false; i += 2; continue }
                block -> out.append(if (c == '\n') '\n' else ' ')
                c == '/' && next == '*' -> { block = true; i += 2; continue }
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
