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

      if (mode === 'webspeech') {
        session = listenViaWebSpeech({
          lang, onInterim: opts.onInterim, autoStop: opts.autoStop !== false,
        });
        try {
          return await session.promise;
        } finally {
          session = null;
        }
      }

      await ensureMic();
      session = { stop: () => recorder.stop(), abort: () => recorder.stop() };
      let blob;
      try {
        blob = await recorder.record({ onLevel: opts.onLevel, autoStop: opts.autoStop !== false });
      } finally {
        session = null;
      }
      // A quarter-second of nothing is a mis-tap, not an answer worth paying to transcribe.
      if (!blob || blob.size < 1600) return '';
      const t = createTranscriber({
        ...stt, language: shortLang(lang), prompt: opts.prompt || undefined,
      });
      return t.transcribe(blob);
    },

    /** End the current capture early and keep what was heard. */
    stop() { if (session) session.stop(); },
    /** Throw away the current capture. */
    abort() { if (session && session.abort) session.abort(); session = null; },

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
