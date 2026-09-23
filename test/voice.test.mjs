import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createSilenceGate, rmsOf, DEFAULTS, DRIVING_GATE, CONFIRM_GATE,
} from '../src/voice/vad.js';
import { createTranscriber, STT_PRESETS, MAX_AUDIO_BYTES } from '../src/voice/transcribe.js';
import { assembleTranscript } from '../src/voice/webspeech.js';
import { endsWithTrigger, parseSpeech } from '../src/core/driving.js';

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

// ───────────────────────────────────────────── giving up on silence
// Hands-free needs an exit the press-to-talk button must not have: there, silence means
// the driver never heard the question, and waiting out maxMs is two dead minutes.

test('by default the gate waits out silence rather than giving up', () => {
  // Every existing caller relies on this. A driver-shaped default would make the mic
  // button hang up on anyone who paused to think.
  const gate = createSilenceGate();
  const seen = drive(gate, quiet(200));                          // 10s of nothing
  assert.ok(!seen.includes('done'), 'silence alone must not end a press-to-talk capture');
});

test('with noSpeechMs set, silence that never becomes speech gives up', () => {
  const gate = createSilenceGate({ noSpeechMs: 800 });
  const seen = drive(gate, quiet(40));                           // 2s of nothing
  assert.ok(seen.includes('done'), 'the gate should have given up');
  assert.equal(gate.state().heardSpeech, false,
    'the caller tells "never started" from "finished" by this flag, not a new verdict');
});

test('speech before the deadline cancels the giving up', () => {
  const gate = createSilenceGate({ noSpeechMs: 800, minSpeechMs: 300 });
  drive(gate, quiet(10));                                        // 0.5s of room
  drive(gate, speech(20), 50, 500);                              // 1s of talking, past 800ms
  assert.equal(gate.state().heardSpeech, true);
  const after = drive(gate, quiet(60), 50, 1500);                // and then a real pause
  assert.ok(after.includes('done'), 'it ends as a normal answer, not as silence');
  assert.equal(gate.state().heardSpeech, true, 'and it knows something was said');
});

test('the car preset is slower to cut in and quicker to give up than the desk one', () => {
  // The direction of each change is the claim; the numbers themselves need a real drive.
  assert.ok(DRIVING_GATE.silenceMs > DEFAULTS.silenceMs, 'a lane change is a longer pause');
  assert.ok(DRIVING_GATE.speechFactor > DEFAULTS.speechFactor, 'road noise lifts the floor');
  assert.ok(DRIVING_GATE.minThreshold > DEFAULTS.minThreshold);
  assert.ok(DRIVING_GATE.maxMs < DEFAULTS.maxMs, 'a segment is not the whole answer');
  assert.ok(DRIVING_GATE.noSpeechMs > 0, 'hands-free must be able to give up');
  assert.ok(CONFIRM_GATE.maxMs < DRIVING_GATE.maxMs, 'a yes or no is not an answer');
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

// ─────────────────────────────────────────────── assembling what was said
// Reported from Android Chrome over a car's Bluetooth: the answer came back as "I I want I
// want to …". With `continuous` on, Chrome there reports every in-progress guess as a FINAL
// result at a new index, usually with confidence 0, and joining every final kept every
// draft. Only whole results are ever compared; the words inside one are never touched.

/** An Android draft: final, at its own index, with no confidence behind it. */
const z = (transcript, session = 0) => ({ transcript, isFinal: true, confidence: 0, session });
/** A desktop final. */
const F = (transcript, session = 0) => ({ transcript, isFinal: true, confidence: 0.9, session });
/** An interim, still being written. */
const live = (transcript, session = 0) => ({ transcript, isFinal: false, confidence: 0, session });
/** A final with no confidence from an engine that also sends interims — Edge has been
 *  reported to do this. Its finals are clauses, not drafts. */
const e = (transcript, session = 0) =>
  ({ transcript, isFinal: true, confidence: 0, session, interims: true });
const said = (...results) => assembleTranscript(results).finals;

const REPORTED = ['I', 'I want', 'I want to', 'I want to', 'I want to create',
  'I want to create', 'I want to create', 'I want to create', 'I want to create a',
  'I want to create a game', 'I want to create a game like',
  'I want to create a game like Tetris'];

test('Android’s growing drafts come out as one sentence, not every draft of it', () => {
  // Joined the old way, these twelve drafts are exactly the transcript that was reported.
  assert.equal(REPORTED.join(' '), 'I I want I want to I want to I want to create I want to '
    + 'create I want to create I want to create I want to create a I want to create a game '
    + 'I want to create a game like I want to create a game like Tetris');
  assert.equal(said(...REPORTED.map((t) => z(t))), 'I want to create a game like Tetris');
});

test('the confirmed final that closes an Android session supplies the wording', () => {
  assert.equal(said(...REPORTED.map((t) => z(t)), F('I want to create a game like Tetris.')),
    'I want to create a game like Tetris.');
});

test('a draft that changes only case and punctuation is the same words', () => {
  assert.equal(said(z('i want to create'), z('I want to create a game.'),
    z('I want to create a game, like Tetris.')), 'I want to create a game, like Tetris.');
  assert.equal(said(z('I dont'), z('I don’t know')), 'I don’t know');
});

test('a revised last word replaces the draft rather than repeating it', () => {
  // Car audio is narrowband, and the recogniser changes its mind about the newest word
  // as soon as it hears the next one.
  assert.equal(said(z('I want'), z('I want to'), z('I want to create'),
    z('I want to create a gay'), z('I want to create a game like'),
    z('I want to create a game like Tetris')), 'I want to create a game like Tetris');
});

test('a word revised in the middle of a draft is forgiven the same way', () => {
  assert.equal(said(z('I'), z('I want'), z('I want to great'), z('I want to great a game'),
    z('I want to create a game like')), 'I want to create a game like');
});

test('the confirmed final may spell a number differently without stuttering', () => {
  const grow = (sentence) => sentence.split(' ').map((_, n, w) => z(w.slice(0, n + 1).join(' ')));
  assert.equal(said(...grow('we had forty people'), F('We had 40 people.')), 'We had 40 people.');
  // Three spoken words become one written one: far more than a revision is allowed to change.
  assert.equal(said(...grow('we expect about one hundred and twenty users'),
    F('We expect about 120 users.')), 'We expect about 120 users.');
  assert.equal(said(z('twenty'), z('twenty dollars'), z('twenty dollars over'), F('$20 over')),
    '$20 over');
});

test('the confirmed final that closes an Android session replaces every draft of it', () => {
  // The final is the recogniser's last word on that audio. Where it rewords or respells what
  // the drafts wrote — across two phrases, or a contraction — neither absorb nor revise can
  // connect them, and only the sweep replaces the drafts.
  assert.equal(said(z('it has to'), z('it has to be fast'), F('It has to be quick.')),
    'It has to be quick.');
  assert.equal(said(z('we'), z('we had forty people'), z('over'), z('over two days'),
    F('We had 40 people over two days.')), 'We had 40 people over two days.');
  const done = said(z('I'), z('I am'), z('I am done'), F('I’m done'));
  assert.equal(done, 'I’m done');
  assert.equal(parseSpeech(done).kind, 'wrap', 'a respelt command is still the command');
});

test('short drafts that change a word still fold, so a spoken command stays a command', () => {
  // Commands are one to three words: exactly where a revision can share only one word. Left
  // unfolded, "skip the skip this one" is an answer — and RE_REFUSAL would read it as one.
  for (const [drafts, kind] of [
    [['skip', 'skip the', 'skip this', 'skip this one'], 'skip'],
    [['repeat', 'repeat the', 'repeat that'], 'repeat'],
    [['next', 'next one', 'next question'], 'skip'],
  ]) {
    const text = said(...drafts.map((t) => z(t)));
    assert.equal(text, drafts[drafts.length - 1]);
    assert.equal(parseSpeech(text).kind, kind, text);
  }
  assert.equal(said(z('I'), z('I won'), z('I want'), z('I want to'), z('I want to create')),
    'I want to create');
});

test('a session’s very first draft can be revised, and a one-word draft completed', () => {
  assert.equal(said(z('I won'), z('I want'), z('I want to'), z('I want to build')),
    'I want to build');
  assert.equal(said(z('I'), z('I’m'), z('I’m going'), z('I’m going to build it')),
    'I’m going to build it');
  const enough = said(z('that'), z('that’s'), z('that’s enough'));
  assert.equal(enough, 'that’s enough');
  assert.equal(parseSpeech(enough).kind, 'wrap');
});

test('a corrected first word is a revision while the rest of the opening stands', () => {
  assert.equal(said(z('hi'), z('hi want'), z('I want to create'), z('I want to create a game')),
    'I want to create a game');
  assert.equal(said(z('I think so'), z('so what else')), 'I think so so what else',
    'a shared word is not a shared opening');
});

test('a confirmed final corrects a misheard draft even in a one-word session', () => {
  // Car Bluetooth is narrowband; "kip" for "skip" is the kind of first guess it produces.
  for (const [drafts, final, kind] of [
    [['kip'], 'skip', 'skip'],
    [['past'], 'Pass.', 'skip'],
    [['I am done'], 'I’m done.', 'wrap'],
    [['skip the', 'skip this one'], 'Skip this one', 'skip'],
    [['say the', 'say that again'], 'Say that again', 'repeat'],
    [['let my', 'let me tri', 'let me try again'], 'Let me try again.', 'scratch'],
  ]) {
    const text = said(...drafts.map((t) => z(t)), F(final));
    assert.equal(text, final);
    assert.equal(parseSpeech(text).kind, kind, text);
  }
});

test('a sentence restarted after a pause is swept whole by the final that closes it', () => {
  // The restart re-says words the last session ended on, so its drafts carry them in. The
  // final opens with those words too, so it stands for them and may replace the drafts.
  const grow = (sentence, s) => sentence.split(' ').map((_, n, w) => z(w.slice(0, n + 1).join(' '), s));
  assert.equal(said(...grow('we expect about', 0), F('We expect about'),
    ...grow('we expect about one hundred and twenty users', 1), F('We expect about 120 users.', 1)),
  'We expect about 120 users.');
  assert.equal(said(z('so'), F('So.'), ...grow('so we had forty people', 1),
    F('So we had 40 people.', 1)), 'So we had 40 people.');
  assert.equal(said(z('it has to'), z('it has to work offline'), F('It has to work offline.'),
    z('it has to work offline and', 1), z('it has to work offline and I do not', 1),
    z('it has to work offline and I do not know why', 1),
    F('It has to work offline, and I don’t know why.', 1)),
  'It has to work offline, and I don’t know why.');
});

test('two words the recogniser merges into one fold like any other revision', () => {
  assert.equal(said(z('I want'), z('I want to create a game like'),
    z('I want to create a game like Tet'), z('I want to create a game like Tet risk'),
    z('I want to create a game like Tetris')), 'I want to create a game like Tetris');
  assert.equal(said(z('we'), z('we are'), z('we are a head of'), z('we are ahead of them')),
    'we are ahead of them');
});

test('a draft that shrinks by a word and then grows again is still one sentence', () => {
  assert.equal(said(z('I want to create a'), z('I want to create'), z('I want to create a game')),
    'I want to create a game');
  assert.equal(said(z('I want'), z('I want to create a'), z('I want to create')),
    'I want to create', 'a draft one word shorter that keeps the rest is a revision: the newest wins');
});

test('one result can absorb several kept results at once', () => {
  assert.equal(said(F('hello there'), F('how are you'), F('hello there how are you')),
    'hello there how are you');
});

test('a phrase replayed from the last session survives the final that closes the next', () => {
  // The replayed draft now carries the earlier session's words. A final that only confirms
  // the new ones must not sweep it away with the drafts it really does replace.
  assert.equal(said(F('I want to create a game'), z('I want to create a game', 1), z('like', 1),
    z('like Tetris', 1), F('like Tetris.', 1)), 'I want to create a game like Tetris.');
});

test('a restarted session that replays the last phrase does not repeat it', () => {
  assert.equal(said(z('I want to'), z('I want to create a game'),
    z('I want to create a game', 1), z('like', 1), z('like Tetris', 1)),
  'I want to create a game like Tetris');
  assert.equal(said(F('the first half of the answer'), F('the first half of the answer', 1)),
    'the first half of the answer', 'a desktop replay across a restart too');
});

test('two Android sessions become two clauses', () => {
  assert.equal(said(z('it has to be'), z('it has to be fast'),
    z('and', 1), z('and it has to work', 1), z('and it has to work offline', 1)),
  'it has to be fast and it has to work offline');
});

test('a missing or negative confidence is read as a draft', () => {
  // Android documents its confidence scores as optional, and -1 as "none".
  const bare = (transcript) => ({ transcript, isFinal: true, session: 0 });
  const none = (transcript) => ({ transcript, isFinal: true, confidence: -1, session: 0 });
  assert.equal(said(...REPORTED.map(bare)), 'I want to create a game like Tetris');
  assert.equal(said(bare('I want'), bare('I want to create a gay'),
    bare('I want to create a game like')), 'I want to create a game like');
  assert.equal(assembleTranscript([bare('we went over')]).settled, false);
  assert.equal(assembleTranscript([none('we went over')]).settled, false);
});

// What must NOT change. Desktop Chrome splits one answer into disjoint finals, and every
// rule above has to leave those alone.

test('desktop finals are separate clauses, joined with a space', () => {
  assert.equal(said(F('forty people over two days'), F(' and I could name four')),
    'forty people over two days and I could name four');
});

test('parallel sentences are all kept, on any engine', () => {
  assert.equal(said(F('it has to be fast'), F('it has to be simple'), F('it has to be cheap')),
    'it has to be fast it has to be simple it has to be cheap');
  assert.equal(said(z('it should'), z('it should be cheap'), z('it should be simple', 1)),
    'it should be cheap it should be simple', 'a revision never crosses a restart');
  assert.equal(said(e('it has to be fast'), e('it has to be simple')),
    'it has to be fast it has to be simple', 'nor to finals from an engine that sends interims');
});

test('an engine that reports no confidence at all never has a sentence revised away', () => {
  // Confidence alone does not make a draft. A session that has sent an interim is finalising
  // clauses, so its finals are never revised or swept, however alike they look — including
  // after a false start that the next sentence absorbs.
  assert.equal(said(e('we need'), e('we need a website'), e('we need a logo')),
    'we need a website we need a logo');
  assert.equal(said(e('it should'), e('it should work offline'), e('it should remember names'),
    e('it should be cheap')), 'it should work offline it should remember names it should be cheap');
  assert.equal(said(e('I think'), e('I think we should build an app'), e('I think we should build a game')),
    'I think we should build an app I think we should build a game');
  assert.equal(said(e('it has to be fast'), F('It has to be cheap.')),
    'it has to be fast It has to be cheap.', 'nor does a confirmed final sweep them');
});

test('a session that has sent an interim stays one, after every interim is finalised', () => {
  // `interims` carries what the list forgets: once the last interim is finalised, nothing
  // in it shows the session ever had one. The list's own interims count too.
  assert.equal(said(z('it has to be fast'), z('it has to be cheap'), live('and')),
    'it has to be fast it has to be cheap');
  assert.equal(said(z('it has to be fast'), z('it has to be cheap')), 'it has to be cheap',
    'from a session with no interims, two drafts of one shape are the same speech revised');
});

test('Android parallel sentences whose drafts start again from the top are both kept', () => {
  // The case a looser rule gets wrong: once "it" and "it should" are kept rather than
  // dropped, "it should be cheap" is compared with "it should be", never with "…fast".
  assert.equal(said(z('it'), z('it should'), z('it should be'), z('it should be fast'),
    z('it'), z('it should'), z('it should be'), z('it should be cheap')),
  'it should be fast it should be cheap');
});

test('a phrase restarting with the last one’s opening words is not read as a shrunken draft', () => {
  // Two words short of "we need a website", "we need" is a new phrase beginning, not the
  // same one losing words. Revising there would let "we need a logo" wipe out the website.
  assert.equal(said(z('we'), z('we need'), z('we need a website'), z('we need'), z('we need a logo')),
    'we need a website we need a logo');
});

test('a draft that drops two words and grows back past them is still one sentence', () => {
  assert.equal(said(z('I want to create a game'), z('I want to'), z('I want to create a game like')),
    'I want to create a game like');
});

test('a trigger spoken on its own after a sentence that opens with it is kept', () => {
  // "over" is a prefix of "over the years we grew". Discarding shorter results as stale
  // would discard the one word that ends the answer, and driving mode would never finish.
  const desk = said(F('over the years we grew'), F('over'));
  assert.equal(desk, 'over the years we grew over');
  assert.ok(endsWithTrigger(desk));
  assert.equal(said(F('over the years we grew'), z('over', 1)), 'over the years we grew over');
});

test('a clause that trails off into its own opening words is kept', () => {
  assert.equal(said(F('we should sell to schools'), F('we should')),
    'we should sell to schools we should');
});

test('repeats inside one result are never touched', () => {
  assert.equal(said(F('very very good, no no no'), F('a game a game')),
    'very very good, no no no a game a game');
  assert.equal(said(z('no'), z('no no'), z('no no no')), 'no no no');
});

test('a command inside a longer answer survives the merge and stays an answer', () => {
  const text = said(F('skip this one'), F('if I could'));
  assert.equal(text, 'skip this one if I could');
  assert.equal(parseSpeech(text).kind, 'answer');
});

test('words outside ASCII are compared as words', () => {
  assert.equal(said(F('café au lait'), F('naïve idea')), 'café au lait naïve idea');
  assert.equal(said(z('café'), z('café au lait')), 'café au lait');
  assert.equal(said(z('привет'), z('привет мир')), 'привет мир',
    'an ASCII-only normaliser would find no words here and throw the whole answer away');
  assert.equal(said(F('naïve idea'), F('—')), 'naïve idea', 'a dash alone is not a word');
});

test('empty, blank and punctuation-only results are ignored', () => {
  assert.deepEqual(assembleTranscript([]), { finals: '', text: '', settled: false });
  assert.deepEqual(assembleTranscript(), { finals: '', text: '', settled: false });
  assert.equal(said(F(''), F('   '), F(' a tool '), F('.'), null, F('for names ')),
    'a tool for names');
  assert.equal(said({ isFinal: true, confidence: 0.9 }, F('still here')), 'still here');
});

test('the same phrase said twice as two results is kept once — a deliberate cost', () => {
  // Telling a restatement from a re-sent draft needs the audio, which the page never sees.
  // Losing one copy of a repeated phrase is the price of never repeating a whole draft.
  assert.equal(said(F('No.'), F('No.')), 'No.');
  assert.equal(said(F('I think'), F('I think we should')), 'I think we should');
});

// The live transcript, and what the trigger rule is shown.

test('an interim that extends the last final replaces it on screen', () => {
  const now = assembleTranscript([z('I want'), live('I want to create a game')]);
  assert.equal(now.text, 'I want to create a game');
  assert.equal(now.finals, 'I want', 'what resolves is still only what was final');
});

test('a desktop interim is appended to what has settled', () => {
  const now = assembleTranscript([F('forty people over two days'), live(' and I'), live(' could')]);
  assert.equal(now.text, 'forty people over two days and I could');
  assert.equal(now.finals, 'forty people over two days');
});

test('an interim that repeats the last final adds nothing', () => {
  assert.equal(assembleTranscript([F('skip this'), live('Skip this.')]).text, 'Skip this.');
});

test('only a final the engine stands behind is settled', () => {
  // `settled` decides whether a trigger ends the answer at once or waits out settleMs.
  // On Android every draft is final, so "final" alone would end "we went over budget" at
  // "we went over", and take the first draft of "repeat customers…" as a command.
  assert.equal(assembleTranscript([F('we went over')]).settled, true);
  assert.equal(assembleTranscript([z('we went over')]).settled, false);
  assert.equal(assembleTranscript([F('we went'), live(' over')]).settled, false);
  assert.equal(assembleTranscript([z('we'), F('we went over')]).settled, true,
    'the confirmed final at the end of an Android session settles it');
});
