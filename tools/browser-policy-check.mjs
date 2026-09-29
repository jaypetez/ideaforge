// Real audio policy checks, deliberately separate from the autoplay-bypassed browser suite.
// Run directly with `node tools\browser-policy-check.mjs`; missing Chrome is always a failure.

import { readFile, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { ROOT, findChrome, serveRepo, launchChrome, connectChrome } from './lib/harness.mjs';
import { runAppAudioPolicy } from '../test/browser/app-audio-policy.probe.mjs';

const NORMAL_POLICY = [
  '--autoplay-policy=document-user-activation-required',
  '--disable-features=PreloadMediaEngagementData,MediaEngagementBypassAutoplayPolicies',
];
const CASES = [
  { name: 'audio-policy-default', mode: 'observe', flags: [] },
  { name: 'audio-policy-normal', mode: 'required', flags: NORMAL_POLICY },
  {
    name: 'audio-policy-synthetic-microphone', mode: 'microphone',
    flags: [
      ...NORMAL_POLICY,
      '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
    ],
  },
];
const APP_CASE = {
  name: 'audio-policy-app', mode: 'app',
  flags: [...NORMAL_POLICY, '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
};

/** A short PCM WAV: real decode/playback without a provider, stored recording, or dependency. */
function toneWav(seconds = 0.5) {
  const sampleRate = 16000;
  const frames = Math.round(sampleRate * seconds);
  const bytes = Buffer.alloc(44 + frames * 2);
  bytes.write('RIFF', 0);
  bytes.writeUInt32LE(bytes.length - 8, 4);
  bytes.write('WAVEfmt ', 8);
  bytes.writeUInt32LE(16, 16);
  bytes.writeUInt16LE(1, 20);
  bytes.writeUInt16LE(1, 22);
  bytes.writeUInt32LE(sampleRate, 24);
  bytes.writeUInt32LE(sampleRate * 2, 28);
  bytes.writeUInt16LE(2, 32);
  bytes.writeUInt16LE(16, 34);
  bytes.write('data', 36);
  bytes.writeUInt32LE(frames * 2, 40);
  for (let i = 0; i < frames; i++) {
    bytes.writeInt16LE(Math.round(2048 * Math.sin(2 * Math.PI * 440 * i / sampleRate)), 44 + i * 2);
  }
  return bytes;
}

async function waitFor(cdp, field, timeout) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await cdp.evaluate('window.__audioPolicy || null');
    if (last?.error) throw new Error(last.error);
    if (last?.[field]) return last;
    await sleep(50);
  }
  throw new Error(`audio policy timed out waiting for ${field}: ${JSON.stringify(last)}`);
}

async function closeBrowser(browser, cdp) {
  const exited = () => !browser.child.pid
    || browser.child.exitCode !== null || browser.child.signalCode !== null;
  let closeError;
  try {
    if (cdp) await cdp.send('Browser.close', {}, 3000);
  } catch (error) {
    // Some builds close the socket before acknowledging Browser.close; process exit decides.
    closeError = error;
  } finally {
    cdp?.close();
  }
  if (!exited() && !cdp) browser.child.kill();
  let deadline = Date.now() + 5000;
  while (!exited() && Date.now() < deadline) {
    await sleep(50);
  }
  if (!exited()) {
    browser.child.kill();
    deadline = Date.now() + 5000;
    while (!exited() && Date.now() < deadline) await sleep(50);
  }
  if (!exited()) {
    throw new Error('policy Chrome did not exit', { cause: closeError });
  }
  await rm(browser.profile, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
  if (existsSync(browser.profile)) throw new Error('policy Chrome profile was not removed');
}

/** Fail-closed both as a standalone command and as a phase of browser-check.mjs. */
export async function runBrowserPolicyChecks({
  chrome = findChrome(), siteRoot = ROOT, appOnly = false, untrustedPreview = false,
} = {}) {
  if (!chrome) throw new Error('no Chrome found: audio policy checks are required');
  if (untrustedPreview && !appOnly) throw new Error('the negative control requires --app-only');
  const results = [];
  const wav = toneWav();
  const server = await serveRepo({
    root: siteRoot,
    async before(req, res, url) {
      if (url.pathname === '/__audio-policy.html') {
        res.writeHead(200, {
          'content-type': 'text/html; charset=utf-8',
          'content-security-policy': "default-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'",
        }).end('<!doctype html><meta charset="utf-8"><title>Real audio policy</title>'
          + '<button id="enable-audio" type="button">Enable audio</button>'
          + '<script type="module" src="/test/browser/audio-policy.probe.mjs"></script>');
        return { handled: true };
      }
      if (url.pathname === '/__audio-policy.wav') {
        res.writeHead(200, { 'content-type': 'audio/wav', 'cache-control': 'no-store' }).end(wav);
        return { handled: true };
      }
      if (url.pathname.startsWith('/test/browser/')) return { path: url.pathname, root: ROOT };
    },
  });
  try {
    const origin = `http://127.0.0.1:${server.address().port}`;
    for (const testCase of appOnly ? [APP_CASE] : [...CASES, APP_CASE]) {
      let browser;
      let cdp;
      const check = (name, ok, detail = '') =>
        results.push({ probe: testCase.name, name, ok: !!ok, detail: String(detail) });
      try {
        browser = launchChrome(chrome, {
          url: 'about:blank', extraArgs: ['--remote-debugging-port=0', ...testCase.flags],
        });
        cdp = await connectChrome(browser);
        const version = await cdp.send('Browser.getVersion');
        const args = browser.child.spawnargs.slice(1);
        const autoplayFlags = args.filter((arg) => arg.startsWith('--autoplay-policy='));
        const fakeMedia = args.some((arg) => arg.startsWith('--use-fake-'));
        check('fresh-profile launch uses the declared policy',
          (testCase.mode === 'observe' ? autoplayFlags.length === 0
            : autoplayFlags.length === 1 && autoplayFlags[0] === NORMAL_POLICY[0])
            && (['microphone', 'app'].includes(testCase.mode)
              ? testCase.flags.every((flag) => args.includes(flag)) : !fakeMedia),
          `${version.product}; ${args.join(' ')}`);
        await cdp.send('Page.enable');
        if (testCase.mode === 'app') {
          const [recognition, fixture] = await Promise.all([
            readFile(join(ROOT, 'test', 'browser', 'fixtures', 'fake-voice.js'), 'utf8'),
            readFile(join(ROOT, 'test', 'browser', 'fixtures', 'app-audio-policy.js'), 'utf8'),
          ]);
          const bootstrap = `window.__appAudioPolicyConfig = ${JSON.stringify({
            wav: toneWav(3).toString('base64'),
          })};\n${recognition}\n${fixture}`;
          await runAppAudioPolicy(cdp, { origin, bootstrap, check, untrustedPreview });
          continue;
        }
        await cdp.send('Page.navigate', { url: `${origin}/__audio-policy.html?mode=${testCase.mode}` });
        await waitFor(cdp, 'ready', 15000);
        await cdp.click('#enable-audio');
        const payload = await waitFor(cdp, 'done', 20000);
        if (!Array.isArray(payload.results) || !payload.results.length
            || payload.results.some((r) => typeof r.ok !== 'boolean' || typeof r.name !== 'string')) {
          throw new Error('audio policy probe returned no valid assertions');
        }
        for (const result of payload.results) check(result.name, result.ok, result.detail);
      } catch (error) {
        check('policy probe completes', false, error.stack || error);
      } finally {
        if (browser) {
          try {
            await closeBrowser(browser, cdp);
            check('owned browser exits and its exact scratch profile is removed', true);
          } catch (error) {
            check('owned browser cleanup', false, error.stack || error);
          }
        }
      }
    }
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
  return results;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) {
    console.log('Usage: node tools\\browser-policy-check.mjs [--app-only [--negative-control]]\n'
      + 'Default: the original 40 checks plus real-app normal-policy integration.\n'
      + '--negative-control deliberately uses an untrusted preview and must exit nonzero.');
    return 0;
  }
  for (const arg of args) {
    if (!['--app-only', '--negative-control'].includes(arg)) throw new Error(`unknown policy option: ${arg}`);
  }
  const results = await runBrowserPolicyChecks({
    appOnly: args.includes('--app-only'), untrustedPreview: args.includes('--negative-control'),
  });
  for (const result of results) {
    console.log(`${result.ok ? 'ok  ' : 'FAIL'} ${result.probe}: ${result.name}`
      + (result.detail ? ` (${result.detail})` : ''));
  }
  const failed = results.filter((result) => !result.ok).length;
  console.log(`${results.length} audio policy checks, ${failed} failed`);
  return failed ? 1 : 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main();
  } catch (error) {
    console.error(error.stack || error);
    process.exitCode = 1;
  }
}
