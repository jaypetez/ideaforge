package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP spine against a real socket (MockWebServer on loopback). No public network: the
 * "hosted" policy here is pointed at the mock server, whose host is let through a test-only
 * subclass because the real Hosted policy insists on https.
 */
class ProviderHttpTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun start() { server = MockWebServer(); server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
    @AfterTest fun stop() { server.close() }

    /** A plain-http client for the mock server, with the production settings and deadline. */
    private fun http(deadlineMs: Long = DEADLINE_MS, now: () -> Long = { 0L }): ProviderHttp {
        val policy = object : NetworkPolicy() {
            override val carriesCredentials = true
            override fun check(url: HttpUrl) {}
        }
        // The local client's settings; the mock server is an IP literal, so no Dns is consulted.
        val client = ProviderHttpClients.create(NetworkPolicy.Local, deadlineMs)
        return ProviderHttp(policy, client, now = now)
    }

    // By IP literal: server.url() names the host by reverse lookup, which on a machine with
    // Docker Desktop is kubernetes.docker.internal, and the local guard rightly refuses that.
    private fun get(h: ProviderHttp) = h.request("http://127.0.0.1:${server.port}/v1/chat", AUTH_BEARER, "sk-test").build()

    private fun failure(response: MockResponse, h: ProviderHttp = http()): ProviderException {
        server.enqueue(response)
        return assertFailsWith<ProviderException> { runBlocking { h.execute(get(h), "OpenAI") } }
    }

    @Test
    fun theDefaultClientCarriesTheDeadlineAndNoOtherBound() {
        val c = ProviderHttpClients.create(NetworkPolicy.Hosted("https://api.openai.com/v1"))
        assertEquals(120_000, c.callTimeoutMillis)
        assertEquals(0, c.readTimeoutMillis)
        assertEquals(0, c.writeTimeoutMillis)
        assertEquals(false, c.followRedirects)
        assertEquals(false, c.followSslRedirects)
        assertTrue(c.interceptors.isEmpty() && c.networkInterceptors.isEmpty(), "interceptor-free")
        assertTrue(c.connectionSpecs.none { it.isTls.not() }, "a hosted client cannot speak cleartext")
    }

    @Test
    fun successReturnsTheResponseAndSendsTheKey() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{\"ok\":true}").build())
        val h = http()
        h.execute(get(h), "OpenAI").use { assertEquals("{\"ok\":true}", it.body.string()) }
        assertEquals("Bearer sk-test", server.takeRequest().headers["authorization"])
    }

    @Test
    fun statusesMapOntoTheTaxonomy() {
        for ((status, code) in listOf(400 to "bad_response", 401 to "auth", 403 to "auth", 404 to "bad_response",
            429 to "rate_limit", 500 to "overloaded", 502 to "overloaded", 503 to "overloaded", 529 to "overloaded")) {
            val e = failure(MockResponse.Builder().code(status).build())
            assertEquals(code, e.code, "status $status")
            assertEquals(status, e.status)
        }
    }

    @Test
    fun theMessageReadsTheBodyThenTheReasonAndHintsAtTheKeyForAuth() {
        val m = failure(MockResponse.Builder().code(400).body("{\"error\":{\"message\":\"bad model\"}}").build())
        assertEquals("OpenAI 400: bad model", m.message)
        val c = failure(MockResponse.Builder().code(400).body("{\"error\":{\"code\":\"model_not_found\"}}").build())
        assertEquals("OpenAI 400: model_not_found", c.message)
        val r = failure(MockResponse.Builder().code(401).status("HTTP/1.1 401 Unauthorized").body("<html>").build())
        assertEquals("OpenAI 401: Unauthorized — check the API key in Settings", r.message)
    }

    @Test
    fun retryAfterSecondsAndDatesAreRead() {
        val secs = failure(MockResponse.Builder().code(429).addHeader("Retry-After", "3").build())
        assertEquals(3000L, secs.retryAfterMs)
        // An HTTP date is relative to the injected clock: 1994-11-06T08:49:37Z minus 10s.
        val at = 784111777_000L
        val date = failure(MockResponse.Builder().code(503).addHeader("Retry-After", "Sun, 06 Nov 1994 08:49:37 GMT").build(),
            http(now = { at - 10_000 }))
        assertEquals(10_000L, date.retryAfterMs)
        assertNull(failure(MockResponse.Builder().code(500).build()).retryAfterMs)
    }

    @Test
    fun withRetryHonoursRetryAfterAgainstARealServer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "2").build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().body("{}").build())
        val h = http()
        val waits = mutableListOf<Long>()
        val res = withRetry(random = { 0.5 }, delay = { waits += it }) { h.execute(get(h), "OpenAI") }
        res.close()
        assertEquals(listOf(2000L, 500L), waits, "Retry-After first, then jittered backoff 0.5 * 1000")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun theDeadlineIsATimeoutNotANetworkError() {
        // The server accepts and never answers. The client's callTimeout is the deadline under
        // test; it is short here so the wait is the deadline itself, not a sleep.
        val e = failure(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build(), http(deadlineMs = 300))
        assertEquals("timeout", e.code)
        assertTrue(e.message!!.startsWith("OpenAI did not answer within 0s."), e.message)
        assertTrue(!isRetryable(e), "a timeout is never retried")
    }

    @Test
    fun cancellingTheCallerIsCancellationNotATimeout() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val h = http()
        val idle = CountDownLatch(1)
        h.client.dispatcher.idleCallback = Runnable { idle.countDown() }
        val job = async(Dispatchers.Default) { h.execute(get(h), "OpenAI") }
        // Wait until the request is on the wire (bounded, so a broken call fails, not hangs).
        if (server.takeRequest(10, TimeUnit.SECONDS) == null) {
            error("the request never arrived: ${runCatching { job.await() }}")
        }
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertIs<CancellationException>(thrown)
        assertTrue(thrown !is ProviderException)
        // The OkHttp call itself was cancelled, long before its 120s deadline.
        assertTrue(idle.await(10, TimeUnit.SECONDS), "the underlying call was not cancelled")
    }

    @Test
    fun failuresAreClassifiedCallerFirst() {
        val timeout = java.io.InterruptedIOException("timeout")
        assertEquals("aborted", callFailure(timeout, "x", callerCancelled = true).code)
        assertEquals("timeout", callFailure(timeout, "x", callerCancelled = false).code)
        assertEquals("network", callFailure(java.net.SocketTimeoutException("connect timed out"), "x", false).code)
        assertEquals("network", callFailure(java.net.ConnectException("refused"), "x", false).code)
        assertEquals("config", callFailure(java.io.IOException("wrapped", LocalNetworkRefused("no")), "x", false).code)
    }

    @Test
    fun anUnreachableServerIsARetryableNetworkError() {
        val port = server.port
        server.close()
        val h = http()
        val e = assertFailsWith<ProviderException> {
            runBlocking { h.execute(h.request("http://127.0.0.1:$port/").build(), "Ollama") }
        }
        assertEquals("network", e.code)
        assertTrue(isRetryable(e))
    }

    @Test
    fun redirectsAreNeverFollowed() {
        val e = failure(MockResponse.Builder().code(307).addHeader("Location", "http://8.8.8.8/steal").build())
        // The redirect comes back as a failure, not a hop toward an address no guard has seen.
        assertEquals(307, e.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aHostedPolicyOnlyTalksToItsOwnHttpsOrigin() {
        val hosted = NetworkPolicy.Hosted("https://api.openai.com/v1")
        val h = ProviderHttp(hosted)
        val req = h.request("https://api.openai.com/v1/models", AUTH_BEARER, "sk-x").build()
        assertEquals("Bearer sk-x", req.header("authorization"))
        for (u in listOf("http://api.openai.com/v1", "https://api.openai.com.evil.com/v1", "https://evil.com/",
            "https://api.openai.com:8443/v1")) {
            assertEquals("config", assertFailsWith<ProviderException>(u) { h.request(u) }.code, u)
        }
        assertFailsWith<IllegalArgumentException> { NetworkPolicy.Hosted("http://api.openai.com/v1") }
    }
}
