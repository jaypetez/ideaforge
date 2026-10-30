package io.github.jaypetez.ideaforge.voice

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** The platform engine's own recovery, on Robolectric's TextToSpeech. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTtsEngineTest {
    private val app = RuntimeEnvironment.getApplication()
    private val made = mutableListOf<TextToSpeech>()

    /** A hang is a failure here, not a test run that never ends. */
    private suspend fun <T> bounded(block: suspend () -> T): T = withTimeout(5_000) { block() }

    /** Generation n answers its init with [answers][n]; null never answers. */
    private fun engine(vararg answers: Int?) = AndroidTtsEngine(app, initTimeoutMs = 50, main = Dispatchers.Unconfined) { ctx, listener ->
        TextToSpeech(ctx, listener).also { tts ->
            val n = made.size
            made += tts
            answers.getOrNull(n)?.let(listener::onInit)
        }
    }

    @Test
    fun voiceMetadataUsesTheExistingEngineWithoutSpeakingOrSelectingAVoice() = runBlocking {
        val installed = setOf(
            Voice("en-network", Locale.UK, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, true, emptySet()),
            Voice("en-local", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()),
        )
        val e = AndroidTtsEngine(app, main = Dispatchers.Unconfined) { ctx, listener ->
            object : TextToSpeech(ctx, listener) {
                override fun getVoices(): Set<Voice> = installed
            }.also { made += it; listener.onInit(TextToSpeech.SUCCESS) }
        }
        assertEquals(listOf(DeviceVoice("en-network", "en-GB", true), DeviceVoice("en-local", "en-US", false)), e.loadDeviceVoices())
        assertEquals(2, e.loadDeviceVoices().size)
        assertEquals("Settings and subsequent speech share initialization", 1, made.size)
        assertEquals(null, shadowOf(made.single()).lastSpokenText)
        e.shutdown()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingACatalogueInitCannotDropAConcurrentSpeakersReadyEngine() = runTest {
        val e = engine(null, TextToSpeech.SUCCESS)
        val catalogue = async { e.awaitReady() }
        runCurrent()
        val speech = async { e.awaitReady() }
        runCurrent()
        assertEquals("only one initialization is in flight", 1, made.size)
        catalogue.cancelAndJoin()
        assertTrue(speech.await())
        assertEquals(2, made.size)
        assertTrue(shadowOf(made.first()).isShutdown)
        assertFalse("the retired catalogue must not shut down speech", shadowOf(made.last()).isShutdown)
        assertTrue(e.awaitReady())
        assertEquals(2, made.size)
        e.shutdown()
    }

    @Test
    fun anInitThatNeverAnswersIsDroppedAndTheNextCallBuildsAFreshEngine() = runBlocking {
        val e = engine(null, TextToSpeech.SUCCESS)
        assertFalse(bounded { e.awaitReady() })
        assertTrue("the half-built engine is shut down, not leaked", shadowOf(made[0]).isShutdown)
        assertTrue(bounded { e.awaitReady() })
        assertEquals(2, made.size)
    }

    @Test
    fun aCallerGivingUpFirstStillLeavesNothingPending() = runBlocking {
        val e = AndroidTtsEngine(app, initTimeoutMs = 60_000, main = Dispatchers.Unconfined) { ctx, listener ->
            TextToSpeech(ctx, listener).also { tts ->
                made += tts
                if (made.size > 1) listener.onInit(TextToSpeech.SUCCESS)
            }
        }
        // The Speaker's own bound cancels the await; cleanup must not depend on returning.
        assertEquals(null, withTimeoutOrNull(50) { e.awaitReady() })
        assertTrue(shadowOf(made[0]).isShutdown)
        assertTrue(bounded { e.awaitReady() })
    }

    @Test
    fun aFailedInitIsDropped() = runBlocking {
        val e = engine(TextToSpeech.ERROR, TextToSpeech.SUCCESS)
        assertFalse(bounded { e.awaitReady() })
        assertTrue(bounded { e.awaitReady() })
        assertEquals(2, made.size)
    }

    @Test
    fun onlyARealFocusLossInterrupts() {
        val focus = AndroidAudioFocus(app)
        var lost = 0
        assertTrue(focus.request { lost++ })
        focus.focusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0, lost)
        focus.focusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus.focusChanged(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(2, lost)
        focus.abandon()
        focus.focusChanged(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals("no callback once focus is given back", 2, lost)
    }
}
