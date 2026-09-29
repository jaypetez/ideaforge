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

import {
  probeWebSpeech, listenViaWebSpeech, webSpeechPresent, forgetVerdict, micPermissionState,
  throwIfVoiceSetupAborted,
} from './webspeech.js';
import { createRecorder, micSupported } from './recorder.js';
import { createMicMeter } from './mic-meter.js';
import { createSilenceGate, meterStarvesRecognition } from './vad.js';
import { createTranscriber, STT_PRESETS, MAX_AUDIO_BYTES } from './transcribe.js';
import { speak, cancelSpeech, primeSpeech, ttsSupported } from './speak.js';

export { STT_PRESETS } from './transcribe.js';
export { primeSpeech, ttsSupported, cancelSpeech } from './speak.js';
export { forgetVerdict } from './webspeech.js';

/**
 * Why the level meter starved recognition on this page, once it has. Page lifetime, not
 * controller lifetime: press-to-talk disposes its controller after every answer and Resume
 * builds a new one, and each would otherwise open the meter, starve the recogniser and lose
 * the start of an answer all over again. Deliberately not persisted — nothing has confirmed
 * the mechanism on a physical phone, so a wrong verdict should not outlive a reload.
 */
let starvedMeter = null;
/** For tests: forget the page's starvation verdict. */
export function forgetStarvedMeter() { starvedMeter = null; }

/**
 * @param {{stt?: object, lang?: string, preferRecorder?: boolean, signal?: AbortSignal}} opts
 *   stt — credentials for the transcription fallback; without them, voice is Web-Speech-only
 *   signal — construction only; rejects AbortError and releases the probe on cancellation.
 *   Once returned, the controller's abort/pause/dispose methods own active capture.
 * @returns {Promise<object>} the voice controller
 */
export async function createVoice({ stt = null, lang = 'en-US', preferRecorder = false, signal } = {}) {
  throwIfVoiceSetupAborted(signal);
  const transcriber = stt && stt.apiKey ? createTranscriber({ ...stt, language: shortLang(lang) }) : null;

  let mode = 'none';
  let reason = null;
  const browserOk = !preferRecorder
    && webSpeechPresent()
    && (await probeWebSpeech({ lang, signal })) === 'alive';
  throwIfVoiceSetupAborted(signal);

  if (browserOk) {
    mode = 'webspeech';
  } else if (transcriber && micSupported()) {
    mode = 'recorder';
  } else if (!micSupported()) {
    reason = 'This browser cannot record audio.';
  } else {
    // The engine did not answer the probe. WHY decides what to say, and getting that wrong
    // sends people hunting for a browser bug that is not there: a blocked microphone fails
    // exactly like a broken engine from in here, and it is the one cause the reader can
    // actually fix. Check it before blaming the platform.
    const permission = await micPermissionState({ signal });
    throwIfVoiceSetupAborted(signal);
    reason = permission === 'denied'
      ? 'The microphone is blocked for this site. Allow it in your browser’s site settings ' +
        'and start an interview again, or add a transcription key in Settings to dictate instead.'
      : webSpeechPresent()
        ? 'Your browser reports dictation support but it does not work here — a known bug in ' +
          'installed iPhone apps, Edge and Firefox. Add a transcription key in Settings to dictate.'
        : 'This browser has no built-in dictation. Add a transcription key in Settings to dictate.';
  }

  let input = null;
  let acquisition = null;
  let capture = null;
  let disposed = false;
  let paused = false;
  let pausePromise = null;
  let disposal = null;
  let meterUnavailable = starvedMeter;
  let interruptedDraft = '';

  function assertOpen() {
    if (disposed) throw new Error('The voice controller has been disposed.');
    if (paused) throw new Error('Voice input is paused. Create a new controller to resume.');
  }

  const listening = (op) => capture === op && !op.stopped && !op.aborted;

  function reportPhase(op, opts, phase) {
    if (!opts.onPhase || capture !== op || op.settled || op.aborted || paused || disposed
      || (phase === 'listening' && op.stopped) || op.phase === phase) return;
    op.phase = phase;
    try { opts.onPhase(phase); } catch (err) {
      op.aborted = op.stopped = true;
      op.transcription.abort();
      op.session?.abort();
      finishCapture(op, '', err);
    }
  }

  function cancelAcquisition() {
    const pending = acquisition;
    acquisition = null;
    pending?.controller.abort();
  }

  async function getInput() {
    assertOpen();
    if (input) return input;
    if (!acquisition) {
      const pending = { controller: new AbortController(), promise: null };
      acquisition = pending;
      pending.promise = (async () => {
        const owner = await (mode === 'recorder' ? createRecorder : createMicMeter)({
          signal: pending.controller.signal,
        });
        if (pending.controller.signal.aborted || disposed || paused || acquisition !== pending) {
          await owner.dispose();
          throw new DOMException('Microphone acquisition cancelled.', 'AbortError');
        }
        input = owner;
        return owner;
      })().finally(() => {
        if (acquisition === pending) acquisition = null;
      });
    }
    return acquisition.promise;
  }

  function releaseInput() {
    cancelAcquisition();
    const owner = input;
    input = null;
    return owner ? owner.dispose() : Promise.resolve();
  }

  function stopLevels(op) {
    clearTimeout(op.meterTimer);
    if (mode === 'webspeech') input?.stop();
  }

  function reportUnavailable(op, opts, reason) {
    if (!listening(op) || op.levelReported) return;
    op.levelReported = true;
    if (opts.onLevelUnavailable) opts.onLevelUnavailable(reason);
    else console.warn('Microphone levels unavailable:', reason);
  }

  function disableMeter(op, opts, reason) {
    meterUnavailable = reason;
    stopLevels(op);
    releaseInput();
    reportUnavailable(op, opts, reason);
  }

  function startLevels(op, opts) {
    if (!opts.onLevel || op.meterStarted || !listening(op)) return;
    op.meterStarted = true;
    if (meterUnavailable) {
      reportUnavailable(op, opts, meterUnavailable);
      return;
    }
    const gate = createSilenceGate(opts.gate || {});
    op.meterTimer = setTimeout(() => {
      if (listening(op)) disableMeter(op, opts,
        'Microphone level monitoring did not start. Dictation can continue without it.');
    }, 1500);
    getInput().then((meter) => {
      clearTimeout(op.meterTimer);
      if (!listening(op) || meterUnavailable) return;
      meter.start((rms) => {
        if (!listening(op)) return;
        gate.push(rms, performance.now());
        const state = gate.state();
        if (meterStarvesRecognition({ speechMs: state.speechMs, heardWords: op.heardWords })) {
          op.starved?.();
          return;
        }
        opts.onLevel(rms, state);
      }, (err) => disableMeter(op, opts, err.message));
    }).catch((err) => {
      clearTimeout(op.meterTimer);
      if (listening(op) && !meterUnavailable) disableMeter(op, opts, err.message);
    });
  }

  /** One recording, transcribed. Resolves '' for anything too short to be an answer. */
  async function recordOnce(op, recorder, opts, gate) {
    const session = { stop: () => recorder.stop(), abort: () => recorder.abort() };
    op.session = session;
    let state = null;
    let blob;
    let inputError = null;
    try {
      blob = await recorder.record({
        autoStop: opts.autoStop !== false,
        onLevel: (rms, s) => {
          state = s;
          if (listening(op) && opts.onLevel) opts.onLevel(rms, s);
        },
        ...(gate || {}),
        onStart: () => reportPhase(op, opts, 'listening'),
        onLevelUnavailable: (reason) => {
          // Earlier positive evidence is still valid; earlier quiet cannot judge unmetered audio.
          if (!state?.heardSpeech) state = null;
          reportUnavailable(op, opts, reason);
        },
      });
    } catch (err) {
      if (err.code !== 'audio-capture' || !(err.audio instanceof Blob)) throw err;
      inputError = err;
      blob = err.audio;
    } finally {
      if (op.session === session) op.session = null;
    }
    // A quarter-second of nothing is a mis-tap, not an answer worth paying to transcribe.
    if (op.aborted || !blob || blob.size < 1600 || state?.heardSpeech === false) {
      return { text: '', state, error: inputError };
    }
    const t = createTranscriber({
      ...stt, language: shortLang(lang), prompt: opts.prompt || undefined,
    });
    const request = t.transcribe(blob, { signal: op.transcription.signal });
    // The request starts before notifying: a cancelling observer must not announce work
    // that never began. Oversized audio is rejected by the adapter before any request.
    if (blob.size <= MAX_AUDIO_BYTES) reportPhase(op, opts, 'transcribing');
    let text;
    try { text = await request; } catch (err) {
      if (!inputError) throw err;
      inputError.cause = err;
      inputError.message += ` Transcription failed: ${err.message}`;
      return { text: '', state, error: inputError };
    }
    return { text: op.aborted ? '' : text, state, error: inputError };
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
  async function recordUntilComplete(op, recorder, opts) {
    const gate = opts.gate || {};
    const maxSegments = opts.maxSegments || 6;
    let text = '';

    for (let seg = 0; seg < maxSegments; seg++) {
      if (op.stopped || op.aborted) break;
      // Each later segment is primed with the question plus what has already been heard,
      // which measurably helps a transcriber with names and jargon it has just met.
      const { text: part, error } = await recordOnce(
        op, recorder,
        { ...opts, autoStop: true, prompt: `${opts.prompt || ''} ${text}`.trim().slice(-800) },
        gate,
      );
      if (op.aborted) return '';
      if (part?.trim()) text = text ? `${text} ${part.trim()}` : part.trim();
      if (error) throw Object.assign(error, { draft: text });
      if (!part || !part.trim()) break;          // real audio, no words: the mic is hearing noise

      if (op.stopped) break;                    // keep this segment, never begin the next
      if (opts.onInterim) opts.onInterim(text);
      if (op.aborted) return '';
      if (op.stopped) break;
      if (opts.isComplete(text)) break;
    }
    return text;
  }

  /** Acquire the microphone once, on a user gesture, and keep it. */
  async function ensureMic() {
    assertOpen();
    if (mode === 'recorder') await getInput();
  }

  async function runCapture(op, opts) {
    if (mode === 'webspeech') {
      let retried = false;
      for (;;) {
        let retryWithoutMeter = false;
        let starved = false;
        // A starved recogniser fails silently rather than with `audio-capture`: the meter
        // hears speech, the recogniser hears nothing and just restarts. What was said while
        // it was starved is gone — the recogniser never heard it — so the session is thrown
        // away. Hands-free resolves '' at once, which is a miss: the loop says so aloud and
        // the driver repeats the answer whole. Retrying silently there would hear only the
        // rest of the sentence and submit it without its beginning. Press-to-talk retries,
        // because that speaker is watching the answer box and the warning above it.
        op.starved = () => {
          if (retried || retryWithoutMeter || !listening(op)) return;
          retryWithoutMeter = starved = true;
          disableMeter(op, opts,
            'The microphone cannot feed level monitoring and dictation at once here. '
            + 'Dictation continues without the level bars.');
          starvedMeter = meterUnavailable;
          op.session?.abort();
        };
        op.session = listenViaWebSpeech({
          lang,
          onInterim: (text) => {
            if (text && text.trim()) op.heardWords = true;
            if (listening(op)) opts.onInterim?.(text);
          },
          onStart: () => {
            reportPhase(op, opts, 'listening');
            startLevels(op, opts);
          },
          onAudioError: () => {
            if (!listening(op) || !op.meterStarted || meterUnavailable) return;
            retryWithoutMeter = true;
            disableMeter(op, opts,
              'This browser cannot share the microphone for level monitoring. Dictation continues without it.');
          },
          autoStop: opts.autoStop !== false,
          isComplete: opts.isComplete || null,
          ...(opts.settleMs == null ? {} : { settleMs: opts.settleMs }),
          ...(opts.deafMs == null ? {} : { deafMs: opts.deafMs }),
        });
        if (op.aborted) op.session.abort();
        else if (op.stopped) op.session.stop({ preserveDraft: op.preserveDraft });
        let text;
        try {
          text = await op.session.promise;
        } catch (err) {
          if (err.code !== 'audio-capture' || !retryWithoutMeter || retried || !listening(op)) throw err;
          retried = true;
          continue;
        } finally {
          op.session = null;
        }
        if (starved && !retried && !opts.isComplete && listening(op)) { retried = true; continue; }
        return text;
      }
    }

    let recorder;
    try { recorder = await getInput(); } catch (err) {
      if (op.stopped && err.name === 'AbortError') return '';
      throw err;
    }
    if (op.stopped || op.aborted) return '';
    if (!opts.isComplete) {
      const { text, error } = await recordOnce(op, recorder, opts, opts.gate);
      if (error) throw Object.assign(error, { draft: text });
      return text;
    }
    return recordUntilComplete(op, recorder, opts);
  }

  function finishCapture(op, text, err = null) {
    if (op.settled) return;
    op.settled = true;
    if (capture === op) {
      stopLevels(op);
      cancelAcquisition();
      if (err?.code === 'audio-capture' && err.fatal) {
        interruptedDraft = err.draft || '';
        releaseInput();
      }
      capture = null;
    }
    if (err) op.reject(err);
    else op.resolve(text);
  }

  function stop({ preserveDraft = false } = {}) {
    const op = capture;
    if (op) {
      op.stopped = true;
      op.preserveDraft ||= preserveDraft;
      stopLevels(op);
      op.session?.stop({ preserveDraft: op.preserveDraft });
    }
    cancelAcquisition();
  }

  function abort() {
    interruptedDraft = '';
    const op = capture;
    if (op) {
      op.aborted = op.stopped = true;
      op.transcription.abort();
      stopLevels(op);
      op.session?.abort();
      finishCapture(op, '');
    }
    cancelAcquisition();
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
     * Levels are measured only while listening. If native recognition cannot share the mic,
     * onLevelUnavailable explains why; recognition remains usable without a level display.
     * onPhase reports actual capture/request starts, never pending permission or paused work.
     * Recorder input loss rejects a fatal audio-capture error with its draft; pause() can
     * retrieve that draft after the failure, without submitting or reopening the microphone.
     * @param {{onInterim?: Function, onLevel?: Function, onLevelUnavailable?: Function,
     *          onPhase?: (phase: 'listening'|'transcribing') => void,
     *          prompt?: string, autoStop?: boolean}} opts
     * @returns {Promise<string>} the transcript, '' if nothing was said
     */
    async listen(opts = {}) {
      assertOpen();
      if (mode === 'none') throw new Error('no voice input is available in this browser');
      if (capture) throw new Error('already listening');
      interruptedDraft = '';
      const op = {
        stopped: false, aborted: false, settled: false, session: null, preserveDraft: false,
        transcription: new AbortController(), meterTimer: null, meterStarted: false,
        levelReported: false, phase: null, resolve: null, reject: null, promise: null,
      };
      op.promise = new Promise((resolve, reject) => { op.resolve = resolve; op.reject = reject; });
      capture = op;
      runCapture(op, opts).then(
        (text) => finishCapture(op, op.aborted ? '' : text),
        (err) => finishCapture(op, '', err),
      );
      return op.promise;
    },

    /** Keep this capture, including pending transcription, but never record another segment. */
    stop,
    /** Discard this capture, cancel transcription, and suppress all late output. */
    abort,

    /** Release input immediately; finish only this draft. Resume uses a fresh controller. */
    async pause() {
      if (!pausePromise) {
        paused = true;
        const draft = (capture?.promise || Promise.resolve(interruptedDraft)).catch((err) => {
          if (err.code === 'audio-capture' && err.fatal && !err.cause
            && typeof err.draft === 'string') return err.draft;
          throw err;
        });
        stop({ preserveDraft: true });
        cancelSpeech();
        pausePromise = Promise.all([draft, releaseInput()]).then(([text]) => text);
      }
      return pausePromise;
    },

    speak: (text, o) => speak(text, { lang, ...o }),
    cancelSpeech,
    primeSpeech,
    ttsSupported,

    /** Let the user retry a backend that was written off, e.g. after granting permission. */
    reset() { forgetVerdict(); },

    dispose() {
      if (disposed) return disposal;
      disposed = true;
      cancelSpeech();
      abort();
      disposal = releaseInput();
      return disposal;
    },
  };
}

const shortLang = (l) => String(l || 'en').slice(0, 2).toLowerCase();
