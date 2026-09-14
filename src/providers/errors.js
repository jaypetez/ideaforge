// One error vocabulary for every provider, so the UI never has to know which backend it
// is talking to. Adapters translate their own failure shapes into these codes and nothing
// above this layer parses an HTTP status or an error string.

/**
 * `timeout` is distinct from `network` on purpose, and is deliberately NOT retryable. A
 * request that failed outright may well succeed on a second try; one that went two minutes
 * without a reply has a server that is wedged, and making someone wait three deadlines to
 * be told so is worse than telling them after one.
 *
 * @typedef {'auth'|'rate_limit'|'overloaded'|'network'|'timeout'|'bad_response'|'aborted'|'config'} ProviderErrorCode
 */

export class ProviderError extends Error {
  /** @param {ProviderErrorCode} code */
  constructor(code, message, { status = null, retryAfterMs = null, cause = null } = {}) {
    super(message);
    this.name = 'ProviderError';
    this.code = code;
    this.status = status;
    this.retryAfterMs = retryAfterMs;
    if (cause) this.cause = cause;
  }
}

/** Only these are worth trying again; the rest need the user to change something. */
export const RETRYABLE = new Set(['rate_limit', 'overloaded', 'network']);

export function isRetryable(err) {
  return err instanceof ProviderError && RETRYABLE.has(err.code);
}

/** Map an HTTP status onto the taxonomy. Shared by every HTTP adapter. */
export function codeForStatus(status) {
  if (status === 401 || status === 403) return 'auth';
  if (status === 429) return 'rate_limit';
  if (status === 529 || status === 503) return 'overloaded';
  if (status >= 500) return 'overloaded';
  return 'bad_response';
}

/** `Retry-After` is seconds or an HTTP date; both appear in the wild. */
export function retryAfterMs(headers) {
  const raw = headers && headers.get && headers.get('retry-after');
  if (!raw) return null;
  const secs = Number(raw);
  if (Number.isFinite(secs)) return Math.max(0, secs * 1000);
  const at = Date.parse(raw);
  return Number.isFinite(at) ? Math.max(0, at - Date.now()) : null;
}

/**
 * Retry with exponential backoff and full jitter.
 *
 * Jitter matters more than it looks: every turn of the interview issues one call, so a
 * rate-limited user retrying on a fixed schedule re-collides with their own backoff.
 */
export async function withRetry(fn, { attempts = 3, baseMs = 500, maxMs = 8000, signal } = {}) {
  let lastErr;
  for (let i = 0; i < attempts; i++) {
    if (signal && signal.aborted) throw new ProviderError('aborted', 'cancelled');
    try {
      return await fn();
    } catch (err) {
      lastErr = err;
      if (!isRetryable(err) || i === attempts - 1) throw err;
      const backoff = Math.min(maxMs, baseMs * 2 ** i);
      const wait = err.retryAfterMs != null ? err.retryAfterMs : Math.random() * backoff;
      await sleep(wait, signal);
    }
  }
  throw lastErr;
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    if (signal) {
      signal.addEventListener('abort', () => {
        clearTimeout(t);
        reject(new ProviderError('aborted', 'cancelled'));
      }, { once: true });
    }
  });
}
