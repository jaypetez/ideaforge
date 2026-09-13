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
    check('the verdict is remembered for this origin', cachedVerdict() === 'dead');

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
