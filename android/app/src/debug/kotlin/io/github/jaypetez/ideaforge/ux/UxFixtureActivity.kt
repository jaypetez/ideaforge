package io.github.jaypetez.ideaforge.ux

import android.graphics.Color
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.ui.result.ResultScreen
import io.github.jaypetez.ideaforge.ui.result.ResultViewModel
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeTheme
import io.github.jaypetez.ideaforge.ui.voice.VoiceStage
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.VoiceMode
import io.github.jaypetez.ideaforge.voice.VoiceStageState

/**
 * An explicitly isolated presentation fixture, not MainActivity or a simulated voice run.
 * No recogniser, speaker, service, timers or level samples are constructed by this activity.
 */
class UxFixtureActivity : ComponentActivity() {
    @Volatile
    var rootRenderingContext: Context? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getStringExtra("theme") == "dark"
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        intent.getStringExtra("foundationPalette")?.let { id ->
            val palette = UxFoundationPalette.from(id)
            setContent {
                val context = LocalContext.current
                SideEffect { rootRenderingContext = context }
                IdeaForgeTheme(dark = dark, dynamicColor = false) {
                    MaterialTheme(colorScheme = palette.scheme(dark, MaterialTheme.colorScheme)) {
                        Scaffold { padding ->
                            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { UxFoundationContent(palette) }
                        }
                    }
                }
            }
            return
        }
        val fixtures = UxFixtures.load(this)
        val state = requestedVoiceState(fixtures, requireNotNull(intent.getStringExtra("state")))
        require(state.evidence == "isolated-component") { "${state.id} is not an isolated component fixture" }
        setContent {
            val context = LocalContext.current
            SideEffect { rootRenderingContext = context }
            IdeaForgeTheme(dark = dark) {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        Text(
                            "Isolated component · ${state.id}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(8.dp).testTag("ux-isolated-label"),
                        )
                        Box(Modifier.weight(1f)) {
                            if (state.phase == "complete") {
                                // Native completion leaves the voice stage for the result;
                                // there is no native "complete" VoiceStage phase.
                                val vm: ResultViewModel = viewModel(factory = ResultViewModel.factory("ux-long-done"))
                                ResultScreen(vm, onBack = ::finish, onAskMore = {})
                            } else {
                                VoiceStage(
                                    voiceState(fixtures, state), onPause = {}, onResume = {}, onExit = ::finish,
                                    ideaTitle = sessionDisplayTitle(fixtures.datasets.getValue("long").single {
                                        stringOf(it["id"]) == "ux-long-active"
                                    }),
                                    microphoneDenied = state.phase == "blocked",
                                    notificationsOff = state.id == "voice-notifications-off",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun voiceState(fixtures: UxFixtures, state: UxState): VoiceStageState = VoiceStageState(
        sessionId = "ux-long-active",
        mode = when (state.phase) {
            "paused" -> VoiceMode.PAUSED
            "blocked" -> VoiceMode.BLOCKED
            else -> VoiceMode.ACTIVE
        },
        phase = when (state.phase) {
            "ready", "starting" -> null
            "speaking" -> DrivePhase.SPEAKING
            "listening" -> DrivePhase.LISTENING
            "transcribing", "thinking", "writing" -> DrivePhase.PROCESSING
            "recovering" -> DrivePhase.RECOVERING
            "paused", "blocked" -> null
            else -> error("unmapped requested native voice phase: ${state.phase}")
        },
        question = fixtures.text("question"),
        caption = if (state.phase in setOf("listening", "transcribing", "thinking", "recovering", "paused", "blocked"))
            fixtures.text("answer") else "",
        said = if (state.phase == "speaking") fixtures.text("question") else "",
        detail = if (state.phase == "blocked") getString(R.string.voice_mic_denied) else "",
        listening = state.phase in setOf("listening", "transcribing"),
        transcribing = state.phase == "transcribing",
        lastSpeech = if (state.id == "voice-fallback") SpeakResult(
            SpeakStatus.SPOKEN, fallback = "Scripted hosted failure. The device voice completed the last utterance.",
            requestedVoice = "ux-retained-unavailable-voice", voiceMissing = true,
            message = "Scripted selected device voice is unavailable; the engine default spoke. The saved choice is retained.",
        ) else null,
        writing = state.phase == "writing",
        levels = emptyList(),
    )

    companion object {
        fun requestedVoiceState(fixtures: UxFixtures, id: String): UxState = when (id) {
            "voice-fallback", "voice-help" -> fixtures.state("voice-paused").copy(id = id)
            "voice-notifications-off" -> fixtures.state("voice-listening").copy(id = id)
            else -> fixtures.state(id)
        }

        fun nativeMapping(state: UxState): String? = when (state.phase) {
            "ready" -> "Native has no ready stage: showing its starting presentation."
            "complete" -> "Native completion leaves the stage: showing the completed result component."
            else -> when (state.id) {
                "voice-fallback" -> "Isolated stage with a scripted successful default-speech result, retained missing-device-voice warning and hosted-failure reason; no real speech, hosted call or output quality is proved."
                "voice-notifications-off" -> "Isolated optional-notification-denial presentation, not a platform permission decision."
                "voice-help" -> "Isolated stage with its real command disclosure opened through the UI."
                else -> null
            }
        }
    }
}
