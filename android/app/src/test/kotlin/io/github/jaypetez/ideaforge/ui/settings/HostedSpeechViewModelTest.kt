package io.github.jaypetez.ideaforge.ui.settings

import io.github.jaypetez.ideaforge.FakeCheck
import androidx.lifecycle.ViewModelStore
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SoftwareKey
import io.github.jaypetez.ideaforge.voice.SpeakFailure
import io.github.jaypetez.ideaforge.voice.SpeakResult
import io.github.jaypetez.ideaforge.voice.SpeakStatus
import io.github.jaypetez.ideaforge.voice.SpeechBackend
import io.github.jaypetez.ideaforge.voice.hostedSpeechReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    @After
    fun reset() = Dispatchers.resetMain()

    private val previews = mutableListOf<Triple<String, String, Double>>()
    private var answer: (String) -> SpeakResult = { SpeakResult(SpeakStatus.SPOKEN, backend = SpeechBackend.HOSTED) }

    private var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val revokes = mutableListOf<String>()
    private val prefs = PrefsStore(MemoryDataStore())
    private val keyring by lazy { KeyringStore(File(tmp.root, "nobackup/${KeyringStore.FILE_NAME}").apply { parentFile!!.mkdirs() }, SoftwareKey()) }
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
        val settings = SettingsViewModel(prefs, keyring, FakeCheck(), onForget = { forgets += Unit }).also { store.put("settings", it) }
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

    private companion object {
        const val INFERENCE = "sk-inference-key"
        const val SPEECH = "sk-speech-key"
    }
}
