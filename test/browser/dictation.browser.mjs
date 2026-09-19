// The dictation session's event state machine, driven by a scripted recogniser.
//
// listenViaWebSpeech is the least-tested and most rule-laden code in the app: it walks
// `resultIndex`, accumulates finals across sessions, restarts itself when the engine ends
// early, keeps a half-finished answer when a live engine fails, and decides on its own when
// an answer is over. Every one of those is a correctness rule stated only in a comment,
// because until now there was no way to make a recogniser say a particular thing at a
// particular moment.
//
// There is now. The fixture replaces the constructor — which works only because
// webspeech.js resolves it lazily — so each test below is a transcript arriving on a
// timeline, and the assertion is what the app decided the answer was.

import { listenViaWebSpeech, forgetVerdict } from '../../src/voice/webspeech.js';
import { speak, ttsSupported } from '../../src/voice/speak.js';
import { endsWithTrigger } from '../../src/core/driving.js';
import { createVoice } from '../../src/voice/index.js';

await import('./fixtures/fake-voice.js');
const fake = window.__FakeVoice;

/** Script one utterance and run a dictation session over it. */
function heard(steps, opts = {}) {
  fake.script([steps]);
  return listenViaWebSpeech({ autoStop: true, ...opts });
}

/** A session that will not settle on its own gets a deadline, so a hang is a failure. */
function within(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`timed out: ${label}`)), ms)),
  ]);
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
    byRecorder.dispose();

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
        && dropped.synthesis.spoken[0].endedAt === null, `${waited}ms, never ended`);

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
