// One adapter for every OpenAI-shaped `/chat/completions` endpoint: OpenAI itself, Groq,
// OpenRouter, and a local Ollama or LM Studio. They differ only in base URL, model id, and
// how they are authenticated — all three of which are now data on the preset rather than
// branches in here.
//
// The trap worth knowing about, verified live against api.openai.com: a request with an
// INVALID key is rejected by an auth layer that sends no CORS headers, so in a browser it
// surfaces as an opaque `TypeError: Failed to fetch` — no status, no body, nothing to show
// the user. A request with no key at all, and `GET /models` with a bad key, both DO carry
// CORS headers. Hence two things below: `validateKey` probes `/models` so a bad key is
// diagnosed cleanly at paste time, and an opaque network failure is reported as a probable
// auth problem rather than "check your internet".

import { ProviderError, withRetry } from './errors.js';
import { extractJson } from './json.js';
import {
  AUTH_BEARER, applyAuth, isLoopback, localFetchOptions, httpError, trimSlash,
  withDeadline, abortError, DEADLINE_MS,
} from './http.js';

/**
 * Presets are base URL, tier mapping and auth style; anything here also works with a
 * hand-typed base URL, which is how a local server we have never heard of gets supported.
 *
 * `tokenParam` is per-preset because the name of the output-budget parameter is a
 * per-vendor fact, not a universal one. `local` is separate from the auth scheme on
 * purpose: a local server needs no key, but an Ollama behind a reverse proxy that wants a
 * bearer token still works if you paste one.
 *
 * `discoverModels` is not a local-only flag. Every hosted preset here answers `GET /models`
 * with CORS headers — that is precisely why `validateKey` probes it — so the settings
 * screen can offer the real catalogue rather than the two ids this file happens to default
 * to. The list arrives raw, embeddings and speech models included; it is a suggestion for a
 * free-text box, not a validated set, which is the same contract it has always had locally.
 * Anthropic is the exception and says so in its own adapter.
 */
export const OPENAI_COMPAT_PRESETS = {
  openai: {
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    auth: AUTH_BEARER,
    // Every GPT-5-era model rejects `max_tokens` outright: "Unsupported parameter:
    // 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."
    // This preset defaults to gpt-5-nano, so it was returning a 400 on every turn.
    tokenParam: 'max_completion_tokens',
    discoverModels: true,
    // And renaming it is only half the fix. A reasoning model spends part of its budget
    // before writing a character, so the 4k that is plenty for Haiku comes back as an
    // empty choice with finish_reason 'length' — the same failure wearing a 200 OK.
    maxTokens: 8192,
    tiers: { quick: 'gpt-5-nano', default: 'gpt-5-nano', complex: 'gpt-5-mini' },
    keyUrl: 'https://platform.openai.com/api-keys',
  },
  groq: {
    label: 'Groq',
    baseUrl: 'https://api.groq.com/openai/v1',
    auth: AUTH_BEARER,
    tokenParam: 'max_tokens',
    discoverModels: true,
    tiers: { quick: 'openai/gpt-oss-20b', default: 'openai/gpt-oss-20b', complex: 'openai/gpt-oss-120b' },
    keyUrl: 'https://console.groq.com/keys',
    note: 'Free tier, no card required.',
  },
  openrouter: {
    label: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    auth: AUTH_BEARER,
    // OpenRouter normalises the budget parameter across everything it fronts, so the
    // OpenAI-shaped name keeps working even for models that would refuse it directly.
    tokenParam: 'max_tokens',
    discoverModels: true,
    tiers: { quick: 'openai/gpt-5-nano', default: 'openai/gpt-5-nano', complex: 'openai/gpt-5-mini' },
    keyUrl: 'https://openrouter.ai/keys',
  },
  ollama: {
    label: 'Ollama (local)',
    baseUrl: 'http://localhost:11434/v1',
    auth: AUTH_BEARER,
    tokenParam: 'max_tokens',
    local: true,
    discoverModels: true,
    modelRequired: true,
    // Double the default. A model that thinks out loud spends part of its budget narrating
    // before it emits a character of JSON, and a reply cut off by the cap arrives as
    // finish_reason 'length' — which is a bad_response, which is NOT retryable, which is
    // the question bank for that turn. Cheap insurance locally, where tokens are free.
    maxTokens: 8192,
    // There is deliberately no tier map. It used to say `llama3.2`, which is a guess, and
    // a guess 404s for everyone who has not pulled exactly that model. The installed list
    // is one GET /models away, so ask the server instead of guessing on its behalf.
    tiers: null,
    note: 'Pick a model below. From a hosted page you must also set OLLAMA_ORIGINS to ' +
          'this app’s origin and restart Ollama — it only reads that at startup.',
  },
  lmstudio: {
    label: 'LM Studio (local)',
    baseUrl: 'http://localhost:1234/v1',
    auth: AUTH_BEARER,
    tokenParam: 'max_tokens',
    local: true,
    discoverModels: true,
    modelRequired: true,
    // Double the default. A model that thinks out loud spends part of its budget narrating
    // before it emits a character of JSON, and a reply cut off by the cap arrives as
    // finish_reason 'length' — which is a bad_response, which is NOT retryable, which is
    // the question bank for that turn. Cheap insurance locally, where tokens are free.
    maxTokens: 8192,
    tiers: null,
    note: 'Enable CORS in LM Studio’s server settings, then pick a model below.',
  },
};

/**
 * Never trust the list. Ollama and LM Studio both answer `/v1/models` with OpenAI's
 * `{data:[{id}]}` shape, Ollama's native `/api/tags` answers `{models:[{name}]}`, and
 * whatever a user has proxied in between could answer with anything at all. Same rule as
 * parseTurnResult: assume it lies about shape, and keep only what survives.
 */
export function parseModelList(body) {
  const rows = Array.isArray(body) ? body
    : Array.isArray(body && body.data) ? body.data
    : Array.isArray(body && body.models) ? body.models
    : [];
  const names = [];
  for (const row of rows) {
    const raw = typeof row === 'string' ? row
      : row && typeof row.id === 'string' ? row.id
      : row && typeof row.name === 'string' ? row.name
      : '';
    const name = raw.trim();
    if (name && name.length <= 120 && !names.includes(name)) names.push(name);
    if (names.length >= 100) break;
  }
  return names.sort();
}

/**
 * @param {{apiKey?: string, baseUrl?: string, preset?: string, model?: string,
 *          tiers?: object, headers?: object, maxTokens?: number, auth?: object,
 *          tokenParam?: string, local?: boolean, modelRequired?: boolean}} config
 */
export function createOpenAICompatProvider(config) {
  const cfg = config || {};
  const preset = cfg.preset ? OPENAI_COMPAT_PRESETS[cfg.preset] : null;
  if (cfg.preset && !preset) throw new ProviderError('config', `unknown preset: ${cfg.preset}`);

  const baseUrl = trimSlash(cfg.baseUrl || (preset && preset.baseUrl) || '');
  const tiers = cfg.tiers || (preset && preset.tiers) || {};
  const {
    apiKey = '', model = null, headers: extraHeaders = {},
    auth = (preset && preset.auth) || AUTH_BEARER,
    tokenParam = (preset && preset.tokenParam) || 'max_tokens',
    maxTokens = (preset && preset.maxTokens) || 4096,
    modelRequired = (preset && preset.modelRequired) || false,
    deadlineMs = (preset && preset.deadlineMs) || DEADLINE_MS,
  } = cfg;
  if (!baseUrl) throw new ProviderError('config', 'OpenAI-compatible provider needs a base URL');

  const label = (preset && preset.label) || baseUrl;
  const local = cfg.local !== undefined ? !!cfg.local : isLoopback(baseUrl);
  if (!apiKey && !local) throw new ProviderError('config', 'this provider needs an API key');
  // `tiers.default` and not just `model`: createProvider resolves the user's choices into a
  // tier map and stops forwarding the scalar, so for a local server the model they typed
  // arrives here as the default tier. A direct caller passing `model` still satisfies it.
  if (modelRequired && !(model || tiers.default)) {
    throw new ProviderError('config',
      `${label} needs a model name — pick one in Settings, or press Check the connection ` +
      'to read the list off the server.');
  }

  const head = (extra = {}) => applyAuth(baseUrl, {
    auth, apiKey, headers: { ...extraHeaders, ...extra },
  }).headers;

  /** True once any request has come back with headers, i.e. CORS is definitely fine. */
  let sawResponse = false;

  /** One code path for both `/models` readers, so they cannot drift apart. */
  async function getJson(path, signal) {
    const res = await fetch(`${baseUrl}${path}`, {
      method: 'GET',
      credentials: 'omit',
      signal: withDeadline(signal, deadlineMs),
      headers: head(),
      ...localFetchOptions(baseUrl),
    }).catch((err) => {
      const aborted = abortError(err, { label: hostLabel(baseUrl), ms: deadlineMs });
      if (aborted) throw aborted;
      throw opaqueFailure(err, sawResponse, local, baseUrl);
    });
    sawResponse = true;
    if (!res.ok) throw await httpError(res, { label: hostLabel(baseUrl) });
    return res.json().catch(() => ({}));
  }

  async function call({ system, prefix, tail }, { modelTier = 'default', json = false, signal } = {}) {
    const resolved = model || tiers[modelTier] || tiers.default;

    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: 'POST',
      credentials: 'omit',
      signal: withDeadline(signal, deadlineMs),
      headers: head({ 'content-type': 'application/json' }),
      ...localFetchOptions(baseUrl),
      body: JSON.stringify({
        model: resolved,
        [tokenParam]: maxTokens,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: `${prefix}\n${tail}` },
        ],
        // json_object rather than a strict json_schema: the turn schema keys `coverage` by
        // dimension id, and strict mode would force the model to restate all seven every
        // turn. parseTurnResult does the real validation anyway. Ollama maps this onto its
        // native `format: json`, so the local path gets the same treatment.
        ...(json ? { response_format: { type: 'json_object' } } : {}),
      }),
    }).catch((err) => {
      const aborted = abortError(err, { label: hostLabel(baseUrl), ms: deadlineMs });
      if (aborted) throw aborted;
      throw opaqueFailure(err, sawResponse, local, baseUrl);
    });

    sawResponse = true;
    if (!res.ok) throw await httpError(res, { label: hostLabel(baseUrl) });

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
    label,
    note: preset && preset.note,
    sample: (parts, opts = {}) => withRetry(() => call(parts, opts), { signal: opts.signal }),
    sampleJson: async (parts, opts = {}) => {
      const out = await withRetry(() => call(parts, { ...opts, json: true }), { signal: opts.signal });
      return { ...out, json: extractJson(out.text) };
    },
    /** What the server will actually answer to, rather than what we guessed it might. */
    listModels: async (signal) => parseModelList(await getJson('/models', signal)),
    /**
     * `GET /models` is the right probe: unlike `/chat/completions` it answers a bad key
     * with a CORS-visible 401, so we can tell "wrong key" from "unreachable".
     */
    validateKey: async (signal) => { await getJson('/models', signal); return true; },
  };
}

/**
 * A fetch that rejects without ever producing a response is either a CORS refusal, a
 * blocked local-network request or a dead network, and the browser deliberately refuses to
 * tell us which. Guess usefully, and for a local server name all three causes — the second
 * one is new, and nobody guesses it unprompted.
 */
function opaqueFailure(err, sawResponse, local, baseUrl) {
  if (local) {
    return new ProviderError('network',
      `could not reach ${baseUrl}. Three things do this: the server is not running; it ` +
      'has not been told to allow this page’s origin (Ollama wants OLLAMA_ORIGINS set ' +
      'and then a restart, because it reads that at startup); or the browser refused the ' +
      'request to your local network, which Chrome asks about the first time and Safari ' +
      'refuses outright from an https:// page.',
      { cause: err });
  }
  if (!sawResponse) {
    return new ProviderError('auth',
      'the request was blocked before a reply came back — usually a rejected API key. ' +
      'Re-check the key in Settings.', { cause: err });
  }
  return new ProviderError('network', `network error talking to ${baseUrl}: ${err.message}`, { cause: err });
}

const hostLabel = (u) => { try { return new URL(u).host; } catch { return u; } };
