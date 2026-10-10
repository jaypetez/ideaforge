package io.github.jaypetez.ideaforge

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.jaypetez.ideaforge.data.AndroidKeystoreKey
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.KeyringKey
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.result.ResultActions
import io.github.jaypetez.ideaforge.ui.settings.BackupJobs
import io.github.jaypetez.ideaforge.voice.AndroidAudioFocus
import io.github.jaypetez.ideaforge.voice.AndroidTtsEngine
import io.github.jaypetez.ideaforge.voice.Speaker
import io.github.jaypetez.ideaforge.voice.TextToSpeechSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency injection: every long-lived collaborator, built once and lazily.
 *
 * This is the Android form of the web app's rule that the runtime never reaches for a clock
 * or a network: [clock] is the only place wall time is read, and everything that needs "now"
 * takes it from here (or from a test's fake) as an argument. No DI framework, for the same
 * reason the web app has no dependencies: there is nothing here a constructor cannot do.
 */
class AppGraph(
    private val context: Context,
    val clock: Clock = Clock.System,
    // Tests replace this: Robolectric has no AndroidKeyStore. Constructing the default
    // touches nothing; the Keystore is first reached when a keyring is read or written.
    private val keyringKey: KeyringKey = AndroidKeystoreKey(),
    // The network call onboarding makes. Tests hand in a fake, so no test opens a socket.
    val providerCheck: ProviderCheck = ProviderCheck.Default,
    providerSource: ProviderSource = ProviderSource.FromKeyring,
) {
    /**
     * Builds the interview's model provider from the keyring. Tests hand in a scripted one;
     * a debug build launched with `--es provider scripted` swaps in the walkthrough replay
     * (see DebugProviders, which a release build compiles to nothing).
     */
    @Volatile
    var providerSource: ProviderSource = providerSource

    /** Outlives every screen; work that must finish (a save) runs here, not in a ViewModel. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val idGen: IdGen by lazy { IdGen(clock) }

    val sessionStore: SessionStore by lazy { SessionStore(File(context.filesDir, "sessions")) }

    val sessionWorker: SessionWorker by lazy { SessionWorker() }

    /** Backup import/export and its state: outlives the settings screen (see BackupJobs). */
    val backupJobs: BackupJobs by lazy { BackupJobs(appScope) }

    /**
     * The encrypted keyring, in noBackupFilesDir and deliberately outside the sessions
     * directory: backups contain sessions and nothing else, and anything that ever walks the
     * sessions directory to build one cannot sweep the keyring up with them.
     */
    val keyringStore: KeyringStore by lazy {
        KeyringStore(File(context.noBackupFilesDir, KeyringStore.FILE_NAME), keyringKey)
    }

    // One DataStore per file per process; DataStore throws if a second instance opens it.
    val prefsStore: PrefsStore by lazy {
        PrefsStore.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("prefs")
        }
    }

    /**
     * Native speech output, with the voice and pace read from prefs at each speak. Lazy, and
     * the engine inside it is lazy too: nothing binds TextToSpeech until something talks.
     */
    val speaker: Speaker by lazy {
        TextToSpeechSpeaker(AndroidTtsEngine(context), AndroidAudioFocus(context), prefs = { prefsStore.current() })
    }
}

/** Holds the [AppGraph]; registered as the application class in the manifest. */
class IdeaForgeApp : Application() {
    lateinit var graph: AppGraph
        // Tests swap in a graph with a software key and a fake provider check before launch.
        @VisibleForTesting internal set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // The library index fills in the background; the shell renders an empty list first.
        graph.appScope.launch { graph.sessionStore.load() }
        // Never throws: an unreadable keyring reads as none, and says so in its state.
        graph.appScope.launch { graph.keyringStore.load() }
        // Shared exports older than a day are no longer anyone's to read (ResultActions).
        graph.appScope.launch { ResultActions.pruneShares(this@IdeaForgeApp, graph.clock.now()) }
    }
}
