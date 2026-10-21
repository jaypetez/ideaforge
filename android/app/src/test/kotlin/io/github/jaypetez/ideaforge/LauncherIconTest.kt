package io.github.jaypetez.ideaforge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private fun installedIcon(): AdaptiveIconDrawable {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        assertTrue("the installed launcher icon must be adaptive, including at minSdk", icon is AdaptiveIconDrawable)
        return icon as AdaptiveIconDrawable
    }

    @Test
    fun theInstalledIconHasAnOpaqueFullBleedBackgroundAndAMaskableMark() {
        val icon = installedIcon()
        val background = pixels(icon.background)
        assertTrue("adaptive background must cover the whole layer", background.all { Color.alpha(it) == 255 })
        val mark = pixels(icon.foreground)
        val ink = mark.indices.filter { Color.alpha(mark[it]) > 0 }
        assertTrue("the foreground must be a visible mark, not a blank or solid tile", ink.size in 1000..15000)
        val radius = 216f * 33f / 108f
        assertTrue("all anvil ink must fit the central 66dp safe circle", ink.all {
            val x = it % 216 + 0.5f - 108f
            val y = it / 216 + 0.5f - 108f
            x * x + y * y <= radius * radius
        })
    }

    @Test
    fun themedIconsUseOnlyTheSameAnvilSilhouetteNotItsBackgroundTile() {
        val icon = installedIcon()
        if (Build.VERSION.SDK_INT >= 33) {
            val monochrome = checkNotNull(icon.monochrome) { "themed launchers need a monochrome layer" }
            assertArrayEquals("the themed mark must retain the complete anvil without its background",
                pixels(icon.foreground), pixels(monochrome))
        }
    }

    private fun pixels(drawable: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
