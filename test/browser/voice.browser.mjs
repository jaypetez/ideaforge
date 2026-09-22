// Voice. Chrome runs these with a synthesised microphone (--use-fake-device-for-media-stream),
// so the recorder, the silence gate and the backend picker are all exercised for real.
//
// The most important check here is the dead-recogniser one. Inside an installed iOS
// home-screen app, webkitSpeechRecognition exists, constructs, and start() returns without
// throwing — and then no event ever fires. Feature detection reports "supported" on the one
// platform where it does not work, so the fallback has to be driven by a behavioural probe.
// That probe is the thing most likely to be "simplified" into an `in window` check by
// someone who has never seen the failure, which is exactly why it is pinned here.

import { probeWebSpeech, forgetVerdict, cachedVerdict, webSpeechPresent } from '../../src/voice/webspeech.js';
import { createRecorder, micSupported } from '../../src/voice/recorder.js';
import { createVoice } from '../../src/voice/index.js';
import { ttsSupported } from '../../src/voice/speak.js';

/** Stand in for a recogniser that reports itself present and then does nothing. */
class SilentRecognition {
  constructor() { this.lang = ''; this.continuous = false; this.interimResults = false; }
  start() { /* the iPhone lie: returns cleanly, fires nothing, ever */ }
  stop() {}
  abort() {}
}

/** Stand in for Edge, which fails immediately with a network error. */
class NetworkErrorRecognition extends SilentRecognition {
  start() { setTimeout(() => this.onerror && this.onerror({ error: 'network' }), 5); }
}

/** Stand in for a user tapping Block on the microphone prompt. */
class RefusedRecognition extends SilentRecognition {
  start() { setTimeout(() => this.onerror && this.onerror({ error: 'not-allowed' }), 5); }
}

/**
 * navigator.permissions is a readonly WebIDL attribute, so it needs defineProperty for the
 * same reason window.speechSynthesis does — assigning to it throws in module code.
 */
function withPermission(state, fn) {
  const real = Object.getOwnPropertyDescriptor(Navigator.prototype, 'permissions')
    || Object.getOwnPropertyDescriptor(navigator, 'permissions');
  Object.defineProperty(navigator, 'permissions', {
    value: { query: async () => ({ state }) }, configurable: true,
  });
  return Promise.resolve(fn()).finally(() => {
    delete navigator.permissions;
    if (real && !('permissions' in navigator)) Object.defineProperty(navigator, 'permissions', real);
  });
}

function withRecognition(Impl, fn) {
  const realSR = window.SpeechRecognition;
  const realWK = window.webkitSpeechRecognition;
  if (Impl) { window.SpeechRecognition = Impl; window.webkitSpeechRecognition = Impl; }
  else { delete window.SpeechRecognition; delete window.webkitSpeechRecognition; }
  return Promise.resolve(fn()).finally(() => {
    window.SpeechRecognition = realSR;
    window.webkitSpeechRecognition = realWK;
    forgetVerdict();
  });
}

const STT = { kind: 'groq', apiKey: 'not-a-real-key-never-called' };

export default async function run(check) {
  check('the browser can record audio', micSupported());
  check('speech synthesis is available', ttsSupported());

  // ── the probe, against every way it fails in the wild ─────────────────────
  await withRecognition(SilentRecognition, async () => {
    forgetVerdict();
    check('a present-but-silent recogniser feature-detects as supported', webSpeechPresent());
    const t0 = performance.now();
    const verdict = await probeWebSpeech({ force: true });
    const ms = Math.round(performance.now() - t0);
    check('...but the behavioural probe declares it dead', verdict === 'dead', ms + 'ms');
    check('the probe does not hang waiting for it', ms < 4000, ms + 'ms');
    // Only with the microphone already granted. A silent engine is then genuinely the
    // engine's fault, which is the installed-iPhone case this whole probe exists for.
    await withPermission('granted', async () => {
      forgetVerdict();
      check('a silent engine with the microphone already granted is remembered as dead',
        (await probeWebSpeech({ force: true })) === 'dead' && cachedVerdict() === 'dead',
        String(cachedVerdict()));
    });

    // ── the bug: a permission decision is not an engine verdict ──────────────
    //
    // rec.start() is what RAISES the prompt, so on a first run the probe was timing a
    // person hunting for Allow, not an engine. It timed out, wrote 'dead', and because a
    // cached verdict short-circuits the probe, dictation was off for that origin for ever
    // — including after the microphone was granted. Reported, never remembered.
    await withPermission('prompt', async () => {
      forgetVerdict();
      check('a timeout while the prompt is still up is NOT remembered',
        (await probeWebSpeech({ force: true, promptMs: 150 })) === 'dead'
          && cachedVerdict() === null,
        String(cachedVerdict()));
    });

    forgetVerdict();
    const v = await createVoice({ stt: STT });
    check('with a transcription key it falls back to the recorder', v.mode === 'recorder');
    check('and says dictation is metered', v.metered() === true);
    v.dispose();

    forgetVerdict();
    const bare = await createVoice({ stt: null });
    // A mic button that starts, fires nothing and hangs is worse than no mic button.
    check('with no transcription key it offers no voice at all', bare.mode === 'none');
    check('and explains what would fix it',
      /transcription key/i.test(bare.unavailableReason || ''), bare.unavailableReason);
    bare.dispose();
  });

  await withRecognition(NetworkErrorRecognition, async () => {
    forgetVerdict();
    const t0 = performance.now();
    check('an Edge-style network error is declared dead',
      (await probeWebSpeech({ force: true })) === 'dead',
      Math.round(performance.now() - t0) + 'ms');
  });

  // A refusal is revocable from site settings, so writing the engine off over one would
  // mean never finding out it had been granted.
  await withRecognition(RefusedRecognition, async () => {
    await withPermission('prompt', async () => {
      forgetVerdict();
      check('a refused microphone is not remembered as a broken engine',
        (await probeWebSpeech({ force: true })) === 'dead' && cachedVerdict() === null,
        String(cachedVerdict()));
    });
  });

  // The poisoned values v1 already wrote to real phones have to be discarded, not believed.
  await withRecognition(SilentRecognition, async () => {
    forgetVerdict();
    localStorage.setItem('ideaforge.webspeech', 'dead');
    check('a verdict written by the previous version is ignored', cachedVerdict() === null);
    forgetVerdict();
  });

  // Blaming the platform for a blocked microphone sends people hunting a browser bug that
  // is not there, past the one cause they can actually fix.
  await withRecognition(RefusedRecognition, async () => {
    await withPermission('denied', async () => {
      forgetVerdict();
      const v = await createVoice({ stt: null });
      check('a blocked microphone is reported as blocked, not as a broken browser',
        /blocked for this site/i.test(v.unavailableReason || ''), v.unavailableReason);
      v.dispose();
    });
  });

  await withRecognition(null, async () => {
    forgetVerdict();
    check('an absent recogniser is declared dead',
      (await probeWebSpeech({ force: true })) === 'dead');
    const v = await createVoice({ stt: STT });
    check('Firefox-shaped browsers still get voice via the recorder', v.mode === 'recorder');
    v.dispose();
  });

  // ── the recorder and the silence gate, against a real audio pipeline ──────
  const rec = await createRecorder();
  check('a recording MIME type was negotiated', typeof rec.mime === 'string', rec.mime || 'browser default');

  let peak = 0;
  let last = null;
  const blob = await rec.record({
    onLevel: (rms, state) => { peak = Math.max(peak, rms); last = state; },
    silenceMs: 600, minSpeechMs: 200, maxMs: 6000,
  });
  check('recording produces a non-empty blob', blob && blob.size > 1000, (blob && blob.size) + ' bytes');
  check('the analyser saw signal', peak > 0.01, 'peak rms ' + peak.toFixed(3));
  check('the silence gate detected speech', !!(last && last.heardSpeech));
  check('the gate closed on its own rather than hitting the ceiling', !!(last && last.done));
  rec.dispose();

  // ── the backend picker when the real recogniser works ─────────────────────
  forgetVerdict();
  const live = await createVoice({ stt: null });
  check('a working recogniser is preferred over paid transcription',
    live.mode === 'webspeech', live.mode);
  live.dispose();

  forgetVerdict();
  const forced = await createVoice({ stt: STT, preferRecorder: true });
  check('preferRecorder overrides that', forced.mode === 'recorder');
  check('the transcriber is named for the UI', forced.transcriberLabel === 'Groq Whisper');
  forced.dispose();
  forgetVerdict();
}
