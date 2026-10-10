package io.github.jaypetez.ideaforge.core.runtime

import io.github.jaypetez.ideaforge.core.PromptParts
import io.github.jaypetez.ideaforge.core.createSession
import io.github.jaypetez.ideaforge.core.js.get
import io.github.jaypetez.ideaforge.core.js.num
import io.github.jaypetez.ideaforge.core.js.str
import io.github.jaypetez.ideaforge.core.js.stringOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The golden vectors only script ProviderExceptions. A real adapter can leak anything; the
// interview must still never dead-end, and a cancellation must still cancel.
class ProviderFailureTest {
    private fun throwing(e: Exception) = object : Provider {
        override suspend fun sampleJson(parts: PromptParts, modelTier: String, cache: Boolean): SampleResult = throw e
    }

    private fun answered() = submitAnswer(
        seedTurn(createSession("s_fail", num(1)), num(1)), str("An app that remembers names at conferences."), now = num(2),
    )

    @Test
    fun anUnwrappedAdapterErrorFallsBackToTheBank() = runBlocking {
        val before = answered()
        val r = runTurn(before, throwing(IOException("socket closed")), num(3))
        assertTrue(r.degraded)
        assertEquals("bank", stringOf(r.turn["questionSource"]))
        assertTrue("provider error" in r.warnings, r.warnings.toString())
        assertEquals(before["zeroGainStreak"], r.session["zeroGainStreak"])
        assertEquals(null, r.error?.code)
    }

    @Test
    fun anUnwrappedErrorInTheWrapUpRestoresTheSession() = runBlocking {
        val r = runSynthesis(answered(), throwing(IllegalStateException("bad decode")), num(3))
        assertFalse(r.ok)
        assertEquals("interviewing", stringOf(r.session["status"]))
    }

    @Test
    fun cancellationIsNeverTurnedIntoAFallback() {
        assertFailsWith<CancellationException> {
            runBlocking { runTurn(answered(), throwing(CancellationException("left the screen")), num(3)) }
        }
    }
}
