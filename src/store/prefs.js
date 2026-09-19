// Device preferences that are not secrets.
//
// localStorage rather than the encrypted keyring in secrets.js, for four reasons and not
// just the obvious one:
//
//   Encryption buys nothing here — none of this is worth stealing.
//   "Forget my key" calls clearCredentials(), which would silently reset the driver's
//     trigger word along with it. That is a confusing bug nobody would connect to the
//     button they pressed.
//   The keyring loads asynchronously at boot, and the driving loop wants the trigger word
//     synchronously, before the first question is read.
//   webspeech.js already keeps its cached liveness verdict here, so this is where a
//     non-secret per-device preference lives in this codebase.
//
// Every access is wrapped: a private window throws on the first touch of localStorage, and
// a preference failing to load must never be the reason an interview cannot start.

const KEY = 'ideaforge.prefs';

const DEFAULTS = {
  /** The word that ends a spoken answer. Empty means "use the built-in default". */
  trigger: '',
  /** Whether hands-free was on last time, so a regular driver is not re-ticking a box. */
  handsFree: false,
};

/** @returns {{trigger: string, handsFree: boolean}} never throws, never null */
export function loadPrefs() {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...DEFAULTS };
    const stored = JSON.parse(raw);
    if (!stored || typeof stored !== 'object') return { ...DEFAULTS };
    return { ...DEFAULTS, ...stored };
  } catch {
    return { ...DEFAULTS };
  }
}

/** Merge and write. Silent on failure: a preference is not worth an error message. */
export function savePrefs(patch) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...loadPrefs(), ...patch }));
  } catch { /* private window, or the quota is full of something that matters more */ }
}
