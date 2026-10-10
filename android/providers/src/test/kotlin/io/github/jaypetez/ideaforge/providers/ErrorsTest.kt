package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** withRetry's schedule with an injected delay and random: no real sleeping anywhere. */
class ErrorsTest {
    private fun fail(code: String, retryAfterMs: Long? = null) = ProviderException(code, code, retryAfterMs = retryAfterMs)

    @Test
    fun backoffDoublesAndCapsWithFullJitter() = runBlocking {
        val waits = mutableListOf<Long>()
        var calls = 0
        val e = assertFailsWith<ProviderException> {
            withRetry(attempts = 5, baseMs = 500, maxMs = 1500, random = { 0.999 }, delay = { waits += it }) {
                calls++; throw fail("overloaded")
            }
        }
        assertEquals("overloaded", e.code)
        assertEquals(5, calls)
        // min(maxMs, base * 2^i) * random, floored; no wait after the last attempt.
        assertEquals(listOf(499L, 999L, 1498L, 1498L), waits)
    }

    @Test
    fun retryAfterBeatsJitter() = runBlocking {
        val waits = mutableListOf<Long>()
        var calls = 0
        val out = withRetry(random = { 0.0 }, delay = { waits += it }) {
            if (++calls < 3) throw fail("rate_limit", retryAfterMs = 4200) else "ok"
        }
        assertEquals("ok", out)
        assertEquals(listOf(4200L, 4200L), waits)
    }

    @Test
    fun nonRetryableCodesAreThrownAtOnce() = runBlocking {
        for (code in listOf("auth", "bad_response", "timeout", "aborted", "config")) {
            var calls = 0
            val err = fail(code)
            val thrown = assertFailsWith<ProviderException> {
                withRetry(delay = { error("must not wait for $code") }) { calls++; throw err }
            }
            assertSame(err, thrown)
            assertEquals(1, calls, code)
        }
    }

    @Test
    fun otherExceptionsAreNeverRetried() = runBlocking {
        var calls = 0
        assertFailsWith<IllegalStateException> { withRetry(delay = {}) { calls++; error("boom") } }
        assertEquals(1, calls)
    }

    @Test
    fun aCancelledCallerStopsBeforeTheNextAttempt() = runBlocking {
        val job = Job()
        var calls = 0
        assertFailsWith<CancellationException> {
            withContext(job) {
                withRetry(delay = { job.cancel() }) { calls++; throw fail("network") }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun retryAfterReadsNumbersTheWayJsNumberDoes() {
        assertEquals(3000L, retryAfterMs(" 3 ", 0))
        assertEquals(10_000L, retryAfterMs("1e1", 0))
        assertEquals(1500L, retryAfterMs("1.5", 0))
        assertEquals(0L, retryAfterMs("-0", 0))
        assertEquals(null, retryAfterMs("", 0))
        assertEquals(null, retryAfterMs(null, 0))
        assertEquals(null, retryAfterMs("soon", 0))
        // A date in the past is "now".
        assertEquals(0L, retryAfterMs("Sun, 06 Nov 1994 08:49:37 GMT", 784111777_000L + 5))
    }

    @Test
    fun hostOfAndTrimSlashMatchTheJs() {
        assertEquals("localhost:11434", hostOf("http://LOCALHOST:11434/v1"))
        assertEquals("api.openai.com", hostOf("https://api.openai.com:443/v1"))
        assertEquals("[::1]:8080", hostOf("http://[0:0:0:0:0:0:0:1]:8080"))
        assertEquals("not a url", hostOf("not a url"))
        assertEquals("http://x/v1", trimSlash("http://x/v1///"))
        assertEquals("", trimSlash(null))
    }

    @Test
    fun isLoopbackFollowsTheBrowserParserBeyondTheVectors() {
        for (u in listOf("http://0x7f.1/", "http://2130706433/", "http://a.localhost/", "http:\\\\localhost\\x"))
            assertEquals(true, isLoopback(u), u)
        for (u in listOf("http://[::ffff:127.0.0.1]/", "http://127.0.0.1.evil.com/", "http://256.0.0.1/"))
            assertEquals(false, isLoopback(u), u)
    }
}
