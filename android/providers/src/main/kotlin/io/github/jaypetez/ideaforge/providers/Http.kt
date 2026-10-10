package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.jsString
import io.github.jaypetez.ideaforge.core.js.truthy
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

// The Kotlin twin of src/providers/http.js: how a credential is attached, what the browser
// rule calls "on this machine", and how a failed response becomes a ProviderException.
//
// What is deliberately NOT here is assertLoopback. That rule exists because a web page's CSP
// is what keeps a stolen key from being posted anywhere, and loopback is the only widening
// that keeps that promise in a browser. A native app has no CSP and different reach, so the
// native rule is LocalNetworkPolicy, enforced at resolution time by LocalNetworkDns.

/** How a credential rides on a request. JS `scheme: 'none'|'bearer'|'header'|'query'`. */
enum class AuthScheme { NONE, BEARER, HEADER, QUERY }

/**
 * Everything that differs between providers' authentication, as data. Nothing about it is
 * implicit in which factory built the adapter.
 *
 * @property header header name, for [AuthScheme.HEADER] (e.g. `x-api-key`)
 * @property prefix value prefix; `Bearer ` by default for [AuthScheme.BEARER]
 * @property param query parameter name, for [AuthScheme.QUERY]
 * @property extraHeaders sent every time, credential or not
 */
data class AuthDescriptor(
    val scheme: AuthScheme,
    val header: String? = null,
    val prefix: String? = null,
    val param: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

val AUTH_NONE = AuthDescriptor(AuthScheme.NONE)
val AUTH_BEARER = AuthDescriptor(AuthScheme.BEARER)

/** The url and headers one request should carry. Headers keep insertion order, as a JS object does. */
data class Authorized(val url: String, val headers: Map<String, String>)

/**
 * Attach the credential to exactly one request. Returns a url as well as headers because
 * 'query' puts the credential in the URL and the other three do not.
 *
 * Deliberately does NOT set content-type: a multipart upload must write its own boundary.
 * A descriptor that names the wrong field (`name` instead of `header`) attaches nothing —
 * the JS does the same, and the vectors record it.
 */
fun applyAuth(
    url: String,
    auth: AuthDescriptor? = AUTH_NONE,
    apiKey: String? = "",
    headers: Map<String, String> = emptyMap(),
): Authorized {
    val a = auth ?: AUTH_NONE
    val out = LinkedHashMap(headers)
    out.putAll(a.extraHeaders)
    val key = apiKey.orEmpty()
    var target = url
    if (key.isNotEmpty()) {
        when {
            a.scheme == AuthScheme.BEARER ->
                out["authorization"] = "${a.prefix?.ifEmpty { null } ?: "Bearer "}$key"
            a.scheme == AuthScheme.HEADER && !a.header.isNullOrEmpty() ->
                out[a.header.lowercase()] = "${a.prefix.orEmpty()}$key"
            a.scheme == AuthScheme.QUERY && !a.param.isNullOrEmpty() -> {
                // JS uses URL.searchParams.set, which throws on an unparseable URL; so does this.
                val u = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid URL: $url")
                target = u.newBuilder().setQueryParameter(a.param, key).build().toString()
            }
        }
    }
    return Authorized(target, out)
}

/**
 * The BROWSER's definition of "this is on my machine", ported faithfully for the golden
 * vectors: the WHATWG hostname is `localhost`, `[::1]`, an address in 127/8 (in any spelling
 * the URL parser normalises, so `127.1` counts) or ends in `.localhost`. Parsing the URL
 * rather than matching its prefix is what makes `localhost.evil.com` remote.
 *
 * Native code does not gate requests on this; see [LocalNetworkPolicy].
 */
fun isLoopback(url: String): Boolean {
    val h = WhatwgHost.parse(url)?.hostname ?: return false
    return LOOPBACK.matches(h) || h.endsWith(".localhost", ignoreCase = true)
}

// 0.0.0.0 is deliberately absent: it is not a loopback address.
private val LOOPBACK = Regex("^(localhost|\\[::1]|127(?:\\.\\d{1,3}){3})$", RegexOption.IGNORE_CASE)

/** JS `trimSlash`: drop every trailing slash. */
fun trimSlash(s: String?): String = s.orEmpty().trimEnd('/')

/** JS `hostOf`: `new URL(u).host` (host plus a non-default port), or the input when unparseable. */
fun hostOf(u: String): String = WhatwgHost.parse(u)?.host ?: u

/**
 * How long one model call may go without a reply before we stop waiting. Two minutes is
 * boring on purpose: a cold local model load takes tens of seconds, so anything past this is
 * a fault rather than slowness. It is the OkHttp `callTimeout` of every provider client.
 */
const val DEADLINE_MS: Long = 120_000

const val DEFAULT_KEY_HINT = "check the API key in Settings"

/**
 * Every adapter's failed response becomes the same fields: a code from the status, the
 * body's `error.message || error.code` (else the reason phrase), a key hint for auth
 * failures, the status, and any Retry-After. Reads and closes the body.
 */
fun httpError(res: Response, label: String, keyHint: String = DEFAULT_KEY_HINT, nowMs: Long = System.currentTimeMillis()): ProviderException {
    val detail = runCatching {
        val body = Json.parseToJsonElement(res.body.string()) as? JsonObject
        val error = body?.get("error") as? JsonObject
        val pick = error?.get("message")?.takeIf(::truthy) ?: error?.get("code")?.takeIf(::truthy)
        pick?.let(::jsString).orEmpty()
    }.getOrDefault("")
    val code = codeForStatus(res.code)
    val hint = if (code == ProviderErrorCode.AUTH) " — $keyHint" else ""
    return ProviderException(
        code,
        "$label ${res.code}: ${detail.ifEmpty { res.message }}$hint",
        status = res.code,
        retryAfterMs = retryAfterMs(res.headers, nowMs),
    )
}

/**
 * Translate a failed call. The JS `abortError` makes the same three-way split, and getting it
 * backwards turns a user pressing cancel into a scary error message:
 *
 * - the caller cancelled → `aborted`, ends the turn quietly;
 * - the deadline expired → `timeout`, worth telling someone about. OkHttp reports its
 *   callTimeout as a plain InterruptedIOException("timeout"); a SocketTimeoutException is a
 *   socket-level connect timeout and means the host was unreachable, i.e. `network`;
 * - the local-network guard refused the host → `config`, the user typed something we refuse;
 * - anything else → `network`, retryable.
 */
fun callFailure(e: Throwable, label: String, callerCancelled: Boolean, deadlineMs: Long = DEADLINE_MS): ProviderException {
    if (callerCancelled) return ProviderException(ProviderErrorCode.ABORTED, "cancelled", e)
    val refused = generateSequence(e) { it.cause }.filterIsInstance<LocalNetworkRefused>().firstOrNull()
    if (refused != null) return ProviderException(ProviderErrorCode.CONFIG, refused.message ?: "refused", e)
    if (e is InterruptedIOException && e !is SocketTimeoutException) {
        return ProviderException(
            ProviderErrorCode.TIMEOUT,
            "$label did not answer within ${Math.round(deadlineMs / 1000.0)}s. For a local server that " +
                "usually means the model is still loading, or is too large for the memory it has.",
            e,
        )
    }
    return ProviderException(ProviderErrorCode.NETWORK, "$label could not be reached: ${e.message ?: e}", e)
}
