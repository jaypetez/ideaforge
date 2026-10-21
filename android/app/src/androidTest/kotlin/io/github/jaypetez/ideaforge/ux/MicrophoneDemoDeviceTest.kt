package io.github.jaypetez.ideaforge.ux

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jaypetez.ideaforge.AppGraph
import io.github.jaypetez.ideaforge.DEVICE_TIMEOUT
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.ScriptedProvider
import io.github.jaypetez.ideaforge.core.SEED_QUESTION
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.openTurn
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.data.inferenceConfig
import io.github.jaypetez.ideaforge.providers.inferenceDestination
import io.github.jaypetez.ideaforge.ui.NAVIGATION
import io.github.jaypetez.ideaforge.ui.interview.ProviderSource
import io.github.jaypetez.ideaforge.voice.DriveService
import io.github.jaypetez.ideaforge.voice.Recognizer
import io.github.jaypetez.ideaforge.voice.RecognizerMode
import io.github.jaypetez.ideaforge.voice.androidRecognizerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real system effects on an owned, fresh emulator; real default native recognition. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MicrophoneDemoDeviceTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val app get() = target.applicationContext as IdeaForgeApp
    private val audio get() = target.getSystemService(AudioManager::class.java)
    private val notifications get() = target.getSystemService(NotificationManager::class.java)

    @Test
    fun requestedMicrophoneForegroundServiceVideo() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("microphoneDemoProbe") == null) {
            require(args.getString("class")?.contains("#requestedMicrophoneForegroundServiceVideo") != true) {
                "the selected microphone demo requires microphoneDemoProbe=true"
            }
            return
        }
        require(args.getString("microphoneDemoProbe") == "true")
        require(Build.VERSION.SDK_INT >= 34 && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "use an owned, fresh API 34+ rendering-enabled emulator, never a shared phone"
        }
        val request = requireNotNull(UxCaptureRequest.from(args.keySet().associateWith { args.get(it).toString() }))
        require(request.mode == "matrix" && request.window == "compact" && request.states == null && request.sourceState == "clean")
        val output = File(requireNotNull(args.getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }), "microphone-demo")
        check(output.isDirectory || output.mkdirs())
        val events = mutableListOf<JsonObject>()
        val expectedStages = setOf("interview", "app-rationale", "microphone-permission",
            "notification-permission", "hands-free-listening", "notification-pause-exit", "exited-same-typed-interview")
        val failures = mutableListOf<String>()
        val video = File(output, "microphone-foreground-service.mp4")
        val json = Json { prettyPrint = true }
        var metadata: JsonObject? = null
        fun manifest(status: String) {
            File(output, "manifest.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("schema", 1)
                put("status", status)
                put("testedSha", request.sha)
                put("sourceState", request.sourceState)
                put("apiLevel", Build.VERSION.SDK_INT)
                put("deviceModel", Build.MODEL)
                put("deviceFingerprint", Build.FINGERPRINT)
                put("display", shell("wm size").trim())
                put("density", shell("wm density").trim())
                put("transport", "UiAutomation.executeShellCommand; the device's real screenrecord binary (shell UID), managed by Gradle")
                put("command", "screenrecord --time-limit 45 --bit-rate 4000000 ${video.absolutePath}")
                put("fixture", "Private test stores and existing debug scripted interview model. The existing graph seam selects the real native DEFAULT recognition service, not scripted callbacks. Native TTS, runtime permission UI, DriveController, DriveService and notification actions are not replaced.")
                put("limitations", "Emulator microphone input, not a person speaking, preferred on-device recognition, physical-device support, speech quality, screen-off survival or Play approval. No production key or app inference/hosted-speech API call; native service network behavior is platform-owned.")
                metadata?.let { put("video", it) }
                put("events", JsonArray(events))
                put("expectedStages", JsonArray(expectedStages.map(::JsonPrimitive)))
                put("failures", JsonArray(failures.map(::JsonPrimitive)))
            }) + "\n")
        }
        manifest("running")
        val originalGraph = app.graph
        val originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val root = File(target.filesDir, "microphone-demo-${SystemClock.elapsedRealtimeNanos()}")
        var graph: AppGraph? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val reader = Executors.newSingleThreadExecutor()
        var recording: java.util.concurrent.Future<String>? = null
        var began = 0L
        var primaryError: Throwable? = null
        try {
            File(output, "capabilities.txt").writeText(buildString {
                appendLine("recognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(target)}")
                appendLine("onDeviceRecognitionAvailable=${SpeechRecognizer.isOnDeviceRecognitionAvailable(target)}")
                appendLine("recognizer=${shell("settings get secure voice_recognition_service").trim()}")
                appendLine("recognizerMode=DEFAULT; real android.speech.SpeechRecognizer.createSpeechRecognizer")
                appendLine("onDeviceRecognizer=${shell("settings get secure on_device_system_speech_recognition_service").trim()}")
                appendLine("tts=${shell("settings get secure tts_default_synth").trim()}")
                appendLine("ttsServices=${target.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).map { it.serviceInfo.packageName }}")
                appendLine("audio=${shell("dumpsys audio")}")
            })
            check(SpeechRecognizer.isRecognitionAvailable(target)) { "the selected image has no native recognition service; see capabilities.txt" }
            check(target.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()) {
                "the selected image has no native TTS engine; see capabilities.txt"
            }
            for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) {
                check(target.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    "$permission is already granted; the video needs a fresh device and real permission dialogs"
                }
            }
            check(root.mkdirs())
            val privateGraph = AppGraph(FixtureContext(target, root), keyringKey = FixtureKey(),
                providerSource = ProviderSource { ScriptedProvider(ScriptedProvider.loadScript(target)) })
            // This image's on-device en-US start failed despite advertised availability.
            // This is the existing real default-service path, not a fake recognizer.
            privateGraph.recognizer = Recognizer(
                androidRecognizerFactory(target, RecognizerMode.DEFAULT), privateGraph.mainScope,
            )
            graph = privateGraph
            app.graph = privateGraph
            runBlocking {
                privateGraph.keyringStore.save(withCreds(emptyKeyring(), "groq",
                    JsonObject(mapOf("apiKey" to JsonPrimitive("scripted_fixture_never_transmitted")))))
                privateGraph.sessionStore.load()
                privateGraph.inferenceConsent.accept(inferenceDestination(inferenceConfig(privateGraph.keyringStore.state.value.ring)))
            }
            scenario = ActivityScenario.launch(Intent(target, MainActivity::class.java).putExtra("provider", "scripted"))
            compose.waitUntil(DEVICE_TIMEOUT) { exists(NAVIGATION) }
            compose.onNode(hasText(target.getString(R.string.nav_new_idea)) and hasAnyAncestor(hasTestTag(NAVIGATION))).performClick()
            await("new-idea-type")
            click("new-idea-type")
            await("voice-start")
            check(!serviceRunning() && activeRecordings() == 0) { "voice effects began before the Answer by voice tap" }
            began = SystemClock.elapsedRealtime()
            val descriptor = automation.executeShellCommand("screenrecord --time-limit 45 --bit-rate 4000000 ${video.absolutePath}")
            recording = reader.submit<String> {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            SystemClock.sleep(1500)
            fun stage(name: String) {
                events += buildJsonObject { put("stage", name); put("videoTimeMs", SystemClock.elapsedRealtime() - began + 1000) }
                SystemClock.sleep(2500)
            }
            stage("interview")
            click("voice-start")
            await("voice-mic-continue")
            stage("app-rationale")
            click("voice-mic-continue")
            // The OS, not a grant-rule/pm command. Resource IDs identify both genuine dialogs.
            val controllers = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
            compose.waitUntil(DEVICE_TIMEOUT) { automation.rootInActiveWindow?.packageName?.toString() in controllers }
            val controller = checkNotNull(automation.rootInActiveWindow?.packageName?.toString())
            repeat(2) {
                // Google's controller retains AOSP resource IDs; its process package is
                // not the resource namespace (the first real-device run caught this).
                val mic = systemNode(controller, "com.android.permissioncontroller:id/permission_allow_foreground_only_button", required = false)
                val button = mic ?: checkNotNull(systemNode(controller, "com.android.permissioncontroller:id/permission_allow_button"))
                val name = if (mic != null) "microphone-permission" else "notification-permission"
                File(output, "$name-ui.txt").writeText(tree(automation.rootInActiveWindow))
                stage(name)
                check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "the real $name Allow action was rejected" }
            }
            check(target.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            await("voice-stage")
            compose.waitUntil(DEVICE_TIMEOUT) {
                privateGraph.driveController.state.value.listening && serviceRunning() && activeRecordings() > 0 &&
                    privateGraph.recognizer!!.samples.value.seq > 0
            }
            val id = requireNotNull(privateGraph.driveController.state.value.sessionId)
            check(privateGraph.driveController.state.value.question == SEED_QUESTION)
            check(privateGraph.recognizer!!.samples.value.seq > 0) { "the native recognizer has sent no real audio-level event" }
            val active = notifications.activeNotifications.single { it.id == DriveService.NOTIFICATION_ID }
            check(active.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            check(active.notification.actions.map { it.title.toString() } ==
                listOf(target.getString(R.string.voice_pause), target.getString(R.string.voice_exit)))
            stage("hands-free-listening")
            snapshot(output, "listening")
            shell("cmd statusbar expand-notifications")
            // SystemUI opens asynchronously. Wait for the real action, not a short probe
            // followed by an assumed expand-button resource/package.
            val exit = checkNotNull(systemNode("com.android.systemui", text = target.getString(R.string.voice_exit)))
            checkNotNull(systemNode("com.android.systemui", text = target.getString(R.string.voice_pause)))
            stage("notification-pause-exit")
            check(exit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "the real notification Exit action was rejected" }
            shell("cmd statusbar collapse")
            await("answer")
            compose.waitUntil(DEVICE_TIMEOUT) { !serviceRunning() && activeRecordings() == 0 &&
                notifications.activeNotifications.none { it.id == DriveService.NOTIFICATION_ID } }
            val exitWrite = requireNotNull(privateGraph.driveController.draftWrite) { "Exit did not schedule its draft save" }
            runBlocking { withTimeout(DEVICE_TIMEOUT) { exitWrite.join() } }
            val saved = requireNotNull(privateGraph.sessionStore.get(id))
            check(stringOf(openTurn(saved)?.get("question")) == SEED_QUESTION)
            check(stringOf(openTurn(saved)?.get("answer")).isNullOrEmpty())
            check(stringOf(saved["status"]) != "done")
            check(privateGraph.driveController.state.value.mode == null)
            snapshot(output, "exited")
            stage("exited-same-typed-interview")
            check(SystemClock.elapsedRealtime() - began < 41_000) { "the required flow exceeded the bounded recording; not valid evidence" }
            File(output, "screenrecord.txt").writeText(recording.get(65, TimeUnit.SECONDS))
            recording = null
            check(!serviceRunning() && activeRecordings() == 0 &&
                notifications.activeNotifications.none { it.id == DriveService.NOTIFICATION_ID }) {
                "voice effects restarted after Exit while the remainder of the clip was recorded"
            }
            snapshot(output, "recording-ended")
            UxEvidence.requireComplete(expectedStages, events.map { it.getValue("stage").toString().trim('"') }.toSet(), failures)
            metadata = decodeVideo(video, output, events)
        } catch (error: Throwable) {
            primaryError = error
            runCatching { File(output, "failure-ui.txt").writeText(tree(automation.rootInActiveWindow)) }
            runCatching { snapshot(output, "failure") }
        } finally {
            UxEvidence.finish(primaryError, listOf(
                { recording?.let {
                    File(output, "screenrecord.txt").writeText(it.get(65, TimeUnit.SECONDS))
                    metadata = decodeVideo(video, output, events)
                } },
                { reader.shutdownNow() },
                { instrumentation.runOnMainSync { graph?.driveController?.exit() } },
                { scenario?.close() },
                { runBlocking {
                    graph?.mainScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                    graph?.appScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                } },
                { app.graph = originalGraph },
                { automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags } },
                { if (root.exists()) check(root.deleteRecursively()) },
            )) { errors ->
                errors.forEach { failures += "${it.javaClass.simpleName}: ${it.message}" }
                manifest(if (errors.isEmpty() && metadata != null) "passed" else "failed")
            }
        }
    }

    private fun activeRecordings(): Int = audio.activeRecordingConfigurations.count { !it.isClientSilenced }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean = target.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == DriveService::class.java.name && it.foreground }

    private fun snapshot(output: File, name: String) {
        File(output, "$name-system.txt").writeText(
            "=== services ===\n${shell("dumpsys activity services ${target.packageName}")}\n" +
                "=== audio ===\n${shell("dumpsys audio")}\n" +
                "=== appops ===\n${shell("cmd appops get ${target.packageName} RECORD_AUDIO")}\n" +
                "=== notification ===\n${shell("dumpsys notification --noredact")}\n",
        )
    }

    private fun decodeVideo(video: File, output: File, events: List<JsonObject>): JsonObject {
        check(video.length() > 0) { "screenrecord produced no video bytes" }
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(video.absolutePath)
            val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            check(duration in 30_000..60_000) { "actual MP4 duration is $duration ms, not 30–60 s" }
            extractor.setDataSource(video.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            for (event in events) {
                val time = event.getValue("videoTimeMs").toString().toLong()
                check(time < duration) { "required ${event["stage"]} is outside the actual recording" }
                val bitmap = requireNotNull(retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    UxEvidence.requireRendered(bitmap, UxBounds(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
                    val name = event.getValue("stage").toString().trim('"')
                    File(output, "video-$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            return buildJsonObject {
                put("artifact", video.name)
                put("bytes", video.length())
                put("sha256", MessageDigest.getInstance("SHA-256").digest(video.readBytes()).joinToString("") { "%02x".format(it) })
                put("durationMs", duration)
                put("mime", format.getString(MediaFormat.KEY_MIME))
                put("widthPx", format.getInteger(MediaFormat.KEY_WIDTH))
                put("heightPx", format.getInteger(MediaFormat.KEY_HEIGHT))
                put("decodedFrames", events.size)
            }
        } finally { retriever.release(); extractor.release() }
    }

    private fun systemNode(
        pkg: String, resource: String? = null, text: String? = null, required: Boolean = true,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + if (required) DEVICE_TIMEOUT else 500
        do {
            val root = automation.rootInActiveWindow
            val nodes = when {
                resource != null -> root?.findAccessibilityNodeInfosByViewId(resource)
                else -> root?.findAccessibilityNodeInfosByText(checkNotNull(text))
            }.orEmpty().filter { it.packageName?.toString() == pkg && it.isVisibleToUser &&
                (text == null || it.text?.toString().equals(text, ignoreCase = true)) }
            check(nodes.size <= 1) { "ambiguous system action $pkg/$resource/$text" }
            nodes.singleOrNull()?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        check(!required) { "missing actual system action $pkg/$resource/$text\n${tree(automation.rootInActiveWindow)}" }
        return null
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun await(tag: String) = compose.waitUntil(DEVICE_TIMEOUT) { exists(tag) }
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()

    private fun tree(node: AccessibilityNodeInfo?, depth: Int = 0): String = if (node == null) "(no active window)" else
        "${" ".repeat(depth)}${node.packageName} ${node.viewIdResourceName} ${node.text} ${node.contentDescription}\n" +
            (0 until node.childCount).joinToString("") { tree(node.getChild(it), depth + 1) }
}
