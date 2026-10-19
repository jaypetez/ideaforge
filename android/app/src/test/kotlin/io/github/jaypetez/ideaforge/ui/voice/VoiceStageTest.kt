package io.github.jaypetez.ideaforge.ui.voice

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.provider.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.voice.VoiceMode
import io.github.jaypetez.ideaforge.voice.VoiceStageState
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.SpeechBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceStageTest {
    @get:Rule
    val compose = createComposeRule()

    private val listening = VoiceStageState(
        sessionId = "s", mode = VoiceMode.ACTIVE, phase = DrivePhase.LISTENING,
        question = "Who is it for?", listening = true,
    )

    private fun show(state: VoiceStageState, onPause: () -> Unit = {}, onResume: () -> Unit = {}, onExit: () -> Unit = {}) =
        compose.setContent { IdeaForgeTheme { Surface { VoiceStage(state, onPause, onResume, onExit) } } }

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

    @Test
    fun aPausedStageShowsTheRetainedAnswerRatherThanDiscardedSpeechOutput() {
        show(listening.copy(mode = VoiceMode.PAUSED, listening = false, caption = "My captured answer stays here."))
        compose.onNodeWithTag("voice-caption").assertTextContains("My captured answer stays here.")
        compose.onNodeWithTag("voice-caption-label").assertTextContains("Your words")
        compose.onNodeWithTag("voice-levels").assertDoesNotExist()
    }

    @Test
    fun theStageNamesItsContextWithoutPretendingAnUnreportedBackendSpoke() {
        show(listening)
        compose.onNodeWithTag("voice-context").assertTextContains("Voice interview")
        compose.onNodeWithTag("voice-backend").assertTextContains("Speech output not reported yet")
    }

    @Test
    fun pausedUsersCanReadTheFinishWordAndWholeUtteranceCommands() {
        show(listening.copy(mode = VoiceMode.PAUSED, listening = false, trigger = "all done"))
        compose.onNodeWithTag("voice-help-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("voice-help").performScrollTo()
            .assertTextContains("all done", substring = true)
            .assertTextContains("whole utterance", substring = true)
            .assertTextContains("exit voice", substring = true)
        compose.onNodeWithTag("voice-resume").assertIsDisplayed()
        compose.onNodeWithTag("voice-exit").assertIsDisplayed()
    }

    @Test
    fun backendAndTranscribingLabelsDescribeReportedWorkAndDoNotAnimateSamples() {
        val state = mutableStateOf(listening.copy(lastSpeech = SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.HOSTED)))
        compose.setContent { IdeaForgeTheme { Surface {
            VoiceStage(state.value, {}, {}, {}, ideaTitle = "Remember names")
        } } }
        compose.onNodeWithTag("voice-context").assertTextContains("Remember names")
        compose.onNodeWithTag("voice-backend").assertTextContains("Last spoken: OpenAI speech")
        compose.onNodeWithTag("voice-phase").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle {
            state.value = state.value.copy(caption = "The first recorded segment", transcribing = true,
                lastSpeech = SpeakResult(SpeakStatus.SPOKEN, fallback = "Scripted fallback to device voice."))
        }
        compose.onNodeWithTag("voice-phase").assertTextContains("Transcribing recorded audio")
        compose.onNodeWithTag("voice-backend").assertTextContains("Last spoken: device voice")
        compose.onNodeWithTag("voice-fallback").performScrollTo().assertTextContains("Scripted fallback", substring = true)
        compose.onNodeWithTag("voice-caption").assertTextContains("The first recorded segment")
        compose.onNodeWithTag("voice-levels").assertDoesNotExist()
        assertNull("live partials are readable without repeated automatic announcements",
            compose.onNodeWithTag("voice-caption").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun microphoneRecoveryOpensOnlyThisAppsSettingsOnADeliberateTap() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent { IdeaForgeTheme { Surface {
            VoiceStage(listening.copy(mode = VoiceMode.BLOCKED, listening = false), {}, {}, {},
                microphoneDenied = true, notificationsOff = true)
        } } }
        assertNull(shadowOf(context).nextStartedActivity)
        compose.onNodeWithTag("voice-mic-settings").performScrollTo().performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        compose.onNodeWithTag("voice-notifications-off").performScrollTo()
            .assertTextContains("controls stay on this screen only", substring = true)
        compose.onNodeWithTag("voice-resume").assertIsDisplayed()
    }

    @Test
    fun missingSettingsHandlerShowsRecoveryInsteadOfPretendingSettingsOpened() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("controlled unavailable Settings") }
        }
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { IdeaForgeTheme { Surface {
            VoiceStage(listening.copy(mode = VoiceMode.BLOCKED, listening = false), {}, {}, {}, microphoneDenied = true)
        } } } }
        compose.onNodeWithTag("voice-mic-settings").performScrollTo().performClick()
        compose.onNodeWithTag("voice-mic-settings-failed").assertIsDisplayed().assertTextContains("could not be opened", substring = true)
        compose.onNodeWithTag("voice-resume").assertIsDisplayed()
        compose.onNodeWithTag("voice-exit").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp-land", fontScale = 2f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun enlargedLandscapeKeepsBothActionsAndTheEndOfTheFullCaption() {
        val caption = "A full answer that remains readable without truncation. ".repeat(20) + "The final words."
        show(listening.copy(mode = VoiceMode.PAUSED, listening = false, caption = caption))
        compose.onNodeWithTag("voice-caption").performScrollTo().assertTextContains("The final words.", substring = true)
        val text = compose.onNodeWithTag("voice-caption").fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.runOnUiThread {
            text.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        }
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) {
            assertTrue(!layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertTrue(!layout.isLineEllipsized(line))
                assertTrue("line $line: painted ${layout.getLineRight(line) - layout.getLineLeft(line)}px, layout ${layout.size.width}px",
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                assertTrue("line $line: bottom ${layout.getLineBottom(line)}px, layout ${layout.size.height}px",
                    layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
        for (tag in listOf("voice-resume", "voice-exit"))
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun thePermissionExplanationKeepsNotificationsExplicitlyOptionalAndSeparate() {
        var starts = 0
        compose.setContent { IdeaForgeTheme {
            VoicePermissionDialog(true, onContinue = { starts++ }, onDismiss = {})
        } }
        assertEquals(0, starts)
        compose.onNodeWithTag("voice-mic-rationale").assertTextContains("Microphone access is needed only", substring = true)
        compose.onNodeWithTag("voice-notification-rationale").performScrollTo()
            .assertTextContains("does not block voice", substring = true)
        compose.onNodeWithTag("voice-mic-continue").performClick()
        assertEquals(1, starts)
    }

    @Test
    fun voiceActionsUseSixtyFourDpTargetsAndNativeButtonRoles() {
        show(listening)
        for (tag in listOf("voice-pause", "voice-exit")) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
    fun enlargedFullCaptionsScrollWhileResumeAndExitRemainReachable() {
        show(listening.copy(mode = VoiceMode.PAUSED, listening = false,
            question = "Who is this for, including the people whose accessibility needs and long descriptions must remain visible?",
            caption = "A full captured answer with enough detail to require several lines on a compact screen. ".repeat(12)))
        compose.onNodeWithTag("voice-caption").performScrollTo().assertIsDisplayed()
        for (tag in listOf("voice-resume", "voice-exit")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(48.dp)
        }
    }
}
