// Reading intent out of a dictated utterance: has the speaker finished, and did they mean
// it as an answer at all.
//
// Pure string work, deliberately. Hands-free mode has to make this judgement between every
// question and the next, and getting it wrong is invisible to someone watching the road —
// a truncated answer looks exactly like a short one. So the rules live here, where
// `node --test` can hold an adversarial corpus against them without a microphone.
//
// Three rules, each earned by a failure it prevents:
//
//   1. The trigger is TERMINAL ONLY. "Over" is an ordinary English word — over budget,
//      over the years, hand it over — but it is almost never the last one. Matched
//      anywhere, every one of those truncates an answer mid-thought.
//   2. A command must be the WHOLE utterance. "I'd skip this one if I could" is an answer.
//      Substring-matching commands turns any sentence containing "wrap it up" into a
//      premature synthesis.
//   3. A command carries NO answer text. That is what stops it reaching `submitAnswer`,
//      where `RE_REFUSAL` in engine.js would read "skip this one" as a refusal and cap the
//      dimension's coverage — the command would work and quietly damage the interview.
//
// Note for anyone reaching for `contentWords` from engine.js: its STOPWORDS set contains
// "over", so it drops the default trigger word entirely. Normalising happens here instead.

export const DRIVING = {
  /** The word that ends an answer. Configurable, because how often you say it mid-sentence
   *  depends entirely on what your ideas are about. */
  trigger: 'over',
  /** How long a trigger seen in an INTERIM result must stand before it counts.
   *  Doing double duty: it lets "we went over budget" extend itself and prove it was not
   *  the end, and it gives the recogniser time to finalise — `stop()` resolves with the
   *  final text only, so firing instantly would discard the last clause. */
  settleMs: 600,
  /** No event at all from the recogniser for this long: give up on the capture rather than
   *  hanging. The engine that reports itself alive and then says nothing is a real platform
   *  bug, not a hypothetical — see webspeech.js. */
  deafMs: 6000,
  /** Consecutive captures with nothing usable before the question is re-read aloud. */
  maxMisses: 2,
};

/** Every phrase that means each command, matched against the whole utterance and nothing
 *  less. Generous, because a driver gets one go and cannot see why it did not take. */
export const COMMANDS = {
  repeat:  ['repeat', 'repeat that', 'repeat the question', 'say that again', 'say again',
            'come again', 'what was that', 'can you repeat that', 'could you repeat that'],
  skip:    ['skip', 'skip it', 'skip this', 'skip that', 'skip this one',
            'skip this question', 'next question', 'next one', 'pass', 'move on'],
  wrap:    ['wrap it up', 'wrap up', 'wrap this up', 'write it up', 'thats enough',
            'that is enough', 'finish up', 'im done', 'i am done'],
  scratch: ['scratch that', 'scratch', 'scratch that answer', 'strike that', 'ignore that',
            'forget that', 'start over', 'let me try again', 'try again'],
};

/** Filler a speaker trails after the trigger without meaning anything by it. */
const TAIL_FILLER = '(?:um+|uh+|erm?|ah+|yeah|yep|ok|okay|right|then|please|and\\s+out|out)';

/** A trigger too common or too short to be safe as the end of every answer. */
const RISKY_TRIGGERS = new Set(
  'the and a an i is it ok no yes so that this right one to of in on for'.split(' '));

// Composable, because people stack them: "yeah, do it", "no, keep going". Each alternative
// must still consume the whole utterance between them, so "no idea" stays an answer rather
// than being read as a refusal.
const RE_YES = /^(?:(?:yes|yeah|yep|yup|sure|ok|okay|do it|go ahead|please do|go on|correct)\W*)+$/i;
const RE_NO = /^(?:(?:no|nope|not yet|keep going|carry on|more questions|not done|keep asking|wait)\W*)+$/i;

/**
 * Lowercase, strip punctuation, collapse whitespace — for MATCHING only.
 *
 * Never use the result as answer text: dropping apostrophes is what lets "i'm done",
 * "im done" and "i am done" all reach one entry in COMMANDS, and it would mangle what the
 * user actually said.
 */
function normalize(text) {
  return String(text || '').toLowerCase()
    .replace(/[^a-z0-9\s]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Escape a user-supplied trigger so a stray `.` or `(` cannot break the pattern. */
function escapeRe(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** A multi-word trigger tolerates any punctuation between its words, and an optional
 *  apostrophe inside them, because a transcriber inserts both unpredictably. */
function triggerPattern(word) {
  return String(word).trim().split(/\s+/)
    .map((t) => escapeRe(t).replace(/['’]/g, "['’]?"))
    .join('[^A-Za-z0-9]+');
}

/**
 * Matches the trigger, plus any trailing filler and punctuation, at the very end.
 *
 * The leading class deliberately omits `.` `!` `?`. A comma or dash in front of the trigger
 * was almost certainly inserted because of it ("names, over") and reads as debris if kept;
 * a full stop is the speaker's own sentence ending ("...couldn't name four. Over") and
 * belongs to the answer. The two are indistinguishable at this point, so the rule is to
 * keep the punctuation that carries meaning and drop the punctuation that does not.
 */
function tailRe(trigger) {
  return new RegExp(
    `[\\s,;:—–-]*\\b${triggerPattern(trigger)}\\b`
    + `(?:[\\s,.;:!?—–-]+${TAIL_FILLER})*[\\s,.;:!?"'’]*$`,
    'i',
  );
}

/**
 * Did this utterance end with the trigger word?
 *
 * Rule 1: terminal only. "over the years" is not the end of an answer.
 */
export function endsWithTrigger(text, trigger = DRIVING.trigger) {
  const t = String(text || '');
  if (!t.trim() || !String(trigger || '').trim()) return false;
  return tailRe(trigger).test(t);
}

/** The utterance with a terminal trigger and its filler removed. Unchanged if there is none. */
export function stripTrigger(text, trigger = DRIVING.trigger) {
  const t = String(text || '');
  if (!endsWithTrigger(t, trigger)) return t.trim();
  return t.replace(tailRe(trigger), '').trim();
}

/** Politeness a driver prefixes to a command without meaning it as part of one. */
const RE_LEAD = /^(ok|okay|hey|please|app|ideaforge)\s+/i;

function commandFor(normalized) {
  const body = normalized.replace(RE_LEAD, '').trim();
  if (!body) return null;
  for (const [kind, phrases] of Object.entries(COMMANDS)) {
    if (phrases.includes(body)) return kind;
  }
  return null;
}

/**
 * Read one captured utterance.
 *
 * @param {string} text the raw transcript, trigger word and all
 * @param {{trigger?: string}} [opts]
 * @returns {{kind: 'repeat'|'skip'|'wrap'|'scratch'|'answer', text: string, stopped: boolean}}
 *
 * `kind` and `stopped` are orthogonal on purpose: "skip this one, over" is both a command
 * and a finished utterance, and one enum would lose whichever it did not encode.
 *
 * `text` is the ORIGINAL transcript minus the trigger — case and punctuation intact,
 * because it is what gets recorded as the answer. It is empty for every command (rule 3).
 */
export function parseSpeech(text, { trigger = DRIVING.trigger } = {}) {
  const raw = String(text || '');
  const stopped = endsWithTrigger(raw, trigger);
  const body = stripTrigger(raw, trigger);

  // Both forms are tested, and the order matters: "start over" ends in the default trigger,
  // so stripping first leaves "start", which is a command on its own. Checking the stripped
  // body first means "scratch that, over" still scratches; falling back to the full text
  // means "start over" is not mistaken for a bare "start".
  const kind = commandFor(normalize(body)) || commandFor(normalize(raw));

  if (kind) return { kind, text: '', stopped };          // rule 3: a command is never an answer
  return { kind: 'answer', text: body, stopped };
}

/**
 * Yes, no, or neither — for the one place the app asks a closed question.
 * Returns null rather than guessing, because wrapping up against an unclear answer ends
 * the interview and cannot be undone by voice.
 */
export function matchAffirmation(text) {
  const t = normalize(text);
  if (!t) return null;
  if (RE_YES.test(t)) return true;
  if (RE_NO.test(t)) return false;
  return null;
}

/** Clean up a hand-typed trigger. An empty one falls back rather than disabling the rule. */
export function normalizeTrigger(raw) {
  const t = String(raw == null ? '' : raw).toLowerCase()
    .replace(/[^a-z\s'’]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 24)
    .trim();
  return t || DRIVING.trigger;
}

/**
 * Why this trigger will misbehave, in words worth showing under the field — or null.
 * Advisory, never enforced: someone who genuinely wants "right" should be able to have it.
 */
export function triggerWarning(word) {
  const t = normalizeTrigger(word);
  if (t.length < 2) {
    return 'A single letter is too easy to mishear. Try a whole word.';
  }
  if (RISKY_TRIGGERS.has(t)) {
    return `“${t}” is common enough that you will say it mid-answer and be cut off. `
      + 'Something you rarely say works better.';
  }
  return null;
}
