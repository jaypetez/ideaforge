package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where a provider may send requests, and whether they may carry its key. See
 * [LocalNetworkPolicy]. Abstract rather than sealed so a test can point a client at a
 * plain-http mock server; production code only ever constructs [Hosted] or [Local].
 */
abstract class NetworkPolicy {
    abstract val carriesCredentials: Boolean

    /** Throws a `config` ProviderException for a URL this policy refuses. */
    abstract fun check(url: HttpUrl)

    /** A hosted preset: its own fixed https origin and nothing else. */
    class Hosted(baseUrl: String) : NetworkPolicy() {
        val origin: HttpUrl = baseUrl.toHttpUrl().also {
            require(it.isHttps) { "a hosted preset must use https: $baseUrl" }
        }
        override val carriesCredentials = true
        override fun check(url: HttpUrl) {
            if (!url.isHttps || url.host != origin.host || url.port != origin.port) {
                throw ProviderException(ProviderErrorCode.CONFIG, "${url.host} is not ${origin.host}; a hosted provider only talks to its own address.")
            }
        }
    }

    /** Ollama, LM Studio or another local server: a private network, never a key. */
    data object Local : NetworkPolicy() {
        override val carriesCredentials = false
        override fun check(url: HttpUrl) = LocalNetworkPolicy.check(url)
    }
}

/**
 * Builds the OkHttp client for one policy. Interceptor-free on purpose: every rule is a
 * client setting or the Dns, so an adapter (#79) can add its own interceptors without
 * reordering a policy chain, and nothing here depends on interceptor order.
 *
 * - `callTimeout` is the whole-call deadline ([DEADLINE_MS]). Read and write timeouts are off
 *   because a non-streaming model reply legitimately sends nothing for a minute; the deadline
 *   is the one bound. Connect keeps a short bound, so an unreachable host is a `network`
 *   error rather than two minutes of nothing.
 * - Redirects are never followed. OkHttp drops `Authorization` on a cross-host redirect but
 *   not `x-api-key`, and a local server redirecting to a public IP literal would skip the Dns
 *   guard entirely. No provider API redirects.
 * - Hosted clients speak TLS only; local clients resolve through [LocalNetworkDns] and never
 *   use a proxy (through a proxy the guard would be judging the proxy, not the server).
 *
 * Pass [base] to share one connection pool and dispatcher between clients.
 */
object ProviderHttpClients {
    const val CONNECT_TIMEOUT_MS: Long = 15_000

    fun create(
        policy: NetworkPolicy,
        deadlineMs: Long = DEADLINE_MS,
        base: OkHttpClient? = null,
        dns: Dns = Dns.SYSTEM,
    ): OkHttpClient {
        val b = (base?.newBuilder() ?: OkHttpClient.Builder())
            .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(CONNECT_TIMEOUT_MS, deadlineMs), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
        when (policy) {
            is NetworkPolicy.Hosted -> b.dns(dns).connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            NetworkPolicy.Local -> b.dns(LocalNetworkDns(dns)).proxy(Proxy.NO_PROXY)
            else -> b.dns(dns)
        }
        return b.build()
    }
}

/**
 * One provider's HTTP: the policy check, credentials, the call, and the error vocabulary.
 * Adapters build bodies and parse replies; everything about getting a reply is here.
 */
class ProviderHttp(
    val policy: NetworkPolicy,
    val client: OkHttpClient = ProviderHttpClients.create(policy),
    private val deadlineMs: Long = client.callTimeoutMillis.toLong().takeIf { it > 0 } ?: DEADLINE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * A request builder for [url] with auth applied. A local policy drops the key whatever
     * the descriptor says; a URL the policy refuses throws `config` before anything is built.
     */
    fun request(url: String, auth: AuthDescriptor = AUTH_NONE, apiKey: String = "", headers: Map<String, String> = emptyMap()): Request.Builder {
        val parsed = url.toHttpUrlOrNull()
            ?: throw ProviderException(ProviderErrorCode.CONFIG, "$url is not an http(s) address")
        policy.check(parsed)
        val a = applyAuth(parsed.toString(), auth, if (policy.carriesCredentials) apiKey else "", headers)
        val b = Request.Builder().url(a.url)
        a.headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    /**
     * Run one request. Returns the 2xx response (the caller closes it) or throws a
     * ProviderException: an HTTP failure through [httpError], a transport failure through
     * [callFailure]. Cancelling the coroutine cancels the call and propagates as
     * cancellation — never as `timeout`, which is reserved for the deadline.
     */
    suspend fun execute(request: Request, label: String, keyHint: String = DEFAULT_KEY_HINT): Response {
        policy.check(request.url)
        val call = client.newCall(request)
        val cancelled = AtomicBoolean(false)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancelled.set(true); call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(callFailure(e, label, cancelled.get() || cont.isCancelled, deadlineMs))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        cont.resume(response) { _, r, _ -> r.close() }
                    } else {
                        val err = response.use { httpError(it, label, keyHint, now()) }
                        cont.resumeWithException(err)
                    }
                }
            })
        }
    }
}
