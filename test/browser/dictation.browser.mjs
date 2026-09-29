// The dictation session's event state machine, driven by a scripted recogniser.
//
// listenViaWebSpeech is the least-tested and most rule-laden code in the app: it rebuilds the
// answer from the whole result list, folds Android's re-sent drafts into one sentence,
// carries finals across sessions, restarts itself when the engine ends early, keeps a
// half-finished answer when a live engine fails, and decides on its own when an answer is
// over. Every one of those is a correctness rule stated only in a comment,
// because until now there was no way to make a recogniser say a particular thing at a
// particular moment.
//
// There is now. The fixture replaces the constructor — which works only because
// webspeech.js resolves it lazily — so each test below is a transcript arriving on a
// timeline, and the assertion is what the app decided the answer was.

import { listenViaWebSpeech, forgetVerdict } from '../../src/voice/webspeech.js';
import { speak, ttsSupported } from '../../src/voice/speak.js';
import { endsWithTrigger, parseSpeech } from '../../src/core/driving.js';
import { createVoice } from '../../src/voice/index.js';

await import('./fixtures/fake-voice.js');
const fake = window.__FakeVoice;

/** Script one utterance and run a dictation session over it. */
function heard(steps, opts = {}) {
  fake.script([steps]);
  return listenViaWebSpeech({ autoStop: true, ...opts });
}

/** An Android draft: already final, at a new index, with no confidence behind it. */
const draft = (at, text) => ({ at, final: text, confidence: 0 });
/** A run of drafts, `step` ms apart. */
const drafts = (texts, from, step) => texts.map((text, n) => draft(from + n * step, text));

/** A session that will not settle on its own gets a deadline, so a hang is a failure. */
function within(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`timed out: ${label}`)), ms)),
  ]);
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const outcome = (promise) => promise.then((value) => ({ value }), (error) => ({ error }));

async function until(predicate, label, ms = 6000) {
  const end = performance.now() + ms;
  while (!predicate()) {
    if (performance.now() > end) throw new Error(`timed out: ${label}`);
    await wait(20);
  }
}

/** Real recording, deferred STT. Only the harness's same-origin progress reports pass through. */
async function withCaptureRequests(fn, { ignoreAbort = false, stream, configureContext, replyText } = {}) {
  const realFetch = globalThis.fetch;
  const RealRecorder = window.MediaRecorder;
  const RealContext = window.AudioContext;
  const realAcquire = navigator.mediaDevices.getUserMedia;
  const seen = { requests: [], streams: [], contexts: [], recordings: 0, events: [], bytes: 0 };
  window.MediaRecorder = function (...args) {
    seen.recordings += 1;
    const recorder = new RealRecorder(...args);
    recorder.addEventListener('start', () => seen.events.push('capture-start'));
    recorder.addEventListener('dataavailable', (event) => { seen.bytes += event.data.size; });
    return recorder;
  };
  window.MediaRecorder.isTypeSupported = RealRecorder.isTypeSupported.bind(RealRecorder);
  if (configureContext) {
    window.AudioContext = function (...args) {
      const context = new RealContext(...args);
      seen.contexts.push(context);
      configureContext(context);
      return context;
    };
  }
  navigator.mediaDevices.getUserMedia = async (...args) => {
    const input = stream ? stream.clone() : await realAcquire.apply(navigator.mediaDevices, args);
    seen.streams.push(input);
    return input;
  };
  globalThis.fetch = (url, init) => {
    if (url === '/__result') return realFetch(url, init);
    if (!String(url).endsWith('/audio/transcriptions')) {
      throw new Error('unexpected request in the transcription fixture');
    }
    seen.events.push('request-start');
    let resolve, reject;
    const reply = new Promise((res, rej) => { resolve = res; reject = rej; });
    const cancel = () => reject(init.signal.reason);
    if (!ignoreAbort) init.signal.addEventListener('abort', cancel, { once: true });
    seen.requests.push({
      signal: init.signal,
      reply(text, status = 200) {
        resolve({ ok: status === 200, status, statusText: 'scripted', headers: { get: () => null },
          json: async () => status === 200 ? { text } : { error: { message: text } } });
      },
      cancel: () => reject(new DOMException('fixture finished', 'AbortError')),
    });
    if (replyText !== undefined) seen.requests.at(-1).reply(replyText);
    return reply.finally(() => init.signal.removeEventListener('abort', cancel));
  };
  try { await fn(seen); } finally {
    globalThis.fetch = realFetch;
    window.MediaRecorder = RealRecorder;
    window.AudioContext = RealContext;
    navigator.mediaDevices.getUserMedia = realAcquire;
    seen.requests.forEach((request) => request.cancel());
    seen.streams.forEach((stream) => stream.getTracks().forEach((track) => track.stop()));
    await Promise.all(seen.contexts.filter((ctx) => ctx.state !== 'closed').map((ctx) => ctx.close()));
  }
}

const tracksEnded = (seen) => seen.streams.length > 0
  && seen.streams.every((stream) => stream.getTracks().every((track) => track.readyState === 'ended'));

async function recorderCancellationChecks(check) {
  const config = { stt: { kind: 'groq', apiKey: 'not-a-real-key' }, preferRecorder: true };
  const gate = { silenceMs: 400, minSpeechMs: 150, maxMs: 2500 };
  const segmented = { isComplete: () => false, gate, maxSegments: 3 };

  for (const action of ['stop', 'pause', 'abort', 'dispose']) {
    await withCaptureRequests(async (seen) => {
      const voice = await createVoice(config);
      try {
        await voice.ensureMic();
        let levels = 0;
        const phases = [];
        const heard = voice.listen({
          ...segmented, gate: { ...gate, silenceMs: 8000, maxMs: 10000 },
          onLevel: () => { levels += 1; },
          onPhase: (phase) => phases.push(phase),
        });
        await until(() => levels > 0, 'first recorder level');
        await wait(900);
        const closing = voice[action]();
        const stoppedAt = levels;
        const retained = action === 'stop' || action === 'pause';
        if (action === 'pause' || action === 'dispose') {
          check(`${action} while recording releases its tracks synchronously`, tracksEnded(seen));
        }
        if (retained) {
          await until(() => seen.requests.length === 1, 'stopped segment transcription');
          seen.requests[0].reply('keep this single segment');
        }
        const text = await within(heard, 2000, action + ' during recording');
        await wait(80);
        check(`${action} during recording never starts a subsequent segment`,
          seen.recordings === 1 && seen.requests.length === (retained ? 1 : 0));
        check(`${action} immediately stops recorder levels and ${retained ? 'retains' : 'discards'} its text`,
          levels === stoppedAt && text === (retained ? 'keep this single segment' : ''));
        check(`${action} reports no late recorder phase after standing down`,
          phases.join(',') === (action === 'stop' ? 'listening,transcribing' : 'listening'),
          phases.join(','));
        if (action === 'pause') {
          check('pause during real recording returns the captured audio as an unsent draft',
            await closing === 'keep this single segment');
        }
      } finally { await voice.dispose(); }
    });
  }

  async function interruptedDraftChecks(check) {
    const realSR = window.SpeechRecognition;
    const realWK = window.webkitSpeechRecognition;
    let active;
    let correction = false;
    const result = (text, isFinal) => Object.assign([{ transcript: text, confidence: 0.9 }], { isFinal });
    class DraftRecognition {
      constructor() { active = this; this.stops = 0; }
      start() {
        queueMicrotask(() => {
          this.onstart?.();
          if (!this.continuous) return;
          this.results = [result('the budget is', true), result('one hundred and twenty dollars', false)];
          this.onresult?.({ resultIndex: 0, results: this.results });
        });
      }
      stop() {
        this.stops += 1;
        if (correction) {
          setTimeout(() => {
            this.results[1] = result('$120', true);
            this.onresult?.({ resultIndex: 1, results: this.results });
          }, 40);
        }
      }
      abort() {}
    }
    window.SpeechRecognition = window.webkitSpeechRecognition = DraftRecognition;
    forgetVerdict();
    try {
      for (const corrected of [false, true]) {
        correction = corrected;
        const voice = await createVoice();
        let shown = '';
        try {
          const pending = voice.listen({ autoStop: false, onInterim: (text) => { shown = text; } });
          await until(() => shown.includes('twenty dollars'), 'visible pending native tail');
          const paused = voice.pause();
          const expected = corrected ? 'the budget is $120' : 'the budget is one hundred and twenty dollars';
          check(corrected
            ? 'pause keeps a finalized shorter correction rather than the longest visible draft'
            : 'pause retains the final clause and still-visible interim tail at its deadline',
          await within(paused, 1200, 'native draft pause') === expected && await pending === expected,
          expected);
          check('native draft-preserving pause stops rather than restarting recognition', active.stops === 1);
        } finally { await voice.dispose(); }
      }
      correction = false;
      let shown = '';
      const ordinary = listenViaWebSpeech({
        autoStop: false, onInterim: (text) => { shown = text; },
      });
      await until(() => shown.includes('twenty dollars'), 'ordinary pending native tail');
      ordinary.stop();
      check('ordinary native stop still returns only settled finals',
        await within(ordinary.promise, 1200, 'ordinary native stop') === 'the budget is');
      const voice = await createVoice();
      try {
        shown = '';
        const pending = voice.listen({ onInterim: (text) => { shown = text; } });
        await until(() => shown.includes('twenty dollars'), 'aborted pending native tail');
        const paused = voice.pause();
        voice.abort();
        check('abort still discards both the final clause and a pending paused draft',
          await pending === '' && await paused === '');
      } finally { await voice.dispose(); }
    } finally {
      window.SpeechRecognition = realSR;
      window.webkitSpeechRecognition = realWK;
      forgetVerdict();
    }
  }

  async function recorderEvidenceChecks(check) {
    const ctx = new AudioContext();
    const source = ctx.createOscillator();
    const gain = ctx.createGain();
    const output = ctx.createMediaStreamDestination();
    source.connect(gain).connect(output);
    gain.gain.value = 0.001;
    source.start();
    await ctx.resume();
    const config = { stt: { kind: 'groq', apiKey: 'not-a-real-key' }, preferRecorder: true };
    try {
      for (const action of ['stop', 'pause']) {
        await withCaptureRequests(async (seen) => {
          const voice = await createVoice(config);
          let last;
          const phases = [];
          try {
            const pending = voice.listen({
              autoStop: false, isComplete: () => false, gate: { maxMs: 12000 },
              onLevel: (_, state) => { last = state; }, onPhase: (phase) => phases.push(phase),
            });
            await until(() => seen.bytes >= 1600 && last?.heardSpeech === false, 'enough observed silence', 8000);
            const closing = voice[action]();
            const text = await within(pending, 2000, 'stopped known silence');
            check(`${action} never transcribes observed silence, even above the byte threshold`,
              text === '' && seen.requests.length === 0 && !phases.includes('transcribing'),
              `${seen.bytes} audio bytes; ${seen.requests.length} STT requests`);
            if (action === 'pause') await closing;
          } finally { await voice.dispose(); }
        }, { stream: output.stream, replyText: 'this would be a silence hallucination' });
      }

      await withCaptureRequests(async (seen) => {
        const voice = await createVoice(config);
        let last;
        try {
          const pending = voice.listen({
            autoStop: false, isComplete: () => false, gate: { maxMs: 12000, minSpeechMs: 900 },
            onLevel: (_, state) => {
              last = state;
              if (state.heardSpeech) voice.stop();
            },
          });
          await until(() => seen.bytes >= 1600 && last?.heardSpeech === false, 'short speech lead-in', 8000);
          gain.gain.value = 0.4;
          check('the first real speech sample is retained without waiting for minSpeechMs',
            await within(pending, 2000, 'short real speech') === 'a short real answer'
              && last.heardSpeech && last.speechMs < 900 && seen.requests.length === 1,
            `${last?.speechMs}ms of detected speech`);
        } finally { await voice.dispose(); }
      }, { stream: output.stream, replyText: 'a short real answer' });

      await withCaptureRequests(async (seen) => {
        const voice = await createVoice(config);
        const unavailable = [];
        let levels = 0;
        try {
          const result = await outcome(within(voice.listen({
            isComplete: () => true, gate: { maxMs: 650 },
            onLevel: () => { levels += 1; }, onLevelUnavailable: (reason) => unavailable.push(reason),
          }), 2500, 'unknown speech evidence'));
          check('an unusable meter reports unknown evidence without discarding recordable speech',
            result.value === 'speech captured without VAD' && levels === 0
              && unavailable.length === 1 && /unknown/i.test(unavailable[0])
              && seen.requests.length === 1,
            result.error?.message || unavailable.join('; '));
        } finally { await voice.dispose(); }
      }, {
        stream: output.stream, replyText: 'speech captured without VAD',
        configureContext: (context) => {
          context.createAnalyser = () => { throw new Error('injected analyser failure'); };
        },
      });

      gain.gain.value = 0.001;
      let failMeter = false;
      await withCaptureRequests(async (seen) => {
        const voice = await createVoice(config);
        const observed = [];
        const unavailable = [];
        try {
          const pending = outcome(voice.listen({
            isComplete: () => true, gate: { maxMs: 1200 },
            onLevel: (_, state) => observed.push(state),
            onLevelUnavailable: (reason) => unavailable.push(reason),
          }));
          await until(() => seen.bytes >= 1600 && observed.length > 0, 'quiet before meter failure');
          failMeter = true;
          gain.gain.value = 0.4;
          const result = await within(pending, 2500, 'unmetered continuation');
          check('quiet observed before meter failure cannot classify later unmetered audio as silence',
            result.value === 'speech after the meter failed' && seen.requests.length === 1
              && observed.every((state) => state.heardSpeech === false)
              && unavailable.length === 1 && /unknown/i.test(unavailable[0]),
            result.error?.message || unavailable.join('; '));
        } finally { await voice.dispose(); }
      }, {
        stream: output.stream, replyText: 'speech after the meter failed',
        configureContext: (context) => {
          const create = context.createAnalyser.bind(context);
          context.createAnalyser = () => {
            const analyser = create();
            const read = analyser.getByteTimeDomainData.bind(analyser);
            analyser.getByteTimeDomainData = (buffer) => {
              if (failMeter) throw new Error('injected mid-capture meter failure');
              read(buffer);
            };
            return analyser;
          };
        },
      });

      for (const pauseWhileTranscribing of [false, true]) {
        gain.gain.value = 0.001;
        await withCaptureRequests(async (seen) => {
          const voice = await createVoice(config);
          let last;
          try {
            const pending = outcome(voice.listen({
              isComplete: () => false, gate: { maxMs: 12000 },
              onLevel: (_, state) => { last = state; },
            }));
            await until(() => last?.heardSpeech === false && seen.bytes >= 1600, 'input loss lead-in', 8000);
            gain.gain.value = 0.4;
            await until(() => last?.heardSpeech, 'speech before microphone loss');
            await wait(250);
            seen.streams[0].getTracks().forEach((track) => track.stop());
            await until(() => seen.requests.length === 1, 'retained input-loss audio transcription');
            const paused = pauseWhileTranscribing ? outcome(voice.pause()) : null;
            seen.requests[0].reply('saved before the microphone ended');
            const result = await within(pending, 2000, 'input loss stand-down');
            const draft = paused ? (await paused).value : await voice.pause();
            check(`ended input retains its draft when pause happens ${pauseWhileTranscribing ? 'during' : 'after'} STT`,
              result.error?.fatal === true && result.error.code === 'audio-capture'
                && result.error.draft === 'saved before the microphone ended'
                && draft === 'saved before the microphone ended'
                && seen.recordings === 1 && seen.requests.length === 1,
              result.error?.message || 'unexpected successful capture');
          } finally { await voice.dispose(); }
        }, { stream: output.stream });
      }
    } finally {
      source.stop();
      output.stream.getTracks().forEach((track) => track.stop());
      await ctx.close();
    }
  }

  await withCaptureRequests(async (seen) => {
    const voice = await createVoice(config);
    try {
      const heard = voice.listen(segmented);
      await until(() => seen.requests.length === 1, 'pending STT before stop');
      voice.stop();
      seen.requests[0].reply('the part already recorded');
      check('stop during STT retains that result without opening a new recorder segment',
        await within(heard, 2000, 'stop during STT') === 'the part already recorded'
          && seen.recordings === 1 && !seen.requests[0].signal.aborted);
      check('pause after a settled capture does not duplicate a previously returned answer',
        await voice.pause() === '' && tracksEnded(seen));
    } finally { await voice.dispose(); }
  });

  await withCaptureRequests(async (seen) => {
    const voice = await createVoice(config);
    try {
      const interims = [];
      const heard = voice.listen({
        ...segmented, onInterim: (text) => interims.push(text),
        onPhase: (phase) => seen.events.push(phase),
      });
      await until(() => seen.requests.length === 1, 'first recorder segment');
      check('recorder phases follow real capture start and an actual transcription request',
        seen.events.join(',') === 'capture-start,listening,request-start,transcribing',
        seen.events.join(','));
      seen.requests[0].reply('the first segment');
      await until(() => seen.requests.length === 2, 'pending STT before pause');
      const expected = 'capture-start,listening,request-start,transcribing,'
        + 'capture-start,listening,request-start,transcribing';
      check('the next recorder segment returns to listening only when capture actually restarts',
        seen.events.join(',') === expected, seen.events.join(','));
      const draft = voice.pause();
      let settled = false;
      draft.then(() => { settled = true; });
      await wait(30);
      check('pause releases recorder tracks immediately but permits started transcription to finish',
        tracksEnded(seen) && !seen.requests[1].signal.aborted && !settled);
      seen.requests[1].reply('and its finalized unsent continuation');
      check('paused transcription returns only the unsent draft with no late interim or next segment',
        await heard === 'the first segment and its finalized unsent continuation'
          && await draft === 'the first segment and its finalized unsent continuation'
          && interims.length === 1 && interims[0] === 'the first segment'
          && seen.recordings === 2 && seen.requests.length === 2);
      check('a paused STT completion cannot restore a listening or transcribing phase',
        seen.events.join(',') === expected, seen.events.join(','));
    } finally { await voice.dispose(); }
  });

  for (const action of ['abort', 'dispose']) {
    await withCaptureRequests(async (seen) => {
      const voice = await createVoice(config);
      try {
        const interims = [];
        const phases = [];
        const heard = voice.listen({
          ...segmented, onInterim: (text) => interims.push(text),
          onPhase: (phase) => phases.push(phase),
        });
        await until(() => seen.requests.length === 1, 'pending STT before ' + action);
        voice[action]();
        check(`${action} forwards cancellation to pending STT and discards the capture promptly`,
          seen.requests[0].signal.aborted && await within(heard, 500, action + ' STT') === '');
        await wait(50);
        check(`${action} cannot publish a late segment or start another recording`,
          seen.recordings === 1 && seen.requests.length === 1 && interims.length === 0);
        check(`${action} during STT cannot emit a late phase`,
          phases.join(',') === 'listening,transcribing', phases.join(','));
        if (action === 'dispose') {
          check('disposal releases recorder resources while STT is pending', tracksEnded(seen));
          const again = await outcome(voice.listen());
          check('a disposed STT controller is terminal', /disposed/.test(again.error?.message || ''));
        }
      } finally { await voice.dispose(); }
    });
  }

  await withCaptureRequests(async (seen) => {
    const voice = await createVoice(config);
    try {
      const interims = [];
      const phases = [];
      const first = voice.listen({
        ...segmented, onInterim: (text) => interims.push(text),
        onPhase: (phase) => phases.push(phase),
      });
      await until(() => seen.requests.length === 1, 'uncancellable STT');
      voice.abort();
      check('abort settles even if a transcription transport ignores its signal',
        await within(first, 500, 'uncancellable STT abort') === '');
      let levels = 0;
      const second = voice.listen({
        autoStop: false, onLevel: () => { levels += 1; }, onInterim: (text) => interims.push(text),
      });
      await until(() => levels > 2, 'new capture after abort');
      seen.requests[0].reply('stale text must not escape');
      const before = levels;
      await wait(900);
      check('a late cancelled STT reply cannot stop or overwrite a later capture',
        levels > before && interims.length === 0 && seen.recordings === 2
          && phases.join(',') === 'listening,transcribing');
      voice.stop();
      await until(() => seen.requests.length === 2, 'new capture transcription');
      seen.requests[1].reply('only the new capture survives');
      check('a controller remains reusable after abort without leaking the old answer',
        await second === 'only the new capture survives' && seen.streams.length === 1);
    } finally { await voice.dispose(); }
  }, { ignoreAbort: true });

  await withCaptureRequests(async (seen) => {
    const voice = await createVoice(config);
    try {
      const heard = outcome(voice.listen(segmented));
      await until(() => seen.requests.length === 1, 'STT failure during pause');
      const draft = outcome(voice.pause());
      seen.requests[0].reply('scripted invalid transcription key', 401);
      const failure = await heard;
      const pausedFailure = await draft;
      check('pause preserves a real transcription error instead of reporting a successful empty draft',
        failure.error?.code === 'auth' && pausedFailure.error === failure.error && tracksEnded(seen));
    } finally { await voice.dispose(); }
  });

  for (const phase of ['listening', 'transcribing']) {
    for (const action of ['abort', 'dispose']) {
      await withCaptureRequests(async (seen) => {
        const voice = await createVoice(config);
        try {
          const phases = [];
          const heard = voice.listen({
            ...segmented,
            onPhase: (next) => {
              phases.push(next);
              if (next === phase) voice[action]();
            },
          });
          check(`${action} from the ${phase} callback settles without starting another capture`,
            await within(heard, 6000, 'phase cancellation') === '' && seen.recordings === 1);
          await wait(40);
          check(`${action} from the ${phase} callback cannot emit later phases or start extra STT`,
            phases.join(',') === (phase === 'listening' ? 'listening' : 'listening,transcribing')
              && seen.requests.length === (phase === 'listening' ? 0 : 1)
              && seen.requests.every((request) => request.signal.aborted),
            phases.join(','));
        } finally { await voice.dispose(); }
      });
    }
  }

  await withCaptureRequests(async (seen) => {
    const voice = await createVoice(config);
    try {
      const phases = [];
      const heard = voice.listen({
        ...segmented, gate: { ...gate, maxMs: 1 },
        onPhase: (phase) => phases.push(phase),
      });
      const text = await heard;
      check('a segment without usable speech never announces a transcription request',
        text === '' && seen.requests.length === 0 && !phases.includes('transcribing'),
        JSON.stringify({ text, phases, requests: seen.requests.length }));
    } finally { await voice.dispose(); }
  });
  await interruptedDraftChecks(check);
  await recorderEvidenceChecks(check);
}

export default async function run(check) {
  fake.install(window, { speakMs: 10 });

  try {
    // ── the accumulator ────────────────────────────────────────────────────
    // Interims rewrite the write cursor; a final settles it and moves on.

    const simple = await heard([
      { at: 10, interim: 'a tool for' },
      { at: 40, interim: 'a tool for remembering names' },
      { at: 70, final: 'a tool for remembering names' },
      { at: 90, end: true },
    ]).promise;
    check('an answer arrives as the engine finalises it',
      simple === 'a tool for remembering names', simple);

    const twoClauses = await heard([
      { at: 10, final: 'forty people over two days' },
      { at: 40, final: 'and I could name four' },
      { at: 60, end: true },
    ]).promise;
    check('two finals in one session are joined with a space',
      twoClauses === 'forty people over two days and I could name four', twoClauses);

    let seen = '';
    const withInterims = heard([
      { at: 10, interim: 'it has to work' },
      { at: 40, interim: 'it has to work in a car' },
      { at: 70, final: 'it has to work in a car' },
      { at: 90, end: true },
    ], { onInterim: (t) => { seen = t; } });
    await withInterims.promise;
    check('interim text is reported as it arrives, for the live transcript',
      seen === 'it has to work in a car', seen);

    // The spec permits an engine to re-announce an index that has already settled. An
    // accumulator built on `+=` counts the clause twice, and the user sees their own words
    // stuttered back at them in the export.
    const replayed = await heard([
      { at: 10, final: 'three seconds with one thumb' },
      { at: 40, replay: 0 },
      { at: 60, end: true },
    ]).promise;
    check('a replayed result index does not duplicate the clause',
      replayed === 'three seconds with one thumb', replayed);

    // ── Android: every draft arrives as a final ────────────────────────────
    // Reported from Android Chrome over a car's Bluetooth. With `continuous` on, Chrome there
    // reports each in-progress guess as a FINAL result at a new index, with confidence 0, so
    // nothing is ever re-announced — every index is new — and the answer came back as every
    // draft of itself. These are the drafts that produced the report, in order.

    const REPORTED = ['I', 'I want', 'I want to', 'I want to', 'I want to create',
      'I want to create', 'I want to create', 'I want to create', 'I want to create a',
      'I want to create a game', 'I want to create a game like',
      'I want to create a game like Tetris'];

    const shown = [];
    const growing = await within(heard([
      ...drafts(REPORTED, 10, 20),
      { at: 300, end: true },
    ], { onInterim: (t) => shown.push(t) }).promise, 3000, 'Android drafts');
    check('Android’s drafts come out as one sentence, not every draft of it',
      growing === 'I want to create a game like Tetris', growing);
    check('...and the live transcript never shows a word twice',
      shown.length > 0 && shown.every((t) => growing.startsWith(t)), JSON.stringify(shown));

    const onScreen = [];
    await within(heard([
      draft(10, 'I want'),
      { at: 40, interim: 'I want to create a game' },
      { at: 70, end: true },
    ], { onInterim: (t) => onScreen.push(t) }).promise, 3000, 'an interim over a draft');
    check('an interim that extends the last final replaces it on screen',
      onScreen[onScreen.length - 1] === 'I want to create a game', JSON.stringify(onScreen));

    // Press-to-talk restarts the engine every time it ends, and a restarted session can
    // open by replaying the last phrase the previous one already gave.
    const across = heard([
      draft(10, 'I want to'),
      draft(30, 'I want to create a game'),
      { at: 50, end: true },
    ], { autoStop: false });
    fake.script([[
      draft(10, 'I want to create a game'),
      draft(30, 'like'),
      draft(50, 'like Tetris'),
      { at: 70, end: true },
    ]]);
    setTimeout(() => across.stop(), 500);
    const replayedAcross = await within(across.promise, 3000, 'a replay after a restart');
    check('a restarted session that replays the last phrase does not repeat it',
      replayedAcross === 'I want to create a game like Tetris', replayedAcross);

    const twoHalves = heard([
      { at: 10, final: 'the first half of the answer' },
      { at: 30, end: true },
    ], { autoStop: false });
    fake.script([[{ at: 10, final: 'and the second half' }, { at: 30, end: true }]]);
    setTimeout(() => twoHalves.stop(), 500);
    const halves = await within(twoHalves.promise, 3000, 'a new clause after a restart');
    check('...while a new clause after a restart is still kept',
      halves === 'the first half of the answer and the second half', halves);

    // A revision never crosses a restart, which depends on each restart being told apart.
    // Taken for one session, the second sentence reads as a revised draft of the first.
    const parallel = heard([
      draft(10, 'it should'),
      draft(30, 'it should be fast'),
      { at: 50, end: true },
    ], { autoStop: false });
    fake.script([[draft(10, 'it should be cheap'), { at: 30, end: true }]]);
    setTimeout(() => parallel.stop(), 500);
    const both = await within(parallel.promise, 3000, 'a parallel sentence after a restart');
    check('...and a similar sentence after a restart is a new sentence, not a revision',
      both === 'it should be fast it should be cheap', both);

    // Confidence alone does not make a draft. An engine that sends interims is finalising
    // clauses even when it reports no confidence for them, and it must still be read that way
    // after its last interim has been finalised — when the list itself no longer shows one.
    const clauses = await within(heard([
      { at: 10, interim: 'we need a' },
      { at: 30, final: 'we need a website', confidence: 0 },
      { at: 60, interim: 'we need a' },
      { at: 80, final: 'we need a logo', confidence: 0 },
      { at: 110, end: true },
    ]).promise, 3000, 'an engine that reports no confidence');
    check('an engine that sends interims keeps every clause, whatever its confidence',
      clauses === 'we need a website we need a logo', clauses);

    // ── ending, and not ending ─────────────────────────────────────────────

    const restarted = await within(heard([
      { at: 10, final: 'the first half of the answer' },
      { at: 30, end: true },
    ]).promise, 3000, 'autoStop with final text');
    check('autoStop ends the answer at the engine endpoint', restarted.includes('first half'));

    // Android ends a session every few seconds whatever `continuous` says. Ending with
    // nothing heard must restart rather than return an empty answer while the user is
    // still thinking.
    const startsBefore = fake.recognition.startCount;
    const session = heard([{ at: 10, end: true }]);
    fake.script([[{ at: 10, final: 'and here it finally is' }, { at: 30, end: true }]]);
    const resumed = await within(session.promise, 3000, 'restart after an empty session');
    check('an endpoint with nothing heard restarts instead of giving up',
      fake.recognition.startCount > startsBefore + 1,
      `${fake.recognition.startCount - startsBefore} sessions`);
    check('...and the answer spoken after the restart is still captured',
      resumed === 'and here it finally is', resumed);

    // ── ending on a word instead of on a pause ─────────────────────────────
    // The rule lives in core/driving.js; this checks WHEN the session consults it. The two
    // interim cases below sit either side of settleMs on purpose and are the sharpest
    // tests here: an implementation that fires on the first terminal-looking interim
    // passes everything else in this file and truncates every real answer containing the
    // trigger word.

    const done = (t) => endsWithTrigger(t);

    const onFinal = await within(heard([
      { at: 10, interim: 'a tool for remembering names' },
      { at: 40, final: 'a tool for remembering names over' },
    ], { isComplete: done, settleMs: 300 }).promise, 3000, 'trigger on a final');
    check('a final ending in the trigger ends the answer at once',
      onFinal === 'a tool for remembering names over', onFinal);

    // 250 -> 600 is 350ms, SHORTER than settleMs: the speaker was mid-sentence.
    const transient = await within(heard([
      { at: 10, interim: 'it has to work in a car' },
      { at: 250, interim: 'it has to work in a car over' },
      { at: 600, interim: 'it has to work in a car over the noise of the engine' },
      { at: 900, final: 'it has to work in a car over the noise of the engine over' },
    ], { isComplete: done, settleMs: 500 }).promise, 4000, 'a transient trigger');
    check('a trigger that turns out to be mid-sentence does not end the answer',
      transient === 'it has to work in a car over the noise of the engine over', transient);

    // Nothing extends it, and no final ever arrives: the timer has to carry it.
    const stalled = await within(heard([
      { at: 10, interim: 'three seconds one thumb standing up over' },
    ], { isComplete: done, settleMs: 300 }).promise, 4000, 'a trigger the engine never finalises');
    check('a trigger the engine never finalises still ends the answer',
      /three seconds one thumb standing up/.test(stalled), stalled);
    check('...and the clause it appeared in is not thrown away',
      /over$/.test(stalled.trim()), stalled);

    // On Android a draft is a FINAL, so "final" cannot mean "settled": a trigger in a draft
    // has to wait out settleMs exactly as one in an interim does. This is the transient case
    // above as Android sends it. Judged as settled, it ended at 250ms, stuttered.
    const drafted = await within(heard([
      draft(10, 'it has to work in a car'),
      draft(250, 'it has to work in a car over'),
      draft(600, 'it has to work in a car over the noise of the engine'),
      draft(900, 'it has to work in a car over the noise of the engine over'),
    ], { isComplete: done, settleMs: 500 }).promise, 4000, 'a transient trigger in a draft');
    check('a trigger in an Android draft waits out settleMs, as one in an interim does',
      drafted === 'it has to work in a car over the noise of the engine over', drafted);

    // A one-word draft can be a whole command. "Repeat customers…" arrives first as
    // "repeat", and ending there skips the question the driver was in the middle of
    // answering. The rule here is app.js's own isComplete.
    const likeTheApp = (t) => {
      const s = parseSpeech(t);
      return s.stopped || s.kind !== 'answer';
    };
    const customers = await within(heard(drafts([
      'repeat',
      'repeat customers',
      'repeat customers are',
      'repeat customers are the whole business',
      'repeat customers are the whole business over',
    ], 10, 60), { isComplete: likeTheApp, settleMs: 300 }).promise, 4000, 'a command-shaped draft');
    check('a first draft that happens to be a command does not end the answer',
      customers === 'repeat customers are the whole business over', customers);

    // None of that may be bought by dropping anything. A trigger said on its own after a
    // sentence that opens with it is a PREFIX of that sentence, and a rule that discarded
    // "stale, shorter" results would discard the one word that ends the answer.
    const spokenAlone = await within(heard([
      { at: 10, final: 'over the years we grew' },
      { at: 40, final: 'over' },
    ], { isComplete: done, settleMs: 300 }).promise, 3000, 'a trigger said on its own');
    check('a trigger said alone after a sentence that opens with it still ends the answer',
      spokenAlone === 'over the years we grew over', spokenAlone);

    // ── failure ────────────────────────────────────────────────────────────

    // 'no-speech' means "nothing yet", not "give up" — it must not reject, and it must not
    // end a press-to-talk capture, which only the user's second tap ends.
    const quiet = heard([{ at: 10, error: 'no-speech' }, { at: 20, end: true }],
      { autoStop: false });
    fake.script([[{ at: 10, interim: 'still holding the button' }]]);
    setTimeout(() => quiet.stop(), 200);
    const harmless = await within(quiet.promise, 3000, 'no-speech');
    check('a no-speech error keeps listening rather than failing',
      typeof harmless === 'string', JSON.stringify(harmless));

    // A live engine that fails mid-answer must not throw away what it already heard.
    const partial = await within(heard([
      { at: 10, final: 'the part that did arrive' },
      { at: 40, error: 'network' },
      { at: 60, end: true },
    ]).promise, 3000, 'mid-session failure');
    check('a mid-session failure keeps the half-finished answer',
      partial === 'the part that did arrive', partial);

    let rejected = null;
    await within(heard([{ at: 10, error: 'not-allowed' }, { at: 20, end: true }]).promise
      .catch((e) => { rejected = e; }), 3000, 'denied permission');
    check('a denied microphone is reported rather than swallowed',
      rejected && /microphone/i.test(rejected.message), rejected && rejected.message);

    // ── the engine that goes deaf mid-answer ───────────────────────────────
    // One interim, then nothing at all: no result, no end, no error. probeWebSpeech guards
    // the START of a session; nothing guarded the stream. This is the iPhone failure mode
    // arriving a few seconds later than the probe can see it.

    const deafStart = performance.now();
    let deafText = null;
    let deafHung = false;
    await within(
      heard([{ at: 10, interim: 'I was in the middle of' }, { deaf: true }], { deafMs: 400 })
        .promise.then((t) => { deafText = t; }),
      4000, 'a recogniser that stops responding',
    ).catch(() => { deafHung = true; });

    check('a recogniser that goes silent mid-answer settles instead of hanging forever',
      !deafHung, deafHung ? 'the promise never resolved' : `${Math.round(performance.now() - deafStart)}ms`);
    check('...and it resolves rather than rejecting, because a dead engine is a small loss',
      !deafHung && typeof deafText === 'string', JSON.stringify(deafText));

    // Android's deaf engine is not silent: it starts, reports no-speech and ends every few
    // seconds, and the capture restarts it. Every one of those is an event, so the deaf
    // deadline never fires — and a hands-free recogniser that never heard a word, the "over"
    // that did nothing, listened for ever instead of reaching the miss ladder. Only a word
    // clears the first-word deadline, and nothing else pushes it back.
    const cycling = () => Array.from({ length: 80 }, () => [{ at: 40, error: 'no-speech' }, { at: 50, end: true }]);
    fake.script(cycling());
    const cycleStart = performance.now();
    const cycleStarts = fake.recognition.startCount;
    const cycled = await outcome(within(
      listenViaWebSpeech({ autoStop: false, firstWordMs: 400, isComplete: (t) => endsWithTrigger(t) }).promise,
      2000, 'a recogniser that restarts without ever hearing a word'));
    const restarts = fake.recognition.startCount - cycleStarts;
    check('an engine that keeps restarting with no result still reaches the first-word deadline',
      cycled.value === '' && restarts > 2,
      cycled.error ? cycled.error.message : `${Math.round(performance.now() - cycleStart)}ms, ${restarts} sessions`);
    fake.script([]);

    // A healthy Android engine does exactly the same while someone thinks, and press-to-talk
    // promises them as long as they like. Once, before the first word, those restarts stopped
    // counting as proof of life, and press-to-talk closed on a person still thinking.
    fake.script(cycling());
    let thinkingSettled = false;
    const thinking = listenViaWebSpeech({ autoStop: false, deafMs: 400 });
    const thought = outcome(thinking.promise.then((t) => { thinkingSettled = true; return t; }));
    await wait(1500);
    const openWhileThinking = !thinkingSettled;
    thinking.stop();
    const thoughtOut = await within(thought, 2000, 'press-to-talk stopped after thinking');
    check('press-to-talk over a restarting engine stays open past the deaf deadline while they think',
      openWhileThinking && thoughtOut.value === '',
      openWhileThinking ? (thoughtOut.error ? thoughtOut.error.message : JSON.stringify(thoughtOut.value))
        : 'closed before stop()');
    fake.script([]);

    // ...and once a word has arrived the first-word deadline is gone, so the same restarts
    // are a driver pausing mid-answer to change lane. Ending on them submitted half an answer
    // without its trigger word.
    const lanePause = Array.from({ length: 20 }, () => [{ at: 40, error: 'no-speech' }, { at: 50, end: true }]);
    fake.script([
      [draft(10, 'it should work offline because'), { at: 50, end: true }],
      ...lanePause,
      [draft(10, 'the venue has no signal over')],
    ]);
    const paused = await outcome(within(
      listenViaWebSpeech({
        autoStop: false, deafMs: 400, firstWordMs: 400, settleMs: 50,
        isComplete: (t) => endsWithTrigger(t),
      }).promise,
      4000, 'an answer resumed after a long pause'));
    check('a pause longer than the deaf deadline mid-answer does not cut the answer short',
      paused.value === 'it should work offline because the venue has no signal over',
      paused.error ? paused.error.message : JSON.stringify(paused.value));
    fake.script([]);

    // ── stop() and abort() ─────────────────────────────────────────────────

    const pressToTalk = heard([
      { at: 10, interim: 'still going' },
      { at: 30, interim: 'still going and going' },
    ], { autoStop: false });
    setTimeout(() => pressToTalk.stop(), 120);
    const stopped = await within(pressToTalk.promise, 3000, 'stop()');
    check('stopping keeps what the engine had captured', stopped.includes('still going'), stopped);

    const thrown = heard([{ at: 10, interim: 'never mind' }], { autoStop: false });
    setTimeout(() => thrown.abort(), 60);
    const aborted = await within(thrown.promise, 3000, 'abort()');
    check('aborting discards the capture', aborted === '', JSON.stringify(aborted));

    const finalToDiscard = heard([{ at: 10, final: 'even a settled final must be discarded' }],
      { autoStop: false });
    await wait(40);
    finalToDiscard.abort();
    check('aborting discards settled finals as well as interims',
      await finalToDiscard.promise === '');

    const OriginalRecognition = window.SpeechRecognition;
    const OriginalWebkitRecognition = window.webkitSpeechRecognition;
    let starts = 0;
    let aborts = 0;
    let stuck;
    class NeverEndsRecognition {
      constructor() { stuck = this; }
      start() {
        starts += 1;
        this.onstart?.();
        const result = [{ transcript: 'keep the settled words', confidence: 0.9 }];
        result.isFinal = true;
        this.onresult?.({ resultIndex: 0, results: [result] });
      }
      stop() {}
      abort() { aborts += 1; }
    }
    window.SpeechRecognition = window.webkitSpeechRecognition = NeverEndsRecognition;
    try {
      const capture = listenViaWebSpeech({ autoStop: false, deafMs: 0 });
      capture.stop();
      const text = await within(capture.promise, 1500, 'stop without onend');
      stuck.onend?.();
      check('an explicit stop settles and releases an engine that never sends onend',
        text === 'keep the settled words' && aborts === 1 && starts === 1);
      const discard = listenViaWebSpeech({ autoStop: false, deafMs: 0 });
      discard.abort();
      stuck.onend?.();
      check('an explicit abort settles immediately and a late onend cannot restart it',
        await within(discard.promise, 500, 'abort without onend') === '' && starts === 2);
    } finally {
      window.SpeechRecognition = OriginalRecognition;
      window.webkitSpeechRecognition = OriginalWebkitRecognition;
    }

    // ── the recorder path, which has no live text ──────────────────────────
    // A transcript only exists after the HTTP round trip, so the trigger cannot end a
    // recording the way it ends a Web Speech session. The gate ends a SEGMENT on silence
    // and the segments accumulate — which is what lets a driver pause to change lane. Real
    // audio from the synthesised device, real gate, scripted transcriber.

    const SHORT = { silenceMs: 400, minSpeechMs: 150, maxMs: 2500 };
    const STT = { kind: 'groq', apiKey: 'not-a-real-key' };

    async function withTranscripts(parts, fn) {
      const real = globalThis.fetch;
      const calls = [];
      globalThis.fetch = async (url, init) => {
        if (url === '/__result') return real(url, init);
        if (!String(url).endsWith('/audio/transcriptions')) {
          throw new Error('unexpected request in the transcription fixture');
        }
        calls.push({ url, init });
        const text = parts[Math.min(calls.length - 1, parts.length - 1)];
        return { ok: true, status: 200, statusText: 'OK', headers: { get: () => null },
          json: async () => ({ text }) };
      };
      try { return await fn(calls); } finally { globalThis.fetch = real; }
    }

    const byRecorder = await createVoice({ stt: STT, preferRecorder: true });
    check('the recorder path is what is under test here', byRecorder.mode === 'recorder');

    await withTranscripts(['a tool for remembering names', 'and it has to be fast over'],
      async (calls) => {
        const text = await byRecorder.listen({
          isComplete: done, gate: SHORT, maxSegments: 4, prompt: 'what is the idea?',
        });
        check('segments accumulate until one of them ends the answer',
          text === 'a tool for remembering names and it has to be fast over', text);
        check('...taking exactly as many transcriptions as it needed',
          calls.length === 2, `${calls.length} calls`);
        // Later segments are primed with what has already been heard, which is what helps a
        // transcriber with a name or a piece of jargon it has just met.
        const primer = calls[1].init.body.get('prompt');
        check('a later segment is primed with the answer so far',
          /remembering names/.test(primer || ''), primer);
      });

    await withTranscripts(['still going'], async (calls) => {
      const text = await byRecorder.listen({
        isComplete: done, gate: SHORT, maxSegments: 2,
      });
      // A loop around a paid network call needs a ceiling it cannot talk its way past.
      check('an answer that never finishes stops at the segment budget',
        calls.length === 2, `${calls.length} calls`);
      check('...and keeps what it heard rather than discarding it',
        text === 'still going still going', text);
    });

    await withTranscripts(['manual stop still returns words'], async (calls) => {
      const heard = byRecorder.listen({ autoStop: false });
      setTimeout(() => byRecorder.stop(), 900);
      const text = await within(heard, 4000, 'manual recorder stop');
      check('a manual recorder stop still resolves to a plain transcript string',
        typeof text === 'string' && text === 'manual stop still returns words',
        JSON.stringify(text));
      check('...and it transcribes exactly one manual capture',
        calls.length === 1, `${calls.length} calls`);
    });
    byRecorder.dispose();

    await recorderCancellationChecks(check);

    // ── the synthesiser ────────────────────────────────────────────────────

    check('the fake synthesiser reports itself supported', ttsSupported());

    await speak('What does good look like?');
    check('speaking records what was actually said',
      fake.synthesis.said(/what does good look like/i),
      fake.synthesis.spoken.map((u) => u.text).join(' | '));

    // Chrome drops an utterance past its internal watchdog and never fires onend. speak.js
    // caps its own wait for exactly that; without the cap, hands-free deadlocks on a
    // question that was never spoken.
    const dropped = fake.install(window, { speakMs: 10, dropUtterance: 1 });
    const t0 = performance.now();
    let deadlocked = false;
    // The deadline has to sit above speak.js's own cap, which scales with word count —
    // seven words is about 4.7s — or this measures the test's patience, not the code's.
    await within(speak('a question nobody will ever hear'), 9000, 'a dropped utterance')
      .catch(() => { deadlocked = true; });
    const waited = Math.round(performance.now() - t0);
    check('an utterance the engine drops does not deadlock the caller',
      !deadlocked, deadlocked ? 'speak() never resolved' : `${waited}ms`);
    check('...and it was the cap that freed it, not a quiet completion',
      !deadlocked && waited > 1000
        && dropped.synthesis.spoken.length === 1
        && dropped.synthesis.cancels >= 2 && !window.speechSynthesis.speaking,
      `${waited}ms, timed-out playback cancelled`);

    // Cancelling errors the in-flight utterance rather than ending it — Chrome's real
    // behaviour, and the path a barge-in takes. speakMs is set far beyond the deadline so
    // that finishing normally cannot be what settles this.
    fake.install(window, { speakMs: 60000 });
    const t1 = performance.now();
    const long = speak('a very long question that will be interrupted');
    setTimeout(() => window.speechSynthesis.cancel(), 50);
    let stranded = false;
    await within(long, 3000, 'a cancelled utterance').catch(() => { stranded = true; });
    check('cancelling speech settles the caller instead of stranding it',
      !stranded, stranded ? 'speak() never resolved' : `${Math.round(performance.now() - t1)}ms`);
  } finally {
    fake.restore();
    forgetVerdict();
  }
}
