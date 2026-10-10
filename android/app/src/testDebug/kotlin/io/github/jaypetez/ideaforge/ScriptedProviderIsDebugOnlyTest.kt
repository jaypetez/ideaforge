package io.github.jaypetez.ideaforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scripted provider replays a canned interview with no key: harmless in a debug build, a
 * way to fake a whole run in a release one. It, and the walkthrough script it reads, must live
 * in the debug source set only (#94). AGP creates no release unit-test task, so this debug
 * test reads the sources and the release variant's merged assets (wired in by
 * app/build.gradle.kts), as RecognizerProbeTest does for the release manifest.
 */
class ScriptedProviderIsDebugOnlyTest {
    private fun appDir(): File {
        val start = File("").absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { if (File(it, "src/debug").isDirectory) it else File(it, "app") }
            .firstOrNull { File(it, "src/debug").isDirectory && File(it, "src/main").isDirectory }
            ?: error("no android/app above $start")
    }

    // Source sets that never reach a release APK. Everything else (main, release, any
    // flavour added later) is checked rather than allowlisted by omission.
    private val debugOnly = setOf("debug", "testDebug", "androidTest", "androidTestDebug", "test")

    @Test
    fun theScriptedProviderIsDeclaredOnlyInTheDebugSourceSet() {
        val src = File(appDir(), "src")
        val declarations = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\bclass\s+ScriptedProvider\b""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(src).invariantSeparatorsPath }
            .toList()
        assertEquals("exactly one declaration, in src/debug: $declarations", 1, declarations.size)
        assertTrue(declarations.single(), declarations.single().startsWith("debug/"))
    }

    @Test
    fun noShippedSourceSetNamesTheScriptedProviderOrItsScript() {
        val src = File(appDir(), "src")
        val shipped = src.listFiles()!!.filter { it.isDirectory && it.name !in debugOnly }
        assertTrue("main is checked", shipped.any { it.name == "main" })
        assertTrue("release is checked", shipped.any { it.name == "release" })
        for (dir in shipped) {
            for (file in dir.walkTopDown().filter { it.isFile }) {
                val text = file.readText()
                val where = file.relativeTo(src).invariantSeparatorsPath
                assertFalse("$where names ScriptedProvider", text.contains("ScriptedProvider"))
                assertFalse("$where names the walkthrough script", text.contains(ScriptedProvider.ASSET))
            }
        }
        // The release DebugProviders answers null for every launch name.
        val releaseProviders = File(src, "release/kotlin/io/github/jaypetez/ideaforge/DebugProviders.kt").readText()
        assertFalse("release DebugProviders knows \"scripted\"", releaseProviders.contains("\"scripted\""))
    }

    @Test
    fun theReleaseAssetsCarryNoWalkthroughScript() {
        val path = System.getProperty("ideaforge.releaseAssets") ?: error("ideaforge.releaseAssets is not set")
        val assets = File(path)
        assertTrue("the release assets were not built: $path", assets.isDirectory)
        val names = assets.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertFalse("release packages ${ScriptedProvider.ASSET}: $names", ScriptedProvider.ASSET in names)
    }
}
