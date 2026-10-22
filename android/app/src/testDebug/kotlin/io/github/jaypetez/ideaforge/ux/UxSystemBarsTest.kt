package io.github.jaypetez.ideaforge.ux

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UxSystemBarsTest {
    private fun original(name: String): Bitmap = checkNotNull(javaClass.getResourceAsStream("/ux-system-bars/$name.png")).use {
        checkNotNull(BitmapFactory.decodeStream(it))
    }

    private fun pixels(bitmap: Bitmap, dark: Boolean): UxPixels {
        val background = if (dark) 0x121316 else 0xFAF9FD
        return UxEvidence.requireRendered(bitmap, UxBounds(0f, 36f, 960f, 528f), background).copy(
            statusBar = UxEvidence.statusBarPixels(bitmap, UxBounds(0f, 0f, 960f, 36f), background, darkInk = !dark),
        )
    }

    @Test
    fun theOriginalLowContrastSystemUiFailsDespiteTwoMatchingAppRootFrames() {
        val bitmap = original("stale-light")
        try {
            val frame = pixels(bitmap, dark = false)
            UxEvidence.requireTheme("light", frame)
            assertTrue("the actual observed symptom has no dark status glyph cores", frame.statusBar!!.statusInkPixels == 0)
            val error = assertThrows(IllegalStateException::class.java) { UxEvidence.requireStatusBar(frame.statusBar) }
            assertTrue(error.message.orEmpty().contains("glyph paint"))
            var clock = 0L
            assertThrows(IllegalStateException::class.java) {
                UxEvidence.requireStableTheme("light", 40L, { clock }) { clock += 10; frame }
            }
            assertEquals("repeated good app roots never bless stale SystemUI", 40L, clock)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun originalRenderedLightAndDarkGlyphsPassButTheOppositeRequestedInkDoesNot() {
        for (dark in listOf(false, true)) {
            val bitmap = original(if (dark) "settled-dark" else "settled-light")
            try {
                val frame = pixels(bitmap, dark)
                UxEvidence.requireTheme(if (dark) "dark" else "light", frame)
                UxEvidence.requireStatusBar(frame.statusBar!!)
                val opposite = UxEvidence.statusBarPixels(bitmap, UxBounds(0f, 0f, 960f, 36f),
                    frame.rootSurfaceRgb!!, darkInk = dark)
                assertThrows(IllegalStateException::class.java) { UxEvidence.requireStatusBar(opposite) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun aStaleSystemUiFrameResetsConsecutiveProofRatherThanAddingADelay() {
        val stale = original("stale-light")
        val settled = original("settled-light")
        try {
            val bad = pixels(stale, dark = false)
            val good = pixels(settled, dark = false)
            val frames = listOf(bad, good, bad, good, good)
            var clock = 0L
            var attempts = 0
            val result = UxEvidence.requireStableTheme("light", 100L, { clock }) {
                clock += 10
                frames[attempts++]
            }
            assertSame(good, result)
            assertEquals("both root and actual glyph paint must agree in consecutive samples", frames.size, attempts)
        } finally {
            stale.recycle()
            settled.recycle()
        }
    }
}
