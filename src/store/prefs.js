// Device preferences that are not secrets.
//
// localStorage rather than the encrypted keyring in secrets.js, for four reasons and not
// just the obvious one:
//
//   Encryption buys nothing here — none of this is worth stealing.
//   "Forget keys" calls clearCredentials(), which would silently reset the driver's
//     trigger word along with it. That is a confusing bug nobody would connect to the
//     button they pressed.
//   The keyring loads asynchronously at boot, and the driving loop wants the trigger word
//     synchronously, before the first question is read.
//   webspeech.js already keeps its cached liveness verdict here, so this is where a
//     non-secret per-device preference lives in this codebase.
//
// Every access is wrapped: a private window throws on the first touch of localStorage, and
// a preference failing to load must never be the reason an interview cannot start.

// src/ui/theme-boot.js reads this key synchronously before first paint; rename both or neither
// (test/prefs.test.mjs checks they agree).
export const PREFS_KEY = 'ideaforge.prefs';
const KEY = PREFS_KEY;

const DEFAULTS = {
  /** The word that ends a spoken answer. Empty means "use the built-in default". */
  trigger: '',
  /** Whether hands-free was on last time, so a regular driver is not re-ticking a box. */
  handsFree: false,
  speechVoice: '',
  speechRate: 1,
  hostedSpeechAllowed: false,
  /** 'system' | 'light' | 'dark' — the same key and values the Android app stores. */
  theme: 'system',
};

/**
 * Anything but an explicit 'light' or 'dark' reads as 'system', so a hand-edited or future
 * value can never leave the page without a theme. src/ui/theme-boot.js applies the stored
 * choice before first paint and repeats this rule, because a classic script cannot import.
 */
export function themePreference(value) {
  return value === 'light' || value === 'dark' ? value : 'system';
}

export function speechPreferences(value = {}) {
  return {
    speechVoice: typeof value.speechVoice === 'string' ? value.speechVoice.slice(0, 512) : '',
    speechRate: Number.isFinite(value.speechRate)
      && value.speechRate >= 0.75 && value.speechRate <= 1.25 ? value.speechRate : 1,
  };
}

/** @returns {{trigger: string, handsFree: boolean, speechVoice: string, speechRate: number,
 *             hostedSpeechAllowed: boolean, theme: 'system'|'light'|'dark'}} */
export function loadPrefs() {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...DEFAULTS };
    const stored = JSON.parse(raw);
    if (!stored || typeof stored !== 'object') return { ...DEFAULTS };
    return {
      ...DEFAULTS, ...stored, ...speechPreferences(stored),
      hostedSpeechAllowed: stored.hostedSpeechAllowed === true,
      theme: themePreference(stored.theme),
    };
  } catch {
    return { ...DEFAULTS };
  }
}

/** Merge and write. Callers handling consent use the result to report a failed write. */
export function savePrefs(patch) {
  try {
    const next = { ...loadPrefs(), ...patch };
    localStorage.setItem(KEY, JSON.stringify({
      ...next, ...speechPreferences(next), theme: themePreference(next.theme),
    }));
    return true;
  } catch {
    return false;
  }
}
