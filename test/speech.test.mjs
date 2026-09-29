import test from 'node:test';
import assert from 'node:assert/strict';

import * as speech from '../src/voice/speak.js';
import { createSpeechOutput } from '../src/voice/output.js';
import { validateSpeechAudio } from '../src/providers/tts.js';
import { ProviderError } from '../src/providers/errors.js';

const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

function nativeFixture(t, voices = [{ voiceURI: 'device-en', lang: 'en-US', default: true }]) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const savedWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  const savedUtterance = Object.getOwnPropertyDescriptor(globalThis, 'SpeechSynthesisUtterance');
  const listeners = new Set();
  const spoken = [];
  let current = null;
  class Utterance {
    constructor(text) { this.text = text; }
  }
  const synth = {
    voices, spoken, listeners, cancels: 0, speaking: false, startEvent: true,
    getVoices() { return this.voices; },
    addEventListener(type, listener) { if (type === 'voiceschanged') listeners.add(listener); },
    removeEventListener(type, listener) { if (type === 'voiceschanged') listeners.delete(listener); },
    changed() { for (const listener of [...listeners]) listener(); },
    speak(utterance) {
      current = utterance;
      spoken.push(utterance);
      this.speaking = true;
      if (this.startEvent) utterance.onstart?.();
    },
    cancel() {
      this.cancels++;
      this.speaking = false;
      const utterance = current;
      current = null;
      utterance?.onerror?.({ error: 'interrupted' });
    },
    end() {
      const utterance = current;
      current = null;
      this.speaking = false;
      utterance?.onend?.();
    },
    fail(error) { current?.onerror?.({ error }); },
  };
  Object.defineProperty(globalThis, 'window', {
    value: { speechSynthesis: synth, SpeechSynthesisUtterance: Utterance }, configurable: true,
  });
  Object.defineProperty(globalThis, 'SpeechSynthesisUtterance', {
    value: Utterance, configurable: true,
  });
  t.after(() => {
    speech.cancelSpeech();
    if (savedWindow) Object.defineProperty(globalThis, 'window', savedWindow);
    else delete globalThis.window;
    if (savedUtterance) Object.defineProperty(globalThis, 'SpeechSynthesisUtterance', savedUtterance);
    else delete globalThis.SpeechSynthesisUtterance;
  });
  return synth;
}

test('native completion, interruption and engine failure are distinct results', async (t) => {
  const synth = nativeFixture(t);
  const done = speech.speak('Read this question.');
  await flush();
  synth.end();
  assert.equal((await done)?.status, 'spoken');

  const cancelled = speech.speak('Cancel this question.');
  await flush();
  speech.cancelSpeech();
  assert.equal((await cancelled)?.status, 'cancelled');

  const failed = speech.speak('An unavailable voice.');
  await flush();
  synth.fail('not-allowed');
  const result = await failed;
  assert.equal(result?.status, 'failed');
  assert.match(result.error.message, /not.allowed|gesture/i);
});

test('native output stays preparing until onstart, then speaks until the 220ms utterance ends', async (t) => {
  const synth = nativeFixture(t);
  synth.startEvent = false;
  const speak = synth.speak.bind(synth);
  synth.speak = (utterance) => {
    speak(utterance);
    setTimeout(() => utterance.onstart?.(), 25);
    setTimeout(() => synth.end(), 220);
  };
  const statuses = [];
  const output = createSpeechOutput({ onStatus: (status) => statuses.push(status) });
  t.after(() => output.dispose());
  let settled = false;
  const pending = output.speak('What is the idea you want to explore?').then((result) => {
    settled = true;
    return result;
  });
  await flush();
  assert.equal(synth.speaking, true);
  assert.equal(settled, false);
  assert.equal(statuses.at(-1)?.phase, 'preparing');
  assert.equal(statuses.at(-1).backend, 'browser');
  t.mock.timers.tick(24);
  await flush();
  assert.equal(statuses.at(-1).phase, 'preparing');
  t.mock.timers.tick(1);
  await flush();
  assert.equal(statuses.at(-1).phase, 'speaking');
  t.mock.timers.tick(194);
  await flush();
  assert.equal(settled, false);
  assert.equal(statuses.at(-1).phase, 'speaking');
  t.mock.timers.tick(1);
  assert.equal((await pending).status, 'spoken');
  assert.equal(statuses.at(-1).phase, 'idle');
  assert.equal(statuses.filter((status) => status.phase === 'speaking').length, 1);
});

test('an accepted native utterance without start or end events never claims to be speaking', async (t) => {
  const synth = nativeFixture(t);
  synth.startEvent = false;
  const phases = [];
  const output = createSpeechOutput({ onStatus: (status) => phases.push(status.phase) });
  t.after(() => output.dispose());
  const pending = output.speak('An engine that never starts.');
  await flush();
  assert.equal(phases.at(-1), 'preparing');
  t.mock.timers.tick(9000);
  const result = await pending;
  assert.equal(result.status, 'failed');
  assert.equal(result.error.code, 'timeout');
  assert.ok(!phases.includes('speaking'));
  assert.equal(phases.at(-1), 'error');
});

test('a delayed native start event neither duplicates speaking nor revives cancelled output', async (t) => {
  const synth = nativeFixture(t);
  synth.startEvent = false;
  const phases = [];
  const output = createSpeechOutput({ onStatus: (status) => phases.push(status.phase) });
  t.after(() => output.dispose());
  const pending = output.speak('Read this question.');
  await flush();
  const lateStart = synth.spoken[0].onstart;
  assert.ok(!phases.includes('speaking'));
  lateStart();
  lateStart();
  assert.equal(phases.filter((phase) => phase === 'speaking').length, 1);
  output.cancel();
  assert.equal((await pending).status, 'cancelled');
  lateStart();
  assert.equal(phases.filter((phase) => phase === 'speaking').length, 1);
  assert.equal(phases.at(-1), 'idle');
});

test('an immediately refused native utterance never reports speaking', async (t) => {
  const synth = nativeFixture(t);
  synth.speak = (utterance) => utterance.onerror({ error: 'not-allowed' });
  const phases = [];
  const output = createSpeechOutput({ onStatus: (status) => phases.push(status.phase) });
  t.after(() => output.dispose());
  const result = await output.speak('Blocked question.');
  assert.equal(result.status, 'failed');
  assert.ok(!phases.includes('speaking'));
  assert.equal(phases.at(-1), 'error');
});

test('native watchdog stops the utterance before resolving a failure', async (t) => {
  const synth = nativeFixture(t);
  const pending = speech.speak('a question nobody will ever hear');
  await flush();
  assert.equal(synth.speaking, true);
  t.mock.timers.tick(9000);
  const result = await pending;
  assert.equal(synth.speaking, false, 'recognition must never overlap a timed-out utterance');
  assert.equal(result?.status, 'failed');
  assert.equal(result.error.code, 'timeout');
});

test('cancellation while voices load removes its listener and prevents later speech', async (t) => {
  const synth = nativeFixture(t, []);
  const pending = speech.speak('Do not speak after cancellation.');
  await flush();
  speech.cancelSpeech();
  await flush();
  assert.equal(synth.listeners.size, 0, 'voice readiness must be cancelled too');
  synth.voices = [{ voiceURI: 'late', lang: 'en-US' }];
  synth.changed();
  t.mock.timers.tick(1000);
  assert.equal((await pending)?.status, 'cancelled');
  assert.equal(synth.spoken.length, 0);
});

test('voice readiness observes late lists, refreshes installations and cleans every listener', async (t) => {
  const synth = nativeFixture(t, []);
  const refreshed = [];
  const unsubscribe = speech.subscribeVoices((voices) => refreshed.push(voices));
  let ready = false;
  const waiting = speech.voicesReady().then((voices) => { ready = true; return voices; });
  synth.changed();
  await flush();
  assert.equal(ready, false, 'an empty voiceschanged event is not readiness');
  const voice = { voiceURI: 'late', lang: 'en-GB', localService: false };
  synth.voices = [voice];
  synth.changed();
  assert.deepEqual(await waiting, [voice]);
  assert.equal(speech.getVoices()[0], voice, 'return actual browser objects');
  assert.equal(synth.listeners.size, 1, 'only the persistent subscription remains');
  synth.voices = [];
  synth.changed();
  assert.deepEqual(refreshed.at(-1), []);
  unsubscribe();
  assert.equal(synth.listeners.size, 0);
  const empty = speech.voicesReady();
  t.mock.timers.tick(1000);
  assert.deepEqual(await empty, []);
  assert.equal(synth.listeners.size, 0, 'timeout removes its listener too');
});

test('cancellable readiness rejects without waiting for its timeout', async (t) => {
  const synth = nativeFixture(t, []);
  const caller = new AbortController();
  const pending = assert.rejects(speech.voicesReady(1000, { signal: caller.signal }),
    (error) => error.code === 'aborted');
  caller.abort();
  await pending;
  assert.equal(synth.listeners.size, 0);
  await assert.rejects(speech.voicesReady(-1), (error) => error.code === 'config');
});

test('a voice-list failure at timeout is reported instead of leaving readiness unsettled', async (t) => {
  const synth = nativeFixture(t, []);
  const pending = speech.speak('This voice list fails late.');
  synth.getVoices = () => { throw new Error('platform voice-list failure'); };
  t.mock.timers.tick(1000);
  const result = await pending;
  assert.equal(result.status, 'failed');
  assert.equal(result.error.code, 'bad_response');
  assert.equal(synth.listeners.size, 0);
});

test('explicit native voice selection overrides local-service and ordering heuristics', async (t) => {
  const local = { voiceURI: 'local', lang: 'en-US', name: 'Premium local', localService: true };
  const chosen = { voiceURI: 'chosen', lang: 'en-GB', name: 'Plain', localService: false };
  const synth = nativeFixture(t, [local, chosen]);
  const pending = speech.speak('Use this voice.', { voiceURI: 'chosen', rate: 0.8 });
  await flush();
  assert.equal(synth.spoken[0].voice, chosen);
  assert.equal(synth.spoken[0].lang, 'en-GB');
  assert.equal(synth.spoken[0].rate, 0.8);
  synth.end();
  const result = await pending;
  assert.equal(result.voiceURI, 'chosen');
  assert.equal(result.voiceFallback, false);
});

test('a vanished selection falls back by exact locale and reports what actually spoke', async (t) => {
  const otherLocale = { voiceURI: 'uk', lang: 'en-GB', default: true, localService: true };
  const exact = { voiceURI: 'us', lang: 'en-US', localService: false };
  const synth = nativeFixture(t, [otherLocale, exact]);
  const statuses = [];
  const pending = speech.speak('Where did my voice go?', {
    voiceURI: 'removed', lang: 'en-US', onStatus: (status) => statuses.push(status),
  });
  await flush();
  assert.equal(synth.spoken[0].voice, exact);
  synth.end();
  const result = await pending;
  assert.equal(result.voiceFallback, true);
  assert.equal(result.requestedVoiceURI, 'removed');
  assert.equal(result.voiceURI, 'us');
  assert.match(statuses[0].message, /unavailable/);
});

test('locale fallback honors the browser default, not whether a voice sounds premium', async (t) => {
  const local = { voiceURI: 'premium', name: 'Premium', lang: 'en-US', localService: true };
  const ordinary = { voiceURI: 'default', name: 'Ordinary', lang: 'en-US', default: true, localService: false };
  const synth = nativeFixture(t, [local, ordinary]);
  const pending = speech.speak('Read with the browser default.');
  await flush();
  assert.equal(synth.spoken[0].voice, ordinary);
  assert.equal(synth.spoken[0].rate, 1.02, 'legacy direct callers retain their default rate');
  synth.end();
  assert.equal((await pending).status, 'spoken');
});

test('a missing locale lets the browser choose rather than forcing an unrelated language', async (t) => {
  const synth = nativeFixture(t, [{ voiceURI: 'other', lang: 'fr-FR' }]);
  const pending = speech.speak('An English question.', { lang: 'en-US', voiceURI: 'gone' });
  await flush();
  assert.equal(synth.spoken[0].voice, undefined);
  assert.equal(synth.spoken[0].lang, 'en-US');
  synth.end();
  const result = await pending;
  assert.equal(result.voiceURI, '');
  assert.equal(result.voiceFallback, true);
});

test('invalid native rates fail without speaking or silently substituting a default', async (t) => {
  const synth = nativeFixture(t);
  for (const rate of [0, -1, 10.1, NaN, Infinity, '1', null]) {
    const result = await speech.speak('Invalid rate.', { rate });
    assert.equal(result.status, 'failed');
    assert.equal(result.error.code, 'config');
  }
  assert.equal(synth.spoken.length, 0);
});

test('primeSpeech submits synchronously and does not double-prime the same synthesizer', (t) => {
  const synth = nativeFixture(t);
  assert.equal(speech.primeSpeech().status, 'primed');
  assert.equal(synth.spoken.length, 1);
  assert.equal(synth.spoken[0].volume, 0);
  speech.primeSpeech();
  assert.equal(synth.spoken.length, 1);
});

test('a native output failure allows the next gesture to prime the engine again', async (t) => {
  const synth = nativeFixture(t);
  speech.primeSpeech();
  const pending = speech.speak('An output permission failure.');
  await flush();
  synth.fail('not-allowed');
  assert.equal((await pending).status, 'failed');
  const before = synth.spoken.length;
  speech.primeSpeech();
  assert.equal(synth.spoken.length, before + 1);
  assert.equal(synth.spoken.at(-1).volume, 0);
});

test('a pre-cancelled native call does not interrupt another active utterance', async (t) => {
  const synth = nativeFixture(t);
  const first = speech.speak('The current question.');
  await flush();
  const caller = new AbortController();
  caller.abort();
  const result = await speech.speak('Do not interrupt.', { signal: caller.signal });
  assert.equal(result.status, 'cancelled');
  assert.equal(synth.speaking, true);
  assert.equal(synth.spoken.length, 1);
  synth.end();
  assert.equal((await first).status, 'spoken');
});

test('a newer call supersedes voice readiness without a late older utterance or cleanup', async (t) => {
  const synth = nativeFixture(t, []);
  const old = speech.speak('The old question.');
  const next = speech.speak('The new question.');
  assert.equal((await old).status, 'cancelled');
  synth.voices = [{ voiceURI: 'loaded', lang: 'en-US' }];
  synth.changed();
  await flush();
  assert.equal(synth.spoken.length, 1);
  assert.equal(synth.spoken[0].text, 'The new question.');
  speech.cancelSpeech();
  assert.equal((await next).status, 'cancelled');
  t.mock.timers.tick(60000);
  assert.equal(synth.spoken.length, 1);
});

test('long speech at slow rates finishes every bounded chunk without a premature watchdog', async (t) => {
  const synth = nativeFixture(t);
  const text = Array.from({ length: 75 }, (_, n) => `word${n}`).join(' ');
  let result;
  const pending = speech.speak(text, { rate: 0.25 }).then((value) => { result = value; });
  for (let i = 0; i < 100 && !result; i++) {
    await flush();
    if (synth.speaking) {
      const utterance = synth.spoken.at(-1);
      assert.equal(utterance.rate, 0.25);
      assert.ok(utterance.text.length <= 30);
      const timeout = speech.speechTimeoutMs(utterance.text, 0.25);
      assert.ok(timeout < 15000);
      t.mock.timers.tick(timeout - 1);
      assert.equal(synth.speaking, true);
      synth.end();
    }
  }
  await pending;
  assert.equal(result.status, 'spoken');
  assert.equal(synth.spoken.map((u) => u.text).join(' '), text);
  t.mock.timers.tick(60000);
  assert.equal(result.status, 'spoken', 'stale watchdogs were removed');
});

test('long unbroken speech is neither dropped nor split through a surrogate pair', async (t) => {
  const synth = nativeFixture(t);
  const text = 'x'.repeat(119) + '\u{1f642}'.repeat(140) + 'ending';
  let result;
  const pending = speech.speak(text).then((value) => { result = value; });
  for (let i = 0; i < 100 && !result; i++) {
    await flush();
    if (synth.speaking) {
      assert.ok(synth.spoken.at(-1).text.length <= 120);
      assert.ok(synth.spoken.at(-1).text.isWellFormed());
      synth.end();
    }
  }
  await pending;
  assert.equal(result.status, 'spoken');
  assert.equal(synth.spoken.map((u) => u.text).join(''), text);
});

test('cancelled long speech cannot advance to its next chunk through a late end event', async (t) => {
  const synth = nativeFixture(t);
  const caller = new AbortController();
  const pending = speech.speak('a long answer '.repeat(70), { signal: caller.signal });
  await flush();
  const lateEnd = synth.spoken[0].onend;
  caller.abort();
  assert.equal((await pending).status, 'cancelled');
  assert.equal(synth.speaking, false);
  lateEnd();
  t.mock.timers.tick(60000);
  await flush();
  assert.equal(synth.spoken.length, 1);
});

test('character and word budgets both scale safely for slow native speech', () => {
  assert.ok(speech.speechTimeoutMs('several words to read', 0.25)
    > speech.speechTimeoutMs('several words to read', 1));
  assert.ok(speech.speechTimeoutMs('x'.repeat(100), 1) > speech.speechTimeoutMs('x', 1));
  const text = 'a '.repeat(80);
  const chunks = speech.splitSpeechText(text, { maxChars: 120, maxWords: 24 });
  assert.ok(chunks.every((part) => part.split(/\s+/).length <= 24));
  assert.equal(chunks.join(' '), text.trim());
  assert.throws(() => speech.splitSpeechText(text, { maxChars: 1 }), (error) => error.code === 'config');
});

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

function wav(samples = 2400) {
  const audio = new ArrayBuffer(44 + samples * 2);
  const view = new DataView(audio);
  const tag = (at, text) => [...text].forEach((c, n) => view.setUint8(at + n, c.charCodeAt(0)));
  tag(0, 'RIFF'); view.setUint32(4, audio.byteLength - 8, true); tag(8, 'WAVE');
  tag(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, 24000, true);
  view.setUint32(28, 48000, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  tag(36, 'data'); view.setUint32(40, samples * 2, true);
  return audio;
}

const bufferFor = (audio = wav()) => {
  const info = validateSpeechAudio(audio);
  return { duration: info.duration, length: info.duration * info.sampleRate, numberOfChannels: info.channels };
};
const response = () => new Response(wav(), { headers: { 'content-type': 'audio/wav' } });

function hostedFixture(t, options = {}) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  t.mock.method(globalThis, 'fetch', () => { throw new Error('A speech test attempted real HTTP.'); });
  const calls = [], events = [], sources = [], nativeCalls = [], statuses = [];
  const listeners = new Set();
  const ctx = {
    state: 'suspended', destination: {}, decodes: 0, closes: 0,
    resume() {
      events.push('resume');
      if (options.resume) return options.resume(this);
      this.state = 'running';
      return Promise.resolve();
    },
    decodeAudioData(audio) {
      this.decodes++;
      return options.decode ? options.decode(audio) : Promise.resolve(bufferFor(audio));
    },
    close() { this.closes++; this.state = 'closed'; events.push('close'); return Promise.resolve(); },
    addEventListener(_, listener) { listeners.add(listener); },
    removeEventListener(_, listener) { listeners.delete(listener); },
    changed() { for (const listener of [...listeners]) listener(); },
    createBufferSource() {
      const source = {
        connected: false, started: false, stopped: false,
        connect() { this.connected = true; },
        disconnect() { this.connected = false; events.push('disconnect'); },
        start() { this.started = true; events.push('start'); },
        stop() { this.stopped = true; events.push('stop'); this.onended?.(); },
        end() { this.onended?.(); },
      };
      sources.push(source);
      return source;
    },
  };
  const native = {
    prime() { events.push('native-prime'); return { status: 'primed', backend: 'browser' }; },
    cancel() { events.push('native-cancel'); },
    supported: () => true,
    async speak(text, opts) {
      nativeCalls.push({ text, opts });
      events.push('native-speak');
      if (options.nativeSpeak) return options.nativeSpeak(text, opts);
      opts.onStatus?.({ phase: 'speaking' });
      return { status: 'spoken', backend: 'browser', voiceURI: 'device',
        lang: opts.lang, rate: opts.rate, voiceFallback: false };
    },
  };
  const output = createSpeechOutput({
    kind: 'openai', apiKey: 'test-only-not-a-real-key', native,
    createAudioContext: () => ctx,
    fetch: (...args) => {
      calls.push(args);
      events.push('fetch');
      return options.fetch ? options.fetch(...args) : Promise.resolve(response());
    },
    onStatus(status) { statuses.push(status); options.onStatus?.(status); },
    ...options.config,
  });
  t.after(async () => { await output.dispose(); });
  return { output, ctx, calls, events, sources, nativeCalls, statuses, listeners };
}

async function until(predicate) {
  for (let i = 0; i < 100; i++) {
    if (predicate()) return;
    await flush();
  }
  assert.fail('expected an operation to become ready without timers or network');
}

test('hosted construction is inactive, and priming resumes synchronously without a request', async (t) => {
  const h = hostedFixture(t);
  assert.deepEqual(h.events, []);
  assert.equal(h.output.supported(), true, 'capability is not a successful compatibility check');
  const primed = h.output.prime();
  assert.deepEqual(h.events, ['native-prime', 'resume']);
  assert.equal(h.calls.length, 0);
  assert.equal((await primed).status, 'primed');
});

test('unprimed compatibility checks fail without requesting audio or falling back', async (t) => {
  const h = hostedFixture(t);
  await assert.rejects(h.output.check('Preview.'), (error) => error.code === 'config');
  assert.equal(h.calls.length, 0);
  assert.equal(h.nativeCalls.length, 0);
  assert.equal(h.statuses.at(-1).phase, 'error');
});

test('a successful check waits for playback end, not merely for a valid response or decode', async (t) => {
  const h = hostedFixture(t);
  const primed = h.output.prime();
  const pending = h.output.check('Preview.');
  let finished = false;
  pending.then(() => { finished = true; });
  assert.equal((await primed).status, 'primed');
  await until(() => h.sources.length === 1);
  assert.equal(finished, false);
  assert.equal(h.sources[0].connected, true);
  assert.deepEqual(h.statuses.map((s) => s.phase), ['preparing', 'speaking']);
  h.sources[0].end();
  const result = await pending;
  assert.deepEqual(result, { status: 'spoken', backend: 'openai' });
  assert.equal(h.sources[0].connected, false);
  assert.equal(h.listeners.size, 0);
  assert.equal(h.statuses.at(-1).phase, 'idle');
});

test('a preview cannot pass by falling back after an HTTP or decode failure', async (t) => {
  const h = hostedFixture(t, { fetch: async () => new Response('denied', { status: 401 }) });
  await h.output.prime();
  await assert.rejects(h.output.check('Preview.'), (error) => error.code === 'auth');
  await assert.rejects(h.output.check('Try again.'), (error) => error.code === 'auth');
  assert.equal(h.calls.length, 2, 'each explicit check still tests the selected backend');
  assert.equal(h.nativeCalls.length, 0);
  assert.equal(h.statuses.at(-1).phase, 'error');
  assert.ok(h.statuses.every((status) => !/Using browser/.test(status.message || '')));
});

test('preview errors and fallback status never echo a supplied speech credential', async (t) => {
  const key = 'mock-speech-credential-for-error-tests';
  const h = hostedFixture(t, {
    config: { apiKey: key },
    fetch: async () => new Response(JSON.stringify({ error: { message: `Invalid key: ${key}` } }), {
      status: 401, headers: { 'content-type': 'application/json' },
    }),
  });
  await h.output.prime();
  await assert.rejects(h.output.check('Preview.'), (error) => {
    assert.equal(error.code, 'auth');
    assert.equal(String(error).includes(key), false);
    assert.equal(error.stack.includes(key), false);
    return true;
  });
  const fallback = await h.output.speak('The next question.');
  assert.equal(fallback.status, 'spoken');
  assert.equal(fallback.backend, 'browser');
  assert.equal(JSON.stringify(h.statuses).includes(key), false);
  assert.equal(h.statuses.filter((status) => /Using browser/.test(status.message || '')).length, 1);
});

test('one hosted failure downgrades all remaining chunks and calls, with one explanation', async (t) => {
  const h = hostedFixture(t, { fetch: async () => { throw new TypeError('blocked'); } });
  await h.output.prime();
  const text = 'long-unbroken-text'.repeat(80);
  const first = await h.output.speak(text);
  assert.equal(first.status, 'spoken');
  assert.equal(first.backend, 'browser');
  assert.equal(h.nativeCalls.map((call) => call.text).join(''), text);
  assert.ok(h.nativeCalls.length > 1);
  await h.output.speak('The next question.');
  assert.equal(h.calls.length, 1);
  assert.equal(h.statuses.filter((status) => /Using browser/.test(status.message || '')).length, 1);
  assert.equal(h.nativeCalls.at(-1).opts.voiceURI, '');
});

test('priming a downgraded session reports its usable native backend, not an unused audio failure', async (t) => {
  let resumes = 0;
  const h = hostedFixture(t, {
    fetch: async () => { throw new TypeError('blocked'); },
    resume(ctx) {
      if (++resumes === 1) { ctx.state = 'running'; return Promise.resolve(); }
      return Promise.reject(new DOMException('hosted output blocked', 'NotAllowedError'));
    },
  });
  await h.output.prime();
  await h.output.speak('Fall back once.');
  const errors = h.statuses.filter((status) => status.phase === 'error').length;
  const primed = await h.output.prime();
  await flush();
  assert.equal(primed.status, 'primed');
  assert.equal(primed.backend, 'browser');
  assert.equal(h.statuses.filter((status) => status.phase === 'error').length, errors);
  assert.equal((await h.output.speak('Keep using native.')).backend, 'browser');
  assert.equal(h.calls.length, 1);
});

test('hosted audio is fetched sequentially with no speculative next chunk or text loss', async (t) => {
  const h = hostedFixture(t);
  await h.output.prime();
  const text = 'a'.repeat(5000);
  const pending = h.output.speak(text, { rate: 0.75 });
  let done = false;
  pending.then(() => { done = true; });
  let ended = 0;
  for (let i = 0; i < 50 && !done; i++) {
    await flush();
    if (h.sources.length > ended) {
      assert.equal(h.calls.length, ended + 1);
      assert.equal(h.sources.length, ended + 1);
      h.sources[ended++].end();
    }
  }
  assert.equal((await pending).status, 'spoken');
  assert.equal(h.calls.map(([, init]) => JSON.parse(init.body).input).join(''), text);
  assert.ok(h.calls.every(([, init]) => {
    const body = JSON.parse(init.body);
    return body.input.length <= 350 && body.speed === 0.75;
  }));
  assert.equal(h.nativeCalls.length, 0);
});

test('invalid per-call rate fails before HTTP and does not downgrade the selected backend', async (t) => {
  const h = hostedFixture(t);
  await h.output.prime();
  for (const rate of [0, null, '1', NaN]) {
    const result = await h.output.speak('Question.', { rate });
    assert.equal(result.status, 'failed');
    assert.equal(result.error.code, 'config');
  }
  assert.equal(h.calls.length, 0);
  assert.equal(h.nativeCalls.length, 0);
});

test('caller cancellation during fetch aborts HTTP and ignores the late response', async (t) => {
  const late = deferred();
  const h = hostedFixture(t, { fetch: () => late.promise });
  await h.output.prime();
  const caller = new AbortController();
  const pending = h.output.speak('Not after exit.', { signal: caller.signal });
  await until(() => h.calls.length === 1);
  caller.abort();
  assert.equal((await pending).status, 'cancelled');
  assert.equal(h.calls[0][1].signal.aborted, true);
  late.resolve(response());
  await flush();
  assert.equal(h.ctx.decodes, 0);
  assert.equal(h.sources.length, 0);
  assert.equal(h.nativeCalls.length, 0);
  assert.ok(h.statuses.every((status) => status.phase !== 'error'));
});

test('controller cancellation during decode settles promptly and never plays a late buffer', async (t) => {
  const late = deferred();
  const h = hostedFixture(t, { decode: () => late.promise });
  await h.output.prime();
  const pending = h.output.speak('Not after pause.');
  await until(() => h.ctx.decodes === 1);
  h.output.cancel();
  assert.equal((await pending).status, 'cancelled');
  late.resolve(bufferFor());
  await flush();
  assert.equal(h.sources.length, 0);
  assert.equal(h.nativeCalls.length, 0);
});

test('cancelled checks reject with aborted, rather than succeeding via silence or fallback', async (t) => {
  const late = deferred();
  const h = hostedFixture(t, { decode: () => late.promise });
  await h.output.prime();
  const pending = assert.rejects(h.output.check('Preview.'), (error) => error.code === 'aborted');
  await until(() => h.ctx.decodes === 1);
  h.output.cancel();
  await pending;
  late.reject(new Error('late decode failure'));
  await flush();
  assert.equal(h.nativeCalls.length, 0);
});

test('cancelling playback stops and disconnects before the awaited call returns', async (t) => {
  const h = hostedFixture(t);
  await h.output.prime();
  const pending = h.output.speak('Interrupt this audio.');
  await until(() => h.sources.length === 1);
  const lateEnd = h.sources[0].onended;
  h.output.cancel();
  assert.equal(h.sources[0].stopped, true);
  assert.equal(h.sources[0].connected, false);
  assert.equal((await pending).status, 'cancelled');
  lateEnd();
  t.mock.timers.tick(60000);
  await flush();
  assert.equal(h.sources.length, 1);
  assert.equal(h.nativeCalls.length, 0);
  assert.equal(h.listeners.size, 0);
});

test('a playback watchdog stops/disconnects before native fallback and reports it only once', async (t) => {
  const h = hostedFixture(t);
  await h.output.prime();
  const pending = h.output.speak('An engine with no ended callback.');
  await until(() => h.sources.length === 1);
  t.mock.timers.tick(2099);
  assert.equal(h.sources[0].stopped, false);
  t.mock.timers.tick(1);
  const result = await pending;
  assert.equal(result.status, 'spoken');
  assert.equal(result.backend, 'browser');
  assert.ok(h.events.indexOf('disconnect') < h.events.indexOf('native-speak'));
  assert.equal(h.sources[0].stopped, true);
  await h.output.speak('Another question.');
  assert.equal(h.calls.length, 1);
});

test('decode exceptions, invalid buffers and lost output policy fail no-fallback checks', async (t) => {
  const h = hostedFixture(t, { decode: async () => { throw new DOMException('invalid data', 'EncodingError'); } });
  await h.output.prime();
  await assert.rejects(h.output.check('Preview.'), (error) => error.code === 'bad_response');
  assert.equal(h.sources.length, 0);
  assert.equal(h.nativeCalls.length, 0);
});

test('empty or truncated decoded buffers cannot count as a successful preview', async (t) => {
  const h = hostedFixture(t, { decode: async () => ({ duration: 0, length: 0, numberOfChannels: 1 }) });
  await h.output.prime();
  await assert.rejects(h.output.check('Preview.'), (error) => error.code === 'bad_response');
  assert.equal(h.sources.length, 0);
});

test('decoding that never completes is bounded and a late success is ignored', async (t) => {
  const late = deferred();
  const h = hostedFixture(t, { decode: () => late.promise });
  await h.output.prime();
  const pending = assert.rejects(h.output.check('Preview.'), (error) => error.code === 'timeout');
  await until(() => h.ctx.decodes === 1);
  t.mock.timers.tick(10000);
  await pending;
  late.resolve(bufferFor());
  await flush();
  assert.equal(h.sources.length, 0);
  assert.equal(h.nativeCalls.length, 0);
});

test('autoplay rejection is reported without claiming a real policy check or sending text', async (t) => {
  const h = hostedFixture(t, { resume: async () => { throw new DOMException('blocked', 'NotAllowedError'); } });
  assert.equal((await h.output.prime()).status, 'failed');
  await assert.rejects(h.output.check('Preview.'), (error) => error instanceof ProviderError);
  assert.equal(h.calls.length, 0);
  assert.equal(h.nativeCalls.length, 0);
});

test('an unresolved autoplay resume is bounded rather than hanging or consuming audio requests', async (t) => {
  const h = hostedFixture(t, { resume: () => new Promise(() => {}) });
  const pending = h.output.prime();
  t.mock.timers.tick(2500);
  assert.equal((await pending).status, 'failed');
  await assert.rejects(h.output.check('Preview.'), (error) => error.code === 'timeout');
  assert.equal(h.calls.length, 0);
});

test('suspended playback is stopped before native fallback, with no output overlap', async (t) => {
  const h = hostedFixture(t);
  await h.output.prime();
  const pending = h.output.speak('Interrupted by the browser.');
  await until(() => h.sources.length === 1);
  h.ctx.state = 'suspended';
  h.ctx.changed();
  const result = await pending;
  assert.equal(result.backend, 'browser');
  assert.equal(h.sources[0].stopped, true);
  assert.equal(h.sources[0].connected, false);
  assert.ok(h.events.indexOf('disconnect') < h.events.indexOf('native-speak'));
});

test('cancelling in the degradation notification never starts fallback audio', async (t) => {
  let output;
  const h = hostedFixture(t, {
    fetch: async () => { throw new TypeError('blocked'); },
    onStatus(status) { if (/Using browser/.test(status.message || '')) output.cancel(); },
  });
  output = h.output;
  await output.prime();
  assert.equal((await output.speak('Pause now.')).status, 'cancelled');
  assert.equal(h.nativeCalls.length, 0);
  assert.equal(h.sources.length, 0);
});

test('fallback failure is a failed result, never success that would start recognition', async (t) => {
  const h = hostedFixture(t, {
    fetch: async () => { throw new TypeError('blocked'); },
    nativeSpeak: async () => ({ status: 'failed', backend: 'browser',
      error: new ProviderError('config', 'No native speech.') }),
  });
  await h.output.prime();
  const result = await h.output.speak('Neither output works.');
  assert.equal(result.status, 'failed');
  assert.equal(result.backend, 'browser');
  assert.match(result.error.message, /No native speech/);
  assert.equal(h.statuses.at(-1).phase, 'error');
});

test('native cancellation remains cancellation even when no optional error was supplied', async (t) => {
  const h = hostedFixture(t, {
    config: { kind: 'browser' },
    nativeSpeak: async () => ({ status: 'cancelled', backend: 'browser' }),
  });
  assert.equal((await h.output.speak('Cancelled.')).status, 'cancelled');
  assert.equal(h.calls.length, 0);
});

test('a superseded decode cannot play over or clear the newer operation', async (t) => {
  const firstDecode = deferred();
  let n = 0;
  const h = hostedFixture(t, { decode: (audio) => ++n === 1 ? firstDecode.promise : Promise.resolve(bufferFor(audio)) });
  await h.output.prime();
  const first = h.output.speak('Old question.');
  await until(() => h.ctx.decodes === 1);
  const second = h.output.speak('New question.');
  assert.equal((await first).status, 'cancelled');
  await until(() => h.sources.length === 1);
  firstDecode.resolve(bufferFor());
  await flush();
  assert.equal(h.sources.length, 1);
  h.output.cancel();
  assert.equal((await second).status, 'cancelled');
  assert.equal(h.sources[0].stopped, true);
});

test('dispose aborts pending output and closes the context exactly once', async (t) => {
  const late = deferred();
  const h = hostedFixture(t, { fetch: () => late.promise });
  await h.output.prime();
  const pending = h.output.speak('No speech after teardown.');
  await until(() => h.calls.length === 1);
  const disposal = h.output.dispose();
  assert.equal(h.calls[0][1].signal.aborted, true);
  assert.equal(h.ctx.closes, 1);
  assert.equal((await pending).status, 'cancelled');
  assert.equal((await disposal).status, 'disposed');
  assert.equal(h.output.supported(), false);
  assert.equal((await h.output.prime()).status, 'failed');
  assert.equal((await h.output.speak('No.')).status, 'failed');
  await assert.rejects(h.output.check('No.'), (error) => error.code === 'config');
  await h.output.dispose();
  assert.equal(h.ctx.closes, 1);
  late.resolve(response());
  await flush();
  assert.equal(h.sources.length, 0);
  assert.equal(h.nativeCalls.length, 0);
});

test('default output remains browser-only even when a speech key is present', async (t) => {
  const synth = nativeFixture(t);
  const output = createSpeechOutput({
    apiKey: 'not-a-real-key',
    fetch() { assert.fail('default output must not make hosted requests'); },
    browserVoice: 'device-en', rate: 0.9,
  });
  t.after(() => output.dispose());
  const primed = output.prime();
  const pending = output.speak('A native question.');
  await primed;
  await flush();
  assert.equal(synth.spoken.at(-1).voice.voiceURI, 'device-en');
  assert.equal(synth.spoken.at(-1).rate, 0.9);
  synth.end();
  const result = await pending;
  assert.equal(result.status, 'spoken');
  assert.equal(result.backend, 'browser');
});
