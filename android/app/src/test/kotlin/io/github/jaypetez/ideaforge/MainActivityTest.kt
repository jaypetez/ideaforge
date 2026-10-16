package io.github.jaypetez.ideaforge

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    // A returning user: with no usable provider the app opens on onboarding instead
    // (OnboardingFlowTest covers that path).
    @Before
    fun launch() {
        installTestGraph(ring = usableRing(), inferenceAgreed = true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("v${BuildConfig.VERSION_NAME}"), timeoutMillis = 10_000)
    }

    @After
    fun close() = scenario.close()

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
        // The interview opens on the hardcoded seed question: no model call, no network.
        compose.waitUntilAtLeastOneExists(hasText(SEED_QUESTION), 5_000)
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
