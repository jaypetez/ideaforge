package io.github.jaypetez.ideaforge.ui.reporting

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import io.github.jaypetez.ideaforge.core.MAX_REPORT_NOTE
import io.github.jaypetez.ideaforge.core.askQuestion
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.reportableOutput
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp", application = Application::class)
class ContentReportDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun draft() = ContentReportState(id = "r_form", sessionId = "s_form", output = reportableOutput(
        askQuestion(createSession("s_form"), str("Neutral generated marker?"), str("outcome")), "question", "t1"))

    @Test fun theCategoryIsExplicitTheNoteIsOptionalAndOverlongNotesCannotBeSaved() {
        val state = mutableStateOf(draft())
        var saves = 0
        compose.setContent {
            IdeaForgeTheme {
                ContentReportForm(state.value, category = { state.value = state.value.copy(category = it) },
                    note = { state.value = state.value.copy(note = it) }, save = { saves++ }, dismiss = {}, replace = {},
                    recipient = "")
            }
        }
        compose.onNodeWithTag("report-save").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        compose.onNodeWithTag("report-category-harmful").performScrollTo().performClick()
        compose.onNodeWithTag("report-save").assertIsEnabled()
        compose.onNodeWithTag("report-save").performClick()
        assertEquals("an empty optional note is valid", 1, saves)
        compose.onNodeWithTag("report-note").performScrollTo().performTextReplacement("x".repeat(MAX_REPORT_NOTE + 1))
        compose.onNodeWithTag("report-save").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        assertEquals("editing alone never saves", 1, saves)
    }

    @Test fun enlargedFailureFeedbackAndActualRadioTargetsStayReachable() {
        val state = draft().copy(category = "other", note = "Neutral retained note.",
            failure = ReportFailure.Save, detail = "Neutral controlled storage failure.")
        var density = 1f
        compose.setContent {
            val actual = LocalDensity.current
            density = actual.density
            CompositionLocalProvider(LocalDensity provides Density(actual.density, 2f)) {
                IdeaForgeTheme {
                    ContentReportForm(state, {}, {}, {}, {}, {}, recipient = "")
                }
            }
        }
        compose.onNodeWithTag("report-error").performScrollTo().assertIsDisplayed()
            .assertTextContains("Your category and note are still here", substring = true)
        compose.onNodeWithTag("report-category-other").performScrollTo().assertIsDisplayed()
        val touch = compose.onNodeWithTag("report-category-other").fetchSemanticsNode().touchBoundsInRoot
        assertTrue("the radio row has a real 48dp interactive target", touch.width / density >= 48f && touch.height / density >= 48f)
        compose.onNodeWithTag("report-note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("report-cancel").assertIsDisplayed()
    }

    @Test fun anUnconfirmedContactOffersLocalExportRatherThanAnInventedRecipient() {
        compose.setContent {
            IdeaForgeTheme { ContentReportForm(draft().copy(saved = true), {}, {}, {}, {}, {}, recipient = "") }
        }
        compose.onNodeWithText("Optional developer email is unavailable", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-email").assertDoesNotExist()
        compose.onNodeWithTag("report-done").assertIsDisplayed()
    }
}
