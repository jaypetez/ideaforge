// Node-side CDP driver for the real index.html, not an autoplay-bypassed browser probe.
export async function runAppAudioPolicy(cdp, { origin, bootstrap, check, untrustedPreview = false }) {
  const deadline = Date.now() + 60000;
  const network = [];
  cdp.on('Network.requestWillBeSent', ({ request }) => {
    if (request.method === 'POST' && new URL(request.url).origin !== origin) network.push(request.url);
  });
  await cdp.send('Network.enable');
  await cdp.send('Network.setBlockedURLs', { urls: ['https://api.openai.com/*'] });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: bootstrap });
  await cdp.send('Page.bringToFront');
  await cdp.send('Page.navigate', { url: `${origin}/index.html` });
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const snapshot = () => cdp.evaluate('window.__appAudioPolicy.snapshot()');
  async function until(predicate, label, ms = 8000) {
    const end = Math.min(deadline, Date.now() + ms);
    let current;
    do {
      current = await snapshot();
      if (predicate(current)) return current;
      await sleep(40);
    } while (Date.now() < end);
    throw new Error(`${label}: ${JSON.stringify(current)}`);
  }
  async function click(selector) {
    await cdp.evaluate(`document.querySelector(${JSON.stringify(selector)})
      .scrollIntoView({ block: 'center', behavior: 'instant' })`);
    await cdp.click(selector);
  }
  function primedInHandler(state, control, label) {
    const handlers = state.handlers.filter((handler) => handler.control === control
      && (!label || handler.label === label));
    return handlers.length > 0 && handlers.every((handler) => state.resumes.some((resume) =>
      resume.handler === handler.id && resume.trusted && resume.active
        && resume.order > handler.order && resume.order < handler.returned
        && resume.stack.includes('/src/voice/output.js')
        && resume.stack.includes('/src/voice/playback.js')));
  }
  const completed = (source) => source?.started && source.ended && source.trustedEnd
    && source.stops === 0 && source.destination && source.disconnected
    && source.endClock >= source.startClock + source.duration
    && source.stack.includes('/src/voice/playback.js');
  const live = (state) => state.captures.filter((capture) => capture.endedAt === null);
  const quiet = (state) => live(state).length === 0
    && state.tracks.every((track) => track === 'ended')
    && state.sources.every((source) => !source.started || source.ended || source.disconnected);

  let ready = false;
  for (const end = Date.now() + 10000; Date.now() < end;) {
    ready = await cdp.evaluate(`!!document.getElementById('version')?.textContent
      && document.getElementById('provider').options.length > 0
      && typeof document.getElementById('b-start-voice').onclick === 'function'`);
    if (ready) break;
    await sleep(40);
  }
  if (!ready) throw new Error('the real app did not finish setup');
  await cdp.evaluate(`(() => {
    for (const [id, value] of [['provider', 'artifact'], ['stt', 'browser'], ['speech', 'openai']]) {
      const field = document.getElementById(id);
      field.value = value;
      field.dispatchEvent(new Event('change'));
    }
    const key = document.getElementById('ttskey');
    key.value = 'policy-fixture-not-a-real-key';
    key.dispatchEvent(new Event('input'));
    window.__appAudioPolicy.arm();
  })()`);
  let state = await snapshot();
  check('real app setup opens no microphone, speech request, or audio context',
    state.tracks.length === 0 && state.requests.length === 0 && state.contexts.length === 0
      && !state.activation);
  check('policy integration keeps native synthesis and AudioContext constructors',
    state.nativeSynthesis && state.nativeContext);
  check('the actual preview control is disabled without consent',
    await cdp.evaluate("document.getElementById('b-speech-preview').disabled"));
  await sleep(100);
  check('configuring hosted speech without consent cannot request speech', (await snapshot()).requests.length === 0);
  if (untrustedPreview) {
    await cdp.evaluate(`(() => {
      const consent = document.getElementById('tts-consent');
      consent.checked = true;
      consent.dispatchEvent(new Event('change'));
    })()`);
  } else {
    await click('#tts-consent');
  }
  await sleep(100);
  check('consent alone cannot request speech before Check and preview', (await snapshot()).requests.length === 0);
  if (untrustedPreview) await cdp.evaluate("document.getElementById('b-speech-preview').click()");
  else await click('#b-speech-preview');
  state = await until((s) => /played successfully|check failed|did not finish/i.test(s.preview), 'preview did not settle');
  check('Check and preview primes native output synchronously in its trusted handler',
    primedInHandler(state, 'b-speech-preview'), JSON.stringify(state.resumes));
  check('actual Check and preview completes native decoded audio through output/playback',
    completed(state.sources[0]) && /played successfully/i.test(state.preview),
    JSON.stringify({ preview: state.preview, sources: state.sources }));
  if (untrustedPreview) {
    check('negative control: an untrusted preview is blocked before any speech request or capture',
      !state.activation && state.requests.length === 0 && state.tracks.length === 0
        && state.sources.length === 0 && state.resumes.some((resume) => resume.state === 'suspended')
        && /check failed/i.test(state.preview), state.preview);
    return;
  }
  if (!completed(state.sources[0])) throw new Error('the real preview never completed; interview was not started');
  state = await until((s) => s.contexts.every((context) => context.state === 'closed'), 'preview context leaked');
  check('the completed preview closes its native context', true);

  await click('#b-start-voice');
  state = await until((s) => s.phase === 'listening' && live(s).length === 1 && s.tracks.includes('live'),
    'Start with voice did not reach real microphone capture');
  check('Start with voice primes in its trusted handler before awaited setup',
    primedInHandler(state, 'b-start-voice'));
  check('Start waits for actual hosted-buffer completion before recognition',
    completed(state.sources[1]) && state.captures[0].startedAt >= state.sources[1].endedAt
      && /^OpenAI/.test(state.backend), JSON.stringify(state.sources[1]));
  check('the actual capture uses synthetic-device tracks, not a permission-UI claim',
    state.tracks.includes('live') && live(state).length === 1);
  await click('#b-voice-pause');
  state = await until((s) => s.phase === 'paused' && !s.pauseDisabled && quiet(s), 'Pause did not release capture');
  check('the actual Pause handler releases microphone tracks and clears the meter',
    state.tracks.length > 0 && state.level === 0 && quiet(state));

  await click('#b-voice-pause');
  state = await until((s) => s.phase === 'listening' && live(s).length === 1 && s.tracks.includes('live'),
    'Resume did not finish speech before capture');
  check('Resume primes synchronously in the trusted Resume handler',
    primedInHandler(state, 'b-voice-pause', 'Resume'));
  check('Resume finishes a new native buffer rather than treating priming as completion',
    state.sources.length === 3 && completed(state.sources[2])
      && state.captures.at(-1).startedAt >= state.sources[2].endedAt);

  await cdp.evaluate('window.__appAudioPolicy.answer()');
  state = await until((s) => s.inferenceCalls === 1 && s.sources.length === 4
    && s.sources[3].started && !s.sources[3].ended, 'scripted inference did not start the next real buffer');
  check('scripted inference feeds the actual output pipeline without provider network',
    state.requests.at(-1).input === 'Which workshop attendee would try the notebook first?'
      && state.phase === 'speaking');
  await click('#b-voice-pause');
  state = await until((s) => s.phase === 'paused' && !s.pauseDisabled && quiet(s)
    && s.sources[3].ended, 'Pause did not stop active playback');
  check('Pause during real playback stops and disconnects its native source',
    state.sources[3].stops === 1 && state.sources[3].disconnected && state.level === 0);
  const pausedRequests = state.requests.length;
  const pausedCaptures = state.captures.length;
  await sleep(200);
  state = await snapshot();
  check('Pause cannot silently fall back, submit again, or start another capture',
    state.requests.length === pausedRequests && state.captures.length === pausedCaptures
      && state.inferenceCalls === 1 && state.phase === 'paused' && quiet(state));

  await click('#b-voice-pause');
  state = await until((s) => s.sources.length === 5 && s.sources[4].started && !s.sources[4].ended,
    'the final Resume did not start native playback');
  check('every Resume retains the trusted synchronous priming contract',
    primedInHandler(state, 'b-voice-pause', 'Resume'));
  await click('#b-voice-exit');
  state = await until((s) => s.stageHidden && s.manualVisible && quiet(s)
    && s.contexts.every((context) => context.state === 'closed'), 'Exit leaked voice resources');
  check('Exit during native playback stops its source and closes every owned context',
    state.sources[4].stops === 1 && state.sources[4].disconnected
      && state.contexts.length > 0 && state.contexts.every((context) => context.native && context.state === 'closed'));
  const exitRequests = state.requests.length;
  await sleep(200);
  state = await snapshot();
  check('Exit restores manual mode without orphaned tracks, playback, or late speech requests',
    state.stageHidden && state.manualVisible && state.level === 0 && quiet(state)
      && state.requests.length === exitRequests);
  check('every hosted response is a real non-silent WAV decoded by playback.js',
    state.decodes.length === 5 && state.decodes.every((decode) => decode.nonSilent
      && decode.duration > 0 && decode.stack.includes('/src/voice/playback.js')));
  check('the speech stub accepts only the actual fixed WAV endpoint contract',
    state.requests.length === 5 && state.requests.every((request) => request.consent
      && request.model === 'gpt-4o-mini-tts' && request.format === 'wav'));
  check('no provider POST left the browser', network.length === 0, network.join(', '));
  check('no uncaught error or CSP violation in the real-app policy phase',
    state.errors.length === 0, state.errors.join('; '));
}
