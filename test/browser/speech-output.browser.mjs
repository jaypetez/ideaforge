// Real Web Audio and the assembled app's CSP; HTTP and native fallback are explicit
// fakes. This harness bypasses autoplay. The separate normal-policy probe, not this
// file, must prove a trusted user gesture. No external API or real credential is used.

if (window.__loadSpeechOutputProbe) {
  try {
    const module = await import(new URL('src/voice/output.js', document.baseURI));
    window.dispatchEvent(new CustomEvent('speech-output-probe-ready', { detail: { module } }));
  } catch (error) {
    window.dispatchEvent(new CustomEvent('speech-output-probe-ready', { detail: { error } }));
  }
}

function wav(win, seconds = 0.15) {
  const samples = Math.round(24000 * seconds);
  const audio = new win.ArrayBuffer(44 + samples * 2);
  const view = new win.DataView(audio);
  const tag = (at, text) => [...text].forEach((c, n) => view.setUint8(at + n, c.charCodeAt(0)));
  tag(0, 'RIFF'); view.setUint32(4, audio.byteLength - 8, true); tag(8, 'WAVE');
  tag(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, 24000, true);
  view.setUint32(28, 48000, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  tag(36, 'data'); view.setUint32(40, samples * 2, true);
  for (let n = 0; n < samples; n++) {
    const envelope = Math.min(1, n / 100, (samples - n) / 100);
    view.setInt16(44 + n * 2, Math.round(Math.sin(n * 2 * Math.PI * 440 / 24000) * 300 * envelope), true);
  }
  return audio;
}

function loadFrame(path) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 800;
    frame.height = 650;
    const timer = setTimeout(() => { frame.remove(); reject(new Error('Speech probe frame did not load.')); }, 5000);
    frame.onload = () => { clearTimeout(timer); resolve(frame); };
    frame.onerror = () => { clearTimeout(timer); frame.remove(); reject(new Error('Speech probe frame failed.')); };
    frame.src = `${path}index.html`;
    document.body.append(frame);
  });
}

function loadModules(frame) {
  const win = frame.contentWindow;
  win.__loadSpeechOutputProbe = true;
  return new Promise((resolve, reject) => {
    const script = frame.contentDocument.createElement('script');
    let timer;
    const cleanup = () => {
      clearTimeout(timer);
      win.removeEventListener('speech-output-probe-ready', ready);
    };
    const ready = (event) => {
      cleanup();
      if (event.detail.error) reject(event.detail.error);
      else resolve(event.detail.module);
    };
    win.addEventListener('speech-output-probe-ready', ready);
    script.type = 'module';
    script.src = '/test/browser/speech-output.browser.mjs';
    script.onerror = () => {
      cleanup();
      reject(new Error('Speech probe module failed to load under the app CSP.'));
    };
    timer = setTimeout(() => {
      cleanup();
      reject(new Error('Speech probe module did not finish loading under the app CSP.'));
    }, 5000);
    frame.contentDocument.head.append(script);
  });
}

async function clearWorkers() {
  for (const registration of await navigator.serviceWorker.getRegistrations()) await registration.unregister();
  for (const key of await caches.keys()) await caches.delete(key);
}

export default async function run(check, { subpath }) {
  await import('./fixtures/fake-voice.js');
  const fake = window.__FakeVoice;
  for (const path of ['/', subpath]) {
    await clearWorkers();
    const frame = await loadFrame(path);
    const win = frame.contentWindow;
    const violations = [];
    const errors = [];
    win.addEventListener('securitypolicyviolation', (event) => violations.push(event.violatedDirective));
    win.addEventListener('error', (event) => errors.push(event.message));
    win.addEventListener('unhandledrejection', (event) => errors.push(String(event.reason)));
    const controllers = [];
    const contexts = [];
    const sources = [];
    let requests = 0;
    let nativeSpeaks = 0;
    let stops = 0;
    let disconnects = 0;
    try {
      const { createSpeechOutput } = await loadModules(frame);
      const native = {
        prime: () => ({ status: 'primed', backend: 'browser' }),
        speak: async () => { nativeSpeaks++; return { status: 'spoken', backend: 'browser' }; },
        cancel() {},
        supported: () => true,
      };
      const make = (overrides = {}) => {
        const controller = createSpeechOutput({
          kind: 'openai', apiKey: 'mock-only-never-sent-to-a-server', native,
          createAudioContext() {
            const context = new win.AudioContext();
            const makeSource = context.createBufferSource.bind(context);
            context.createBufferSource = () => {
              const source = makeSource();
              const stop = source.stop.bind(source);
              const disconnect = source.disconnect.bind(source);
              source.stop = (...args) => { stops++; return stop(...args); };
              source.disconnect = (...args) => { disconnects++; return disconnect(...args); };
              sources.push(source);
              return source;
            };
            contexts.push(context);
            return context;
          },
          fetch: async (url, init) => {
            requests++;
            if (url !== 'https://api.openai.com/v1/audio/speech' || init.credentials !== 'omit') {
              throw new Error('Unexpected mocked speech request.');
            }
            return new win.Response(wav(win), { headers: { 'content-type': 'audio/wav' } });
          },
          ...overrides,
        });
        controllers.push(controller);
        return controller;
      };

      const phases = [];
      const output = make({ onStatus: (status) => phases.push(status.phase) });
      check(`${path}: constructing hosted output does not request text`, requests === 0);
      const priming = output.prime();
      check(`${path}: Web Audio is constructed synchronously by prime`, contexts.length === 1);
      check(`${path}: priming sends no paid request`, requests === 0);
      const primed = await priming;
      check(`${path}: real Web Audio runs in the autoplay-bypass harness`,
        primed.status === 'primed' && contexts[0].state === 'running', primed.error?.message);
      const result = await output.check('Mocked speech preview, not a voice-quality test.');
      check(`${path}: generated PCM WAV decodes and plays through real Web Audio`,
        result.status === 'spoken' && result.backend === 'openai' && sources.length === 1);
      check(`${path}: the check observes preparing, speaking, then actual completion`,
        phases.join(',') === 'preparing,speaking,idle', phases.join(','));
      check(`${path}: completed audio is disconnected`, disconnects === 1);
      check(`${path}: a passing hosted check did not use the native fake`, nativeSpeaks === 0);

      let cancelOutput;
      cancelOutput = make({
        onStatus(status) {
          if (status.phase === 'speaking') cancelOutput.cancel();
        },
      });
      await cancelOutput.prime();
      const cancelled = await cancelOutput.speak('Cancel scheduled audio immediately.');
      check(`${path}: cancellation stops and disconnects real scheduled audio`,
        cancelled.status === 'cancelled' && stops === 1 && disconnects === 2);
      check(`${path}: cancellation never invokes native fallback`, nativeSpeaks === 0);

      const rejected = make({
        fetch: async () => new win.Response('mock auth failure', { status: 401 }),
      });
      await rejected.prime();
      let failure = null;
      try { await rejected.check('This check must fail.'); }
      catch (error) { failure = error; }
      check(`${path}: a mocked auth failure cannot pass the preview gate via fallback`,
        failure?.code === 'auth' && nativeSpeaks === 0, failure?.message);

      for (const variant of ['sentinel', 'partial-frame', 'truncated-sized']) {
        let audio = wav(win);
        if (variant !== 'truncated-sized') {
          const header = new win.DataView(audio);
          header.setUint32(4, 0xffffffff, true);
          header.setUint32(40, 0xffffffff, true);
        }
        if (variant === 'partial-frame') audio = audio.slice(0, -1);
        if (variant === 'truncated-sized') audio = audio.slice(0, -2);
        const streamed = make({
          fetch: async () => {
            requests++;
            const bytes = new win.Uint8Array(audio);
            return new win.Response(new win.ReadableStream({
              start(controller) {
                controller.enqueue(bytes.subarray(0, 17));
                controller.enqueue(bytes.subarray(17, 43));
                controller.enqueue(bytes.subarray(43));
                controller.close();
              },
            }), { headers: { 'content-type': 'audio/wav' } });
          },
        });
        await streamed.prime();
        const sourceCount = sources.length;
        let result = null;
        let error = null;
        try { result = await streamed.check('A local streaming WAV fixture.'); }
        catch (cause) { error = cause; }
        if (variant === 'sentinel') {
          check(`${path}: completed sentinel WAV normalizes and plays through real Web Audio`,
            result?.status === 'spoken' && result.backend === 'openai'
              && sources.length === sourceCount + 1 && nativeSpeaks === 0, error?.message);
        } else {
          check(`${path}: ${variant} WAV fails before playback and cannot pass via fallback`,
            error?.code === 'bad_response' && sources.length === sourceCount
              && nativeSpeaks === 0, error?.message);
        }
      }

      fake.install(win, { speakMs: 220 });
      let speakingWhilePending = false;
      let nativeCompleted = false;
      const nativePhases = [];
      const browserOutput = createSpeechOutput({
        onStatus(status) {
          nativePhases.push(status.phase);
          if (status.phase === 'speaking') {
            speakingWhilePending = !nativeCompleted && win.speechSynthesis.speaking
              && fake.synthesis.spoken.at(-1)?.endedAt === null;
          }
        },
      });
      controllers.push(browserOutput);
      const nativePending = browserOutput.speak('What is the idea you want to explore?');
      for (let n = 0; n < 12; n++) await Promise.resolve();
      check(`${path}: queued native output stays preparing until the asynchronous start event`,
        fake.synthesis.spoken.length === 1 && nativePhases.at(-1) === 'preparing',
        nativePhases.join(','));
      const nativeResult = await nativePending;
      nativeCompleted = true;
      check(`${path}: the native start event reports speaking during its pending 220ms utterance`,
        speakingWhilePending, nativePhases.join(','));
      check(`${path}: native output reports speaking once and waits for the end before idle`,
        nativeResult.status === 'spoken' && fake.synthesis.spoken.at(-1)?.endedAt !== null
          && nativePhases.filter((phase) => phase === 'speaking').length === 1
          && nativePhases.at(-1) === 'idle', nativePhases.join(','));

      for (const controller of controllers) await controller.dispose();
      fake.restore();
      check(`${path}: disposal closes every real audio context`, contexts.every((ctx) => ctx.state === 'closed'));
      check(`${path}: buffered playback needs no media-src/blob CSP expansion`,
        violations.length === 0, violations.join(', '));
      check(`${path}: speech output leaves no uncaught browser errors`,
        errors.length === 0, errors.join(' | '));
    } finally {
      for (const controller of controllers) await controller.dispose();
      fake.restore();
      frame.remove();
      await clearWorkers();
    }
  }
}
