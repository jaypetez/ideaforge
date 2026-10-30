package io.github.jaypetez.ideaforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeTextButton
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.voice.DeviceVoice

@Composable
internal fun DeviceVoiceSettings(state: SettingsState, vm: SettingsViewModel) {
    val prefs = state.prefs ?: return
    var picking by remember { mutableStateOf(false) }
    val selected = state.deviceVoices.choices.firstOrNull { it.id == prefs.speechVoice }
    val label = if (prefs.speechVoice.isEmpty()) stringResource(R.string.device_voice_default)
        else selected?.let { voiceLabel(it) } ?: prefs.speechVoice
    Text(stringResource(R.string.device_voice_title), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.device_voice_hint), style = MaterialTheme.typography.bodySmall)
    IdeaForgeOutlinedButton(
        onClick = { picking = true }, enabled = !state.voiceSaving,
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.DEVICE_VOICE),
    ) {
        Text(stringResource(R.string.device_voice_choose, label))
    }
    if (state.voiceSaving) {
        IdeaForgeStatusText(stringResource(R.string.device_voice_saving),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.voiceSaveProblem?.let {
        IdeaForgeStatusText(it, Modifier.testTag(SettingsTags.DEVICE_VOICE_SAVE_PROBLEM)
            .semantics { liveRegion = LiveRegionMode.Polite }, tone = StatusTone.Error)
    }
    if (!picking) VoiceCatalogueStatus(state, vm)
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.device_voice_title)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related)) {
                    VoiceCatalogueStatus(state, vm)
                    Column(Modifier.selectableGroup()) {
                        VoiceChoice(stringResource(R.string.device_voice_default), null, prefs.speechVoice.isEmpty(),
                            SettingsTags.DEVICE_VOICE_DEFAULT) {
                            vm.setSpeechVoice("")
                            picking = false
                        }
                        if (state.deviceVoices.phase == DeviceVoicePhase.READY) {
                            for (voice in state.deviceVoices.choices) {
                                VoiceChoice(voiceLabel(voice), voiceMetadata(voice), prefs.speechVoice == voice.id,
                                    SettingsTags.deviceVoice(voice.id)) {
                                    vm.setSpeechVoice(voice.id)
                                    picking = false
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                IdeaForgeTextButton(onClick = { picking = false },
                    modifier = Modifier.testTag(SettingsTags.DEVICE_VOICE_CLOSE)) {
                    Text(stringResource(R.string.device_voice_close))
                }
            },
        )
    }
}

@Composable
private fun VoiceCatalogueStatus(state: SettingsState, vm: SettingsViewModel) {
    val catalogue = state.deviceVoices
    val text = when (catalogue.phase) {
        DeviceVoicePhase.LOADING -> stringResource(R.string.device_voices_loading)
        DeviceVoicePhase.FAILED -> listOfNotNull(catalogue.problem, stringResource(R.string.device_voices_error)).joinToString(" ")
        DeviceVoicePhase.READY -> if (catalogue.choices.isEmpty()) stringResource(R.string.device_voices_empty) else null
    }
    text?.let {
        IdeaForgeStatusText(it, Modifier.testTag(SettingsTags.DEVICE_VOICE_STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
            tone = if (catalogue.phase == DeviceVoicePhase.FAILED) StatusTone.Error else StatusTone.Neutral)
    }
    val saved = state.prefs?.speechVoice.orEmpty()
    if (catalogue.phase == DeviceVoicePhase.READY && saved.isNotEmpty() && catalogue.choices.none { it.id == saved }) {
        IdeaForgeStatusText(stringResource(R.string.device_voice_missing, saved),
            Modifier.testTag(SettingsTags.DEVICE_VOICE_MISSING).semantics { liveRegion = LiveRegionMode.Polite },
            tone = StatusTone.Warning)
    }
    IdeaForgeTextButton(onClick = { vm.refreshDeviceVoices() }, enabled = catalogue.phase != DeviceVoicePhase.LOADING,
        modifier = Modifier.testTag(SettingsTags.DEVICE_VOICE_RELOAD)) {
        Text(stringResource(R.string.device_voices_reload))
    }
}

@Composable
private fun VoiceChoice(label: String, metadata: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = IdeaForgeSpacing.control)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = IdeaForgeSpacing.tight).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.related),
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            metadata?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun voiceLabel(voice: DeviceVoice): String =
    listOf(voice.id, voice.locale).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun voiceMetadata(voice: DeviceVoice): String = stringResource(when (voice.networkRequired) {
    true -> R.string.device_voice_network
    false -> R.string.device_voice_local
    null -> R.string.device_voice_metadata_unknown
})
