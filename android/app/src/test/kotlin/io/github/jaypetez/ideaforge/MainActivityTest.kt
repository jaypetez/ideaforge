package io.github.jaypetez.ideaforge

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    // The library's FAB is also labelled "New idea"; the bar's items are found inside the bar.
    private fun navItem(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(BOTTOM_BAR)))

    @Test
    fun launchesToTheLibraryWithTheBottomBar() {
        compose.onNodeWithText("IdeaForge").assertExists()
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertExists()
        for (label in listOf("Ideas", "New idea", "Settings")) navItem(label).assertExists()
    }

    @Test
    fun theBottomBarNavigatesBetweenDestinations() {
        navItem("Settings").performClick()
        compose.onNodeWithText("Hands-free").assertExists()
        navItem("New idea").performClick()
        compose.onNodeWithText("Interview").assertExists()
        navItem("Ideas").performClick()
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertExists()
    }

    // The version is read from package.json at build time, not copied; if the two ever
    // disagree the Android build has started keeping its own version.
    @Test
    fun versionNameComesFromPackageJson() {
        val pkg = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "package.json") }
            .first { it.isFile && it.readText().contains("\"name\": \"ideaforge\"") }
            .readText()
        val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg)!!.groupValues[1]
        // `check` runs this for both build types; only debug carries the suffixes.
        val suffix = if (BuildConfig.DEBUG) "-debug" else ""
        assertEquals("$version$suffix", BuildConfig.VERSION_NAME)
        val (major, minor, patch) = version.split('.').map(String::toInt)
        assertEquals(major * 10_000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
        assertEquals(BuildConfig.DEBUG, BuildConfig.APPLICATION_ID.endsWith(".debug"))
    }
}
