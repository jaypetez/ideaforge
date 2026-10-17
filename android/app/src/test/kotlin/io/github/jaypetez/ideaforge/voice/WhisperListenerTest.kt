package io.github.jaypetez.ideaforge.voice

import io.github.jaypetez.ideaforge.core.emptyKeyring
import io.github.jaypetez.ideaforge.core.runtime.ListenFailure
import io.github.jaypetez.ideaforge.core.withCreds
import io.github.jaypetez.ideaforge.core.withStt
import io.github.jaypetez.ideaforge.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperListenerTest {
    private val uploads = mutableListOf<ByteArray>()
    private val replies = ArrayDeque<String>()

    private fun TestScope.listener(mic: FakePcm, maxBytes: Int = 25 * 1024 * 1024) = WhisperListener(
        source = { mic },
        transcribe = { audio, mime ->
            assertEquals("audio/wav", mime)
            uploads += audio
            replies.removeFirst()
        },
        maxBytes = maxBytes,
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun anAnswerEndsOnTheTriggerAndIsReturnedRaw() = runTest {
        val mic = FakePcm(speech())
        replies += "it should be cheap over"
        assertEquals("it should be cheap over", listener(mic).listenForDrive("over", 8000))
        assertEquals(1, uploads.size)
        assertEquals("RIFF", String(uploads[0], 0, 4, Charsets.ISO_8859_1))
        assertEquals("the microphone is closed afterwards", 1, mic.stops)
    }

    // After a word, a pause never ends a hands-free answer: only the trigger does.
    @Test
    fun segmentsAccumulateUntilTheTrigger() = runTest {
        val mic = FakePcm(listOf(600L to 40, 1500L to 8000, 4000L to 40, 1500L to 8000))
        replies += "we went over budget"
        replies += "last year over"
        assertEquals("we went over budget last year over", listener(mic).listenForDrive("over", 8000))
        assertEquals(2, uploads.size)
    }

    @Test
    fun nothingSaidIsAMissAndUploadsNothing() = runTest {
        val mic = FakePcm(emptyList())
        assertEquals("", listener(mic).listenForDrive("over", 8000))
        assertTrue("silence is never sent anywhere", uploads.isEmpty())
        assertEquals(1, mic.stops)
    }

    @Test
    fun oneUploadIsBoundedInSize() = runTest {
        val mic = FakePcm(listOf(600L to 40, 60_000L to 8000))
        replies += "a very long answer over"
        listener(mic, maxBytes = 64_000).listenForDrive("over", 8000)
        assertTrue("upload ${uploads[0].size} bytes", uploads[0].size <= 64_000)
    }

    @Test
    fun cancellationStopsTheRecording() = runTest {
        val mic = FakePcm(speech())
        val hold = CompletableDeferred<String>()
        val w = WhisperListener({ mic }, { _, _ -> hold.await() }, io = StandardTestDispatcher(testScheduler))
        val listening = async { w.listenForDrive("over", 8000) }
        runCurrent()
        listening.cancel()
        runCurrent()
        assertEquals(1, mic.stops)
        assertFalse(mic.open)
    }

    @Test
    fun transcriptionStatusBracketsTheRealUploadAndClosesOnCancellation() = runTest {
        val mic = FakePcm(speech())
        val hold = CompletableDeferred<String>()
        val status = mutableListOf<Boolean>()
        val w = WhisperListener({ mic }, { _, _ -> hold.await() }, io = StandardTestDispatcher(testScheduler))
        val listening = async { w.listenForDrive("over", 8000, onTranscribing = { status += it }) }
        runCurrent()
        assertEquals(listOf(true), status)
        listening.cancel()
        runCurrent()
        assertEquals(listOf(true, false), status)
        assertFalse(mic.open)
        assertTrue("nothing returned after cancellation", listening.isCancelled)
    }

    @Test
    fun aMicrophoneThatWillNotOpenIsACaptureFailure() = runTest {
        try {
            listener(FakePcm(speech(), startOk = false)).listenForDrive("over", 8000)
            fail("expected a ListenFailure")
        } catch (e: ListenFailure) {
            assertEquals("AudioCaptureError", e.name)
        }
    }

    // Its own consent and its own key: never the inference key.
    @Test
    fun theFallbackNeedsAudioConsentAndItsOwnSttKey() {
        val inferenceOnly = withCreds(emptyKeyring(), "groq", JsonObject(mapOf("apiKey" to JsonPrimitive("gsk_inference"))))
        val withStt = withStt(inferenceOnly, JsonObject(mapOf("kind" to JsonPrimitive("groq"), "apiKey" to JsonPrimitive("gsk_stt"))))
        val consent = Prefs(hostedTranscriptionAllowed = true)
        val mic = { FakePcm(emptyList()) }
        assertNull("the inference key is never borrowed", whisperFrom(inferenceOnly, consent, source = mic))
        assertNull("no audio-transfer consent", whisperFrom(withStt, Prefs(hostedSpeechAllowed = true), source = mic))
        assertNotNull(whisperFrom(withStt, consent, source = mic))
    }
    // Review #93: consent withdrawn or Forget mid-answer: nothing more is uploaded.
    @Test
    fun aWithdrawnConsentStopsTheNextUpload() = runTest {
        val mic = FakePcm(speech())
        var allowed = false
        val w = WhisperListener({ mic }, { a, _ -> uploads += a; "x over" }, io = StandardTestDispatcher(testScheduler), allowed = { allowed })
        try {
            w.listenForDrive("over", 8000)
            fail("expected a stop")
        } catch (e: ListenFailure) {
            assertEquals("AbortError", e.name)
        }
        assertTrue(uploads.isEmpty())
        assertEquals(1, mic.stops)
    }

}
