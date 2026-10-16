package io.github.jaypetez.ideaforge.data

import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.providers.InferenceDestination
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
import io.github.jaypetez.ideaforge.providers.inferenceDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.UUID

fun inferenceConfig(ring: JsonElement): ProviderConfig {
    val active = stringOf(ring["active"]).orEmpty()
    val record = credsFor(ring, active)
    fun field(name: String) = stringOf(record[name])?.takeIf { it.isNotEmpty() }
    return ProviderConfig(active, field("apiKey").orEmpty(), field("baseUrl"), field("model"), field("wrapModel"))
}

data class InferencePermit(val destination: InferenceDestination, val grant: String, val revision: Long)

data class InferenceConsentState(
    val kind: String? = null,
    val destination: String? = null,
    val revision: Long = 0,
    val blocked: Boolean = false,
    val problem: String? = null,
    val permit: InferencePermit? = null,
)

/** Device-local authorization, independent of credentials and of microphone/STT/TTS consent. */
class InferenceConsent(
    private val prefs: PrefsStore,
    private val ring: () -> JsonElement,
    private val scope: CoroutineScope,
    private val onRetire: (String) -> Unit = {},
    private val newGrant: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(InferenceConsentState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var pendingWithdrawal: Job? = null

    fun allowed(destination: InferenceDestination?, preferences: Prefs): Boolean {
        val current = state.value
        return destination != null && !current.blocked
            && (current.destination == null || current.destination == destination.id)
            && preferences.inferenceDestination == destination.id && preferences.inferenceGrant.isNotEmpty()
    }

    /** Invalid keystrokes do not become a destination; a kind switch always retires the old one. */
    fun select(kind: String, destination: String?): Boolean {
        val changed: Boolean
        synchronized(lock) {
            val previous = state.value
            changed = previous.kind != null && (previous.kind != kind
                || (destination != null && previous.destination != null && destination != previous.destination))
            _state.value = previous.copy(
                kind = kind,
                destination = destination ?: if (changed) null else previous.destination,
            )
        }
        if (changed) withdraw("The provider destination changed. Review and agree again before continuing.")
        return changed
    }

    fun withdraw(message: String = "Inference agreement withdrawn. Review and agree again before continuing."): Job {
        val revision = retire(message)
        val job = scope.launch {
            val saved = prefs.update {
                if (state.value.revision != revision) it
                else it.copy(inferenceDestination = state.value.destination.orEmpty(), inferenceGrant = "")
            }
            if (!saved && state.value.revision == revision) {
                _state.update { it.copy(problem = "Inference is blocked, but withdrawal could not be saved. Check device storage before restarting.") }
            }
        }
        pendingWithdrawal = job
        return job
    }

    suspend fun accept(destination: InferenceDestination, owns: () -> Boolean = { true }): InferencePermit {
        val revision = state.value.revision
        pendingWithdrawal?.join()
        if (state.value.revision != revision) throw denied("The provider changed. Review the agreement again.")
        if (!owns() || state.value.destination?.let { it != destination.id } == true
            || recipient().id != destination.id) throw denied("The provider changed. Review the agreement again.")
        val previous = read()
        if (allowed(destination, previous)) return require()
        val grant = newGrant()
        val saved = prefs.update {
            if (state.value.revision != revision || !owns()) it
            else it.copy(inferenceDestination = destination.id, inferenceGrant = grant)
        }
        if (!saved) {
            retire("The inference agreement could not be saved. Interview text will not be sent; check device storage and retry.")
            throw denied(state.value.problem!!)
        }
        if (state.value.revision != revision || !owns() || recipient().id != destination.id) {
            throw denied("The provider changed while saving the agreement. Review it again.")
        }
        val stored = read()
        if (stored.inferenceDestination != destination.id || stored.inferenceGrant != grant) {
            retire("The saved inference agreement could not be verified. Interview text will not be sent.")
            throw denied(state.value.problem!!)
        }
        synchronized(lock) {
            if (state.value.revision != revision || !owns()) throw denied("The provider changed. Review the agreement again.")
            _state.update { it.copy(blocked = false, problem = null) }
        }
        return require()
    }

    suspend fun require(expected: InferencePermit? = null): InferencePermit {
        val revision = state.value.revision
        val destination = recipient()
        val preferences = read()
        val current = state.value
        if (current.revision != revision || !allowed(destination, preferences)
            || (expected != null && (expected.destination.id != destination.id
                || expected.grant != preferences.inferenceGrant || expected.revision != current.revision))) {
            throw denied(current.problem ?: "Review and agree to send interview text to this provider in Settings before continuing.")
        }
        val permit = InferencePermit(destination, preferences.inferenceGrant, current.revision)
        synchronized(lock) {
            if (state.value.revision != revision || state.value.blocked) {
                throw denied("The provider changed. Review the agreement again.")
            }
            if (state.value.permit != permit) _state.update { it.copy(permit = permit) }
        }
        return permit
    }

    fun current(expected: InferencePermit): Boolean {
        val current = state.value
        return !current.blocked && current.revision == expected.revision
            && (current.destination == null || current.destination == expected.destination.id)
    }

    private fun recipient(): InferenceDestination = try {
        inferenceDestination(inferenceConfig(ring()))
    } catch (error: ProviderException) {
        throw denied("Choose a usable provider and review its interview-text agreement in Settings.", error)
    }

    private suspend fun read(): Prefs = try {
        prefs.inferenceCurrent()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        unreadable(error)
    } catch (error: SecurityException) {
        unreadable(error)
    } catch (error: ClassCastException) {
        unreadable(error)
    } catch (error: IllegalStateException) {
        unreadable(error)
    }

    private fun unreadable(error: Exception): Nothing {
        retire("Device preferences could not be read. Interview text will not be sent; check device storage.")
        throw denied(state.value.problem!!, error)
    }

    private fun retire(message: String): Long {
        val revision = synchronized(lock) {
            _state.update { it.copy(revision = it.revision + 1, blocked = true, problem = message, permit = null) }
            state.value.revision
        }
        onRetire(message)
        return revision
    }

    private fun denied(message: String, cause: Throwable? = null) =
        ProviderException(ProviderErrorCode.CONSENT, message, cause)
}
