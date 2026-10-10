package io.github.jaypetez.ideaforge.ui.onboarding

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.data.KeyringState
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
import io.github.jaypetez.ideaforge.ui.LibraryRoute
import io.github.jaypetez.ideaforge.ui.OnboardingRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartDestinationTest {
    private fun rec(vararg f: Pair<String, String>) = JsonObject(f.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun nothingIsDecidedUntilTheKeyringHasBeenRead() {
        assertEquals(StartDecision.Loading, startDecisionFor(KeyringState(withCreds(emptyKeyring(), "groq", rec("apiKey" to "k")), KeyringState.Status.NOT_LOADED)))
    }

    @Test
    fun anEmptyKeyringOpensOnboarding() {
        assertEquals(StartDecision.Go(OnboardingRoute), startDecisionFor(KeyringState(emptyKeyring(), KeyringState.Status.NONE)))
    }

    // Unreadable is not "no keys": the blob is kept for a Keystore that may answer next time,
    // and latching onboarding here is how a save then overwrote it.
    @Test
    fun anUnreadableKeyringIsReportedNotTakenAsEmpty() {
        assertEquals(
            StartDecision.Unreadable("lost key"),
            startDecisionFor(KeyringState(emptyKeyring(), KeyringState.Status.UNREADABLE, "lost key")),
        )
    }

    @Test
    fun aHostedProviderWithAKeyOpensTheLibrary() {
        val ring = withCreds(emptyKeyring(), "anthropic", rec("apiKey" to "sk-ant-x"))
        assertEquals(StartDecision.Go(LibraryRoute), startDecisionFor(KeyringState(ring, KeyringState.Status.STORED)))
    }

    @Test
    fun aHostedProviderWithoutAKeyIsNotUsable() {
        assertFalse(hasUsableProvider(withCreds(emptyKeyring(), "openai", rec("model" to "gpt-5-nano"))))
    }

    // A dictation key is a secret (hasAnyKey says yes) but it cannot run an interview.
    @Test
    fun aDictationKeyAloneIsNotAnInterviewProvider() {
        val ring = withStt(emptyKeyring(), rec("kind" to "groq", "apiKey" to "gsk_stt"))
        assertFalse(hasUsableProvider(ring))
    }

    @Test
    fun aLocalProviderNeedsAModelAndNoKey() {
        assertFalse(hasUsableProvider(withCreds(emptyKeyring(), "ollama", rec())))
        assertTrue(hasUsableProvider(withCreds(emptyKeyring(), "ollama", rec("model" to "qwen2.5:7b-instruct"))))
        // A custom server has no default address to fall back on.
        assertFalse(hasUsableProvider(withCreds(emptyKeyring(), "custom", rec("model" to "m"))))
        assertTrue(hasUsableProvider(withCreds(emptyKeyring(), "custom", rec("model" to "m", "baseUrl" to "http://192.168.1.20:8080/v1"))))
    }

    // Retired or unknown providers (a keyring from a build that offered GitHub Models) are not usable.
    @Test
    fun anActiveProviderThisBuildDoesNotOfferIsNotUsable() {
        assertFalse(hasUsableProvider(withCreds(emptyKeyring(), "github", rec("apiKey" to "ghp_x"))))
    }

    @Test
    fun aRefusedKeyLeadsWithTheKeyProblemAndKeepsTheProvidersWords() {
        val f = checkFailure(ProviderException(ProviderErrorCode.AUTH, "api.openai.com 401: Incorrect API key — check the API key in Settings", status = 401))
        assertTrue(f.lead.startsWith("That key was refused"))
        assertEquals("api.openai.com 401: Incorrect API key — check the API key in Settings", f.detail)
        assertTrue(checkFailure(ProviderException(ProviderErrorCode.NETWORK, "x")).lead.startsWith("Could not reach"))
    }

    @Test
    fun theEmulatorAddressIsOfferedOnlyForLoopback() {
        assertEquals("http://10.0.2.2:11434/v1", emulatorAddress("http://localhost:11434/v1"))
        assertEquals("http://10.0.2.2:1234/v1", emulatorAddress("http://127.0.0.1:1234/v1"))
        assertNull(emulatorAddress("http://192.168.1.20:11434/v1"))
        assertNull(emulatorAddress("not a url"))
    }
}
