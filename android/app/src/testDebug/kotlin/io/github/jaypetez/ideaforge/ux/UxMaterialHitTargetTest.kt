package io.github.jaypetez.ideaforge.ux

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UxMaterialHitTargetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nativeSwitchAndIconButtonActuallyAcceptTouchesBeyondTheirPaintedBounds() {
        val checked = mutableStateOf(false)
        var clicked = 0
        compose.setContent {
            IdeaForgeTheme(dynamicColor = false) {
                Surface {
                    Column(Modifier.padding(24.dp)) {
                        Switch(checked.value, { checked.value = it }, Modifier.testTag("switch"))
                        IconButton({ clicked++ }, Modifier.testTag("icon")) { Text("Back") }
                    }
                }
            }
        }
        for (tag in listOf("switch", "icon")) {
            val interaction = compose.onNodeWithTag(tag)
            val node = interaction.fetchSemanticsNode()
            val density = node.layoutInfo.density.density
            val paint = node.boundsInRoot
            val touch = node.touchBoundsInRoot
            println("$tag paint=$paint touch=$touch density=$density")
            assertTrue("native touch layout is at least 48dp wide", touch.width / density >= 48f)
            assertTrue("native touch layout is at least 48dp high", touch.height / density >= 48f)
            assertTrue("this regression exercises expansion beyond paint", touch.height > paint.height)
            assertThrows(IllegalStateException::class.java) {
                UxEvidence.requireTarget(tag, paint.width / density, paint.height / density)
            }
            val measured = UxEvidence.touchBounds(node)
            UxEvidence.requireTarget(tag, measured.width / density, measured.height / density)
            val fringe = (touch.top + paint.top) / 2 - paint.top
            interaction.performTouchInput { click(Offset(paint.width / 2, fringe)) }
        }
        compose.onNodeWithTag("switch").assertIsOn()
        assertEquals("the icon's expanded target invokes its real callback", 1, clicked)
    }
}
