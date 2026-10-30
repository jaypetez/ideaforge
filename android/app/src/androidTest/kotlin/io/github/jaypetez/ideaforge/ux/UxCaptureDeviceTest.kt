package io.github.jaypetez.ideaforge.ux

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.DEVICE_TIMEOUT
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ScriptedProvider
import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.DIMENSION_IDS
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.buildBackup
import io.github.jaypetez.ideaforge.core.renameSession
import io.github.jaypetez.ideaforge.core.MAX_SESSION_NAME
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.runtime.Provider
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.runtime.SampleResult
import io.github.jaypetez.ideaforge.core.sessionDisplayTitle
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.Clock
import io.github.jaypetez.ideaforge.data.KeyringKey
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.scriptedWalkthrough
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import io.github.jaypetez.ideaforge.ui.NAVIGATION_RAIL
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.ui.result.exportFor
import io.github.jaypetez.ideaforge.ui.result.DocumentSink
import io.github.jaypetez.ideaforge.ui.result.ContentResolverSink
import io.github.jaypetez.ideaforge.ui.settings.SettingsTags
import io.github.jaypetez.ideaforge.ui.settings.HostedSpeechTags
import io.github.jaypetez.ideaforge.ui.settings.BackupTags
import io.github.jaypetez.ideaforge.ui.voice.voicePermissionsHeld
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
import io.github.jaypetez.ideaforge.voice.DriveServiceControl
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.Speaker
import io.github.jaypetez.ideaforge.voice.VoiceMode
import io.github.jaypetez.ideaforge.voice.DeviceVoice
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.TtsEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Opt-in development evidence. Normal connected/ATD runs retain the ordinary walkthrough.
 * A matrix requires an explicit SHA and Gradle's additional-test-output directory.
 *
 * Every real-app state uses MainActivity navigation and the existing scripted provider;
 * only voice presentations launch the labelled debug-only isolated component activity.
 * Each graph has its own private files and in-memory dummy encryption key. No user session,
 * keyring, preference, microphone or real provider is read or changed. Permission state
 * is never changed; the new-entry recovery case checks that a permission is ungranted,
 * then cancels the app rationale without launching an OS request.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UxCaptureDeviceTest {
    @get:Rule
    val compose = if (listOf("playListingProbe", "uxMakeoverProbe", "uxReportingProbe", "uxDeviceVoicesProbe", "uxDictationProbe", "uxIdeasProbe").any {
        InstrumentationRegistry.getArguments().getString(it) == "true"
    }) {
        // Queue new capture effects; the legacy unconfined dispatcher can re-enter layout.
        androidx.compose.ui.test.junit4.v2.createEmptyComposeRule()
    } else createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val app get() = target.applicationContext as IdeaForgeApp
    private var scenario: ActivityScenario<out Activity>? = null
    private var fixtureGraph: AppGraph? = null
    private var fixturePreferencesScope: CoroutineScope? = null
    private var activity: Activity? = null
    private lateinit var fixtures: UxFixtures
    private lateinit var runDir: File
    private var nextGraph = 0
    private var heldLibraryRead: CountDownLatch? = null
    private var libraryReadEntered: CountDownLatch? = null
    private var heldVoiceInit: CompletableDeferred<Boolean>? = null
    private val walkthroughFrames = listOf("seed", "chip", "result")
    private val libraryExportFrames = listOf("ideas-export-menu", "ideas-export-long", "ideas-export-cancelled",
        "ideas-export-error", "ideas-export-saved")

    @Test
    fun failedShareVerificationAndChooserLaunchStillCleanOnlyOwnedFiles() {
        val shares = File(target.cacheDir, io.github.jaypetez.ideaforge.ui.result.ResultActions.SHARE_DIR)
        val hadDirectory = shares.isDirectory
        check(shares.isDirectory || shares.mkdirs())
        val prefix = "ux207-cleanup-${SystemClock.elapsedRealtimeNanos()}"
        val prior = File(shares, "$prefix-prior").apply { check(mkdir()); resolve("prior.md").writeText("keep prior export") }
        try {
            for (chooserFails in listOf(false, true)) {
                val created = File(shares, "$prefix-$chooserFails")
                val failure = runCatching {
                    UxSystemActions(instrumentation, target, target.cacheDir).use { system ->
                        if (android.os.Build.VERSION.SDK_INT >= 34) system.requireCommandTransport()
                        system.share {
                            check(created.mkdir())
                            created.resolve("result.md").writeText("truncated")
                            if (chooserFails) error("scripted chooser launch failed")
                        }
                        system.requireShared("complete canonical markdown")
                    }
                }.exceptionOrNull()
                assertTrue("the intended chooser/content fault remains a failure", failure is IllegalStateException)
                assertTrue("even a failed share removes its registered files", !created.exists())
                assertEquals("prior exports remain untouched", "keep prior export", prior.resolve("prior.md").readText())
            }
        } finally {
            check(prior.deleteRecursively())
            if (!hadDirectory) check(shares.delete())
        }
    }

    @Test
    fun requestedNativeMakeoverAcceptance() {
        val bundle = InstrumentationRegistry.getArguments()
        if (bundle.getString("uxMakeoverProbe") != "true") {
            require(bundle.getString("class")?.contains("#requestedNativeMakeoverAcceptance") != true) {
                "the selected makeover acceptance requires uxMakeoverProbe=true"
            }
            return
        }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "makeover acceptance has a fixed complete inventory" }
        val output = File(requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }), "ux-makeover")
        fixtures = UxFixtures.load(target)
        val windows = when (request.window) {
            "compact" -> listOf("compact", "compact-landscape")
            "expanded" -> listOf("expanded")
            else -> listOf("compact", "compact-landscape", "expanded")
        }
        val sizes = mapOf("compact" to (411 to 914), "compact-landscape" to (640 to 400), "expanded" to (1280 to 900))
        val matrix = windows.flatMap { window -> listOf("light", "dark").flatMap { theme ->
            listOf(1f, 1.3f, 2f).map { scale -> UxConfiguration(window, theme, scale,
                requestedWidthDp = sizes.getValue(window).first, requestedHeightDp = sizes.getValue(window).second) }
        } }
        val states = listOf("library", "library-100", "filtered", "library-long", "interview-ime", "bank",
            "busy-turn", "save-failure", "synthesis-failure", "result-long",
            "voice-listening", "voice-paused", "voice-recovering", "voice-blocked", "voice-notifications-off")
        val allFontStates = setOf("library", "library-long", "interview-ime", "bank", "save-failure", "result-long", "voice-listening")
        fun statesFor(config: UxConfiguration) = states.filter { config.fontScale == 2f || it in allFontStates }
        val journeyFrames = listOf("fresh", "provider-checking", "provider-error", "ready-choice", "voice-rationale", "typed-seed",
            "chip", "result", "save-cancel", "share-cancel", "saved-markdown", "copied-markdown", "ask-more",
            "controller-paused", "controller-exit", "wrap", "skipped", "theme", "forget-dialog", "forget-cancel",
            "backup", "import-copy", "import-failure")
        val environment = UxDeviceEnvironment(instrumentation.uiAutomation)
        check(environment.navigationModes.toSet() == setOf("threebutton", "gestural")) {
            "acceptance needs installed gesture and three-button navigation overlays"
        }
        val navigationMatrix = matrix.filter { it.fontScale == 2f }.flatMap { config ->
            environment.navigationModes.map { config.copy(navigationMode = it) }
        }
        val expected = matrix.flatMap { config ->
            (statesFor(config) + if (config.fontScale == 2f) listOf("library-menu", "delete-dialog", "delete-cancel")
                else emptyList()).map { "${config.id}/$it" } +
                if (config.fontScale == 1f && config.window != "compact-landscape") {
                    journeyFrames.map { "${config.id}/journey-$it" } +
                        listOf("${config.id}/journey-rotated", "${config.id}/library-rotated")
                } else emptyList()
        }.toSet() + navigationMatrix.map { "${it.id}/interview-ime" }
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, output, expected)
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-makeover-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            environment.use {
                for (config in matrix + navigationMatrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    config.navigationMode?.let { mode ->
                        environment.selectNavigationMode(mode)
                        compose.waitUntil(DEVICE_TIMEOUT) { environment.navigationModeApplied(mode) }
                    }
                    for (id in if (config.navigationMode == null) statesFor(config) else listOf("interview-ime")) {
                        val state = when {
                            id.startsWith("voice-") -> UxFixtureActivity.requestedVoiceState(fixtures, id)
                            id == "interview-ime" -> fixtures.state("interview-long").copy(id = id)
                            else -> fixtures.state(id)
                        }
                        try {
                            prepare(state, config)
                            val controls = navigate(state).toMutableList()
                            if (id in setOf("library", "library-100")) {
                                controls += tag(LibraryTags.more(requireNotNull(stringOf(fixtures.sessions(state).first()["id"]))))
                            }
                            requireMakeoverSemantics(state)
                            recorder.capture(state, config, checkNotNull(activity), controls,
                                nativeSurface = nativeSurface(state), nativeMapping = nativeMapping(state))
                            if (id == "interview-ime") {
                                check(activity!!.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())) {
                                    "actual IME was lost during native reachability checks"
                                }
                            }
                            if (id == "library-long") requireSelectedPaneSurvivesRecreation(config, recorder)
                            if (id == "library" && config.fontScale == 2f) {
                                val graph = checkNotNull(fixtureGraph)
                                val before = graph.sessionStore.sessions.value
                                val menu = LibraryTags.more(requireNotNull(stringOf(fixtures.sessions(state).first()["id"])))
                                compose.onNodeWithTag(menu).performClick()
                                val delete = hasText(target.getString(R.string.idea_delete)) and hasClickAction()
                                recorder.capture(state.copy(id = "library-menu"), config, checkNotNull(activity),
                                    listOf(UxControl("delete-menu", delete)), rootSurfaceRole = "surfaceContainer")
                                compose.onNode(delete).performClick()
                                val cancel = hasText(target.getString(R.string.action_cancel)) and hasClickAction() and hasAnyAncestor(isDialog())
                                recorder.capture(state.copy(id = "delete-dialog"), config, checkNotNull(activity),
                                    listOf(UxControl("cancel-delete", cancel), UxControl("confirm-delete", delete and hasAnyAncestor(isDialog()))),
                                    rootSurfaceRole = "surfaceContainerHigh")
                                compose.onNode(cancel).performClick()
                                assertEquals("Cancel does not delete a library row", before, graph.sessionStore.sessions.value)
                                recorder.capture(state.copy(id = "delete-cancel"), config, checkNotNull(activity),
                                    listOf(tag(menu)))
                            }
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
                        } finally { closeFixture() }
                    }
                    if (config.navigationMode == null && config.fontScale == 1f && config.window != "compact-landscape") {
                        try {
                            captureMakeoverJourney(config, recorder, output)
                        } catch (error: Throwable) {
                            recorder.failedRun(error)
                            var diagnostic = activity
                            for (key in UxEvidence.unrecorded(journeyFrames.map { "${config.id}/journey-$it" }.toSet() +
                                "${config.id}/journey-rotated", recorder.recordedKeys)) {
                                recorder.failed(UxState(key.substringAfter('/'), "journey", "real-app", null, null, null),
                                    config, error, diagnostic)
                                diagnostic = null
                            }
                        } finally { closeFixture() }
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() }, { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "owned makeover state remains: $runDir" } },
                { check(app.graph === originalGraph) { "the original app graph was not restored" } },
            )) { errors -> errors.forEach(recorder::failedRun); recorder.finish() }
        }
    }

    private fun captureMakeoverJourney(config: UxConfiguration, recorder: UxCaptureRecorder, output: File) {
        val checks = AtomicInteger()
        val checking = CompletableDeferred<Unit>()
        prepare(fixtures.state("fresh"), config, checker = ProviderCheck { _, _ ->
            if (checks.incrementAndGet() == 1) {
                checking.await()
                throw ProviderException(ProviderErrorCode.AUTH, "Scripted rejected dummy key; retry the same field.", status = 401)
            }
            emptyList()
        })
        val graph = checkNotNull(fixtureGraph)
        val starts = AtomicInteger()
        val speeches = AtomicInteger()
        graph.driveService = object : DriveServiceControl {
            override fun start() { starts.incrementAndGet() }
            override fun stop() {}
        }
        graph.driveSpeaker = object : Speaker {
            override suspend fun speak(text: String): SpeakResult { speeches.incrementAndGet(); return awaitCancellation() }
            override fun cancel() {}
            override fun shutdown() {}
        }
        fun capture(frame: String, controls: List<UxControl>, surfaceRole: String = "background") = recorder.capture(
            UxState("journey-$frame", "journey", "real-app", null, null, null), config, checkNotNull(activity), controls,
            nativeMapping = "Real MainActivity navigation and native system document/share actions. Only model/check/speech/service effects are scripted; private stores and dummy credentials are removed in cleanup.",
            rootSurfaceRole = surfaceRole)
        fun click(tag: String) { revealControl(tag); compose.onNodeWithTag(tag).assertIsDisplayed().performClick() }
        fun button(label: Int) = hasText(target.getString(label)) and hasClickAction()
        fun dialogCancel() = compose.onNode(button(R.string.action_cancel) and hasAnyAncestor(isDialog())).performClick()
        launchMain()
        await("provider-groq")
        compose.onNode(hasText(target.getString(R.string.onboarding_pick_title)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        capture("fresh", listOf(tag("provider-groq"), tag("provider-ollama")))
        click("provider-groq")
        await("api-key")
        compose.onNodeWithTag("api-key").performTextReplacement("ux_dummy_never_transmitted")
        hideKeyboard()
        click("inference-consent")
        click("check-key")
        compose.waitUntilAtLeastOneExists(hasText(target.getString(R.string.onboarding_checking)), DEVICE_TIMEOUT)
        compose.onNodeWithTag("check-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("api-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        capture("provider-checking", listOf(UxControl("checking-key", hasTestTag("api-key"), false),
            textControl("provider-back", R.string.onboarding_back)))
        checking.complete(Unit)
        await("check-failure")
        compose.onNodeWithTag("check-failure")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        capture("provider-error", listOf(tag("api-key"), tag("inference-consent"), tag("check-key")))
        click("check-key")
        await("new-idea-choice")
        assertEquals(0, graph.sessionStore.sessions.value.size)
        assertEquals(0, starts.get())
        compose.onNodeWithTag("new-idea-voice").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        capture("ready-choice", listOf(tag("new-idea-voice"), tag("new-idea-type"), tag("new-idea-cancel")))
        require(!voicePermissionsHeld(target)) { "permission-intent acceptance requires an ungranted managed-device permission" }
        click("new-idea-voice")
        await("voice-mic-rationale")
        capture("voice-rationale", listOf(tag("voice-mic-continue"),
            UxControl("voice-not-now", button(R.string.voice_mic_cancel))), "surfaceContainerHigh")
        compose.onNode(button(R.string.voice_mic_cancel)).performClick()
        await("answer")
        assertEquals("rationale cancellation starts no service", 0, starts.get())
        assertEquals("rationale cancellation speaks nothing", 0, speeches.get())
        compose.onNode(navigationMatcher(R.string.nav_new_idea)).performClick()
        await("new-idea-type")
        click("new-idea-type")
        await("answer")
        requireMakeoverSemantics(fixtures.state("interview-long").copy(id = "typed"))
        capture("typed-seed", interviewControls())

        scriptedWalkthrough(compose, target, launch = {}, settleInput = ::hideKeyboard, capture = { frame ->
            if (frame == "chip") capture("chip", interviewControls())
            if (frame == "result") capture("result", resultControls())
        })
        val done = graph.sessionStore.sessions.value.single { stringOf(it["status"]) == "done" }
        val id = requireNotNull(stringOf(done["id"]))
        val export = exportFor(done)
        val ids = runBlocking { graph.sessionStore.knownIds() }
        UxSystemActions(instrumentation, target, File(output, "${config.id}/native-system")).use { system ->
            system.requireCommandTransport()
            click(ResultTags.SAVE)
            system.awaitExternalWindow()
            system.snapshot("save-cancel")
            system.back()
            await(ResultTags.OUTPUT)
            assertEquals(done, graph.sessionStore.get(id))
            capture("save-cancel", resultControls())
            system.share { click(ResultTags.SHARE) }
            system.awaitExternalWindow()
            system.requireShared(export)
            system.snapshot("share-cancel")
            system.back()
            await(ResultTags.OUTPUT)
            assertEquals(done, graph.sessionStore.get(id))
            capture("share-cancel", resultControls())
            click(ResultTags.SAVE)
            val saved = system.createDocument("result.md")
            await(ResultTags.OUTPUT)
            compose.waitUntil(DEVICE_TIMEOUT) { system.readDocument(saved) == export }
            capture("saved-markdown", resultControls())
            system.copy { click(ResultTags.COPY) }
            system.requireCopied(export)
            capture("copied-markdown", resultControls())
            click(ResultTags.ASK_MORE)
            await("answer")
            compose.waitUntil(DEVICE_TIMEOUT) { !exists("interview-busy") }
            assertEquals(ids, runBlocking { graph.sessionStore.knownIds() })
            assertEquals("interviewing", stringOf(graph.sessionStore.get(id)!!["status"]))
            capture("ask-more", interviewControls())
            assertEquals("manual work starts no voice service", 0, starts.get())
            compose.runOnUiThread { graph.driveController.start(checkNotNull(graph.sessionStore.get(id)), "typed") }
            await("voice-stage")
            compose.waitUntil(DEVICE_TIMEOUT) { speeches.get() == 1 }
            click("voice-pause")
            assertEquals(VoiceMode.PAUSED, graph.driveController.state.value.mode)
            capture("controller-paused", listOf(tag("voice-resume"), tag("voice-exit")))
            // Admission is deliberately scripted here; real UI Resume still requires mic permission.
            compose.runOnUiThread { graph.driveController.resume() }
            compose.waitUntil(DEVICE_TIMEOUT) { speeches.get() == 2 }
            click("voice-pause")
            click("voice-exit")
            await("answer")
            runBlocking { withTimeout(DEVICE_TIMEOUT) { graph.driveController.draftWrite?.join() } }
            compose.waitUntil(DEVICE_TIMEOUT) { !exists("interview-busy") }
            compose.waitForIdle()
            assertEquals(null, graph.driveController.state.value.mode)
            assertEquals("a retired run cannot start more speech", 2, speeches.get())
            assertEquals(ids, runBlocking { graph.sessionStore.knownIds() })
            capture("controller-exit", interviewControls())
            val draft = "The same draft and session survive real device rotation."
            awaitEditableQuestion()
            compose.onNodeWithTag("answer").assertIsEnabled().performTextReplacement(draft)
            compose.waitUntil(DEVICE_TIMEOUT) {
                compose.onNodeWithTag("answer").fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.EditableText)?.text == draft
            }
            hideKeyboard()
            assertEquals(draft, compose.onNodeWithTag("answer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            val before = checkNotNull(activity).resources.configuration.orientation
            scenario!!.onActivity { it.requestedOrientation = if (before == Configuration.ORIENTATION_PORTRAIT)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(DEVICE_TIMEOUT) {
                var rotated = false
                scenario!!.onActivity { activity = it; rotated = it.resources.configuration.orientation != before }
                rotated && exists("answer")
            }
            assertEquals(draft, compose.onNodeWithTag("answer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(ids, runBlocking { graph.sessionStore.knownIds() })
            assertEquals(sessionDisplayTitle(graph.sessionStore.get(id)!!), nodeText("interview-title"))
            // Rotation has its own achieved dimensions; never label the swapped window as a tablet.
            val view = checkNotNull(activity).window.decorView
            val density = checkNotNull(activity).resources.displayMetrics.density
            recorder.capture(UxState("journey-rotated", "interview", "real-app", null, null, id),
                config.copy(requestedWidthDp = (view.width / density).toInt(), requestedHeightDp = (view.height / density).toInt()),
                checkNotNull(activity), interviewControls(),
                nativeMapping = "Actual requestedOrientation recreation; exact session ids, full title and editable draft are asserted unchanged. Achieved rotated dp/density are recorded.")
            scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            closeFixture()
            prepare(fixtures.state("ready"), config)
            // Keep the real typed wrap path independent of the earlier chip case.
            scriptedWalkthrough(compose, target, launch = ::launchMain, settleInput = ::hideKeyboard, wrapOnly = true)
            capture("wrap", listOf(tag("wrap-accept"), tag("wrap-decline")))
            click("wrap-decline")
            val wrapGraph = checkNotNull(fixtureGraph)
            val wrapping = wrapGraph.sessionStore.sessions.value.single()
            val wrapId = requireNotNull(stringOf(wrapping["id"]))
            val turn = (wrapping["turns"] as JsonArray).last()
            click("skip")
            compose.waitUntil(DEVICE_TIMEOUT) {
                !exists("interview-busy") && (wrapGraph.sessionStore.get(wrapId)!!["turns"] as JsonArray)
                    .any { it["n"] == turn["n"] && truthy(it["skipped"]) }
            }
            capture("skipped", interviewControls())
        }
        val dataGraph = checkNotNull(fixtureGraph)
        compose.onNode(navigationMatcher(R.string.nav_settings)).performClick()
        await(SettingsTags.PROVIDER)
        val keyBytes = dataGraph.keyringStore.file.readBytes()
        val generation = dataGraph.keyringStore.generation.value
        revealControl(SettingsTags.theme(config.theme))
        compose.onNodeWithTag(SettingsTags.theme(config.theme)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        val otherTheme = if (config.theme == "light") "dark" else "light"
        click(SettingsTags.theme(otherTheme))
        compose.waitUntil(DEVICE_TIMEOUT) { runBlocking { dataGraph.prefsStore.current().theme } == otherTheme }
        click(SettingsTags.theme(config.theme))
        compose.waitUntil(DEVICE_TIMEOUT) { runBlocking { dataGraph.prefsStore.current().theme } == config.theme }
        capture("theme", Prefs.THEMES.map { tag(SettingsTags.theme(it)) })
        click(SettingsTags.FORGET)
        await(SettingsTags.FORGET_CONFIRM)
        capture("forget-dialog", listOf(UxControl("cancel-forget", button(R.string.action_cancel) and hasAnyAncestor(isDialog()))),
            "surfaceContainerHigh")
        dialogCancel()
        click(SettingsTags.FORGET)
        await(SettingsTags.FORGET_CONFIRM)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(DEVICE_TIMEOUT) { !exists(SettingsTags.FORGET_CONFIRM) }
        assertTrue(keyBytes.contentEquals(dataGraph.keyringStore.file.readBytes()))
        assertEquals(generation, dataGraph.keyringStore.generation.value)
        capture("forget-cancel", listOf(tag(SettingsTags.FORGET)))
        UxSystemActions(instrumentation, target, File(output, "${config.id}/native-system")).use { system ->
            click(BackupTags.EXPORT)
            val path = system.createDocument("backup.json")
            await(BackupTags.STATUS)
            compose.waitUntil(DEVICE_TIMEOUT) {
                !dataGraph.backupJobs.state.value.busy &&
                    dataGraph.backupJobs.state.value.message.orEmpty().startsWith("Backed up")
            }
            val backup = Json.parseToJsonElement(system.readDocument(path)) as JsonObject
            assertEquals(setOf("format", "version", "exportedAt", "sessions"), backup.keys)
            assertEquals(dataGraph.sessionStore.sessions.value.size, (backup["sessions"] as JsonArray).size)
            capture("backup", listOf(tag(BackupTags.EXPORT), tag(BackupTags.IMPORT)))
            val original = dataGraph.sessionStore.sessions.value.single()
            val changed = renameSession(original, str("A collision preserved as an imported copy"), num(fixtures.now + 1))
            val incoming = system.importDocument("collision", buildBackup(JsonArray(listOf(changed)), num(fixtures.now)))
            click(BackupTags.IMPORT)
            system.chooseDocument(incoming)
            await(BackupTags.STATUS)
            compose.waitUntil(DEVICE_TIMEOUT) { dataGraph.sessionStore.sessions.value.size == 2 && !dataGraph.backupJobs.state.value.busy }
            assertEquals(original, dataGraph.sessionStore.get(requireNotNull(stringOf(original["id"]))))
            assertTrue(dataGraph.backupJobs.state.value.message.orEmpty().contains("1 kept as copies"))
            capture("import-copy", listOf(tag(BackupTags.IMPORT), UxControl("import-status", hasTestTag(BackupTags.STATUS), false)))
            val beforeImport = dataGraph.sessionStore.sessions.value
            val malformed = system.importDocument("malformed", "{\"not\":\"an IdeaForge backup\"}")
            click(BackupTags.IMPORT)
            system.chooseDocument(malformed)
            compose.waitUntil(DEVICE_TIMEOUT) { dataGraph.backupJobs.state.value.failed && !dataGraph.backupJobs.state.value.busy }
            revealControl(BackupTags.STATUS)
            compose.onNodeWithTag(BackupTags.STATUS).assertIsDisplayed().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertEquals(beforeImport, dataGraph.sessionStore.sessions.value)
            assertTrue(keyBytes.contentEquals(dataGraph.keyringStore.file.readBytes()))
            capture("import-failure", listOf(tag(BackupTags.IMPORT), UxControl("import-status", hasTestTag(BackupTags.STATUS), false)))
        }
        assertEquals("only the explicitly scripted controller admission starts a service", 1, starts.get())
        assertEquals("no retired speech runs after leaving the interview", 2, speeches.get())
    }

    private fun requireMakeoverSemantics(state: UxState) {
        fun role(tag: String, value: Role) = compose.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, value))
        if (state.id.startsWith("voice-")) {
            compose.onNodeWithTag("voice-question").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithTag("voice-phase").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            role(if (state.phase in setOf("paused", "blocked")) "voice-resume" else "voice-pause", Role.Button)
            role("voice-exit", Role.Button)
            compose.onNodeWithTag("voice-levels").assertDoesNotExist()
            if (state.id in setOf("voice-listening", "voice-notifications-off"))
                compose.onNodeWithTag("voice-no-level").assertExists()
        } else if (state.id == "busy-turn") {
            compose.onNodeWithTag("question").assertDoesNotExist()
            val thread = hasAnyAncestor(hasTestTag("thread"))
            compose.onNode(hasText(SEED_QUESTION) and thread).assertExists()
            compose.onNode(hasText(fixtures.text("answer")) and thread).assertExists()
            role("send", Role.Button)
            compose.onNodeWithTag("answer").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            compose.onNodeWithTag("send").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            compose.onNodeWithTag("interview-busy").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            compose.onNodeWithTag("busy-line").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        } else if (state.surface == "interview") {
            compose.onNodeWithTag("question").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            role("send", Role.Button)
            compose.onNodeWithTag("answer").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            val tags = compose.onAllNodes(SemanticsMatcher("all native task semantics") { true }, useUnmergedTree = true).fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            check(tags.indexOf("question") in 0 until tags.indexOf("answer") && tags.indexOf("answer") < tags.indexOf("send")) {
                "the native task reading order must be question, editable answer, then Send: $tags"
            }
            if (state.id == "bank") compose.onNodeWithTag("degraded").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            if (state.id == "save-failure") {
                assertEquals(SEED_QUESTION, nodeText("question"))
                compose.onNodeWithTag("answer").assertIsEnabled()
                compose.onNodeWithTag("send").assertIsEnabled()
                compose.onNodeWithTag("history-toggle").assertDoesNotExist()
                compose.onNodeWithTag("notice-message").assertIsDisplayed()
                compose.onNodeWithTag("notice-dismiss").assertExists()
            }
        } else if (state.surface == "library") {
            compose.onNodeWithTag(LibraryTags.HEADING).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            if (exists(LibraryTags.FILTERS)) compose.onNodeWithTag(LibraryTags.FILTERS)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        } else if (state.id == "result-long") {
            compose.onNodeWithTag(ResultTags.COPY).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertTrue("the actual document exposes section headings",
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
                    hasAnyAncestor(hasTestTag(ResultTags.OUTPUT))).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun requireSelectedPaneSurvivesRecreation(config: UxConfiguration, recorder: UxCaptureRecorder) {
        val graph = checkNotNull(fixtureGraph)
        val ids = runBlocking { graph.sessionStore.knownIds() }
        val title = sessionDisplayTitle(checkNotNull(graph.sessionStore.get("ux-long-active")))
        scenario!!.recreate()
        scenario!!.onActivity { activity = it }
        await(LibraryTags.DETAIL)
        compose.onNode(hasTestTag(LibraryTags.DETAIL) and hasAnyDescendant(hasText(title))).assertIsDisplayed()
        assertEquals(ids, runBlocking { graph.sessionStore.knownIds() })
        val selected = compose.onAllNodes(hasTestTag(LibraryTags.CARD)).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
        val listVisible = runCatching { compose.onNodeWithTag(LibraryTags.LIST).assertIsDisplayed() }.isSuccess
        if (listVisible) {
            assertTrue("an expanded adaptive list/detail keeps the selected row", selected.isNotEmpty())
        }
        if (config.fontScale == 1f && config.window != "compact-landscape") {
            val before = checkNotNull(activity).resources.configuration.orientation
            scenario!!.onActivity { it.requestedOrientation = if (before == Configuration.ORIENTATION_PORTRAIT)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(DEVICE_TIMEOUT) {
                var rotated = false
                scenario!!.onActivity { activity = it; rotated = it.resources.configuration.orientation != before }
                rotated && exists(LibraryTags.DETAIL)
            }
            compose.onNode(hasTestTag(LibraryTags.DETAIL) and hasAnyDescendant(hasText(title))).assertIsDisplayed()
            assertEquals("rotation never recreates the selected library idea", ids, runBlocking { graph.sessionStore.knownIds() })
            val view = checkNotNull(activity).window.decorView
            val density = checkNotNull(activity).resources.displayMetrics.density
            recorder.capture(fixtures.state("library-long").copy(id = "library-rotated"),
                config.copy(requestedWidthDp = (view.width / density).toInt(), requestedHeightDp = (view.height / density).toInt()),
                checkNotNull(activity), listOf(textControl("continue", R.string.idea_continue)) + navigationControls(),
                nativeMapping = "Installed adaptive list/detail selection survives actual requestedOrientation recreation; actual dp/density are recorded.")
            scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test
    fun requestedPlayListingAssets() {
        val bundle = InstrumentationRegistry.getArguments()
        val probe = bundle.getString("playListingProbe")
        if (probe == null) {
            require(bundle.getString("class")?.contains("#requestedPlayListingAssets") != true) {
                "the selected listing capture requires playListingProbe=true"
            }
            return
        }
        require(probe == "true") { "playListingProbe must be true" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "walkthrough" && request.window == "compact" && request.states == null) {
            "listing capture requires the complete compact scripted walkthrough"
        }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        val output = File(outputPath, "play-listing")
        val matrix = listOf("light", "dark").map { UxConfiguration("compact", it, 1f) }
        val expected = matrix.flatMap { config -> walkthroughFrames.map { "${config.id}/walkthrough-$it" } }.toSet()
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, output, expected)
        fixtures = UxFixtures.load(target)
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-listing-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs()) { "cannot create owned listing fixture directory" }
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = false)
                    captureWalkthrough(config, recorder)
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() }, { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "cannot remove owned listing fixture directory" } },
            )) { errors ->
                errors.forEach(recorder::failedRun)
                if (recorder.failures.isEmpty()) {
                    try {
                        val screenshots = matrix.flatMap { config -> walkthroughFrames.map { frame ->
                            "${config.theme}-$frame" to File(output, "${config.id}/real-app/walkthrough-$frame.png")
                        } }
                        writePlayListingAssets(target, output, screenshots)
                    } catch (error: Throwable) {
                        recorder.failedRun(error)
                    }
                }
                recorder.finish()
            }
        }
    }

    @Test
    fun requestedNativeResultKeepsDocumentAndCanonicalActions() {
        val bundle = InstrumentationRegistry.getArguments()
        if (bundle.getString("uxResultProbe") != "true") {
            require(bundle.getString("class")?.contains("#requestedNativeResultKeepsDocumentAndCanonicalActions") != true) {
                "the selected Result probe requires uxResultProbe=true"
            }
            return
        }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "Result evidence has a fixed complete matrix" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window -> listOf("light", "dark").flatMap { theme ->
            listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
        } }
        val frames = listOf("long", "long-scrolled", "short", "checklist", "synthesis-failure", "loading")
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request,
            File(outputPath, "ux-result"),
            matrix.flatMap { config -> frames.map { "${config.id}/result-$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (frame in frames) {
                        val state = UxState("result-$frame", "result", "real-app", null, null, null)
                        var release: CompletableDeferred<Unit>? = null
                        try {
                            val base = fixtures.state(if (frame == "synthesis-failure") "synthesis-failure" else "result-long")
                            prepare(base, config)
                            val graph = checkNotNull(fixtureGraph)
                            var id = requireNotNull(base.sessionId)
                            if (frame == "short") {
                                val short = fixtures.datasets.getValue("mixed").first { stringOf(it["status"]) == "done" }
                                check(runBlocking { graph.sessionStore.insert(short) })
                                id = requireNotNull(stringOf(short["id"]))
                            } else if (frame == "checklist") {
                                val done = requireNotNull(graph.sessionStore.get(id))
                                val turns = (done["turns"] as kotlinx.serialization.json.JsonArray).mapIndexed { index, turn ->
                                    if (index == 0) kotlinx.serialization.json.JsonObject((turn as kotlinx.serialization.json.JsonObject) +
                                        ("questionSource" to JsonPrimitive("bank"))) else turn
                                }
                                runBlocking { graph.sessionStore.save(kotlinx.serialization.json.JsonObject(done +
                                    ("turns" to kotlinx.serialization.json.JsonArray(turns)))) }
                            }
                            if (frame == "synthesis-failure") {
                                navigate(base)
                                await(ResultTags.SYNTHESIS_FAILED)
                                revealControl(ResultTags.SYNTHESIS_FAILED)
                            } else {
                                launchMain()
                                await(NAVIGATION)
                                if (frame == "loading") {
                                    val entered = CompletableDeferred<Unit>()
                                    val gate = CompletableDeferred<Unit>()
                                    release = gate
                                    graph.appScope.launch {
                                        graph.sessionWorker.exclusive(id) {
                                            entered.complete(Unit)
                                            gate.await()
                                        }
                                    }
                                    runBlocking { entered.await() }
                                }
                                selectSession(id)
                                await(ResultTags.SCREEN)
                                if (frame == "loading") {
                                    compose.waitUntilAtLeastOneExists(hasText(target.getString(R.string.result_loading)), DEVICE_TIMEOUT)
                                } else {
                                    await(ResultTags.OUTPUT)
                                    if (frame == "checklist")
                                        assertTrue("checklist provenance is explicit", nodeText(ResultTags.META).contains("checklist"))
                                    else compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
                                    if (frame == "long-scrolled") {
                                        compose.onNodeWithTag(ResultTags.CONTENT)
                                            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
                                        compose.waitUntil(DEVICE_TIMEOUT) {
                                            compose.onNodeWithTag(ResultTags.CONTENT).fetchSemanticsNode().config
                                                .getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()?.let { it > 0f } == true
                                        }
                                    }
                                }
                            }
                            val controls = if (frame == "loading")
                                listOf(UxControl("loading", hasText(target.getString(R.string.result_loading)), actionRequired = false))
                            else resultControls()
                            recorder.capture(state, config, checkNotNull(activity), controls, nativeSurface = "result",
                                nativeMapping = "Real MainActivity Result $frame; canonical stored export, scripted synthesis or held session lock; no chooser/clipboard/file picker or live model.")
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
                        } finally {
                            release?.complete(Unit)
                            closeFixture()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() }, { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "could not delete owned fixture files: $runDir" } },
            )) { errors -> errors.forEach(recorder::failedRun); recorder.finish() }
        }
    }

    @Test
    fun requestedNativeSettingsKeepSectionsAndDataBoundaries() {
        val bundle = InstrumentationRegistry.getArguments()
        if (bundle.getString("uxSettingsProbe") != "true") {
            require(bundle.getString("class")?.contains("#requestedNativeSettingsKeepSectionsAndDataBoundaries") != true) {
                "the selected Settings probe requires uxSettingsProbe=true"
            }
            return
        }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "Settings evidence has a fixed complete matrix" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window -> listOf("light", "dark").flatMap { theme ->
            listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
        } }
        val deviceVoices = bundle.getString("uxDeviceVoicesProbe") == "true"
        val frames = if (deviceVoices) listOf("device-default", "device-long-choice", "device-selected",
            "device-loading", "device-error", "device-empty", "device-missing", "device-spoken-fallback")
        else listOf("connection-lan", "voice", "hosted-consents", "appearance", "data",
            "cancel-forget", "about", "connection-failure", "checking", "backup-failure")
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request,
            File(outputPath, if (deviceVoices) "ux-device-voices" else "ux-settings"),
            matrix.flatMap { config -> frames.map { "${config.id}/settings-$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (frame in frames) {
                        val isolated = if (frame == "device-spoken-fallback")
                            UxFixtureActivity.requestedVoiceState(fixtures, "voice-fallback") else null
                        val state = isolated?.copy(id = "settings-$frame")
                            ?: UxState("settings-$frame", "settings", "real-app", null, null, null)
                        try {
                            if (isolated != null) {
                                prepare(isolated, config)
                                val controls = navigate(isolated) +
                                    UxControl("spoken-device-warning", hasTestTag("voice-fallback"), actionRequired = false)
                                compose.onNodeWithTag("voice-fallback").performScrollTo().assertIsDisplayed()
                                check(nodeText("voice-fallback").contains("selected device voice is unavailable"))
                                recorder.capture(state, config, checkNotNull(activity), controls,
                                    nativeSurface = "voice", nativeMapping = UxFixtureActivity.nativeMapping(isolated))
                                continue
                            }
                            val checker = ProviderCheck { _, _ ->
                                when (frame) {
                                    "checking" -> awaitCancellation()
                                    "connection-failure" -> throw ProviderException(ProviderErrorCode.AUTH,
                                        "Scripted refused connection. ${fixtures.text("answer")}", status = 401)
                                    else -> error("unexpected Settings fixture check")
                                }
                            }
                            val base = fixtures.state("ready")
                            val engine = if (deviceVoices) FixtureDeviceVoiceEngine(frame) else null
                            heldVoiceInit = engine?.pending
                            prepare(base, config, inferenceAgreed = frame != "connection-lan", checker = checker,
                                nativeEngine = engine)
                            val graph = checkNotNull(fixtureGraph)
                            val savedVoice = if (frame in setOf("device-loading", "device-error", "device-missing"))
                                "retained-unavailable-voice" else ""
                            if (deviceVoices) runBlocking {
                                check(graph.prefsStore.update { it.copy(speechVoice = savedVoice) })
                            }
                            val ringBefore = graph.keyringStore.state.value.ring
                            val keyGeneration = graph.keyringStore.generation.value
                            val prefsBefore = runBlocking { graph.prefsStore.current() }
                            navigate(base)
                            compose.onNode(navigationMatcher(R.string.nav_settings)).performClick()
                            await(SettingsTags.PROVIDER)
                            if (deviceVoices) {
                                prepareDeviceVoiceFrame(frame, checkNotNull(engine))
                            } else when (frame) {
                                "connection-lan" -> {
                                    compose.onNodeWithTag(SettingsTags.PROVIDER).performScrollTo().performClick()
                                    compose.onNodeWithTag(SettingsTags.provider("ollama")).performScrollTo().performClick()
                                    await(SettingsTags.BASE_URL)
                                    compose.onNodeWithTag(SettingsTags.BASE_URL).performScrollTo()
                                        .performTextReplacement("http://192.168.1.20:11434/v1")
                                    hideKeyboard()
                                    await("local-http-warning")
                                    revealControl("inference-consent")
                                    compose.onNodeWithTag("local-http-warning").assertIsDisplayed()
                                }
                                "hosted-consents" -> {
                                    revealControl(HostedSpeechTags.HOSTED)
                                    compose.onNodeWithTag(HostedSpeechTags.HOSTED).performClick()
                                    await(HostedSpeechTags.CONSENT_METERED)
                                    revealControl(HostedSpeechTags.CONSENT_SYNTHETIC)
                                }
                                "cancel-forget" -> {
                                    val graph = checkNotNull(fixtureGraph)
                                    val bytes = graph.keyringStore.file.readBytes()
                                    val generation = graph.keyringStore.generation.value
                                    revealControl(SettingsTags.FORGET)
                                    compose.onNodeWithTag(SettingsTags.FORGET).performClick()
                                    await(SettingsTags.FORGET_CONFIRM)
                                    compose.onNode(hasText(target.getString(R.string.action_cancel)) and hasClickAction()).performClick()
                                    compose.waitUntil(DEVICE_TIMEOUT) { !exists(SettingsTags.FORGET_CONFIRM) }
                                    assertTrue("Cancel keeps the credential blob", bytes.contentEquals(graph.keyringStore.file.readBytes()))
                                    assertEquals("Cancel does not revoke", generation, graph.keyringStore.generation.value)
                                }
                                "connection-failure", "checking" -> {
                                    revealControl(SettingsTags.CHECK)
                                    compose.onNodeWithTag(SettingsTags.CHECK).performClick()
                                    await(SettingsTags.STATUS)
                                    compose.waitUntil(DEVICE_TIMEOUT) {
                                        nodeText(SettingsTags.STATUS).contains(if (frame == "checking")
                                            target.getString(R.string.onboarding_checking)
                                        else "Scripted refused connection")
                                    }
                                    revealControl(SettingsTags.STATUS)
                                    compose.onNodeWithTag(SettingsTags.STATUS).assertIsDisplayed()
                                }
                                "backup-failure" -> {
                                    revealControl(BackupTags.EXPORT)
                                    checkNotNull(fixtureGraph).backupJobs.launch {
                                        throw IOException("Scripted backup destination failure. Existing ideas and keys are unchanged.")
                                    }
                                    await(BackupTags.STATUS)
                                    compose.waitUntil(DEVICE_TIMEOUT) { nodeText(BackupTags.STATUS).contains("Scripted backup destination") }
                                    revealControl(BackupTags.STATUS)
                                    compose.onNodeWithTag(BackupTags.STATUS).assertIsDisplayed()
                                }
                                else -> {
                                    val section = when (frame) {
                                        "voice" -> "voice"
                                        "appearance" -> "appearance"
                                        "data" -> "data"
                                        else -> "about"
                                    }
                                    revealControl("settings-section-$section")
                                }
                            }
                            val controls = if (deviceVoices) deviceVoiceControls(frame) else when (frame) {
                                "voice" -> {
                                    compose.onNodeWithTag("settings-handsfree").assertDoesNotExist()
                                    listOf(tag(SettingsTags.TRIGGER), tag(SettingsTags.PACE), tag(SettingsTags.DEVICE_VOICE))
                                }
                                "hosted-consents" -> listOf(tag(HostedSpeechTags.VOICE_MARIN), tag(HostedSpeechTags.VOICE_CEDAR),
                                    tag(HostedSpeechTags.CONSENT_SYNTHETIC), tag(HostedSpeechTags.CONSENT_TEXT), tag(HostedSpeechTags.CONSENT_METERED))
                                "appearance" -> Prefs.THEMES.map { tag(SettingsTags.theme(it)) }
                                "data", "backup-failure" -> listOf(tag(BackupTags.EXPORT), tag(BackupTags.IMPORT), tag(SettingsTags.FORGET))
                                "cancel-forget" -> listOf(tag(SettingsTags.FORGET))
                                "about" -> listOf(textControl("privacy", R.string.privacy_policy))
                                "checking" -> listOf(tag(SettingsTags.FORGET))
                                "connection-lan" -> listOf(tag("inference-consent"))
                                else -> listOf(tag(SettingsTags.PROVIDER), tag(SettingsTags.CHECK), tag(SettingsTags.SAVE))
                            }
                            if (deviceVoices) {
                                assertTrue("opening Settings and selecting voices do not speak", engine!!.spoken.isEmpty())
                                assertEquals("a native edit cannot change hosted/inference keys", ringBefore, graph.keyringStore.state.value.ring)
                                assertEquals(keyGeneration, graph.keyringStore.generation.value)
                                assertEquals("native voice edits change only speechVoice", prefsBefore.copy(
                                    speechVoice = if (frame == "device-selected") FixtureDeviceVoiceEngine.LONG_ID else savedVoice,
                                ), runBlocking { graph.prefsStore.current() })
                                assertEquals("Settings starts no voice run", null, graph.driveController.state.value.mode)
                            }
                            recorder.capture(state, config, checkNotNull(activity), controls, nativeSurface = "settings",
                                nativeMapping = if (deviceVoices)
                                    "Real MainActivity Settings $frame; controlled engine identity/locale/network metadata and pending/error reads. UI/configuration proof, not real voice quality, network/offline support or physical-device evidence."
                                else "Real MainActivity Settings $frame; scripted connection/backup error, no file picker, hosted preview or real provider.",
                                rootSurfaceRole = if (frame in setOf("device-long-choice", "device-empty"))
                                    "surfaceContainerHigh" else "background")
                            if (deviceVoices) {
                                when (frame) {
                                    "device-selected", "device-missing" -> {
                                        val result = runBlocking { graph.nativeSpeaker.speak("Controlled subsequent native output.") }
                                        assertEquals(SpeakStatus.SPOKEN, result.status)
                                        assertEquals(frame == "device-missing", result.voiceMissing)
                                        assertEquals(if (frame == "device-selected") FixtureDeviceVoiceEngine.LONG_ID else savedVoice,
                                            result.requestedVoice)
                                        assertEquals(if (frame == "device-selected") FixtureDeviceVoiceEngine.LONG_ID else "", result.voice)
                                        assertEquals(result.voice, engine!!.selected)
                                    }
                                    "device-loading" -> assertTrue("loading remains a real pending engine read", !engine!!.pending.isCompleted)
                                    "device-error" -> {
                                        compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_RELOAD).performScrollTo().assertIsDisplayed().performClick()
                                        compose.waitUntil(DEVICE_TIMEOUT) { !exists(SettingsTags.DEVICE_VOICE_STATUS) }
                                        assertEquals(savedVoice, runBlocking { graph.prefsStore.current().speechVoice })
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
                        } finally { closeFixture() }
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() }, { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "could not delete owned fixture files: $runDir" } },
            )) { errors -> errors.forEach(recorder::failedRun); recorder.finish() }
        }
    }

    private fun prepareDeviceVoiceFrame(frame: String, engine: FixtureDeviceVoiceEngine) {
        await(SettingsTags.DEVICE_VOICE)
        if (frame in setOf("device-loading", "device-error", "device-empty")) {
            await(SettingsTags.DEVICE_VOICE_STATUS)
            val status = when (frame) {
                "device-loading" -> "Loading device voices"
                "device-error" -> "did not start"
                else -> "no installed voices"
            }
            compose.waitUntil(DEVICE_TIMEOUT) { nodeText(SettingsTags.DEVICE_VOICE_STATUS).contains(status) }
            compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_STATUS).performScrollTo().assertIsDisplayed()
        } else {
            compose.waitUntil(DEVICE_TIMEOUT) { !exists(SettingsTags.DEVICE_VOICE_STATUS) }
        }
        when (frame) {
            "device-long-choice", "device-selected", "device-empty" -> {
                compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().assertIsDisplayed().performClick()
                await(SettingsTags.DEVICE_VOICE_DEFAULT)
                if (frame != "device-empty") {
                    // Existence alone does not mean a scrolled option can actually be clicked.
                    compose.onNodeWithTag(SettingsTags.deviceVoice(FixtureDeviceVoiceEngine.LONG_ID))
                        .performScrollTo().assertIsDisplayed()
                }
                if (frame == "device-selected") {
                    compose.onNodeWithTag(SettingsTags.deviceVoice(FixtureDeviceVoiceEngine.LONG_ID)).performClick()
                    compose.waitUntil(DEVICE_TIMEOUT) {
                        runBlocking { fixtureGraph!!.prefsStore.current().speechVoice } == FixtureDeviceVoiceEngine.LONG_ID
                    }
                    scenario!!.recreate()
                    scenario!!.onActivity { activity = it }
                    await(SettingsTags.DEVICE_VOICE)
                    compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().assertIsDisplayed()
                    assertTrue(nodeText(SettingsTags.DEVICE_VOICE).contains(FixtureDeviceVoiceEngine.LONG_ID))
                    assertTrue(engine.spoken.isEmpty())
                }
            }
            "device-missing" -> {
                await(SettingsTags.DEVICE_VOICE_MISSING)
                compose.onNodeWithTag(SettingsTags.DEVICE_VOICE_MISSING).performScrollTo().assertIsDisplayed()
            }
            "device-default" -> compose.onNodeWithTag(SettingsTags.DEVICE_VOICE).performScrollTo().assertIsDisplayed()
        }
    }

    private fun deviceVoiceControls(frame: String): List<UxControl> = when (frame) {
        "device-long-choice" -> listOf(tag(SettingsTags.DEVICE_VOICE_DEFAULT),
            tag(SettingsTags.deviceVoice(FixtureDeviceVoiceEngine.LONG_ID)), tag(SettingsTags.DEVICE_VOICE_CLOSE))
        "device-empty" -> listOf(UxControl("empty-voices", hasTestTag(SettingsTags.DEVICE_VOICE_STATUS), actionRequired = false),
            tag(SettingsTags.DEVICE_VOICE_DEFAULT), tag(SettingsTags.DEVICE_VOICE_CLOSE))
        "device-loading", "device-error" -> listOf(
            UxControl("voice-read-status", hasTestTag(SettingsTags.DEVICE_VOICE_STATUS), actionRequired = false),
            tag(SettingsTags.DEVICE_VOICE_RELOAD), tag(SettingsTags.DEVICE_VOICE))
        "device-missing" -> listOf(
            UxControl("missing-voice-warning", hasTestTag(SettingsTags.DEVICE_VOICE_MISSING), actionRequired = false),
            tag(SettingsTags.DEVICE_VOICE), tag(SettingsTags.DEVICE_VOICE_RELOAD))
        else -> listOf(tag(SettingsTags.DEVICE_VOICE), tag(SettingsTags.DEVICE_VOICE_RELOAD))
    }

    @Test
    fun requestedNativeOnboardingAndEntryKeepIntentAndRecovery() {
        val bundle = InstrumentationRegistry.getArguments()
        if (bundle.getString("uxOnboardingProbe") != "true") {
            require(bundle.getString("class")?.contains("#requestedNativeOnboardingAndEntryKeepIntentAndRecovery") != true) {
                "the selected onboarding probe requires uxOnboardingProbe=true"
            }
            return
        }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "onboarding evidence has a fixed complete matrix" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window -> listOf("light", "dark").flatMap { theme ->
            listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
        } }
        val frames = listOf("providers", "hosted", "local-lan", "entry", "typed", "voice-cancel",
            "check-failure", "checking", "model-failure", "unreadable")
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request,
            File(outputPath, "ux-onboarding"),
            matrix.flatMap { config -> frames.map { "${config.id}/start-$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (frame in frames) {
                        val entry = frame in setOf("entry", "typed", "voice-cancel")
                        val surface = if (frame in setOf("typed", "voice-cancel")) "interview" else "onboarding"
                        val state = UxState("start-$frame", surface, "real-app", null, null, null)
                        try {
                            val check = ProviderCheck { _, _ ->
                                when (frame) {
                                    "checking" -> awaitCancellation()
                                    "check-failure" -> throw ProviderException(ProviderErrorCode.AUTH,
                                        "Scripted refused key. ${fixtures.text("answer")}", status = 401)
                                    "model-failure" -> throw ProviderException(ProviderErrorCode.NETWORK,
                                        "Scripted unavailable model list. Enter a known model manually.")
                                    else -> error("unexpected onboarding fixture check")
                                }
                            }
                            val base = fixtures.state(if (entry || frame == "unreadable") "ready" else "fresh")
                            prepare(base, config, inferenceAgreed = entry, checker = check)
                            val graph = checkNotNull(fixtureGraph)
                            val starts = AtomicInteger()
                            graph.driveService = object : DriveServiceControl {
                                override fun start() { starts.incrementAndGet() }
                                override fun stop() {}
                            }
                            graph.driveSpeaker = object : Speaker {
                                override suspend fun speak(text: String): SpeakResult = awaitCancellation()
                                override fun cancel() {}
                                override fun shutdown() {}
                            }
                            if (frame == "unreadable") {
                                graph.keyringStore.file.writeBytes(byteArrayOf(1, 2, 3))
                                runBlocking { graph.keyringStore.load() }
                                launchMain()
                                await("keyring-problem")
                            } else {
                                navigate(base)
                                when {
                                    entry -> {
                                        val held = graph.sessionStore.sessions.value.map { stringOf(it["id"]) }.toSet()
                                        compose.onNode(navigationMatcher(R.string.nav_new_idea)).performClick()
                                        await("new-idea-choice")
                                        assertEquals("opening the choice creates no library idea",
                                            held, graph.sessionStore.sessions.value.map { stringOf(it["id"]) }.toSet())
                                        assertEquals("navigation starts no service", 0, starts.get())
                                        if (frame != "entry") {
                                            if (frame == "voice-cancel") {
                                                require(!voicePermissionsHeld(target)) { "recovery capture requires an ungranted permission; permissions will not be changed" }
                                                compose.onNodeWithTag("new-idea-voice").performClick()
                                                await("voice-mic-rationale")
                                                compose.onNode(hasText(target.getString(R.string.voice_mic_cancel)) and hasClickAction()).performClick()
                                            } else compose.onNodeWithTag("new-idea-type").performClick()
                                            await("answer")
                                            if (frame == "voice-cancel")
                                                compose.waitUntilAtLeastOneExists(hasText(target.getString(R.string.voice_entry_cancelled)), DEVICE_TIMEOUT)
                                            assertEquals("entry/recovery starts no service", 0, starts.get())
                                        }
                                    }
                                    frame == "providers" -> revealControl("provider-ollama")
                                    frame == "local-lan" || frame == "model-failure" -> {
                                        compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
                                        await("base-url")
                                        compose.onNodeWithTag("base-url").performScrollTo()
                                            .performTextReplacement("http://192.168.1.20:11434/v1")
                                        if (frame == "model-failure") {
                                            revealControl("find-models")
                                            compose.onNodeWithTag("find-models").performClick()
                                            await("check-failure")
                                        } else {
                                            compose.onNodeWithTag("model").performScrollTo().performTextInput("scripted-local-model")
                                            hideKeyboard()
                                            await("local-http-warning")
                                            revealControl("inference-consent")
                                            compose.onNodeWithTag("local-http-warning").assertIsDisplayed()
                                        }
                                    }
                                    else -> {
                                        compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
                                        await("api-key")
                                        if (frame == "hosted") {
                                            revealControl("inference-consent")
                                        } else {
                                            compose.onNodeWithTag("api-key").performTextInput("scripted-visual-key")
                                            hideKeyboard()
                                            compose.onNodeWithTag("inference-consent").performScrollTo().performClick()
                                            compose.onNodeWithTag("check-key").performScrollTo().performClick()
                                            if (frame == "checking")
                                                compose.waitUntilAtLeastOneExists(hasText(target.getString(R.string.onboarding_checking)), DEVICE_TIMEOUT)
                                            else await("check-failure")
                                        }
                                    }
                                }
                            }
                            val controls = when (frame) {
                                "entry" -> listOf(tag("new-idea-voice"), tag("new-idea-type"), tag("new-idea-cancel"))
                                "typed", "voice-cancel" -> interviewControls()
                                "providers" -> listOf(tag("provider-groq"), tag("provider-ollama"))
                                "unreadable" -> listOf(tag("keyring-retry"), tag("keyring-setup"))
                                "local-lan", "model-failure" -> listOf(tag("model"), tag("find-models"), tag("inference-consent"))
                                "checking" -> listOf(textControl("back", R.string.onboarding_back))
                                else -> listOf(tag("api-key"), tag("check-key"), tag("inference-consent"))
                            }
                            recorder.capture(state, config, checkNotNull(activity), controls,
                                nativeSurface = surface,
                                nativeMapping = "Real MainActivity $frame; scripted connection/output/service, no OS permission request or live provider.")
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
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
                { closeFixture() }, { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "could not delete owned fixture files: $runDir" } },
            )) { errors -> errors.forEach(recorder::failedRun); recorder.finish() }
        }
    }

    @Test
    fun requestedInferenceConsentIsProminent() {
        val bundle = InstrumentationRegistry.getArguments()
        val phase = bundle.getString("uxInferenceProbe")
        if (phase.isNullOrEmpty()) {
            require(bundle.getString("class")?.contains("#requestedInferenceConsentIsProminent") != true) {
                "the selected inference probe requires uxInferenceProbe=before or after and capture provenance"
            }
            return
        }
        require(phase in setOf("before", "after")) { "uxInferenceProbe must be before or after" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "consent evidence has a fixed complete matrix" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
            }
        }
        val frames = listOf("settings-connection", "onboarding-provider", "onboarding-hosted", "onboarding-local") +
            if (phase == "after") listOf("settings-http-lan", "onboarding-http-lan") else emptyList()
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request,
            File(outputPath, "ux-consent-$phase"),
            matrix.flatMap { config -> frames.map { "${config.id}/consent-$phase-$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (frame in frames) {
                        val settings = frame.startsWith("settings-")
                        val state = UxState("consent-$phase-$frame",
                            if (settings) "settings" else "onboarding",
                            "real-app", phase, null, null)
                        try {
                            val base = fixtures.state(if (settings) "ready" else "fresh")
                            prepare(base, config, inferenceAgreed = false)
                            navigate(base)
                            when (frame) {
                                "settings-connection", "settings-http-lan" -> {
                                    compose.onNode(navigationMatcher(R.string.nav_settings)).performClick()
                                    await("settings-active")
                                    if (frame == "settings-http-lan") {
                                        await("settings-provider")
                                        compose.onNodeWithTag("settings-provider").performScrollTo().performClick()
                                        compose.onNodeWithTag("settings-provider-ollama").performScrollTo().performClick()
                                        await("settings-baseurl")
                                        compose.onNodeWithTag("settings-baseurl").performScrollTo()
                                            .performTextReplacement("http://192.168.1.20:11434/v1")
                                        hideKeyboard()
                                    }
                                }
                                "onboarding-provider" -> revealControl("provider-ollama")
                                "onboarding-hosted" -> {
                                    compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
                                    await("api-key")
                                }
                                "onboarding-local", "onboarding-http-lan" -> {
                                    compose.onNodeWithTag("provider-ollama").performScrollTo().performClick()
                                    await("model")
                                    if (frame == "onboarding-http-lan") {
                                        compose.onNodeWithTag("base-url").performScrollTo()
                                            .performTextReplacement("http://192.168.1.20:11434/v1")
                                    }
                                    compose.onNodeWithTag("model").performScrollTo().performTextInput("scripted-local-model")
                                    hideKeyboard()
                                }
                            }
                            if (frame.endsWith("-http-lan")) await("local-http-warning")
                            val controls = when {
                                frame == "onboarding-provider" -> listOf(tag("provider-ollama"))
                                phase == "after" -> {
                                    await("inference-consent")
                                    compose.onNodeWithTag("inference-consent").performScrollTo().assertIsDisplayed()
                                    if (frame.endsWith("-http-lan")) {
                                        compose.onNodeWithTag("local-http-warning").assertIsDisplayed()
                                    }
                                    listOf(tag("inference-consent"))
                                }
                                else -> {
                                    compose.onNodeWithTag("inference-consent").assertDoesNotExist()
                                    listOf(tag(when (frame) {
                                        "settings-connection" -> "settings-save"
                                        "onboarding-hosted" -> "check-key"
                                        else -> "finish-local"
                                    }))
                                }
                            }
                            recorder.capture(state, config, checkNotNull(activity), controls,
                                nativeSurface = state.surface,
                                nativeMapping = "Real MainActivity $frame; $phase inference consent, actual system dynamic colour.")
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
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

    @Test
    fun requestedPrivacyPolicyLinksAreReachable() {
        val bundle = InstrumentationRegistry.getArguments()
        val phase = bundle.getString("uxPrivacyProbe")
        if (phase.isNullOrEmpty()) {
            require(bundle.getString("class")?.contains("#requestedPrivacyPolicyLinksAreReachable") != true) {
                "the selected privacy probe requires uxPrivacyProbe=before or after and capture provenance"
            }
            return
        }
        require(phase in setOf("before", "after")) { "uxPrivacyProbe must be before or after" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "privacy evidence has a fixed complete matrix" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
            }
        }
        val frames = listOf("settings-about", "onboarding-provider", "onboarding-details")
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request,
            File(outputPath, "ux-privacy-$phase"),
            matrix.flatMap { config -> frames.map { "${config.id}/privacy-$phase-$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (frame in frames) {
                        val state = UxState("privacy-$phase-$frame",
                            if (frame == "settings-about") "settings" else "onboarding",
                            "real-app", phase, null, null)
                        try {
                            val base = fixtures.state(if (frame == "settings-about") "ready" else "fresh")
                            prepare(base, config)
                            navigate(base)
                            when (frame) {
                                "settings-about" -> {
                                    compose.onNode(navigationMatcher(R.string.nav_settings)).performClick()
                                    await("settings-version")
                                    revealControl("settings-version")
                                }
                                "onboarding-provider" -> revealControl("provider-custom")
                                "onboarding-details" -> {
                                    compose.onNodeWithTag("provider-groq").performScrollTo().performClick()
                                    await("api-key")
                                    compose.onNode(hasText(target.getString(R.string.onboarding_back)) and hasClickAction())
                                        .performScrollTo()
                                }
                            }
                            val link = hasText("Privacy policy") and hasClickAction()
                            val controls = if (phase == "after") {
                                compose.onNode(link).performScrollTo().assertIsDisplayed()
                                listOf(UxControl("privacy-policy", link))
                            } else {
                                compose.onNode(link).assertDoesNotExist()
                                when (frame) {
                                    "settings-about" -> listOf(textControl("how-it-works", R.string.settings_guide))
                                    "onboarding-provider" -> listOf(tag("provider-custom"))
                                    else -> listOf(textControl("back", R.string.onboarding_back))
                                }
                            }
                            recorder.capture(state, config, checkNotNull(activity), controls,
                                nativeSurface = state.surface,
                                nativeMapping = "Real MainActivity $frame; $phase privacy-link integration, actual system dynamic colour.")
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
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

    @Test
    fun requestedShellEvidenceKeepsInsetsAndIdentity() {
        val bundle = InstrumentationRegistry.getArguments()
        val optIn = bundle.getString("uxShellProbe")
        if (optIn.isNullOrEmpty()) {
            require(bundle.getString("class")?.contains("#requestedShellEvidenceKeepsInsetsAndIdentity") != true) {
                "the selected shell probe requires uxShellProbe=true and capture provenance"
            }
            return
        }
        require(optIn == "true") { "uxShellProbe must be true when requested" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "shell evidence has a fixed complete matrix" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val windows = when (request.window) {
            "compact" -> listOf("compact", "medium", "compact-landscape")
            "expanded" -> listOf("expanded")
            else -> listOf("compact", "medium", "compact-landscape", "expanded")
        }
        val sizes = mapOf("compact" to (411 to 914), "medium" to (720 to 960),
            "compact-landscape" to (640 to 400), "expanded" to (1280 to 900))
        val baseMatrix = windows.flatMap { window ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map {
                    UxConfiguration(window, theme, it, requestedWidthDp = sizes.getValue(window).first,
                        requestedHeightDp = sizes.getValue(window).second)
                }
            }
        }
        val states = listOf("fresh", "ready", "library-long", "interview-long", "result-long")
        val frames = states + listOf("shell-interview-ime", "shell-voice-paused")
        val environment = UxDeviceEnvironment(instrumentation.uiAutomation)
        check(environment.navigationModes.isNotEmpty()) { "no supported system-navigation overlay is installed" }
        val navigationFrames = listOf("interview-long", "result-long", "shell-interview-ime", "shell-voice-paused")
        val matrix = baseMatrix + baseMatrix.filter { it.theme == "light" && it.fontScale == 2f }.flatMap { config ->
            environment.navigationModes.map { config.copy(navigationMode = it) }
        }
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, File(outputPath, "ux-shell"),
            matrix.flatMap { config ->
                (if (config.navigationMode == null) frames else navigationFrames).map { "${config.id}/$it" }
            }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            environment.use {
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    config.navigationMode?.let { mode ->
                        environment.selectNavigationMode(mode)
                        compose.waitUntil(DEVICE_TIMEOUT) { environment.navigationModeApplied(mode) }
                    }
                    for (id in if (config.navigationMode == null) states else listOf("interview-long", "result-long")) {
                        val state = fixtures.state(id)
                        try {
                            prepare(state, config)
                            val controls = navigate(state)
                            if (id == "fresh") {
                                compose.onNodeWithTag(NAVIGATION).assertDoesNotExist()
                            } else {
                                requireNavigation(config)
                                if (id == "interview-long" || id == "result-long") {
                                    val session = checkNotNull(fixtureGraph!!.sessionStore.get(requireNotNull(state.sessionId)))
                                    assertEquals(sessionDisplayTitle(session),
                                        nodeText(if (id == "interview-long") "interview-title" else ResultTags.TITLE))
                                }
                            }
                            recorder.capture(state, config, checkNotNull(activity), controls,
                                nativeMapping = "Real MainActivity adaptive shell; actual system dynamic colour and requested display/font configuration.")
                            if (id == "interview-long") {
                                revealControl("answer")
                                compose.onNodeWithTag("answer").performClick()
                                compose.waitUntil(DEVICE_TIMEOUT) {
                                    activity?.window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                                }
                                val draft = "The same editable draft survives the shell resize and recreation."
                                compose.onNodeWithTag("answer").performTextReplacement(draft)
                                recorder.capture(state.copy(id = "shell-interview-ime"), config, checkNotNull(activity), interviewControls(),
                                    nativeMapping = "Real MainActivity with actual IME; the composer consumes only keyboard clearance beyond the shell.")
                                hideKeyboard()
                                val ids = runBlocking { fixtureGraph!!.sessionStore.knownIds() }
                                val other = if (config.window == "compact") "expanded" else "compact"
                                environment.configure(other, config.fontScale, resize = true)
                                scenario!!.recreate()
                                scenario!!.onActivity { activity = it }
                                await("answer")
                                assertEquals(draft, compose.onNodeWithTag("answer").fetchSemanticsNode().config
                                    .getOrNull(SemanticsProperties.EditableText)?.text)
                                assertEquals(ids, runBlocking { fixtureGraph!!.sessionStore.knownIds() })
                                assertEquals(sessionDisplayTitle(checkNotNull(fixtureGraph!!.sessionStore.get(requireNotNull(state.sessionId)))),
                                    nodeText("interview-title"))
                                environment.configure(config.window, config.fontScale, resize = true)
                            } else if (id == "result-long") {
                                val ids = runBlocking { fixtureGraph!!.sessionStore.knownIds() }
                                revealControl(ResultTags.ASK_MORE)
                                compose.onNodeWithTag(ResultTags.ASK_MORE).assertIsDisplayed().performClick()
                                await("answer")
                                assertEquals(ids, runBlocking { fixtureGraph!!.sessionStore.knownIds() })
                                assertEquals("interviewing", stringOf(fixtureGraph!!.sessionStore.get(requireNotNull(state.sessionId))!!["status"]))
                            } else if (id == "library-long") {
                                val title = sessionDisplayTitle(checkNotNull(fixtureGraph!!.sessionStore.get("ux-long-active")))
                                scenario!!.recreate()
                                scenario!!.onActivity { activity = it }
                                await(LibraryTags.DETAIL)
                                compose.onNode(hasTestTag(LibraryTags.DETAIL) and hasAnyDescendant(hasText(title))).assertExists()
                            }
                        } catch (error: Throwable) {
                            recorder.failed(state, config, error, activity)
                        } finally {
                            compose.runOnUiThread { fixtureGraph?.driveController?.exit() }
                            closeFixture()
                            environment.configure(config.window, config.fontScale, resize = true)
                        }
                    }
                    val base = fixtures.state("interview-long")
                    val paused = base.copy(id = "shell-voice-paused", surface = "voice", phase = "paused")
                    try {
                        prepare(base, config)
                        navigate(base)
                        val graph = checkNotNull(fixtureGraph)
                        graph.driveSpeaker = object : Speaker {
                            override suspend fun speak(text: String): SpeakResult = awaitCancellation()
                            override fun cancel() {}
                            override fun shutdown() {}
                        }
                        val serviceStarts = AtomicInteger()
                        graph.driveService = object : DriveServiceControl {
                            override fun start() { serviceStarts.incrementAndGet() }
                            override fun stop() {}
                        }
                        val session = checkNotNull(graph.sessionStore.get("ux-long-active"))
                        compose.runOnUiThread { graph.driveController.start(session, "typed") }
                        // An immediate Pause may correctly retire Start before asynchronous consent admission.
                        compose.waitUntil(DEVICE_TIMEOUT) { serviceStarts.get() == 1 }
                        compose.runOnUiThread { graph.driveController.pause() }
                        await("voice-stage")
                        assertEquals(VoiceMode.PAUSED, graph.driveController.state.value.mode)
                        assertEquals(1, serviceStarts.get())
                        compose.onNodeWithTag(NAVIGATION).assertDoesNotExist()
                        compose.onNodeWithTag("interview-back").assertDoesNotExist()
                        val view = checkNotNull(activity).window.decorView
                        val insets = view.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        val stage = compose.onNodeWithTag("voice-stage").fetchSemanticsNode().boundsInWindow
                        assertEquals("voice receives status clearance once", insets.top.toFloat(), stage.top, 1f)
                        assertEquals("voice receives navigation clearance once", (view.height - insets.bottom).toFloat(), stage.bottom, 1f)
                        recorder.capture(paused, config, checkNotNull(activity), listOf(tag("voice-resume"), tag("voice-exit")),
                            nativeMapping = "Real MainActivity and app-scope DriveController paused with scripted speech and a recorded fake service; no microphone, permission UI or actual service behavior is proved.")
                        compose.onNodeWithTag("voice-exit").performClick()
                        await(NAVIGATION)
                        requireNavigation(config)
                    } catch (error: Throwable) {
                        recorder.failed(paused, config, error, activity)
                    } finally {
                        closeFixture()
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() },
                { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) },
            )) { errors ->
                errors.forEach(recorder::failedRun)
                recorder.finish()
            }
        }
    }

    private fun requireNavigation(config: UxConfiguration) {
        val rail = config.window != "compact"
        await(if (rail) NAVIGATION_RAIL else BOTTOM_BAR)
        compose.onNodeWithTag(if (rail) BOTTOM_BAR else NAVIGATION_RAIL).assertDoesNotExist()
        val bounds = compose.onNodeWithTag(if (rail) NAVIGATION_RAIL else BOTTOM_BAR).fetchSemanticsNode().boundsInWindow
        assertTrue("navigation orientation follows the actual window class", if (rail) bounds.height > bounds.width else bounds.width > bounds.height)
    }

    @Test
    fun requestedShortResultEvidenceKeepsTheFullDocumentAndActionsReachable() {
        val bundle = InstrumentationRegistry.getArguments()
        val optIn = bundle.getString("uxShortResultProbe")
        if (optIn == null) {
            require(bundle.getString("class")?.contains("#requestedShortResultEvidenceKeepsTheFullDocumentAndActionsReachable") != true) {
                "the selected short Result capture requires uxShortResultProbe=true and capture provenance"
            }
            return
        }
        require(optIn == "true") { "uxShortResultProbe must be true when requested" }
        val args = bundle.keySet().associateWith { bundle.get(it).toString() }
        val request = requireNotNull(UxCaptureRequest.from(args)) { "short Result evidence requires explicit capture provenance" }
        require(request.mode == "matrix" && request.states == null && request.window in setOf("all", "compact")) {
            "short Result evidence requires its complete fixed landscape matrix, without state selectors"
        }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val state = fixtures.state("result-long")
        val matrix = listOf(320, 400).flatMap { height ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration("compact-landscape-h$height", theme, it,
                    requestedWidthDp = 640, requestedHeightDp = height) }
            }
        }
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, File(outputPath, "ux-short-result"),
            matrix.map { "${it.id}/${state.id}" }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure("compact-landscape", config.fontScale, resize = true,
                        heightPx = requireNotNull(config.requestedHeightDp) * 3 / 2)
                    try {
                        prepare(state, config)
                        navigate(state)
                        val viewport = compose.onNodeWithTag(ResultTags.CONTENT).fetchSemanticsNode().boundsInWindow
                        assertTrue("the actual Result document viewport is nonzero", viewport.height > 0f)
                        val output = compose.onNodeWithTag(ResultTags.OUTPUT).fetchSemanticsNode()
                        fun textNodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                            listOf(node).filter { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) } +
                                node.children.flatMap(::textNodes)
                        val texts = textNodes(output)
                        assertTrue("the full long export is present", texts.size > 10)
                        for (text in texts) {
                            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                            compose.runOnUiThread {
                                text.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                            }
                            for (layout in layouts) {
                                val lines = (0 until layout.lineCount).toList()
                                UxEvidence.requireLabel("Result document",
                                    lines.maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f,
                                    lines.maxOfOrNull(layout::getLineBottom) ?: 0f, layout.size.width, layout.size.height,
                                    layout.multiParagraph.didExceedMaxLines || lines.any(layout::isLineEllipsized))
                            }
                        }
                        for (text in listOf(texts.first(), texts.last())) {
                            compose.onNode(SemanticsMatcher("document ${text.id}") { it.id == text.id }).performScrollTo().assertIsDisplayed()
                        }
                        recorder.capture(state, config, checkNotNull(activity), resultControls(),
                            nativeMapping = "Real MainActivity Result at 640dp display width and ${config.requestedHeightDp}dp display height; height-constrained landscape, not the expanded-width fixture.",
                            documentViewport = UxBounds(viewport.left, viewport.top, viewport.right, viewport.bottom))
                    } catch (error: Throwable) {
                        recorder.failed(state, config, error, activity)
                    } finally {
                        closeFixture()
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() },
                { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) },
            )) { errors ->
                errors.forEach(recorder::failedRun)
                recorder.finish()
            }
        }
    }

    @Test
    fun requestedFoundationEvidenceSeparatesFallbackAndSimulatedPalettesFromActualWallpaper() {
        val bundle = InstrumentationRegistry.getArguments()
        val optIn = bundle.getString("uxFoundationProbe")
        if (optIn == null) {
            require(bundle.getString("class")?.contains("#requestedFoundationEvidenceSeparatesFallbackAndSimulatedPalettesFromActualWallpaper") != true) {
                "the selected foundation capture method requires uxFoundationProbe=true and explicit capture provenance"
            }
            return
        }
        require(optIn == "true") { "uxFoundationProbe must be true when requested" }
        val args = bundle.keySet().associateWith { bundle.get(it).toString() }
        val request = requireNotNull(UxCaptureRequest.from(args)) { "foundation evidence requires explicit capture provenance" }
        require(request.mode == "matrix" && request.states == null) { "foundation evidence has its own complete component matrix; no state selectors" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }) {
            "requested foundation evidence needs Gradle's additionalTestOutputDir"
        }
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).flatMap { scale ->
                    UxFoundationPalette.entries.map { UxConfiguration(window, theme, scale, it.id) }
                }
            }
        }
        fun state(config: UxConfiguration) = UxState("foundation-${config.palette}", "shared-controls", "isolated-component", null, null, null)
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, File(outputPath, "ux-foundation"),
            matrix.map { "${it.id}/${state(it).id}" }.toSet())
        var primaryError: Throwable? = null
        try {
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    val current = state(config)
                    environment.configure(config.window, config.fontScale, resize = true)
                    try {
                        val intent = Intent(target, UxFixtureActivity::class.java)
                            .putExtra("theme", config.theme).putExtra("foundationPalette", config.palette)
                        val launched = ActivityScenario.launch<UxFixtureActivity>(intent)
                        scenario = launched
                        launched.onActivity { activity = it }
                        await("ux-isolated-label")
                        assertTrue(nodeText("ux-isolated-label").contains(requireNotNull(config.palette)))
                        val palette = UxFoundationPalette.from(requireNotNull(config.palette))
                        assertTrue("synthetic provenance must be visible",
                            nodeText("ux-foundation-provenance").contains(if (palette == UxFoundationPalette.FocusStudio) "dynamicColor=false" else "not actual wallpaper"))
                        val controls = listOf("primary", "tonal", "outlined", "quiet", "field", "field-error", "suggestion",
                            "selected", "disabled", "busy", "destructive", "menu", "dialog", "voice-pause", "voice-exit").map { tag("foundation-$it") } +
                            listOf("warning", "error").map { UxControl("foundation-$it", hasTestTag("foundation-$it"), actionRequired = false) }
                        recorder.capture(current, config, checkNotNull(activity), controls,
                            nativeMapping = if (palette == UxFoundationPalette.FocusStudio)
                                "Production shared controls composed through IdeaForgeTheme(dynamicColor=false). This component fixture invokes no app/provider/voice effects."
                            else "Production shared controls on an explicitly simulated Material scheme, not actual wallpaper colours. This component fixture invokes no app/provider/voice effects.")
                    } catch (error: Throwable) {
                        recorder.failed(current, config, error, activity)
                    } finally {
                        closeFixture()
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf({ closeFixture() })) { errors ->
                errors.forEach(recorder::failedRun)
                recorder.finish()
            }
        }
    }

    @Test
    fun requestedShortImeEvidenceKeepsTheEditorAndActionsUsableAboveAnOpenKeyboard() {
        val bundle = InstrumentationRegistry.getArguments()
        val optIn = bundle.getString("uxShortImeProbe") ?: return
        require(optIn == "true") { "uxShortImeProbe must be true when requested" }
        val args = bundle.keySet().associateWith { bundle.get(it).toString() }
        val request = requireNotNull(UxCaptureRequest.from(args)) { "short IME evidence requires explicit capture provenance" }
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() })
        fixtures = UxFixtures.load(target)
        val state = fixtures.state("interview-long")
        val matrix = listOf(900, 700).flatMap { height ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration("expanded-ime-h$height", theme, it) to height }
            }
        }
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, File(outputPath, "ux-short-ime"),
            matrix.map { "${it.first.id}/${state.id}" }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for ((config, height) in matrix) {
                    environment.configure("expanded", config.fontScale, resize = true, heightPx = height)
                    try {
                        prepare(state, config)
                        navigate(state)
                        revealControl("answer")
                        compose.onNodeWithTag("answer").performClick()
                        compose.waitUntil(DEVICE_TIMEOUT) { activity?.window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                        revealControl("answer")
                        val typed = "An editable answer while the landscape keyboard stays open"
                        compose.onNodeWithTag("answer").performTextReplacement(typed)
                        assertEquals(typed, compose.onNodeWithTag("answer").fetchSemanticsNode().config
                            .getOrNull(SemanticsProperties.EditableText)?.text)
                        assertTrue("the editor retains real height", compose.onNodeWithTag("answer").fetchSemanticsNode().boundsInWindow.height > 0f)
                        assertTrue("the actual keyboard remains open", activity!!.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
                        recorder.capture(state, config, checkNotNull(activity), interviewControls(),
                            nativeMapping = "Real MainActivity long interview in a short landscape window with the actual IME open.")
                        assertTrue("reachability scrolling must not close the keyboard", activity!!.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
                    } catch (error: Throwable) {
                        recorder.failed(state, config, error, activity)
                    } finally {
                        closeFixture()
                    }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() },
                { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) },
            )) { errors ->
                errors.forEach(recorder::failedRun)
                recorder.finish()
            }
        }
    }

    @Test
    fun requestedUxEvidenceIsRenderedCompleteAndReachable() {
        val bundle = InstrumentationRegistry.getArguments()
        val args = bundle.keySet().associateWith { bundle.get(it).toString() }
        val request = UxCaptureRequest.from(args) ?: return
        captureUxEvidence(request)
    }

    @Test
    fun requestedContentReportsKeepLocalPayloadAndQuestionIdentity() {
        val bundle = InstrumentationRegistry.getArguments()
        if (bundle.getString("uxReportingProbe") != "true") {
            require(bundle.getString("class")?.contains("#requestedContentReportsKeepLocalPayloadAndQuestionIdentity") != true) {
                "the selected reporting acceptance requires uxReportingProbe=true"
            }
            return
        }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "reporting acceptance has a fixed complete inventory" }
        val output = File(requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }), "ux-reporting")
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window -> listOf("light", "dark").flatMap { theme ->
            listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
        } }
        val frames = listOf("question", "suggestions", "dialog", "saving", "save-failure", "saved", "replaced",
            "history", "result", "library", "voice")
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, output,
            matrix.flatMap { config -> frames.map { "${config.id}/report-$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-reporting-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    try {
                        captureReportingJourney(config, recorder)
                    } catch (error: Throwable) {
                        recorder.failedRun(error)
                        var diagnostic = activity
                        for (key in UxEvidence.unrecorded(frames.map { "${config.id}/report-$it" }.toSet(), recorder.recordedKeys)) {
                            recorder.failed(UxState(key.substringAfter('/'), "reporting", "real-app", null, null, null),
                                config, error, diagnostic)
                            diagnostic = null
                        }
                    } finally { closeFixture() }
                }
            }
        } catch (error: Throwable) {
            primaryError = error
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { closeFixture() }, { app.graph = originalGraph }, { check(runDir.deleteRecursively()) },
            )) { errors ->
                errors.forEach(recorder::failedRun)
                recorder.finish()
            }
        }
    }

    private fun captureReportingJourney(config: UxConfiguration, recorder: UxCaptureRecorder) {
        fun click(name: String) {
            // Lazy history can be composed but unplaced; node existence is not visibility.
            val lazyHistory = name.startsWith("report-history-") &&
                compose.onAllNodes(hasTestTag("thread") and hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
            if (lazyHistory || !exists(name)) compose.onNodeWithTag("thread").performScrollToNode(hasTestTag(name))
            runCatching { compose.onNodeWithTag(name).performScrollTo() }
            compose.onNodeWithTag(name).assertIsDisplayed().performClick()
        }
        fun capture(frame: String, controls: List<UxControl>, dialog: Boolean = true) = recorder.capture(
            UxState("report-$frame", "reporting", "real-app", null, null, null),
            config, checkNotNull(activity), controls,
            nativeMapping = "Real MainActivity reporting with neutral private fixtures. Saves and replacement use the real store/worker/reducers; held-lock loading and refused report writes are controlled faults. Voice output/service are scripted; no recognizer, email composer or real provider is invoked.",
            rootSurfaceRole = if (dialog) "surfaceContainerHigh" else "background",
        )
        val base = fixtures.state("interview-long")
        val rejected = AtomicBoolean(false)
        prepare(base, config, reportWritesRejected = rejected)
        navigate(base)
        val graph = checkNotNull(fixtureGraph)
        val id = requireNotNull(base.sessionId)
        val before = checkNotNull(graph.sessionStore.get(id))
        val open = io.github.jaypetez.ideaforge.core.openTurn(before)!!
        val turnId = stringOf(open["id"])!!
        val output = io.github.jaypetez.ideaforge.core.reportableOutput(before, "question", turnId)!!
        capture("question", listOf(tag("report-current-question"), tag("report-current-suggestions"), tag("chip-0")), dialog = false)
        click("report-current-suggestions")
        await("content-report-dialog")
        capture("suggestions", listOf(tag("report-review"), tag("report-category-other"), tag("report-cancel")))
        click("report-cancel")
        assertEquals(null, graph.sessionStore.get(id)!!["contentReports"])
        click("report-current-question")
        await("content-report-dialog")
        click("report-category-inaccurate")
        val note = "Neutral optional report note marker. ".repeat(4).trim()
        compose.onNodeWithTag("report-note").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(note)) }
        hideKeyboard()
        capture("dialog", listOf(tag("report-category-inaccurate"), tag("report-note"), tag("report-save"), tag("report-cancel")))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold = graph.appScope.launch { graph.sessionWorker.exclusive(id) { entered.complete(Unit); release.await() } }
        try {
            runBlocking { withTimeout(DEVICE_TIMEOUT) { entered.await() } }
            rejected.set(true)
            click("report-save")
            await("report-saving")
            assertEquals(null, graph.sessionStore.get(id)!!["contentReports"])
            capture("saving", listOf(UxControl("report-saving", hasTestTag("report-saving"), actionRequired = false), tag("report-save")))
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(DEVICE_TIMEOUT) { hold.join() } }
        }
        await("report-error")
        assertTrue(nodeText("report-error").contains("report was not saved"))
        assertEquals(null, graph.sessionStore.get(id)!!["contentReports"])
        capture("save-failure", listOf(UxControl("report-error", hasTestTag("report-error"), actionRequired = false),
            tag("report-note"), tag("report-save"), tag("report-cancel")))
        rejected.set(false)
        click("report-save")
        await("report-saved")
        val report = (graph.sessionStore.get(id)!!["contentReports"] as JsonArray).single()
        assertEquals(note, stringOf(report["note"]))
        assertEquals(output, report["output"])
        assertTrue(exportFor(graph.sessionStore.get(id)!!).contains(note))
        capture("saved", listOf(tag("report-replace"), tag("report-email"), tag("report-done")))
        click("report-replace")
        await("report-replaced")
        val after = graph.sessionStore.get(id)!!
        for (key in listOf("coverage", "facts", "zeroGainStreak", "opening", "draftAnswer"))
            assertEquals("replacement leaves $key unchanged", before[key], after[key])
        assertEquals((before["turns"] as JsonArray).size, (after["turns"] as JsonArray).size)
        val replaced = io.github.jaypetez.ideaforge.core.openTurn(after)!!
        assertEquals(turnId, stringOf(replaced["id"]))
        assertEquals("", stringOf(replaced["answer"]))
        assertTrue(!truthy(replaced["skipped"]))
        assertEquals("replacement", stringOf(replaced["questionSource"]))
        assertTrue(replaced["question"] != open["question"])
        click("report-done")
        await("replacement-source")
        compose.onNodeWithTag("report-current-question").assertDoesNotExist()
        capture("replaced", listOf(UxControl("replacement-source", hasTestTag("replacement-source"), actionRequired = false),
            tag("answer"), tag("send")), dialog = false)

        click("history-toggle")
        val past = (before["turns"] as JsonArray).first { stringOf(it["questionSource"]) == "model" && truthy(it["answer"]) }
        click("report-history-question-${stringOf(past["id"])}")
        await("content-report-dialog")
        capture("history", listOf(tag("report-category-other"), tag("report-save"), tag("report-cancel")))
        click("report-cancel")
        closeFixture()

        val result = fixtures.state("result-long")
        prepare(result, config)
        navigate(result)
        click("report-result")
        await("content-report-dialog")
        capture("result", listOf(tag("report-review"), tag("report-category-other"), tag("report-cancel")))
        click("report-cancel")
        closeFixture()

        prepare(base, config)
        val voiceGraph = checkNotNull(fixtureGraph)
        val starts = AtomicInteger()
        val speeches = AtomicInteger()
        voiceGraph.driveService = object : DriveServiceControl {
            override fun start() { starts.incrementAndGet() }
            override fun stop() {}
        }
        voiceGraph.driveSpeaker = object : Speaker {
            override suspend fun speak(text: String): SpeakResult { speeches.incrementAndGet(); return awaitCancellation() }
            override fun cancel() {}
            override fun shutdown() {}
        }
        navigate(base)
        compose.runOnUiThread { voiceGraph.driveController.start(checkNotNull(voiceGraph.sessionStore.get(id)), "typed") }
        await("voice-stage")
        compose.waitUntil(DEVICE_TIMEOUT) { starts.get() == 1 && speeches.get() == 1 }
        click("report-voice-question")
        await("content-report-dialog")
        assertEquals(VoiceMode.PAUSED, voiceGraph.driveController.state.value.mode)
        capture("voice", listOf(tag("report-category-other"), tag("report-save"), tag("report-cancel")))
        assertEquals("opening Report never restarts speech", 1, speeches.get())
        assertEquals("opening Report never starts another service", 1, starts.get())
        click("report-cancel")
        compose.runOnUiThread { voiceGraph.driveController.resume() }
        compose.waitUntil(DEVICE_TIMEOUT) { speeches.get() == 2 }
        scenario!!.onActivity { (it as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed() }
        await(LibraryTags.DETAIL)
        assertEquals("Back preserves the app-graph run until reporting pauses it", VoiceMode.ACTIVE,
            voiceGraph.driveController.state.value.mode)
        val libraryBefore = voiceGraph.sessionStore.get(id)!!
        val libraryTurn = io.github.jaypetez.ideaforge.core.openTurn(libraryBefore)!!
        click("report-library-question-${stringOf(libraryTurn["id"])}")
        await("content-report-dialog")
        assertEquals("Ideas reporting must pause the same surviving run", VoiceMode.PAUSED,
            voiceGraph.driveController.state.value.mode)
        click("report-category-other")
        click("report-save")
        await("report-saved")
        click("report-replace")
        await("report-replaced")
        val libraryAfter = voiceGraph.sessionStore.get(id)!!
        assertEquals(libraryBefore["coverage"], libraryAfter["coverage"])
        assertEquals((libraryBefore["turns"] as JsonArray).size, (libraryAfter["turns"] as JsonArray).size)
        assertEquals(stringOf(io.github.jaypetez.ideaforge.core.openTurn(libraryAfter)!!["question"]),
            voiceGraph.driveController.state.value.question)
        assertEquals("replacement from Ideas never resumes speech", 2, speeches.get())
        capture("library", listOf(UxControl("report-replaced", hasTestTag("report-replaced"), actionRequired = false),
            tag("report-email"), tag("report-done")))
    }

    @Test
    fun requestedIdeasAreCompactReachableAndTruthful() {
        val bundle = InstrumentationRegistry.getArguments()
        val probe = bundle.getString("uxIdeasProbe")
        if (probe == null) {
            require(bundle.getString("class")?.contains("#requestedIdeasAreCompactReachableAndTruthful") != true) {
                "the selected Ideas probe requires uxIdeasProbe=true and its complete capture inventory"
            }
            return
        }
        require(probe == "true") { "uxIdeasProbe must be true" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states?.toSet() == setOf(
            "ready", "empty", "unavailable", "library", "library-100", "library-archived", "filtered", "library-long", "ideas-loading",
        )) { "Ideas evidence requires its complete fixed state matrix" }
        captureUxEvidence(request, nativeIdeas = true)
    }

    private fun captureLibraryExports(config: UxConfiguration, recorder: UxCaptureRecorder, systemOutput: File) {
        check(fixtureGraph == null && scenario == null && heldLibraryRead == null) {
            "export evidence must start after the preceding fixture's complete cleanup"
        }
        check(libraryExportFrames.none { "${config.id}/$it" in recorder.recordedKeys }) {
            "export evidence must run exactly once per configuration"
        }
        val base = fixtures.state("library-long")
        val id = "ux-long-active"
        val calls = AtomicInteger()
        val rejecting = ProviderSource {
            calls.incrementAndGet()
            object : Provider {
                override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult =
                    throw ProviderException("network", "Controlled unavailable library-export provider.")
            }
        }
        if (config.theme == "light" && config.fontScale == 1f) {
            prepare(base, config)
            checkNotNull(fixtureGraph).providerSource = rejecting
            launchMain(scripted = false)
            selectSession(id)
            awaitEditableQuestion()
            revealControl("answer")
            compose.onNodeWithTag("answer").performSemanticsAction(SemanticsActions.SetText) {
                check(it(androidx.compose.ui.text.AnnotatedString("A deliberate control answer reaches the installed rejecting provider.")))
            }
            revealControl("send")
            compose.onNodeWithTag("send").performClick()
            compose.waitUntil(DEVICE_TIMEOUT) { calls.get() > 0 && !exists("interview-busy") }
            assertEquals("the actual non-scripted app reached its installed negative control", "bank",
                stringOf(io.github.jaypetez.ideaforge.core.openTurn(fixtureGraph!!.sessionStore.get(id)!!)?.get("questionSource")))
            closeFixture()
        }
        calls.set(0)
        val rejectWrite = AtomicBoolean(false)
        val realSink = ContentResolverSink(target.contentResolver)
        prepare(base, config, inferenceAgreed = false, documentSink = DocumentSink { uri ->
            if (rejectWrite.get()) throw IOException("Neutral controlled document write refusal.")
            realSink.openOutput(uri)
        })
        val graph = checkNotNull(fixtureGraph)
        graph.providerSource = rejecting
        val original = checkNotNull(graph.sessionStore.get(id))
        val question = checkNotNull(io.github.jaypetez.ideaforge.core.openTurn(original))
        val reported = io.github.jaypetez.ideaforge.core.addContentReport(original, str("r_library_export"),
            io.github.jaypetez.ideaforge.core.reportableOutput(original, "question", stringOf(question["id"]))!!,
            str("other"), str("Neutral private local report; included only in the canonical file."), num(fixtures.now))
        runBlocking { graph.sessionStore.save(reported) }
        val unchanged = graph.sessionStore.sessions.value.toList()
        val export = exportFor(reported)
        launchMain(scripted = false)
        await(LibraryTags.SEARCH)
        runBlocking { graph.keyringStore.forget() }
        assertTrue("local export has no inference grant", graph.inferenceConsent.state.value.permit == null)
        assertTrue("local export has no usable provider key", !io.github.jaypetez.ideaforge.ui.onboarding.hasUsableProvider(
            graph.keyringStore.state.value.ring))
        val list = hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST))
        compose.onNode(list).performScrollToNode(hasTestTag(LibraryTags.more(id)))
        compose.onNodeWithTag(LibraryTags.more(id)).assertIsDisplayed().performClick()
        fun capture(frame: String, controls: List<UxControl>, menu: Boolean = false) = recorder.capture(
            base.copy(id = frame), config, checkNotNull(activity), controls, nativeSurface = "library",
            nativeMapping = "Real MainActivity Ideas; no inference grant/key, installed rejecting provider (non-scripted launch). " +
                "Canonical snapshot with draft and local report; real native share/SAF, controlled sink refusal only for the named error.",
            rootSurfaceRole = if (menu) "surfaceContainer" else "background",
        )
        capture("ideas-export-menu", listOf(tag(LibraryTags.share(id)), tag(LibraryTags.save(id)),
            tag(LibraryTags.details(id)), textControl("rename", R.string.idea_edit),
            textControl("archive", R.string.idea_archive), textControl("delete", R.string.idea_delete)), menu = true)
        UxSystemActions(instrumentation, target, systemOutput).use { system ->
            system.requireCommandTransport()
            system.share { compose.onNodeWithTag(LibraryTags.share(id)).performScrollTo().assertIsDisplayed().performClick() }
            system.awaitExternalWindow()
            system.requireShared(export)
            system.snapshot("ideas-share-cancel")
            system.back()
            await(LibraryTags.SEARCH)
            assertEquals(unchanged, graph.sessionStore.sessions.value)
            compose.onNodeWithTag("answer").assertDoesNotExist()
            compose.onNodeWithTag(ResultTags.SCREEN).assertDoesNotExist()
            selectSession(id, open = false)
            val controls = listOf(textControl("continue", R.string.idea_continue),
                tag(LibraryTags.share(id)), tag(LibraryTags.save(id)), tag(LibraryTags.detailMore(id)))
            capture("ideas-export-long", controls)
            fun save() {
                revealControl(LibraryTags.save(id))
                compose.onNodeWithTag(LibraryTags.save(id)).assertIsDisplayed().performClick()
            }
            fun notice(frame: String, text: Int) {
                val expected = target.getString(text, sessionDisplayTitle(reported))
                compose.waitUntilAtLeastOneExists(hasText(expected), DEVICE_TIMEOUT)
                capture(frame, listOf(UxControl("named-export-result", hasText(expected), actionRequired = false),
                    tag(LibraryTags.EXPORT_DISMISS)) + controls)
            }
            save()
            system.awaitExternalWindow()
            system.snapshot("ideas-save-cancel")
            system.back()
            notice("ideas-export-cancelled", R.string.idea_export_cancelled)
            rejectWrite.set(true)
            save()
            val refused = system.createDocument("ideas-failed.md")
            notice("ideas-export-error", R.string.idea_export_save_failed)
            assertEquals("a refused sink writes no fake success bytes", "", system.readDocument(refused))
            rejectWrite.set(false)
            save()
            val saved = system.createDocument("ideas.md")
            compose.waitUntil(DEVICE_TIMEOUT) { system.readDocument(saved) == export }
            notice("ideas-export-saved", R.string.idea_export_saved)
            assertEquals("the saved document is the complete canonical file", export, system.readDocument(saved))
            assertEquals("sharing/saving never built a provider", 0, calls.get())
            assertEquals("exports, cancellation and write failure never change a session", unchanged, graph.sessionStore.sessions.value)
            compose.onNodeWithTag("answer").assertDoesNotExist()
            compose.onNodeWithTag(ResultTags.SCREEN).assertDoesNotExist()
        }
    }

    /**
     * A cancelled Save for a maximum-length name in the two window shapes that once pushed
     * Dismiss out of the window and collapsed the pane: 640x400dp (ASCII) and 320x720dp (a
     * full-width CJK name). Dismiss and the row's More must be reachable native targets, the
     * pane must keep height, and the complete named text must be present and scrollable.
     */
    private fun captureExportFeedback(config: UxConfiguration, recorder: UxCaptureRecorder, systemOutput: File) {
        check(fixtureGraph == null && scenario == null && heldLibraryRead == null) {
            "export feedback evidence must start after the preceding fixture's complete cleanup"
        }
        val base = fixtures.state("library-long")
        val id = "ux-long-active"
        val name = if (config.window == "compact-narrow") "会議で会った人の名前を思い出すためのアプリ".repeat(6).take(MAX_SESSION_NAME)
            else "A deliberately long idea name ".padEnd(MAX_SESSION_NAME, 'x')
        check(name.length == MAX_SESSION_NAME)
        prepare(base, config, inferenceAgreed = false)
        val graph = checkNotNull(fixtureGraph)
        val renamed = renameSession(checkNotNull(graph.sessionStore.get(id)), str(name), num(fixtures.now))
        runBlocking { graph.sessionStore.save(renamed) }
        val unchanged = graph.sessionStore.sessions.value.toList()
        launchMain(scripted = false)
        await(LibraryTags.SEARCH)
        val list = hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LibraryTags.LIST))
        compose.onNode(list).performScrollToNode(hasTestTag(LibraryTags.more(id)))
        compose.onNodeWithTag(LibraryTags.more(id)).assertIsDisplayed().performClick()
        UxSystemActions(instrumentation, target, systemOutput).use { system ->
            compose.onNodeWithTag(LibraryTags.save(id)).performScrollTo().assertIsDisplayed().performClick()
            system.awaitExternalWindow()
            system.back()
        }
        val expected = target.getString(R.string.idea_export_cancelled, sessionDisplayTitle(renamed))
        compose.waitUntilAtLeastOneExists(hasText(expected), DEVICE_TIMEOUT)
        val text = compose.onNode(hasText(expected)).fetchSemanticsNode()
        val hidden = text.layoutInfo.coordinates.size.height - text.boundsInRoot.height
        if (hidden > 1f) {
            val range = generateSequence(text.parent) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
            assertTrue("clipped named feedback scrolls to its end", range != null && range.maxValue() >= hidden - 1f)
        }
        val pane = compose.onNodeWithTag(LibraryTags.LIST).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the list pane keeps at least one native target of height: $pane in $root",
            pane.bottom.coerceAtMost(root.bottom) - pane.top >= 48f * target.resources.displayMetrics.density)
        recorder.capture(
            base.copy(id = "ideas-export-feedback"), config, checkNotNull(activity),
            listOf(tag(LibraryTags.EXPORT_DISMISS), tag(LibraryTags.more(id))), nativeSurface = "library",
            nativeMapping = "Real MainActivity Ideas at ${config.requestedWidthDp}x${config.requestedHeightDp}dp: a real native Save picker " +
                "cancelled for a ${MAX_SESSION_NAME}-character ${if (config.window == "compact-narrow") "full-width CJK" else "ASCII"} name. " +
                "The complete named text is in the semantics tree and scrolls inside its bounded feedback.",
        )
        assertEquals("cancelling a save never changes a session", unchanged, graph.sessionStore.sessions.value)
    }

    @Test
    fun requestedManualDictationKeepsLiteralDraftAndExplicitSend() {
        val bundle = InstrumentationRegistry.getArguments()
        if (bundle.getString("uxDictationProbe") != "true") {
            require(bundle.getString("class")?.contains("#requestedManualDictationKeepsLiteralDraftAndExplicitSend") != true) {
                "the selected manual-dictation method requires uxDictationProbe=true"
            }
            return
        }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states == null) { "manual dictation has a fixed complete inventory" }
        val output = File(requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }), "ux-dictation")
        fixtures = UxFixtures.load(target)
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window -> listOf("light", "dark").flatMap { theme ->
            listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
        } }
        val frames = listOf("dictation-ready", "dictation-listening", "dictation-stopped", "dictation-error",
            "dictation-long", "dictation-report", "dictation-settings")
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, output,
            matrix.flatMap { config -> frames.map { "${config.id}/$it" } }.toSet())
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-dictation-run-${SystemClock.elapsedRealtimeNanos()}")
        var primary: Throwable? = null
        try {
            check(runDir.mkdirs())
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (frame in frames) {
                        val state = UxState(frame, if (frame == "dictation-settings") "settings" else "interview",
                            "real-app", null, null, null)
                        try {
                            prepare(state, config, nativeEngine = FixtureDeviceVoiceEngine("device-default"), manualNative = true)
                            val graph = checkNotNull(fixtureGraph)
                            val engines = mutableListOf<FixtureManualEngine>()
                            val serviceStarts = AtomicInteger()
                            graph.driveService = object : DriveServiceControl {
                                override fun start() { serviceStarts.incrementAndGet() }
                                override fun stop() = Unit
                            }
                            graph.recognizer = io.github.jaypetez.ideaforge.voice.Recognizer(
                                io.github.jaypetez.ideaforge.voice.RecognizerEngineFactory {
                                    FixtureManualEngine(graph.mainScope).also(engines::add)
                                }, graph.mainScope)
                            launchMain()
                            await(NAVIGATION)
                            compose.onNode(navigationMatcher(R.string.nav_new_idea)).performClick()
                            await("new-idea-type")
                            compose.onNodeWithTag("new-idea-type").performScrollTo().assertIsDisplayed().performClick()
                            awaitEditableQuestion()
                            assertTrue("Type/entry never starts recognition", engines.isEmpty())
                            var controls = interviewControls()
                            if (frame == "dictation-settings") {
                                compose.onNode(navigationMatcher(R.string.nav_settings)).performClick()
                                await(SettingsTags.TRIGGER)
                                revealControl(SettingsTags.TRIGGER)
                                compose.onNodeWithTag(SettingsTags.TRIGGER).assertIsDisplayed()
                                compose.onNodeWithTag("settings-handsfree").assertDoesNotExist()
                                assertTrue("opening Settings is never a microphone decision", engines.isEmpty())
                                controls = listOf(tag(SettingsTags.TRIGGER), tag(SettingsTags.DEVICE_VOICE), tag(SettingsTags.PACE))
                            } else if (frame != "dictation-ready") {
                                if (frame == "dictation-report") {
                                    revealControl("answer")
                                    compose.onNodeWithTag("answer").assertIsEnabled()
                                        .performTextReplacement("A local idea for remembering names.")
                                    compose.waitUntil(DEVICE_TIMEOUT) { editableAnswer() == "A local idea for remembering names." }
                                    hideKeyboard()
                                    assertEquals("the opening draft survives keyboard dismissal",
                                        "A local idea for remembering names.", editableAnswer())
                                    revealControl("send")
                                    compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled().performClick()
                                    await("report-current-question")
                                }
                                revealControl("answer")
                                compose.onNodeWithTag("answer").assertIsEnabled().performTextReplacement("Already typed words.")
                                compose.waitUntil(DEVICE_TIMEOUT) { editableAnswer() == "Already typed words." }
                                hideKeyboard()
                                assertEquals("the typed prefix is present before manual input starts", "Already typed words.", editableAnswer())
                                revealControl("dictation-toggle")
                                compose.onNodeWithTag("dictation-toggle").assertIsDisplayed().performClick()
                                allowManualMicrophoneIfRequested()
                                compose.waitUntil(DEVICE_TIMEOUT) { engines.size == 1 && engines.single().starts.get() == 1 }
                                val engine = engines.single()
                                val words = if (frame == "dictation-long") fixtures.text("answer") + " skip this one over"
                                    else "We went over budget; skip this one over."
                                engine.text = words
                                compose.runOnUiThread {
                                    if (frame == "dictation-error") engine.callbacks.onError(
                                        io.github.jaypetez.ideaforge.voice.RecognizerError.AUDIO)
                                    else engine.callbacks.onPartial(words, null, true)
                                }
                                if (frame == "dictation-error") {
                                    compose.waitUntil(DEVICE_TIMEOUT) { nodeText("dictation-status").contains(target.getString(R.string.dictation_failed)) }
                                    controls += UxControl("dictation-status", hasTestTag("dictation-status"), actionRequired = false)
                                    assertEquals("Already typed words.", editableAnswer())
                                    assertTrue("an engine failure releases its audio owner", engine.destroyed.get())
                                } else {
                                    await("dictation-caption")
                                    assertEquals(words, nodeText("dictation-caption"))
                                    assertEquals(target.getString(R.string.dictation_stop), nodeText("dictation-toggle"))
                                    assertEquals("a manual final/finish word never auto-sends or changes the editor", "Already typed words.", editableAnswer())
                                    if (frame == "dictation-listening") {
                                        controls += listOf(
                                            UxControl("dictation-status", hasTestTag("dictation-status"), actionRequired = false),
                                            UxControl("dictation-caption", hasTestTag("dictation-caption"), actionRequired = false))
                                    } else if (frame == "dictation-report") {
                                        revealControl("report-current-question")
                                        compose.onNodeWithTag("report-current-question").assertIsDisplayed().performClick()
                                        await("content-report-dialog")
                                        assertTrue("the input is released before any private report note", engine.destroyed.get())
                                        compose.onNodeWithTag("report-note").performScrollTo().assertIsDisplayed()
                                            .performSemanticsAction(SemanticsActions.SetText) {
                                                it(androidx.compose.ui.text.AnnotatedString("Neutral private report note, not dictated answer text."))
                                            }
                                        assertEquals("Neutral private report note, not dictated answer text.",
                                            compose.onNodeWithTag("report-note").fetchSemanticsNode().config
                                                .getOrNull(SemanticsProperties.EditableText)?.text)
                                        controls = listOf(tag("report-note"), tag("report-cancel"))
                                    } else {
                                        revealControl("dictation-toggle")
                                        compose.onNodeWithTag("dictation-toggle").assertIsDisplayed().performClick()
                                        compose.waitUntil(DEVICE_TIMEOUT) { editableAnswer() == "Already typed words. $words" }
                                        assertTrue(engine.destroyed.get())
                                        controls += UxControl("dictation-status", hasTestTag("dictation-status"), actionRequired = false)
                                    }
                                }
                                if (frame != "dictation-report") {
                                    val session = graph.sessionStore.sessions.value.singleOrNull()
                                    val turns = session?.get("turns") as? JsonArray
                                    assertTrue("no manual settlement, error or Stop submits an answer",
                                        turns == null || turns.size == 1 && stringOf(turns.first()["answer"]).isNullOrEmpty())
                                    assertEquals(SEED_QUESTION, nodeText("question"))
                                }
                            }
                            assertEquals("manual input never starts a hands-free service", 0, serviceStarts.get())
                            assertTrue("manual input never attaches a drive", !graph.driveController.state.value.attached)
                            recorder.capture(state, config, checkNotNull(activity), controls,
                                nativeMapping = "Real MainActivity/manual controller and PRESS_TO_TALK recognizer with controlled native callbacks; literal editable draft and explicit Send. The real microphone permission is granted through its OS dialog only when needed on this owned managed device. No real recognizer, recording, speech, service or hosted request; not physical #89 evidence.",
                                rootSurfaceRole = if (frame == "dictation-report") "surfaceContainerHigh" else "background")
                        } catch (failure: Throwable) {
                            recorder.failed(state, config, failure, activity)
                        } finally { closeFixture() }
                    }
                }
            }
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            UxEvidence.finish(primary, listOf(
                { closeFixture() }, { app.graph = originalGraph },
                { check(runDir.deleteRecursively()) { "could not remove owned dictation fixture files" } },
            )) { errors -> errors.forEach(recorder::failedRun); recorder.finish() }
        }
    }

    private fun editableAnswer() = compose.onNodeWithTag("answer").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun allowManualMicrophoneIfRequested() {
        if (target.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        await("voice-mic-continue")
        compose.onNodeWithTag("voice-notification-rationale").assertDoesNotExist()
        compose.onNodeWithTag("voice-mic-continue").assertIsDisplayed().performClick()
        val deadline = SystemClock.uptimeMillis() + DEVICE_TIMEOUT
        var clicked = false
        while (!clicked && SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByViewId(
                "com.android.permissioncontroller:id/permission_allow_foreground_only_button").orEmpty()
                .filter { it.isVisibleToUser && it.isEnabled && it.isClickable }
            check(nodes.size <= 1) { "ambiguous native microphone permission action" }
            clicked = nodes.singleOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!clicked) SystemClock.sleep(100)
        }
        check(clicked) { "the actual native microphone Allow action was not found" }
        compose.waitUntil(DEVICE_TIMEOUT) {
            target.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private class FixtureManualEngine(private val main: CoroutineScope) : io.github.jaypetez.ideaforge.voice.RecognizerEngine {
        lateinit var callbacks: io.github.jaypetez.ideaforge.voice.RecognizerEngine.Listener
        val starts = AtomicInteger()
        val destroyed = AtomicBoolean()
        var text = ""
        private var readings: Job? = null
        override fun setListener(listener: io.github.jaypetez.ideaforge.voice.RecognizerEngine.Listener) { callbacks = listener }
        override fun start(language: String) {
            starts.incrementAndGet()
            callbacks.onReady()
            readings = main.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    callbacks.onRms(-1.5f)
                }
            }
        }
        override fun stop() { callbacks.onResults(text) }
        override fun cancel() { readings?.cancel(); readings = null }
        override fun destroy() { cancel(); destroyed.set(true) }
    }

    @Test
    fun requestedInterviewIsQuestionFirstKeyboardSafeAndTruthful() {
        val bundle = InstrumentationRegistry.getArguments()
        val probe = bundle.getString("uxInterviewProbe")
        if (probe == null) {
            require(bundle.getString("class")?.contains("#requestedInterviewIsQuestionFirstKeyboardSafeAndTruthful") != true) {
                "the selected Interview probe requires uxInterviewProbe=true and its complete capture inventory"
            }
            return
        }
        require(probe == "true") { "uxInterviewProbe must be true" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states?.toSet() == setOf(
            "interview-seed", "interview-long", "interview-history", "interview-coverage", "bank", "busy-turn",
            "save-failure", "interview-ime", "interview-wrap", "writing", "synthesis-failure",
        )) { "Interview evidence requires its complete fixed state matrix" }
        captureUxEvidence(request, nativeInterview = true)
    }

    @Test
    fun requestedVoicePhasesKeepCaptionsRecoveryAndControlsHonest() {
        val bundle = InstrumentationRegistry.getArguments()
        val probe = bundle.getString("uxVoiceProbe")
        if (probe == null) {
            require(bundle.getString("class")?.contains("#requestedVoicePhasesKeepCaptionsRecoveryAndControlsHonest") != true) {
                "the selected voice probe requires uxVoiceProbe=true and its complete capture inventory"
            }
            return
        }
        require(probe == "true") { "uxVoiceProbe must be true" }
        val request = requireNotNull(UxCaptureRequest.from(bundle.keySet().associateWith { bundle.get(it).toString() }))
        require(request.mode == "matrix" && request.states?.toSet() == setOf(
            "voice-ready", "voice-starting", "voice-speaking", "voice-listening", "voice-transcribing",
            "voice-thinking", "voice-paused", "voice-recovering", "voice-blocked", "voice-complete", "voice-writing",
            "voice-fallback", "voice-notifications-off", "voice-help",
        )) { "Voice evidence requires its complete fixed phase/recovery inventory" }
        captureUxEvidence(request, nativeVoice = true)
    }

    private fun captureUxEvidence(
        request: UxCaptureRequest, nativeIdeas: Boolean = false, nativeInterview: Boolean = false, nativeVoice: Boolean = false,
    ) {
        val bundle = InstrumentationRegistry.getArguments()
        val outputPath = requireNotNull(bundle.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }) {
            "requested UX evidence needs Gradle's additionalTestOutputDir"
        }
        fixtures = UxFixtures.load(target)
        val selected = if (request.mode == "walkthrough") emptyList() else request.states?.map { id ->
            when {
                nativeVoice -> UxFixtureActivity.requestedVoiceState(fixtures, id)
                nativeIdeas && id == "ideas-loading" -> fixtures.state("ready").copy(id = id, surface = "library", phase = "loading")
                nativeInterview && id in setOf("interview-seed", "interview-wrap") ->
                    fixtures.state("ready").copy(id = id, surface = "interview")
                nativeInterview && id in setOf("interview-history", "interview-coverage", "interview-ime") ->
                    fixtures.state("interview-long").copy(id = id)
                else -> fixtures.state(id)
            }
        } ?: fixtures.states
        val windows = if (request.window == "all") listOf("compact", "expanded") else listOf(request.window)
        val matrix = windows.flatMap { window ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it) }
            }
        } + if (nativeVoice && "compact" in windows) listOf("light", "dark").map {
            UxConfiguration("compact-landscape", it, 2f, requestedWidthDp = 640, requestedHeightDp = 400)
        } else emptyList()
        // Export feedback once consumed short and narrow windows; those shapes get their own records.
        val feedbackMatrix = if (nativeIdeas && "compact" in windows) listOf(
            Triple("compact-landscape", 640, 400), Triple("compact-narrow", 320, 720),
        ).flatMap { (window, width, height) ->
            listOf("light", "dark").flatMap { theme ->
                listOf(1f, 1.3f, 2f).map { UxConfiguration(window, theme, it, requestedWidthDp = width, requestedHeightDp = height) }
            }
        } else emptyList()
        val requestedStates = matrix.associateWith { config ->
            if (nativeVoice && config.window == "compact-landscape")
                selected.filter { it.id in setOf("voice-listening", "voice-paused", "voice-blocked") }
            else selected
        }
        val walkthrough = if (request.states == null) walkthroughFrames else emptyList()
        val expected = (matrix.flatMap { config ->
            requestedStates.getValue(config).map { "${config.id}/${it.id}" } + walkthrough.map { "${config.id}/walkthrough-$it" } +
                if (nativeIdeas) libraryExportFrames.map { "${config.id}/$it" } else emptyList()
        } + feedbackMatrix.map { "${it.id}/ideas-export-feedback" }).toSet()
        val recorder = UxCaptureRecorder(compose, instrumentation.uiAutomation, request, File(outputPath, "ux"), expected)
        val originalGraph = app.graph
        runDir = File(target.filesDir, "ux-fixture-run-${SystemClock.elapsedRealtimeNanos()}")
        var primaryError: Throwable? = null
        try {
            check(runDir.mkdirs()) { "could not create owned fixture run directory" }
            UxDeviceEnvironment(instrumentation.uiAutomation).use { environment ->
                for (config in matrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    for (state in requestedStates.getValue(config)) {
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
                            if (state.id == "ideas-loading") {
                                assertTrue("the read remains pending for the whole loading capture", heldLibraryRead?.count == 1L)
                                assertTrue("loading did not become an empty or failed read", !fixtureGraph!!.sessionStore.loadState.value.ready &&
                                    fixtureGraph!!.sessionStore.loadState.value.problem == null)
                            }
                            if (state.id == "interview-ime") {
                                assertTrue("reachability keeps the actual keyboard open",
                                    activity!!.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
                            }
                        } catch (e: Throwable) {
                            recorder.failed(state, config, e, activity)
                        } finally {
                            closeFixture()
                        }
                    }
                    if (nativeIdeas) {
                        try {
                            captureLibraryExports(config, recorder, File(outputPath, "ux/${config.id}/native-system"))
                        } catch (error: Throwable) {
                            recorder.failedRun(error)
                            var diagnostic = activity
                            for (key in UxEvidence.unrecorded(libraryExportFrames.map { "${config.id}/$it" }.toSet(), recorder.recordedKeys)) {
                                recorder.failed(fixtures.state("library-long").copy(id = key.substringAfter('/')),
                                    config, error, diagnostic)
                                diagnostic = null
                            }
                        } finally { closeFixture() }
                    }
                    if (walkthrough.isNotEmpty()) {
                        captureWalkthrough(config, recorder)
                    }
                }
                for (config in feedbackMatrix) {
                    environment.configure(config.window, config.fontScale, resize = true)
                    try {
                        captureExportFeedback(config, recorder, File(outputPath, "ux/${config.id}/native-system"))
                    } catch (error: Throwable) {
                        recorder.failed(fixtures.state("library-long").copy(id = "ideas-export-feedback"), config, error, activity)
                    } finally { closeFixture() }
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

    private fun captureWalkthrough(config: UxConfiguration, recorder: UxCaptureRecorder) {
        val state = UxState("walkthrough", "interview", "real-app", null, null, null)
        try {
            prepare(state, config)
            scriptedWalkthrough(compose, target, launch = ::launchMain, capture = { frame ->
                val captured = state.copy(id = "walkthrough-$frame", surface = if (frame == "result") "result" else "interview")
                recorder.capture(captured, config, checkNotNull(activity),
                    if (frame == "result") resultControls() else interviewControls())
            }, settleInput = ::hideKeyboard)
        } catch (error: Throwable) {
            Log.e("UxCapture", "scripted walkthrough failed for ${config.id}", error)
            recorder.failedRun(error)
            val keys = walkthroughFrames.map { "${config.id}/walkthrough-$it" }.toSet()
            for (key in UxEvidence.unrecorded(keys, recorder.recordedKeys)) {
                recorder.failed(state.copy(id = key.substringAfter('/')), config, error, activity)
            }
        } finally {
            closeFixture()
        }
    }

    private fun prepare(
        state: UxState,
        config: UxConfiguration,
        inferenceAgreed: Boolean = true,
        checker: ProviderCheck = ProviderCheck { _, _ -> error("a UX fixture attempted a provider check") },
        reportWritesRejected: AtomicBoolean? = null,
        nativeEngine: TtsEngine? = null,
        manualNative: Boolean = false,
        documentSink: DocumentSink? = null,
    ) = runBlocking {
        val context = FixtureContext(target, File(runDir, "graph-${++nextGraph}"))
        val script = ScriptedProvider.loadScript(target)
        val controlled = FixtureProvider(ScriptedProvider(script), state.id)
        val gate = if (state.id == "ideas-loading") CountDownLatch(1) else null
        heldLibraryRead = gate
        libraryReadEntered = gate?.let { CountDownLatch(1) }
        val storage = gate?.let {
            val entered = checkNotNull(libraryReadEntered)
            SessionStore(File(context.filesDir, "sessions"), readAttributes = { file ->
                entered.countDown()
                if (!gate.await(DEVICE_TIMEOUT, TimeUnit.MILLISECONDS)) throw IOException("controlled UX library read exceeded its capture budget")
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            })
        } ?: reportWritesRejected?.let { refused ->
            SessionStore(File(context.filesDir, "sessions"), writer = { file, bytes ->
                if (refused.get() && (Json.parseToJsonElement(bytes.decodeToString()) as JsonObject).containsKey("contentReports")) {
                    throw IOException("Neutral controlled report write failure; retry keeps the note.")
                }
                SessionStore.write(file, bytes)
            })
        }
        val graph = AppGraph(
            context, clock = Clock { fixtures.now }, keyringKey = FixtureKey(),
            providerCheck = checker,
            providerSource = ProviderSource { controlled },
            sessionStorage = storage,
            ttsEngine = nativeEngine,
            recognitionAvailable = { manualNative || android.speech.SpeechRecognizer.isRecognitionAvailable(context) },
            documentSink = documentSink ?: ContentResolverSink(context.contentResolver),
            preferences = io.github.jaypetez.ideaforge.data.PrefsStore.create(
                CoroutineScope(SupervisorJob() + Dispatchers.IO).also { fixturePreferencesScope = it },
            ) { context.preferencesDataStoreFile("prefs") },
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
        if (inferenceAgreed && state.id != "fresh") {
            graph.inferenceConsent.accept(io.github.jaypetez.ideaforge.providers.inferenceDestination(
                io.github.jaypetez.ideaforge.data.inferenceConfig(graph.keyringStore.state.value.ring)))
        }
        if (state.id == "unavailable") {
            File(context.filesDir, "sessions").writeText("owned UX storage fault")
            val load = runCatching { graph.sessionStore.load() }
            assertTrue("the real store rejects unavailable storage", load.exceptionOrNull() is IOException)
            assertTrue("the real store reports the failed read", graph.sessionStore.loadState.value.problem != null)
        } else if (state.id != "ideas-loading") {
            graph.sessionStore.load()
        }
        if (state.id == "save-failure") {
            // Read a fresh library successfully, then make the actual atomic write fail.
            File(context.filesDir, "sessions").writeText("owned UX storage fault")
        }
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
            if (state.phase in setOf("transcribing", "thinking", "recovering", "paused", "blocked"))
                assertEquals(fixtures.text("answer"), nodeText("voice-caption"))
            if (state.phase == "transcribing")
                assertEquals(target.getString(R.string.voice_phase_transcribing), nodeText("voice-phase"))
            if (state.id == "voice-fallback") {
                compose.onNodeWithTag("voice-fallback").assertExists()
                assertEquals(target.getString(R.string.voice_backend_native), nodeText("voice-backend"))
            }
            if (state.id == "voice-help") {
                compose.onNodeWithTag("voice-help-toggle").performScrollTo().performClick()
                compose.onNodeWithTag("voice-help").assertExists()
            }
            return buildList {
                if (state.phase == "listening") add(UxControl("voice-no-level", hasTestTag("voice-no-level"), actionRequired = false))
                if (state.phase == "blocked") add(tag("voice-mic-settings"))
                if (state.id == "voice-notifications-off")
                    add(UxControl("voice-notifications-off", hasTestTag("voice-notifications-off"), actionRequired = false))
                add(tag("voice-help-toggle"))
                addAll(listOf(
                tag(if (state.phase in setOf("paused", "blocked")) "voice-resume" else "voice-pause"),
                tag("voice-exit"),
                ))
            }
        }
        if (state.id == "interview-wrap") {
            scriptedWalkthrough(compose, target, launch = ::launchMain, settleInput = ::hideKeyboard, wrapOnly = true)
            await("wrap-offer")
            return listOf(tag("wrap-accept"), tag("wrap-decline"), tag("interview-back"))
        }
        launchMain(scripted = state.id !in setOf("busy-turn", "writing", "synthesis-failure"))
        if (state.id == "fresh") {
            await("provider-groq")
            return listOf(tag("provider-groq"), tag("provider-ollama"))
        }
        await(NAVIGATION)
        when (state.id) {
            "ideas-loading" -> {
                compose.waitUntil(DEVICE_TIMEOUT) { libraryReadEntered?.count == 0L }
                val label = hasText(target.getString(R.string.library_loading))
                compose.onNode(label).assertIsDisplayed()
                compose.onNodeWithTag(LibraryTags.ZERO).assertDoesNotExist()
                compose.onNodeWithTag(LibraryTags.EMPTY).assertDoesNotExist()
                compose.onNodeWithTag(LibraryTags.UNAVAILABLE).assertDoesNotExist()
                return listOf(UxControl("library-loading", label, actionRequired = false), tag(LibraryTags.NEW)) + navigationControls()
            }
            "ready", "empty" -> {
                await(LibraryTags.ZERO)
                return listOf(tag(LibraryTags.NEW)) + navigationControls()
            }
            "unavailable" -> {
                await(LibraryTags.UNAVAILABLE)
                compose.onNodeWithTag(LibraryTags.ZERO).assertDoesNotExist()
                compose.onNodeWithTag(LibraryTags.EMPTY).assertDoesNotExist()
                compose.onNode(hasText(target.getString(R.string.library_unavailable))).assertExists()
                return listOf(tag(LibraryTags.RETRY), tag(LibraryTags.NEW)) + navigationControls()
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
                return listOf(tag(LibraryTags.NEW), tag(LibraryTags.SEARCH), tag(LibraryTags.FILTERS)) + navigationControls()
            }
            "library-archived" -> {
                await(LibraryTags.FILTERS)
                compose.onNodeWithTag(LibraryTags.FILTERS).performScrollTo().performClick()
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
                return listOf(tag(LibraryTags.SEARCH), tag(LibraryTags.CLEAR), tag(LibraryTags.NEW)) + navigationControls()
            }
            "library-long" -> {
                selectSession("ux-long-active", open = false)
                assertTrue("the long idea is active, not a failed completed result", stringOf(fixtureGraph!!.sessionStore.get("ux-long-active")!!["status"]) != "done")
                compose.onNodeWithTag(ResultTags.SYNTHESIS_FAILED).assertDoesNotExist()
                return listOf(textControl("continue", R.string.idea_continue), tag(LibraryTags.NEW)) + navigationControls()
            }
            "interview-seed" -> {
                compose.onNode(navigationMatcher(R.string.nav_new_idea)).performClick()
                await("new-idea-type")
                compose.onNodeWithTag("new-idea-type").performClick()
                await("question")
                assertEquals(SEED_QUESTION, nodeText("question"))
                compose.onNodeWithTag("history-toggle").assertDoesNotExist()
                return interviewControls() + tag("coverage-details")
            }
            "interview-long", "bank", "interview-history", "interview-coverage", "interview-ime" -> {
                selectSession(requireNotNull(state.sessionId))
                await("question")
                if (state.id == "bank") compose.onNodeWithTag("degraded").assertExists()
                else assertEquals(fixtures.text("question"), nodeText("question"))
                when (state.id) {
                    "interview-history" -> {
                        revealControl("history-toggle")
                        compose.onNodeWithTag("history-toggle").performClick()
                        assertEquals(target.getString(R.string.interview_history_hide), nodeText("history-toggle"))
                    }
                    "interview-coverage" -> {
                        revealControl("coverage-details")
                        compose.onNodeWithTag("coverage-details").performClick()
                        return listOf(tag("coverage-details")) + DIMENSION_IDS.map {
                            UxControl("pill-$it", hasTestTag("pill-$it"), actionRequired = false)
                        }
                    }
                    "interview-ime" -> {
                        revealControl("answer")
                        compose.onNodeWithTag("answer").performClick()
                        compose.waitUntil(DEVICE_TIMEOUT) {
                            activity!!.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
                        }
                        compose.onNodeWithTag("answer").performTextReplacement(fixtures.text("answer"))
                        revealControl("answer")
                        assertEquals(fixtures.text("answer"), compose.onNodeWithTag("answer").fetchSemanticsNode()
                            .config.getOrNull(SemanticsProperties.EditableText)?.text)
                    }
                }
                return interviewControls() + if (state.id == "interview-long") {
                    (fixtures.text.getValue("chips") as kotlinx.serialization.json.JsonArray).indices.map { tag("chip-$it") } +
                        UxControl("turn-status", hasTestTag("turn-status"), actionRequired = false)
                } else listOf(UxControl("turn-status", hasTestTag("turn-status"), actionRequired = false))
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
                revealControl("write-up")
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
                await("new-idea-type")
                compose.onNodeWithTag("new-idea-type").performClick()
                awaitEditableQuestion()
                compose.onNodeWithTag("answer").assertIsEnabled().performTextReplacement(fixtures.text("answer"))
                compose.waitUntil(DEVICE_TIMEOUT) {
                    compose.onNodeWithTag("answer").fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.EditableText)?.text == fixtures.text("answer")
                }
                hideKeyboard()
                assertEquals("the real failure/loading draft is entered before submission", fixtures.text("answer"),
                    compose.onNodeWithTag("answer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
                revealControl("send")
                compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled().performClick()
                if (state.id == "busy-turn") {
                    await("interview-busy")
                    await("busy-line")
                } else {
                    compose.waitUntil(DEVICE_TIMEOUT) {
                        exists("question") && !exists("interview-busy") && compose.onAllNodes(hasText(target.getString(
                            R.string.notice_interview_failed, "unused",
                        ).substringBefore("unused"), substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue("save failure did not secretly persist an idea", fixtureGraph!!.sessionStore.sessions.value.isEmpty())
                    compose.onNodeWithTag("answer").assertIsEnabled()
                    assertEquals("the rejected submission remains editable", fixtures.text("answer"),
                        compose.onNodeWithTag("answer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
                    compose.onNodeWithTag("send").assertIsEnabled()
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
        compose.onNodeWithTag(LibraryTags.more(id)).performClick()
        compose.onNodeWithTag(LibraryTags.details(id)).performClick()
        await(LibraryTags.DETAIL)
        if (open) {
            val action = if (stringOf(session["status"]) == "done") R.string.idea_view_result else R.string.idea_continue
            compose.onNode(hasText(target.getString(action)) and hasClickAction()).performScrollTo().performClick()
        }
    }

    private fun interviewControls(): List<UxControl> = buildList {
        add(tag("answer")); add(tag("send")); add(tag("skip")); add(tag("dictation-toggle")); add(tag("voice-start"))
        if (exists("write-up")) add(tag("write-up"))
        add(tag("interview-back"))
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
        hasText(target.getString(label)) and hasAnyAncestor(hasTestTag(NAVIGATION)) and hasClickAction()

    private fun textControl(name: String, label: Int) = UxControl(name, hasText(target.getString(label)) and hasClickAction())
    private fun tag(name: String) = UxControl(name, hasTestTag(name))
    private fun await(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), DEVICE_TIMEOUT)
    private fun awaitEditableQuestion() {
        compose.waitUntil(DEVICE_TIMEOUT) {
            exists("question") && compose.onAllNodes(hasTestTag("answer")).fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }
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
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(300, DEVICE_TIMEOUT)
    }

    private fun revealControl(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val top = coordinates.localToWindow(Offset.Zero)
        val raw = UxBounds(top.x, top.y, top.x + coordinates.size.width, top.y + coordinates.size.height)
        val view = checkNotNull(activity).window.decorView
        val insets = view.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        val safe = UxBounds(insets.left.toFloat(), insets.top.toFloat(),
            (view.width - insets.right).toFloat(), (view.height - insets.bottom).toFloat())
        val clipped = node.boundsInWindow.let { UxBounds(it.left, it.top, it.right, it.bottom) }
        if (!safe.intersect(clipped).contains(raw)) compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun closeFixture() {
        heldVoiceInit?.complete(false)
        heldVoiceInit = null
        heldLibraryRead?.countDown()
        heldLibraryRead = null
        libraryReadEntered = null
        val closing = scenario
        val graph = fixtureGraph
        val preferences = fixturePreferencesScope
        scenario = null
        activity = null
        fixtureGraph = null
        fixturePreferencesScope = null
        UxEvidence.finish(null, listOf(
            { compose.runOnUiThread { graph?.manualDictation?.cancel(); graph?.driveController?.exit() } },
            { closing?.close() },
            { graph?.nativeTtsEngine?.shutdown() },
            { runBlocking { graph?.mainScope?.coroutineContext?.get(Job)?.cancelAndJoin() } },
            { runBlocking { graph?.appScope?.coroutineContext?.get(Job)?.cancelAndJoin() } },
            { runBlocking { preferences?.coroutineContext?.get(Job)?.cancelAndJoin() } },
        )) {}
    }

    private class FixtureDeviceVoiceEngine(private val frame: String) : TtsEngine {
        companion object {
            const val LONG_ID = "en-GB-installed-voice-with-a-deliberately-long-engine-name-for-readable-wrapping-and-selection"
        }
        val pending = CompletableDeferred<Boolean>()
        val spoken = mutableListOf<String>()
        var selected = ""
            private set
        private var reads = 0
        private var listener: TtsEngine.Listener? = null
        override fun setListener(listener: TtsEngine.Listener) { this.listener = listener }
        override suspend fun awaitReady(): Boolean = when {
            // Hold only this controlled boundary until the named loading frame is retained.
            frame == "device-loading" -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
            frame == "device-error" && ++reads == 1 -> false
            else -> true
        }
        override fun voiceDetails() = if (frame == "device-empty") emptyList() else listOf(
            DeviceVoice("en-US-device-local", "en-US", false), DeviceVoice(LONG_ID, "en-GB", true))
        override fun voices() = voiceDetails().map { it.id }
        override fun setVoice(id: String): Boolean { selected = id; return id in voices() }
        override fun useDefaultVoice() { selected = "" }
        override fun setRate(rate: Float) {}
        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text
            checkNotNull(listener).onStart(utteranceId)
            checkNotNull(listener).onDone(utteranceId)
            return true
        }
        override fun stop() {}
        override fun reset() {}
        override fun shutdown() { pending.complete(false) }
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
        "ideas-loading" -> "Real MainActivity Ideas while its actual SessionStore read is held pending; no empty index or synthetic UI state."
        "interview-wrap" -> "Real MainActivity canonical typed walkthrough stopped at its actual engine wrap offer; not a fabricated presentation state."
        "interview-ime" -> "Real MainActivity long interview with the actual native keyboard open throughout capture and reachability."
        else -> null
    }
}

internal class FixtureContext(base: Context, root: File) : ContextWrapper(base) {
    private val files = File(root, "files").apply { check(mkdirs()) }
    private val noBackup = File(root, "no-backup").apply { check(mkdirs()) }
    override fun getFilesDir(): File = files
    override fun getNoBackupFilesDir(): File = noBackup
    // DataStore resolves applicationContext.filesDir, not the wrapper's direct filesDir.
    override fun getApplicationContext(): Context = this
}

internal class FixtureKey : KeyringKey {
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
