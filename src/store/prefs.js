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
// ordinary preferences can fall back. Inference authorization uses readPrefs()'s explicit
// error instead: unreadable consent cannot authorize an interview.

// src/ui/theme-boot.js reads this key synchronously before first paint; rename both or neither
// (test/prefs.test.mjs checks they agree).
export const PREFS_KEY = 'ideaforge.prefs';
const KEY = PREFS_KEY;
export const INFERENCE_KEY = `${PREFS_KEY}.inference`;

const DEFAULTS = {
  /** The word that ends a spoken answer. Empty means "use the built-in default". */
  trigger: '',
  /** Whether hands-free was on last time, so a regular driver is not re-ticking a box. */
  handsFree: false,
  speechVoice: '',
  speechRate: 1,
  hostedSpeechAllowed: false,
  inferenceDestination: '',
  inferenceGrant: '',
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

export function inferencePreferences(value = {}) {
  return {
    inferenceDestination: typeof value.inferenceDestination === 'string'
      && value.inferenceDestination.length <= 4096 ? value.inferenceDestination : '',
    inferenceGrant: typeof value.inferenceGrant === 'string'
      && /^[a-zA-Z0-9_-]{1,128}$/.test(value.inferenceGrant) ? value.inferenceGrant : '',
  };
}

/** @returns {{trigger: string, handsFree: boolean, speechVoice: string, speechRate: number,
 *             hostedSpeechAllowed: boolean, inferenceDestination: string, inferenceGrant: string,
 *             theme: 'system'|'light'|'dark'}} */
export function loadPrefs() {
  return readPrefs().prefs;
}

/** Authorization callers must inspect error rather than treating defaults as stored consent. */
export function readPrefs() {
  let stored = {};
  let consent = {};
  let error = null;
  try {
    stored = readObject(KEY);
  } catch (cause) {
    error = cause;
  }
  try {
    consent = readObject(INFERENCE_KEY);
  } catch (cause) {
    error ||= cause;
  }
  return { prefs: {
      ...DEFAULTS, ...stored, ...speechPreferences(stored), ...inferencePreferences(consent),
      hostedSpeechAllowed: stored.hostedSpeechAllowed === true,
      theme: themePreference(stored.theme),
    }, error };
}

function readObject(key) {
  const raw = localStorage.getItem(key);
  if (!raw) return {};
  const value = JSON.parse(raw);
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Device preferences have an invalid format.');
  }
  return value;
}

function writePrefs(next) {
  try {
    localStorage.setItem(KEY, JSON.stringify({
      ...next, ...speechPreferences(next),
      inferenceDestination: undefined, inferenceGrant: undefined,
      theme: themePreference(next.theme),
    }));
    return true;
  } catch {
    return false;
  }
}

/** Merge and write. Callers handling consent use the result to report a failed write. */
export function savePrefs(patch) {
  return writePrefs({ ...loadPrefs(), ...patch });
}

export function saveInferenceConsent(destination, grant) {
  if (readPrefs().error) return false;
  // One consent-owned atomic entry: an ordinary preference writer in another tab must
  // never put its stale grant back after withdrawal.
  try {
    localStorage.setItem(INFERENCE_KEY, JSON.stringify(inferencePreferences({
      inferenceDestination: destination, inferenceGrant: grant,
    })));
    return true;
  } catch {
    return false;
  }
}
