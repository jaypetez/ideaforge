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
    fun nativeComfortTargetsRejectTheOldFortyDpButtonAndASmallVoiceAction() {
        UxEvidence.requireTarget("Send", 48f, 48f)
        UxEvidence.requireTarget("Pause", 96f, 64f, minHeightDp = 64f)
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTarget("Send", 64f, 40f) }
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTarget("Voice start", 96f, 48f, minHeightDp = 64f) }
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTarget("narrow", 40f, 64f) }
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

    /**
     * The rejected chooser frames had a complete system tree and passed the whole-screen
     * render check while the screen showed only scrim over the dimmed source. Coordinates
     * mirror the rejected compact frame's reported chooser header and drag handle.
     */
    @Test
    fun aChooserTreeOverOnlyItsScrimIsNotAPaintedSheetInEitherTheme() {
        val header = UxBounds(0f, 2065f, 1080f, 2118f)
        val handle = UxBounds(456f, 2086f, 624f, 2097f)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        fun fill(box: UxBounds, colour: Int) {
            for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) bitmap.setPixel(x, y, colour)
        }
        fun dimmedSource() {
            bitmap.eraseColor(Color.rgb(8, 9, 12))
            // Dimmed source text runs through the band above the header, as in the real frame.
            for (y in 1800 until 2060 step 40) fill(UxBounds(40f, y.toFloat(), 700f, y + 12f), Color.rgb(70, 70, 76))
        }
        for ((scrim, sheet, ink) in listOf(
            Triple(Color.rgb(8, 9, 12), Color.rgb(242, 239, 247), Color.rgb(196, 196, 204)),
            Triple(Color.rgb(8, 9, 12), Color.rgb(43, 41, 48), Color.rgb(140, 138, 148)),
            Triple(Color.rgb(120, 120, 124), Color.rgb(242, 239, 247), Color.rgb(196, 196, 204)),
        )) {
            dimmedSource()
            assertEquals("scrim over the source is not a sheet", false, UxEvidence.sheetPainted(bitmap, header, handle))
            bitmap.eraseColor(scrim)
            fill(UxBounds(0f, header.top, 1080f, 2400f), sheet)
            assertEquals("a sheet without its painted handle is not complete", false,
                UxEvidence.sheetPainted(bitmap, header, handle))
            fill(handle, ink)
            assertTrue("the painted sheet and handle are accepted", UxEvidence.sheetPainted(bitmap, header, handle))
            fill(UxBounds(0f, header.top - header.height, 1080f, header.top), sheet)
            assertEquals("a header indistinguishable from what lies above it is not proof", false,
                UxEvidence.sheetPainted(bitmap, header, handle))
        }
        assertEquals("reported bounds outside the screenshot are never accepted", false,
            UxEvidence.sheetPainted(bitmap, UxBounds(0f, 2390f, 1080f, 2443f), UxBounds(456f, 2400f, 624f, 2411f)))
        bitmap.recycle()
    }

    /**
     * A Save picker frame was accepted while the screen was still the app, unchanged or mid
     * fade. A light picker over a light app differs in only 13-25% of samples, so the proof
     * is change from the pre-launch frame plus a settled (repeated) sample, not identity.
     */
    @Test
    fun aPickerFrameMustChangeFromTheAppAndSettleBeforeItIsEvidence() {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        fun band(top: Int, bottom: Int, colour: Int) {
            for (y in top until bottom) for (x in 0 until bitmap.width) bitmap.setPixel(x, y, colour)
        }
        bitmap.eraseColor(Color.rgb(248, 247, 252))
        band(200, 320, Color.rgb(30, 30, 40))
        val app = UxEvidence.frameSample(bitmap)
        assertEquals("the unchanged app is not a picker", false, UxEvidence.windowChanged(app, UxEvidence.frameSample(bitmap)))

        // A light picker: same near-white background, different toolbar and bottom bar.
        bitmap.eraseColor(Color.WHITE)
        band(60, 180, Color.rgb(30, 30, 40))
        band(2150, 2280, Color.rgb(33, 150, 243))
        val picker = UxEvidence.frameSample(bitmap)
        assertTrue("a light picker over a light app is a change", UxEvidence.windowChanged(app, picker))
        assertTrue("the light case really shares most samples",
            app.indices.count { app[it] != picker[it] } < app.size / 2)
        assertEquals("a single changed sample is not yet settled", false, UxEvidence.settled(null, picker))
        assertTrue(UxEvidence.settled(picker, UxEvidence.frameSample(bitmap)))

        // Mid-transition: the next sample differs, so the frame is not settled.
        band(0, 60, Color.rgb(120, 120, 120))
        assertEquals(false, UxEvidence.settled(picker, UxEvidence.frameSample(bitmap)))
        bitmap.recycle()
    }

    @Test
    fun aThemePreferenceAloneIsNotEvidenceThatThePixelsChanged() {
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTheme("dark", UxPixels(20, 30.0, 0x101010)) }
        assertThrows(IllegalStateException::class.java) { UxEvidence.requireTheme("light", UxPixels(20, 220.0, 0xF0F0F0)) }
    }

    @Test
    fun actualRootPixelsProveTheThemeDespiteALargeInverseSnackbarAndRejectASwappedTheme() {
        val light = Color.rgb(245, 247, 250)
        val dark = Color.rgb(17, 22, 27)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val area = UxBounds(0f, 0f, 200f, 100f)
        try {
            for ((theme, root) in listOf("light" to light, "dark" to dark)) {
                val inverse = if (theme == "light") Color.rgb(27, 34, 42) else Color.rgb(222, 230, 238)
                val wrongRoot = if (theme == "light") dark else light
                bitmap.eraseColor(root)
                for (y in 20 until 100) for (x in 0 until 200) bitmap.setPixel(x, y, inverse)
                for (i in 0 until 16) bitmap.setPixel(i, 0, Color.rgb(i * 16, 80, 140))
                val pixels = UxEvidence.requireRendered(bitmap, area, root)
                assertTrue("the inverse message reverses the old brightness proxy",
                    if (theme == "light") pixels.meanLuma < 130 else pixels.meanLuma > 130)
                UxEvidence.requireTheme(theme, pixels)
                val swapped = UxEvidence.requireRendered(bitmap, area, wrongRoot)
                assertThrows(IllegalStateException::class.java) { UxEvidence.requireTheme(if (theme == "light") "dark" else "light", swapped) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun anUnsettledWindowFrameIsDiagnosticUntilTwoConsecutiveRequestedRootSamplesMatch() {
        var clock = 0L
        val requested = 0x121316
        val frames = listOf(
            UxPixels(20, 40.0, 0x16171A, rootSurfaceRgb = requested, rootSurfaceMatches = 0, sampledPixels = 20_000),
            UxPixels(20, 30.0, requested, rootSurfaceRgb = requested, rootSurfaceMatches = 4_000, sampledPixels = 20_000),
            UxPixels(20, 30.0, requested, rootSurfaceRgb = requested, rootSurfaceMatches = 4_000, sampledPixels = 20_000),
        )
        val attempts = mutableListOf<UxPixels>()
        val result = UxEvidence.requireStableTheme("dark", 100L, { clock }) {
            clock += 10
            frames[attempts.size].also(attempts::add)
        }
        assertEquals("the first mismatching frame remains an attempt, not a false failure/pass", frames, attempts)
        assertSame(frames.last(), result)
    }

    @Test
    fun aBlankFrameUsesTheExistingBudgetAndResetsConsecutiveRenderedRootProof() {
        val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        var clock = 0L
        var attempts = 0
        val rendered = listOf(false, true, false, true, true)
        val pixels = UxPixels(20, 30.0, 0x121316, rootSurfaceRgb = 0x121316,
            rootSurfaceMatches = 4_000, sampledPixels = 20_000)
        try {
            val result = UxEvidence.requireStableTheme("dark", 100L, { clock }) {
                clock += 10
                if (!rendered[attempts++]) UxEvidence.requireRendered(bitmap, viewport)
                pixels
            }
            assertSame(pixels, result)
            assertEquals("neither an initial blank nor a blank between matches can certify the root", 5, attempts)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun aPermanentlyBlankFrameStillFailsWithinTheUnchangedBudget() {
        val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        var clock = 0L
        var attempts = 0
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                UxEvidence.requireStableTheme("light", 40L, { clock }) {
                    clock += 10
                    attempts++
                    UxEvidence.requireRendered(bitmap, viewport)
                }
            }
            assertEquals(4, attempts)
            assertEquals(40L, clock)
            assertTrue(failure.message.orEmpty().contains("blank/unrendered app screenshot"))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun frameSettlingDoesNotRetryUnrelatedCaptureFailures() {
        var attempts = 0
        val failure = IllegalStateException("screenshot pixels do not cover the measured app viewport")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            UxEvidence.requireStableTheme("light", 100L, { 0L }) {
                attempts++
                throw failure
            }
        })
        assertEquals(1, attempts)
    }

    @Test
    fun oneCorrectFrameCannotBlessALaterWrongThemeAndAStableSwappedThemeTimesOut() {
        for (briefMatch in listOf(false, true)) {
            var clock = 0L
            var attempts = 0
            val requested = 0x121316
            assertThrows(IllegalStateException::class.java) {
                UxEvidence.requireStableTheme("dark", 40L, { clock }) {
                    clock += 10
                    attempts++
                    UxPixels(20, 220.0, 0xFAF9FD, rootSurfaceRgb = requested,
                        rootSurfaceMatches = if (briefMatch && attempts == 1) 4_000 else 0, sampledPixels = 20_000)
                }
            }
            assertEquals("wrong-theme failure uses only the bounded existing budget", 4, attempts)
        }
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
