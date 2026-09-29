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
//   Until the user pauses/exits, the interview wraps, or the loop explicitly stands down,
//   speaking, listening and processing are serialized. Transient failures recover without
//   waiting for a tap; cancellation never starts another effect.
//
// Empty captures and transient recogniser errors follow a bounded recovery ladder. Fatal
// failures and deliberate stops are observable, not silent exits from a supposedly live loop.
//
// The failure mode is deliberately a SKIPPED QUESTION rather than a stopped app. Somebody
// overtaking a truck loses one question, not the interview.

import { parseSpeech, matchAffirmation, spokenExamples, DRIVING } from '../core/driving.js';

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
const CANCELLED = Symbol('drive stopped');

const SAY = {
  miss: 'I didn’t catch that. Take your time, and say “%s” when you’re done.',
  again: 'Let me read that again.',
  giveUpOne: 'I’ll come back to that one.',
  keptDraft: 'Your draft is still here. I have paused listening rather than skip those words. Resume when you are ready.',
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
 *   listen:     (opts: {prompt: string, confirm?: boolean}) => Promise<string>,  // RAW, trigger included
 *   openTurn:   () => object|null,
 *   submit:     (text: string) => Promise<void>,
 *   skip:       () => Promise<void>,
 *   wrap:       () => Promise<void>,
 *   offerWrap:  () => string|null,
 *   advisory:   (reason: string) => string|null,
 *   notify:     (msg: string) => void,
 *   running:    () => boolean,
 *   answeredCount?: () => number,
 *   hasDraft?:   () => boolean,
 *   onState?:   (phase: 'speaking'|'listening'|'processing'|'recovering'|'paused'|'stopped') => void,
 *   pause?:     () => void|Promise<void>,
 *   exit?:      () => void|Promise<void>,
 *   scratch?:   () => void|Promise<void>,
 *   config?:    {trigger?: string},
 * }} io
 * @returns {{run: () => Promise<'stopped'|'wrapped'|'done'>, stop: () => void}}
 *
 * One instance owns one run; concurrent/repeated run() calls share its promise. Resume
 * deliberately creates a new instance. stop() invalidates pending work without waiting
 * for it; the UI owns media shutdown and any already-started session/draft work.
 *
 * State notifications are synchronous, deduplicated, and precede effects. Pause/exit latch
 * paused/stopped before awaiting their optional callbacks, then return 'stopped'. External
 * stop does not call either command callback. Scratch is awaited before relistening.
 *
 * AbortError is cancellation, never recovery. Other effect failures reject after announcing
 * stopped; listen errors recover unless denied or explicitly fatal/recoverable: false.
 */
export function createDriveLoop(io) {
  const cfg = { ...DRIVING, ...(io.config || {}) };
  const trigger = cfg.trigger;

  let stopped = false;
  let phase = null;
  let runPromise = null;
  let releaseStop;
  const stoppedPromise = new Promise((resolve) => { releaseStop = resolve; });
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

  function checkRunning() {
    if (!live()) throw CANCELLED;
  }

  function setState(next) {
    if (phase === next) return;
    phase = next;
    io.onState?.(next);
  }

  function halt(next = 'stopped') {
    if (stopped) return;
    stopped = true;
    releaseStop();
    setState(next);
  }

  /** Race only progression, not the effect itself: a submitted turn may still settle. */
  async function perform(next, action) {
    checkRunning();
    setState(next);
    checkRunning();
    try {
      const result = await Promise.race([action(), stoppedPromise]);
      if (stopped) throw CANCELLED;
      return result;
    } catch (err) {
      if (stopped) throw CANCELLED;             // late rejections belong to the retired run
      throw err;
    }
  }

  async function say(text) {
    if (!text) return;
    await perform('speaking', () => {
      io.notify(text);
      checkRunning();
      return io.speak(text);
    });
  }

  async function stopByCommand(kind) {
    checkRunning();
    halt(kind === 'pause' ? 'paused' : 'stopped');
    await io[kind]?.();
    return 'stopped';
  }

  const clearDraft = () => perform('processing', () => io.scratch?.());

  async function handleListenError(err) {
    checkRunning();
    if (err === CANCELLED || err?.name === 'AbortError') throw CANCELLED;
    if (err?.fatal === true || err?.recoverable === false) throw err;
    if (RE_FATAL.test(`${err?.name || ''} ${err?.message || ''}`)) {
      await say(SAY.lostMic);
      throw CANCELLED;
    }
    setState('recovering');
    checkRunning();
  }

  /** What gets read aloud: the bridge and the question. The chips follow, separately. */
  const spoken = (turn) => (turn.bridge ? `${turn.bridge} ${turn.question}` : turn.question);

  /**
   * The wrap offer, asked rather than announced.
   *
   * Two unclear answers means carry on. Wrapping ends the interview and cannot be undone by
   * voice, so a guess is the one mistake here with no recovery.
   */
  async function askToWrap(reason) {
    checkRunning();
    offered = true;
    await say([io.advisory(reason), SAY.confirm].filter(Boolean).join(' '));

    for (let i = 0; i < CONFIRM_TRIES && live(); i++) {
      let heard = '';
      try {
        heard = await perform('listening', () => io.listen({ prompt: SAY.confirm, confirm: true }));
      } catch (err) {
        await handleListenError(err);
        return false;                         // a failed confirm is not a yes
      }
      checkRunning();
      const said = parseSpeech(heard, { trigger });
      if (said.kind === 'pause' || said.kind === 'exit') {
        await stopByCommand(said.kind);
        return false;
      }
      if (said.kind === 'scratch') await clearDraft();
      if (said.kind === 'wrap') return true;
      const verdict = matchAffirmation(said.text);
      if (verdict === true) return true;
      if (verdict === false) { await say(SAY.carryOn); return false; }
      if (i < CONFIRM_TRIES - 1) await say(SAY.unclear);
    }
    return false;
  }

  /**
   * Nothing usable was captured: re-prompt, then re-read the question, then give that
   * question up. Only after several questions in a row have gone that way does a miss
   * stand the loop down.
   *
   * @returns {Promise<boolean>} false to stand down
   */
  async function recoverFromMiss() {
    checkRunning();
    setState('recovering');
    checkRunning();
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

    if (io.hasDraft?.()) {
      await say(SAY.keptDraft);
      return false;
    }
    misses = 0;
    blindSkips += 1;
    await say(SAY.giveUpOne);
    await perform('processing', () => io.skip());
    answers += 1;
    spokenFor = null;

    if (blindSkips >= MAX_BLIND_SKIPS) {
      await say(SAY.standDown);
      return false;
    }
    return true;
  }

  async function drive() {
    while (live()) {
      const turn = io.openTurn();
      checkRunning();
      if (!turn) return 'done';

      // Asked before the next question is read, or the offer arrives buried under it.
      if (!offered && answers >= 1) {
        const reason = io.offerWrap();
        if (reason) {
          if (await askToWrap(reason)) {
            await perform('processing', () => io.wrap());
            return 'wrapped';
          }
          if (!live()) break;
        }
      }

      if (spokenFor !== keyOf(turn)) {
        await say(spoken(turn));
        // Two utterances, not one longer string, and that is load-bearing twice over.
        // `speak.js` caps its own wait at 2s + words/2.6 because Chrome drops an utterance
        // that outlasts an internal ~15s watchdog without ever firing `onend`; a question
        // plus four fourteen-word chips clears that on its own. And a beat between the
        // question and its examples is how a person would say it.
        if (live()) await say(spokenExamples(turn.chips));
        spokenFor = keyOf(turn);
      }
      if (!live()) break;

      let heard = '';
      try {
        heard = await perform('listening', () => io.listen({ prompt: turn.question }));
      } catch (err) {
        await handleListenError(err);
        // Everything else has already been retried inside the provider's own backoff, so
        // a second attempt here would be a third. Treat it as a miss and let the ladder
        // decide when to give up.
        if (!(await recoverFromMiss())) return 'stopped';
        continue;
      }
      if (!live()) break;

      const said = parseSpeech(heard, { trigger });

      if (said.kind === 'pause' || said.kind === 'exit') return stopByCommand(said.kind);
      if (said.kind === 'answer' && said.stopped && !said.text.trim() && io.hasDraft?.()) {
        await perform('processing', () => io.submit(''));
        answers += 1;
        misses = blindSkips = 0;
        spokenFor = null;
        continue;
      }
      if (said.kind === 'answer' && !said.text.trim()) {
        if (!(await recoverFromMiss())) return 'stopped';
        continue;
      }
      misses = 0;
      blindSkips = 0;

      if (said.kind === 'repeat') { spokenFor = null; continue; }
      if (said.kind === 'scratch') {
        await clearDraft();
        await say(SAY.scratched);
        continue;
      }

      if (said.kind === 'skip') {
        await perform('processing', () => io.skip());
        answers += 1;
        spokenFor = null;
        continue;
      }

      if (said.kind === 'wrap') {
        // A misheard command must not end an interview that has nothing in it yet.
        const count = io.answeredCount ? io.answeredCount() : answers;
        if (!Number.isSafeInteger(count) || count < 0) {
          throw new Error('The answered turn count must be a nonnegative integer.');
        }
        if (count < MIN_TURNS_TO_WRAP) { await say(SAY.tooEarly); spokenFor = null; continue; }
        await perform('processing', () => io.wrap());
        return 'wrapped';
      }

      await perform('processing', () => io.submit(said.text));
      answers += 1;
      spokenFor = null;
    }
    return 'stopped';
  }

  function run() {
    // Defer entry until the promise is installed, including reentry from an IO callback.
    if (!runPromise) {
      runPromise = Promise.resolve().then(drive).catch((err) => {
        if (err === CANCELLED || err?.name === 'AbortError') return 'stopped';
        throw err;
      }).finally(() => halt());
    }
    return runPromise;
  }

  return { run, stop() { halt(); } };
}
