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
 * @param {{lang?: string, onInterim?: Function, autoStop?: boolean}} opts
 * @returns {{promise: Promise<string>, stop: Function, abort: Function}}
 */
export function listenViaWebSpeech({ lang = 'en-US', onInterim, autoStop = false } = {}) {
  const SR = Impl();
  if (!SR) throw new Error('no speech recognition in this browser');

  const rec = new SR();
  rec.lang = lang;
  rec.interimResults = true;
  rec.continuous = true;              // honoured on desktop, a no-op on Android
  if ('maxAlternatives' in rec) rec.maxAlternatives = 1;

  let finalText = '';
  let wantMore = true;
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });

  rec.onresult = (ev) => {
    let interim = '';
    for (let i = ev.resultIndex; i < ev.results.length; i++) {
      const r = ev.results[i];
      if (r.isFinal) finalText += (finalText ? ' ' : '') + r[0].transcript.trim();
      else interim += r[0].transcript;
    }
    if (onInterim) onInterim((finalText + ' ' + interim).trim());
  };

  rec.onerror = (ev) => {
    const kind = ev && ev.error;
    if (kind === 'no-speech' || kind === 'aborted') return;   // onend will settle it
    wantMore = false;
    // A mid-session failure on an engine that had been working: keep whatever was heard
    // rather than throwing away a half-finished answer.
    if (finalText.trim()) return;
    reject(new Error(kind === 'not-allowed'
      ? 'Microphone access was denied.'
      : `Speech recognition failed (${kind || 'unknown'}).`));
  };

  rec.onend = () => {
    // In hands-free mode the engine's endpoint IS the end of the answer — but only once
    // it has actually heard something, otherwise Android's habit of ending every few
    // seconds would return an empty answer before the user finished thinking.
    if (autoStop && finalText.trim()) { resolve(finalText.trim()); return; }
    if (wantMore) { try { rec.start(); return; } catch { /* fall through and settle */ } }
    resolve(finalText.trim());
  };

  try { rec.start(); } catch (e) { reject(new Error(`Could not start dictation: ${e.message}`)); }

  return {
    promise,
    stop() { wantMore = false; try { rec.stop(); } catch { /* already stopped */ } },
    abort() { wantMore = false; try { rec.abort(); } catch { /* already stopped */ } },
  };
}
