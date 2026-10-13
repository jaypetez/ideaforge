package io.github.jaypetez.ideaforge.ux

import android.app.Application
import android.content.Intent
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h818dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UxRootContextTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theUnmodifiedIsolatedScaffoldPaintIsTheRequestedFactoryBackgroundFromItsActualContext() {
        val target = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            val intent = Intent(target, UxFixtureActivity::class.java)
                .putExtra("theme", if (dark) "dark" else "light").putExtra("state", "voice-listening")
            ActivityScenario.launch<UxFixtureActivity>(intent).use { scenario ->
                compose.waitUntilAtLeastOneExists(hasTestTag("ux-isolated-label"), 5_000)
                var expected = 0
                var opposite = 0
                var activityExpected = 0
                scenario.onActivity { activity ->
                    val context = checkNotNull(activity.rootRenderingContext) { "reference context comes from actual composition" }
                    expected = (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).background.toArgb()
                    opposite = (if (dark) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)).background.toArgb()
                    activityExpected = (if (dark) dynamicDarkColorScheme(activity) else dynamicLightColorScheme(activity)).background.toArgb()
                    println("dark=$dark activity=${activity.javaClass.name} context=${context.javaClass.name} sameContext=${context === activity} sameResources=${context.resources === activity.resources} activityBg=${activityExpected.toUInt().toString(16)} contextBg=${expected.toUInt().toString(16)} oppositeBg=${opposite.toUInt().toString(16)}")
                }
                compose.waitForIdle()
                val image = compose.onRoot().captureToImage().toPixelMap()
                val actual = image[1, image.height / 2].toArgb()
                println("dark=$dark nativeRootPixel=${actual.toUInt().toString(16)}")
                assertEquals("the native Scaffold consumes the requested background without an override", expected, actual)
                assertNotEquals("the opposite requested theme must not pass", opposite, actual)
            }
        }
    }

    @Test
    fun anActualDarkFixtureCannotSelfReportAColourThatPassesALightRequest() {
        val target = RuntimeEnvironment.getApplication()
        val intent = Intent(target, UxFixtureActivity::class.java)
            .putExtra("theme", "dark").putExtra("state", "voice-listening")
        ActivityScenario.launch<UxFixtureActivity>(intent).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasTestTag("ux-isolated-label"), 5_000)
            var requestedLight = 0
            scenario.onActivity { activity ->
                requestedLight = dynamicLightColorScheme(checkNotNull(activity.rootRenderingContext)).background.toArgb()
            }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val pixels = UxEvidence.requireRendered(bitmap,
                UxBounds(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), requestedLight)
            var time = 0L
            assertThrows(IllegalStateException::class.java) {
                UxEvidence.requireStableTheme("light", 40L, { time }) {
                    time += 10
                    pixels
                }
            }
            assertEquals("observing a context never turns rendered wrong-theme colour into the oracle", 40L, time)
        }
    }
}
