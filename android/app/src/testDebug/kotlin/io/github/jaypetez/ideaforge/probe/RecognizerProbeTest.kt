package io.github.jaypetez.ideaforge.probe

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.usableRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The debug build's recognizer probe (issue #89) launches, and the launch hook reaches it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognizerProbeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theProbeLaunchesAndReportsTheRecognizer() {
        installTestGraph(ring = usableRing())
        ActivityScenario.launch(RecognizerProbeActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(RecognizerProbeActivity.SCREEN), 10_000)
            compose.waitUntilAtLeastOneExists(hasText("Start trial"), 5_000)
            // The capability report runs on launch, before any trial.
            assertTrue(ProbeRunner.text(), ProbeRunner.text().contains("isRecognitionAvailable:"))
        }
    }

    @Test
    fun theDebugManifestDeclaresTheProbeAndItsPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS)
        assertTrue(info.activities!!.any { it.name == RecognizerProbeActivity::class.java.name })
        assertTrue(info.requestedPermissions!!.contains(android.Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun probeRecognizerOnTheLaunchIntentOpensTheProbe() {
        installTestGraph(ring = usableRing())
        val launch = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("probe", "recognizer").putExtra("restarts", 3)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            scenario.onActivity { activity ->
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull("nothing was started", next)
                assertEquals(RecognizerProbeActivity::class.java.name, next.component!!.className)
                // Trial settings ride along, so a phone run can be scripted over adb.
                assertEquals(3, RecognizerProbeActivity.configFrom(next).restarts)
            }
        }
    }

    // `check` runs only the debug unit tests, so ReleaseHasNoProbeTest (testRelease) is not
    // what guards CI. This does, from the source sets: the probe and its microphone
    // permission live under src/debug, and src/main neither declares nor names them.
    @Test
    fun theReleaseSourceSetsCarryNoProbe() {
        val start = java.io.File(System.getProperty("user.dir")).absoluteFile
        val app = generateSequence(start) { it.parentFile }
            .flatMap { sequenceOf(it, java.io.File(it, "app"), java.io.File(it, "android/app")) }
            .firstOrNull { java.io.File(it, "src/main/AndroidManifest.xml").isFile && java.io.File(it, "src/debug").isDirectory }
            ?: error("no android/app above $start")
        val main = java.io.File(app, "src/main")
        val manifest = java.io.File(main, "AndroidManifest.xml").readText()
        assertTrue("main manifest asks for RECORD_AUDIO", !manifest.contains("RECORD_AUDIO"))
        assertTrue("main manifest names the probe", !manifest.contains("probe", ignoreCase = true))
        val leaks = (main.walkTopDown() + java.io.File(app, "src/release").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("RecognizerProbeActivity") || it.contains("import io.github.jaypetez.ideaforge.probe") } }
            .toList()
        assertTrue("release-reachable sources name the probe: $leaks", leaks.isEmpty())
        val releaseHook = java.io.File(app, "src/release/kotlin/io/github/jaypetez/ideaforge/DebugProbes.kt").readText()
        assertTrue(releaseHook.contains("): Boolean = false"))
    }

    @Test
    fun errorCodesAreNamedAndUnknownOnesStayNumbers() {
        assertEquals("ERROR_NO_MATCH", ProbeFormat.errorName(7))
        assertEquals("ERROR_RECOGNIZER_BUSY", ProbeFormat.errorName(8))
        assertEquals("ERROR_INSUFFICIENT_PERMISSIONS", ProbeFormat.errorName(9))
        assertEquals("ERROR_UNKNOWN(99)", ProbeFormat.errorName(99))
    }

    @Test
    fun hypothesesPairTextWithConfidence() {
        assertEquals("[\"over\"@0.912 | \"over there\"@n/a]", ProbeFormat.hypotheses(listOf("over", "over there"), floatArrayOf(0.912f)))
        assertEquals("texts=<none>", ProbeFormat.hypotheses(null, null))
    }

    @Test
    fun aParsedWavKeepsItsFormat() {
        // 44-byte canonical header, 24 kHz mono 16-bit, four samples.
        val data = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(24_000); putInt(48_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        val clip = TtsClip.parseWav(header + data)
        assertEquals(24_000, clip.sampleRate)
        assertEquals(1, clip.channels)
        assertEquals(data.toList(), clip.pcm.toList())
    }
}
