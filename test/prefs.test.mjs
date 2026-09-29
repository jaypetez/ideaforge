import test from 'node:test';
import assert from 'node:assert/strict';
import { speechPreferences } from '../src/store/prefs.js';

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
