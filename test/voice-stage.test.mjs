import test from 'node:test';
import assert from 'node:assert/strict';

import { createVoiceStage, normalizeVoiceLevel } from '../src/ui/voice-stage.js';

test('the voice stage can be imported without a browser', () => {
  assert.equal(typeof createVoiceStage, 'function');
});

test('a silent microphone remains a genuine zero rather than an invented signal', () => {
  assert.equal(normalizeVoiceLevel(0), 0);
  assert.equal(normalizeVoiceLevel(-0), 0);
});

test('an unavailable microphone sample is distinguishable from silence', () => {
  for (const sample of [undefined, null, NaN, Infinity, -Infinity, '', '0.1', {}, []]) {
    assert.equal(normalizeVoiceLevel(sample), null, String(sample));
  }
});

test('finite microphone samples cannot escape the visual range', () => {
  for (const sample of [-Number.MAX_VALUE, -1, -.01]) {
    assert.equal(normalizeVoiceLevel(sample), 0);
  }
  for (const sample of [.25, 1, 50, Number.MAX_VALUE]) {
    assert.equal(normalizeVoiceLevel(sample), 1);
  }
});

test('quiet and conversational inputs produce distinct proportional levels', () => {
  assert.equal(normalizeVoiceLevel(.005), .02);
  assert.equal(normalizeVoiceLevel(.025), .1);
  assert.equal(normalizeVoiceLevel(.125), .5);
  assert.equal(normalizeVoiceLevel(.2), .8);
});

test('increasing measured energy never decreases the displayed level', () => {
  let previous = 0;
  for (let step = 0; step <= 1000; step++) {
    const value = normalizeVoiceLevel(step / 1000);
    assert.ok(Number.isFinite(value) && value >= previous && value <= 1);
    previous = value;
  }
});
