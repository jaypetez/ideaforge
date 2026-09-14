import test from 'node:test';
import assert from 'node:assert/strict';

import { extractJson } from '../src/providers/json.js';
import {
  ProviderError, codeForStatus, isRetryable, retryAfterMs, withRetry, RETRYABLE,
} from '../src/providers/errors.js';
import {
  OPENAI_COMPAT_PRESETS, createOpenAICompatProvider, parseModelList,
} from '../src/providers/openaiCompat.js';
import { createAnthropicProvider, ANTHROPIC_TIERS } from '../src/providers/anthropic.js';
import { AUTH_BEARER, AUTH_NONE, applyAuth, isLoopback } from '../src/providers/http.js';
import { PROVIDER_CHOICES, createProvider } from '../src/providers/index.js';

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

test('a local provider blames the server and the origin, never the key it has none of', async () => {
  const real = globalThis.fetch;
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
  try {
    const p = createOpenAICompatProvider({ preset: 'ollama', model: 'qwen3' });
    await assert.rejects(
      () => p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' }),
      (e) => e.code === 'network'
        // Naming OLLAMA_ORIGINS is the whole point: it is the cause nobody guesses
        // unprompted, and the one that needs a restart rather than a page reload.
        && /OLLAMA_ORIGINS/.test(e.message)
        && !/API key/.test(e.message)
    );
  } finally { globalThis.fetch = real; }
});

test('every hosted preset maps all three tiers and declares how it authenticates', () => {
  for (const [name, preset] of Object.entries(OPENAI_COMPAT_PRESETS)) {
    if (preset.local) continue;
    assert.doesNotThrow(() => new URL(preset.baseUrl), `${name} base URL`);
    for (const tier of ['quick', 'default', 'complex']) {
      assert.ok(preset.tiers[tier], `${name} is missing the ${tier} tier`);
    }
    assert.ok(preset.auth && preset.auth.scheme, `${name} does not say how it authenticates`);
    assert.ok(preset.tokenParam, `${name} does not name its output-budget parameter`);
  }
});

test('every local preset asks for a model instead of guessing one', () => {
  for (const [name, preset] of Object.entries(OPENAI_COMPAT_PRESETS)) {
    if (!preset.local) continue;
    assert.ok(isLoopback(preset.baseUrl), `${name} is marked local but is not on loopback`);
    assert.equal(preset.tiers, null, `${name} still guesses a model; llama3.2 404s for most people`);
    assert.ok(preset.modelRequired, `${name} must require a model`);
    assert.ok(preset.discoverModels, `${name} should be able to read its own model list`);
  }
});

test('a hosted provider refuses to be constructed without a key', () => {
  assert.throws(() => createOpenAICompatProvider({ preset: 'openai' }), /needs an API key/);
  assert.doesNotThrow(
    () => createOpenAICompatProvider({ preset: 'ollama', model: 'qwen3' }),
    'local needs no key'
  );
});

// ─────────────────────────────────────── the auth descriptor, and what counts as local
test('applyAuth speaks every scheme, and never sets a content-type', () => {
  const bearer = applyAuth('https://x/v1', { auth: AUTH_BEARER, apiKey: 'k' });
  assert.equal(bearer.headers.authorization, 'Bearer k');
  assert.equal(bearer.url, 'https://x/v1');

  const header = applyAuth('https://x/v1', {
    auth: {
      scheme: 'header', header: 'x-api-key',
      extraHeaders: { 'anthropic-version': '2023-06-01' },
    },
    apiKey: 'sk',
  });
  assert.equal(header.headers['x-api-key'], 'sk');
  assert.equal(header.headers['anthropic-version'], '2023-06-01');
  assert.equal(header.headers.authorization, undefined);

  // 'query' is the one scheme nothing here uses yet. It is four lines, and it is what
  // makes "any token type" true rather than "either of the two we happened to need".
  const query = applyAuth('https://x/v1/models', {
    auth: { scheme: 'query', param: 'key' }, apiKey: 'abc',
  });
  assert.equal(new URL(query.url).searchParams.get('key'), 'abc');
  assert.deepEqual(query.headers, {});

  assert.deepEqual(applyAuth('https://x', { auth: AUTH_NONE, apiKey: 'k' }).headers, {});

  // The transcription endpoint posts FormData and must set its own multipart boundary,
  // which is why this helper returns credential headers and nothing else.
  for (const scheme of [AUTH_BEARER, AUTH_NONE]) {
    assert.equal(
      applyAuth('https://x', { auth: scheme, apiKey: 'k' }).headers['content-type'],
      undefined
    );
  }
});

test('isLoopback parses the URL rather than matching its prefix', () => {
  for (const yes of ['http://localhost:11434/v1', 'http://127.0.0.1:1234', 'http://127.2.3.4',
                     'http://[::1]:11434/v1', 'https://localhost:8443',
                     'http://app.localhost:3000']) {
    assert.ok(isLoopback(yes), `${yes} should be loopback`);
  }
  // The prefix match this replaces said yes to localhost.evil.com, which made a remote
  // host the app would have trusted with no key and blamed its failures on CORS.
  for (const no of ['http://192.168.1.50:11434', 'http://10.0.0.1', 'http://0.0.0.0:11434',
                    'https://api.openai.com/v1', 'http://notlocalhost.com',
                    'http://localhost.evil.com/v1', '', 'not a url']) {
    assert.ok(!isLoopback(no), `${no} should not be loopback`);
  }
});

test('a base URL that is not on this machine is refused, and the message says why', async () => {
  await assert.rejects(
    () => createProvider({ kind: 'custom', baseUrl: 'https://evil.example/v1', model: 'm' }),
    (e) => e.code === 'config' && /not on this machine/.test(e.message)
  );
  await assert.doesNotReject(
    () => createProvider({ kind: 'custom', baseUrl: 'http://127.0.0.1:11434/v1', model: 'm' })
  );
});

test('a local provider will not be built without a model, because guessing one 404s', async () => {
  await assert.rejects(
    () => createProvider({ kind: 'custom', baseUrl: 'http://localhost:11434/v1' }),
    (e) => e.code === 'config' && /needs a model name/.test(e.message)
  );
});

test('needsKey is false for exactly the local choices', () => {
  const byId = Object.fromEntries(PROVIDER_CHOICES.map((c) => [c.id, c]));
  for (const id of ['ollama', 'lmstudio', 'custom', 'artifact']) {
    assert.equal(byId[id].needsKey, false, `${id} should not demand a key`);
  }
  for (const id of ['anthropic', 'openai', 'groq', 'openrouter']) {
    assert.equal(byId[id].needsKey, true, `${id} should demand a key`);
  }
});

// ───────────────────────────────────────────────────── the output-budget parameter
test('the OpenAI preset sends max_completion_tokens — gpt-5 rejects max_tokens outright', async () => {
  await withFetch(
    () => jsonResponse({ choices: [{ message: { content: '{"a":1}' }, finish_reason: 'stop' }] }),
    async (seen) => {
      const p = createOpenAICompatProvider({ preset: 'openai', apiKey: 'k' });
      await p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' });
      assert.equal(seen[0].body.max_tokens, undefined, 'max_tokens 400s on every gpt-5 model');
      assert.ok(seen[0].body.max_completion_tokens > 4096,
        'a reasoning model spends budget before writing, so renaming alone is not the fix');
    }
  );
});

test('the presets that only understand max_tokens still send max_tokens', async () => {
  for (const preset of ['groq', 'openrouter']) {
    await withFetch(
      () => jsonResponse({ choices: [{ message: { content: '{"a":1}' }, finish_reason: 'stop' }] }),
      async (seen) => {
        const p = createOpenAICompatProvider({ preset, apiKey: 'k' });
        await p.sampleJson({ system: 'S', prefix: 'P', tail: 'T' });
        assert.ok(seen[0].body.max_tokens, `${preset} should send max_tokens`);
        assert.equal(seen[0].body.max_completion_tokens, undefined);
      }
    );
  }
});

// ────────────────────────────────────────────────────────── reading the model list
test('parseModelList survives every shape a local server might answer with', () => {
  assert.deepEqual(parseModelList({ data: [{ id: 'b' }, { id: 'a' }] }), ['a', 'b']);
  assert.deepEqual(parseModelList({ models: [{ name: 'qwen3:8b' }] }), ['qwen3:8b']);
  assert.deepEqual(parseModelList(['z', 'y']), ['y', 'z']);
  assert.deepEqual(parseModelList({ data: [{ id: 'a' }, { id: 'a' }] }), ['a'], 'deduped');
  assert.deepEqual(parseModelList({ data: [{ id: '  spaced  ' }] }), ['spaced']);

  // Never trust the list: the same rule parseTurnResult applies to the model.
  for (const junk of [null, undefined, {}, [], 'nope', { data: null },
                      { data: [{}, { id: '' }] }, { data: [{ id: 'x'.repeat(200) }] }]) {
    assert.deepEqual(parseModelList(junk), [], `${JSON.stringify(junk)} should yield nothing`);
  }
  const many = { data: Array.from({ length: 500 }, (_, i) => ({ id: `m${i}` })) };
  assert.equal(parseModelList(many).length, 100, 'a runaway list is capped');
});

test('listModels reads the list off the server it is pointed at', async () => {
  await withFetch(
    () => jsonResponse({ data: [{ id: 'qwen3:8b' }, { id: 'llama3.2' }] }),
    async (seen) => {
      const p = createOpenAICompatProvider({ preset: 'ollama', model: 'qwen3:8b' });
      assert.deepEqual(await p.listModels(), ['llama3.2', 'qwen3:8b']);
      assert.equal(seen[0].url, 'http://localhost:11434/v1/models');
    }
  );
});
