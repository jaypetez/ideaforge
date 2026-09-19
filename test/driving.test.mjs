import test from 'node:test';
import assert from 'node:assert/strict';

import {
  DRIVING, parseSpeech, endsWithTrigger, stripTrigger,
  matchAffirmation, normalizeTrigger, triggerWarning,
} from '../src/core/driving.js';

// ───────────────────────────────────────────────── the trigger word
// The whole feature rests on this: a driver cannot see that an answer was cut off, so a
// trigger that fires mid-sentence is worse than one that never fires at all. Most of these
// cases are sentences somebody would really say with "over" in the middle of them.

test('an answer ending in the trigger is finished', () => {
  const r = parseSpeech('a tool for remembering names over');
  assert.equal(r.stopped, true);
  assert.equal(r.kind, 'answer');
  assert.equal(r.text, 'a tool for remembering names');
});

test('the trigger in the middle of a sentence is just a word', () => {
  // The case that would truncate an answer with no way for the driver to know.
  const r = parseSpeech('it has to work in a car over the noise of the engine');
  assert.equal(r.stopped, false);
  assert.equal(r.text, 'it has to work in a car over the noise of the engine');
});

test('a leading trigger is not stripped either', () => {
  const r = parseSpeech('over the years the thing I keep hitting is names over');
  assert.equal(r.stopped, true);
  assert.equal(r.text, 'over the years the thing I keep hitting is names');
});

test('the trigger survives being said twice in one answer', () => {
  const r = parseSpeech('we went over budget and then over again, over');
  assert.equal(r.stopped, true);
  // The comma goes with the trigger: a separator in front of it is transcriber debris.
  assert.equal(r.text, 'we went over budget and then over again');
});

test('a full stop belongs to the answer, a comma belongs to the trigger', () => {
  assert.equal(parseSpeech('I could name four. Over').text, 'I could name four.');
  assert.equal(parseSpeech('I could name four, over').text, 'I could name four');
});

test('"no idea" is an answer, not a refusal to wrap up', () => {
  // The composable yes/no patterns must still consume the whole utterance.
  assert.equal(matchAffirmation('no idea'), null);
  assert.equal(matchAffirmation('yes and also this other thing'), null);
});

test('an unfinished answer about going over budget is left alone', () => {
  assert.equal(endsWithTrigger('we went way over budget'), false);
  assert.equal(stripTrigger('we went way over budget'), 'we went way over budget');
});

test('trailing filler after the trigger still counts as finished', () => {
  // Real speech is "...and that's the main thing. Over. Um, yeah."
  assert.equal(parseSpeech('thats the main thing over um yeah').text, 'thats the main thing');
  assert.equal(parseSpeech('that is the main thing. Over, and out.').text,
    'that is the main thing.');
});

test('an answer keeps its own casing and punctuation', () => {
  // This text becomes the recorded answer, so normalising it would be lossy.
  const r = parseSpeech("Forty people, and I couldn't name four. Over");
  assert.equal(r.text, "Forty people, and I couldn't name four.");
});

test('a trigger word is a word, not a substring', () => {
  assert.equal(endsWithTrigger('the whole thing felt like a leftover'), false);
  assert.equal(endsWithTrigger('I want to start it over'), true);
});

test('nothing said is not a finished answer', () => {
  assert.equal(endsWithTrigger(''), false);
  assert.equal(endsWithTrigger('   '), false);
  assert.equal(parseSpeech('').kind, 'answer');
  assert.equal(parseSpeech('').text, '');
});

// ───────────────────────────────────────────────── commands
// Rule 2: the whole utterance or nothing. The asymmetry is deliberate — a command matched
// inside an answer eats the answer, while a command missed costs one repetition.

test('a bare command is a command', () => {
  assert.equal(parseSpeech('repeat that').kind, 'repeat');
  assert.equal(parseSpeech('skip this one').kind, 'skip');
  assert.equal(parseSpeech('wrap it up').kind, 'wrap');
  assert.equal(parseSpeech('scratch that').kind, 'scratch');
});

test('a sentence that merely contains a command is an answer', () => {
  // Substring matching here would silently discard the whole answer.
  const r = parseSpeech("I'd skip this one if I could, but it matters over");
  assert.equal(r.kind, 'answer');
  assert.equal(r.text, "I'd skip this one if I could, but it matters");
});

test('an answer that talks about wrapping up is still an answer', () => {
  assert.equal(parseSpeech('the hard part is knowing when to wrap it up').kind, 'answer');
});

test('a command carries no answer text', () => {
  // Rule 3. If this ever regresses, RE_REFUSAL in engine.js reads the command as a refusal
  // and caps the dimension's coverage — the command works and damages the interview.
  for (const said of ['skip this one', 'wrap it up', 'repeat that', 'scratch that']) {
    assert.equal(parseSpeech(said).text, '', `"${said}" must not reach submitAnswer`);
  }
});

test('a command spoken with the trigger is both', () => {
  const r = parseSpeech('skip this one, over');
  assert.equal(r.kind, 'skip');
  assert.equal(r.stopped, true, 'the utterance was also finished');
});

test('"start over" is a command, not a bare "start"', () => {
  // The ordering trap: stripping the trigger first leaves "start", which is nothing.
  assert.equal(parseSpeech('start over').kind, 'scratch');
});

test('politeness in front of a command is ignored', () => {
  assert.equal(parseSpeech('ok repeat that').kind, 'repeat');
  assert.equal(parseSpeech('hey skip this').kind, 'skip');
});

test('contractions are matched however the recogniser spells them', () => {
  for (const said of ["I'm done", 'im done', 'I am done']) {
    assert.equal(parseSpeech(said).kind, 'wrap', said);
  }
});

// ───────────────────────────────────────────────── the confirm exchange

test('yes and no are understood, and anything else is not guessed at', () => {
  assert.equal(matchAffirmation('yes'), true);
  assert.equal(matchAffirmation('Yeah, do it.'), true);
  assert.equal(matchAffirmation('no'), false);
  assert.equal(matchAffirmation('keep going'), false);
  assert.equal(matchAffirmation('not yet'), false);
  // Never wrap against an unclear answer: ending the interview cannot be undone by voice.
  assert.equal(matchAffirmation('well the thing is'), null);
  assert.equal(matchAffirmation(''), null);
});

// ───────────────────────────────────────────────── configuring the trigger

test('a custom trigger works the same way', () => {
  const r = parseSpeech('that is the whole idea finished', { trigger: 'finished' });
  assert.equal(r.stopped, true);
  assert.equal(r.text, 'that is the whole idea');
  // ...and the default no longer terminates.
  assert.equal(endsWithTrigger('that is the whole idea over', 'finished'), false);
});

test('a multi-word trigger tolerates the punctuation a transcriber inserts', () => {
  assert.equal(endsWithTrigger('so that is it, all done', 'all done'), true);
  assert.equal(endsWithTrigger('so that is it. All, done.', 'all done'), true);
});

test('an empty trigger falls back rather than disabling the rule', () => {
  // A blank settings field must not silently make every answer unfinishable.
  assert.equal(normalizeTrigger(''), DRIVING.trigger);
  assert.equal(normalizeTrigger('   '), DRIVING.trigger);
  assert.equal(normalizeTrigger(null), DRIVING.trigger);
});

test('a typed trigger is cleaned up before it is stored', () => {
  assert.equal(normalizeTrigger('  Over!  '), 'over');
  assert.equal(normalizeTrigger('DONE'), 'done');
});

test('a regex metacharacter in the trigger cannot break matching', () => {
  const t = normalizeTrigger('over.*');
  assert.equal(endsWithTrigger('names over', t), true, 'the cleaned trigger still matches');
  assert.doesNotThrow(() => endsWithTrigger('anything', 'a('));
});

test('a risky trigger is flagged but not refused', () => {
  assert.ok(triggerWarning('the'), 'a very common word will cut people off');
  assert.ok(triggerWarning('a'), 'a single letter is too easy to mishear');
  assert.equal(triggerWarning('over'), null);
  assert.equal(triggerWarning('bananas'), null);
});
