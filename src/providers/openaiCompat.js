// One adapter for every OpenAI-shaped `/chat/completions` endpoint: OpenAI itself, Groq,
// OpenRouter, and a local Ollama or LM Studio. They differ only in base URL and model id.
//
// The trap worth knowing about, verified live against api.openai.com: a request with an
// INVALID key is rejected by an auth layer that sends no CORS headers, so in a browser it
// surfaces as an opaque `TypeError: Failed to fetch` — no status, no body, nothing to show
// the user. A request with no key at all, and `GET /models` with a bad key, both DO carry
// CORS headers. Hence two things below: `validateKey` probes `/models` so a bad key is
// diagnosed cleanly at paste time, and an opaque network failure is reported as a probable
// auth problem rather than "check your internet".

import { ProviderError, codeForStatus, retryAfterMs, withRetry } from './errors.js';
import { extractJson } from './json.js';

/**
 * Presets are base URL + tier mapping only; anything here also works with a hand-typed
 * base URL, which is how a provider we have never heard of gets supported.
 */
export const OPENAI_COMPAT_PRESETS = {
  openai: {
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    tiers: { quick: 'gpt-5-nano', default: 'gpt-5-nano', complex: 'gpt-5-mini' },
    keyUrl: 'https://platform.openai.com/api-keys',
  },
  groq: {
    label: 'Groq',
    baseUrl: 'https://api.groq.com/openai/v1',
    tiers: { quick: 'openai/gpt-oss-20b', default: 'openai/gpt-oss-20b', complex: 'openai/gpt-oss-120b' },
    keyUrl: 'https://console.groq.com/keys',
    note: 'Free tier, no card required.',
  },
  openrouter: {
    label: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    tiers: { quick: 'openai/gpt-5-nano', default: 'openai/gpt-5-nano', complex: 'openai/gpt-5-mini' },
    keyUrl: 'https://openrouter.ai/keys',
  },
  ollama: {
    label: 'Ollama (local)',
    baseUrl: 'http://localhost:11434/v1',
    tiers: { quick: 'llama3.2', default: 'llama3.2', complex: 'llama3.1:8b' },
    // Ollama only allows localhost page origins by default, so a hosted PWA is blocked
    // until the user widens it. Surfaced in the UI rather than left to fail mysteriously.
    note: 'Set OLLAMA_ORIGINS to this app’s origin, or run the app from localhost.',
  },
  lmstudio: {
    label: 'LM Studio (local)',
    baseUrl: 'http://localhost:1234/v1',
    tiers: { quick: 'local-model', default: 'local-model', complex: 'local-model' },
    note: 'Enable CORS in LM Studio’s server settings first.',
  },
};

/**
 * @param {{apiKey?: string, baseUrl?: string, preset?: string, model?: string,
 *          tiers?: object, headers?: object, maxTokens?: number}} config
 */
export function createOpenAICompatProvider(config) {
  const cfg = config || {};
  const preset = cfg.preset ? OPENAI_COMPAT_PRESETS[cfg.preset] : null;
  if (cfg.preset && !preset) throw new ProviderError('config', `unknown preset: ${cfg.preset}`);

  const baseUrl = trimSlash(cfg.baseUrl || (preset && preset.baseUrl) || '');
  const tiers = cfg.tiers || (preset && preset.tiers) || {};
  const { apiKey = '', model = null, headers: extraHeaders = {}, maxTokens = 4096 } = cfg;
  if (!baseUrl) throw new ProviderError('config', 'OpenAI-compatible provider needs a base URL');

  const isLocal = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])/i.test(baseUrl);
  if (!apiKey && !isLocal) throw new ProviderError('config', 'this provider needs an API key');

  const authHeaders = () => ({
    'content-type': 'application/json',
    ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {}),
    ...extraHeaders,
  });

  /** True once any request has come back with headers, i.e. CORS is definitely fine. */
  let sawResponse = false;

  async function call({ system, prefix, tail }, { modelTier = 'default', json = false, signal } = {}) {
    const resolved = model || tiers[modelTier] || tiers.default;

    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: 'POST',
      credentials: 'omit',
      signal,
      headers: authHeaders(),
      body: JSON.stringify({
        model: resolved,
        max_tokens: maxTokens,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: `${prefix}\n${tail}` },
        ],
        // json_object rather than a strict json_schema: the turn schema keys `coverage` by
        // dimension id, and strict mode would force the model to restate all seven every
        // turn. parseTurnResult does the real validation anyway.
        ...(json ? { response_format: { type: 'json_object' } } : {}),
      }),
    }).catch((err) => {
      if (err && err.name === 'AbortError') throw new ProviderError('aborted', 'cancelled');
      throw opaqueFailure(err, sawResponse, isLocal, baseUrl);
    });

    sawResponse = true;
    if (!res.ok) throw await httpError(res, baseUrl);

    const body = await res.json();
    const choice = (body.choices || [])[0];
    const text = (choice && choice.message && choice.message.content) || '';
    if (choice && choice.finish_reason === 'length') {
      throw new ProviderError('bad_response', 'the reply was cut off by the token limit');
    }
    return { text, modelTierApplied: body.model || resolved, usage: body.usage || null };
  }

  return {
    id: cfg.preset || 'openai-compat',
    label: (preset && preset.label) || baseUrl,
    note: preset && preset.note,
    sample: (parts, opts = {}) => withRetry(() => call(parts, opts), { signal: opts.signal }),
    sampleJson: async (parts, opts = {}) => {
      const out = await withRetry(() => call(parts, { ...opts, json: true }), { signal: opts.signal });
      return { ...out, json: extractJson(out.text) };
    },
    /**
     * `GET /models` is the right probe: unlike `/chat/completions` it answers a bad key
     * with a CORS-visible 401, so we can tell "wrong key" from "unreachable".
     */
    validateKey: async (signal) => {
      const res = await fetch(`${baseUrl}/models`, {
        method: 'GET', credentials: 'omit', signal, headers: authHeaders(),
      }).catch((err) => {
        if (err && err.name === 'AbortError') throw new ProviderError('aborted', 'cancelled');
        throw opaqueFailure(err, false, isLocal, baseUrl);
      });
      sawResponse = true;
      if (!res.ok) throw await httpError(res, baseUrl);
      return true;
    },
  };
}

/**
 * A fetch that rejects without ever producing a response is either a CORS refusal or a
 * dead network, and the browser deliberately refuses to tell us which. Guess usefully.
 */
function opaqueFailure(err, sawResponse, isLocal, baseUrl) {
  if (isLocal) {
    return new ProviderError('network',
      `could not reach ${baseUrl}. Is the local server running, and is CORS enabled for this origin?`,
      { cause: err });
  }
  if (!sawResponse) {
    return new ProviderError('auth',
      'the request was blocked before a reply came back — usually a rejected API key. ' +
      'Re-check the key in Settings.', { cause: err });
  }
  return new ProviderError('network', `network error talking to ${baseUrl}: ${err.message}`, { cause: err });
}

async function httpError(res, baseUrl) {
  let detail = '';
  try {
    const body = await res.json();
    detail = (body && body.error && (body.error.message || body.error.code)) || '';
  } catch { /* a non-JSON error body is still an error */ }
  const code = codeForStatus(res.status);
  const hint = code === 'auth' ? ' — check the API key in Settings' : '';
  return new ProviderError(code, `${host(baseUrl)} ${res.status}: ${detail || res.statusText}${hint}`, {
    status: res.status,
    retryAfterMs: retryAfterMs(res.headers),
  });
}

const trimSlash = (s) => String(s || '').replace(/\/+$/, '');
const host = (u) => { try { return new URL(u).host; } catch { return u; } };
