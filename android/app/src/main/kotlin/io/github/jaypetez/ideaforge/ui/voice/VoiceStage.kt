package io.github.jaypetez.ideaforge.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.RMS_FLOOR_DB
import io.github.jaypetez.ideaforge.voice.VoiceMode
import io.github.jaypetez.ideaforge.voice.VoiceStageState

/**
 * The voice stage: the Android twin of createVoiceStage (src/ui/voice-stage.js). Presentation
 * only. It has no timers and no animation of its own: the bars are this capture's level
 * readings and nothing else, and when no reading has arrived it says so rather than drawing
 * a pulse that would look like a working microphone.
 */
@Composable
fun VoiceStage(
    state: VoiceStageState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().testTag("voice-stage")) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                phaseLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("voice-phase"),
            )
            if (state.question.isNotEmpty()) {
                Text(state.question, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("voice-question"))
            }
            val caption = if (state.listening) state.caption else state.said
            if (caption.isNotEmpty()) {
                Text(caption, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("voice-caption"))
            }
            if (state.listening) {
                Text(
                    if (state.confirm) stringResource(R.string.voice_confirm_hint) else stringResource(R.string.voice_listening_hint, state.trigger),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LevelBars(state.levels)
            }
            if (state.deaf && state.mode == VoiceMode.ACTIVE) {
                Text(
                    stringResource(R.string.voice_deaf),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("voice-deaf"),
                )
            }
            if (state.detail.isNotEmpty()) {
                Text(
                    state.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.mode == VoiceMode.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("voice-detail"),
                )
            }
        }
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val active = state.mode == VoiceMode.ACTIVE
                FilledTonalButton(
                    onClick = if (active) onPause else onResume,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag(if (active) "voice-pause" else "voice-resume"),
                ) { Text(stringResource(if (active) R.string.voice_pause else R.string.voice_resume), style = MaterialTheme.typography.titleMedium) }
                FilledTonalButton(
                    onClick = onExit,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("voice-exit"),
                ) { Text(stringResource(R.string.voice_exit), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun phaseLabel(state: VoiceStageState): String = stringResource(
    when {
        state.mode == VoiceMode.BLOCKED -> R.string.voice_phase_blocked
        state.mode == VoiceMode.PAUSED -> R.string.voice_phase_paused
        state.writing -> R.string.voice_phase_writing
        state.listening || state.phase == DrivePhase.LISTENING -> R.string.voice_phase_listening
        state.phase == DrivePhase.SPEAKING -> R.string.voice_phase_speaking
        state.phase == DrivePhase.PROCESSING -> R.string.voice_phase_thinking
        state.phase == DrivePhase.RECOVERING -> R.string.voice_phase_recovering
        else -> R.string.voice_phase_starting
    },
)

/** A reading's bar height, 0..1, over the range the #89 spike saw (floor -2.0, peaks near +10). */
internal fun barFraction(db: Float): Float = ((db - RMS_FLOOR_DB) / 12f).coerceIn(0.04f, 1f)

@Composable
private fun LevelBars(levels: List<Float>) {
    if (levels.isEmpty()) {
        // Reported, not fabricated: no bars at all until the recogniser sends a reading.
        Text(
            stringResource(R.string.voice_no_level),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("voice-no-level"),
        )
        return
    }
    val description = stringResource(R.string.voice_levels, levels.size)
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier.height(64.dp).fillMaxWidth().testTag("voice-levels").semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (db in levels) {
            Box(Modifier.width(6.dp).height((64 * barFraction(db)).dp).background(color, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────── permissions

/** What to ask for before a drive: the microphone, and on API 33+ the notification. */
fun voicePermissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> =
    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Whether every permission a drive wants is already held, so no rationale is needed. */
fun voicePermissionsHeld(context: Context): Boolean = voicePermissions().all { hasPermission(context, it) }

/**
 * The permission answer, applied. A denied microphone is a fatal stand-down, shown on the
 * stage with [deniedMessage], and nothing is started: no recogniser, no service. A denied
 * notification is not: the drive runs and the stage keeps its own controls.
 *
 * @return true when the drive started
 */
fun beginVoice(drive: DriveController, handOff: Pair<Session, String>?, micGranted: Boolean, deniedMessage: String): Boolean {
    val (session, source) = handOff ?: return false
    if (!micGranted) {
        drive.standDown(session, source, deniedMessage)
        return false
    }
    drive.start(session, source)
    return true
}
