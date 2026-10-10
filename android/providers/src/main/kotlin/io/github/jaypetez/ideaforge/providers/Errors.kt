package io.github.jaypetez.ideaforge.providers

import io.github.jaypetez.ideaforge.core.js.toNumber
import io.github.jaypetez.ideaforge.core.runtime.ProviderException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers

// The Kotlin twin of src/providers/errors.js: one error vocabulary for every provider, so the
// UI never has to know which backend it is talking to. The error type itself is :core's
// ProviderException, because the runtime is what reads the code.

/**
 * The JS ProviderErrorCode values. `timeout` is distinct from `network` on purpose and is
 * deliberately NOT retryable: a server that went two minutes without a reply is wedged, and
 * making someone wait three deadlines to be told so is worse than telling them after one.
 */
object ProviderErrorCode {
    const val AUTH = "auth"
    const val RATE_LIMIT = "rate_limit"
    const val OVERLOADED = "overloaded"
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val BAD_RESPONSE = "bad_response"
    const val ABORTED = "aborted"
    const val CONFIG = "config"
}

/** Only these are worth trying again; the rest need the user to change something. */
val RETRYABLE: Set<String> = setOf(ProviderErrorCode.RATE_LIMIT, ProviderErrorCode.OVERLOADED, ProviderErrorCode.NETWORK)

/** Mirrors `err instanceof ProviderError && RETRYABLE.has(err.code)`: anything else is never retried. */
fun isRetryable(err: Any?): Boolean = err is ProviderException && err.code in RETRYABLE

/** Map an HTTP status onto the taxonomy. Shared by every HTTP adapter. */
fun codeForStatus(status: Int): String = when {
    status == 401 || status == 403 -> ProviderErrorCode.AUTH
    status == 429 -> ProviderErrorCode.RATE_LIMIT
    status >= 500 -> ProviderErrorCode.OVERLOADED   // 503 and 529 included, as in the JS
    else -> ProviderErrorCode.BAD_RESPONSE
}

/**
 * `Retry-After` is seconds or an HTTP date; both appear in the wild. Seconds are read with JS
 * `Number()` semantics (so `" 3 "` and `"1e1"` count, `"soon"` does not), negative values
 * clamp to zero. The JS returns a fractional millisecond count for fractional seconds; this
 * rounds up, since the value is only ever a minimum wait.
 *
 * [nowMs] is injected because the date form is relative to now.
 */
fun retryAfterMs(raw: String?, nowMs: Long): Long? {
    if (raw.isNullOrEmpty()) return null
    val secs = toNumber(JsonPrimitive(raw))
    if (secs.isFinite()) return Math.ceil(maxOf(0.0, secs * 1000)).toLong()
    val at = runCatching { Headers.headersOf("retry-after", raw).getDate("retry-after") }.getOrNull()
        ?: return null
    return maxOf(0L, at.time - nowMs)
}

fun retryAfterMs(headers: Headers, nowMs: Long): Long? = retryAfterMs(headers["retry-after"], nowMs)

/** The longest server-requested wait withRetry will honour: one call deadline. */
const val MAX_RETRY_AFTER_MS: Long = DEADLINE_MS

/**
 * Retry with exponential backoff and full jitter. Jitter matters more than it looks: every
 * turn of the interview issues one call, so a rate-limited user retrying on a fixed schedule
 * re-collides with their own backoff.
 *
 * [delay] and [random] are injected so the schedule is testable without sleeping. The JS
 * checks its abort signal before every attempt; here that is the coroutine's own
 * cancellation, which propagates rather than becoming an `aborted` error, the same rule the
 * runtime's `asProviderFailure` follows.
 */
suspend fun <T> withRetry(
    attempts: Int = 3,
    baseMs: Long = 500,
    maxMs: Long = 8000,
    maxRetryAfterMs: Long = MAX_RETRY_AFTER_MS,
    random: () -> Double = Math::random,
    delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    fn: suspend () -> T,
): T {
    var last: Throwable? = null
    for (i in 0 until attempts) {
        currentCoroutineContext().ensureActive()
        try {
            return fn()
        } catch (err: ProviderException) {
            last = err
            if (!isRetryable(err) || i == attempts - 1) throw err
            // A server asking for longer than the bound (Retry-After: 86400, or 1e300 read as
            // Long.MAX_VALUE) is not asking for a retry this turn can wait for: surface it.
            val asked = err.retryAfterMs
            if (asked != null && asked > maxRetryAfterMs) throw err
            val backoff = minOf(maxMs, baseMs shl i)
            delay(asked ?: (random() * backoff).toLong())
        }
    }
    throw last ?: IllegalArgumentException("withRetry needs at least one attempt")
}
