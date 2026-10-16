package io.github.jaypetez.ideaforge.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
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
    /**
     * "system", "light" or "dark": the same key and values the web stores in src/store/prefs.js
     * (`theme`, validated by themePreference). Anything else reads as "system", so a value from
     * a newer build cannot leave the
     * app in a theme it does not know.
     */
    val theme: String = THEME_SYSTEM,
    /**
     * Consent to send recorded audio to the transcription vendor, for the Whisper fallback
     * that runs only when the native recogniser is unavailable (#93). Android-only: the web's
     * record path is the user's explicit choice of dictation engine. Cleared by Forget.
     */
    val hostedTranscriptionAllowed: Boolean = false,
    val inferenceDestination: String = "",
    val inferenceGrant: String = "",
) {
    /** The trigger the driving loop listens for (src/core/driving.js DEFAULTS.trigger). */
    val effectiveTrigger: String get() = trigger.ifBlank { DEFAULT_TRIGGER }

    /** speechPreferences() in prefs.js: a voice id capped at 512, a rate in range or 1. */
    fun validated(): Prefs = copy(
        speechVoice = speechVoice.take(MAX_VOICE),
        speechRate = if (speechRate.isFinite() && speechRate in MIN_RATE..MAX_RATE) speechRate else 1.0,
        theme = theme.takeIf { it in THEMES } ?: THEME_SYSTEM,
        inferenceDestination = inferenceDestination.takeIf { it.length <= 4096 }.orEmpty(),
        inferenceGrant = inferenceGrant.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) }.orEmpty(),
    )

    companion object {
        const val DEFAULT_TRIGGER = io.github.jaypetez.ideaforge.core.DEFAULT_TRIGGER
        const val MAX_VOICE = 512
        const val MIN_RATE = 0.75
        const val MAX_RATE = 1.25
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val THEMES = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
    }
}

class PrefsStore(private val store: DataStore<Preferences>) {
    companion object {
        /**
         * The one way to open the prefs file. A corrupt file is replaced with defaults, as
         * loadPrefs() falls back on the web and the next save overwrites; without the
         * handler every read fails and every update fails for ever.
         */
        fun create(scope: CoroutineScope, produceFile: () -> java.io.File): PrefsStore = PrefsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            ),
        )
    }

    private object Keys {
        val trigger = stringPreferencesKey("trigger")
        val handsFree = booleanPreferencesKey("handsFree")
        val speechVoice = stringPreferencesKey("speechVoice")
        val speechRate = doublePreferencesKey("speechRate")
        val hostedSpeechAllowed = booleanPreferencesKey("hostedSpeechAllowed")
        val hostedTranscriptionAllowed = booleanPreferencesKey("hostedTranscriptionAllowed")
        val theme = stringPreferencesKey("theme")
        val inferenceDestination = stringPreferencesKey("inferenceDestination")
        val inferenceGrant = stringPreferencesKey("inferenceGrant")
    }

    /**
     * Ordinary preferences can use defaults. Inference authorization uses
     * [inferenceCurrent], which must not turn a read failure into permission.
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
        hostedTranscriptionAllowed = p[Keys.hostedTranscriptionAllowed] == true,
        theme = p[Keys.theme] ?: Prefs.THEME_SYSTEM,
        inferenceDestination = p[Keys.inferenceDestination] ?: "",
        inferenceGrant = p[Keys.inferenceGrant] ?: "",
    ).validated()

    suspend fun current(): Prefs = prefs.first()

    suspend fun inferenceCurrent(): Prefs = read(store.data.first())

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
            p[Keys.hostedTranscriptionAllowed] = next.hostedTranscriptionAllowed
            p[Keys.theme] = next.theme
            p[Keys.inferenceDestination] = next.inferenceDestination
            p[Keys.inferenceGrant] = next.inferenceGrant
        }
        true
    } catch (e: java.io.IOException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
