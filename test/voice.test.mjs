import test from 'node:test';
import assert from 'node:assert/strict';

import { createSilenceGate, rmsOf, DEFAULTS } from '../src/voice/vad.js';
import { createTranscriber, STT_PRESETS, MAX_AUDIO_BYTES } from '../src/voice/transcribe.js';

// ───────────────────────────────────────────────── the silence gate
// Hands-free mode lives or dies on this: too eager and it cuts you off mid-sentence,
// too slow and every answer ends with an awkward wait.

/** Drive the gate with a scripted loudness profile at a fixed sample interval. */
function drive(gate, samples, stepMs = 50, startMs = 0) {
  const seen = [];
  let t = startMs;
  for (const rms of samples) {
    seen.push(gate.push(rms, t));
    t += stepMs;
  }
  return seen;
}

const quiet = (n, level = 0.002) => Array(n).fill(level);
/** Speech is not a constant tone: word boundaries dip almost to the room floor. */
const speech = (n, peak = 0.2, floor = 0.004) =>
  Array.from({ length: n }, (_, i) => (i % 7 === 6 ? floor : peak * (0.75 + 0.25 * ((i % 3) / 2))));

test('silence before anyone speaks is never mistaken for a finished answer', () => {
  const gate = createSilenceGate();
  // Twelve seconds of someone thinking, far longer than the silence timeout.
  const seen = drive(gate, quiet(240));
  assert.ok(seen.every((v) => v === 'idle'));
  assert.equal(gate.state().heardSpeech, false);
});

test('a pause after enough speech ends the answer', () => {
  const gate = createSilenceGate();
  drive(gate, quiet(10));                                        // 0.5s of room
  drive(gate, speech(30), 50, 500);                              // 1.5s of talking
  const after = drive(gate, quiet(60), 50, 2000);                // 3s of silence
  assert.ok(after.includes('done'), 'the gate should have closed');
});

test('a throat-clear is not an answer', () => {
  const gate = createSilenceGate({ minSpeechMs: 700 });
  drive(gate, quiet(10));
  drive(gate, speech(4), 50, 500);                               // 200ms of noise
  const after = drive(gate, quiet(80), 50, 700);                 // 4s of silence
  assert.ok(!after.includes('done'), 'too little speech to count as a reply');
});

test('a mid-sentence pause does not end the answer', () => {
  const gate = createSilenceGate({ silenceMs: 1800 });
  drive(gate, quiet(10));
  drive(gate, speech(30), 50, 500);
  // A one-second beat, the kind people leave while thinking of the next word.
  const gap = drive(gate, quiet(20), 50, 2000);
  assert.ok(!gap.includes('done'));
  assert.equal(drive(gate, speech(4), 50, 3000)[0], 'speech', 'and speech resumes cleanly');
});

test('the gate measures the room rather than trusting an absolute number', () => {
  // A train carriage: ambient hiss louder than a quiet room's whole speech threshold.
  const gate = createSilenceGate();
  const ambient = drive(gate, quiet(40, 0.05));
  assert.ok(!ambient.includes('speech'), 'ambient noise must not read as speech');
  assert.equal(gate.state().heardSpeech, false);

  const talking = drive(gate, speech(20, 0.4, 0.05), 50, 2000);
  assert.ok(talking.includes('speech'), 'real speech above that floor still registers');
});

test('a recording that opens mid-sentence still finds the floor', () => {
  // No ambient lead-in at all — the mic opens and they are already talking. The gaps
  // between words are what rescue it.
  const gate = createSilenceGate();
  const seen = drive(gate, speech(40));
  assert.ok(seen.includes('speech'), 'speech-first must not calibrate the gate deaf');
});

test('total silence cannot calibrate the threshold down to nothing', () => {
  const gate = createSilenceGate();
  drive(gate, Array(60).fill(0));
  assert.equal(gate.state().heardSpeech, false);
  // A whisper barely above zero must still not trip it, because minThreshold holds.
  assert.equal(drive(gate, [DEFAULTS.minThreshold * 0.5], 50, 3000)[0], 'idle');
});

test('a stuck microphone stops at the hard ceiling instead of recording forever', () => {
  const gate = createSilenceGate({ maxMs: 1000 });
  const seen = drive(gate, speech(40));   // 2s of talking that never pauses long enough
  assert.ok(seen.includes('done'));
});

test('the gate latches: once done it stays done', () => {
  const gate = createSilenceGate({ maxMs: 100 });
  drive(gate, speech(10));
  assert.equal(gate.push(0.9, 99999), 'done');
});

test('rmsOf measures deviation from the 128 midpoint, not raw amplitude', () => {
  assert.equal(rmsOf(new Uint8Array([128, 128, 128, 128])), 0, 'silence is centred, not zero');
  assert.equal(rmsOf(new Uint8Array([])), 0);
  assert.ok(rmsOf(new Uint8Array([0, 255, 0, 255])) > 0.9, 'full swing is near 1');
});

// ───────────────────────────────────────────────────── transcription

function withFetch(handler, fn) {
  const real = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (url, init) => { seen.push({ url, init }); return handler(seen.length); };
  return Promise.resolve(fn(seen)).finally(() => { globalThis.fetch = real; });
}

const okJson = (body) => ({
  ok: true, status: 200, statusText: 'OK', headers: { get: () => null }, json: async () => body,
});
const errJson = (status, body) => ({
  ok: false, status, statusText: 'x', headers: { get: () => null }, json: async () => body,
});

test('a transcription posts multipart audio to the right endpoint', async () => {
  await withFetch(() => okJson({ text: '  a dictated answer  ' }), async (seen) => {
    const t = createTranscriber({ kind: 'groq', apiKey: 'gsk-x', language: 'en' });
    const text = await t.transcribe(new Blob(['audio'], { type: 'audio/webm' }));

    assert.equal(seen[0].url, 'https://api.groq.com/openai/v1/audio/transcriptions');
    assert.equal(seen[0].init.headers.authorization, 'Bearer gsk-x');
    assert.equal(seen[0].init.headers['content-type'], undefined,
      'FormData must set its own boundary');
    assert.equal(text, 'a dictated answer', 'the transcript is trimmed');

    const form = seen[0].init.body;
    assert.equal(form.get('model'), STT_PRESETS.groq.model);
    assert.equal(form.get('language'), 'en');
  });
});

test('the question primes the recogniser with this turn’s vocabulary', async () => {
  await withFetch(() => okJson({ text: 'x' }), async (seen) => {
    const t = createTranscriber({ kind: 'groq', apiKey: 'k', prompt: 'What did the run sheet cost?' });
    await t.transcribe(new Blob(['a'], { type: 'audio/webm' }));
    assert.equal(seen[0].init.body.get('prompt'), 'What did the run sheet cost?');
  });
});

test('the upload filename matches the codec, because the endpoint dispatches on it', async () => {
  await withFetch(() => okJson({ text: 'x' }), async (seen) => {
    const t = createTranscriber({ kind: 'openai', apiKey: 'k' });
    for (const [mime, name] of [
      ['audio/webm;codecs=opus', 'answer.webm'],
      ['audio/ogg;codecs=opus', 'answer.ogg'],
      ['audio/mp4', 'answer.mp4'],
      ['audio/wav', 'answer.wav'],
    ]) {
      await t.transcribe(new Blob(['a'], { type: mime }));
      assert.equal(seen[seen.length - 1].init.body.get('file').name, name, mime);
    }
  });
});

test('an oversized recording is refused before it is uploaded', async () => {
  let called = false;
  const real = globalThis.fetch;
  globalThis.fetch = async () => { called = true; return okJson({ text: '' }); };
  try {
    const t = createTranscriber({ kind: 'groq', apiKey: 'k' });
    const huge = { size: MAX_AUDIO_BYTES + 1, type: 'audio/webm' };
    await assert.rejects(() => t.transcribe(huge), /over the 25 MB limit/);
    assert.equal(called, false, 'never spend the upload');
  } finally { globalThis.fetch = real; }
});

test('a rejected transcription key says so instead of blaming the network', async () => {
  await withFetch(() => errJson(401, { error: { message: 'bad key' } }), async () => {
    const t = createTranscriber({ kind: 'openai', apiKey: 'nope' });
    await assert.rejects(
      () => t.transcribe(new Blob(['a'], { type: 'audio/webm' })),
      (e) => e.code === 'auth' && /check the transcription key/.test(e.message)
    );
  });
});

test('an opaque CORS failure is reported as a key problem, not as being offline', async () => {
  const real = globalThis.fetch;
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
  try {
    const t = createTranscriber({ kind: 'openai', apiKey: 'bad' });
    await assert.rejects(
      () => t.transcribe(new Blob(['a'], { type: 'audio/webm' })),
      (e) => e.code === 'auth' && /rejected key/.test(e.message)
    );
  } finally { globalThis.fetch = real; }
});

test('a transcriber refuses to exist without a key or a model', () => {
  assert.throws(() => createTranscriber({ kind: 'groq' }), /needs an API key/);
  assert.throws(() => createTranscriber({ apiKey: 'k' }), /base URL and a model/);
});

test('every STT preset is a real URL with a model', () => {
  for (const [name, p] of Object.entries(STT_PRESETS)) {
    assert.doesNotThrow(() => new URL(p.baseUrl), name);
    assert.ok(p.model, `${name} has no model`);
    assert.ok(p.note, `${name} has no cost note for the UI`);
  }
});
