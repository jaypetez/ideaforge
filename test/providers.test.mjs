import test from 'node:test';
import assert from 'node:assert/strict';

import { extractJson } from '../src/providers/json.js';
import {
  ProviderError, codeForStatus, isRetryable, retryAfterMs, withRetry, RETRYABLE,
} from '../src/providers/errors.js';
import { OPENAI_COMPAT_PRESETS, createOpenAICompatProvider } from '../src/providers/openaiCompat.js';
import { createAnthropicProvider, ANTHROPIC_TIERS } from '../src/providers/anthropic.js';

// ──────────────────────────────────────────────── getting an object back
test('extractJson reads a bare object, a fenced one, and one buried in prose', () => {
  const want = { question: 'What breaks first?' };
  assert.deepEqual(extractJson('{"question":"What breaks first?"}'), want);
  assert.deepEqual(extractJson('```json\n{"question":"What breaks first?"}\n```'), want);
  assert.deepEqual(extractJson('```\n{"question":"What breaks first?"}\n```'), want);
  assert.deepEqual(extractJson('Here you go:\n{"question":"What breaks first?"}\nHope that helps!'), want);
});

test('extractJson tolerates a trailing comma', () => {
  assert.deepEqual(extractJson('{"a":1,}'), { a: 1 });
});

test('extractJson keeps braces that live inside a string value', () => {
  assert.deepEqual(extractJson('{"q":"what about {this}?"}'), { q: 'what about {this}?' });
});

test('extractJson refuses anything that is not an object', () => {
  for (const bad of ['', '   ', null, undefined, 'no json here', '[1,2,3]', '"a string"']) {
    assert.throws(() => extractJson(bad), (e) => e instanceof ProviderError && e.code === 'bad_response');
  }
});

// ─────────────────────────────────────────────────── the error taxonomy
test('HTTP statuses map onto the taxonomy', () => {
  assert.equal(codeForStatus(401), 'auth');
  assert.equal(codeForStatus(403), 'auth');
  assert.equal(codeForStatus(429), 'rate_limit');
  assert.equal(codeForStatus(529), 'overloaded');
  assert.equal(codeForStatus(503), 'overloaded');
  assert.equal(codeForStatus(500), 'overloaded');
  assert.equal(codeForStatus(400), 'bad_response');
});

test('only transport failures are retryable — an auth error must reach the user', () => {
  assert.deepEqual([...RETRYABLE].sort(), ['network', 'overloaded', 'rate_limit']);
  assert.equal(isRetryable(new ProviderError('rate_limit', 'x')), true);
  assert.equal(isRetryable(new ProviderError('auth', 'x')), false);
  assert.equal(isRetryable(new Error('plain')), false);
});

test('retry-after is read as seconds or as a date', () => {
  const h = (v) => ({ get: () => v });
  assert.equal(retryAfterMs(h('3')), 3000);
  assert.equal(retryAfterMs(h(null)), null);
  assert.ok(retryAfterMs(h(new Date(Date.now() + 5000).toUTCString())) > 3000);
});

test('withRetry gives up immediately on a non-retryable error', async () => {
  let calls = 0;
  await assert.rejects(() => withRetry(async () => {
    calls++;
    throw new ProviderError('auth', 'bad key');
  }), /bad key/);
  assert.equal(calls, 1);
});

test('withRetry retries a transport failure and returns the eventual success', async () => {
  let calls = 0;
  const out = await withRetry(async () => {
    calls++;
    if (calls < 3) throw new ProviderError('overloaded', 'busy', { retryAfterMs: 0 });
    return 'ok';
  }, { baseMs: 0 });
  assert.equal(out, 'ok');
  assert.equal(calls, 3);
});

test('withRetry stops after the attempt budget', async () => {
  let calls = 0;
  await assert.rejects(() => withRetry(async () => {
    calls++;
    throw new ProviderError('network', 'down', { retryAfterMs: 0 });
  }, { attempts: 3, baseMs: 0 }), /down/);
  assert.equal(calls, 3);
});

// ────────────────────────────────────── the adapters, with a fake fetch
/** Swap in a fetch that records the request and replays a canned response. */
function withFetch(handler, fn) {
  const real = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (url, init) => {
    seen.push({ url, init, body: init && init.body ? JSON.parse(init.body) : null });
    return handler(seen.length);
  };
  return Promise.resolve(fn(seen)).finally(() => { globalThis.fetch = real; });
}

const jsonResponse = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  statusText: 'x',
  headers: { get: () => null },
  json: async () => body,
});

test('the Anthropic adapter sends the header that makes browser CORS work at all', async () => {
  await withFetch(
    () => jsonResponse({ content: [{ type: 'text', text: '{"question":"go on?"}' }], usage: {} }),
    async (seen) => {
      const p = createAnthropicProvider({ apiKey: 'sk-test' });
      const out = await p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' });

      const { init, body } = seen[0];
      assert.equal(seen[0].url, 'https://api.anthropic.com/v1/messages');
      assert.equal(init.headers['anthropic-dangerous-direct-browser-access'], 'true');
      assert.equal(init.headers['anthropic-version'], '2023-06-01');
      assert.equal(init.headers['x-api-key'], 'sk-test');
      assert.notEqual(init.credentials, 'include', 'allow-credentials with * is rejected by browsers');
      assert.equal(body.model, ANTHROPIC_TIERS.default);
      assert.equal(body.system[0].text, 'S');
      assert.equal(body.messages[0].content[0].text, 'P');
      assert.equal(body.messages[0].content[1].text, 'T');
      assert.deepEqual(out.json, { question: 'go on?' });
    }
  );
});

test('the Anthropic adapter only spends a cache breakpoint once the prefix is worth caching', async () => {
  const reply = () => jsonResponse({ content: [{ type: 'text', text: '{"a":1}' }] });
  await withFetch(reply, async (seen) => {
    const p = createAnthropicProvider({ apiKey: 'k' });
    await p.sampleJson({ system: 'S', prefix: 'short', tail: 'T' });
    assert.equal(seen[0].body.messages[0].content[0].cache_control, undefined);

    await p.sampleJson({ system: 'S', prefix: 'x'.repeat(20 * 1024), tail: 'T' });
    assert.deepEqual(seen[1].body.messages[0].content[0].cache_control, { type: 'ephemeral' });
  });
});

test('an Anthropic refusal is an error, not an empty question', async () => {
  await withFetch(
    () => jsonResponse({ stop_reason: 'refusal', content: [] }),
    async () => {
      const p = createAnthropicProvider({ apiKey: 'k' });
      await assert.rejects(() => p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' }), /declined/);
    }
  );
});

test('a 401 tells the user to check the key rather than leaking a status code', async () => {
  await withFetch(
    () => jsonResponse({ error: { message: 'invalid x-api-key' } }, 401),
    async () => {
      const p = createAnthropicProvider({ apiKey: 'nope' });
      await assert.rejects(
        () => p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' }),
        (e) => e.code === 'auth' && /check the API key/.test(e.message)
      );
    }
  );
});

test('the OpenAI-compatible adapter asks for JSON and sends a bearer token', async () => {
  await withFetch(
    () => jsonResponse({ choices: [{ message: { content: '{"question":"and then?"}' }, finish_reason: 'stop' }] }),
    async (seen) => {
      const p = createOpenAICompatProvider({ preset: 'groq', apiKey: 'gsk-test' });
      const out = await p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' });

      assert.equal(seen[0].url, 'https://api.groq.com/openai/v1/chat/completions');
      assert.equal(seen[0].init.headers.authorization, 'Bearer gsk-test');
      assert.deepEqual(seen[0].body.response_format, { type: 'json_object' });
      assert.equal(seen[0].body.messages[0].role, 'system');
      assert.deepEqual(out.json, { question: 'and then?' });
    }
  );
});

test('a truncated OpenAI reply is reported rather than silently half-parsed', async () => {
  await withFetch(
    () => jsonResponse({ choices: [{ message: { content: '{"question":"cut' }, finish_reason: 'length' }] }),
    async () => {
      const p = createOpenAICompatProvider({ preset: 'openai', apiKey: 'k' });
      await assert.rejects(() => p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' }), /cut off/);
    }
  );
});

test('an opaque first-call failure is reported as a probable bad key, not as "offline"', async () => {
  const real = globalThis.fetch;
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
  try {
    const p = createOpenAICompatProvider({ preset: 'openai', apiKey: 'bad' });
    await assert.rejects(
      () => p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' }),
      (e) => e.code === 'auth' && /rejected API key/.test(e.message)
    );
  } finally { globalThis.fetch = real; }
});

test('a local provider blames CORS rather than the key, because it has none', async () => {
  const real = globalThis.fetch;
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
  try {
    const p = createOpenAICompatProvider({ preset: 'ollama' });
    await assert.rejects(
      () => p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' }),
      (e) => e.code === 'network' && /CORS/.test(e.message)
    );
  } finally { globalThis.fetch = real; }
});

test('every preset is a usable base URL with all three tiers mapped', () => {
  for (const [name, preset] of Object.entries(OPENAI_COMPAT_PRESETS)) {
    assert.doesNotThrow(() => new URL(preset.baseUrl), `${name} base URL`);
    for (const tier of ['quick', 'default', 'complex']) {
      assert.ok(preset.tiers[tier], `${name} is missing the ${tier} tier`);
    }
  }
});

test('a hosted provider refuses to be constructed without a key', () => {
  assert.throws(() => createOpenAICompatProvider({ preset: 'openai' }), /needs an API key/);
  assert.doesNotThrow(() => createOpenAICompatProvider({ preset: 'ollama' }), 'local needs no key');
});
