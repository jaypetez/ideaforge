package io.github.jaypetez.ideaforge.ui.settings

import io.github.jaypetez.ideaforge.FakeCheck
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.data.InferenceConsent
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SoftwareKey
import io.github.jaypetez.ideaforge.data.writeAtomically
import io.github.jaypetez.ideaforge.ui.onboarding.OnboardingViewModel
import io.github.jaypetez.ideaforge.voice.SpeakFailure
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.SpeechBackend
import io.github.jaypetez.ideaforge.voice.hostedSpeechReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hosted-speech gate in Settings, against a real KeyringStore and prefs in memory. The
 * previewer is a fake: no request is made, and nothing is ever billed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostedSpeechViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    // Cleared first: a view model left alive keeps its coroutines running into the next test
    // class, where an exception surfaces as UncaughtExceptionsBeforeTest against innocent code.
    @After
    fun reset() {
        store.clear()
        Dispatchers.resetMain()
    }

    private val previews = mutableListOf<Triple<String, String, Double>>()
    private var answer: (String) -> SpeakResult = { SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.HOSTED) }

    private var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val revokes = mutableListOf<String>()
    private val prefs = PrefsStore(MemoryDataStore())
    private val key = SoftwareKey()
    @Volatile private var writer: (File, ByteArray) -> Unit = ::writeAtomically
    private val keyring by lazy {
        KeyringStore(File(tmp.root, "nobackup/${KeyringStore.FILE_NAME}").apply { parentFile!!.mkdirs() }, key,
            writer = { file, bytes -> writer(file, bytes) })
    }
    private val store = ViewModelStore()
    private val vm by lazy {
        runBlocking {
            keyring.load()
            // An interview key already saved: speech must never borrow it.
            keyring.save(withCreds(emptyKeyring(), "openai", JsonObject(mapOf("apiKey" to JsonPrimitive(INFERENCE)))))
        }
        HostedSpeechViewModel(prefs, keyring, previewer = { key, voice, rate ->
            previews += Triple(key, voice, rate)
            hold?.await()
            answer(key)
        }, onRevoke = { revokes += it }).also { store.put("speech", it) }
    }

    private fun join(job: Job?) = runBlocking { withTimeout(10_000) { job?.join() } }
    private fun settle(ok: (HostedSpeechState) -> Boolean) = runBlocking { withTimeout(10_000) { vm.state.first(ok) } }
    private fun ring(): JsonElement = keyring.state.value.ring
    private fun ready() = runBlocking { hostedSpeechReady(ring(), prefs.current()) }

    private fun consentAll() {
        vm.setConsent(synthetic = true)
        vm.setConsent(text = true)
        vm.setConsent(metered = true)
    }

    private fun enable(key: String = SPEECH) {
        join(vm.chooseHosted(true))
        vm.setKey(key)
        consentAll()
        join(vm.checkAndPreview())
        settle { it.enabled }
    }

    private fun independentSaves(): SettingsViewModel {
        vm.state
        runBlocking {
            keyring.save(withCreds(keyring.state.value.ring, "anthropic",
                JsonObject(mapOf("apiKey" to JsonPrimitive("synthetic-other-provider")))))
        }
        val inference = InferenceConsent(prefs, { keyring.state.value.ring }, vm.viewModelScope)
        val settings = SettingsViewModel(prefs, keyring, FakeCheck(), inference).also { store.put("settings", it) }
        settings.pickProvider("openai")
        settings.setApiKey("synthetic-new-inference")
        settings.setAgreement(true)
        vm.setSttKind("groq")
        vm.setSttKey("synthetic-transcription")
        join(vm.chooseHosted(true))
        vm.setKey(SPEECH)
        consentAll()
        return settings
    }

    private fun overlapping(vararg saves: () -> Job?, whileHeld: () -> Job? = { null }) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs = mutableListOf<Job?>()
        var first = true
        writer = { file, bytes ->
            if (first) {
                first = false
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "held credential write was not released" }
            }
            writeAtomically(file, bytes)
        }
        try {
            jobs += saves.first().invoke()
            assertTrue("the first real writer is held before durability", entered.await(10, TimeUnit.SECONDS))
            saves.drop(1).forEach { jobs += it() }
            jobs += whileHeld()
        } finally {
            release.countDown()
            jobs.forEach(::join)
            writer = ::writeAtomically
        }
    }

    private fun assertIndependentSaves() = runBlocking {
        val loaded = KeyringStore(keyring.file, key).load()
        assertEquals(KeyringState.Status.STORED, loaded.status)
        assertEquals("synthetic-new-inference", stringOf(credsFor(loaded.ring, "openai")["apiKey"]))
        assertEquals("synthetic-other-provider", stringOf(credsFor(loaded.ring, "anthropic")["apiKey"]))
        assertEquals("openai", stringOf(loaded.ring["active"]))
        assertEquals("synthetic-transcription", stringOf(loaded.ring["stt"]["apiKey"]))
        assertEquals(SPEECH, stringOf(loaded.ring["tts"]["apiKey"]))
        assertEquals(JsonPrimitive(true), loaded.ring["tts"]["verified"])
        assertEquals("memory must describe the durable merged ring", loaded.ring, ring())
    }

    @Test
    fun overlappingIndependentSavesMustKeepTheNewConnection() {
        val settings = independentSaves()
        overlapping(settings::save, vm::saveTranscription, vm::checkAndPreview)
        assertIndependentSaves()
    }

    @Test
    fun reorderedSpeechTranscriptionAndConnectionSavesKeepAllBranches() {
        val settings = independentSaves()
        overlapping(vm::checkAndPreview, vm::saveTranscription, settings::save)
        assertIndependentSaves()
    }

    @Test
    fun onboardingKeepsQueuedSpeechWritesAndUneditedProviderModels() {
        independentSaves()
        runBlocking {
            keyring.save(withCreds(keyring.state.value.ring, "openai",
                JsonObject(mapOf("apiKey" to JsonPrimitive(INFERENCE), "wrapModel" to JsonPrimitive("saved-wrap-model")))))
        }
        val inference = InferenceConsent(prefs, { keyring.state.value.ring }, vm.viewModelScope)
        val onboarding = OnboardingViewModel(keyring, FakeCheck(), inference).also { store.put("onboarding", it) }
        onboarding.pick("openai")
        onboarding.setApiKey("synthetic-new-inference")
        onboarding.setAgreement(true)
        overlapping(vm::checkAndPreview, vm::saveTranscription, { onboarding.checkKey(); null })
        runBlocking { withTimeout(10_000) { onboarding.state.first { it.done || it.failure != null } } }
        assertTrue(onboarding.state.value.done)
        assertIndependentSaves()
        assertEquals("saved-wrap-model", stringOf(credsFor(ring(), "openai")["wrapModel"]))
    }

    @Test
    fun sequentialIndependentSavesKeepBothBranchesControl() {
        val settings = independentSaves()
        join(settings.save())
        join(vm.saveTranscription())
        join(vm.checkAndPreview())
        assertIndependentSaves()
    }

    @Test
    fun forgetWhileIndependentSavesAreQueuedLeavesNoCredentials() {
        val settings = independentSaves()
        overlapping(settings::save, vm::saveTranscription, vm::checkAndPreview, whileHeld = settings::forget)
        assertFalse(keyring.file.exists())
        assertEquals(KeyringState.Status.NONE, keyring.state.value.status)
        assertTrue(key.keys.all { it == null })
        assertFalse(ready())
    }

    @Test
    fun newerTranscriptionEditsSurviveAPendingSave() {
        independentSaves()
        overlapping(vm::saveTranscription, whileHeld = {
            vm.setSttKind("openai")
            vm.setSttKey("synthetic-newer-transcription")
            null
        })
        assertEquals("synthetic-transcription", stringOf(ring()["stt"]["apiKey"]))
        val edited = settle { !it.checking }
        assertEquals("synthetic-newer-transcription", edited.sttKeyDraft)
        assertEquals("openai", edited.sttKind)
    }

    // Acceptance: no hosted speech without consent and a passing check.
    @Test
    fun noCheckRunsWithoutAllThreeConsents() {
        join(vm.chooseHosted(true))
        vm.setKey(SPEECH)
        vm.setConsent(synthetic = true)
        vm.setConsent(text = true)
        assertFalse(settle { true }.canCheck)
        join(vm.checkAndPreview())
        assertTrue("nothing was sent before consent", previews.isEmpty())
        assertFalse(ready())
    }

    @Test
    fun aPassingConsentedCheckEnablesHostedSpeechWithTheSpeechKey() {
        enable()
        assertEquals("the speech key, never the inference key", listOf(SPEECH), previews.map { it.first })
        assertTrue(ready())
        assertEquals("true", ring()["tts"]["verified"].toString())
        assertEquals("other records kept", INFERENCE, stringOf(ring()["byKind"]["openai"]["apiKey"]))
    }

    @Test
    fun aFailedCheckEnablesNothing() {
        answer = { SpeakResult(SpeakStatus.FAILED, SpeakFailure.ENGINE_ERROR, "OpenAI speech 401", backend = SpeechBackend.HOSTED) }
        join(vm.chooseHosted(true))
        vm.setKey(SPEECH)
        consentAll()
        join(vm.checkAndPreview())
        assertTrue(settle { it.notice?.contains("failed") == true }.notice!!.contains("401"))
        assertFalse(ready())
        assertFalse(runBlocking { prefs.current() }.hostedSpeechAllowed)
    }

    @Test
    fun transcriptionStatusNeverOverwritesTheIndependentSpeechFailure() {
        answer = { SpeakResult(SpeakStatus.FAILED, SpeakFailure.ENGINE_ERROR, "controlled speech failure",
            backend = SpeechBackend.HOSTED) }
        join(vm.chooseHosted(true))
        vm.setKey(SPEECH)
        consentAll()
        join(vm.checkAndPreview())
        val failed = settle { it.noticeFailed }.notice
        vm.setSttKind("groq")
        join(vm.saveTranscription())
        val state = settle { it.sttNoticeFailed }
        assertEquals(failed, state.notice)
        assertTrue(state.noticeFailed)
        assertTrue(state.sttNotice!!.contains("own key"))
        assertFalse(ready())
        vm.setSttKey("scripted-stt-key")
        join(vm.saveTranscription())
        val saved = settle { it.sttNotice?.contains("saved") == true }
        assertFalse(saved.sttNoticeFailed)
        assertEquals(failed, saved.notice)
        assertEquals(INFERENCE, stringOf(ring()["byKind"]["openai"]["apiKey"]))
    }

    @Test
    fun unreadableKeysCannotBeReportedAsASuccessfulVoiceChange() {
        vm.state
        keyring.file.writeBytes(byteArrayOf(1, 2, 3))
        runBlocking { keyring.load() }
        join(vm.chooseHosted(false))
        val failed = settle { it.noticeFailed }
        assertTrue(failed.notice!!.contains("could not be read"))
        assertFalse(failed.enabled)
    }

    // Acceptance: a fallback never passes the check, even if it "spoke".
    @Test
    fun aFallbackThatSpokeIsNotAPass() {
        answer = { SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.NATIVE, fallback = "OpenAI failed; device voice used") }
        join(vm.chooseHosted(true))
        vm.setKey(SPEECH)
        consentAll()
        join(vm.checkAndPreview())
        assertFalse(ready())
        answer = { SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.NATIVE) }
        join(vm.checkAndPreview())
        assertFalse("device-voice audio proves nothing about OpenAI", ready())
    }

    // Acceptance: a key or voice change requires a new check.
    @Test
    fun aVoiceChangeRequiresANewCheck() {
        enable()
        join(vm.setVoice("cedar"))
        assertFalse(ready())
        assertEquals("cedar", stringOf(ring()["tts"]["voice"]))
        consentAll()
        join(vm.checkAndPreview())
        assertTrue(settle { it.enabled }.enabled)
        assertEquals("cedar", previews.last().second)
    }

    @Test
    fun aKeyChangeRequiresANewCheck() {
        enable()
        join(vm.setKey("sk-another-speech-key"))
        assertFalse(ready())
        assertEquals("the old key stays saved until the new one passes", SPEECH, stringOf(ring()["tts"]["apiKey"]))
        consentAll()
        join(vm.checkAndPreview())
        settle { it.enabled }
        assertEquals("sk-another-speech-key", stringOf(ring()["tts"]["apiKey"]))
    }

    @Test
    fun withdrawingAnyConsentOrChoosingTheDeviceVoiceDisablesIt() {
        enable()
        join(vm.setConsent(metered = false))
        assertFalse(ready())
        assertFalse(runBlocking { prefs.current() }.hostedSpeechAllowed)

        enable()
        join(vm.chooseHosted(false))
        assertFalse(ready())
        assertEquals("browser", stringOf(ring()["tts"]["kind"]))
    }

    @Test
    fun transcriptionKeepsItsOwnKeyAndAChangedVendorNeverInheritsOne() {
        vm.setSttKind("groq")
        vm.setSttKey("gsk_transcription")
        join(vm.saveTranscription())
        assertEquals("gsk_transcription", stringOf(ring()["stt"]["apiKey"]))
        assertEquals(INFERENCE, stringOf(ring()["byKind"]["openai"]["apiKey"]))

        vm.setSttKind("openai")
        join(vm.saveTranscription())
        assertEquals("refused: no key of its own", "groq", stringOf(ring()["stt"]["kind"]))

        join(vm.setAudioConsent(true))
        vm.setSttKind(null)
        join(vm.saveTranscription())
        assertEquals("", stringOf(ring()["stt"]["apiKey"]))
        assertFalse("turning it off withdraws the audio consent", runBlocking { prefs.current() }.hostedTranscriptionAllowed)
        assertTrue(previews.isEmpty())
        // withStt is the only writer: the record shape is the web's.
        assertEquals(withStt(emptyKeyring(), null)["stt"], ring()["stt"])
    }

    // Review #93 (HIGH): Forget while a check is playing must not put the speech key back on
    // disk or turn hosted speech on afterwards. The web's credentialEpoch.
    @Test
    fun forgetDuringACheckLeavesNoKeyAndNoPermission() {
        val forgets = mutableListOf<Unit>()
        val inference = io.github.jaypetez.ideaforge.data.InferenceConsent(prefs, { keyring.state.value.ring },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val settings = SettingsViewModel(prefs, keyring, FakeCheck(), inference, onForget = { forgets += Unit }).also { store.put("settings", it) }
        join(vm.chooseHosted(true))
        vm.setKey(SPEECH)
        consentAll()
        hold = kotlinx.coroutines.CompletableDeferred()
        val checking = vm.checkAndPreview()
        settle { it.checking }
        join(settings.forget())
        assertEquals("Forget stopped speech and paused the drive first", 1, forgets.size)
        hold!!.complete(Unit) // the preview "passes" after the keys are gone
        join(checking)
        assertFalse("no key file came back", File(tmp.root, "nobackup/${KeyringStore.FILE_NAME}").exists())
        assertEquals("", stringOf(ring()["tts"]["apiKey"]))
        assertFalse(runBlocking { prefs.current() }.hostedSpeechAllowed)
        assertFalse(ready())
    }

    @Test
    fun aTranscriptionSaveBegunBeforeForgetWritesNothing() {
        vm.setSttKind("groq")
        vm.setSttKey("gsk_transcription")
        val generation = keyring.generation.value
        keyring.revoke() // Forget has begun
        assertFalse(runBlocking { keyring.saveIf(generation, withStt(ring(), JsonObject(mapOf("kind" to JsonPrimitive("groq"), "apiKey" to JsonPrimitive("gsk_x"))))) })
        assertEquals("refused: nothing written", "", stringOf(ring()["stt"]["apiKey"]))
    }

    // Review #93: withdrawing consent stops what is speaking and pauses an active drive.
    @Test
    fun revokingHostedSpeechStopsSpeechAndPausesTheDrive() {
        enable()
        assertTrue(revokes.isEmpty())
        join(vm.setConsent(text = false))
        assertEquals(1, revokes.size)
    }

    // Main went red on this (#93): `state` is combined asynchronously, so it can lag the
    // taps. Actions that read it let quick consent taps overwrite each other, and the check
    // never ran. Main is paused here so the screen state cannot catch up at all.
    @Test
    fun quickTapsAreNeverLostWhileTheScreenStateLags() {
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        vm.chooseHosted(true)
        paused.scheduler.runCurrent()
        vm.setKey(SPEECH)
        consentAll()
        assertTrue("the check started from the taps, not the stale screen", vm.checkAndPreview() != null)
        paused.scheduler.runCurrent()
        assertEquals(listOf(SPEECH), previews.map { it.first })
    }

    private companion object {
        const val INFERENCE = "sk-inference-key"
        const val SPEECH = "sk-speech-key"
    }
}
