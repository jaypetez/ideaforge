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
