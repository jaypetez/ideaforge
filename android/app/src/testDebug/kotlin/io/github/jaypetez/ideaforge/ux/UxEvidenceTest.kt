package io.github.jaypetez.ideaforge.ux

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Helper tests must not start AppGraph's background loads.
@Config(sdk = [35], application = Application::class)
class UxEvidenceTest {
    private val viewport = UxBounds(0f, 24f, 390f, 820f)
    private val sha = "366398b999443bdd3cb3aed7a220713e18226879"

    @Test
    fun aNormalWalkthroughNeedsNoCaptureArgumentsButRequestedEvidenceNeedsASha() {
        assertNull(UxCaptureRequest.from(emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { UxCaptureRequest.from(mapOf("uxCapture" to "matrix")) }
        assertThrows(IllegalArgumentException::class.java) { UxCaptureRequest.from(mapOf("uxCaptureSha" to sha)) }
        assertThrows(IllegalArgumentException::class.java) {
            UxCaptureRequest.from(mapOf("uxCapture" to "matrix", "uxCaptureSha" to "main"))
        }
        assertEquals("expanded", UxCaptureRequest.from(mapOf(
            "uxCapture" to "matrix", "uxCaptureSha" to sha, "uxCaptureWindow" to "expanded",
        ))!!.window)
        assertEquals(listOf("voice-listening", "result-long"), UxCaptureRequest.from(mapOf(
            "uxCapture" to "matrix", "uxCaptureSha" to sha, "uxCaptureStates" to "voice-listening+result-long",
        ))!!.states)
        assertEquals("walkthrough", UxCaptureRequest.from(mapOf(
            "uxCapture" to "walkthrough", "uxCaptureSha" to sha,
        ))!!.mode)
        assertThrows(IllegalArgumentException::class.java) {
            UxCaptureRequest.from(mapOf("uxCapture" to "walkthrough", "uxCaptureSha" to sha, "uxCaptureStates" to "fresh"))
        }
    }

    @Test
    fun developmentCapturesAreExplicitlyLabelledAndUnknownSourceStatesFail() {
        val args = mapOf("uxCapture" to "matrix", "uxCaptureSha" to sha)
        assertEquals("clean", UxCaptureRequest.from(args)!!.sourceState)
        assertEquals("development-dirty", UxCaptureRequest.from(args + ("uxCaptureSourceState" to "development-dirty"))!!.sourceState)
        assertThrows(IllegalArgumentException::class.java) {
            UxCaptureRequest.from(args + ("uxCaptureSourceState" to "unknown"))
        }
    }

    @Test
    fun teardownAttemptsEveryOperationAndFinalizesFailedEvidenceWithoutLosingTheOriginalError() {
        val original = IllegalStateException("capture failed")
        val close = IllegalStateException("activity close failed")
        val delete = IllegalStateException("owned directory deletion failed")
        val finalize = IllegalStateException("failed matrix")
        val calls = mutableListOf<String>()
        var terminalStatus = "running"
        val thrown = assertThrows(IllegalStateException::class.java) {
            UxEvidence.finish(original, listOf(
                { calls += "close"; throw close },
                { calls += "restore" },
                { calls += "delete"; throw delete },
            )) { errors ->
                calls += "finalize"
                assertEquals(listOf(original, close, delete), errors)
                terminalStatus = if (errors.isEmpty()) "passed" else "failed"
                throw finalize
            }
        }
        assertSame(original, thrown)
        assertEquals(listOf(close, delete, finalize), thrown.suppressed.toList())
        assertEquals(listOf("close", "restore", "delete", "finalize"), calls)
        assertEquals("failed", terminalStatus)
    }

    @Test
    fun aCleanupFailureAloneMakesOtherwiseCompleteEvidenceFail() {
        val failure = IllegalStateException("restore failed")
        var finalized = false
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            UxEvidence.finish(null, listOf({ throw failure })) { errors ->
                assertEquals(listOf(failure), errors)
                finalized = true
            }
        })
        assertTrue(finalized)
    }

    @Test
    fun aLateWalkthroughFailureLeavesEarlierFrameEvidenceAndItsOriginalFailureIntact() {
        val frames = linkedMapOf("seed" to "passed", "chip" to "chip-specific failure")
        val pending = UxEvidence.unrecorded(setOf("seed", "chip", "result"), frames.keys)
        for (frame in pending) frames[frame] = "late result timeout"
        assertEquals(setOf("result"), pending)
        assertEquals("passed", frames["seed"])
        assertEquals("chip-specific failure", frames["chip"])
        assertEquals("late result timeout", frames["result"])
    }

    @Test
    fun missingStatesFailInsteadOfPassingAnEmptyOrPartialCapture() {
        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireComplete(setOf("fresh", "voice-listening"), setOf("fresh"), emptyList())
        }
        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireComplete(setOf("fresh"), setOf("fresh"), listOf("fresh: blank screenshot"))
        }
        UxEvidence.requireComplete(setOf("fresh"), setOf("fresh"), emptyList())
    }

    @Test
    fun clippingCannotBeHiddenByClampedSemanticsBoundsOrByAnUnlabelledButton() {
        val rawButton = UxBounds(345f, 740f, 440f, 805f)
        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireControl("Send", rawButton, viewport, "Send", true)
        }

        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireControl("Send", UxBounds(20f, 740f, 140f, 805f), viewport, "", true)
        }
        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireControl("Send", UxBounds(20f, 740f, 140f, 805f), viewport, "Send", false)
        }
        UxEvidence.requireControl("Send after scrolling", UxBounds(20f, 740f, 140f, 805f), viewport, "Send", true)
        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireControl("clipped by the scrolling pane", UxBounds(20f, 740f, 140f, 805f),
                viewport.intersect(UxBounds(20f, 740f, 140f, 780f)), "Send", true)
        }
    }

    @Test
    fun labelChecksMeasurePaintedLinesNotUnusedParagraphConstraints() {
        UxEvidence.requireLabel("Pause", 134f, 64f, 134, 64, false)
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireLabel("Pause", 134f, 64f, 90, 64, false) }
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireLabel("Pause", 134f, 64f, 134, 32, false) }
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireLabel("Pause", 100f, 64f, 134, 64, true) }
    }

    @Test
    fun blackAtdAndBlankWhiteScreensFailEvenIfSystemBarsRender() {
        val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
        for (colour in listOf(Color.BLACK, Color.WHITE)) {
            bitmap.eraseColor(colour)
            for (x in 0 until bitmap.width) bitmap.setPixel(x, 5, Color.rgb(x % 255, 140, 100))
            assertThrows(IllegalStateException::class.java) { UxEvidence.requireRendered(bitmap, viewport) }
        }

        for (y in 30 until 820) for (x in 0 until 390) {
            bitmap.setPixel(x, y, Color.rgb(x % 255, y % 255, 40))
        }
        UxEvidence.requireRendered(bitmap, viewport)
        assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireRendered(bitmap, UxBounds(0f, 24f, 780f, 1640f))
        }
        bitmap.recycle()
    }

    @Test
    fun aThemePreferenceAloneIsNotEvidenceThatThePixelsChanged() {
        UxEvidence.requireTheme("dark", UxPixels(20, 30.0, 0x101010))
        UxEvidence.requireTheme("light", UxPixels(20, 220.0, 0xF0F0F0))
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTheme("dark", UxPixels(20, 220.0, 0xF0F0F0)) }
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTheme("light", UxPixels(20, 30.0, 0x101010)) }
    }

    @Test
    fun theSharedInventoryAndEverySessionReachNativeWithoutAMigrationRewrite() {
        val dir = System.getProperty("ideaforge.fixtures") ?: error("ideaforge.fixtures is missing")
        val fixtures = UxFixtures.parse(File(dir, UxFixtures.ASSET).readText())
        assertEquals(100, fixtures.datasets.getValue("hundred").size)
        assertEquals(12, fixtures.datasets.getValue("mixed").size)
        assertTrue(fixtures.states.any { it.id == "voice-listening" && it.evidence == "isolated-component" })
        assertEquals("writing", fixtures.state("voice-writing").phase)
        assertEquals("ux-long-active", fixtures.state("interview-long").sessionId)
        assertThrows(IllegalStateException::class.java) { fixtures.state("invented-phase") }
    }
}
