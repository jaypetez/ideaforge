package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.speechChunksForRate
import io.github.jaypetez.ideaforge.core.speechTimeoutMs
import io.github.jaypetez.ideaforge.data.Prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Speaker against a scripted engine, on virtual time: no TextToSpeech, no audio, and a
 * hung chunk costs no wall-clock time.
 */
class FakeEngine : TtsEngine {
    /** true/false is the init verdict; null never answers. */
    var init: Boolean? = true
    /** Bumped by reset(): each value is a fresh platform engine. */
    var generation = 0
    var resets = 0
    /** Generations whose init never answers. */
    var initHangs = emptySet<Int>()
    /** Generations that refuse every speak, as a TextToSpeech whose service died does. */
    var deadGenerations = emptySet<Int>()
    var installed = listOf("en-us-x-iol-local", "en-gb-x-rjs-network")
    var details: List<DeviceVoice>? = null
    var initCalls = 0
    /** Chunk indexes the engine accepts and then never finishes (no onDone, ever). */
    var hangs = emptySet<Int>()
    var errors = emptyMap<Int, Int>()
    var accept = true

    val spoken = mutableListOf<String>()
    var rate: Float? = null
    var voice: String? = null
    var defaultVoiceUsed = 0
    var stops = 0
    private lateinit var listener: TtsEngine.Listener

    override fun setListener(listener: TtsEngine.Listener) { this.listener = listener }
    override suspend fun awaitReady(): Boolean {
        initCalls++
        return if (generation in initHangs) awaitCancellation() else init ?: awaitCancellation()
    }
    override fun voices() = installed
    override fun voiceDetails() = details ?: super.voiceDetails()
    override fun setVoice(id: String): Boolean { voice = id; return true }
    override fun useDefaultVoice() { voice = null; defaultVoiceUsed++ }
    override fun setRate(rate: Float) { this.rate = rate }

    override fun speak(text: String, utteranceId: String): Boolean {
        if (!accept || generation in deadGenerations) return false
        val i = spoken.size
        spoken += text
        listener.onStart(utteranceId)
        when {
            i in hangs -> Unit
            i in errors -> listener.onError(utteranceId, errors.getValue(i))
            else -> listener.onDone(utteranceId)
        }
        return true
    }

    override fun stop() { stops++ }
    override fun reset() { generation++; resets++ }
    override fun shutdown() = Unit
}

class FakeFocus(var grant: Boolean = true) : AudioFocus {
    var requests = 0
    var abandons = 0
    private var onLoss: (() -> Unit)? = null
    override fun request(onLoss: () -> Unit): Boolean {
        requests++
        if (grant) this.onLoss = onLoss
        return grant
    }
    override fun abandon() { abandons++; onLoss = null }
    /** Another app takes the audio: a call rings, navigation speaks. */
    fun lose() { onLoss?.invoke() }
}

private const val LONG = "The first sentence sets the scene for the whole idea and runs on a little. " +
    "A second sentence keeps going with more detail about who it is for and why they care. " +
    "The third and last one wraps it up with what success would look like in a month."

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerTest {
    private val engine = FakeEngine()
    private val focus = FakeFocus()
    private var prefs = Prefs()
    private val speaker = TextToSpeechSpeaker(engine, focus, prefs = { prefs })

    @Test
    fun voiceEnumerationIsBoundedReportsEngineFailuresAndNeverSpeaksOrChangesTheVoice() = runTest {
        engine.details = listOf(DeviceVoice("en-test", "en-GB", true))
        assertEquals(engine.details, engine.loadDeviceVoices())
        assertTrue(engine.spoken.isEmpty())
        assertNull(engine.voice)
        assertEquals(0, engine.defaultVoiceUsed)
        assertEquals(0, focus.requests)
        engine.init = false
        assertTrue(runCatching { engine.loadDeviceVoices() }.exceptionOrNull() is DeviceVoiceLoadException)
        engine.init = null
        val start = currentTime
        assertTrue(runCatching { engine.loadDeviceVoices() }.exceptionOrNull() is DeviceVoiceLoadException)
        assertEquals(TextToSpeechSpeaker.INIT_TIMEOUT_MS, currentTime - start)
        val refused = object : TtsEngine by engine {
            override suspend fun awaitReady() = true
            override fun voiceDetails(): List<DeviceVoice> = error("controlled service failure")
        }
        assertTrue("a failed read must not become an empty voice list",
            runCatching { refused.loadDeviceVoices() }.exceptionOrNull() is DeviceVoiceLoadException)
    }

    @Test
    fun speaksTheWebsChunksNeverTheWholeTextAtOnce() = runTest {
        val r = speaker.speak(LONG)
        assertEquals(SpeakStatus.SPOKEN, r.status)
        assertEquals(speechChunksForRate(LONG, 1.0), engine.spoken)
        assertTrue("one utterance for the whole passage", engine.spoken.size > 1)
        assertEquals(engine.spoken.size, r.chunksSpoken)
        assertEquals("a finished speak does not stop the engine afterwards", 0, engine.stops)
    }

    @Test
    fun aHungChunkResolvesThroughItsLengthScaledTimeoutAndIsReported() = runTest {
        engine.hangs = setOf(1)
        val chunks = speechChunksForRate(LONG, 1.0)
        val start = currentTime
        val r = speaker.speak(LONG)
        assertEquals(SpeakStatus.FAILED, r.status)
        assertEquals(SpeakFailure.TIMEOUT, r.failure)
        assertEquals(1, r.chunksSpoken)
        // Exactly the hung chunk's own budget: not a fixed wait, and not the whole passage's.
        assertEquals(speechTimeoutMs(chunks[1], 1.0), currentTime - start)
        assertEquals("nothing after the hung chunk is queued", 2, engine.spoken.size)
        assertTrue("the hung utterance is stopped", engine.stops >= 1)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun theTimeoutLengthensAtASlowerRate() = runTest {
        prefs = Prefs(speechRate = 0.75)
        engine.hangs = setOf(0)
        val first = speechChunksForRate(LONG, 0.75)[0]
        val start = currentTime
        assertEquals(SpeakFailure.TIMEOUT, speaker.speak(LONG).failure)
        assertEquals(speechTimeoutMs(first, 0.75), currentTime - start)
    }

    @Test
    fun theQuestionAndItsSuggestionsAreTwoSpeakCalls() = runTest {
        val calls = mutableListOf<String>()
        val spy = object : Speaker by speaker {
            override suspend fun speak(text: String): SpeakResult { calls += text; return speaker.speak(text) }
            override suspend fun speakQuestion(question: String, examples: String) =
                super.speakQuestion(question, examples)
        }
        val question = "Who is this for?"
        val examples = "For example: new parents, night-shift nurses, or anyone who forgets names."
        val out = spy.speakQuestion(question, examples)
        assertEquals(listOf(question, examples), calls)
        assertEquals(SpeakStatus.SPOKEN, out.question.status)
        assertEquals(SpeakStatus.SPOKEN, out.examples?.status)
        assertTrue("no utterance carries both", engine.spoken.none { "Who is" in it && "For example" in it })
    }

    @Test
    fun noSuggestionsMeansOneUtteranceAndAFailedQuestionSkipsThem() = runTest {
        assertNull(speaker.speakQuestion("Who is this for?", "  ").examples)
        assertEquals(1, engine.spoken.size)

        engine.errors = mapOf(1 to -3)
        val out = speaker.speakQuestion("Who is this for?", "For example: parents.")
        assertEquals(SpeakFailure.ENGINE_ERROR, out.question.failure)
        assertNull(out.examples)
        assertEquals(2, engine.spoken.size)
    }

    @Test
    fun aMissingSelectedVoiceIsReportedNotSilentlySwapped() = runTest {
        prefs = Prefs(speechVoice = "de-de-x-uninstalled")
        val r = speaker.speak("Hello there.")
        assertEquals(SpeakStatus.SPOKEN, r.status)
        assertTrue(r.voiceMissing)
        assertEquals("de-de-x-uninstalled", r.requestedVoice)
        assertEquals("", r.voice)
        assertTrue(r.message!!.contains("unavailable"))
        assertEquals(1, engine.defaultVoiceUsed)
    }

    @Test
    fun anInstalledSelectedVoiceIsUsed() = runTest {
        prefs = Prefs(speechVoice = "en-gb-x-rjs-network")
        val r = speaker.speak("Hello there.")
        assertFalse(r.voiceMissing)
        assertEquals("en-gb-x-rjs-network", r.voice)
        assertEquals("en-gb-x-rjs-network", engine.voice)
    }

    @Test
    fun cancelMidUtteranceStopsTheEngineAndResolvesCancelled() = runTest {
        engine.hangs = setOf(0)
        val speaking = async { speaker.speak(LONG) }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, engine.spoken.size)
        val stopsBefore = engine.stops
        speaker.cancel()
        val r = speaking.await()
        assertEquals(SpeakStatus.CANCELLED, r.status)
        assertNull("cancelling is not an error", r.failure)
        assertEquals(stopsBefore + 1, engine.stops)
        assertEquals(1, focus.abandons)
        assertTrue("resolved at once, not by the timeout", currentTime < speechTimeoutMs(engine.spoken[0], 1.0))
    }

    @Test
    fun anEngineThatFailsToInitialiseIsReported() = runTest {
        engine.init = false
        val r = speaker.speak("Hello there.")
        assertEquals(SpeakStatus.FAILED, r.status)
        assertEquals(SpeakFailure.ENGINE_INIT, r.failure)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, focus.requests)
    }

    @Test
    fun anEngineThatNeverInitialisesIsBoundedAndReported() = runTest {
        engine.init = null
        val start = currentTime
        val r = speaker.speak("Hello there.")
        assertEquals(SpeakFailure.ENGINE_INIT, r.failure)
        assertEquals(TextToSpeechSpeaker.INIT_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun theRateFromPrefsIsApplied() = runTest {
        prefs = Prefs(speechRate = 0.8)
        speaker.speak(LONG)
        assertEquals(0.8f, engine.rate)
        assertEquals("slower speech is cut into shorter chunks", speechChunksForRate(LONG, 0.8), engine.spoken)
    }

    @Test
    fun focusIsDuckableForOneSpeakAndARefusalSaysNothing() = runTest {
        speaker.speak("Hello there.")
        assertEquals(1, focus.requests)
        assertEquals(1, focus.abandons)

        focus.grant = false
        val r = speaker.speak("Hello again.")
        assertEquals(SpeakFailure.FOCUS_DENIED, r.failure)
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun aRefusedChunkAndEmptyTextAreReported() = runTest {
        assertEquals(SpeakFailure.CONFIG, speaker.speak("   ").failure)
        engine.accept = false
        assertEquals(SpeakFailure.SPEAK_REJECTED, speaker.speak("Hello.").failure)
    }

    @Test
    fun aNewSpeakCancelsTheOneInProgress() = runTest {
        engine.hangs = setOf(0)
        val first = async { speaker.speak("First, a long thought.") }
        runCurrent()
        engine.hangs = emptySet()
        val second = speaker.speak("Second.")
        assertEquals(SpeakStatus.CANCELLED, first.await().status)
        assertEquals(SpeakStatus.SPOKEN, second.status)
    }

    @Test
    fun aDeadEngineIsRebuiltAndTheChunkRetriedOnce() = runTest {
        engine.deadGenerations = setOf(0)
        val r = speaker.speak("Hello there.")
        assertEquals(SpeakStatus.SPOKEN, r.status)
        assertEquals("the refusing engine was dropped for a fresh one", 1, engine.generation)
        assertEquals(listOf("Hello there."), engine.spoken)
        // and it stays usable: the next speak needs no further rebuild
        assertEquals(SpeakStatus.SPOKEN, speaker.speak("Again.").status)
        assertEquals(1, engine.resets)
    }

    @Test
    fun anEngineStillRefusingAfterARebuildIsReportedAndDroppedAgain() = runTest {
        engine.deadGenerations = setOf(0, 1)
        assertEquals(SpeakFailure.SPEAK_REJECTED, speaker.speak("Hello.").failure)
        assertEquals(2, engine.generation)
        assertEquals(SpeakStatus.SPOKEN, speaker.speak("Hello.").status)
    }

    @Test
    fun aHungInitIsDroppedSoTheNextSpeakStartsAFreshEngine() = runTest {
        engine.initHangs = setOf(0)
        val first = speaker.speak("Hello.")
        assertEquals(SpeakFailure.ENGINE_INIT, first.failure)
        val start = currentTime
        val second = speaker.speak("Hello again.")
        assertEquals(SpeakStatus.SPOKEN, second.status)
        assertEquals("no second wait on the dead init", start, currentTime)
    }

    @Test
    fun losingAudioFocusMidSpeechStopsItAsInterrupted() = runTest {
        engine.hangs = setOf(0)
        val speaking = async { speaker.speak(LONG) }
        advanceTimeBy(300)
        runCurrent()
        val stopsBefore = engine.stops
        focus.lose()
        val r = speaking.await()
        assertEquals(SpeakStatus.FAILED, r.status)
        assertEquals(SpeakFailure.INTERRUPTED, r.failure)
        assertEquals(stopsBefore + 1, engine.stops)
        assertEquals("nothing after the interruption is queued", 1, engine.spoken.size)
        assertEquals(1, focus.abandons)
        assertTrue(currentTime < speechTimeoutMs(engine.spoken[0], 1.0))
    }

    @Test
    fun aLateFocusLossAfterSpeakingChangesNothing() = runTest {
        assertEquals(SpeakStatus.SPOKEN, speaker.speak("Hello.").status)
        val stops = engine.stops
        focus.lose()
        assertEquals(stops, engine.stops)
    }
}
