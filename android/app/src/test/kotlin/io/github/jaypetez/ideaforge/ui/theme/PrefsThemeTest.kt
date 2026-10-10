package io.github.jaypetez.ideaforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.jaypetez.ideaforge.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stored theme reaches MaterialTheme: what the app's content actually renders with, not
 * only what was saved. Robolectric's default configuration is not night mode, so "system"
 * renders light here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theStoredThemeDecidesTheSchemeAndFollowsChanges() {
        val prefs = MutableStateFlow(Prefs(theme = Prefs.THEME_LIGHT))
        var background: Color? = null
        val reported = mutableListOf<Boolean>()
        compose.setContent {
            PrefsTheme(prefs, onDark = { reported += it }) { background = MaterialTheme.colorScheme.background }
        }
        compose.waitForIdle()
        assertTrue("light renders a light background", background!!.luminance() > 0.5f)

        prefs.value = Prefs(theme = Prefs.THEME_DARK)
        compose.waitForIdle()
        assertTrue("dark renders a dark background", background!!.luminance() < 0.5f)

        prefs.value = Prefs(theme = Prefs.THEME_SYSTEM)
        compose.waitForIdle()
        assertTrue("system follows the (light) configuration", background!!.luminance() > 0.5f)
        assertEquals("the system bars were told each time", listOf(false, true, false), reported)
    }

    @Test
    fun nothingRendersBeforeThePreferencesAreRead() {
        val prefs = MutableStateFlow<Prefs?>(null)
        var rendered = false
        compose.setContent {
            PrefsTheme(kotlinx.coroutines.flow.flow { prefs.collect { if (it != null) emit(it) } }) { rendered = true }
        }
        compose.waitForIdle()
        assertFalse("no frame in the wrong theme", rendered)
        prefs.value = Prefs(theme = Prefs.THEME_DARK)
        compose.waitForIdle()
        assertTrue(rendered)
    }

    @Test
    fun isDarkTheme() {
        assertTrue(isDarkTheme(Prefs.THEME_DARK, systemDark = false))
        assertFalse(isDarkTheme(Prefs.THEME_LIGHT, systemDark = true))
        assertTrue(isDarkTheme(Prefs.THEME_SYSTEM, systemDark = true))
        assertFalse(isDarkTheme(Prefs.THEME_SYSTEM, systemDark = false))
    }
}
