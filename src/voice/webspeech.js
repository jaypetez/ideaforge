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
const PROBE_MS = 1500;
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
  try { return localStorage.getItem(VERDICT_KEY); } catch { return null; }
}
function remember(verdict) {
  try { localStorage.setItem(VERDICT_KEY, verdict); } catch { /* private window */ }
}
export function forgetVerdict() {
  try { localStorage.removeItem(VERDICT_KEY); } catch { /* private window */ }
}

/**
 * Prove the engine is alive, rather than merely present.
 *
 * @param {{lang?: string, force?: boolean}} [opts] force re-probes past a cached verdict
 * @returns {Promise<'alive'|'dead'>}
 */
export function probeWebSpeech({ lang = 'en-US', force = false } = {}) {
  const SR = Impl();
  if (!SR) return Promise.resolve('dead');
  if (!force) {
    const cached = cachedVerdict();
    if (cached === 'alive' || cached === 'dead') return Promise.resolve(cached);
  }

  return new Promise((resolve) => {
    let settled = false;
    let rec;
    const done = (verdict) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { if (rec) { rec.onend = null; rec.abort(); } } catch { /* nothing to abort */ }
      remember(verdict);
      resolve(verdict);
    };

    const timer = setTimeout(() => done('dead'), PROBE_MS);

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
      // 'no-speech' and 'aborted' mean it ran, which is all we asked. Edge's 'network'
      // and a denied permission mean it did not.
      done(e && (e.error === 'no-speech' || e.error === 'aborted') ? 'alive' : 'dead');
    };
    rec.onend = () => done('dead');

    try { rec.start(); } catch { done('dead'); }
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
 * @param {{lang?: string, onInterim?: Function, autoStop?: boolean, deafMs?: number}} opts
 * @returns {{promise: Promise<string>, stop: Function, abort: Function}}
 */
export function listenViaWebSpeech({
  lang = 'en-US', onInterim, autoStop = false, deafMs = DEAF_MS,
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

  /** Resolve or reject exactly once, and stop watching. */
  function settle(fn) {
    if (settled) return;
    settled = true;
    clearTimeout(deaf);
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
    if (onInterim) onInterim((heard() + ' ' + interim).trim());
  };

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
