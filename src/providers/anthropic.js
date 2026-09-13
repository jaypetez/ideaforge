// Anthropic Messages API, called straight from the page with the user's own key.
//
// Two things about browser-direct Anthropic that are easy to get wrong and expensive to
// debug, both verified against the live API:
//
//  1. The CORS preflight only succeeds if `anthropic-dangerous-direct-browser-access` is
//     among the *requested* headers. The server keys off the header NAME appearing in
//     Access-Control-Request-Headers, not its value. Send it and allow-headers is
//     reflected verbatim, so anything else you add is fine; omit it and the preflight
//     400s with no CORS headers at all and you never see the real response.
//  2. Do not set `credentials: 'include'`. The preflight returns the spec-invalid pairing
//     of `allow-credentials: true` with `allow-origin: *`, which the browser rejects.
//     fetch's default is what we want; it is spelled out below so nobody "fixes" it.
//
// This header ships in Anthropic's own SDK but is absent from their public docs, so treat
// it as supported-but-undocumented. If it ever stops working the fix is the proxy in
// openaiCompat's `proxyBase`, not a rewrite.

import { ProviderError, codeForStatus, retryAfterMs, withRetry } from './errors.js';
import { extractJson } from './json.js';

const ENDPOINT = 'https://api.anthropic.com/v1/messages';
const API_VERSION = '2023-06-01';

/**
 * Below Haiku 4.5's 4,096-token minimum a cache breakpoint is silently ignored, so we
 * only spend one once the prefix is plausibly past it. ~3.6 bytes/token is deliberately
 * conservative for English prose.
 */
const MIN_CACHEABLE_BYTES = 16 * 1024;

export const ANTHROPIC_TIERS = {
  quick: 'claude-haiku-4-5',
  default: 'claude-haiku-4-5',
  complex: 'claude-sonnet-5',
};

/**
 * @param {{apiKey: string, model?: string, tiers?: object, baseUrl?: string,
 *          maxTokens?: number}} config
 */
export function createAnthropicProvider(config) {
  const { apiKey, model = null, tiers = ANTHROPIC_TIERS, baseUrl = ENDPOINT,
          maxTokens = 4096 } = config || {};
  if (!apiKey) throw new ProviderError('config', 'Anthropic provider needs an API key');

  async function call({ system, prefix, tail }, { modelTier = 'default', signal } = {}) {
    const resolved = model || tiers[modelTier] || tiers.default;

    const head = { type: 'text', text: prefix };
    if (byteLength(prefix) >= MIN_CACHEABLE_BYTES) head.cache_control = { type: 'ephemeral' };

    const res = await fetch(baseUrl, {
      method: 'POST',
      credentials: 'omit',
      signal,
      headers: {
        'content-type': 'application/json',
        'x-api-key': apiKey,
        'anthropic-version': API_VERSION,
        'anthropic-dangerous-direct-browser-access': 'true',
      },
      body: JSON.stringify({
        model: resolved,
        max_tokens: maxTokens,
        system: [{ type: 'text', text: system }],
        messages: [{ role: 'user', content: [head, { type: 'text', text: tail }] }],
      }),
    }).catch((err) => {
      if (err && err.name === 'AbortError') throw new ProviderError('aborted', 'cancelled');
      throw new ProviderError('network', `could not reach Anthropic: ${err.message}`, { cause: err });
    });

    if (!res.ok) throw await httpError(res);

    const body = await res.json();
    const text = (body.content || [])
      .filter((b) => b && b.type === 'text')
      .map((b) => b.text)
      .join('');

    if (body.stop_reason === 'refusal') {
      throw new ProviderError('bad_response', 'the model declined to answer this turn');
    }
    return { text, modelTierApplied: resolved, usage: body.usage || null };
  }

  return {
    id: 'anthropic',
    label: 'Anthropic',
    sample: (parts, opts = {}) =>
      withRetry(() => call(parts, opts), { signal: opts.signal }),
    sampleJson: async (parts, opts = {}) => {
      const out = await withRetry(() => call(parts, opts), { signal: opts.signal });
      return { ...out, json: extractJson(out.text) };
    },
    /** Cheapest possible round trip that proves the key works. */
    validateKey: async (signal) => {
      await call(
        { system: 'Reply with the single word ok.', prefix: 'ping', tail: 'ok' },
        { modelTier: 'quick', signal }
      );
      return true;
    },
  };
}

async function httpError(res) {
  let detail = '';
  try {
    const body = await res.json();
    detail = (body && body.error && body.error.message) || '';
  } catch { /* a non-JSON error body is still an error */ }
  const code = codeForStatus(res.status);
  const hint = code === 'auth' ? ' — check the API key in Settings' : '';
  return new ProviderError(code, `Anthropic ${res.status}: ${detail || res.statusText}${hint}`, {
    status: res.status,
    retryAfterMs: retryAfterMs(res.headers),
  });
}

/** Same counting rule as digest.utf8Length; duplicated to keep core dependency-free. */
function byteLength(str) {
  let bytes = 0;
  for (let i = 0; i < str.length; i++) {
    const c = str.codePointAt(i);
    if (c <= 0x7f) bytes += 1;
    else if (c <= 0x7ff) bytes += 2;
    else if (c <= 0xffff) bytes += 3;
    else { bytes += 4; i++; }
  }
  return bytes;
}
