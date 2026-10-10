package io.github.jaypetez.ideaforge.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Device preferences that are not secrets; the twin of src/store/prefs.js, same defaults
 * and same validation. Not the keyring: "Forget my key" must not reset the trigger word.
 */
data class Prefs(
    /** The word that ends a spoken answer. Empty means "use the built-in default". */
    val trigger: String = "",
    /** Whether hands-free was on last time, so a regular driver is not re-ticking a box. */
    val handsFree: Boolean = false,
    val speechVoice: String = "",
    val speechRate: Double = 1.0,
    val hostedSpeechAllowed: Boolean = false,
) {
    /** The trigger the driving loop listens for (src/core/driving.js DEFAULTS.trigger). */
    val effectiveTrigger: String get() = trigger.ifBlank { DEFAULT_TRIGGER }

    /** speechPreferences() in prefs.js: a voice id capped at 512, a rate in range or 1. */
    fun validated(): Prefs = copy(
        speechVoice = speechVoice.take(MAX_VOICE),
        speechRate = if (speechRate.isFinite() && speechRate in MIN_RATE..MAX_RATE) speechRate else 1.0,
    )

    companion object {
        const val DEFAULT_TRIGGER = "over"
        const val MAX_VOICE = 512
        const val MIN_RATE = 0.75
        const val MAX_RATE = 1.25
    }
}

class PrefsStore(private val store: DataStore<Preferences>) {
    private object Keys {
        val trigger = stringPreferencesKey("trigger")
        val handsFree = booleanPreferencesKey("handsFree")
        val speechVoice = stringPreferencesKey("speechVoice")
        val speechRate = doublePreferencesKey("speechRate")
        val hostedSpeechAllowed = booleanPreferencesKey("hostedSpeechAllowed")
    }

    /**
     * A preference failing to load must never be the reason an interview cannot start, so a
     * read error yields the defaults rather than an exception.
     */
    val prefs: Flow<Prefs> = store.data
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map(::read)

    private fun read(p: Preferences): Prefs = Prefs(
        trigger = p[Keys.trigger] ?: "",
        handsFree = p[Keys.handsFree] ?: false,
        speechVoice = p[Keys.speechVoice] ?: "",
        speechRate = p[Keys.speechRate] ?: 1.0,
        hostedSpeechAllowed = p[Keys.hostedSpeechAllowed] == true,
    ).validated()

    suspend fun current(): Prefs = prefs.first()

    /**
     * Merge and write, validated. Returns false on a failed write, which callers handling
     * consent (hostedSpeechAllowed) use to report it, as savePrefs does on the web.
     */
    suspend fun update(transform: (Prefs) -> Prefs): Boolean = try {
        store.edit { p ->
            val next = transform(read(p)).validated()
            p[Keys.trigger] = next.trigger
            p[Keys.handsFree] = next.handsFree
            p[Keys.speechVoice] = next.speechVoice
            p[Keys.speechRate] = next.speechRate
            p[Keys.hostedSpeechAllowed] = next.hostedSpeechAllowed
        }
        true
    } catch (e: java.io.IOException) {
        false
    }
}
