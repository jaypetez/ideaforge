package io.github.jaypetez.ideaforge

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.IdGen
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.SessionWorker
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
) {
    /** Outlives every screen; work that must finish (a save) runs here, not in a ViewModel. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val idGen: IdGen by lazy { IdGen(clock) }

    val sessionStore: SessionStore by lazy { SessionStore(File(context.filesDir, "sessions")) }

    val sessionWorker: SessionWorker by lazy { SessionWorker() }

    // One DataStore per file per process; DataStore throws if a second instance opens it.
    val prefsStore: PrefsStore by lazy {
        PrefsStore.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("prefs")
        }
    }
}

/** Holds the [AppGraph]; registered as the application class in the manifest. */
class IdeaForgeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // The library index fills in the background; the shell renders an empty list first.
        graph.appScope.launch { graph.sessionStore.load() }
    }
}
