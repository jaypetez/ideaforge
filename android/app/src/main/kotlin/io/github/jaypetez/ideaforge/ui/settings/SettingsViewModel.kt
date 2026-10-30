package io.github.jaypetez.ideaforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.hasAnyKey
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.normalizeTrigger
import io.github.jaypetez.ideaforge.core.triggerWarning
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.InferenceConsent
import io.github.jaypetez.ideaforge.providers.PROVIDER_CHOICES
import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.createProvider
import io.github.jaypetez.ideaforge.providers.presetModels
import io.github.jaypetez.ideaforge.providers.InferenceDestination
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
import io.github.jaypetez.ideaforge.providers.inferenceDestination
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.ui.onboarding.CheckFailure
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.onboarding.checkFailure
import io.github.jaypetez.ideaforge.ui.onboarding.hasUsableProvider
import io.github.jaypetez.ideaforge.voice.DeviceVoice
import io.github.jaypetez.ideaforge.voice.DeviceVoiceLoadException
import io.github.jaypetez.ideaforge.voice.loadDeviceVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The connection form: one provider's record being edited. Only the fields [choice] declares
 * are ever read back into the keyring (see [SettingsViewModel.save]); the rest may hold
 * anything and it does not matter.
 */
data class ConnectionEditor(
    val choice: ProviderChoice,
    /** A NEW key. Blank keeps the saved one, as the web's key field does. */
    val apiKey: String = "",
    /** The key already saved for [choice], for the masked placeholder. Never shown in full. */
    val savedKey: String = "",
    val baseUrl: String = "",
    /** The QUESTIONS model (the record's `model`, which keeps its old name). */
    val model: String = "",
    val wrapModel: String = "",
    /** Suggestions for both model boxes: the preset's tiers, or what the server listed. */
    val models: List<String> = presetModels(choice),
    val agreement: Boolean? = null,
)

enum class DeviceVoicePhase { LOADING, READY, FAILED }

data class DeviceVoicesState(
    val phase: DeviceVoicePhase = DeviceVoicePhase.LOADING,
    val choices: List<DeviceVoice> = emptyList(),
    val problem: String? = null,
)

data class SettingsState(
    /** Null until the preferences file has been read once. */
    val prefs: Prefs? = null,
    val keyring: KeyringState = KeyringState(io.github.jaypetez.ideaforge.core.emptyKeyring(), KeyringState.Status.NOT_LOADED),
    /** Null until the keyring has loaded. */
    val editor: ConnectionEditor? = null,
    val checking: Boolean = false,
    val failure: CheckFailure? = null,
    /** What the last check or save achieved, in the web's words. */
    val notice: String? = null,
    /** Set when saving would replace an unreadable keyring; nothing is written until confirmed. */
    val unreadable: String? = null,
    /** The finish word as typed; null when the field shows what is stored. */
    val triggerDraft: String? = null,
    val confirmingForget: Boolean = false,
    val forgetting: Boolean = false,
    /** Forget all ran. With no usable provider left, the screen routes to onboarding. */
    val forgotten: Boolean = false,
    val inferenceDestination: InferenceDestination? = null,
    val inferenceAgreed: Boolean = false,
    val inferenceProblem: String? = null,
    val deviceVoices: DeviceVoicesState = DeviceVoicesState(),
    val voiceSaving: Boolean = false,
    val voiceSaveProblem: String? = null,
) {
    val ring: JsonObject get() = keyring.ring

    /** The provider the next interview uses, or null when none is set. */
    val active: ProviderChoice? get() = stringOf(ring["active"])?.let { id -> PROVIDER_CHOICES.firstOrNull { it.id == id } }

    val activeCreds: JsonObject? get() = active?.let { credsFor(ring, it.id) }

    val trigger: String get() = triggerDraft ?: prefs?.effectiveTrigger.orEmpty()

    /** Live, from what is typed: the web shows it under the field the moment it applies. */
    val triggerWarning: String? get() = if (trigger.isBlank()) null else triggerWarning(trigger)

    /** Forget is offered only when there is something to forget (the web's b-forget rule). */
    val canForget: Boolean get() = hasAnyKey(ring) || keyring.status == KeyringState.Status.UNREADABLE

    val needsOnboarding: Boolean get() =
        forgotten && keyring.status != KeyringState.Status.NOT_LOADED && !hasUsableProvider(ring)
}

private data class Local(
    val editor: ConnectionEditor? = null,
    val checking: Boolean = false,
    val failure: CheckFailure? = null,
    val notice: String? = null,
    val unreadable: String? = null,
    val triggerDraft: String? = null,
    val confirmingForget: Boolean = false,
    val forgetting: Boolean = false,
    val forgotten: Boolean = false,
    val deviceVoices: DeviceVoicesState = DeviceVoicesState(),
    val voiceSaving: Boolean = false,
    val voiceSaveProblem: String? = null,
)

/**
 * The settings screen: the connection (provider, key, the two models), listening prefs, the
 * theme, Forget, and the version. Preferences go to [PrefsStore]; credentials to the
 * [KeyringStore] through withCreds, so changing one provider never touches another's record.
 */
class SettingsViewModel(
    private val prefsStore: PrefsStore,
    private val keyring: KeyringStore,
    private val checker: ProviderCheck,
    private val inference: InferenceConsent,
    val choices: List<ProviderChoice> = PROVIDER_CHOICES,
    // The web's buildProvider before accepting local settings: refuses an address the adapter
    // would refuse on every call, without touching the network (see OnboardingViewModel).
    private val buildLocal: (ProviderConfig) -> Unit = { createProvider(it) },
    /**
     * Forget's effects outside the keyring, run first and synchronously: stop hosted speech
     * (and with it a Settings check that is playing) and pause an active drive, as the web's
     * forgetKey tears voice down before it deletes anything.
     */
    private val onForget: () -> Unit = {},
    private val loadDeviceVoices: suspend () -> List<DeviceVoice> = { emptyList() },
) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsState> =
        combine(prefsStore.prefs, keyring.state, local, inference.state) { prefs, ring, l, consent ->
            val editor = l.editor ?: if (ring.status == KeyringState.Status.NOT_LOADED) null else editorFor(ring.ring, activeId(ring.ring))
            val destination = editor?.let(::destinationOf)
            SettingsState(
                prefs = prefs,
                keyring = ring,
                editor = editor,
                checking = l.checking,
                failure = l.failure,
                notice = l.notice,
                unreadable = l.unreadable,
                triggerDraft = l.triggerDraft,
                confirmingForget = l.confirmingForget,
                forgetting = l.forgetting,
                forgotten = l.forgotten,
                inferenceDestination = destination,
                inferenceAgreed = destination != null && (editor?.agreement ?: inference.allowed(destination, prefs)),
                inferenceProblem = consent.problem,
                deviceVoices = l.deviceVoices,
                voiceSaving = l.voiceSaving,
                voiceSaveProblem = l.voiceSaveProblem,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    private var work: Job? = null
    private var generation = 0
    private var voicesWork: Job? = null
    private var voicesGeneration = 0
    @Volatile private var voiceRevision = 0

    /**
     * Forget all, while it runs. Save, Check and Replace refuse to start meanwhile: a save
     * begun after Forget would write the key Forget is deleting straight back. Enforced here,
     * not only by the screen's disabled buttons.
     */
    private var forgetJob: Job? = null
    private val forgetting: Boolean get() = forgetJob?.isActive == true

    init {
        // The application starts this at launch; a graph built without it still works.
        viewModelScope.launch { if (keyring.state.value.status == KeyringState.Status.NOT_LOADED) keyring.load() }
        refreshDeviceVoices()
    }

    private fun activeId(ring: JsonObject): String =
        stringOf(ring["active"])?.takeIf { id -> choices.any { it.id == id } } ?: choices.first().id

    /**
     * The form for [id], filled from its saved record, as the web's onProviderChange: the
     * address (or the preset's own), both models, and the saved key only as a placeholder.
     */
    private fun editorFor(ring: JsonObject, id: String): ConnectionEditor {
        val choice = choices.first { it.id == id }
        val saved = credsFor(ring, id)
        return ConnectionEditor(
            choice = choice,
            savedKey = text(saved["apiKey"]),
            baseUrl = text(saved["baseUrl"]).ifEmpty { choice.defaultBaseUrl.orEmpty() },
            model = text(saved["model"]),
            wrapModel = text(saved["wrapModel"]),
        )
    }

    /**
     * The form as it is now, read from the sources rather than [state]: the combined state is
     * emitted asynchronously, so a save straight after an edit could otherwise read the form
     * from before the edit.
     */
    private fun editor(): ConnectionEditor? = local.value.editor
        ?: keyring.state.value.takeIf { it.status != KeyringState.Status.NOT_LOADED }?.let { editorFor(it.ring, activeId(it.ring)) }

    private fun edit(transform: (ConnectionEditor) -> ConnectionEditor) {
        val e = editor() ?: return
        generation++
        work?.cancel()
        local.update { it.copy(editor = transform(e), checking = false, failure = null, notice = null, unreadable = null) }
    }

    // ---- Connection -------------------------------------------------------------------

    fun pickProvider(id: String) {
        if (choices.none { it.id == id }) return
        editor()?.let { inference.select(it.choice.id, destinationOf(it)?.id) }
        // Any pending "replace the unreadable keyring?" belonged to the provider being left;
        // the confirmation itself is never stored (see persist's `replace`).
        generation++
        work?.cancel()
        val next = editorFor(keyring.state.value.ring, id)
        val changed = inference.select(next.choice.id, destinationOf(next)?.id)
        local.update { it.copy(editor = if (changed) next.copy(agreement = false) else next, checking = false, failure = null, notice = null, unreadable = null) }
    }

    fun setApiKey(v: String) = edit { it.copy(apiKey = v) }

    /** A different address is a different server: the list read off the old one is a lie about it. */
    fun setBaseUrl(v: String) = edit {
        inference.select(it.choice.id, destinationOf(it)?.id)
        val next = it.copy(baseUrl = v, models = presetModels(it.choice))
        if (inference.select(next.choice.id, destinationOf(next)?.id)) next.copy(agreement = false) else next
    }

    fun setModel(v: String) = edit { it.copy(model = v) }

    fun setWrapModel(v: String) = edit { it.copy(wrapModel = v) }

    fun setAgreement(on: Boolean) {
        editor()?.let { inference.select(it.choice.id, destinationOf(it)?.id) }
        edit { it.copy(agreement = on) }
        if (!on) inference.withdraw()
    }

    private fun destinationOf(e: ConnectionEditor): InferenceDestination? = try {
        inferenceDestination(configOf(e))
    } catch (_: ProviderException) { null }

    /** The key to use: a newly typed one, else the saved one. Only for a provider with a key. */
    private fun keyOf(e: ConnectionEditor): String = if (e.choice.needsKey) e.apiKey.trim().ifEmpty { e.savedKey } else ""

    /** What the checker is handed: exactly the fields [ConnectionEditor.choice] declares. */
    private fun configOf(e: ConnectionEditor) = ProviderConfig(
        kind = e.choice.id,
        apiKey = keyOf(e),
        baseUrl = if (e.choice.local) e.baseUrl.trim() else null,
    )

    /**
     * The web's Check button: a provider with a model list is checked by reading it (and the
     * list fills the model suggestions); one without (Anthropic) by validateKey.
     */
    fun check(): Job? {
        if (forgetting) return null
        val e = editor() ?: return null
        missing(e)?.let { f -> local.update { it.copy(failure = f) }; return null }
        return launchWork {
            val names = checker.check(e.choice, configOf(e))
            val notice = when {
                e.choice.discoverModels && e.choice.local && names.isEmpty() -> "That server answered, but it has no models. Pull one first."
                e.choice.needsKey -> "That key works."
                else -> "That server answered."
            }
            local.update { l ->
                val cur = l.editor ?: e
                val listed = if (e.choice.discoverModels && names.isNotEmpty()) names.distinct().sorted() else cur.models
                // One installed model is not a choice, so make it (as onboarding does).
                val model = if (cur.model.isBlank() && e.choice.local && names.size == 1) names[0] else cur.model
                l.copy(editor = cur.copy(models = listed, model = model), notice = notice)
            }
        }
    }

    private fun missing(e: ConnectionEditor): CheckFailure? = when {
        e.choice.needsKey && keyOf(e).isEmpty() -> CheckFailure("Paste your ${e.choice.label} key first.", "")
        e.choice.local && e.baseUrl.isBlank() -> CheckFailure("Enter the server's address first.", "")
        else -> null
    }

    /**
     * Validate, then save. A hosted key is checked first; a local setup is built first (which
     * refuses an address the app would refuse on every call) and needs a model, without which
     * no interview could start.
     *
     * The record is written field by field from what this provider DECLARES, never from the
     * whole form: the web shipped two bugs from reading a hidden field back (the preset's own
     * address sent to the loopback check for every hosted provider, an empty model box
     * POSTing no model). The models are stored as typed; resolveTiers decides what runs, and
     * keeps a question-model override away from the wrap-up's strong tier.
     */
    fun save(): Job? {
        if (forgetting) return null
        val e = editor() ?: return null
        missing(e)?.let { f -> local.update { it.copy(failure = f) }; return null }
        if (e.choice.local && e.model.isBlank()) {
            local.update { it.copy(failure = CheckFailure("Choose a model first. Check the connection to list what the server has.", "")) }
            return null
        }
        val credentialGeneration = keyring.generation.value
        return launchWork { persist(e, credentialGeneration) }
    }

    /**
     * [replace] is the user's confirmation to overwrite an unreadable keyring, for THIS save
     * only. It used to be a field cleared only by a successful save, so a confirmed save whose
     * check then failed left it armed, and the next Save, for any provider, replaced the
     * unreadable keyring without asking.
     */
    private suspend fun persist(e: ConnectionEditor, credentialGeneration: Long, replace: Boolean = false) {
        val generation = generation
        val destination = inferenceDestination(configOf(e))
        if (e.agreement != true && (e.agreement == false || !inference.allowed(destination, prefsStore.inferenceCurrent()))) {
            throw ProviderException(ProviderErrorCode.CONSENT, "Agree to send interview text to ${e.choice.label} before saving this connection.")
        }
        val key = keyOf(e)
        if (e.choice.local) {
            buildLocal(ProviderConfig(kind = e.choice.id, baseUrl = e.baseUrl.trim(), model = e.model.trim()))
        } else {
            checker.check(e.choice, configOf(e))
        }
        currentCoroutineContext().ensureActive()
        if (generation != this.generation) throw CancellationException("Connection changed")
        val saved = try {
            currentCoroutineContext().ensureActive()
            if (generation != this.generation) throw CancellationException("Connection changed")
            keyring.saveIf(credentialGeneration, replaceUnreadable = replace) { latest ->
                val previous = credsFor(latest, e.choice.id)
                val rec = LinkedHashMap<String, JsonElement>().apply {
                    put("apiKey", JsonPrimitive(if (e.choice.needsKey) key else text(previous["apiKey"])))
                    put("baseUrl", JsonPrimitive(if (e.choice.local) e.baseUrl.trim() else ""))
                    put("model", JsonPrimitive(if (e.choice.picksModel) e.model.trim() else ""))
                    put("wrapModel", JsonPrimitive(if (e.choice.picksModel) e.wrapModel.trim() else ""))
                }
                withCreds(latest, e.choice.id, JsonObject(rec))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            local.update { it.copy(failure = CheckFailure("That worked, but it could not be saved on this device.", ex.message.orEmpty())) }
            return
        }
        currentCoroutineContext().ensureActive()
        if (saved == null || keyring.generation.value != credentialGeneration) throw CancellationException("Keys were deleted meanwhile")
        if (saved.status == KeyringState.Status.UNREADABLE) {
            local.update { it.copy(unreadable = saved.problem.orEmpty()) }
            return
        }
        inference.accept(destination) { generation == this.generation && keyring.generation.value == credentialGeneration }
        // Back to the saved record: the typed key is now the masked placeholder.
        local.update { it.copy(editor = null, unreadable = null, notice = "Saved. ${e.choice.label} answers the next interview.") }
    }

    fun replaceUnreadable(): Job? {
        if (forgetting) return null
        val e = editor() ?: return null
        local.update { it.copy(unreadable = null) }
        val credentialGeneration = keyring.generation.value
        return launchWork { persist(e, credentialGeneration, replace = true) }
    }

    fun dismissUnreadable() = local.update { it.copy(unreadable = null) }

    private fun launchWork(block: suspend () -> Unit): Job {
        work?.cancel()
        val gen = ++generation
        local.update { it.copy(checking = true, failure = null, notice = null) }
        return viewModelScope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                if (gen == generation) local.update { it.copy(failure = checkFailure(ex)) }
            } finally {
                if (gen == generation) local.update { it.copy(checking = false) }
            }
        }.also { work = it }
    }

    // ---- Listening ----------------------------------------------------------------------

    fun setTriggerDraft(v: String) = local.update { it.copy(triggerDraft = v) }

    /**
     * Clean the typed word up and store it, as the web's applyTrigger does on change. Called
     * when the field is finished with (Done, focus leaving, the screen closing), never per
     * keystroke: normalising mid-word would rewrite "two words" into "two" under the cursor.
     */
    fun commitTrigger(): Job? {
        val draft = local.value.triggerDraft ?: return null
        val next = normalizeTrigger(draft)
        local.update { it.copy(triggerDraft = null) }
        return viewModelScope.launch {
            if (next != prefsStore.current().effectiveTrigger) prefsStore.update { it.copy(trigger = next) }
        }
    }

    fun setSpeechRate(rate: Double): Job = viewModelScope.launch { prefsStore.update { it.copy(speechRate = rate) } }

    fun refreshDeviceVoices(): Job {
        val request = ++voicesGeneration
        voicesWork?.cancel()
        local.update { it.copy(deviceVoices = it.deviceVoices.copy(phase = DeviceVoicePhase.LOADING, problem = null)) }
        return viewModelScope.launch {
            try {
                val choices = loadDeviceVoices()
                currentCoroutineContext().ensureActive()
                if (request == voicesGeneration) local.update {
                    it.copy(deviceVoices = DeviceVoicesState(DeviceVoicePhase.READY, choices))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DeviceVoiceLoadException) {
                if (request == voicesGeneration) local.update {
                    it.copy(deviceVoices = it.deviceVoices.copy(phase = DeviceVoicePhase.FAILED, problem = failure.message))
                }
            }
        }.also { voicesWork = it }
    }

    /** Merge only this preference; an engine read never writes or replaces a saved choice. */
    fun setSpeechVoice(id: String): Job? {
        val problem = when {
            id.length > Prefs.MAX_VOICE -> "That device voice identifier is too long to save. Your previous choice is kept; choose another voice."
            id.isNotEmpty() && (local.value.deviceVoices.phase != DeviceVoicePhase.READY ||
                local.value.deviceVoices.choices.none { it.id == id }) ->
                "The device voice list changed. Your previous choice is kept; reload and choose again."
            else -> null
        }
        if (problem != null) {
            local.update { it.copy(voiceSaveProblem = problem) }
            return null
        }
        val request = ++voiceRevision
        local.update { it.copy(voiceSaving = true, voiceSaveProblem = null) }
        return viewModelScope.launch {
            val saved = prefsStore.update { if (request == voiceRevision) it.copy(speechVoice = id) else it }
            if (request == voiceRevision) local.update {
                it.copy(voiceSaving = false, voiceSaveProblem = if (saved) null else
                    "The device voice could not be saved. Your previous choice is kept; choose again to retry.")
            }
        }
    }

    // ---- Appearance ---------------------------------------------------------------------

    fun setTheme(theme: String): Job? {
        if (theme !in Prefs.THEMES) return null
        return viewModelScope.launch { prefsStore.update { it.copy(theme = theme) } }
    }

    // ---- Data ---------------------------------------------------------------------------

    fun askForget() = local.update { it.copy(confirmingForget = true) }

    fun cancelForget() = local.update { it.copy(confirmingForget = false) }

    /**
     * Delete the keyring file and both Keystore aliases. As on the web, it also withdraws the
     * consent to hosted speech, which belonged to a key that no longer exists. Preferences
     * otherwise survive: "Forget my key" must not reset the trigger word.
     *
     * The consent goes first, whatever happens to the keyring after it (the web's forgetKey
     * order): a Forget that fails halfway must not leave hosted speech permitted. Work already
     * in flight is cancelled and waited for, so a save that was running cannot land after the
     * keys are gone (KeyringStore's mutex then orders anything that got that far before us).
     */
    fun forget(): Job {
        forgetJob?.takeIf { it.isActive }?.let { return it }
        generation++
        // Before anything suspends: a speech check or transcription save in flight read the
        // old generation and will now be refused (KeyringStore.saveIf).
        keyring.revoke()
        inference.withdraw("Keys were forgotten. Review the inference agreement after setting up a provider.")
        runCatching { onForget() }
        val previous = work
        previous?.cancel()
        local.update { it.copy(confirmingForget = false, forgetting = true, checking = true, failure = null, notice = null, unreadable = null) }
        return viewModelScope.launch {
            try {
                // Both hosted consents belonged to keys that are about to stop existing.
                val p = prefsStore.current()
                if (p.hostedSpeechAllowed || p.hostedTranscriptionAllowed) {
                    prefsStore.update { it.copy(hostedSpeechAllowed = false, hostedTranscriptionAllowed = false) }
                }
                previous?.join()
                keyring.forget()
                local.update { it.copy(editor = null, forgotten = true, notice = "Keys deleted from this device.") }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                local.update { it.copy(failure = CheckFailure("The keys could not all be deleted.", ex.message.orEmpty())) }
            } finally {
                local.update { it.copy(checking = false, forgetting = false) }
            }
        }.also { forgetJob = it }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as IdeaForgeApp).graph
                SettingsViewModel(
                    graph.prefsStore, graph.keyringStore, graph.providerCheck, graph.inferenceConsent,
                    onForget = {
                        graph.hostedSpeaker.cancel()
                        graph.driveController.pauseFor("Keys were deleted from this device. Tap Resume to continue with the device voice.")
                    },
                    loadDeviceVoices = graph.nativeTtsEngine::loadDeviceVoices,
                )
            }
        }
    }
}

private fun text(e: JsonElement?): String = stringOf(e).orEmpty()
