// Deciding when someone has stopped talking.
//
// Pure and clock-injected, so the thresholds can be tested without a microphone — which
// matters, because this is the component that decides whether hands-free mode feels
// natural or cuts you off mid-sentence.
//
// Three rules, each earned:
//   1. Silence before any speech is not an answer. Someone thinking for six seconds
//      before they start must not submit an empty recording.
//   2. A pause only ends the answer after a minimum amount of speech, so a throat-clear
//      or a single "so…" does not count as a complete reply.
//   3. The threshold is relative to the observed noise floor, not absolute. A phone on a
//      train and a laptop in a quiet room do not share a number.
//   4. Silence that never becomes speech can give up early, but only when asked to. Someone
//      who tapped the microphone gets to think; hands-free cannot afford to, because there
//      silence means the question was never heard by anyone.

export const DEFAULTS = {
  /** Silence this long after real speech ends the answer. Dictation pauses mid-sentence,
   *  so this is deliberately longer than a speech-detection VAD would use. */
  silenceMs: 1800,
  /** Below this much total speech, a pause is a pause and not the end. */
  minSpeechMs: 700,
  /** Hard stop, so a stuck microphone cannot record forever into a paid transcription. */
  maxMs: 120000,
  /** Speech is this many times louder than the measured noise floor. */
  speechFactor: 2.4,
  /** Absolute floor, so total silence never calibrates the gate down to nothing. */
  minThreshold: 0.008,
  /** Judge nothing until the room has been measured for this long. */
  calibrateMs: 350,
  /** Give up if this long passes with nothing said at all. 0 disables it, which is the
   *  press-to-talk contract: someone who opened the mic deliberately gets to think for as
   *  long as they like. Hands-free needs the opposite — silence there means the question
   *  was never heard, and waiting out `maxMs` is two minutes of a dead interview. */
  noSpeechMs: 0,
};

/**
 * A car. Broadband road noise instead of a quiet room, and a driver who stops mid-sentence
 * to change lane.
 *
 * Every number is raised from DEFAULTS for the same underlying reason: the noise floor is
 * both higher and steadier, and the pauses are longer because talking is the second task.
 * `maxMs` is the exception and goes DOWN, because under segment accumulation one recording
 * is no longer the whole answer — the trigger word ends the answer, not the gate.
 *
 * These are first guesses against an imagined car. Nothing automated can validate them;
 * one real drive is what tunes them.
 */
export const DRIVING_GATE = {
  silenceMs: 2500,
  minSpeechMs: 900,
  maxMs: 45000,
  speechFactor: 2.8,
  minThreshold: 0.012,
  calibrateMs: 500,
  noSpeechMs: 8000,
};

/** A yes or a no, nothing longer. Short windows throughout, because the app has just asked
 *  a closed question and is holding the interview open waiting for one word. */
export const CONFIRM_GATE = {
  silenceMs: 1200,
  minSpeechMs: 300,
  maxMs: 8000,
  noSpeechMs: 6000,
};

/**
 * @param {object} [opts]
 * @returns {{push: (rms: number, tMs: number) => 'idle'|'speech'|'pause'|'done',
 *            state: () => object}}
 */
export function createSilenceGate(opts = {}) {
  const cfg = { ...DEFAULTS, ...opts };

  let startedAt = null;
  let observedMin = null;
  let speechMs = 0;
  let lastAt = null;
  let quietSince = null;
  let heardSpeech = false;
  let done = false;

  return {
    /**
     * Feed one RMS sample.
     * @param {number} rms 0..1
     * @param {number} tMs monotonic milliseconds
     * @returns {'idle'|'speech'|'pause'|'done'}
     */
    push(rms, tMs) {
      if (done) return 'done';
      if (startedAt === null) { startedAt = tMs; lastAt = tMs; }
      const dt = Math.max(0, tMs - lastAt);
      lastAt = tMs;

      const elapsed = tMs - startedAt;
      if (elapsed >= cfg.maxMs) { done = true; return 'done'; }

      // The floor is the quietest sample seen so far, which works because speech is not
      // continuous: even a fast talker leaves gaps at word boundaries, so over a couple
      // of seconds the minimum is the room rather than the voice. That also means a
      // recording which opens mid-sentence corrects itself within a word or two.
      observedMin = observedMin === null ? rms : Math.min(observedMin, rms);

      // Judge nothing until the room has been heard for a moment. Without this, the very
      // first sample would set the threshold, and on a noisy train that first sample is
      // ambient hiss loud enough to look like speech forever after.
      if (elapsed < cfg.calibrateMs) return 'idle';

      const threshold = Math.max(cfg.minThreshold, observedMin * cfg.speechFactor);

      if (rms >= threshold) {
        heardSpeech = true;
        speechMs += dt;
        quietSince = null;
        return 'speech';
      }

      if (!heardSpeech) {
        // rule 4: nobody is going to say anything. Latching `done` with `heardSpeech` still
        // false is what lets the caller tell "they finished" from "they never started" —
        // there is deliberately no fifth verdict, because recorder.js branches on 'done'
        // and a new one would be silently ignored by every existing call site.
        if (cfg.noSpeechMs && elapsed >= cfg.noSpeechMs) { done = true; return 'done'; }
        return 'idle';                          // rule 1: nothing said yet
      }

      if (quietSince === null) quietSince = tMs;
      if (speechMs < cfg.minSpeechMs) return 'pause';   // rule 2: too little to be an answer
      if (tMs - quietSince >= cfg.silenceMs) { done = true; return 'done'; }
      return 'pause';
    },

    state() {
      return { speechMs, heardSpeech, noiseFloor: observedMin, done };
    },
  };
}

/** RMS of a time-domain buffer, 0..1. Uint8 samples are centred on 128. */
export function rmsOf(bytes) {
  let sum = 0;
  for (let i = 0; i < bytes.length; i++) {
    const v = (bytes[i] - 128) / 128;
    sum += v * v;
  }
  return bytes.length ? Math.sqrt(sum / bytes.length) : 0;
}
