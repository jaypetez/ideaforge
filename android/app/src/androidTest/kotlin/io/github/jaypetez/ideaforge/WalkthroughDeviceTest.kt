package io.github.jaypetez.ideaforge

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.ui.onboarding.hasUsableProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The README walkthrough, end to end on a real device: MainActivity launched with
 * `--es provider scripted`, the interview answered through the screen (typed answers and a
 * chip), Write it up, and the Result screen showing the export. WalkthroughVectorTest proves
 * the same run byte for byte under Robolectric; this proves the real app, on a real Android
 * runtime, actually gets there. CI runs it on a Gradle managed emulator
 * (.github/workflows/android-device.yml).
 *
 * A degraded interview looks like a healthy one: a failed model call is answered from the
 * built-in checklist and the run still reaches a result. So every question is compared with
 * the scripted reply for its turn, and the checklist marker must never appear.
 *
 * Onboarding is passed by seeding the keyring before launch, through the app's own
 * KeyringStore, rather than by any hook in the app. On a device that already has a usable
 * provider nothing is written; otherwise the original keyring is put back afterwards, and the
 * idea this run created is deleted, so a shared emulator is left as it was found. That
 * cleanup is @After, so it needs the instrumentation alive: an app crash kills the
 * instrumentation process with it, and then neither runs. A device that had no usable
 * provider is left holding the dummy Groq key, and the interrupted idea stays in the library.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WalkthroughDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (target.applicationContext as IdeaForgeApp).graph

    private var scenario: ActivityScenario<MainActivity>? = null
    private var restore: (suspend () -> Unit)? = null
    private var restoreProvider: (() -> Unit)? = null
    private var restorePreferences: (suspend () -> Unit)? = null
    // Null until @Before has read the store: a throw before then leaves nothing to delete.
    private var knownBefore: Set<String>? = null

    @Before
    fun seedAUsableProvider() = runBlocking {
        val providers = graph.providerSource
        restoreProvider = { graph.providerSource = providers }
        val keyring = graph.keyringStore
        val state = keyring.load()
        check(state.status != KeyringState.Status.UNREADABLE) { "the keyring on this device is unreadable: ${state.problem}" }
        if (!hasUsableProvider(state.ring)) {
            val original = state.ring
            val hadOne = state.status == KeyringState.Status.STORED
            // Any provider that counts as usable will do: the scripted provider replaces the
            // interview's model, so this key is never sent anywhere.
            keyring.save(withCreds(original, "groq", JsonObject(mapOf("apiKey" to JsonPrimitive("gsk_instrumented_walkthrough")))))
            restore = { if (hadOne) keyring.save(original) else keyring.forget() }
        }
        graph.sessionStore.load()
        val originalPreferences = graph.prefsStore.current()
        restorePreferences = { check(graph.prefsStore.update { originalPreferences }) }
        graph.inferenceConsent.accept(io.github.jaypetez.ideaforge.providers.inferenceDestination(
            io.github.jaypetez.ideaforge.data.inferenceConfig(graph.keyringStore.state.value.ring)))
        knownBefore = graph.sessionStore.knownIds()
    }

    // The keyring restore runs whatever else throws: closing the activity and deleting the
    // idea are tidiness, but a dummy key left behind changes how the device's app opens.
    @After
    fun leaveTheDeviceAsFound() = runBlocking {
        try {
            runCatching { scenario?.close() }
            knownBefore?.let { before ->
                runCatching {
                    for (id in graph.sessionStore.knownIds() - before) runCatching { graph.sessionStore.delete(id) }
                }
            }
        } finally {
            try {
                restore?.invoke()
            } finally {
                try { restorePreferences?.invoke() } finally { restoreProvider?.invoke() }
            }
        }
        Unit
    }

    @Test
    fun theScriptedWalkthroughReachesTheResultScreen() {
        scriptedWalkthrough(compose, target, launch = {
            scenario = ActivityScenario.launch(Intent(target, MainActivity::class.java).putExtra("provider", "scripted"))
        })
    }
}
