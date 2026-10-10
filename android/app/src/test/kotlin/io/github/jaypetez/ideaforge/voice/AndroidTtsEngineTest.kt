package io.github.jaypetez.ideaforge.voice

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
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
