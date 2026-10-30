import test from 'node:test';
import assert from 'node:assert/strict';
import { completedAudio } from './fixtures/audio-completion.mjs';

const natural = (overrides = {}) => ({
  started: true, ended: true, trustedEnd: true, stops: 0, destination: true, disconnected: true,
  duration: 3, sampleRate: 44100, renderQuantumSize: 128,
  startClock: 0.11029478458049886, endClock: 3.1085714285714285,
  ...overrides,
});

test('a trusted natural end in the final render quantum satisfies the audio clock proof', () => {
  // #298: the main-CI trace was 76 frames short at 44100 Hz, not truncated playback.
  const source = natural();
  assert.ok(source.endClock < source.startClock + source.duration);
  assert.equal(completedAudio(source), true);
});

test('natural completion at an exact buffer boundary can precede that quantum clock advance', () => {
  // Native AudioWorklet evidence at 48000 Hz: ended arrived while the last 128 frames
  // were being rendered, before the context advanced its clock to the next block.
  const source = natural({
    sampleRate: 48000, startClock: 6.013333333333334, endClock: 9.010666666666667,
  });
  assert.equal(completedAudio(source), true);
});

test('a trusted-looking end from an earlier quantum or half-played buffer is rejected', () => {
  const source = natural();
  for (const endClock of [
    source.endClock - source.renderQuantumSize / source.sampleRate,
    source.startClock + source.duration / 2,
    source.startClock,
  ]) {
    assert.equal(completedAudio({ ...source, endClock }), false, `${endClock}`);
  }
});

test('the completion bound uses the context sample rate and render size, not fixed milliseconds', () => {
  const source = natural({
    sampleRate: 48000, renderQuantumSize: 256, startClock: 0, duration: 3.001,
    endClock: 143872 / 48000,
  });
  assert.equal(completedAudio(source), true);
  assert.equal(completedAudio({ ...source, renderQuantumSize: 128 }), false);
  assert.equal(completedAudio({ ...source, sampleRate: 96000 }), false);
});

test('enough elapsed audio clock cannot replace natural completion, no-stop or graph evidence', () => {
  const source = natural({ endClock: 4 });
  assert.equal(completedAudio(source), true);
  for (const missing of [
    { started: false }, { ended: false }, { trustedEnd: false }, { stops: 1 },
    { destination: false }, { disconnected: false },
  ]) {
    assert.equal(completedAudio({ ...source, ...missing }), false, JSON.stringify(missing));
  }
});

test('missing or invalid context clock evidence cannot prove completion', () => {
  const source = natural({ endClock: 4 });
  for (const invalid of [
    { sampleRate: undefined }, { sampleRate: 0 }, { sampleRate: NaN },
    { renderQuantumSize: undefined }, { renderQuantumSize: 0 }, { renderQuantumSize: 1.5 },
    { startClock: -1 }, { endClock: Infinity }, { endClock: null },
    { duration: 0 }, { duration: NaN },
  ]) {
    assert.equal(completedAudio({ ...source, ...invalid }), false, JSON.stringify(invalid));
  }
  assert.equal(completedAudio(null), false);
});
