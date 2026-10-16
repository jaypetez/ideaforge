package io.github.jaypetez.ideaforge.ui.settings

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.jaypetez.ideaforge.FakeCheck
import io.github.jaypetez.ideaforge.core.credsFor
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.js.stringOf
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withTts
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.data.KeyringKey
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.ui.onboarding.ProviderCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.jaypetez.ideaforge.data.KeyringStore
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.data.PrefsStore
import io.github.jaypetez.ideaforge.data.SoftwareKey
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SettingsViewModel against a real PrefsStore (DataStore on disk) and a real KeyringStore
 * (AES-GCM under a software key). "Process death" is the ViewModel cleared and the DataStore
 * shut down, then both opened again over the same files.
 *
 * One preference write per prefs file: DataStore replaces its file by rename, which a Windows
 * JVM refuses over an existing file (see PrefsStoreTest). Android does not.
 */
/**
 * Preferences in memory, for tests that need more than one write: a Windows JVM refuses the
 * second write to a real DataStore file (see the class comment below).
 */
class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

/** A software key whose deletions can fail, or wait, on demand. */
class FlakyKey(val inner: SoftwareKey = SoftwareKey()) : KeyringKey by inner {
    var failDelete: Exception? = null
    var holdDelete: CountDownLatch? = null

    override fun delete(slot: Int) {
        holdDelete?.await(10, TimeUnit.SECONDS)
        failDelete?.let { throw it }
        inner.delete(slot)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    /** One "process": a prefs DataStore, a keyring, and the ViewModel over them. */
    private inner class Process(
        val prefsFile: File,
        val key: KeyringKey = SoftwareKey(),
        val check: FakeCheck = FakeCheck(),
        val built: MutableList<ProviderConfig> = mutableListOf(),
        checker: ProviderCheck = check,
        /** Null: a real DataStore on [prefsFile]. */
        memoryPrefs: Preferences? = null,
    ) {
        private val job = SupervisorJob()
        val prefs = if (memoryPrefs != null) PrefsStore(MemoryDataStore(memoryPrefs))
            else PrefsStore.create(CoroutineScope(job + Dispatchers.IO)) { prefsFile }
        val keyring = KeyringStore(File(tmp.root, "nobackup/${KeyringStore.FILE_NAME}").apply { parentFile!!.mkdirs() }, key)
        private val store = ViewModelStore()
        private val inference = io.github.jaypetez.ideaforge.data.InferenceConsent(
            prefs, { keyring.state.value.ring }, CoroutineScope(job + Dispatchers.IO))
        val vm = SettingsViewModel(prefs, keyring, checker, inference, buildLocal = { built += it }).also { store.put("settings", it) }

        fun loaded(): SettingsState = runBlocking {
            withTimeout(10_000) { vm.state.first { it.prefs != null && it.editor != null } }
        }

        fun agreeAndSave(): Job? {
            vm.setAgreement(true)
            return vm.save()
        }

        fun join(job: Job?) = runBlocking { withTimeout(10_000) { job?.join() } }

        /** The state once it satisfies [ok]: it is combined asynchronously. */
        fun settle(ok: (SettingsState) -> Boolean = { !it.checking }): SettingsState = runBlocking {
            withTimeout(10_000) { vm.state.first(ok) }
        }

        fun kill() = runBlocking {
            val viewModelJob = vm.viewModelScope.coroutineContext[Job]
            store.clear()
            viewModelJob?.join()
            job.cancelAndJoin()
        }
    }

    private fun prefsFile(name: String) = File(tmp.root, "$name.preferences_pb")

    private fun <K : KeyringKey> seed(ring: JsonObject, key: K): K {
        val p = Process(prefsFile("seed"), key, memoryPrefs = emptyPreferences())
        runBlocking { p.keyring.save(ring) }
        p.kill()
        return key
    }

    private fun seed(ring: JsonObject): SoftwareKey = seed(ring, SoftwareKey())

    private fun consenting() = mutablePreferencesOf(
        booleanPreferencesKey("hostedSpeechAllowed") to true,
        booleanPreferencesKey("hostedTranscriptionAllowed") to true,
    )

    @Test
    fun savingNeedsAnAffirmativeAgreementButCheckingNeverCreatesOne() {
        val ring = withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_legacy_without_agreement"))
        val key = seed(ring)
        val p = Process(prefsFile("missing-inference-agreement"), key, memoryPrefs = emptyPreferences())
        assertFalse(p.loaded().inferenceAgreed)
        p.join(p.vm.save())
        assertTrue(p.settle { it.failure != null }.failure!!.detail.contains("Agree"))
        assertTrue(p.check.calls.isEmpty())
        assertEquals(ring, p.keyring.state.value.ring)
        p.join(p.vm.check())
        assertEquals(1, p.check.calls.size)
        assertEquals("", runBlocking { p.prefs.inferenceCurrent() }.inferenceGrant)
        assertFalse(p.settle().inferenceAgreed)
        p.kill()
    }

    // Acceptance: every preference persists across process death. Each is set through the
    // ViewModel, the process dies, and a new one reads it back from the same file.
    @Test
    fun everyPreferencePersistsAcrossProcessDeath() {
        val cases: List<Triple<String, (SettingsViewModel) -> Job?, (Prefs) -> Any?>> = listOf(
            Triple("handsFree", { vm -> vm.setHandsFree(true) }, { p -> p.handsFree }),
            Triple("trigger", { vm -> vm.setTriggerDraft("  Bananas! "); vm.commitTrigger() }, { p -> p.trigger }),
            Triple("theme", { vm -> vm.setTheme(Prefs.THEME_DARK) }, { p -> p.theme }),
            Triple("speechRate", { vm -> vm.setSpeechRate(1.2) }, { p -> p.speechRate }),
        )
        val expected = mapOf<String, Any?>("handsFree" to true, "trigger" to "bananas", "theme" to "dark", "speechRate" to 1.2)
        for ((name, set, read) in cases) {
            val file = prefsFile(name)
            val first = Process(file)
            assertEquals("$name starts at the default", read(Prefs()), read(first.loaded().prefs!!))
            first.join(set(first.vm))
            first.kill()

            val second = Process(file)
            assertEquals("$name after a restart", expected[name], read(second.loaded().prefs!!))
            second.kill()
        }
    }

    // The trigger is stored as the web stores it: normalised, and only when it changed.
    @Test
    fun aTriggerIsNormalisedOnCommitNotPerKeystroke() {
        val p = Process(prefsFile("trigger"))
        p.loaded()
        p.vm.setTriggerDraft("two wor")
        assertEquals("the draft is shown as typed, not cleaned mid-word", "two wor", p.settle { it.triggerDraft != null }.trigger)
        p.vm.setTriggerDraft("Over")
        p.join(p.vm.commitTrigger())
        assertEquals("over", p.settle { it.triggerDraft == null }.trigger)
        assertEquals("committing the word already in effect writes nothing", "", runBlocking { p.prefs.current().trigger })
        p.kill()
    }

    // Mirrors the speech vectors' triggerWarning cases, through the state the screen renders.
    @Test
    fun theTriggerWarningIsLiveForRiskyWordsAndAbsentForSafeOnes() {
        val p = Process(prefsFile("warning"))
        p.loaded()
        assertNull("the default word is safe", p.vm.state.value.triggerWarning)
        val risky = mapOf(
            "the" to "“the” is common enough that you will say it mid-answer and be cut off. Something you rarely say works better.",
            "a" to "A single letter is too easy to mishear. Try a whole word.",
            "Yes" to "“yes” is common enough that you will say it mid-answer and be cut off. Something you rarely say works better.",
            "and" to "“and” is common enough that you will say it mid-answer and be cut off. Something you rarely say works better.",
        )
        for ((word, warning) in risky) {
            p.vm.setTriggerDraft(word)
            assertEquals(word, warning, p.settle { it.triggerDraft == word }.triggerWarning)
        }
        for (word in listOf("over", "done", "skip", "repeat", "finished", "stop", "thanks", "bananas")) {
            p.vm.setTriggerDraft(word)
            assertNull(word, p.settle { it.triggerDraft == word }.triggerWarning)
        }
        p.kill()
    }

    // Acceptance: Forget all removes the keyring file and both Keystore aliases, and the
    // screen is told to route to onboarding. Preferences other than the speech consent stay.
    @Test
    fun forgetAllRemovesTheKeyringAndBothAliases() {
        val ring = withCreds(withCreds(emptyKeyring(), "anthropic", obj("apiKey" to "sk-ant-forget-me-000001")), "groq", obj("apiKey" to "gsk_forget_me_00000001"))
        val key = seed(ring)
        // A key left in the second slot by an earlier replacement must go too.
        key.keys[1] = SoftwareKey.fresh()
        val p = Process(prefsFile("forget"), key, memoryPrefs = consenting())
        val before = p.loaded()
        assertTrue(before.prefs!!.hostedSpeechAllowed)
        assertTrue(before.canForget)
        assertFalse(before.needsOnboarding)
        assertTrue(p.keyring.file.exists())

        p.vm.askForget()
        assertTrue(p.settle { it.confirmingForget }.confirmingForget)
        p.join(p.vm.forget())

        assertFalse("the keyring file is gone", p.keyring.file.exists())
        assertNull("slot 0's key is gone", key.keys[0])
        assertNull("slot 1's key is gone", key.keys[1])
        val after = p.settle { it.forgotten && it.keyring.status == KeyringState.Status.NONE && !it.checking }
        assertTrue("no usable provider remains, so the screen routes to onboarding", after.needsOnboarding)
        assertFalse(after.canForget)
        assertFalse("the hosted speech consent went with the keys", runBlocking { p.prefs.current() }.hostedSpeechAllowed)
        p.kill()
    }

    // The web's forgetKey withdraws the consent first, whatever happens next: a Forget that
    // fails halfway (here the Keystore refuses to delete an alias) must not leave hosted
    // speech permitted. Nor may it claim the keys are gone and send the user to onboarding.
    @Test
    fun aFailedForgetStillWithdrawsTheSpeechConsent() {
        val key = seed(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_forget_me_00000001")), FlakyKey())
        key.failDelete = java.security.KeyStoreException("keystore busy")
        val p = Process(prefsFile("forget-fails"), key, memoryPrefs = consenting())
        p.loaded()
        p.join(p.vm.forget())
        val after = p.settle()
        assertNotNull("the failure is reported", after.failure)
        assertFalse("not reported as forgotten", after.forgotten)
        assertFalse(after.needsOnboarding)
        assertFalse("the consent was withdrawn before the keyring was touched", runBlocking { p.prefs.current() }.hostedSpeechAllowed)
        // #93: the consent to send recorded audio goes with it, for the same reason.
        assertFalse("the audio-transfer consent was withdrawn too", runBlocking { p.prefs.current() }.hostedTranscriptionAllowed)
        p.kill()
    }

    // Forget/save ordering is the ViewModel's, not only the screen's: while Forget runs, Save,
    // Check and Replace refuse to start, so the key being deleted cannot be written back.
    @Test
    fun aSaveStartedDuringForgetWritesNothing() {
        val key = seed(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_pre_forget_000000001")), FlakyKey())
        val hold = CountDownLatch(1)
        key.holdDelete = hold
        val p = Process(prefsFile("forget-race"), key, memoryPrefs = emptyPreferences())
        p.loaded()
        val forgetting = p.vm.forget()
        assertNull("save refused while Forget runs", p.vm.save())
        assertNull("check refused while Forget runs", p.vm.check())
        assertNull("replace refused while Forget runs", p.vm.replaceUnreadable())
        hold.countDown()
        p.join(forgetting)
        assertFalse("nothing wrote the keyring back", p.keyring.file.exists())
        assertTrue(p.check.calls.isEmpty())
        assertTrue(p.settle { it.forgotten }.needsOnboarding)
        p.kill()
    }

    // A save already in flight when Forget is confirmed is cancelled and waited for, so it
    // cannot land after the keys are gone.
    @Test
    fun aSaveInFlightWhenForgetStartsDoesNotLandAfterIt() {
        seed(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_pre_forget_000000001")))
        val gate = CompletableDeferred<Unit>()
        val slow = ProviderCheck { _, _ -> gate.await(); emptyList() }
        val p = Process(prefsFile("forget-inflight"), SoftwareKey(), checker = slow, memoryPrefs = emptyPreferences())
        // The seed's key is not this process's, so give it the seed's ring under its own key.
        runBlocking { p.keyring.save(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_pre_forget_000000001"))) }
        p.loaded()
        p.vm.setApiKey("gsk_typed_before_forget_01")
        val saving = p.agreeAndSave()!!
        p.join(p.vm.forget())
        gate.complete(Unit)
        p.join(saving)
        assertFalse("the in-flight save never wrote", p.keyring.file.exists())
        assertEquals(KeyringState.Status.NONE, p.keyring.state.value.status)
        p.kill()
    }

    // replaceUnreadable's confirmation is for that one save. A confirmed save whose check then
    // fails must not leave it armed: the next Save, for any provider, has to ask again rather
    // than overwrite the unreadable keyring.
    @Test
    fun aFailedReplaceDoesNotArmTheNextSave() {
        // A real unreadable keyring: sealed under one key, opened under another.
        seed(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_unreadable_00000001")))
        var refuse = false
        val check = FakeCheck { _, _ -> if (refuse) throw ProviderException("network", "offline") else emptyList() }
        val p = Process(prefsFile("unreadable"), SoftwareKey(), check, memoryPrefs = emptyPreferences())
        val start = p.loaded()
        assertEquals(KeyringState.Status.UNREADABLE, start.keyring.status)
        val blob = p.keyring.file.readBytes()

        p.vm.pickProvider("groq")
        p.vm.setApiKey("gsk_new_key_0000000001")
        p.join(p.agreeAndSave())
        assertNotNull("saving asks first", p.settle { it.unreadable != null }.unreadable)

        refuse = true
        p.join(p.vm.replaceUnreadable())
        assertNotNull("the confirmed save failed its check", p.settle().failure)

        refuse = false
        p.vm.pickProvider("openai")
        p.vm.setApiKey("sk-proj-other-provider-001")
        p.join(p.agreeAndSave())
        assertNotNull("the next save asks again", p.settle { !it.checking }.unreadable)
        assertTrue("nothing was written", blob.contentEquals(p.keyring.file.readBytes()))
        assertEquals(KeyringState.Status.UNREADABLE, p.keyring.state.value.status)
        p.kill()
    }

    @Test
    fun cancellingForgetKeepsEverything() {
        val key = seed(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_keep_me_000000001")))
        val p = Process(prefsFile("cancel"), key)
        p.loaded()
        p.vm.askForget()
        p.vm.cancelForget()
        assertFalse(p.settle { !it.confirmingForget }.confirmingForget)
        assertTrue(p.keyring.file.exists())
        assertNotNull(key.keys[0])
        p.kill()
    }

    // Changing one provider's key validates the new key, saves it, and leaves every other
    // record (another provider, a local server, the speech key) exactly as it was.
    @Test
    fun changingAKeyPreservesTheOtherProvidersRecords() {
        val ollama = obj("baseUrl" to "http://10.0.2.2:11434/v1", "model" to "qwen2.5:7b-instruct")
        var ring = withCreds(emptyKeyring(), "ollama", ollama)
        ring = withCreds(ring, "anthropic", obj("apiKey" to "sk-ant-other-provider-01", "wrapModel" to "claude-wrap"))
        ring = withTts(ring, obj("kind" to "openai", "apiKey" to "sk-tts-keep-0000000001", "voice" to "cedar"))
        ring = withCreds(ring, "groq", obj("apiKey" to "gsk_old_key_0000000001", "model" to "llama-q"))
        val key = seed(ring)
        val p = Process(prefsFile("change"), key)
        val s = p.loaded()
        assertEquals("groq", s.editor!!.choice.id)
        assertEquals("gsk_old_key_0000000001", s.editor!!.savedKey)

        p.vm.setApiKey("  gsk_new_key_0000000002\n")
        p.join(p.agreeAndSave())
        assertNull(p.settle().failure)

        assertEquals("the new key was checked before it was saved", "gsk_new_key_0000000002", p.check.calls.single().second.apiKey)
        p.kill()

        // Read back from disk, as the next launch would.
        val reread = Process(prefsFile("change2"), key)
        val saved = reread.loaded().ring
        assertEquals("gsk_new_key_0000000002", stringOf(credsFor(saved, "groq")["apiKey"]))
        assertEquals("the questions model survives a key change", "llama-q", stringOf(credsFor(saved, "groq")["model"]))
        assertEquals("sk-ant-other-provider-01", stringOf(credsFor(saved, "anthropic")["apiKey"]))
        assertEquals("claude-wrap", stringOf(credsFor(saved, "anthropic")["wrapModel"]))
        assertEquals("http://10.0.2.2:11434/v1", stringOf(credsFor(saved, "ollama")["baseUrl"]))
        assertEquals("qwen2.5:7b-instruct", stringOf(credsFor(saved, "ollama")["model"]))
        assertEquals("sk-tts-keep-0000000001", stringOf((saved["tts"] as JsonObject)["apiKey"]))
        assertEquals("groq", stringOf(saved["active"]))
        reread.kill()
    }

    // A failed check saves nothing.
    @Test
    fun aRefusedKeyIsNotSaved() {
        val key = seed(withCreds(emptyKeyring(), "groq", obj("apiKey" to "gsk_old_key_0000000001")))
        val check = FakeCheck { _, _ -> throw io.github.jaypetez.ideaforge.core.runtime.ProviderException("auth", "api.groq.com 401: invalid key") }
        val p = Process(prefsFile("refused"), key, check)
        p.loaded()
        p.vm.setApiKey("gsk_typo")
        p.join(p.agreeAndSave())
        assertNotNull(p.settle().failure)
        assertEquals("gsk_old_key_0000000001", stringOf(credsFor(p.keyring.state.value.ring, "groq")["apiKey"]))
        p.kill()
    }

    // The web's hidden-field bug: a hosted OpenAI-compatible preset has a default address,
    // and reading it back sent https://api.openai.com/v1 to the loopback check. Neither the
    // check nor the saved record may carry an address for a hosted provider.
    @Test
    fun aHostedProviderNeverSavesOrSendsAnAddress() {
        val p = Process(prefsFile("hosted"))
        p.loaded()
        p.vm.pickProvider("openai")
        assertTrue("the preset's address sits in the form", p.settle { it.editor?.choice?.id == "openai" }.editor!!.baseUrl.isNotEmpty())
        p.vm.setApiKey("sk-proj-new-key-000000001")
        p.vm.setModel("gpt-q")
        p.vm.setWrapModel("gpt-wrap")
        p.join(p.agreeAndSave())
        assertNull(p.settle().failure)
        assertNull("the check got no address", p.check.calls.single().second.baseUrl)
        val rec = credsFor(p.keyring.state.value.ring, "openai")
        assertEquals("", stringOf(rec["baseUrl"]))
        assertEquals("gpt-q", stringOf(rec["model"]))
        assertEquals("gpt-wrap", stringOf(rec["wrapModel"]))
        p.kill()
    }

    // A local server is built (not key-checked) and saved with its address and models; it
    // never gains a key, and needs a model before it can be saved at all.
    @Test
    fun aLocalServerSavesItsAddressAndModelsAndNoKey() {
        val p = Process(prefsFile("local"))
        p.loaded()
        p.vm.pickProvider("ollama")
        p.vm.setBaseUrl("http://10.0.2.2:11434/v1")
        assertNull("no model, no save", p.vm.save())
        assertNotNull(p.settle { it.failure != null }.failure)
        p.vm.setModel("qwen2.5:7b-instruct")
        p.join(p.agreeAndSave())
        assertNull(p.settle().failure)
        assertTrue("a local save makes no network call", p.check.calls.isEmpty())
        assertEquals("http://10.0.2.2:11434/v1", p.built.single().baseUrl)
        val rec = credsFor(p.keyring.state.value.ring, "ollama")
        assertEquals("", stringOf(rec["apiKey"]))
        assertEquals("http://10.0.2.2:11434/v1", stringOf(rec["baseUrl"]))
        assertEquals("qwen2.5:7b-instruct", stringOf(rec["model"]))
        assertEquals("ollama", stringOf(p.keyring.state.value.ring["active"]))
        p.kill()
    }

    // Check on a local server reads its model list into the suggestions; one model is chosen.
    @Test
    fun checkingALocalServerListsItsModels() {
        val check = FakeCheck { _, _ -> listOf("qwen2.5:7b-instruct") }
        val p = Process(prefsFile("discover"), check = check)
        p.loaded()
        p.vm.pickProvider("ollama")
        p.join(p.vm.check())
        val e = p.settle { !it.checking && it.notice != null }.editor!!
        assertEquals(listOf("qwen2.5:7b-instruct"), e.models)
        assertEquals("qwen2.5:7b-instruct", e.model)
        assertEquals("", check.calls.single().second.apiKey)
        p.kill()
    }
}
