// The credential keyring, and the migration into it.
//
// None of this touches IndexedDB or WebCrypto: the shape logic is pure and separable from
// the storage it eventually lands in, which is what lets it be tested here in
// milliseconds. The encryption itself is exercised in test/browser/storage.browser.mjs,
// against a real origin, because there is no honest way to fake a non-extractable key.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  CREDENTIALS_VERSION, emptyKeyring, migrateCredentials, credsFor, withCreds, withStt,
  hasAnyKey, maskKey,
} from '../src/store/secrets.js';

test('a v1 blob becomes a keyring filed under the provider it belonged to', () => {
  const ring = migrateCredentials({ kind: 'groq', apiKey: 'gsk-1', model: 'whisper' });
  assert.equal(ring.version, CREDENTIALS_VERSION);
  assert.equal(ring.active, 'groq');
  assert.equal(ring.byKind.groq.apiKey, 'gsk-1');
  assert.equal(ring.byKind.groq.model, 'whisper');
});

test('a v1 blob keeps its dictation key, which lived in different fields', () => {
  const ring = migrateCredentials({ kind: 'openai', apiKey: 'sk-1', sttKind: 'groq', sttKey: 'gsk-2' });
  assert.deepEqual(ring.stt, { kind: 'groq', apiKey: 'gsk-2' });
});

test('a v1 blob with no dictation provider migrates without inventing one', () => {
  const ring = migrateCredentials({ kind: 'anthropic', apiKey: 'sk-ant' });
  assert.deepEqual(ring.stt, { kind: null, apiKey: '' });
});

test('a v2 keyring passes through the migration untouched', () => {
  const ring = withCreds(emptyKeyring(), 'groq', { apiKey: 'gsk-1' });
  assert.deepEqual(migrateCredentials(ring), ring);
});

test('junk decrypts into an empty keyring rather than refusing to boot', () => {
  // An app that will not start over a credential it could simply re-ask for is worse than
  // one that asks. Every one of these has to survive.
  for (const junk of [null, undefined, [], 'nope', 42, {}, { version: 99 }, { byKind: 'no' }]) {
    const ring = migrateCredentials(junk);
    assert.equal(ring.version, CREDENTIALS_VERSION, `${JSON.stringify(junk)} should migrate`);
    assert.deepEqual(ring.byKind, {});
  }
});

test('switching provider keeps the previous provider’s key — the whole point of v2', () => {
  // v1 stored one record, so this sequence used to leave the Groq key gone for good.
  let ring = withCreds(emptyKeyring(), 'groq', { apiKey: 'gsk-1' });
  ring = withCreds(ring, 'anthropic', { apiKey: 'sk-ant' });
  ring = withCreds(ring, 'groq', { apiKey: 'gsk-1' });

  assert.equal(credsFor(ring, 'groq').apiKey, 'gsk-1');
  assert.equal(credsFor(ring, 'anthropic').apiKey, 'sk-ant');
  assert.equal(ring.active, 'groq');
});

test('a local provider’s address and model are remembered like any other credential', () => {
  const ring = withCreds(emptyKeyring(), 'ollama', {
    baseUrl: 'http://127.0.0.1:11434/v1', model: 'qwen3:8b',
  });
  assert.equal(credsFor(ring, 'ollama').baseUrl, 'http://127.0.0.1:11434/v1');
  assert.equal(credsFor(ring, 'ollama').model, 'qwen3:8b');
  // Nothing secret in it, so there is nothing to offer to forget.
  assert.equal(hasAnyKey(ring), false);
});

test('hasAnyKey sees a dictation key even when no provider holds one', () => {
  assert.equal(hasAnyKey(withStt(emptyKeyring(), { kind: 'groq', apiKey: 'gsk-1' })), true);
  assert.equal(hasAnyKey(emptyKeyring()), false);
});

test('credsFor an unknown provider returns empty strings, not undefined', () => {
  const c = credsFor(emptyKeyring(), 'nothing-here');
  assert.deepEqual(c, { kind: 'nothing-here', apiKey: '', baseUrl: '', model: '' });
});

test('maskKey never reveals the middle of a key', () => {
  const masked = maskKey('sk-ant-api03-must-never-appear-1234');
  assert.ok(!masked.includes('must-never'));
  assert.equal(maskKey('short'), '••••');
});
