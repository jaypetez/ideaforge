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
import io.github.jaypetez.ideaforge.providers.PROVIDER_CHOICES
import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.createProvider
import io.github.jaypetez.ideaforge.providers.presetModels
import io.github.jaypetez.ideaforge.ui.onboarding.CheckFailure
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.onboarding.checkFailure
import io.github.jaypetez.ideaforge.ui.onboarding.hasUsableProvider
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
    /** Forget all ran. With no usable provider left, the screen routes to onboarding. */
    val forgotten: Boolean = false,
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
    val forgotten: Boolean = false,
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
    val choices: List<ProviderChoice> = PROVIDER_CHOICES,
    // The web's buildProvider before accepting local settings: refuses an address the adapter
    // would refuse on every call, without touching the network (see OnboardingViewModel).
    private val buildLocal: (ProviderConfig) -> Unit = { createProvider(it) },
) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsState> =
        combine(prefsStore.prefs, keyring.state, local) { prefs, ring, l ->
            SettingsState(
                prefs = prefs,
                keyring = ring,
                editor = l.editor ?: if (ring.status == KeyringState.Status.NOT_LOADED) null else editorFor(ring.ring, activeId(ring.ring)),
                checking = l.checking,
                failure = l.failure,
                notice = l.notice,
                unreadable = l.unreadable,
                triggerDraft = l.triggerDraft,
                confirmingForget = l.confirmingForget,
                forgotten = l.forgotten,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    private var work: Job? = null
    private var generation = 0
    private var replaceConfirmed = false

    init {
        // The application starts this at launch; a graph built without it still works.
        viewModelScope.launch { if (keyring.state.value.status == KeyringState.Status.NOT_LOADED) keyring.load() }
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
        local.update { it.copy(editor = transform(e), failure = null, notice = null) }
    }

    // ---- Connection -------------------------------------------------------------------

    fun pickProvider(id: String) {
        if (choices.none { it.id == id }) return
        generation++
        work?.cancel()
        local.update { it.copy(editor = editorFor(keyring.state.value.ring, id), checking = false, failure = null, notice = null, unreadable = null) }
    }

    fun setApiKey(v: String) = edit { it.copy(apiKey = v) }

    /** A different address is a different server: the list read off the old one is a lie about it. */
    fun setBaseUrl(v: String) = edit { it.copy(baseUrl = v, models = presetModels(it.choice)) }

    fun setModel(v: String) = edit { it.copy(model = v) }

    fun setWrapModel(v: String) = edit { it.copy(wrapModel = v) }

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
        val e = editor() ?: return null
        missing(e)?.let { f -> local.update { it.copy(failure = f) }; return null }
        if (e.choice.local && e.model.isBlank()) {
            local.update { it.copy(failure = CheckFailure("Choose a model first. Check the connection to list what the server has.", "")) }
            return null
        }
        return launchWork { persist(e) }
    }

    private suspend fun persist(e: ConnectionEditor) {
        val key = keyOf(e)
        if (e.choice.local) {
            buildLocal(ProviderConfig(kind = e.choice.id, baseUrl = e.baseUrl.trim(), model = e.model.trim()))
        } else {
            checker.check(e.choice, configOf(e))
        }
        // An unreadable keyring is kept on disk on purpose (a Keystore that failed once may
        // answer now): read it again, and if it is still unreadable, saving would replace
        // every other provider's record, so that waits for the user's say-so.
        if (keyring.state.value.status == KeyringState.Status.UNREADABLE) keyring.load()
        val current = keyring.state.value
        if (current.status == KeyringState.Status.UNREADABLE && !replaceConfirmed) {
            local.update { it.copy(unreadable = current.problem.orEmpty()) }
            return
        }
        val saved = credsFor(current.ring, e.choice.id)
        val rec = LinkedHashMap<String, JsonElement>().apply {
            put("apiKey", JsonPrimitive(if (e.choice.needsKey) key else text(saved["apiKey"])))
            put("baseUrl", JsonPrimitive(if (e.choice.local) e.baseUrl.trim() else ""))
            put("model", JsonPrimitive(if (e.choice.picksModel) e.model.trim() else ""))
            put("wrapModel", JsonPrimitive(if (e.choice.picksModel) e.wrapModel.trim() else ""))
        }
        try {
            keyring.save(withCreds(current.ring, e.choice.id, JsonObject(rec)))
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            local.update { it.copy(failure = CheckFailure("That worked, but it could not be saved on this device.", ex.message.orEmpty())) }
            return
        }
        replaceConfirmed = false
        // Back to the saved record: the typed key is now the masked placeholder.
        local.update { it.copy(editor = null, unreadable = null, notice = "Saved. ${e.choice.label} answers the next interview.") }
    }

    fun replaceUnreadable(): Job? {
        val e = editor() ?: return null
        replaceConfirmed = true
        local.update { it.copy(unreadable = null) }
        return launchWork { persist(e) }
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

    fun setHandsFree(on: Boolean): Job = viewModelScope.launch { prefsStore.update { it.copy(handsFree = on) } }

    fun setSpeechRate(rate: Double): Job = viewModelScope.launch { prefsStore.update { it.copy(speechRate = rate) } }

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
     */
    fun forget(): Job {
        generation++
        work?.cancel()
        local.update { it.copy(confirmingForget = false, checking = true, failure = null, notice = null) }
        return viewModelScope.launch {
            try {
                keyring.forget()
                if (prefsStore.current().hostedSpeechAllowed) prefsStore.update { it.copy(hostedSpeechAllowed = false) }
                local.update { it.copy(editor = null, forgotten = true, notice = "Keys deleted from this device.") }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                local.update { it.copy(failure = CheckFailure("The keys could not all be deleted.", ex.message.orEmpty())) }
            } finally {
                local.update { it.copy(checking = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as IdeaForgeApp).graph
                SettingsViewModel(graph.prefsStore, graph.keyringStore, graph.providerCheck)
            }
        }
    }
}

private fun text(e: JsonElement?): String = stringOf(e).orEmpty()
