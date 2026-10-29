package io.github.jaypetez.ideaforge.probe

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.content.pm.PackageManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.jaypetez.ideaforge.MainActivity
import io.github.jaypetez.ideaforge.installTestGraph
import io.github.jaypetez.ideaforge.usableRing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File
import java.time.Duration

/** The debug build's recognizer probe (issue #89) launches, and the launch hook reaches it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognizerProbeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theProbeLaunchesAndReportsTheRecognizer() {
        installTestGraph(ring = usableRing(), inferenceAgreed = true)
        ActivityScenario.launch(RecognizerProbeActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(RecognizerProbeActivity.SCREEN), 10_000)
            compose.waitUntilAtLeastOneExists(hasText("Start trial"), 5_000)
            // The capability report runs on launch, before any trial.
            assertTrue(ProbeRunner.text(), ProbeRunner.text().contains("isRecognitionAvailable:"))
        }
    }

    @Test
    fun theDebugManifestDeclaresTheProbeAndItsPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS)
        assertTrue(info.activities!!.any { it.name == RecognizerProbeActivity::class.java.name })
        assertTrue(info.requestedPermissions!!.contains(android.Manifest.permission.RECORD_AUDIO))
        val probe = info.activities!!.single { it.name == RecognizerProbeActivity::class.java.name }
        assertFalse("the probe activity is exported", probe.exported)
    }

    @Test
    fun probeRecognizerOnTheLaunchIntentOpensTheProbe() {
        installTestGraph(ring = usableRing(), inferenceAgreed = true)
        val launch = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("probe", "recognizer").putExtra("restarts", 3)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            scenario.onActivity { activity ->
                val next = shadowOf(activity).nextStartedActivity
                assertNotNull("nothing was started", next)
                assertEquals(RecognizerProbeActivity::class.java.name, next.component!!.className)
                // Trial settings ride along, so a phone run can be scripted over adb.
                assertEquals(3, RecognizerProbeActivity.configFrom(next).restarts)
            }
        }
    }

    private fun appDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile && File(it, "src/debug").isDirectory }
            ?: error("no android/app above $start")
    }

    // AGP creates no release unit-test task, so this debug test reads the release variant's
    // final merged manifest (wired in by app/build.gradle.kts) and denies everything the
    // debug manifest adds: each permission, the <queries> block, and the probe components.
    @Test
    fun theReleaseMergedManifestHasNothingTheProbeAdds() {
        val path = System.getProperty("ideaforge.releaseManifest") ?: error("ideaforge.releaseManifest is not set")
        val release = File(path).readText()
        assertTrue("not the app's manifest: $path", release.contains("io.github.jaypetez.ideaforge.MainActivity"))
        val debug = File(appDir(), "src/debug/AndroidManifest.xml").readText()
        // An explicit allowlist, not "whatever main declares": subtracting main would let a
        // probe permission copied into main pass. RECORD_AUDIO and the RecognitionService
        // query belong to the app itself since native capture (#91), and the microphone
        // foreground service and its notification since the drive service (#92). WAKE_LOCK
        // stays the probe's: the drive holds none. Nothing else may leak.
        val sharedPermissions = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
        )
        val sharedQueries = setOf("android.speech.RecognitionService", "android.intent.action.TTS_SERVICE")
        fun permissions(x: String) = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(x).map { it.groupValues[1] }.toSet()
        fun queryActions(x: String) = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL).findAll(x)
            .flatMap { q -> Regex("""<action\s+android:name="([^"]+)"""").findAll(q.groupValues[1]).map { it.groupValues[1] } }.toSet()
        val added = permissions(debug) - sharedPermissions
        assertTrue("the deny list came out empty", added.isNotEmpty())
        for (permission in added) assertFalse("release asks for $permission", release.contains("\"$permission\""))
        val addedQueries = queryActions(debug) - sharedQueries
        assertTrue("the debug manifest adds no query", addedQueries.isNotEmpty())
        for (action in addedQueries) assertFalse("release queries $action", release.contains("\"$action\""))
        for (component in listOf(".probe.", "RecognizerProbeActivity", "ProbeMicService")) {
            assertFalse("release names $component", release.contains(component))
        }
    }

    // Spoken questions need the TTS engine to be visible (package visibility, API 30+). When
    // only the debug manifest declared the query, a release build could not speak at all.
    @Test
    fun theReleaseMergedManifestCanSeeTheSpeechServices() {
        val path = System.getProperty("ideaforge.releaseManifest") ?: error("ideaforge.releaseManifest is not set")
        val release = File(path).readText()
        val queries = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL).findAll(release)
            .flatMap { q -> Regex("""<action\s+android:name="([^"]+)"""").findAll(q.groupValues[1]).map { it.groupValues[1] } }.toSet()
        for (action in listOf("android.intent.action.TTS_SERVICE", "android.speech.RecognitionService")) {
            assertTrue("release does not query $action: $queries", action in queries)
        }
    }

    @Test
    fun releaseReachableSourcesNeverNameTheProbe() {
        val app = appDir()
        val leaks = (File(app, "src/main").walkTopDown() + File(app, "src/release").walkTopDown())
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> f.readText().let { it.contains("RecognizerProbeActivity") || it.contains("ideaforge.probe") } }
            .toList()
        assertTrue("release-reachable sources name the probe: $leaks", leaks.isEmpty())
        val releaseHook = File(app, "src/release/kotlin/io/github/jaypetez/ideaforge/DebugProbes.kt").readText()
        assertTrue(releaseHook.contains("): Boolean = false"))
    }

    // ---- trials against a fake engine ------------------------------------------------------

    private class FakeEngine : ProbeEngine {
        val calls = mutableListOf<String>()
        override fun setRecognitionListener(listener: RecognitionListener) {}
        override fun startListening(intent: Intent) { calls += "start" }
        override fun stopListening() { calls += "stop" } // never answers: a deaf engine
        override fun cancel() { calls += "cancel" }
        override fun destroy() { calls += "destroy" }
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    private fun withFakeEngines(body: (MutableList<FakeEngine>) -> Unit) {
        val engines = mutableListOf<FakeEngine>()
        val real = ProbeRunner.engineFactory
        ProbeRunner.engineFactory = { _, _ -> FakeEngine().also { engines += it } }
        try {
            body(engines)
        } finally {
            ProbeRunner.cancel(); ProbeRunner.engineFactory = real; ProbeRunner.clear(app)
        }
    }

    // Each trial restarts `session` at 1, so trial A's watchdog used to match trial B's
    // session 1 and cancel it. Found in review, not by a run.
    @Test
    fun anEarlierTrialsWatchdogLeavesALaterTrialAlone() = withFakeEngines { engines ->
        ProbeRunner.start(app, TrialConfig()); advance(10)
        advance(1_000); ProbeRunner.cancel()               // A ends; its 60 s watchdog is still queued
        advance(1_000)
        ProbeRunner.start(app, TrialConfig()); advance(10)  // B's session 1
        advance(58_500)                                     // A's watchdog fires here; B's at ~62 s
        assertTrue("trial B was ended by trial A's watchdog", ProbeRunner.running.value)
        assertFalse(engines[1].calls.contains("cancel"))
        assertFalse(ProbeRunner.text().contains("watchdog"))
        advance(2_000)                                      // B's own watchdog still works
        assertFalse(ProbeRunner.running.value)
        assertTrue(ProbeRunner.text().contains("watchdog"))
    }

    @Test
    fun anEarlierTrialsStopTimerLeavesALaterTrialAlone() = withFakeEngines {
        ProbeRunner.start(app, TrialConfig()); advance(10)
        ProbeRunner.stopListening(); advance(100); ProbeRunner.cancel() // A's 5 s stop timer is queued
        advance(900)
        ProbeRunner.start(app, TrialConfig()); advance(10)
        advance(3_000); ProbeRunner.stopListening()                    // B's own timer is 5 s from here
        advance(1_100)                                                 // A's timer fires here
        assertTrue("trial B was ended by trial A's stop timer", ProbeRunner.running.value)
        advance(5_000)
        assertFalse(ProbeRunner.running.value)
        assertTrue(ProbeRunner.text().contains("no end callback 5 s after stopListening"))
    }

    private val ttsInstances = mutableListOf<TextToSpeech>()

    @After
    fun releaseSyntheticPreparations() {
        ttsInstances.forEach {
            it.shutdown()
            shadowOf(it).lastSynthesizeToFile?.delete()
        }
    }

    private fun createdTts(): ShadowTextToSpeech =
        ShadowTextToSpeech.getLastTextToSpeechInstance().let { tts ->
            ttsInstances += tts
            shadowOf(tts)
        }

    private fun heldSynthesis(): ShadowTextToSpeech =
        createdTts().also {
            it.onInitListener.onInit(TextToSpeech.SUCCESS)
            advance(0)
            assertNotNull("synthesis did not reach its output file", it.lastSynthesizeToFile)
        }

    private fun completeSynthesis(tts: ShadowTextToSpeech, sample: Byte, rate: Int = 24_000) {
        tts.lastSynthesizeToFile.writeBytes(wav(byteArrayOf(sample, 0, sample, 0), rate))
        tts.utteranceProgressListener.onDone("probe")
        advance(0)
    }

    @Test
    fun aCancelledTtsFailureCannotFinishTheReplacementTrial() = withFakeEngines { engines ->
        ProbeRunner.start(app, TrialConfig(ttsPhrase = "cancelled failure"))
        val old = heldSynthesis()
        ProbeRunner.cancel()
        ProbeRunner.start(app, TrialConfig(wakeLock = true, foregroundService = true))
        advance(10)
        val activeWakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(activeWakeLock.isHeld)
        old.utteranceProgressListener.onError("probe", TextToSpeech.ERROR)
        advance(0)
        assertTrue("a retired failure ended the replacement trial", ProbeRunner.running.value)
        assertEquals(listOf("start"), engines.single().calls)
        assertTrue("a retired failure released the replacement wake lock", activeWakeLock.isHeld)
        val application = shadowOf(app as Application)
        assertNull("a retired failure stopped the replacement service", application.nextStoppedService)
        ProbeRunner.cancel()
        assertFalse(activeWakeLock.isHeld)
        assertEquals(ProbeMicService::class.java.name, application.nextStoppedService.component!!.className)
    }

    @Test
    fun aCancelledTtsSuccessCannotBeginOrReplaceTheNextPreparation() = withFakeEngines { engines ->
        for (oldFinishesLast in listOf(false, true)) {
            ProbeRunner.start(app, TrialConfig(ttsPhrase = "cancelled success $oldFinishesLast", delayMs = 500))
            val old = heldSynthesis()
            ProbeRunner.cancel()
            ProbeRunner.start(app, TrialConfig(ttsPhrase = "current success $oldFinishesLast", delayMs = 5_000))
            val current = heldSynthesis()
            try {
                if (oldFinishesLast) completeSynthesis(current, 2)
                val before = ProbeRunner.lines.value
                completeSynthesis(old, 1, 16_000)
                assertEquals("a retired success changed the replacement trial", before, ProbeRunner.lines.value)
                advance(600)
                assertTrue("a retired preparation started the replacement recognizer", engines.isEmpty())
                if (!oldFinishesLast) completeSynthesis(current, 2)
                assertTrue(ProbeRunner.running.value)
                assertTrue(ProbeRunner.text().contains("24000 Hz, 1 ch"))
            } finally {
                ProbeRunner.cancel()
            }
        }
    }

    @Test
    fun aQueuedTtsCompletionCannotBorrowTheReplacementTrial() = withFakeEngines { engines ->
        for (success in listOf(false, true)) {
            ProbeRunner.start(app, TrialConfig(ttsPhrase = "queued $success", delayMs = 500))
            val old = heldSynthesis()
            if (success) {
                old.lastSynthesizeToFile.writeBytes(wav(byteArrayOf(1, 0, 1, 0)))
                old.utteranceProgressListener.onDone("probe")
            } else old.utteranceProgressListener.onError("probe", TextToSpeech.ERROR)
            // Deliver the native result, but leave the runner's callback queued across Cancel.
            shadowOf(Looper.getMainLooper()).runOneTask()
            assertTrue(old.isShutdown)
            ProbeRunner.cancel()
            ProbeRunner.start(app, TrialConfig(delayMs = 2_000))
            val before = ProbeRunner.lines.value
            advance(600)
            assertEquals("a queued result changed the replacement trial", before, ProbeRunner.lines.value)
            assertTrue(ProbeRunner.running.value)
            val starts = engines.size
            advance(1_400)
            assertEquals("the replacement's own delayed start still runs", starts + 1, engines.size)
            ProbeRunner.cancel()
        }
    }

    @Test
    fun overlappingTtsPreparationsKeepTheirOwnOutputBytes() {
        val results = mutableMapOf<String, TtsClip?>()
        val firstRequest = TtsClip.synthesize(app, "first") { clip, _ -> results["first"] = clip }
        val first = heldSynthesis()
        val secondRequest = TtsClip.synthesize(app, "second") { clip, _ -> results["second"] = clip }
        val second = heldSynthesis()
        try {
            assertNotEquals("concurrent TTS requests share an output file", first.lastSynthesizeToFile, second.lastSynthesizeToFile)
            first.lastSynthesizeToFile.writeBytes(wav(byteArrayOf(1, 0, 1, 0), 16_000))
            second.lastSynthesizeToFile.writeBytes(wav(byteArrayOf(2, 0, 2, 0)))
            second.utteranceProgressListener.onDone("probe")
            first.utteranceProgressListener.onDone("probe")
            advance(0)
            assertEquals(listOf<Byte>(1, 0, 1, 0), results["first"]?.pcm?.toList())
            assertEquals(16_000, results["first"]?.sampleRate)
            assertEquals(listOf<Byte>(2, 0, 2, 0), results["second"]?.pcm?.toList())
            assertEquals(24_000, results["second"]?.sampleRate)
            assertTrue(first.isShutdown)
            assertTrue(second.isShutdown)
            assertFalse(first.lastSynthesizeToFile.exists())
            assertFalse(second.lastSynthesizeToFile.exists())
        } finally {
            firstRequest.cancel()
            secondRequest.cancel()
        }
    }

    @Test
    fun cancellingOneTtsRequestDoesNotRemoveAnotherRequestsOutput() {
        val results = mutableListOf<TtsClip?>()
        val firstRequest = TtsClip.synthesize(app, "cancel this") { clip, _ -> results += clip }
        val first = heldSynthesis()
        val secondRequest = TtsClip.synthesize(app, "keep this") { clip, _ -> results += clip }
        val second = heldSynthesis()
        try {
            second.lastSynthesizeToFile.writeBytes(wav(byteArrayOf(2, 0, 2, 0)))
            firstRequest.cancel()
            assertTrue(first.isShutdown)
            assertFalse(first.lastSynthesizeToFile.exists())
            assertFalse(second.isShutdown)
            assertTrue(second.lastSynthesizeToFile.exists())
            completeSynthesis(first, 1) // even a late writer owns only its retired output
            assertTrue(results.isEmpty())
            assertFalse(first.lastSynthesizeToFile.exists())
            assertTrue(second.lastSynthesizeToFile.exists())
            second.utteranceProgressListener.onDone("probe")
            advance(0)
            assertEquals(listOf<Byte>(2, 0, 2, 0), results.single()!!.pcm.toList())
            assertFalse(second.lastSynthesizeToFile.exists())
        } finally {
            firstRequest.cancel()
            secondRequest.cancel()
        }
    }

    @Test
    fun aCurrentTtsPreparationReturnsItsParsedClip() {
        val results = mutableListOf<TtsClip?>()
        TtsClip.synthesize(app, "current control") { clip, _ -> results += clip }
        val current = heldSynthesis()
        completeSynthesis(current, 3)
        current.utteranceProgressListener.onError("probe", TextToSpeech.ERROR)
        current.utteranceProgressListener.onDone("probe")
        advance(0)
        val result = results.single()
        assertNotNull(result)
        assertEquals(listOf<Byte>(3, 0, 3, 0), result!!.pcm.toList())
        assertEquals(24_000, result.sampleRate)
        assertTrue(current.isShutdown)
        assertFalse(current.lastSynthesizeToFile.exists())
    }

    @Test
    fun stoppingDuringTtsPreparationReleasesItWithoutStartingRecognition() = withFakeEngines { engines ->
        ProbeRunner.start(app, TrialConfig(ttsPhrase = "stop before ready", wakeLock = true))
        val current = heldSynthesis()
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        ProbeRunner.stopListening()
        assertTrue(current.isShutdown)
        assertFalse(current.lastSynthesizeToFile.exists())
        completeSynthesis(current, 3)
        advance(4_000)
        assertTrue(engines.isEmpty())
        assertTrue(ProbeRunner.running.value)
        advance(1_000)
        assertFalse(ProbeRunner.running.value)
        assertFalse(wakeLock.isHeld)
        assertTrue(ProbeRunner.text().contains("no end callback 5 s after stopListening"))
    }

    @Test
    fun aCurrentTtsFailureStillFinishesAndCleansUpItsTrial() = withFakeEngines {
        for (initFailure in listOf(true, false)) {
            val before = app.cacheDir.listFiles()!!.toSet()
            ProbeRunner.start(app, TrialConfig(ttsPhrase = "failed $initFailure", wakeLock = true))
            val current = if (initFailure) createdTts() else heldSynthesis()
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            if (initFailure) current.onInitListener.onInit(TextToSpeech.ERROR)
            else current.utteranceProgressListener.onDone("probe") // the shadow's text is not a WAV
            advance(0)
            assertFalse(ProbeRunner.running.value)
            assertTrue(ProbeRunner.text().contains("finished: no audio"))
            assertTrue(current.isShutdown)
            assertFalse(wakeLock.isHeld)
            assertEquals("failed preparation left its output behind", before, app.cacheDir.listFiles()!!.toSet())
        }
    }

    @Test
    fun cancellingBeforeTtsInitPreventsLateSynthesis() {
        val before = app.cacheDir.listFiles()!!.toSet()
        val results = mutableListOf<TtsClip?>()
        val request = TtsClip.synthesize(app, "never start this") { clip, _ -> results += clip }
        val current = createdTts()
        request.cancel()
        assertTrue(current.isShutdown)
        current.onInitListener.onInit(TextToSpeech.SUCCESS)
        advance(0)
        assertNull(current.lastSynthesizeToFile)
        assertTrue(results.isEmpty())
        assertEquals(before, app.cacheDir.listFiles()!!.toSet())
    }

    @Test
    fun aReportDuringATrialKeepsTheTrialClock() = withFakeEngines {
        ProbeRunner.start(app, TrialConfig()); advance(10)
        advance(2_000)
        ProbeRunner.report(app)
        val ms = ProbeRunner.lines.value.last().trim().substringBefore(' ').toDouble()
        assertTrue("the report re-timed the trial: $ms ms", ms >= 2_000)
    }

    // The probe's activity is not exported but MainActivity is, and forwards its extras: an
    // intent from any app must not start the microphone. Autostart needs a shell-only property.
    @Test
    fun autostartFromAnIntentNeedsTheShellProperty() = withFakeEngines { engines ->
        val gate = RecognizerProbeActivity.shellAllowsAutostart
        try {
            installTestGraph(ring = usableRing(), inferenceAgreed = true)
            val hostile = Intent(app, RecognizerProbeActivity::class.java)
                .putExtra("autostart", true).putExtra("fgs", true).putExtra("wakelock", true)
            RecognizerProbeActivity.shellAllowsAutostart = { false }
            ActivityScenario.launch<RecognizerProbeActivity>(hostile).use { advance(100) }
            assertFalse(ProbeRunner.running.value)
            assertTrue(engines.isEmpty())
            assertTrue(ProbeRunner.text().contains("autostart ignored"))
            RecognizerProbeActivity.shellAllowsAutostart = { true }
            val shell = Intent(app, RecognizerProbeActivity::class.java).putExtra("autostart", true)
            ActivityScenario.launch<RecognizerProbeActivity>(shell).use {
                advance(100)
                assertTrue(ProbeRunner.running.value)
            }
        } finally {
            RecognizerProbeActivity.shellAllowsAutostart = gate
        }
    }

    @Test
    fun errorCodesAreNamedAndUnknownOnesStayNumbers() {
        assertEquals("ERROR_NO_MATCH", ProbeFormat.errorName(7))
        assertEquals("ERROR_RECOGNIZER_BUSY", ProbeFormat.errorName(8))
        assertEquals("ERROR_INSUFFICIENT_PERMISSIONS", ProbeFormat.errorName(9))
        assertEquals("ERROR_UNKNOWN(99)", ProbeFormat.errorName(99))
    }

    @Test
    fun hypothesesPairTextWithConfidence() {
        assertEquals("[\"over\"@0.912 | \"over there\"@n/a]", ProbeFormat.hypotheses(listOf("over", "over there"), floatArrayOf(0.912f)))
        assertEquals("texts=<none>", ProbeFormat.hypotheses(null, null))
    }

    @Test
    fun aParsedWavKeepsItsFormat() {
        // 44-byte canonical header, 24 kHz mono 16-bit, four samples.
        val data = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val clip = TtsClip.parseWav(wav(data))
        assertEquals(24_000, clip.sampleRate)
        assertEquals(1, clip.channels)
        assertEquals(data.toList(), clip.pcm.toList())
    }

    private fun wav(data: ByteArray, rate: Int = 24_000): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        return header + data
    }
}
