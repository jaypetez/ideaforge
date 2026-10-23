package io.github.jaypetez.ideaforge

import android.speech.SpeechRecognizer
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.jaypetez.ideaforge.data.AndroidKeystoreKey
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.InferenceConsent
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
import io.github.jaypetez.ideaforge.voice.AndroidWavPlayer
import io.github.jaypetez.ideaforge.voice.WhisperMicrophone
import io.github.jaypetez.ideaforge.voice.HostedSpeaker
import io.github.jaypetez.ideaforge.voice.hostedSynth
import io.github.jaypetez.ideaforge.voice.whisperFrom
import io.github.jaypetez.ideaforge.voice.Speaker
import io.github.jaypetez.ideaforge.voice.DriveController
import io.github.jaypetez.ideaforge.voice.DriveService
import io.github.jaypetez.ideaforge.voice.DriveServiceControl
import io.github.jaypetez.ideaforge.voice.Recognizer
import io.github.jaypetez.ideaforge.voice.TextToSpeechSpeaker
import io.github.jaypetez.ideaforge.voice.TtsEngine
import io.github.jaypetez.ideaforge.voice.androidRecognizerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

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
    preferences: PrefsStore? = null,
    sessionStorage: SessionStore? = null,
    ttsEngine: TtsEngine? = null,
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

    val sessionStore: SessionStore by lazy { sessionStorage ?: SessionStore(File(context.filesDir, "sessions")) }

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
        preferences ?: PrefsStore.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("prefs")
        }
    }

    val inferenceConsent: InferenceConsent by lazy {
        InferenceConsent(
            prefsStore, ring = { keyringStore.state.value.ring }, scope = appScope,
            onRetire = { message -> mainScope.launch { driveController.blockForInference(message) } },
        )
    }

    /**
     * One lazy native engine for the Settings catalogue and speech. Listing voices neither
     * speaks nor configures the next utterance; voice and pace are read at each speak.
     */
    val nativeTtsEngine: TtsEngine by lazy { ttsEngine ?: AndroidTtsEngine(context) }

    val nativeSpeaker: Speaker by lazy {
        TextToSpeechSpeaker(nativeTtsEngine, AndroidAudioFocus(context), prefs = { prefsStore.current() })
    }

    /**
     * What actually speaks: OpenAI after consent and a passing check (#93), the device voice
     * otherwise, and the device voice again, visibly, when a hosted request fails live.
     */
    val hostedSpeaker: HostedSpeaker by lazy {
        HostedSpeaker(
            nativeSpeaker, AndroidWavPlayer(), AndroidAudioFocus(context),
            prefs = { prefsStore.current() },
            // Read per chunk too: after Forget the ring is empty, so the gate is closed.
            synth = { hostedSynth(keyringStore.state.value.ring, prefsStore.current()) },
        )
    }

    val speaker: Speaker get() = hostedSpeaker

    /** Main-thread scope for the platform recogniser and the drive loop (both main-only). */
    val mainScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /** Native speech capture. Lazy: nothing binds a recognition service until voice starts. */
    var recognizer: Recognizer? = null
        get() = field ?: Recognizer(androidRecognizerFactory(context), mainScope).also { field = it }

    /**
     * The hands-free drive. In the graph rather than a ViewModel so it outlives the screen;
     * DriveService keeps the process in the foreground while it runs. Tests replace the
     * recogniser and speaker through [recognizer] and [driveSpeaker] before first use.
     */
    var driveSpeaker: Speaker? = null
    var driveService: DriveServiceControl = DriveService.control(context)

    /** Tests hand in a controller built on their own dispatcher and fakes. */
    @VisibleForTesting
    internal var driveOverride: DriveController? = null

    val driveController: DriveController get() = driveOverride ?: defaultDrive

    private val defaultDrive: DriveController by lazy {
        DriveController(
            sessionStore, sessionWorker, clock,
            ring = { keyringStore.state.value.ring },
            providers = { providerSource },
            writes = appScope,
            main = mainScope,
            recognizerSource = { recognizer!! },
            speaker = { driveSpeaker ?: speaker },
            service = object : DriveServiceControl {
                override fun start() = driveService.start()
                override fun stop() = driveService.stop()
            },
            inference = inferenceConsent,
            trigger = { prefsStore.current().effectiveTrigger },
            recognitionAvailable = { SpeechRecognizer.isRecognitionAvailable(context) },
            whisper = {
                val generation = keyringStore.generation.value
                whisperFrom(
                    keyringStore.state.value.ring, prefsStore.current(),
                    stillAllowed = {
                        keyringStore.generation.value == generation && prefsStore.current().hostedTranscriptionAllowed
                    },
                ) { WhisperMicrophone() }
            },
        )
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
        graph.appScope.launch {
            try {
                graph.sessionStore.load()
            } catch (e: IOException) {
                Log.w("IdeaForge", "Could not preload the ideas library", e)
            } catch (e: SecurityException) {
                Log.w("IdeaForge", "Access to the ideas library was denied", e)
            }
        }
        // Never throws: an unreadable keyring reads as none, and says so in its state.
        graph.appScope.launch { graph.keyringStore.load() }
        // Shared exports older than a day are no longer anyone's to read (ResultActions).
        graph.appScope.launch { ResultActions.pruneShares(this@IdeaForgeApp, graph.clock.now()) }
    }
}
