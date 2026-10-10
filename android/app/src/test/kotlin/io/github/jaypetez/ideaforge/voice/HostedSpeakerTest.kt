package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withTts
import io.github.jaypetez.ideaforge.data.Prefs
import io.github.jaypetez.ideaforge.providers.ProviderErrorCode
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every request; [fail] makes the next ones throw; [hold] makes them wait. */
class FakeSynth : SpeechSynth {
    val asked = mutableListOf<String>()
    var fail: Exception? = null
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun synthesize(text: String, rate: Double): ByteArray {
        asked += text
        hold?.await()
        fail?.let { throw it }
        return pcmWav(ByteArray(3200), 16_000)
    }
}

class FakePlayer : WavPlayer {
    val played = mutableListOf<ByteArray>()
    var stops = 0
    override suspend fun play(wav: ByteArray) {
        wavPcm(wav) // what the real player does first: refuse anything unplayable
        played += wav
    }
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HostedSpeakerTest {
    private val synth = FakeSynth()
    private val player = FakePlayer()
    private val focus = FakeFocus()
    private val native = FakeSpeaker()
    private var open = true

    private val speaker = HostedSpeaker(native, player, focus, prefs = { Prefs() }, synth = { if (open) synth else null })

    @Test
    fun aClosedGateSpeaksWithTheDeviceVoiceAndNeverCallsOpenAi() = runTest {
        open = false
        val r = speaker.speak("What is the idea?")
        assertEquals(SpeakStatus.SPOKEN, r.status)
        assertEquals(SpeechBackend.NATIVE, r.backend)
        assertEquals(listOf("What is the idea?"), native.said)
        assertTrue("no hosted request without the gate", synth.asked.isEmpty())
    }

    @Test
    fun anOpenGateSpeaksThroughOpenAiInChunksWithFocusHeld() = runTest {
        val long = "word ".repeat(150).trim() // > 350 characters: never one whole request
        val r = speaker.speak(long)
        assertEquals(SpeakStatus.SPOKEN, r.status)
        assertEquals(SpeechBackend.HOSTED, r.backend)
        assertTrue("split into the web's chunks", synth.asked.size > 1 && synth.asked.all { it.length <= 350 })
        assertEquals(synth.asked.size, player.played.size)
        assertTrue(native.said.isEmpty())
        assertEquals("focus released", focus.requests, focus.abandons)
    }

    // The question and its suggestions are two utterances, two requests, never one string.
    @Test
    fun theQuestionAndItsSuggestionsAreTwoHostedUtterances() = runTest {
        val q = speaker.speakQuestion("What should it cost?", "For example: free, or a small fee.")
        assertEquals(SpeakStatus.SPOKEN, q.examples?.status)
        assertEquals(listOf("What should it cost?", "For example: free, or a small fee."), synth.asked)
    }

    // Acceptance: a live failure falls back visibly to the device voice.
    @Test
    fun aLiveFailureFallsBackVisiblyAndStaysThereUntilANewSession() = runTest {
        synth.fail = ProviderException(ProviderErrorCode.NETWORK, "OpenAI speech could not be reached.")
        val r = speaker.speak("What is the idea?")
        assertEquals(SpeakStatus.SPOKEN, r.status)
        assertEquals(SpeechBackend.NATIVE, r.backend)
        assertNotNull("the fallback is reported", r.fallback)
        assertTrue(r.fallback!!, r.fallback!!.contains("could not be reached") && r.fallback!!.contains("device voice"))
        assertEquals("the device voice said it instead", listOf("What is the idea?"), native.said)

        synth.fail = null
        speaker.speak("Next question?")
        assertEquals("downgraded for the rest of the drive: no second paid attempt", 1, synth.asked.size)
        assertEquals(2, native.said.size)

        speaker.newSession()
        assertEquals(SpeechBackend.HOSTED, speaker.speak("A new drive.").backend)
    }

    // Acceptance: cancellation does not fall back. Pause must silence everything.
    @Test
    fun cancellingNeverFallsBackToTheDeviceVoice() = runTest {
        synth.hold = CompletableDeferred()
        val speaking = async { speaker.speak("What is the idea?") }
        runCurrent()
        assertEquals(1, synth.asked.size)
        speaker.cancel()
        val r = speaking.await()
        assertEquals(SpeakStatus.CANCELLED, r.status)
        assertNull("a cancellation is not a fallback", r.fallback)
        assertTrue("the device voice never started", native.said.isEmpty())
        assertNull(speaker.downgraded)
        assertEquals(focus.requests, focus.abandons)
    }

    @Test
    fun losingFocusIsAnInterruptionNotAFallback() = runTest {
        synth.hold = CompletableDeferred()
        val speaking = async { speaker.speak("What is the idea?") }
        runCurrent()
        focus.lose()
        val r = speaking.await()
        assertEquals(SpeakFailure.INTERRUPTED, r.failure)
        assertTrue(native.said.isEmpty())
        assertNull(r.fallback)
    }

    // Acceptance: a fallback never passes the check. The device voice would speak happily; the
    // check must not ask it to, and must report the hosted failure.
    @Test
    fun theCheckUsesOpenAiOnlySoAFallbackCanNeverPass() = runTest {
        open = false // the gate is irrelevant to the check: it vouches for the pair it is given
        val failing = FakeSynth().apply { fail = ProviderException(ProviderErrorCode.AUTH, "OpenAI speech 401") }
        val r = speaker.check(failing, 1.0)
        assertEquals(SpeakStatus.FAILED, r.status)
        assertEquals(SpeechBackend.HOSTED, r.backend)
        assertNull(r.fallback)
        assertTrue("the device voice never spoke the sample", native.said.isEmpty())
        assertNull("a failed check does not downgrade live speech", speaker.downgraded)

        val ok = speaker.check(synth, 1.0)
        assertEquals(SpeakStatus.SPOKEN, ok.status)
        assertEquals(SpeechBackend.HOSTED, ok.backend)
        assertEquals(listOf(SPEECH_CHECK_TEXT), synth.asked)
    }

    @Test
    fun audioThatCannotBePlayedFallsBackRatherThanPlayingNoise() = runTest {
        val garbage = SpeechSynth { _, _ -> ByteArray(100) }
        val s = HostedSpeaker(native, player, focus, prefs = { Prefs() }, synth = { garbage })
        val r = s.speak("Hello.")
        assertNotNull(r.fallback)
        assertEquals(listOf("Hello."), native.said)
        advanceUntilIdle()
    }

    // The gate: consent, the passing check, and a key. Each one missing closes it.
    @Test
    fun hostedSpeechNeedsConsentAndAPassingCheck() {
        fun ring(kind: String = "openai", key: String = "sk-speech", verified: Boolean = true) =
            withTts(emptyKeyring(), JsonObject(mapOf(
                "kind" to JsonPrimitive(kind), "apiKey" to JsonPrimitive(key),
                "voice" to JsonPrimitive("marin"), "verified" to JsonPrimitive(verified),
            )))
        val allowed = Prefs(hostedSpeechAllowed = true)
        assertTrue(hostedSpeechReady(ring(), allowed))
        assertFalse("no consent", hostedSpeechReady(ring(), Prefs()))
        assertFalse("no passing check", hostedSpeechReady(ring(verified = false), allowed))
        assertFalse("not chosen", hostedSpeechReady(ring(kind = "browser"), allowed))
        assertFalse("no key", hostedSpeechReady(ring(key = ""), allowed))
        assertFalse("an inference key is not a speech key", hostedSpeechReady(
            withCreds(emptyKeyring(), "openai", JsonObject(mapOf("apiKey" to JsonPrimitive("sk-inference")))), allowed,
        ))
        assertNull(hostedSynth(ring(verified = false), allowed))
    }

    @Test
    fun wavPcmFindsTheSamples() {
        val pcm = wavPcm(pcmWav(ByteArray(32_000), 16_000))
        assertEquals(44, pcm.offset)
        assertEquals(16_000, pcm.frames)
        assertEquals(1000L, pcm.durationMs)
    }
    // Review #93: Forget or a revoked consent mid-utterance stops the remaining chunks, and the
    // deleted key signs nothing more. No fallback either: it is a stop, not a failure.
    @Test
    fun aGateClosedMidUtteranceStopsTheRestWithoutFallingBack() = runTest {
        synth.hold = CompletableDeferred()
        val speaking = async { speaker.speak("word ".repeat(150).trim()) }
        runCurrent()
        open = false // Forget ran while the first chunk was being synthesized
        synth.hold!!.complete(Unit)
        val r = speaking.await()
        assertEquals(SpeakStatus.CANCELLED, r.status)
        assertEquals("only the chunk already requested", 1, synth.asked.size)
        assertNull(r.fallback)
        assertTrue(native.said.isEmpty())
    }

}
