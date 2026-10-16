import test from 'node:test';
import assert from 'node:assert/strict';
import { createSession } from '../src/core/session.js';
import { seedTurn, submitAnswer, runTurn } from '../src/runtime/turn.js';
import { runSynthesis } from '../src/runtime/synthesize.js';
import { createAnthropicProvider } from '../src/providers/anthropic.js';
import { createOpenAICompatProvider } from '../src/providers/openaiCompat.js';
import { ProviderError } from '../src/providers/errors.js';
import { inferenceDestination } from '../src/providers/index.js';
import { createInferenceConsent } from '../src/store/inference-consent.js';
import { loadPrefs, savePrefs } from '../src/store/prefs.js';

const denied = () => new ProviderError('consent', 'Review the inference agreement in Settings.');
const parts = { system: 'system', prefix: 'private idea', tail: 'private answer' };
const answered = () => submitAnswer(seedTurn(createSession({ id: 'consent-test', now: 1 })), {
  text: 'A quiet notebook for remembering delivery instructions.', now: 2,
});

test('denied inference authorization is not converted into a bank question', async () => {
  const error = denied();
  const session = answered();
  await assert.rejects(runTurn(session, {
    provider: { async sampleJson() { throw error; } }, now: 3,
  }), (actual) => actual === error);
  assert.equal(session.pending, null);
  assert.equal(session.turns.length, 1);
});

test('denied synthesis is not converted into a failed completed write-up', async () => {
  const error = denied();
  const session = answered();
  await assert.rejects(runSynthesis(session, {
    provider: { async sampleJson() { throw error; } }, now: 3,
  }), (actual) => actual === error);
  assert.equal(session.status, 'interviewing');
});

for (const kind of ['anthropic', 'openai-compat']) {
  test(`${kind} checks authorization before an inference HTTP request`, async () => {
    const original = globalThis.fetch;
    let requests = 0;
    globalThis.fetch = async () => {
      requests++;
      return new Response(JSON.stringify({
        content: [{ type: 'text', text: '{}' }],
        choices: [{ message: { content: '{}' } }],
      }));
    };
    try {
      const beforeSample = () => { throw denied(); };
      const provider = kind === 'anthropic'
        ? createAnthropicProvider({ apiKey: 'scripted-not-a-key', beforeSample })
        : createOpenAICompatProvider({
          baseUrl: 'http://127.0.0.1:11434/v1', local: true, model: 'scripted', beforeSample,
        });
      await assert.rejects(provider.sampleJson(parts), (error) => error.code === 'consent');
      assert.equal(requests, 0, 'no interview bytes reached fetch');
    } finally {
      globalThis.fetch = original;
    }
  });
}

async function withPreferences(run, initial = {}) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const storage = { value: JSON.stringify(initial), failRead: false, failWrite: false };
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem() { if (storage.failRead) throw new Error('scripted unreadable preferences'); return storage.value; },
    setItem(_key, value) { if (storage.failWrite) throw new Error('scripted full disk'); storage.value = value; },
  } });
  let grants = 0;
  const retired = [];
  const gate = createInferenceConsent({ newGrant: () => `test-grant-${++grants}`, onRetire: (reason) => retired.push(reason) });
  try { await run({ gate, storage, retired }); } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original);
    else delete globalThis.localStorage;
  }
}

test('missing, legacy and malformed inference preferences default to no permission', async () => {
  for (const value of [{}, { handsFree: true, hostedSpeechAllowed: true },
    { inferenceDestination: 'groq', inferenceGrant: true },
    { inferenceDestination: 'groq', inferenceGrant: {} }]) {
    await withPreferences(({ gate }) => {
      gate.select('groq', 'groq');
      assert.equal(gate.status('groq').allowed, false);
      assert.throws(() => gate.require('groq'), (error) => error.code === 'consent');
    }, value);
  }
});

test('agreement persists separately from voice preferences and survives a fresh owner', async () => {
  await withPreferences(({ gate }) => {
    gate.select('groq', 'groq');
    const permit = gate.accept('groq');
    assert.equal(gate.require('groq', permit).grant, permit.grant);
    savePrefs({ handsFree: true, hostedSpeechAllowed: true, speechVoice: 'device:voice' });
    const reloaded = createInferenceConsent();
    reloaded.select('groq', 'groq');
    assert.equal(reloaded.require('groq').grant, permit.grant);
    assert.equal(loadPrefs().hostedSpeechAllowed, true);
  });
});

test('kind switch and switch back require a fresh grant; model-only selection does not', async () => {
  await withPreferences(({ gate }) => {
    gate.select('groq', 'groq');
    const original = gate.accept('groq');
    gate.select('groq', 'groq');
    assert.deepEqual(gate.require('groq', original), original);
    gate.select('openai', 'openai');
    gate.select('groq', 'groq');
    assert.throws(() => gate.require('groq'), (error) => error.code === 'consent');
    const next = gate.accept('groq');
    assert.notEqual(next.grant, original.grant);
    assert.throws(() => gate.require('groq', original), (error) => error.code === 'consent');
    assert.equal(gate.require('groq', next).grant, next.grant);
  });
});

test('local identity is validated and normalized, never a model, key or raw invalid input', async () => {
  const a = inferenceDestination({ kind: 'ollama', baseUrl: 'http://LOCALHOST:11434/v1/' });
  assert.equal(a.id, inferenceDestination({ kind: 'ollama', baseUrl: 'http://localhost:11434/v1', model: 'other' }).id);
  const b = inferenceDestination({ kind: 'ollama', baseUrl: 'http://127.0.0.1:11993/v1' });
  for (const baseUrl of ['https://example.com/v1', 'http://secret:password@localhost:11434/v1',
    'http://localhost:11434/v1?key=secret', 'ftp://localhost/v1']) {
    assert.throws(() => inferenceDestination({ kind: 'ollama', baseUrl }), (error) => error.code === 'config');
  }
  await withPreferences(({ gate }) => {
    gate.select(a.kind, a.id);
    const permit = gate.accept(a.id);
    gate.select(a.kind, null);
    assert.equal(gate.require(a.id, permit).grant, permit.grant, 'invalid keystrokes do not invent a new destination');
    gate.select(b.kind, b.id);
    assert.throws(() => gate.require(b.id), (error) => error.code === 'consent');
    gate.select(a.kind, a.id);
    assert.throws(() => gate.require(a.id), (error) => error.code === 'consent');
  });
});

test('failed acceptance, read failure and failed withdrawal are explicitly fail-closed', async () => {
  await withPreferences(({ gate, storage, retired }) => {
    gate.select('groq', 'groq');
    storage.failWrite = true;
    assert.throws(() => gate.accept('groq'), /could not be saved/);
    assert.equal(gate.status('groq').allowed, false);
    storage.failWrite = false;
    const permit = gate.accept('groq');
    storage.failRead = true;
    assert.throws(() => gate.require('groq', permit), /could not be read/);
    storage.failRead = false;
    assert.throws(() => gate.require('groq'), (error) => error.code === 'consent', 'recovering storage is not fresh consent');
    gate.accept('groq');
    storage.failWrite = true;
    assert.throws(() => gate.withdraw(), /withdrawal could not be saved/);
    assert.equal(gate.status('groq').allowed, false);
    assert.ok(retired.length >= 3);
  });
});

test('withdrawal and re-agreement cannot revive an old cached provider lease', async () => {
  await withPreferences(async ({ gate }) => {
    gate.select('groq', 'groq');
    const old = gate.accept('groq');
    gate.withdraw();
    gate.accept('groq');
    const original = globalThis.fetch;
    let requests = 0;
    globalThis.fetch = async () => { requests++; return new Response('{}'); };
    try {
      const provider = createOpenAICompatProvider({
        apiKey: 'scripted', preset: 'groq', beforeSample: () => gate.require('groq', old),
      });
      await assert.rejects(provider.sampleJson(parts), (error) => error.code === 'consent');
      assert.equal(requests, 0);
    } finally { globalThis.fetch = original; }
  });
});

test('HTTP retries recheck permission instead of resending after withdrawal', async () => {
  await withPreferences(async ({ gate }) => {
    gate.select('groq', 'groq');
    const permit = gate.accept('groq');
    const original = globalThis.fetch;
    let requests = 0;
    globalThis.fetch = async () => {
      requests++;
      gate.withdraw();
      return new Response('{}', { status: 503, headers: { 'retry-after': '0' } });
    };
    try {
      const provider = createOpenAICompatProvider({
        apiKey: 'scripted', preset: 'groq', beforeSample: () => gate.require('groq', permit),
      });
      await assert.rejects(provider.sampleJson(parts), (error) => error.code === 'consent');
      assert.equal(requests, 1, 'only the request admitted before withdrawal left the adapter');
    } finally { globalThis.fetch = original; }
  });
});
