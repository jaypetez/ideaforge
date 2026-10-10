package io.github.jaypetez.ideaforge.ui.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.voice.VoiceMode
import io.github.jaypetez.ideaforge.voice.VoiceStageState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceStageTest {
    @get:Rule
    val compose = createComposeRule()

    private val listening = VoiceStageState(
        sessionId = "s", mode = VoiceMode.ACTIVE, phase = DrivePhase.LISTENING,
        question = "Who is it for?", listening = true,
    )

    private fun show(state: VoiceStageState, onPause: () -> Unit = {}, onResume: () -> Unit = {}, onExit: () -> Unit = {}) =
        compose.setContent { IdeaForgeTheme { VoiceStage(state, onPause, onResume, onExit) } }

    @Test
    fun withNoLevelReadingThereAreNoBarsAndTheStageSaysSo() {
        show(listening)
        compose.onNodeWithTag("voice-no-level").assertIsDisplayed()
        compose.onNodeWithTag("voice-levels").assertDoesNotExist()
        compose.onNodeWithTag("voice-phase").assertTextContains("Listening")
        compose.onNodeWithTag("voice-question").assertTextContains("Who is it for?")
    }

    @Test
    fun barsAreDrawnFromTheReadingsAndOnlyThem() {
        show(listening.copy(levels = listOf(-2f, 4f, 9f), caption = "for my sister"))
        compose.onNodeWithTag("voice-levels").assert(hasContentDescription("Microphone level, 3 readings"))
        compose.onNodeWithTag("voice-no-level").assertDoesNotExist()
        compose.onNodeWithTag("voice-caption").assertTextContains("for my sister")
    }

    @Test
    fun deafnessIsAClearMessage() {
        show(listening.copy(deaf = true))
        compose.onNodeWithTag("voice-deaf").assertTextContains("No sound is reaching the recogniser", substring = true)
    }

    @Test
    fun pauseAndExitAreTheStagesButtonsAndPausedOffersResume() {
        var paused = 0
        var exited = 0
        show(listening, onPause = { paused++ }, onExit = { exited++ })
        compose.onNodeWithTag("voice-pause").performClick()
        compose.onNodeWithTag("voice-exit").performClick()
        assertEquals(1, paused)
        assertEquals(1, exited)
    }

    @Test
    fun aStoodDownStageShowsWhyAndOffersResume() {
        var resumed = 0
        show(
            VoiceStageState(sessionId = "s", mode = VoiceMode.BLOCKED, detail = "Microphone access was denied, so voice stood down."),
            onResume = { resumed++ },
        )
        compose.onNodeWithTag("voice-phase").assertTextContains("Voice stopped")
        compose.onNodeWithTag("voice-detail").assertTextContains("denied", substring = true)
        compose.onNodeWithTag("voice-no-level").assertDoesNotExist()
        compose.onNodeWithTag("voice-resume").performClick()
        assertEquals(1, resumed)
    }
}
