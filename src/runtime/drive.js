// The hands-free loop: read a question, listen, submit, repeat, for a whole commute.
//
// Every effect is injected, so this file runs in `node --test` with no browser, no
// microphone and no timers it does not own. That is not tidiness — the loop's correctness
// is entirely about SEQUENCING (what happens after an empty capture, after an error, after
// a command), and sequencing tested through a browser is slow enough that nobody writes the
// eighth failure case. `tools/lint-purity.mjs` keeps it honest: this file names `io.speak`,
// never `window`.
//
// THE INVARIANT, stated so it is falsifiable:
//
//   From the moment driving mode is on until the user switches it off, the interview wraps,
//   or the loop gives up out loud, it is always either speaking or listening. It never comes
//   to rest waiting for a tap.
//
// An empty capture, a recogniser error and a recogniser that died all `continue`. Adding a
// `break` or a `return` to any catch here is how driving mode starts asking a driver to look
// at the screen — which is exactly what the loop this replaced did, in two places.
//
// The failure mode is deliberately a SKIPPED QUESTION rather than a stopped app. Somebody
// overtaking a truck loses one question, not the interview.

import { parseSpeech, matchAffirmation, DRIVING } from '../core/driving.js';

/**
 * Errors where trying again is noise rather than resilience. A denied permission fails
 * identically every time, and re-asking a driver to grant it is worse than standing down.
 */
const RE_FATAL = /denied|not[- ]?allowed|no microphone|microphone.*(?:in use|unavailable)/i;

/** Consecutive questions given up before we conclude nobody is there. */
const MAX_BLIND_SKIPS = 2;
/** Below this many answers the export is worthless, so a misheard "wrap it up" is refused. */
const MIN_TURNS_TO_WRAP = 4;
/** Times to re-ask an unclear yes/no before carrying on regardless. */
const CONFIRM_TRIES = 2;

const SAY = {
  miss: 'I didn’t catch that. Take your time, and say “%s” when you’re done.',
  again: 'Let me read that again.',
  giveUpOne: 'I’ll come back to that one.',
  standDown: 'I can’t hear you, so I’ve switched hands-free off. '
    + 'Tap the microphone when you’re ready.',
  lostMic: 'I’ve lost the microphone, so I’ve switched hands-free off.',
  scratched: 'Scratched — go again.',
  tooEarly: 'We’ve only just started — a few more questions first.',
  confirm: 'Shall I write it up? Say yes, or say keep going.',
  unclear: 'Sorry — say yes to write it up, or say keep going.',
  carryOn: 'Right — carrying on.',
};

/**
 * @param {{
 *   speak:      (text: string) => Promise<void>,
 *   listen:     (opts: {prompt: string}) => Promise<string>,  // RAW, trigger included
 *   openTurn:   () => object|null,
 *   submit:     (text: string) => Promise<void>,
 *   skip:       () => Promise<void>,
 *   wrap:       () => Promise<void>,
 *   offerWrap:  () => string|null,
 *   advisory:   (reason: string) => string|null,
 *   notify:     (msg: string) => void,
 *   running:    () => boolean,
 *   config?:    {trigger?: string},
 * }} io
 * @returns {{run: () => Promise<'stopped'|'wrapped'|'done'>, stop: () => void}}
 */
export function createDriveLoop(io) {
  const cfg = { ...DRIVING, ...(io.config || {}) };
  const trigger = cfg.trigger;

  let stopped = false;
  let misses = 0;
  let blindSkips = 0;
  let offered = false;
  let answers = 0;
  /**
   * Which question has already been read out, so it is not read twice.
   *
   * Keyed by id rather than by object identity: every reducer in session.js returns a NEW
   * session, so the object `openTurn` hands back is not guaranteed to be the same one twice
   * even while the question has not changed. Identity worked until it quietly did not.
   */
  let spokenFor = null;
  const keyOf = (turn) => (turn && (turn.id || turn.question)) || null;

  const live = () => !stopped && io.running();

  async function say(text) {
    if (!text) return;
    io.notify(text);
    await io.speak(text);
  }

  /** What gets read aloud: the bridge and the question, never the chips. */
  const spoken = (turn) => (turn.bridge ? `${turn.bridge} ${turn.question}` : turn.question);

  /**
   * The wrap offer, asked rather than announced.
   *
   * Two unclear answers means carry on. Wrapping ends the interview and cannot be undone by
   * voice, so a guess is the one mistake here with no recovery.
   */
  async function askToWrap(reason) {
    offered = true;
    await say([io.advisory(reason), SAY.confirm].filter(Boolean).join(' '));

    for (let i = 0; i < CONFIRM_TRIES && live(); i++) {
      let heard = '';
      try {
        heard = await io.listen({ prompt: SAY.confirm, confirm: true });
      } catch {
        return false;                         // a failed confirm is not a yes
      }
      const said = parseSpeech(heard, { trigger });
      if (said.kind === 'wrap') return true;
      const verdict = matchAffirmation(said.text);
      if (verdict === true) return true;
      if (verdict === false) { await say(SAY.carryOn); return false; }
      if (i < CONFIRM_TRIES - 1) await say(SAY.unclear);
    }
    return false;
  }

  /**
   * Nothing usable was captured. The ladder, and the only place the loop is allowed to end
   * itself: re-prompt, then re-read the question, then give that question up — and only
   * after several questions in a row have gone that way, conclude nobody is listening.
   *
   * @returns {Promise<boolean>} false to stand down
   */
  async function recoverFromMiss() {
    misses += 1;

    if (misses === 1) {
      await say(SAY.miss.replace('%s', trigger));
      return true;                            // re-listen; do NOT re-read the question
    }
    if (misses === 2) {
      await say(SAY.again);
      spokenFor = null;                       // they may simply never have heard it
      return true;
    }

    misses = 0;
    blindSkips += 1;
    await say(SAY.giveUpOne);
    await io.skip();
    answers += 1;
    spokenFor = null;

    if (blindSkips >= MAX_BLIND_SKIPS) {
      await say(SAY.standDown);
      return false;
    }
    return true;
  }

  async function run() {
    while (live()) {
      const turn = io.openTurn();
      if (!turn) return 'done';

      // Asked before the next question is read, or the offer arrives buried under it.
      if (!offered && answers >= 1) {
        const reason = io.offerWrap();
        if (reason) {
          if (await askToWrap(reason)) { await io.wrap(); return 'wrapped'; }
          if (!live()) break;
        }
      }

      if (spokenFor !== keyOf(turn)) {
        await say(spoken(turn));
        spokenFor = keyOf(turn);
      }
      if (!live()) break;

      let heard = '';
      try {
        heard = await io.listen({ prompt: turn.question });
      } catch (err) {
        if (!live()) break;
        // Everything else has already been retried inside the provider's own backoff, so
        // a second attempt here would be a third. Treat it as a miss and let the ladder
        // decide when to give up.
        if (RE_FATAL.test((err && err.message) || '')) { await say(SAY.lostMic); return 'stopped'; }
        if (!(await recoverFromMiss())) return 'stopped';
        continue;
      }
      if (!live()) break;

      const said = parseSpeech(heard, { trigger });

      if (said.kind === 'answer' && !said.text.trim()) {
        if (!(await recoverFromMiss())) return 'stopped';
        continue;
      }
      misses = 0;
      blindSkips = 0;

      if (said.kind === 'repeat') { spokenFor = null; continue; }
      if (said.kind === 'scratch') { await say(SAY.scratched); continue; }

      if (said.kind === 'skip') {
        await io.skip();
        answers += 1;
        spokenFor = null;
        continue;
      }

      if (said.kind === 'wrap') {
        // A misheard command must not end an interview that has nothing in it yet.
        if (answers < MIN_TURNS_TO_WRAP) { await say(SAY.tooEarly); spokenFor = null; continue; }
        await io.wrap();
        return 'wrapped';
      }

      await io.submit(said.text);
      answers += 1;
      spokenFor = null;
    }
    return 'stopped';
  }

  return { run, stop() { stopped = true; } };
}
