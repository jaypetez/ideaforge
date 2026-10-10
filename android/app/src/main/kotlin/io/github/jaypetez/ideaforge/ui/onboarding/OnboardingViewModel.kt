package io.github.jaypetez.ideaforge.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.providers.PROVIDER_CHOICES
import io.github.jaypetez.ideaforge.providers.ProviderChoice
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a check failed: a lead chosen by the error code, and the provider's own words. */
data class CheckFailure(val lead: String, val detail: String)

data class OnboardingState(
    /** Null on step 1 (pick a provider); the chosen provider on step 2. */
    val choice: ProviderChoice? = null,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    /** What the server listed, after a successful read. */
    val models: List<String>? = null,
    val checking: Boolean = false,
    val failure: CheckFailure? = null,
    /** Saved: the screen navigates to the library exactly once on this. */
    val done: Boolean = false,
) {
    val step: Int get() = if (choice == null) 1 else 2
    val canFinishLocal: Boolean get() = choice?.local == true && !checking && model.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * Two steps: pick a provider; then either a key (hosted) or a server address and a model read
 * off it (local). A hosted key is checked before it is saved, so the first interview does not
 * become the place a typo is discovered. A local server never gets a key field: its
 * [io.github.jaypetez.ideaforge.providers.NetworkPolicy.Local] client drops one anyway.
 */
class OnboardingViewModel(
    private val keyring: KeyringStore,
    private val checker: ProviderCheck,
    val choices: List<ProviderChoice> = PROVIDER_CHOICES,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    private var work: Job? = null

    // A busy flag is not an identity guard: a cancelled check's `finally` still runs, and
    // must not clear `checking` on (or report into) the check that replaced it.
    private var generation = 0

    fun pick(id: String) {
        val choice = choices.firstOrNull { it.id == id } ?: return
        // A record saved earlier for this provider (a re-run of onboarding) prefills the
        // address and model; never the key, which is not shown back in full anywhere.
        val saved = credsFor(keyring.state.value.ring, id)
        _state.value = OnboardingState(
            choice = choice,
            baseUrl = text(saved["baseUrl"]).ifEmpty { choice.defaultBaseUrl.orEmpty() }.takeIf { choice.local }.orEmpty(),
            model = if (choice.local) text(saved["model"]) else "",
        )
    }

    /** Back to the provider cards. Cancels a check in flight so it cannot land on step 1. */
    fun back() {
        generation++
        work?.cancel()
        _state.value = OnboardingState()
    }

    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v, failure = null) }

    /** A different address is a different server: the list read off the old one is a lie about it. */
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v, models = null, failure = null) }

    fun setModel(v: String) = _state.update { it.copy(model = v, failure = null) }

    /** Hosted: check the key, and on success save it and finish. */
    fun checkKey() {
        val s = _state.value
        val choice = s.choice?.takeIf { !it.local } ?: return
        // Pasted keys arrive with a trailing newline or space more often than not.
        val key = s.apiKey.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(failure = CheckFailure("Paste your ${choice.label} key first.", "")) }
            return
        }
        launchCheck {
            checker.check(choice, ProviderConfig(kind = choice.id, apiKey = key))
            save(choice) { put("apiKey", JsonPrimitive(key)) }
        }
    }

    /** Local: ask the server what it has. One installed model is not a choice, so make it. */
    fun findModels() {
        val s = _state.value
        val choice = s.choice?.takeIf { it.local } ?: return
        val base = s.baseUrl.trim()
        if (base.isEmpty()) {
            _state.update { it.copy(failure = CheckFailure("Enter the server's address first.", "")) }
            return
        }
        launchCheck {
            val names = checker.check(choice, ProviderConfig(kind = choice.id, baseUrl = base))
            _state.update {
                it.copy(models = names, model = if (it.model.isBlank() && names.size == 1) names[0] else it.model)
            }
        }
    }

    /** Local: save the address and model and finish. No network call; findModels was the check. */
    fun finishLocal() {
        val s = _state.value
        val choice = s.choice?.takeIf { it.local } ?: return
        if (!s.canFinishLocal) return
        launchCheck {
            save(choice) {
                put("baseUrl", JsonPrimitive(s.baseUrl.trim()))
                put("model", JsonPrimitive(s.model.trim()))
            }
        }
    }

    private suspend fun save(choice: ProviderChoice, edit: MutableMap<String, JsonElement>.() -> Unit) {
        val ring = keyring.state.value.ring
        // Keep whatever else this provider's record held (a wrap-up model chosen in Settings).
        val rec = LinkedHashMap<String, JsonElement>(credsFor(ring, choice.id)).apply(edit)
        try {
            keyring.save(withCreds(ring, choice.id, JsonObject(rec)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // "Your key was not saved" has to reach the user; a silent failure here would
            // send them to a library that cannot start an interview.
            throw SaveFailed(e)
        }
        _state.update { it.copy(done = true) }
    }

    private fun launchCheck(block: suspend () -> Unit) {
        work?.cancel()
        val gen = ++generation
        _state.update { it.copy(checking = true, failure = null) }
        work = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) _state.update { it.copy(failure = checkFailure(e)) }
            } finally {
                if (gen == generation) _state.update { it.copy(checking = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as IdeaForgeApp).graph
                OnboardingViewModel(graph.keyringStore, graph.providerCheck)
            }
        }
    }
}

private class SaveFailed(cause: Exception) : Exception(cause.message, cause)

private fun text(e: JsonElement?): String = stringOf(e).orEmpty()

/**
 * What a failed check says. The detail is the provider error's own message, which for an
 * HTTP failure is httpError's `"<host> 401: <body's error> — <key hint>"`, so the server's
 * words are never replaced by a guess. The lead is chosen by the error code, the vocabulary
 * every adapter shares, so it reads the same whichever provider failed.
 */
fun checkFailure(e: Throwable): CheckFailure {
    if (e is SaveFailed) return CheckFailure("That worked, but it could not be saved on this device.", e.message.orEmpty())
    val detail = e.message.orEmpty().ifEmpty { e.toString() }
    val lead = when ((e as? ProviderException)?.code) {
        ProviderErrorCode.AUTH -> "That key was refused. Check it was copied in full, and that it is a key for this provider."
        ProviderErrorCode.RATE_LIMIT -> "The provider is rate-limiting this key. Wait a moment and try again."
        ProviderErrorCode.OVERLOADED -> "The provider is overloaded right now. Try again in a moment."
        ProviderErrorCode.NETWORK -> "Could not reach the server. Check the address and that it is running."
        ProviderErrorCode.TIMEOUT -> "The server did not answer in time."
        ProviderErrorCode.BAD_RESPONSE -> "The server answered, but not in a way this app understands."
        ProviderErrorCode.CONFIG -> "That setup cannot be used."
        ProviderErrorCode.ABORTED -> "The check was cancelled."
        else -> "The check failed."
    }
    return CheckFailure(lead, detail)
}

/**
 * Whether the keyring can start an interview without onboarding: an active provider this
 * build offers, with what that provider needs. A hosted provider needs a key; a local one
 * needs a model (Ollama, LM Studio and a custom server all require one) and an address,
 * which only a custom server lacks a default for.
 */
fun hasUsableProvider(ring: JsonElement?, choices: List<ProviderChoice> = PROVIDER_CHOICES): Boolean {
    val active = text((ring as? JsonObject)?.get("active"))
    val choice = choices.firstOrNull { it.id == active } ?: return false
    val creds = credsFor(ring, active)
    return if (choice.needsKey) {
        text(creds["apiKey"]).isNotEmpty()
    } else {
        text(creds["model"]).isNotEmpty() && (choice.defaultBaseUrl != null || text(creds["baseUrl"]).isNotEmpty())
    }
}
