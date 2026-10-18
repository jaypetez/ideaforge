package io.github.jaypetez.ideaforge.ux

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import io.github.jaypetez.ideaforge.core.DIMENSION_IDS
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
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
import io.github.jaypetez.ideaforge.data.SessionStore
import io.github.jaypetez.ideaforge.scriptedWalkthrough
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.BOTTOM_BAR
import io.github.jaypetez.ideaforge.ui.NAVIGATION_RAIL
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.ui.library.LibraryTags
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import io.github.jaypetez.ideaforge.ui.result.ResultTags
import io.github.jaypetez.ideaforge.voice.DriveServiceControl
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.Speaker
import io.github.jaypetez.ideaforge.voice.VoiceMode
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var heldLibraryRead: CountDownLatch? = null
    private var libraryReadEntered: CountDownLatch? = null

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
                        var serviceStarts = 0
                        graph.driveService = object : DriveServiceControl {
                            override fun start() { serviceStarts++ }
                            override fun stop() {}
                        }
                        val session = checkNotNull(graph.sessionStore.get("ux-long-active"))
                        compose.runOnUiThread {
                            graph.driveController.start(session, "typed")
                            graph.driveController.pause()
                        }
                        await("voice-stage")
                        assertEquals(VoiceMode.PAUSED, graph.driveController.state.value.mode)
                        assertEquals(1, serviceStarts)
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
        val requestedStates = matrix.associateWith { config ->
            if (nativeVoice && config.window == "compact-landscape")
                selected.filter { it.id in setOf("voice-listening", "voice-paused", "voice-blocked") }
            else selected
        }
        val walkthrough = if (request.states == null) listOf("seed", "chip", "result") else emptyList()
        val expected = matrix.flatMap { config ->
            requestedStates.getValue(config).map { "${config.id}/${it.id}" } + walkthrough.map { "${config.id}/walkthrough-$it" }
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
                    if (walkthrough.isNotEmpty()) {
                        val state = UxState("walkthrough", "interview", "real-app", null, null, null)
                        try {
                            prepare(state, config)
                            scriptedWalkthrough(compose, target, launch = ::launchMain, capture = { frame ->
                                val captured = state.copy(id = "walkthrough-$frame", surface = if (frame == "result") "result" else "interview")
                                recorder.capture(captured, config, checkNotNull(activity), if (frame == "result") resultControls() else interviewControls())
                            }, settleInput = ::hideKeyboard)
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

    private fun prepare(state: UxState, config: UxConfiguration, inferenceAgreed: Boolean = true) = runBlocking {
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
        }
        val graph = AppGraph(
            context, clock = Clock { fixtures.now }, keyringKey = FixtureKey(),
            providerCheck = ProviderCheck { _, _ -> error("a UX fixture attempted a provider check") },
            providerSource = ProviderSource { controlled },
            sessionStorage = storage,
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
        compose.onNodeWithTag(LibraryTags.more(id)).performClick()
        compose.onNodeWithTag(LibraryTags.details(id)).performClick()
        await(LibraryTags.DETAIL)
        if (open) {
            val action = if (stringOf(session["status"]) == "done") R.string.idea_view_result else R.string.idea_continue
            compose.onNode(hasText(target.getString(action)) and hasClickAction()).performScrollTo().performClick()
        }
    }

    private fun interviewControls(): List<UxControl> = buildList {
        add(tag("answer")); add(tag("send")); add(tag("skip")); add(tag("voice-start"))
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
        heldLibraryRead?.countDown()
        heldLibraryRead = null
        libraryReadEntered = null
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
        "ideas-loading" -> "Real MainActivity Ideas while its actual SessionStore read is held pending; no empty index or synthetic UI state."
        "interview-wrap" -> "Real MainActivity canonical typed walkthrough stopped at its actual engine wrap offer; not a fabricated presentation state."
        "interview-ime" -> "Real MainActivity long interview with the actual native keyboard open throughout capture and reachability."
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
