package io.github.jaypetez.ideaforge.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.providers.ProviderConfig
import io.github.jaypetez.ideaforge.providers.inferenceDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class ConsentDataStore : DataStore<Preferences> {
    private val value = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    var failRead = false
    var holdRead: CompletableDeferred<Unit>? = null
    var failWrite: Exception? = null
    var holdWrite: CompletableDeferred<Unit>? = null
    override val data: Flow<Preferences> get() = flow {
        if (failRead) throw IOException("scripted unreadable preferences")
        holdRead?.await()
        emitAll(value)
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
        failWrite?.let { throw it }
        val next = transform(value.value)
        holdWrite?.await()
        value.value = next
        next
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InferenceConsentTest {
    private suspend fun denied(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("missing inference consent was accepted")
        } catch (error: ProviderException) {
            assertEquals("consent", error.code)
        }
    }

    @Test
    fun defaultAndLegacyPreferencesDoNotAuthorize() = runTest {
        val prefs = PrefsStore(ConsentDataStore())
        prefs.update { it.copy(handsFree = true, hostedSpeechAllowed = true, hostedTranscriptionAllowed = true) }
        val consent = InferenceConsent(prefs, { testInferenceRing() }, backgroundScope)
        denied { consent.require() }
        assertEquals("", prefs.current().inferenceGrant)
    }

    @Test
    fun anAffirmativeGrantSurvivesRestartButNeverChangesVoicePermissions() = runTest {
        val prefs = PrefsStore(ConsentDataStore())
        prefs.update { it.copy(hostedSpeechAllowed = true, hostedTranscriptionAllowed = true, trigger = "finished") }
        val destination = inferenceDestination(inferenceConfig(testInferenceRing()))
        val first = InferenceConsent(prefs, { testInferenceRing() }, backgroundScope).accept(destination)
        val restarted = InferenceConsent(prefs, { testInferenceRing() }, backgroundScope)
        assertEquals(first.grant, restarted.require().grant)
        assertTrue(prefs.current().hostedSpeechAllowed)
        assertTrue(prefs.current().hostedTranscriptionAllowed)
        assertEquals("finished", prefs.current().trigger)
        restarted.withdraw().join()
        denied { InferenceConsent(prefs, { testInferenceRing() }, backgroundScope).require() }
    }

    @Test
    fun providerSwitchBackRequiresFreshConsentAndRetiresOldPermits() = runTest {
        var ring = testInferenceRing()
        var grants = 0
        val consent = InferenceConsent(PrefsStore(ConsentDataStore()), { ring }, backgroundScope,
            newGrant = { "grant-${++grants}" })
        val groq = inferenceDestination(inferenceConfig(ring))
        consent.select("groq", groq.id)
        val first = consent.accept(groq)
        ring = withCreds(ring, "openai", JsonObject(mapOf("apiKey" to JsonPrimitive("scripted"))))
        consent.select("openai", "openai")
        ring = testInferenceRing()
        consent.select("groq", "groq")
        denied { consent.require() }
        val second = consent.accept(groq)
        assertNotEquals(first.grant, second.grant)
        denied { consent.require(first) }
        assertEquals(second, consent.require(second))
    }

    @Test
    fun modelOnlyEditsKeepTheGrantButValidatedLocalAddressChangesDoNot() = runTest {
        fun local(base: String, model: String) = withCreds(emptyKeyring(), "ollama",
            JsonObject(mapOf("baseUrl" to JsonPrimitive(base), "model" to JsonPrimitive(model))))
        var ring = local("http://192.168.1.20:11434/v1/", "first")
        val consent = InferenceConsent(PrefsStore(ConsentDataStore()), { ring }, backgroundScope)
        val first = inferenceDestination(inferenceConfig(ring))
        consent.select("ollama", first.id)
        val permit = consent.accept(first)
        ring = local("http://192.168.1.20:11434/v1", "second")
        assertFalse(consent.select("ollama", inferenceDestination(inferenceConfig(ring)).id))
        assertEquals(permit, consent.require(permit))
        consent.select("ollama", null)
        assertEquals("invalid keystrokes do not invent a destination", permit, consent.require(permit))
        ring = local("http://192.168.1.21:11434/v1", "second")
        assertTrue(consent.select("ollama", inferenceDestination(inferenceConfig(ring)).id))
        denied { consent.require() }
    }

    @Test
    fun failedWritesAndReadsStayBlockedUntilAnotherAffirmativeAction() = runTest {
        val store = ConsentDataStore()
        val prefs = PrefsStore(store)
        val consent = InferenceConsent(prefs, { testInferenceRing() }, backgroundScope)
        val destination = inferenceDestination(ProviderConfig("groq"))
        store.failWrite = IOException("scripted full disk")
        denied { consent.accept(destination) }
        store.failWrite = null
        val permit = consent.accept(destination)
        store.failRead = true
        denied { consent.require(permit) }
        store.failRead = false
        denied { consent.require() }
        consent.accept(destination)
        store.failWrite = IOException("scripted full disk")
        consent.withdraw().join()
        denied { consent.require() }
        assertTrue(consent.state.value.problem!!.contains("could not be saved"))
    }

    @Test
    fun aDelayedAcceptanceCannotAuthorizeAChangedProvider() = runTest {
        val store = ConsentDataStore()
        val consent = InferenceConsent(PrefsStore(store), { testInferenceRing() }, backgroundScope)
        consent.select("groq", "groq")
        store.holdWrite = CompletableDeferred()
        val accepting = async {
            try {
                consent.accept(inferenceDestination(ProviderConfig("groq")))
                false
            } catch (error: ProviderException) {
                assertEquals("consent", error.code)
                true
            }
        }
        runCurrent()
        consent.select("openai", "openai")
        store.holdWrite!!.complete(Unit)
        assertTrue(accepting.await())
        denied { consent.require() }
    }

    @Test
    fun aQueuedWithdrawalCannotEraseANewerAffirmativeGrant() = runTest {
        var grants = 0
        val consent = InferenceConsent(PrefsStore(ConsentDataStore()), { testInferenceRing() }, backgroundScope,
            newGrant = { "grant-${++grants}" })
        val destination = inferenceDestination(ProviderConfig("groq"))
        val first = consent.accept(destination)
        consent.withdraw()
        val second = consent.accept(destination)
        runCurrent()
        assertNotEquals(first.grant, second.grant)
        assertEquals(second, consent.require(second))
        denied { consent.require(first) }
    }

    @Test
    fun cancellingAStrictReadDoesNotWithdrawTheUsersAgreement() = runTest {
        val store = ConsentDataStore()
        val consent = InferenceConsent(PrefsStore(store), { testInferenceRing() }, backgroundScope)
        val permit = consent.accept(inferenceDestination(ProviderConfig("groq")))
        store.holdRead = CompletableDeferred()
        val reading = launch { consent.require(permit) }
        runCurrent()
        reading.cancelAndJoin()
        store.holdRead = null
        assertFalse(consent.state.value.blocked)
        assertEquals(permit, consent.require(permit))
    }

    @Test
    fun deniedStorageWritesCannotGrantOrSilentlySaveAWithdrawal() = runTest {
        val store = ConsentDataStore()
        val consent = InferenceConsent(PrefsStore(store), { testInferenceRing() }, backgroundScope)
        val destination = inferenceDestination(ProviderConfig("groq"))
        store.failWrite = SecurityException("scripted storage permission denial")
        denied { consent.accept(destination) }
        assertTrue(consent.state.value.problem!!.contains("could not be saved"))
        store.failWrite = null
        consent.accept(destination)
        store.failWrite = SecurityException("scripted storage permission denial")
        consent.withdraw().join()
        denied { consent.require() }
        assertTrue(consent.state.value.problem!!.contains("could not be saved"))
    }
}
