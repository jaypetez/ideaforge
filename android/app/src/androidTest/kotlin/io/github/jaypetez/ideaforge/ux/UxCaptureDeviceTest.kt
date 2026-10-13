package io.github.jaypetez.ideaforge.ux

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.DEVICE_TIMEOUT
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ScriptedProvider
import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.KeyringKey
import io.github.jaypetez.ideaforge.scriptedWalkthrough
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Opt-in development evidence. Normal connected/ATD runs retain the ordinary walkthrough.
 * A matrix requires an explicit SHA and Gradle's additional-test-output directory.
 *
 * Every real-app state uses MainActivity navigation and the existing scripted provider;
 * only voice presentations launch the labelled debug-only isolated component activity.
 * Each graph has its own private files and in-memory dummy encryption key. No user session,
 * keyring, preference, permission, microphone or real provider is read or changed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UxCaptureDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val app get() = target.applicationContext as IdeaForgeApp
    private var scenario: ActivityScenario<out Activity>? = null
    private var fixtureGraph: AppGraph? = null
    private var activity: Activity? = null
    private lateinit var fixtures: UxFixtures
    private lateinit var runDir: File
    private var nextGraph = 0

    @Test
    fun requestedUxEvidenceIsRenderedCompleteAndReachable() {
        val bundle = InstrumentationRegistry.getArguments()
        val args = bundle.keySet().associateWith { bundle.get(it).toString() }
        val request = UxCaptureRequest.from(args) ?: return
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }) {
            "requested UX evidence needs Gradle's additionalTestOutputDir"
        }
        fixtures = UxFixtures.load(target)
        val selected = if (request.mode == "walkthrough") emptyList() else request.states?.map(fixtures::state) ?: fixtures.states
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
            }
        }
        val walkthrough = if (request.states == null) listOf("seed", "chip", "result") else emptyList()
        val expected = matrix.flatMap { config ->
            selected.map { "${config.id}/${it.id}" } + walkthrough.map { "${config.id}/walkthrough-$it" }
        }.toSet()
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, File(outputPath, "ux"), expected)
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs()) { "could not create owned fixture run directory" }
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (state in selected) {
                        try {
                            prepare(state, config)
                            val before = SystemClock.elapsedRealtimeNanos()
                            val controls = navigate(state)
                            val elapsed = (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0
                            recorder.capture(
                                state, config, checkNotNull(activity), controls,
                                nativeSurface = nativeSurface(state),
                                nativeMapping = nativeMapping(state),
                                labNavigationMs = if (state.id == "library-100") elapsed else null,
                            )
                            if (state.id == "unavailable") {
                                error("Native SessionStore has no storage-read-unavailable presentation; this is the observed empty library, not an unavailable-status pass")
                            }
                        } catch (e: Throwable) {
                            recorder.failed(state, config, e, activity)
                        } finally {
                            closeFixture()
                        }
                    }
                    if (walkthrough.isNotEmpty()) {
                        val state = UxState("walkthrough", "interview", "real-app", null, null, null)
                        try {
                            prepare(state, config)
                            scriptedWalkthrough(compose, target, launch = ::launchMain) { frame ->
                                val captured = state.copy(id = "walkthrough-$frame", surface = if (frame == "result") "result" else "interview")
                                recorder.capture(captured, config, checkNotNull(activity), if (frame == "result") resultControls() else interviewControls())
                            }
                        } catch (e: Throwable) {
                            recorder.failedRun(e)
                            val keys = walkthrough.map { "${config.id}/walkthrough-$it" }.toSet()
                            for (key in UxEvidence.unrecorded(keys, recorder.recordedKeys)) {
                                recorder.failed(state.copy(id = key.substringAfter('/')), config, e, activity)
                            }
                        } finally {
                            closeFixture()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() },
                { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "could not delete owned fixture files: $runDir" } },
            )) { errors ->
                errors.forEach(recorder::failedRun)
                recorder.finish()
            }
        }
    }

    private fun prepare(state: UxState, config: UxConfiguration) = runBlocking {
        val context = FixtureContext(target, File(runDir, "graph-${++nextGraph}"))
        val script = ScriptedProvider.loadScript(target)
        val controlled = FixtureProvider(ScriptedProvider(script), state.id)
        val graph = AppGraph(
            context, clock = Clock { fixtures.now }, keyringKey = FixtureKey(),
            providerCheck = ProviderCheck { _, _ -> error("a UX fixture attempted a provider check") },
            providerSource = ProviderSource { controlled },
        )
        fixtureGraph = graph
        app.graph = graph
        check(graph.prefsStore.update { it.copy(theme = config.theme) })
        check(File(context.filesDir, "datastore/prefs.preferences_pb").isFile) { "fixture preferences escaped their owned directory" }
        if (state.id != "fresh") {
            graph.keyringStore.save(withCreds(emptyKeyring(), "groq", JsonObject(mapOf(
                "apiKey" to JsonPrimitive("ux_dummy_never_transmitted"),
            ))))
        }
        graph.keyringStore.load()
        if (state.id == "unavailable" || state.id == "save-failure") {
            // The files are ours. A non-directory sessions path makes reads unavailable and
            // a real atomic save throw, without touching any production store implementation.
            File(context.filesDir, "sessions").writeText("owned UX storage fault")
        }
        graph.sessionStore.load()
        for (session in fixtures.sessions(state)) check(graph.sessionStore.insert(session))
        if (state.phase == "complete") {
            for (session in fixtures.datasets.getValue("long")) check(graph.sessionStore.insert(session))
        }
    }

    private fun navigate(state: UxState): List<UxControl> {
        if (state.evidence == "isolated-component") {
            val intent = Intent(target, UxFixtureActivity::class.java)
                .putExtra("state", state.id).putExtra("theme", runBlocking { fixtureGraph!!.prefsStore.current().theme })
            val launched = ActivityScenario.launch<UxFixtureActivity>(intent)
            scenario = launched
            launched.onActivity { activity = it }
            await("ux-isolated-label")
            if (state.phase == "complete") {
                await(ResultTags.OUTPUT)
                return resultControls()
            }
            await("voice-stage")
            compose.onNodeWithTag("voice-levels").assertDoesNotExist()
            if (state.phase == "listening") {
                compose.onNodeWithTag("voice-no-level").assertExists()
                assertEquals(fixtures.text("answer"), nodeText("voice-caption"))
            }
            return buildList {
                if (state.phase == "listening") add(UxControl("voice-no-level", hasTestTag("voice-no-level"), actionRequired = false))
                addAll(listOf(
                tag(if (state.phase in setOf("paused", "blocked")) "voice-resume" else "voice-pause"),
                tag("voice-exit"),
                ))
            }
        }
        launchMain(scripted = state.id !in setOf("busy-turn", "writing", "synthesis-failure"))
        if (state.id == "fresh") {
            await("provider-groq")
            return listOf(tag("provider-groq"), tag("provider-ollama"))
        }
        await(BOTTOM_BAR)
        when (state.id) {
            "ready", "empty", "unavailable" -> {
                await(LibraryTags.ZERO)
                return listOf(tag(LibraryTags.NEW)) + navigationControls()
            }
            "library", "library-100" -> {
                await(LibraryTags.SEARCH)
                assertEquals(fixtures.sessions(state).size, fixtureGraph!!.sessionStore.sessions.value.size)
                if (state.id == "library-100") {
                    val oldest = fixtures.sessions(state).first()
                    val menu = LibraryTags.more(stringOf(oldest["id"])!!)
                    compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
                        .performScrollToNode(hasTestTag(menu))
                    compose.onNodeWithTag(menu).assertExists("the hundredth idea's menu remains reachable")
                }
                return listOf(tag(LibraryTags.NEW), tag(LibraryTags.SEARCH), tag(LibraryTags.ARCHIVED)) + navigationControls()
            }
            "library-archived" -> {
                await(LibraryTags.ARCHIVED)
                compose.onNodeWithTag(LibraryTags.ARCHIVED).performScrollTo().performClick()
                compose.waitUntil(DEVICE_TIMEOUT) {
                    compose.onAllNodes(hasTestTag(LibraryTags.CARD)).fetchSemanticsNodes().isNotEmpty()
                }
                return listOf(tag(LibraryTags.NEW), tag(LibraryTags.SEARCH), tag(LibraryTags.ARCHIVED)) + navigationControls()
            }
            "filtered" -> {
                await(LibraryTags.SEARCH)
                compose.onNodeWithTag(LibraryTags.SEARCH).performScrollTo().performTextInput("no matching UX fixture")
                hideKeyboard()
                await(LibraryTags.EMPTY)
                return listOf(tag(LibraryTags.SEARCH), tag(LibraryTags.NEW)) + navigationControls()
            }
            "library-long" -> {
                selectSession("ux-long-active", open = false)
                assertTrue("the long idea is active, not a failed completed result", stringOf(fixtureGraph!!.sessionStore.get("ux-long-active")!!["status"]) != "done")
                compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
                return listOf(textControl("continue", R.string.idea_continue), tag(LibraryTags.NEW)) + navigationControls()
            }
            "interview-long", "bank" -> {
                selectSession(requireNotNull(state.sessionId))
                await("question")
                if (state.id == "bank") compose.onNodeWithTag("degraded").assertExists()
                else assertEquals(fixtures.text("question"), nodeText("question"))
                return interviewControls() + if (state.id == "interview-long") {
                    (fixtures.text.getValue("chips") as kotlinx.serialization.json.JsonArray).indices.map { tag("chip-$it") }
                } else emptyList()
            }
            "result-long" -> {
                selectSession(requireNotNull(state.sessionId))
                await(ResultTags.OUTPUT)
                compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
                return resultControls()
            }
            "writing", "synthesis-failure" -> {
                selectSession(requireNotNull(state.sessionId))
                await("write-up")
                compose.onNodeWithTag("write-up").performClick()
                if (state.id == "writing") {
                    await("interview-busy")
                    await("busy-line")
                    assertEquals(target.getString(R.string.interview_writing), nodeText("busy-line"))
                    return interviewControls()
                }
                await(ResultTags.SYNTHESIS_FAILED)
                await(ResultTags.OUTPUT)
                val session = fixtureGraph!!.sessionStore.get(requireNotNull(state.sessionId))!!
                assertEquals("a failed synthesis was attempted and settled, not an active idea", "done", stringOf(session["status"]))
                return resultControls()
            }
            "busy-turn", "save-failure" -> {
                compose.onNode(navigationMatcher(R.string.nav_new_idea)).performClick()
                await("answer")
                compose.onNodeWithTag("answer").performTextInput(fixtures.text("answer"))
                hideKeyboard()
                compose.onNodeWithTag("send").performClick()
                if (state.id == "busy-turn") {
                    await("interview-busy")
                    await("busy-line")
                } else {
                    compose.waitUntil(DEVICE_TIMEOUT) {
                        !exists("interview-busy") && compose.onAllNodes(hasText(target.getString(
                            R.string.notice_interview_failed, "unused",
                        ).substringBefore("unused"), substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue("save failure did not secretly persist an idea", fixtureGraph!!.sessionStore.sessions.value.isEmpty())
                }
                return interviewControls()
            }
            else -> error("missing native navigation for requested UX state ${state.id}")
        }
    }

    private fun launchMain(scripted: Boolean = true) {
        val intent = Intent(target, MainActivity::class.java)
        if (scripted) intent.putExtra("provider", "scripted")
        val launched = ActivityScenario.launch<MainActivity>(intent)
        scenario = launched
        launched.onActivity { activity = it }
    }

    private fun selectSession(id: String, open: Boolean = true) {
        val session = checkNotNull(fixtureGraph!!.sessionStore.get(id))
        val title = sessionDisplayTitle(session)
        await(LibraryTags.SEARCH)
        compose.onNodeWithTag(LibraryTags.SEARCH).performScrollTo().performTextReplacement(title)
        hideKeyboard()
        val card = hasTestTag(LibraryTags.CARD) and hasText(title)
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST))).performScrollToNode(card)
        compose.onNode(card).performClick()
        await(LibraryTags.DETAIL)
        if (open) {
            val action = if (stringOf(session["status"]) == "done") R.string.idea_view_result else R.string.idea_continue
            compose.onNode(hasText(target.getString(action)) and hasClickAction()).performScrollTo().performClick()
        }
    }

    private fun interviewControls(): List<UxControl> = buildList {
        add(tag("answer")); add(tag("send")); add(tag("skip")); add(tag("voice-start"))
        if (exists("write-up")) add(tag("write-up"))
    }

    private fun resultControls() = listOf(
        tag(ResultTags.BACK), tag(ResultTags.SHARE), tag(ResultTags.COPY), tag(ResultTags.SAVE), tag(ResultTags.ASK_MORE),
    )

    private fun navigationControls() = listOf(
        UxControl("nav-ideas", navigationMatcher(R.string.nav_ideas)),
        UxControl("nav-new", navigationMatcher(R.string.nav_new_idea)),
        UxControl("nav-settings", navigationMatcher(R.string.nav_settings)),
    )

    private fun navigationMatcher(label: Int) =
        hasText(target.getString(label)) and hasAnyAncestor(hasTestTag(BOTTOM_BAR)) and hasClickAction()

    private fun textControl(name: String, label: Int) = UxControl(name, hasText(target.getString(label)) and hasClickAction())
    private fun tag(name: String) = UxControl(name, hasTestTag(name))
    private fun await(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), DEVICE_TIMEOUT)
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun nodeText(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun hideKeyboard() {
        scenario?.onActivity { current ->
            val input = current.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.hideSoftInputFromWindow(current.window.decorView.windowToken, 0)
            current.currentFocus?.clearFocus()
        }
        compose.waitUntil(DEVICE_TIMEOUT) { activity?.window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
    }

    private fun closeFixture() {
        val closing = scenario
        val graph = fixtureGraph
        scenario = null
        activity = null
        fixtureGraph = null
        UxEvidence.finish(null, listOf(
            { closing?.close() },
            { runBlocking { graph?.appScope?.coroutineContext?.get(Job)?.cancelAndJoin() } },
        )) {}
    }

    private fun nativeSurface(state: UxState): String = when {
        state.phase == "complete" -> "result"
        state.id == "fresh" -> "onboarding"
        state.id == "ready" -> "library"
        state.id == "library-long" -> "library-detail"
        state.id == "writing" -> "interview"
        else -> state.surface
    }

    private fun nativeMapping(state: UxState): String? = UxFixtureActivity.nativeMapping(state) ?: when (state.id) {
        "fresh" -> "Native first run is provider onboarding, not the web Start screen."
        "ready" -> "Native with a ready provider starts at Ideas; it has no separate Start screen."
        "writing" -> "Native write-up stays in the interview with a Writing busy line until it settles."
        "unavailable" -> "Native currently presents an unreadable/non-directory session store as an empty library; no unavailable status exists."
        else -> null
    }
}

private class FixtureContext(base: Context, root: File) : ContextWrapper(base) {
    private val files = File(root, "files").apply { check(mkdirs()) }
    private val noBackup = File(root, "no-backup").apply { check(mkdirs()) }
    override fun getFilesDir(): File = files
    override fun getNoBackupFilesDir(): File = noBackup
    // DataStore resolves applicationContext.filesDir, not the wrapper's direct filesDir.
    override fun getApplicationContext(): Context = this
}

private class FixtureKey : KeyringKey {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    override fun existing(slot: Int): SecretKey = key
    override fun getOrCreate(slot: Int): SecretKey = key
    override fun delete(slot: Int) {}
}

private class FixtureProvider(private val scripted: ScriptedProvider, private val state: String) : Provider {
    override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult {
        if (state == "busy-turn" || state == "writing" && modelTier == "complex") awaitCancellation()
        if (state == "synthesis-failure" && modelTier == "complex") throw ProviderException("bad_response", "Injected UX synthesis failure")
        return scripted.sampleJson(parts, modelTier, cache)
    }
}
