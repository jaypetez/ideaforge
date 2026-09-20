// One `listen()` for the UI, whichever backend actually works here.
//
// The choice is made by behaviour, not by feature detection, because the platform this
// app most needs voice on — an installed iPhone PWA — is precisely the one where the
// Web Speech API reports itself present and then does nothing. See webspeech.js.
//
// Order of preference:
//   1. Web Speech, if it proves itself alive. Free, instant, shows words as you speak.
//   2. Record and transcribe. Costs about a penny an interview, works everywhere, and is
//      better than the browser on rambling, accented or jargon-heavy speech — which is
//      most of what a dictated answer to a hard question sounds like.
//   3. Neither: the keyboard, which is always there.

import { probeWebSpeech, listenViaWebSpeech, webSpeechPresent, forgetVerdict } from './webspeech.js';
import { createRecorder, micSupported } from './recorder.js';
import { createTranscriber, STT_PRESETS } from './transcribe.js';
import { speak, cancelSpeech, primeSpeech, ttsSupported } from './speak.js';

export { STT_PRESETS } from './transcribe.js';
export { primeSpeech, ttsSupported, cancelSpeech } from './speak.js';
export { forgetVerdict } from './webspeech.js';

/**
 * @param {{stt?: object, lang?: string, preferRecorder?: boolean}} opts
 *   stt — credentials for the transcription fallback; without them, voice is Web-Speech-only
 * @returns {Promise<object>} the voice controller
 */
export async function createVoice({ stt = null, lang = 'en-US', preferRecorder = false } = {}) {
  const transcriber = stt && stt.apiKey ? createTranscriber({ ...stt, language: shortLang(lang) }) : null;

  let mode = 'none';
  let reason = null;
  const browserOk = !preferRecorder
    && webSpeechPresent()
    && (await probeWebSpeech({ lang })) === 'alive';

  if (browserOk) {
    mode = 'webspeech';
  } else if (transcriber && micSupported()) {
    mode = 'recorder';
  } else if (!micSupported()) {
    reason = 'This browser cannot record audio.';
  } else {
    // The engine is present but did not answer the probe — an installed iPhone app, Edge,
    // or Firefox with the pref off. Offering a mic button here would be worse than none:
    // it would start, never fire an event, and hang with no error to show. Say what would
    // actually fix it instead.
    reason = webSpeechPresent()
      ? 'Your browser reports dictation support but it does not work here — a known bug in ' +
        'installed iPhone apps, Edge and Firefox. Add a transcription key in Settings to dictate.'
      : 'This browser has no built-in dictation. Add a transcription key in Settings to dictate.';
  }

  let recorder = null;
  let session = null;
  let aborted = false;

  /** One recording, transcribed. Resolves '' for anything too short to be an answer. */
  async function recordOnce(opts, gate) {
    session = { stop: () => recorder.stop(), abort: () => recorder.stop() };
    let state = null;
    let blob;
    try {
      blob = await recorder.record({
        autoStop: opts.autoStop !== false,
        onLevel: (rms, s) => { state = s; if (opts.onLevel) opts.onLevel(rms, s); },
        ...(gate || {}),
      });
    } finally {
      session = null;
    }
    // A quarter-second of nothing is a mis-tap, not an answer worth paying to transcribe.
    if (!blob || blob.size < 1600) return { text: '', state };
    const t = createTranscriber({
      ...stt, language: shortLang(lang), prompt: opts.prompt || undefined,
    });
    return { text: await t.transcribe(blob), state };
  }

  /**
   * Keep recording until the speaker says they are done.
   *
   * The recorder path has no live text — a transcript exists only after the HTTP round
   * trip — so the trigger word cannot end a recording the way it ends a Web Speech session.
   * Instead the gate ends a SEGMENT on silence, and the segments accumulate until one of
   * them completes the answer. That is what lets a driver pause to change lane: the pause
   * closes a segment, not the answer.
   *
   * Three independent ways out, because a loop around a paid network call needs them:
   * the answer completes, the speaker says nothing at all into a segment, or the segment
   * budget runs out. `heardSpeech` is checked BEFORE transcribing, so silence is never
   * paid for.
   */
  async function recordUntilComplete(opts) {
    const gate = opts.gate || {};
    const maxSegments = opts.maxSegments || 6;
    let text = '';

    for (let seg = 0; seg < maxSegments; seg++) {
      // Each later segment is primed with the question plus what has already been heard,
      // which measurably helps a transcriber with names and jargon it has just met.
      const { text: part, state } = await recordOnce(
        { ...opts, autoStop: true, prompt: `${opts.prompt || ''} ${text}`.trim().slice(-800) },
        gate,
      );
      if (aborted) break;
      if (!state || !state.heardSpeech) break;   // nothing said: do not pay to transcribe it
      if (!part || !part.trim()) break;          // real audio, no words: the mic is hearing noise

      text = text ? `${text} ${part.trim()}` : part.trim();
      if (opts.onInterim) opts.onInterim(text);
      if (opts.isComplete(text)) break;
    }
    return text;
  }

  /** Acquire the microphone once, on a user gesture, and keep it. */
  async function ensureMic() {
    if (mode !== 'recorder' || recorder) return;
    recorder = await createRecorder();
  }

  return {
    get mode() { return mode; },
    available: mode !== 'none',
    /** Why there is no mic button, in words the user can act on. */
    unavailableReason: reason,
    /** True when answers cost money to transcribe, so the UI can say so once. */
    metered: () => mode === 'recorder',
    transcriberLabel: transcriber ? transcriber.label : null,

    ensureMic,

    /**
     * Capture one answer.
     *
     * @param {{onInterim?: Function, onLevel?: Function, prompt?: string, autoStop?: boolean}} opts
     * @returns {Promise<string>} the transcript, '' if nothing was said
     */
    async listen(opts = {}) {
      if (mode === 'none') throw new Error('no voice input is available in this browser');
      aborted = false;

      if (mode === 'webspeech') {
        session = listenViaWebSpeech({
          lang,
          onInterim: opts.onInterim,
          autoStop: opts.autoStop !== false,
          isComplete: opts.isComplete || null,
          ...(opts.settleMs == null ? {} : { settleMs: opts.settleMs }),
          ...(opts.deafMs == null ? {} : { deafMs: opts.deafMs }),
        });
        try {
          return await session.promise;
        } finally {
          session = null;
        }
      }

      await ensureMic();
      // Without `isComplete` this is the press-to-talk contract: one recording, one
      // transcription, whatever the gate decided. Unchanged.
      if (!opts.isComplete) return (await recordOnce(opts)).text;
      return recordUntilComplete(opts);
    },

    /** End the current capture early and keep what was heard. */
    stop() { if (session) session.stop(); },
    /** Throw away the current capture, including any segments still to come. */
    abort() {
      aborted = true;
      if (session && session.abort) session.abort();
      session = null;
    },

    speak: (text, o) => speak(text, { lang, ...o }),
    cancelSpeech,
    primeSpeech,
    ttsSupported,

    /** Let the user retry a backend that was written off, e.g. after granting permission. */
    reset() { forgetVerdict(); },

    dispose() {
      cancelSpeech();
      if (session && session.abort) session.abort();
      if (recorder) recorder.dispose();
      recorder = null;
    },
  };
}

const shortLang = (l) => String(l || 'en').slice(0, 2).toLowerCase();
