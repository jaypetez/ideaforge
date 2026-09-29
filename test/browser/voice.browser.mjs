// Voice. Chrome runs these with a synthesised microphone (--use-fake-device-for-media-stream),
// so the recorder, the silence gate and the backend picker are all exercised for real.
//
// The most important check here is the dead-recogniser one. Inside an installed iOS
// home-screen app, webkitSpeechRecognition exists, constructs, and start() returns without
// throwing — and then no event ever fires. Feature detection reports "supported" on the one
// platform where it does not work, so the fallback has to be driven by a behavioural probe.
// That probe is the thing most likely to be "simplified" into an `in window` check by
// someone who has never seen the failure, which is exactly why it is pinned here.

import { probeWebSpeech, forgetVerdict, cachedVerdict, webSpeechPresent } from '../../src/voice/webspeech.js';
import { createRecorder, micSupported } from '../../src/voice/recorder.js';
import { createVoice, forgetStarvedMeter } from '../../src/voice/index.js';
import { ttsSupported } from '../../src/voice/speak.js';
import { endsWithTrigger } from '../../src/core/driving.js';

/** Stand in for a recogniser that reports itself present and then does nothing. */
class SilentRecognition {
  constructor() { this.lang = ''; this.continuous = false; this.interimResults = false; }
  start() { /* the iPhone lie: returns cleanly, fires nothing, ever */ }
  stop() {}
  abort() {}
}

/** Stand in for Edge, which fails immediately with a network error. */
class NetworkErrorRecognition extends SilentRecognition {
  start() { setTimeout(() => this.onerror && this.onerror({ error: 'network' }), 5); }
}

/** Stand in for a user tapping Block on the microphone prompt. */
class RefusedRecognition extends SilentRecognition {
  start() { setTimeout(() => this.onerror && this.onerror({ error: 'not-allowed' }), 5); }
}

/**
 * navigator.permissions is a readonly WebIDL attribute, so it needs defineProperty for the
 * same reason window.speechSynthesis does — assigning to it throws in module code.
 */
function withPermission(state, fn) {
  const real = Object.getOwnPropertyDescriptor(Navigator.prototype, 'permissions')
    || Object.getOwnPropertyDescriptor(navigator, 'permissions');
  Object.defineProperty(navigator, 'permissions', {
    value: { query: async () => typeof state === 'function' ? state() : ({ state }) },
    configurable: true,
  });
  return Promise.resolve(fn()).finally(() => {
    delete navigator.permissions;
    if (real && !('permissions' in navigator)) Object.defineProperty(navigator, 'permissions', real);
  });
}

function withRecognition(Impl, fn) {
  const realSR = window.SpeechRecognition;
  const realWK = window.webkitSpeechRecognition;
  if (Impl) { window.SpeechRecognition = Impl; window.webkitSpeechRecognition = Impl; }
  else { delete window.SpeechRecognition; delete window.webkitSpeechRecognition; }
  return Promise.resolve(fn()).finally(() => {
    window.SpeechRecognition = realSR;
    window.webkitSpeechRecognition = realWK;
    forgetVerdict();
  });
}

const STT = { kind: 'groq', apiKey: 'not-a-real-key-never-called' };
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const outcome = (promise) => promise.then((value) => ({ value }), (error) => ({ error }));

function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

async function within(promise, label, ms = 4000) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out: ${label}`)), ms);
      }),
    ]);
  } finally { clearTimeout(timer); }
}

async function until(predicate, label, ms = 4000) {
  const end = performance.now() + ms;
  while (!predicate()) {
    if (performance.now() > end) throw new Error(`timed out: ${label}`);
    await wait(20);
  }
}

function trackedSetupSignal() {
  const controller = new AbortController();
  const { signal } = controller;
  const listeners = new Set();
  const add = signal.addEventListener.bind(signal);
  const remove = signal.removeEventListener.bind(signal);
  signal.addEventListener = (type, callback, options) => {
    if (type === 'abort') listeners.add(callback);
    add(type, callback, options);
  };
  signal.removeEventListener = (type, callback, options) => {
    if (type === 'abort') listeners.delete(callback);
    remove(type, callback, options);
  };
  return { controller, signal, listeners };
}

async function setupCancellationChecks(check) {
  const callbacks = ['onstart', 'onaudiostart', 'onresult', 'onerror', 'onend'];
  const factories = [
    ['probeWebSpeech', (signal) => probeWebSpeech({
      signal, force: true, probeMs: 800, promptMs: 800,
    })],
    ['createVoice', (signal) => createVoice({ signal })],
  ];
  const promptly = (promise) => Promise.race([
    promise, wait(120).then(() => ({ timedOut: true })),
  ]);

  for (const [name, construct] of factories) {
    const records = [];
    class SetupRecognition extends SilentRecognition {
      constructor() {
        super();
        this.starts = 0;
        this.aborts = 0;
        records.push(this);
      }
      start() {
        this.starts += 1;
        this.queued = Object.fromEntries(callbacks.map((key) => [key, this[key]]));
      }
      abort() {
        this.aborts += 1;
        this.onerror?.({ error: 'aborted' });
        this.onend?.();
      }
    }
    for (const preAborted of [false, true]) {
      records.length = 0;
      const permission = deferred();
      let queries = 0;
      await withRecognition(SetupRecognition, () => withPermission(() => {
        queries += 1;
        return permission.promise;
      }, async () => {
        forgetVerdict();
        const { controller, signal, listeners } = trackedSetupSignal();
        if (preAborted) controller.abort();
        const pending = outcome(construct(signal));
        controller.abort();
        const result = await promptly(pending);
        check(`${name} rejects AbortError ${preAborted ? 'for an already-cancelled setup' : 'while permission is pending'}`,
          result.error?.name === 'AbortError', result.error?.name || 'not rejected within 120ms');
        if (preAborted) check(`${name} does not query permissions for a cancelled setup`, queries === 0);
        check(`${name} removes its cancellation listener before permission resolves`, listeners.size === 0);
        permission.resolve({ state: 'prompt' });
        await wait(30);
        check(`${name} never constructs or starts a recognizer after a late permission result`,
          records.length === 0, `${records.length} constructed`);
        // Also deliver callbacks retained by the platform before cancellation.
        for (const rec of records) rec.queued?.onstart?.();
        const settled = await within(pending, `${name} cancelled setup cleanup`, 1200);
        check(`${name} caller cancellation never caches an engine verdict`, cachedVerdict() === null);
        await settled.value?.dispose?.();
      }));
    }

    records.length = 0;
    await withRecognition(SetupRecognition, () => withPermission('prompt', async () => {
      forgetVerdict();
      const { controller, signal, listeners } = trackedSetupSignal();
      const pending = outcome(construct(signal));
      await until(() => records[0]?.starts === 1, `${name} initial probe`, 1200);
      const rec = records[0];
      const queued = rec.queued;
      controller.abort();
      check(`${name} immediately aborts an already-started probe`, rec.aborts === 1, `${rec.aborts} aborts`);
      check(`${name} detaches every recognizer callback on cancellation`,
        callbacks.every((key) => rec[key] == null));
      const result = await promptly(pending);
      check(`${name} promptly rejects AbortError after probe start`,
        result.error?.name === 'AbortError', result.error?.name || 'not rejected within 120ms');
      check(`${name} unregisters its started-probe cancellation listener`, listeners.size === 0);
      queued.onstart?.();
      queued.onaudiostart?.();
      queued.onerror?.({ error: 'network' });
      queued.onend?.();
      const settled = await within(pending, `${name} started probe cleanup`, 1200);
      check(`${name} late probe events cannot cache alive or dead after cancellation`,
        cachedVerdict() === null);
      await settled.value?.dispose?.();
    }));
  }

  await withRecognition(null, async () => {
    const permission = deferred();
    await withPermission(() => permission.promise, async () => {
      const { controller, signal, listeners } = trackedSetupSignal();
      const pending = outcome(createVoice({ signal }));
      controller.abort();
      const result = await promptly(pending);
      check('createVoice cancellation also interrupts its fallback permission explanation',
        result.error?.name === 'AbortError', result.error?.name || 'not rejected within 120ms');
      permission.resolve({ state: 'denied' });
      const settled = await within(pending, 'cancelled fallback explanation', 1200);
      check('a cancelled fallback query cannot return a voice controller or retain a listener',
        settled.error?.name === 'AbortError' && listeners.size === 0);
      await settled.value?.dispose?.();
    });
  });

  const cancelled = new AbortController();
  cancelled.abort();
  const recorder = await outcome(createVoice({ stt: STT, preferRecorder: true, signal: cancelled.signal }));
  check('already-cancelled recorder-only setup rejects rather than returning a controller',
    recorder.error?.name === 'AbortError');
  await recorder.value?.dispose?.();

  const records = [];
  class ReadyRecognition extends SilentRecognition {
    constructor() { super(); this.aborts = 0; records.push(this); }
    start() { queueMicrotask(() => this.onstart?.()); }
    abort() { this.aborts += 1; this.onend?.(); }
  }
  await withRecognition(ReadyRecognition, () => withPermission('granted', async () => {
    forgetVerdict();
    const { controller, signal, listeners } = trackedSetupSignal();
    const voice = await createVoice({ signal });
    try {
      check('successful setup unregisters its signal listener and probe callbacks',
        listeners.size === 0 && callbacks.every((key) => records[0][key] == null));
      controller.abort();
      const capture = voice.listen({ autoStop: false });
      await until(() => records.length === 2, 'active capture after setup cancellation', 1200);
      check('setup signal ownership ends when createVoice returns',
        records[0].aborts === 1 && records[1].aborts === 0 && cachedVerdict() === 'alive');
      voice.abort();
      check('the returned controller still owns active-capture cancellation',
        await within(capture, 'active capture abort', 1200) === '' && records[1].aborts === 1);
    } finally { await voice.dispose(); }
  }));
}

/** Real tracks and Web Audio, with visibility into ownership and optional acquisition delays. */
async function withMedia({ acquire, configureContext, configureRecorder } = {}, fn) {
  const devices = navigator.mediaDevices;
  const realAcquire = devices.getUserMedia;
  const RealContext = window.AudioContext;
  const RealRecorder = window.MediaRecorder;
  const seen = { requests: 0, streams: [], contexts: [], recorders: [], speakerConnections: 0 };
  const acquireReal = (constraints = { audio: true }) => realAcquire.call(devices, constraints);
  devices.getUserMedia = async (constraints) => {
    seen.requests += 1;
    const stream = await (acquire ? acquire(constraints, acquireReal) : acquireReal(constraints));
    seen.streams.push(stream);
    return stream;
  };
  window.AudioContext = function (...args) {
    const ctx = new RealContext(...args);
    seen.contexts.push(ctx);
    for (const method of ['createMediaStreamSource', 'createAnalyser']) {
      const make = ctx[method].bind(ctx);
      ctx[method] = (...params) => {
        const node = make(...params);
        const connect = node.connect.bind(node);
        node.connect = (destination, ...rest) => {
          if (destination === ctx.destination) seen.speakerConnections += 1;
          return connect(destination, ...rest);
        };
        return node;
      };
    }
    configureContext?.(ctx);
    return ctx;
  };
  window.MediaRecorder = function (...args) {
    const recorder = new RealRecorder(...args);
    seen.recorders.push(recorder);
    configureRecorder?.(recorder);
    return recorder;
  };
  window.MediaRecorder.isTypeSupported = RealRecorder.isTypeSupported.bind(RealRecorder);
  try { return await fn(seen, acquireReal); } finally {
    devices.getUserMedia = realAcquire;
    window.AudioContext = RealContext;
    window.MediaRecorder = RealRecorder;
    seen.streams.forEach((stream) => stream.getTracks().forEach((track) => track.stop()));
    await Promise.all(seen.contexts.filter((ctx) => ctx.state !== 'closed').map((ctx) => ctx.close()));
  }
}

/** Exactly one explanation that the level bars were given up for dictation. */
const gaveUpBars = (sink) => sink.unavailable.length === 1 && /level bars/.test(sink.unavailable[0]);

const tracksEnded = (seen) => seen.streams.length > 0
  && seen.streams.every((stream) => stream.getTracks().every((track) => track.readyState === 'ended'));

async function captureLifetimeChecks(check) {
  for (const action of ['dispose', 'pause']) {
    const permission = deferred();
    await withMedia({ acquire: () => permission.promise }, async (seen, acquireReal) => {
      const voice = await createVoice({ stt: STT, preferRecorder: true });
      try {
        const prepared = outcome(voice.ensureMic());
        const alsoPrepared = outcome(voice.ensureMic());
        let levels = 0;
        const phases = [];
        const heard = voice.listen({
          onLevel: () => { levels += 1; }, onPhase: (phase) => phases.push(phase),
        });
        check('pending recorder permission is not reported as listening', phases.length === 0);
        const closing = voice[action]();
        check(`${action} cancels permission acquisition without waiting for a grant`,
          (await within(prepared, action + ' acquisition')).error?.name === 'AbortError'
            && (await within(alsoPrepared, action + ' shared acquisition')).error?.name === 'AbortError');
        check(`${action} before capture returns no draft`,
          await within(heard, action + ' capture') === ''
            && (action !== 'pause' || await within(closing, 'pause draft') === ''));
        check('concurrent microphone preparation shares one permission request', seen.requests === 1);
        const late = await acquireReal();
        permission.resolve(late);
        await wait(50);
        check(`${action} stops every track from a late permission grant`,
          tracksEnded(seen) && seen.contexts.length === 0 && levels === 0);
        check(`${action} prevents late capture-phase callbacks after a permission grant`,
          phases.length === 0, phases.join(','));
        const restart = await outcome(voice.listen());
        check(`${action} cannot secretly restart the same capture controller`,
          !!restart.error && /disposed|paused/i.test(restart.error.message), restart.error?.message);
      } finally { await voice.dispose(); }
    });
  }

  await withMedia({
    configureContext: (ctx) => {
      ctx.createAnalyser = () => { throw new Error('injected analyser setup failure'); };
    },
  }, async (seen) => {
    const result = await outcome(createRecorder());
    check('analyser setup failure does not discard a usable microphone',
      !!result.value && !tracksEnded(seen), result.error?.message || '');
    if (!result.value) return;
    const unavailable = [];
    let levels = 0;
    try {
      const started = performance.now();
      const audio = await within(result.value.record({
        autoStop: false, maxMs: 650, onLevel: () => { levels += 1; },
        onLevelUnavailable: (reason) => unavailable.push(reason),
      }), 'recording without an analyser', 2000);
      check('an unavailable analyser leaves real audio recording until an independent deadline',
        audio.size > 1600 && performance.now() - started >= 600 && !result.value.recording(),
        `${audio.size} bytes`);
      check('missing level and speech evidence is explicit, never fabricated',
        levels === 0 && unavailable.length === 1 && /unknown|unavailable/i.test(unavailable[0]),
        unavailable.join('; '));
    } finally { await result.value.dispose(); }
  });

  await withMedia({
    configureRecorder: (recorder) => {
      recorder.start = () => { throw new Error('injected recording start failure'); };
    },
  }, async () => {
    const rec = await createRecorder();
    try {
      const phases = [];
      const result = await outcome(rec.record({ onStart: () => phases.push('listening') }));
      check('a MediaRecorder start failure rejects instead of looking like an empty successful capture',
        /injected recording start failure/.test(result.error?.message || '') && !rec.recording());
      check('a failed recorder start does not announce listening', phases.length === 0);
    } finally { await rec.dispose(); }
  });

  await withMedia({}, async (seen) => {
    const rec = await createRecorder();
    let samples = 0;
    try {
      const recording = rec.record({ autoStop: false, onLevel: () => { samples += 1; } });
      await until(() => samples > 8, 'real recorder samples');
      rec.stop();
      const stoppedAt = samples;
      const blob = await within(recording, 'retained recorder blob');
      await wait(80);
      check('recorder stop keeps its audio but stops level callbacks immediately',
        blob.size > 0 && samples === stoppedAt && !rec.recording());
      const discarded = rec.record({ autoStop: false, onLevel: () => { samples += 1; } });
      await until(() => samples > stoppedAt + 5, 'second real recording');
      rec.abort();
      const abortedAt = samples;
      check('recorder abort discards buffered audio', (await discarded).size === 0);
      await wait(80);
      check('recorder abort leaves no stale level callbacks', samples === abortedAt);
      const flushing = rec.record({ autoStop: false });
      await wait(80);
      seen.recorders.at(-1).stop();
      rec.stop();
      check('stop during an already-queued recorder flush keeps its last audio chunk',
        (await flushing).size > 0);
      await rec.dispose();
      await rec.dispose();
      const restart = await outcome(Promise.resolve().then(() => rec.record()));
      check('recorder disposal is terminal and closes tracks and its audio context',
        /disposed/i.test(restart.error?.message || '') && tracksEnded(seen)
          && seen.contexts.every((ctx) => ctx.state === 'closed'));
      check('recorder reuse does not reacquire the mic or route it to speakers',
        seen.requests === 1 && seen.speakerConnections === 0);
    } finally { await rec.dispose(); }
  });
}

async function recorderMeterFailureChecks(check) {
  await withMedia({
    configureContext: (ctx) => {
      ctx.suspend();
      ctx.resume = () => new Promise(() => {});
    },
  }, async () => {
    const rec = await createRecorder();
    const unavailable = [];
    let levels = 0;
    try {
      const result = await outcome(within(rec.record({
        autoStop: false, maxMs: 650, onLevel: () => { levels += 1; },
        onLevelUnavailable: (reason) => unavailable.push(reason),
      }), 'recorder while Web Audio cannot resume', 2500));
      check('a never-resuming audio context cannot strand MediaRecorder or invent silence',
        result.value?.size > 1600 && levels === 0 && unavailable.length === 1
          && /unknown/i.test(unavailable[0]), result.error?.message || unavailable.join('; '));
    } finally { await rec.dispose(); }
  });

  for (const failure of ['suspend', 'close']) {
    await withMedia({}, async (seen) => {
      const rec = await createRecorder();
      const unavailable = [];
      let samples = 0;
      try {
        const pending = outcome(rec.record({
          autoStop: false, maxMs: 1000, onLevel: () => { samples += 1; },
          onLevelUnavailable: (reason) => unavailable.push(reason),
        }));
        await until(() => samples >= 5, 'meter before interruption');
        await seen.contexts[0][failure]();
        await wait(80);
        check(`an analyser ${failure} does not stop or disable the recorder microphone`,
          rec.recording() && seen.streams[0].getAudioTracks().every((track) =>
            track.readyState === 'live' && track.enabled));
        const result = await within(pending, 'meter failure deadline', 2500);
        check(`recording survives analyser ${failure} with its captured audio intact`,
          !result.error && result.value?.size > 1600, result.error?.message || `${result.value?.size} bytes`);
        check(`analyser ${failure} is reported once without stale levels`,
          unavailable.length === 1 && /unknown|unavailable/i.test(unavailable[0]),
          unavailable.join('; '));
      } finally { await rec.dispose(); }
    });
  }

  await withMedia({}, async (seen) => {
    const rec = await createRecorder();
    try {
      const pending = outcome(rec.record({ autoStop: false, maxMs: 4000 }));
      await wait(650);
      await seen.contexts[0].close();
      seen.streams[0].getTracks().forEach((track) => track.stop());
      const result = await within(pending, 'lost microphone input', 2000);
      check('ended input rejects explicitly but preserves the already captured audio',
        result.error?.code === 'audio-capture' && result.error.fatal === true
          && result.error.audio?.size > 1600 && !rec.recording(),
        result.error?.message || 'unexpected successful recording');
    } finally { await rec.dispose(); }
  });
}

async function nativeLevelChecks(check) {
  await import('./fixtures/fake-voice.js');
  const fake = window.__FakeVoice;
  fake.install(window);
  forgetVerdict();
  try {
    // A controllable signal through REAL Web Audio, never a level inferred from text.
    const source = new AudioContext();
    const oscillator = source.createOscillator();
    const gain = source.createGain();
    const sink = source.createMediaStreamDestination();
    gain.gain.value = 0;
    oscillator.connect(gain).connect(sink);
    oscillator.start();
    await source.resume();
    try {
      await withMedia({ acquire: async () => sink.stream.clone() }, async (seen) => {
        const voice = await createVoice();
        try {
          await voice.ensureMic();
          fake.script([[{ at: 10, final: 'ordinary dictation' }, { at: 30, end: true }]]);
          const phases = [];
          const first = voice.listen({ onPhase: (phase) => phases.push(phase) });
          check('native capture does not announce listening before its start event', phases.length === 0);
          await first;
          check('native capture reports listening without inventing a transcription phase',
            phases.join(',') === 'listening', phases.join(','));
          check('native metering does not acquire audio at setup or without onLevel', seen.requests === 0);

          const samples = [];
          const unavailable = [];
          const shown = [];
          fake.script([[{ at: 10, final: 'many spoken words with a silent audio input' }]]);
          const heard = voice.listen({
            autoStop: false, onInterim: (text) => shown.push(text),
            onLevel: (rms, state) => samples.push({ rms, state }),
            onLevelUnavailable: (reason) => unavailable.push(reason),
          });
          await until(() => samples.length >= 6, 'native levels from silence');
          check('recognition text does not fabricate microphone activity',
            shown.length > 0 && samples.every(({ rms }) => rms === 0), `${samples.length} silent samples`);
          gain.gain.value = 0.4;
          await until(() => samples.some(({ rms }) => rms > 0.15), 'native levels from audio');
          check('native levels reflect real analyser data and carry the current gate state',
            samples.every(({ rms, state }) => Number.isFinite(rms) && rms >= 0 && rms <= 1
              && typeof state.heardSpeech === 'boolean') && unavailable.length === 0);
          gain.gain.value = 0;
          await until(() => samples.slice(-4).every(({ rms }) => rms === 0), 'native silence again');
          voice.stop();
          const stoppedAt = samples.length;
          check('native stop retains settled text', await heard === 'many spoken words with a silent audio input');
          await wait(80);
          check('no native meter frames run between turns',
            samples.length === stoppedAt && seen.streams[0].getTracks().every((track) => !track.enabled));

          fake.script([[{ at: 10, interim: 'a draft finalized on pause' }]]);
          const next = voice.listen({
            autoStop: false, onLevel: (rms) => samples.push({ rms }),
            onLevelUnavailable: (reason) => unavailable.push(reason),
          });
          await until(() => samples.length >= stoppedAt + 5, 'reused native input');
          const draft = voice.pause();
          const pausedAt = samples.length;
          check('pause releases actual native meter tracks before draft finalization', tracksEnded(seen));
          check('native pause retains the flushed draft without starting another capture',
            await next === 'a draft finalized on pause' && await draft === 'a draft finalized on pause');
          await wait(80);
          check('native pause closes its analyser context with no stale frames',
            samples.length === pausedAt && seen.contexts.every((ctx) => ctx.state === 'closed'));
          check('native metering reuses one input and never connects it to speakers',
            seen.requests === 1 && seen.speakerConnections === 0);
          check('repeated pause returns the same draft', await voice.pause() === await draft);
        } finally { await voice.dispose(); }
      });
    } finally {
      oscillator.stop();
      sink.stream.getTracks().forEach((track) => track.stop());
      await source.close();
    }

    await withMedia({
      acquire: async () => { throw new DOMException('injected busy microphone', 'NotReadableError'); },
    }, async (seen) => {
      const voice = await createVoice();
      try {
        const unavailable = [];
        const phases = [];
        let levels = 0;
        fake.script([[{ at: 30, final: 'recognition still works' }, { at: 80, end: true }]]);
        const text = await voice.listen({
          onLevel: () => { levels += 1; }, onLevelUnavailable: (reason) => unavailable.push(reason),
          onPhase: (phase) => phases.push(phase),
        });
        check('an unavailable native meter is visible but never a fatal recognition error',
          text === 'recognition still works' && levels === 0 && unavailable.length === 1
            && /in use/.test(unavailable[0]), unavailable.join('; '));
        check('a meter failure does not gate the native listening phase',
          phases.join(',') === 'listening', phases.join(','));
        fake.script([[{ at: 10, final: 'the next answer' }, { at: 30, end: true }]]);
        await voice.listen({ onLevel() {}, onLevelUnavailable: (reason) => unavailable.push(reason) });
        check('an unavailable meter does not reacquire permission on each question',
          seen.requests === 1 && unavailable.length === 2);
      } finally { await voice.dispose(); }
    });

    await withMedia({}, async (seen) => {
      const voice = await createVoice();
      try {
        const unavailable = [];
        fake.script([
          [{ at: 200, error: 'audio-capture' }, { at: 220, end: true }],
          [{ at: 10, final: 'retry without competing for the microphone' }, { at: 40, end: true }],
        ]);
        const text = await voice.listen({
          onLevel() {}, onLevelUnavailable: (reason) => unavailable.push(reason),
        });
        check('a native microphone conflict releases metering and retries dictation once',
          text === 'retry without competing for the microphone' && unavailable.length === 1
            && /cannot share/.test(unavailable[0]) && tracksEnded(seen));
      } finally { await voice.dispose(); }
    });

    // Android's version of that conflict says nothing. While the page's meter holds the
    // microphone the recogniser starts, hears silence, ends and is restarted, with no
    // audio-capture error — so hands-free listened for ever while the bars moved with the
    // driver's voice, and "over" was never heard because no word was. The only evidence is
    // the disagreement: the meter heard speech for seconds and the recogniser heard nothing.
    {
      const starvedSource = new AudioContext();
      const tone = starvedSource.createOscillator();
      const level = starvedSource.createGain();
      const out = starvedSource.createMediaStreamDestination();
      level.gain.value = 0;
      tone.connect(level).connect(out);
      tone.start();
      await starvedSource.resume();
      try {
        await withMedia({ acquire: async () => out.stream.clone() }, async (seen) => {
          const metered = () => seen.streams.some((stream) =>
            stream.getAudioTracks().some((track) => track.readyState === 'live'));
          // Android drafts: each already final, at a new index, with no confidence behind it.
          // The first lands after the meter has opened, as a driver's first word does.
          const drafts = (texts, from = 300) =>
            texts.map((t, n) => ({ at: from + n * 30, final: t, confidence: 0 }));
          const handsFree = (v, sink) => v.listen({
            autoStop: false, settleMs: 100, isComplete: (text) => endsWithTrigger(text),
            onLevel: (rms) => sink.samples.push(rms),
            onLevelUnavailable: (reason) => sink.unavailable.push(reason),
          });
          const talk = async (sink) => {
            await until(() => sink.samples.length >= 8, 'calibrating on silence');
            level.gain.value = 0.4;               // the driver starts talking
          };
          fake.starveWhile(metered, 250);
          let voice = await createVoice();
          try {
            const sink = { samples: [], unavailable: [] };
            const started = fake.recognition.startCount;
            fake.script([drafts(['it', 'it should', 'it should be fast', 'it should be fast over'])]);
            const heard = handsFree(voice, sink);
            await talk(sink);
            const text = await within(heard, 'a starved recogniser', 5000);
            const starvedSessions = fake.recognition.sessions.slice(started).filter((s) => s.starved);
            check('a meter that starves Android recognition is detected by behaviour, not by platform',
              starvedSessions.length >= 2 && gaveUpBars(sink) && tracksEnded(seen),
              `${starvedSessions.length} starved; ${sink.unavailable.join('; ')}`);
            // The words said while starved were never heard by anything that could transcribe
            // them, so a silent retry could only submit the end of the sentence. A miss makes
            // the drive loop say so aloud and the driver repeat the answer whole.
            check('...and hands-free ends that capture as a miss rather than keep half an answer',
              text === '' && fake.recognition.starvedSteps > 0, JSON.stringify(text));

            fake.script([drafts(['said again', 'said again over'], 10)]);
            const again = await within(handsFree(voice, sink), 'the repeated answer', 3000);
            check('...the repeated answer is heard in full and the trigger word ends it',
              again === 'said again over' && seen.requests === 1, `${JSON.stringify(again)}, ${seen.requests} acquisitions`);
            await voice.dispose();

            // Resume builds a new controller, and press-to-talk one per answer.
            voice = await createVoice();
            const resumed = { samples: [], unavailable: [] };
            fake.script([drafts(['after resume', 'after resume over'], 10)]);
            const afterResume = await within(handsFree(voice, resumed), 'the answer after Resume', 3000);
            check('...and a new controller on the same page never reopens the starving meter',
              afterResume === 'after resume over' && seen.requests === 1 && gaveUpBars(resumed),
              `${JSON.stringify(afterResume)}, ${seen.requests} acquisitions`);
            await voice.dispose();

            // Press-to-talk is watched: it retries once, silently, without the meter.
            forgetStarvedMeter();
            level.gain.value = 0;
            voice = await createVoice();
            const watched = { samples: [], unavailable: [] };
            const shown = [];
            fake.script([
              drafts(['lost while', 'lost while starved']),
              [{ at: 10, final: 'heard after the retry', confidence: 0 }],
            ]);
            const pressed = voice.listen({
              autoStop: false, onInterim: (t) => shown.push(t),
              onLevel: (rms) => watched.samples.push(rms),
              onLevelUnavailable: (reason) => watched.unavailable.push(reason),
            });
            await talk(watched);
            await until(() => shown.includes('heard after the retry'), 'press-to-talk retry', 6000);
            voice.stop();
            check('press-to-talk retries a starved capture without the meter',
              await within(pressed, 'press-to-talk stop', 2000) === 'heard after the retry'
                && gaveUpBars(watched) && seen.requests === 2 && tracksEnded(seen),
              `${seen.requests} acquisitions; ${watched.unavailable.join('; ')}`);
          } finally {
            fake.starveWhile(null);
            forgetStarvedMeter();
            await voice.dispose();
          }
        });
      } finally {
        tone.stop();
        out.stream.getTracks().forEach((track) => track.stop());
        await starvedSource.close();
      }
    }

    await withMedia({
      configureContext: (ctx) => {
        ctx.suspend();
        ctx.resume = () => new Promise(() => {});
      },
    }, async (seen) => {
      const voice = await createVoice();
      try {
        const unavailable = [];
        let levels = 0;
        fake.script([[{ at: 10, final: 'still listening without a running meter' }, { at: 2000, end: true }]]);
        const text = await voice.listen({
          onLevel: () => { levels += 1; }, onLevelUnavailable: (reason) => unavailable.push(reason),
        });
        check('a suspended native analyser reports unavailable instead of fabricating silent samples',
          text === 'still listening without a running meter' && levels === 0
            && unavailable.length === 1 && /unavailable/.test(unavailable[0]) && tracksEnded(seen));
      } finally { await voice.dispose(); }
    });

    for (const action of ['pause', 'dispose']) {
      const permission = deferred();
      await withMedia({ acquire: () => permission.promise }, async (seen, acquireReal) => {
        const voice = await createVoice();
        try {
          let callbacks = 0;
          fake.script([[{ at: 10, final: 'keep this while permission is pending' }]]);
          const heard = voice.listen({
            autoStop: false, onLevel: () => { callbacks += 1; },
            onLevelUnavailable: () => { callbacks += 1; },
          });
          await until(() => seen.requests === 1, 'native meter permission request');
          await wait(30);
          const closing = voice[action]();
          const expected = action === 'pause' ? 'keep this while permission is pending' : '';
          check(`native ${action} does not wait for meter permission to settle capture`,
            await within(heard, 'native ' + action) === expected
              && (action !== 'pause' || await closing === expected));
          permission.resolve(await acquireReal());
          await wait(60);
          check(`a native meter grant after ${action} is closed without any stale callbacks`,
            tracksEnded(seen) && seen.contexts.length === 0 && callbacks === 0);
        } finally { await voice.dispose(); }
      });
    }

    for (const action of ['abort', 'dispose']) {
      await withMedia({}, async (seen) => {
        const voice = await createVoice();
        try {
          const phases = [];
          fake.script([[{ at: 20, final: 'cancelled before recognition starts' }]]);
          const heard = voice.listen({
            onPhase: (phase) => phases.push(phase), onLevel() {},
          });
          voice[action]();
          await heard;
          await wait(40);
          check(`native ${action} before start suppresses late phase callbacks and metering`,
            phases.length === 0 && seen.requests === 0);
        } finally { await voice.dispose(); }
      });

      await withMedia({}, async (seen) => {
        const voice = await createVoice();
        try {
          const phases = [];
          const starts = fake.recognition.startCount;
          fake.script([[{ at: 20, final: 'cancelled from phase callback' }]]);
          const heard = voice.listen({
            onLevel() {},
            onPhase: (phase) => {
              phases.push(phase);
              voice[action]();
            },
          });
          const text = await within(heard, 'native phase cancellation');
          await wait(40);
          check(`native ${action} from onPhase cannot start metering or a second capture`,
            text === '' && phases.join(',') === 'listening'
              && fake.recognition.startCount === starts + 1 && seen.requests === 0);
        } finally { await voice.dispose(); }
      });
    }
  } finally {
    fake.restore();
    forgetVerdict();
  }
}

export default async function run(check) {
  check('the browser can record audio', micSupported());
  check('speech synthesis is available', ttsSupported());

  // ── the probe, against every way it fails in the wild ─────────────────────
  await withRecognition(SilentRecognition, async () => {
    forgetVerdict();
    check('a present-but-silent recogniser feature-detects as supported', webSpeechPresent());
    const t0 = performance.now();
    const verdict = await probeWebSpeech({ force: true });
    const ms = Math.round(performance.now() - t0);
    check('...but the behavioural probe declares it dead', verdict === 'dead', ms + 'ms');
    check('the probe does not hang waiting for it', ms < 4000, ms + 'ms');
    // Only with the microphone already granted. A silent engine is then genuinely the
    // engine's fault, which is the installed-iPhone case this whole probe exists for.
    await withPermission('granted', async () => {
      forgetVerdict();
      check('a silent engine with the microphone already granted is remembered as dead',
        (await probeWebSpeech({ force: true })) === 'dead' && cachedVerdict() === 'dead',
        String(cachedVerdict()));
    });

    // ── the bug: a permission decision is not an engine verdict ──────────────
    //
    // rec.start() is what RAISES the prompt, so on a first run the probe was timing a
    // person hunting for Allow, not an engine. It timed out, wrote 'dead', and because a
    // cached verdict short-circuits the probe, dictation was off for that origin for ever
    // — including after the microphone was granted. Reported, never remembered.
    await withPermission('prompt', async () => {
      forgetVerdict();
      check('a timeout while the prompt is still up is NOT remembered',
        (await probeWebSpeech({ force: true, promptMs: 150 })) === 'dead'
          && cachedVerdict() === null,
        String(cachedVerdict()));
    });

    forgetVerdict();
    const v = await createVoice({ stt: STT });
    check('with a transcription key it falls back to the recorder', v.mode === 'recorder');
    check('and says dictation is metered', v.metered() === true);
    v.dispose();

    forgetVerdict();
    const bare = await createVoice({ stt: null });
    // A mic button that starts, fires nothing and hangs is worse than no mic button.
    check('with no transcription key it offers no voice at all', bare.mode === 'none');
    check('and explains what would fix it',
      /transcription key/i.test(bare.unavailableReason || ''), bare.unavailableReason);
    bare.dispose();
  });

  await withRecognition(NetworkErrorRecognition, async () => {
    forgetVerdict();
    const t0 = performance.now();
    check('an Edge-style network error is declared dead',
      (await probeWebSpeech({ force: true })) === 'dead',
      Math.round(performance.now() - t0) + 'ms');
  });

  // A refusal is revocable from site settings, so writing the engine off over one would
  // mean never finding out it had been granted.
  await withRecognition(RefusedRecognition, async () => {
    await withPermission('prompt', async () => {
      forgetVerdict();
      check('a refused microphone is not remembered as a broken engine',
        (await probeWebSpeech({ force: true })) === 'dead' && cachedVerdict() === null,
        String(cachedVerdict()));
    });
  });

  // The poisoned values v1 already wrote to real phones have to be discarded, not believed.
  await withRecognition(SilentRecognition, async () => {
    forgetVerdict();
    localStorage.setItem('ideaforge.webspeech', 'dead');
    check('a verdict written by the previous version is ignored', cachedVerdict() === null);
    forgetVerdict();
  });

  // Blaming the platform for a blocked microphone sends people hunting a browser bug that
  // is not there, past the one cause they can actually fix.
  await withRecognition(RefusedRecognition, async () => {
    await withPermission('denied', async () => {
      forgetVerdict();
      const v = await createVoice({ stt: null });
      check('a blocked microphone is reported as blocked, not as a broken browser',
        /blocked for this site/i.test(v.unavailableReason || ''), v.unavailableReason);
      v.dispose();
    });
  });

  await withRecognition(null, async () => {
    forgetVerdict();
    check('an absent recogniser is declared dead',
      (await probeWebSpeech({ force: true })) === 'dead');
    const v = await createVoice({ stt: STT });
    check('Firefox-shaped browsers still get voice via the recorder', v.mode === 'recorder');
    v.dispose();
  });

  // ── the recorder and the silence gate, against a real audio pipeline ──────
  await withMedia({}, async (seen) => {
    const rec = await createRecorder();
    check('a recording MIME type was negotiated', typeof rec.mime === 'string', rec.mime || 'browser default');
    let peak = 0;
    let last = null;
    const started = performance.now();
    try {
      const blob = await rec.record({
        onLevel: (rms, state) => {
          peak = Math.max(peak, rms);
          last = state;
          // Give the real analyser silence after enough synthetic microphone speech.
          if (state.speechMs >= 200) seen.streams[0].getTracks().forEach((track) => { track.enabled = false; });
        },
        silenceMs: 600, minSpeechMs: 200, maxMs: 6000,
      });
      check('recording produces a non-empty blob', blob && blob.size > 1000, (blob && blob.size) + ' bytes');
      check('the analyser saw signal', peak > 0.01, 'peak rms ' + peak.toFixed(3));
      check('the silence gate detected speech', !!(last && last.heardSpeech));
      check('the gate closed on actual silence before the independent time limit',
        !!last?.done && performance.now() - started < 5500);
    } finally { await rec.dispose(); }
  });

  // ── the backend picker when the real recogniser works ─────────────────────
  forgetVerdict();
  const live = await createVoice({ stt: null });
  check('a working recogniser is preferred over paid transcription',
    live.mode === 'webspeech', live.mode);
  live.dispose();

  forgetVerdict();
  const forced = await createVoice({ stt: STT, preferRecorder: true });
  check('preferRecorder overrides that', forced.mode === 'recorder');
  check('the transcriber is named for the UI', forced.transcriberLabel === 'Groq Whisper');
  forced.dispose();
  forgetVerdict();

  await captureLifetimeChecks(check);
  await recorderMeterFailureChecks(check);
  await nativeLevelChecks(check);
  await setupCancellationChecks(check);
}
