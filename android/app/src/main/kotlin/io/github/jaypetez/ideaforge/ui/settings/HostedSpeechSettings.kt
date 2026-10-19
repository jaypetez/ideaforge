package io.github.jaypetez.ideaforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.maskKey
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.core.withTts
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.providers.STT_PRESETS
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeButton as Button
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeField as OutlinedTextField
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeOutlinedButton as OutlinedButton
import io.github.jaypetez.ideaforge.ui.components.IdeaForgeStatusText
import io.github.jaypetez.ideaforge.ui.components.StatusTone
import io.github.jaypetez.ideaforge.ui.theme.IdeaForgeSpacing
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.SpeechBackend
import io.github.jaypetez.ideaforge.voice.checkSynth
import io.github.jaypetez.ideaforge.voice.hostedSpeechReady
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Hosted speech and the Whisper fallback in Settings: the Android twin of the web's speech
// controls (previewSpeech, readSpeechFromForm, revokeHostedSpeech in src/ui/app.js).
//
//  - OpenAI speech needs its own key (never the inference key), consent to AI-generated
//    speech, to sending the spoken text, and to metered use, and then a check that played
//    OpenAI audio on this device. Only that check writes `verified: true` (through withTts,
//    so every other keyring record is kept) and turns hostedSpeechAllowed on.
//  - The check runs on the hosted backend only (HostedSpeaker.check). A result from any other
//    backend, a fallback, or anything short of SPOKEN is not a pass.
//  - Changing the key or the voice clears `verified` at once: the check vouched for the old
//    pair. Unticking any consent or choosing the device voice withdraws hosted speech.
//  - Transcription is separate again: its own vendor, its own key, its own consent to send
//    recorded audio, used only when the device has no working recogniser.

/** The previewer: plays the check sample through OpenAI with exactly this key and voice. */
fun interface SpeechPreviewer {
    suspend fun preview(apiKey: String, voice: String, rate: Double): SpeakResult
}

data class HostedSpeechState(
    /** OpenAI speech chosen in the form (it may not be enabled yet). */
    val hosted: Boolean = false,
    val keyDraft: String = "",
    /** The saved speech key, masked; "" when there is none. */
    val savedKey: String = "",
    val voice: String = "marin",
    val consentSynthetic: Boolean = false,
    val consentText: Boolean = false,
    val consentMetered: Boolean = false,
    /** Consent given and the check passed for the saved key and voice: replies use OpenAI. */
    val enabled: Boolean = false,
    val checking: Boolean = false,
    val notice: String? = null,
    val noticeFailed: Boolean = false,
    /** "groq", "openai" or null (off). */
    val sttKind: String? = null,
    val sttKeyDraft: String = "",
    val sttSavedKey: String = "",
    val audioConsent: Boolean = false,
    val sttNotice: String? = null,
    val sttNoticeFailed: Boolean = false,
) {
    val consented: Boolean get() = consentSynthetic && consentText && consentMetered
    val canCheck: Boolean get() = hosted && consented
}

private data class SpeechLocal(
    val hosted: Boolean? = null,
    val keyDraft: String = "",
    /** null: follow `enabled` (all ticked once enabled, none before). */
    val consents: Triple<Boolean, Boolean, Boolean>? = null,
    val checking: Boolean = false,
    val notice: String? = null,
    val noticeFailed: Boolean = false,
    val sttKind: String? = null,
    val sttKindSet: Boolean = false,
    val sttKeyDraft: String = "",
    val sttNotice: String? = null,
    val sttNoticeFailed: Boolean = false,
)

class HostedSpeechViewModel(
    private val prefsStore: PrefsStore,
    private val keyring: KeyringStore,
    private val previewer: SpeechPreviewer,
    /** Hosted speech was withdrawn: stop what is speaking and pause an active drive. */
    private val onRevoke: (String) -> Unit = {},
) : ViewModel() {
    private val local = MutableStateFlow(SpeechLocal())

    /** The prefs [state] last saw; DataStore offers no synchronous read. */
    @Volatile private var seenPrefs = Prefs()

    // For the screen only. `combine` delivers asynchronously, so [state] can lag a `local`
    // update just made: actions read [now] instead. Reading [state] let three quick consent
    // taps each start from stale ticks and overwrite one another, so the check never ran.
    val state: StateFlow<HostedSpeechState> =
        combine(prefsStore.prefs, keyring.state, local) { prefs, ks, l -> seenPrefs = prefs; derive(prefs, ks, l) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HostedSpeechState())

    /** The form as it is now: local edits and the keyring are synchronous, prefs as last seen. */
    private fun now(l: SpeechLocal = local.value) = derive(seenPrefs, keyring.state.value, l)

    /** Bumped by every change that would make a check in flight vouch for the wrong thing. */
    private var epoch = 0
    private var check: Job? = null

    private fun derive(prefs: Prefs, ks: KeyringState, l: SpeechLocal): HostedSpeechState {
        val tts = ks.ring["tts"]
        val stt = ks.ring["stt"]
        val enabled = hostedSpeechReady(ks.ring, prefs)
        val consents = l.consents ?: Triple(enabled, enabled, enabled)
        return HostedSpeechState(
            hosted = l.hosted ?: (stringOf(tts["kind"]) == "openai"),
            keyDraft = l.keyDraft,
            savedKey = maskKey(stringOf(tts["apiKey"])),
            voice = if (stringOf(tts["voice"]) == "cedar") "cedar" else "marin",
            consentSynthetic = consents.first,
            consentText = consents.second,
            consentMetered = consents.third,
            enabled = enabled,
            checking = l.checking,
            notice = l.notice,
            noticeFailed = l.noticeFailed,
            sttKind = if (l.sttKindSet) l.sttKind else stringOf(stt["kind"])?.takeIf { it in STT_PRESETS },
            sttKeyDraft = l.sttKeyDraft,
            sttSavedKey = maskKey(stringOf(stt["apiKey"])),
            audioConsent = prefs.hostedTranscriptionAllowed,
            sttNotice = l.sttNotice,
            sttNoticeFailed = l.sttNoticeFailed,
        )
    }

    private fun ring(): JsonElement = keyring.state.value.ring
    private fun tts(): JsonElement? = ring()["tts"]
    private fun savedTtsKey(): String = stringOf(tts()["apiKey"]).orEmpty()

    private fun ttsRecord(kind: String, apiKey: String, voice: String, verified: Boolean) = JsonObject(
        linkedMapOf(
            "kind" to JsonPrimitive(kind), "apiKey" to JsonPrimitive(apiKey),
            "voice" to JsonPrimitive(voice), "verified" to JsonPrimitive(verified),
        ),
    )

    /** Keyring writes refuse an unreadable ring: saving would replace every other record. */
    private suspend fun saveRing(next: JsonElement, generation: Long = keyring.generation.value,
        report: (String) -> Unit = { note(it, failed = true) }): Boolean {
        if (keyring.state.value.status == KeyringState.Status.UNREADABLE) {
            report("The saved keys could not be read, so nothing was changed. Fix the connection first.")
            return false
        }
        // Refused when a Forget began after [generation] was read: writing now would put a
        // deleted key back on disk.
        if (!keyring.saveIf(generation, next)) {
            report("The keys were deleted meanwhile, so nothing was saved.")
            return false
        }
        return true
    }

    private fun note(text: String?, failed: Boolean = false) =
        local.update { it.copy(notice = text, noticeFailed = failed) }

    private fun sttNote(text: String?, failed: Boolean = false) =
        local.update { it.copy(sttNotice = text, sttNoticeFailed = failed) }

    /** Drop the check's vouching: `verified` false for the saved record, kind as given. */
    private suspend fun invalidate(kind: String, voice: String = now().voice): Boolean {
        epoch++
        check?.cancel()
        return saveRing(withTts(ring(), ttsRecord(kind, savedTtsKey(), voice, verified = false)))
    }

    fun chooseHosted(on: Boolean): Job = viewModelScope.launch {
        local.update { it.copy(hosted = on, notice = null, noticeFailed = false) }
        if (!on) revoke(kind = "browser", "Replies use the device voice.")
    }

    /** Any new key invalidates the check, at once: it vouched for the old one. */
    fun setKey(v: String): Job? {
        local.update { it.copy(keyDraft = v) }
        val typed = v.trim()
        if (typed.isEmpty() || typed == savedTtsKey() || tts()["verified"] != JsonPrimitive(true)) return null
        return viewModelScope.launch {
            if (invalidate("openai")) note("The key changed. Check and preview again to enable OpenAI speech.")
        }
    }

    fun setVoice(v: String): Job? {
        val voice = if (v == "cedar") "cedar" else "marin"
        if (voice == now().voice) return null
        return viewModelScope.launch {
            if (invalidate(if (now().hosted) "openai" else stringOf(tts()["kind"]) ?: "browser", voice))
                note("The voice changed. Check and preview again to enable OpenAI speech.")
        }
    }

    fun setConsent(synthetic: Boolean? = null, text: Boolean? = null, metered: Boolean? = null): Job? {
        // Read and written in one step, so a tap never overwrites the tick before it.
        local.update { l ->
            val s = now(l)
            l.copy(consents = Triple(synthetic ?: s.consentSynthetic, text ?: s.consentText, metered ?: s.consentMetered))
        }
        val withdrawn = synthetic == false || text == false || metered == false
        return if (withdrawn) viewModelScope.launch { revoke("openai", "Hosted speech is disabled. A new check and preview is needed to enable it.") } else null
    }

    /** revokeHostedSpeech: permission first, then the record, so a half-done revoke is still off. */
    private suspend fun revoke(kind: String, message: String) {
        val wasEnabled = hostedSpeechReady(ring(), prefsStore.current())
        epoch++
        check?.cancel()
        val allowedSaved = prefsStore.update { it.copy(hostedSpeechAllowed = false) }
        if (wasEnabled) runCatching { onRevoke("Hosted speech is disabled. Tap Resume to continue with the device voice.") }
        if (!saveRing(withTts(ring(), ttsRecord(kind, savedTtsKey(), now().voice, verified = false)))) return
        note(if (allowedSaved) message else "$message Its permission preference could not be updated.", failed = !allowedSaved)
    }

    /** Check and preview; pressed again while checking, it cancels (never a pass). */
    fun checkAndPreview(): Job? {
        if (check?.isActive == true) {
            epoch++
            check?.cancel()
            local.update { it.copy(checking = false, notice = "Preview cancelled.", noticeFailed = false) }
            return null
        }
        val s = now()
        val key = s.keyDraft.trim().ifEmpty { savedTtsKey() }
        if (!s.hosted || key.isEmpty() || !s.consented) {
            note("Enter a separate speech key and confirm all three statements first.", failed = true)
            return null
        }
        val my = ++epoch
        val generation = keyring.generation.value
        val voice = s.voice
        local.update { it.copy(checking = true, notice = "Checking one short, metered speech sample…", noticeFailed = false) }
        return viewModelScope.launch {
            try {
                val rate = prefsStore.current().speechRate
                val result = previewer.preview(key, voice, rate)
                if (my != epoch) return@launch
                // Only OpenAI audio played to the end counts. A device-voice fallback proves
                // nothing about the hosted backend, so it can never pass.
                if (result.status != SpeakStatus.SPOKEN || result.backend != SpeechBackend.HOSTED || result.fallback != null) {
                    note(
                        if (result.status == SpeakStatus.CANCELLED) "Preview cancelled."
                        else "The voice check failed: ${result.message ?: "no audio played"}. OpenAI speech has not been enabled.",
                        failed = result.status != SpeakStatus.CANCELLED,
                    )
                    return@launch
                }
                if (!saveRing(withTts(ring(), ttsRecord("openai", key, voice, verified = true)), generation)) return@launch
                if (my != epoch || keyring.generation.value != generation) return@launch
                if (!prefsStore.update { it.copy(hostedSpeechAllowed = true) }) {
                    saveRing(withTts(ring(), ttsRecord("openai", key, voice, verified = false)))
                    note("The preview played, but permission could not be saved. The device voice remains active.", failed = true)
                    return@launch
                }
                if (keyring.generation.value != generation) {
                    // Forget began while the permission was being written: take it back.
                    prefsStore.update { it.copy(hostedSpeechAllowed = false) }
                    return@launch
                }
                local.update { it.copy(keyDraft = "", consents = null) }
                note("OpenAI speech played successfully on this device. Enhanced speech is enabled.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (my == epoch) note("The voice check failed: ${e.message}", failed = true)
            } finally {
                if (my == epoch) local.update { it.copy(checking = false) }
            }
        }.also { check = it }
    }

    // ---- Transcription fallback -----------------------------------------------------------

    fun setSttKind(kind: String?) = local.update {
        it.copy(sttKind = kind?.takeIf { k -> k in STT_PRESETS }, sttKindSet = true,
            sttNotice = null, sttNoticeFailed = false)
    }

    fun setSttKey(v: String) = local.update { it.copy(sttKeyDraft = v, sttNotice = null, sttNoticeFailed = false) }

    fun setAudioConsent(on: Boolean): Job = viewModelScope.launch { prefsStore.update { it.copy(hostedTranscriptionAllowed = on) } }

    /** withStt: its own record and key. A changed vendor never inherits the old vendor's key. */
    fun saveTranscription(): Job {
        val generation = keyring.generation.value
        return viewModelScope.launch { saveTranscription(generation) }
    }

    private suspend fun saveTranscription(generation: Long) {
        val s = now()
        val stt = ring()["stt"]
        val sameKind = s.sttKind != null && s.sttKind == stringOf(stt["kind"])
        val key = s.sttKeyDraft.trim().ifEmpty { if (sameKind) stringOf(stt["apiKey"]).orEmpty() else "" }
        if (s.sttKind != null && key.isEmpty()) {
            sttNote("Transcription needs its own key.", failed = true)
            return
        }
        val rec = JsonObject(
            linkedMapOf("kind" to (s.sttKind?.let(::JsonPrimitive) ?: JsonNull), "apiKey" to JsonPrimitive(if (s.sttKind == null) "" else key)),
        )
        if (!saveRing(withStt(ring(), rec), generation, report = { sttNote(it, failed = true) })) return
        if (s.sttKind == null) prefsStore.update { it.copy(hostedTranscriptionAllowed = false) }
        local.update { it.copy(sttKeyDraft = "", sttKindSet = false) }
        sttNote(if (s.sttKind == null) "Transcription is off." else "Transcription saved. It is used only when the speech recogniser is missing or cannot recognise your language.")
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as IdeaForgeApp).graph
                HostedSpeechViewModel(
                    graph.prefsStore, graph.keyringStore,
                    previewer = { key, voice, rate -> graph.hostedSpeaker.check(checkSynth(key, voice, rate), rate) },
                    onRevoke = { detail ->
                        graph.hostedSpeaker.cancel()
                        graph.driveController.pauseFor(detail)
                    },
                )
            }
        }
    }
}

object HostedSpeechTags {
    const val NATIVE = "speech-native"
    const val HOSTED = "speech-hosted"
    const val KEY = "speech-key"
    const val VOICE_MARIN = "speech-voice-marin"
    const val VOICE_CEDAR = "speech-voice-cedar"
    const val CONSENT_SYNTHETIC = "speech-consent-synthetic"
    const val CONSENT_TEXT = "speech-consent-text"
    const val CONSENT_METERED = "speech-consent-metered"
    const val CHECK = "speech-check"
    const val NOTICE = "speech-notice"
    const val ENABLED = "speech-enabled"
    const val STT_OFF = "stt-off"
    fun stt(kind: String) = "stt-$kind"
    const val STT_KEY = "stt-key"
    const val AUDIO_CONSENT = "stt-consent"
    const val STT_SAVE = "stt-save"
    const val STT_NOTICE = "stt-notice"
}

@Composable
fun HostedSpeechSection(vm: HostedSpeechViewModel = viewModel(factory = HostedSpeechViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    Text(stringResource(R.string.speech_output), style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.selectableGroup()) {
        Choice(stringResource(R.string.speech_native), !s.hosted, HostedSpeechTags.NATIVE) { vm.chooseHosted(false) }
        Choice(stringResource(R.string.speech_hosted), s.hosted, HostedSpeechTags.HOSTED) { vm.chooseHosted(true) }
    }
    if (s.hosted) {
        Text(
            stringResource(R.string.speech_hosted_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = s.keyDraft,
            onValueChange = { vm.setKey(it) },
            label = { Text(stringResource(R.string.speech_key)) },
            placeholder = { Text(if (s.savedKey.isNotEmpty()) stringResource(R.string.settings_key_saved, s.savedKey) else stringResource(R.string.speech_key_hint)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(HostedSpeechTags.KEY),
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(IdeaForgeSpacing.tight)) {
            Choice(stringResource(R.string.speech_voice_marin), s.voice == "marin", HostedSpeechTags.VOICE_MARIN) { vm.setVoice("marin") }
            Choice(stringResource(R.string.speech_voice_cedar), s.voice == "cedar", HostedSpeechTags.VOICE_CEDAR) { vm.setVoice("cedar") }
        }
        Consent(stringResource(R.string.speech_consent_synthetic), s.consentSynthetic, HostedSpeechTags.CONSENT_SYNTHETIC) { vm.setConsent(synthetic = it) }
        Consent(stringResource(R.string.speech_consent_text), s.consentText, HostedSpeechTags.CONSENT_TEXT) { vm.setConsent(text = it) }
        Consent(stringResource(R.string.speech_consent_metered), s.consentMetered, HostedSpeechTags.CONSENT_METERED) { vm.setConsent(metered = it) }
        if (s.checking) {
            OutlinedButton(onClick = { vm.checkAndPreview() }, modifier = Modifier.testTag(HostedSpeechTags.CHECK)) {
                Text(stringResource(R.string.speech_cancel_check))
            }
        } else {
            Button(onClick = { vm.checkAndPreview() }, enabled = s.canCheck, modifier = Modifier.testTag(HostedSpeechTags.CHECK)) {
                Text(stringResource(R.string.speech_check))
            }
        }
        Text(
            stringResource(if (s.enabled) R.string.speech_enabled else R.string.speech_not_enabled),
            Modifier.testTag(HostedSpeechTags.ENABLED),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    s.notice?.let {
        IdeaForgeStatusText(it, Modifier.testTag(HostedSpeechTags.NOTICE),
            tone = if (s.noticeFailed) StatusTone.Error else StatusTone.Neutral)
    }

    Text(stringResource(R.string.stt_title), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.stt_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.selectableGroup()) {
        Choice(stringResource(R.string.stt_off), s.sttKind == null, HostedSpeechTags.STT_OFF) { vm.setSttKind(null) }
        for ((id, preset) in STT_PRESETS) {
            Choice(preset.label, s.sttKind == id, HostedSpeechTags.stt(id)) { vm.setSttKind(id) }
        }
    }
    if (s.sttKind != null) {
        OutlinedTextField(
            value = s.sttKeyDraft,
            onValueChange = vm::setSttKey,
            label = { Text(stringResource(R.string.stt_key)) },
            placeholder = { if (s.sttSavedKey.isNotEmpty()) Text(stringResource(R.string.settings_key_saved, s.sttSavedKey)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(HostedSpeechTags.STT_KEY),
        )
        Consent(stringResource(R.string.stt_consent), s.audioConsent, HostedSpeechTags.AUDIO_CONSENT) { vm.setAudioConsent(it) }
    }
    OutlinedButton(onClick = { vm.saveTranscription() }, modifier = Modifier.testTag(HostedSpeechTags.STT_SAVE)) {
        Text(stringResource(R.string.stt_save))
    }
    s.sttNotice?.let {
        IdeaForgeStatusText(it, Modifier.testTag(HostedSpeechTags.STT_NOTICE),
            tone = if (s.sttNoticeFailed) StatusTone.Error else StatusTone.Neutral)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = IdeaForgeSpacing.control).selectable(selected, onClick = onClick, role = Role.RadioButton).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = IdeaForgeSpacing.tight))
    }
}

@Composable
private fun Consent(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = IdeaForgeSpacing.control).toggleable(checked, onValueChange = onChange, role = Role.Checkbox).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = IdeaForgeSpacing.tight), style = MaterialTheme.typography.bodyMedium)
    }
}
