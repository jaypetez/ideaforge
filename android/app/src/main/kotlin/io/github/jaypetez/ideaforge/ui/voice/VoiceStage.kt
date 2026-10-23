package io.github.jaypetez.ideaforge.ui.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.Session
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton as TextButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTonalButton as FilledTonalButton
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeShape
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeWidths
import io.github.jaypetez.ideaforge.ui.theme.readableColor
import io.github.jaypetez.ideaforge.ui.reporting.ContentReportAction
import kotlinx.serialization.json.JsonObject
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.RMS_FLOOR_DB
import io.github.jaypetez.ideaforge.voice.SpeechBackend
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
    ideaTitle: String = "",
    microphoneDenied: Boolean = false,
    notificationsOff: Boolean = false,
    questionOutput: JsonObject? = null,
    suggestionsOutput: JsonObject? = null,
    onReport: (JsonObject) -> Unit = {},
) {
    var help by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().imePadding().testTag("voice-stage")) {
        Column(
            Modifier.weight(1f).fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = IdeaForgeWidths.reading).testTag("voice-reading").verticalScroll(rememberScrollState())
                .padding(horizontal = IdeaForgeSpacing.section, vertical = IdeaForgeSpacing.content),
            verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.content),
        ) {
            Text(
                if (ideaTitle.isBlank()) stringResource(R.string.drive_notification_title) else ideaTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("voice-context"),
            )
            Text(
                phaseLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.readableColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("voice-phase").semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(when (state.lastSpeech?.backend) {
                    SpeechBackend.NATIVE -> R.string.voice_backend_native
                    SpeechBackend.HOSTED -> R.string.voice_backend_hosted
                    null -> R.string.voice_backend_unreported
                }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("voice-backend"),
            )
            if (state.question.isNotEmpty()) {
                Text(stringResource(R.string.voice_current_question), style = MaterialTheme.typography.labelLarge)
                ContentReportAction(questionOutput, "report-voice-question", onReport)
                Text(state.question, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("voice-question").semantics { heading() })
            }
            ContentReportAction(suggestionsOutput, "report-voice-suggestions", onReport)
            if (state.phase == DrivePhase.SPEAKING && state.mode == VoiceMode.ACTIVE &&
                state.said.isNotEmpty() && state.said != state.question) {
                Text(stringResource(R.string.voice_reading_aloud), style = MaterialTheme.typography.labelLarge)
                Text(state.said, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("voice-spoken-caption"))
            }
            if (state.caption.isNotEmpty()) {
                Text(
                    stringResource(if (state.listening) R.string.voice_live_answer else R.string.voice_your_words),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("voice-caption-label"),
                )
                Text(state.caption, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("voice-caption").semantics {
                        if (!state.listening) liveRegion = LiveRegionMode.Polite
                    })
            }
            if (state.listening && !state.transcribing) {
                Text(
                    if (state.confirm) stringResource(R.string.voice_confirm_hint) else stringResource(R.string.voice_listening_hint, state.trigger),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LevelBars(state.levels)
            }
            if (state.deaf && state.mode == VoiceMode.ACTIVE) {
                IdeaForgeStatusText(
                    stringResource(R.string.voice_deaf),
                    tone = StatusTone.Warning,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("voice-deaf"),
                )
            }
            if (state.detail.isNotEmpty()) {
                IdeaForgeStatusText(
                    state.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    tone = if (state.mode == VoiceMode.BLOCKED) StatusTone.Error else StatusTone.Neutral,
                    modifier = Modifier.testTag("voice-detail"),
                )
            }
            state.lastSpeech?.fallback?.takeIf { it != state.detail }?.let {
                IdeaForgeStatusText(it, tone = StatusTone.Warning, modifier = Modifier.testTag("voice-fallback"))
            }
            if (microphoneDenied) MicrophoneSettingsAction()
            if (notificationsOff) IdeaForgeStatusText(
                stringResource(R.string.voice_notifications_denied),
                modifier = Modifier.testTag("voice-notifications-off"),
            )
            TextButton(onClick = { help = !help }, modifier = Modifier.testTag("voice-help-toggle")) {
                Text(stringResource(if (help) R.string.voice_help_hide else R.string.voice_help_show))
            }
            if (help) Text(
                stringResource(R.string.voice_commands, state.trigger),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("voice-help"),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = IdeaForgeWidths.reading)
                        .navigationBarsPadding().padding(IdeaForgeSpacing.content),
                    horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
                ) {
                    val active = state.mode == VoiceMode.ACTIVE
                    FilledTonalButton(
                        onClick = if (active) onPause else onResume,
                        voice = true,
                        modifier = Modifier.weight(1f).testTag(if (active) "voice-pause" else "voice-resume"),
                    ) { Text(stringResource(if (active) R.string.voice_pause else R.string.voice_resume), style = MaterialTheme.typography.titleMedium) }
                    FilledTonalButton(
                        onClick = onExit,
                        voice = true,
                        modifier = Modifier.weight(1f).testTag("voice-exit"),
                    ) { Text(stringResource(R.string.voice_exit), style = MaterialTheme.typography.titleMedium) }
                }
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
        state.transcribing -> R.string.voice_phase_transcribing
        state.listening || state.phase == DrivePhase.LISTENING -> R.string.voice_phase_listening
        state.phase == DrivePhase.SPEAKING -> R.string.voice_phase_speaking
        state.phase == DrivePhase.PROCESSING -> R.string.voice_phase_thinking
        state.phase == DrivePhase.RECOVERING -> R.string.voice_phase_recovering
        else -> R.string.voice_phase_starting
    },
)

@Composable
private fun MicrophoneSettingsAction() {
    val context = LocalContext.current
    val feedback = remember { BringIntoViewRequester() }
    var failedOpens by remember { mutableIntStateOf(0) }
    LaunchedEffect(failedOpens) { if (failedOpens > 0) feedback.bringIntoView() }
    TextButton(onClick = {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            failedOpens = 0
        } catch (_: ActivityNotFoundException) {
            failedOpens++
        } catch (_: SecurityException) {
            failedOpens++
        }
    }, modifier = Modifier.testTag("voice-mic-settings")) {
        Text(stringResource(R.string.voice_mic_settings))
    }
    if (failedOpens > 0) IdeaForgeStatusText(
        stringResource(R.string.voice_mic_settings_failed),
        modifier = Modifier.testTag("voice-mic-settings-failed").bringIntoViewRequester(feedback),
        tone = StatusTone.Error,
    )
}

@Composable
internal fun VoicePermissionDialog(notificationsNeeded: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_mic_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.content)) {
                Text(stringResource(R.string.voice_mic_rationale), modifier = Modifier.testTag("voice-mic-rationale"))
                if (notificationsNeeded) {
                    Text(stringResource(R.string.voice_notifications_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.voice_notifications_rationale), modifier = Modifier.testTag("voice-notification-rationale"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, modifier = Modifier.testTag("voice-mic-continue")) {
                Text(stringResource(R.string.voice_mic_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_mic_cancel)) }
        },
    )
}

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
        Modifier.height(IdeaForgeSpacing.voice).fillMaxWidth().testTag("voice-levels").semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (db in levels) {
            Box(Modifier.width(6.dp).height(IdeaForgeSpacing.voice * barFraction(db)).background(color, IdeaForgeShape.status))
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
