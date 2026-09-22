// The browser's own recogniser, where it genuinely works.
//
// When it works it is the best option available: free, instant, and it shows words as you
// say them. The problem is that feature detection is a lie on the exact platform this app
// most needs — inside an installed iOS home-screen app, `webkitSpeechRecognition` exists,
// constructs, and `start()` returns without throwing, and then nothing ever happens. No
// error, no result, no end event.
//
// So the only trustworthy test is behavioural: start it, and require the engine to prove
// it is alive by firing `start` or `audiostart` within a short window. Anything else —
// silence, or Edge's immediate `network` error, or Firefox's disabled-by-default pref —
// is treated as unavailable and the recorder path takes over for good.
//
// Also note `continuous` is a documented no-op on Android Chrome, so a long dictated
// answer arrives as several sessions. We restart until the caller says stop rather than
// trusting the flag.

const VERDICT_KEY = 'ideaforge.webspeech';
/**
 * Bumped when the MEANING of a stored verdict changes, so every value written under the old
 * meaning is discarded rather than believed. v1 cached a 'dead' that a pending microphone
 * prompt had caused — a verdict about a human reading a dialog, not about an engine — and
 * because a cached verdict short-circuits the probe, that was permanent per origin.
 */
const VERDICT_VERSION = '2';
const PROBE_MS = 1500;
/**
 * The deadline when the browser is about to ask for the microphone.
 *
 * `rec.start()` is what RAISES that prompt, so on a first run the probe is not timing an
 * engine, it is timing a person finding and tapping Allow. Nobody does that in PROBE_MS, so
 * the old budget declared every first run dead. Generous rather than precise: setupVoice
 * runs after the interview panel is already on screen, so waiting costs a late microphone
 * button and nothing else. A refusal does not wait it out — Chrome fires `not-allowed` the
 * moment they decline.
 */
const PROMPT_MS = 20000;
/**
 * How long a dictation session may go with no event of any kind before we give up on it.
 *
 * Generous, because it is a last resort and not a turn timer: a speaker thinking in silence
 * produces no events either, and cutting them off would be a worse bug than the one this
 * prevents. Lives here rather than with the driving-mode tunables because it defends
 * press-to-talk just as much — the engine does not know which button started it.
 */
const DEAF_MS = 6000;

const Impl = () => (typeof window === 'undefined'
  ? null
  : window.SpeechRecognition || window.webkitSpeechRecognition || null);

export function webSpeechPresent() {
  return !!Impl();
}

/** The remembered result of a previous probe: 'alive' | 'dead' | null. */
export function cachedVerdict() {
  try {
    const raw = localStorage.getItem(VERDICT_KEY);
    if (!raw) return null;
    const at = raw.indexOf(':');
    // An unversioned value was written by v1 and is not trusted; see VERDICT_VERSION.
    if (at < 0 || raw.slice(0, at) !== VERDICT_VERSION) return null;
    const verdict = raw.slice(at + 1);
    return verdict === 'alive' || verdict === 'dead' ? verdict : null;
  } catch { return null; }
}
function remember(verdict) {
  try {
    localStorage.setItem(VERDICT_KEY, `${VERDICT_VERSION}:${verdict}`);
  } catch { /* private window */ }
}

export function forgetVerdict() {
  try { localStorage.removeItem(VERDICT_KEY); } catch { /* private window */ }
}

/**
 * Whether the browser is about to ask for the microphone, so the probe can tell a broken
 * engine from an unanswered question.
 *
 * Firefox and Safari do not accept 'microphone' here and throw, which is why the unknown
 * case must behave exactly as before: those are the browsers the short budget and the
 * cached verdict were written for.
 */
export async function micPermissionState() {
  try {
    if (!navigator.permissions || !navigator.permissions.query) return 'unknown';
    const status = await navigator.permissions.query({ name: 'microphone' });
    return (status && status.state) || 'unknown';
  } catch {
    return 'unknown';
  }
}

/**
 * Prove the engine is alive, rather than merely present.
 *
 * @param {{lang?: string, force?: boolean, probeMs?: number, promptMs?: number}} [opts]
 *   force re-probes past a cached verdict; the two budgets are injectable for the same
 *   reason `deafMs` is — the twenty-second one is otherwise untestable in any suite anyone
 *   would be willing to wait for.
 * @returns {Promise<'alive'|'dead'>}
 */
export async function probeWebSpeech({
  lang = 'en-US', force = false, probeMs = PROBE_MS, promptMs = PROMPT_MS,
} = {}) {
  const SR = Impl();
  if (!SR) return 'dead';
  if (!force) {
    const cached = cachedVerdict();
    if (cached === 'alive' || cached === 'dead') return cached;
  }

  // A permission decision is not an engine verdict, and conflating the two is what made a
  // first run on Android permanent: the prompt went up, PROBE_MS expired while it was still
  // on screen, and 'dead' was cached for the origin for ever. So when a prompt is pending,
  // the probe waits for a person rather than for an engine, and any 'dead' it reaches is
  // reported but NOT remembered — the answer can change the next time we ask.
  const permission = await micPermissionState();
  const pending = permission === 'prompt';

  return new Promise((resolve) => {
    let settled = false;
    let rec;
    const done = (verdict, { cache = true } = {}) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { if (rec) { rec.onend = null; rec.abort(); } } catch { /* nothing to abort */ }
      if (cache) remember(verdict);
      resolve(verdict);
    };

    const timer = setTimeout(() => done('dead', { cache: !pending }), pending ? promptMs : probeMs);

    try {
      rec = new SR();
    } catch {
      done('dead');
      return;
    }
    rec.lang = lang;
    rec.interimResults = false;
    rec.continuous = false;
    // Either of these proves the engine actually engaged the microphone pipeline.
    rec.onstart = () => done('alive');
    rec.onaudiostart = () => done('alive');
    rec.onresult = () => done('alive');
    rec.onerror = (e) => {
      const kind = e && e.error;
      // 'no-speech' and 'aborted' mean it ran, which is all we asked.
      if (kind === 'no-speech' || kind === 'aborted') { done('alive'); return; }
      // Edge's 'network' is the engine failing and is worth remembering. A refusal is not:
      // the user can grant the microphone later from site settings, and an engine written
      // off over a permission would never be tried again to find out.
      const refused = kind === 'not-allowed' || kind === 'service-not-allowed';
      done('dead', { cache: !refused && !pending });
    };
    rec.onend = () => done('dead', { cache: !pending });

    try { rec.start(); } catch { done('dead', { cache: !pending }); }
  });
}

/**
 * A dictation session over the Web Speech API.
 *
 * `autoStop` ends the answer at the engine's own endpoint — the natural pause after a
 * sentence — which is what hands-free mode needs. Without it the session is restarted
 * until the caller says stop, which is what a press-to-talk button needs, and which is
 * also what makes Android usable: it ends the session every few seconds regardless of
 * `continuous`, so a restart is the only way to hear a long answer.
 *
 * `deafMs` is the stream's answer to what `probeWebSpeech` does for the start. The probe
 * proves the engine was alive when it began; nothing proved it stayed that way, and an
 * engine that emits one interim and then goes silent — no result, no end, no error — left
 * this promise unsettled for ever, with nothing on screen and nothing in the console. That
 * is the same installed-iOS failure the file header describes, arriving a few seconds later
 * than the probe can see it.
 *
 * `isComplete` is how driving mode ends an answer on a word rather than on a pause. It is a
 * CALLBACK rather than a trigger string so the rule itself stays in src/core/driving.js,
 * where it is pure and unit-tested; this file only decides WHEN to consult it. The text it
 * receives is raw, trigger included, and so is the text resolved — all stripping happens
 * above, which keeps one definition of the rule and gives the recorder path the same one.
 *
 * @param {{lang?: string, onInterim?: Function, autoStop?: boolean, deafMs?: number,
 *          isComplete?: (text: string) => boolean, settleMs?: number}} opts
 * @returns {{promise: Promise<string>, stop: Function, abort: Function}}
 */
export function listenViaWebSpeech({
  lang = 'en-US', onInterim, autoStop = false, deafMs = DEAF_MS,
  isComplete = null, settleMs = 600,
} = {}) {
  const SR = Impl();
  if (!SR) throw new Error('no speech recognition in this browser');

  const rec = new SR();
  rec.lang = lang;
  rec.interimResults = true;
  rec.continuous = true;              // honoured on desktop, a no-op on Android
  if ('maxAlternatives' in rec) rec.maxAlternatives = 1;

  let settled = false;
  let deaf = null;
  let settling = null;

  /** Resolve or reject exactly once, and stop watching. */
  function settle(fn) {
    if (settled) return;
    settled = true;
    clearTimeout(deaf);
    clearTimeout(settling);
    fn();
  }

  /**
   * Any event at all is proof of life; the deadline restarts from it.
   *
   * Giving up RESOLVES with whatever was heard rather than rejecting, because a dead engine
   * is a small loss and not an error — the same judgement speak.js makes about an utterance
   * Chrome drops. The caller re-asks; it does not need a stack trace.
   */
  function alive() {
    if (settled || !deafMs) return;
    clearTimeout(deaf);
    deaf = setTimeout(() => {
      try { rec.onend = null; rec.abort(); } catch { /* nothing to abort */ }
      settle(() => resolve(heard()));
    }, deafMs);
  }

  // Finals settled in EARLIER sessions of this same capture, kept apart from the current
  // session's. Android ends a session every few seconds whatever `continuous` says, so one
  // answer routinely spans several, and the carry is what stitches them back together.
  let carried = '';
  let sessionText = '';
  let wantMore = true;
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });

  const heard = () => [carried, sessionText].filter(Boolean).join(' ').trim();

  /**
   * Recomputed from the whole result list every time rather than appended to.
   *
   * `results` is cumulative for the session, and the spec lets an engine re-announce an
   * index that has already settled. Appending on each event counted such a clause twice and
   * put the user's own words back to them stuttered, in the export, with nothing on screen
   * to explain it.
   */
  rec.onresult = (ev) => {
    let finals = '';
    let interim = '';
    for (let i = 0; i < ev.results.length; i++) {
      const r = ev.results[i];
      if (r.isFinal) finals += (finals ? ' ' : '') + r[0].transcript.trim();
      else interim += r[0].transcript;
    }
    sessionText = finals;
    alive();
    const full = (heard() + ' ' + interim).trim();
    if (onInterim) onInterim(full);
    if (isComplete) judge(full, interim === '');
  };

  /**
   * Decide whether that was the end of the answer.
   *
   * A final that completes it ends the capture immediately. An interim only ARMS a timer,
   * for two reasons that happen to want the same delay:
   *
   *   The word might not be the end. "We went over budget" reads as complete for as long as
   *   it takes the speaker to reach "budget", and firing on the first terminal-looking
   *   interim truncates every answer containing the trigger mid-sentence — invisibly, to
   *   someone watching the road.
   *
   *   And `stop()` resolves with settled finals only. Ending the instant a trigger appears
   *   in an interim throws away the clause it appeared in, because the engine has not
   *   finalised it yet. The wait is what lets it.
   */
  function judge(text, isFinalOnly) {
    clearTimeout(settling);
    if (!isComplete(text)) return;
    if (isFinalOnly) { wantMore = false; finishNow(); return; }
    settling = setTimeout(() => { wantMore = false; finishNow(); }, settleMs);
  }

  /** End the capture, keeping whatever the engine has flushed by the time it stops. */
  function finishNow() {
    try { rec.stop(); } catch { /* already stopped; onend will settle it */ }
    // A stop that produces no onend must not strand the answer.
    setTimeout(() => settle(() => resolve(heard())), 400);
  }

  rec.onerror = (ev) => {
    alive();
    const kind = ev && ev.error;
    if (kind === 'no-speech' || kind === 'aborted') return;   // onend will settle it
    wantMore = false;
    // A mid-session failure on an engine that had been working: keep whatever was heard
    // rather than throwing away a half-finished answer.
    if (heard()) return;
    settle(() => reject(new Error(kind === 'not-allowed'
      ? 'Microphone access was denied.'
      : `Speech recognition failed (${kind || 'unknown'}).`)));
  };

  rec.onstart = alive;
  rec.onaudiostart = alive;

  rec.onend = () => {
    // In hands-free mode the engine's endpoint IS the end of the answer — but only once
    // it has actually heard something, otherwise Android's habit of ending every few
    // seconds would return an empty answer before the user finished thinking.
    if (autoStop && heard()) { settle(() => resolve(heard())); return; }
    if (wantMore) {
      carried = heard();
      sessionText = '';
      try { rec.start(); alive(); return; } catch { /* fall through and settle */ }
    }
    settle(() => resolve(heard()));
  };

  try { rec.start(); } catch (e) { settle(() => reject(new Error(`Could not start dictation: ${e.message}`))); }
  alive();

  return {
    promise,
    stop() { wantMore = false; try { rec.stop(); } catch { /* already stopped */ } },
    abort() { wantMore = false; try { rec.abort(); } catch { /* already stopped */ } },
  };
}
