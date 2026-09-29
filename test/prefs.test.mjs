import test from 'node:test';
import assert from 'node:assert/strict';
import { loadPrefs, savePrefs, speechPreferences } from '../src/store/prefs.js';

test('speech preferences migrate without a voice or rate override', () => {
  assert.deepEqual(speechPreferences({ trigger: 'finished', handsFree: true }), {
    speechVoice: '', speechRate: 1,
  });
});

test('a selected voice and supported rate are retained', () => {
  assert.deepEqual(speechPreferences({ speechVoice: 'device:voice', speechRate: 0.85 }), {
    speechVoice: 'device:voice', speechRate: 0.85,
  });
});

test('invalid or unbounded speech preferences cannot reach the audio engine', () => {
  for (const rate of [NaN, Infinity, -1, 0, 0.74, 1.26, 10, '1', null]) {
    assert.equal(speechPreferences({ speechRate: rate }).speechRate, 1);
  }
  assert.equal(speechPreferences({ speechVoice: {} }).speechVoice, '');
  assert.equal(speechPreferences({ speechVoice: 'x'.repeat(900) }).speechVoice.length, 512);
});

function withStorage(value, run) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { value, configurable: true });
  try { run(); } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original);
    else delete globalThis.localStorage;
  }
}

test('hosted speech requires a literal consent flag and revocation is synchronous', () => {
  let stored = null;
  withStorage({
    getItem: () => stored,
    setItem: (_key, value) => { stored = value; },
  }, () => {
    assert.equal(loadPrefs().hostedSpeechAllowed, false);
    for (const value of ['true', 1, null]) {
      stored = JSON.stringify({ hostedSpeechAllowed: value });
      assert.equal(loadPrefs().hostedSpeechAllowed, false);
    }
    assert.equal(savePrefs({ hostedSpeechAllowed: true }), true);
    assert.equal(loadPrefs().hostedSpeechAllowed, true);
    assert.equal(savePrefs({ hostedSpeechAllowed: false }), true);
    assert.equal(loadPrefs().hostedSpeechAllowed, false);
    savePrefs({ speechVoice: 'device:voice', speechRate: 0.85 });
    assert.equal(loadPrefs().hostedSpeechAllowed, false);
  });
});

test('preference writes report failure so consent changes cannot claim a saved choice', () => {
  withStorage({
    getItem: () => null,
    setItem: () => { throw new Error('storage full'); },
  }, () => assert.equal(savePrefs({ hostedSpeechAllowed: false }), false));
});
