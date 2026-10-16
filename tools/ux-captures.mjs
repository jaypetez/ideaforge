import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, findChrome, serveRepo, launchChrome, connectChrome } from './lib/harness.mjs';
import { assembleSite } from './assemble-site.mjs';
import { buildUxFixtures, UX_STATES } from './fixtures/ux.mjs';
import { uxBootstrap } from '../test/browser/fixtures/ux.js';

const MODULE = '/test/browser/fixtures/ux.js';
const VIEWPORTS = [{ width: 390, height: 844 }, { width: 1280, height: 900 }];
const FONT_ORIGINS = new Set(['https://fonts.googleapis.com', 'https://fonts.gstatic.com']);

export function captureOptions(args) {
  const options = {
    states: UX_STATES.filter((state) => state.platform !== 'android'),
    themes: ['light', 'dark'], viewports: VIEWPORTS, textScale: 1, allowDirty: false,
  };
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--allow-dirty') { options.allowDirty = true; continue; }
    const value = args[++i];
    if (!value || value.startsWith('--')) throw new Error(`Missing value for ${arg}`);
    if (arg === '--out') options.out = resolve(value);
    else if (arg === '--states') {
      const ids = value.split(',');
      options.states = ids.map((id) => {
        const state = UX_STATES.find((item) => item.id === id);
        if (!state) throw new Error(`Unknown requested UX state: ${id}`);
        if (state.platform === 'android') throw new Error(`Requested UX state is native-only: ${id}`);
        return state;
      });
      if (new Set(ids).size !== ids.length) throw new Error('Duplicate requested UX states');
    } else if (arg === '--theme') {
      if (!['light', 'dark', 'both'].includes(value)) throw new Error('Theme must be light, dark or both');
      options.themes = value === 'both' ? ['light', 'dark'] : [value];
    } else if (arg === '--viewport') {
      const match = value.match(/^(\d+)x(\d+)$/);
      if (!match || Number(match[1]) < 320 || Number(match[2]) < 320) throw new Error('Viewport must be WIDTHxHEIGHT, each at least 320');
      options.viewports = [{ width: Number(match[1]), height: Number(match[2]) }];
    } else if (arg === '--text-scale') {
      if (!['1', '2'].includes(value)) throw new Error('Text scale must be 1 or 2');
      options.textScale = Number(value);
    } else throw new Error(`Unknown capture option: ${arg}`);
  }
  if (!options.out) throw new Error('Supply --out with a development-artifact directory');
  return options;
}

async function until(cdp, expression, label, ms = 10000) {
  const end = performance.now() + ms;
  do {
    if (await cdp.evaluate(expression)) return;
    await new Promise((resolveWait) => setTimeout(resolveWait, 25));
  } while (performance.now() < end);
  throw new Error(`UX capture did not reach ${label}`);
}

async function loadPage(cdp, method, params) {
  let unsubscribe;
  let timer;
  const loaded = new Promise((resolveLoad, reject) => {
    unsubscribe = cdp.on('Page.loadEventFired', resolveLoad);
    timer = setTimeout(() => reject(new Error('UX capture navigation did not finish')), 15000);
  });
  try {
    await cdp.send(method, params);
    await loaded;
  } finally {
    clearTimeout(timer);
    unsubscribe();
  }
}

async function captureOne(options, scene, viewport, theme, fixtures, origin) {
  const browser = launchChrome(findChrome(), {
    url: 'about:blank', extraArgs: ['--remote-debugging-port=0', '--mute-audio',
      `--blink-settings=defaultFontSize=${16 * options.textScale},defaultFixedFontSize=${13 * options.textScale}`],
  });
  let cdp;
  try {
    cdp = await connectChrome(browser);
    const exceptions = [];
    const remote = [];
    const fontRequests = [];
    const interceptionErrors = [];
    cdp.on('Runtime.exceptionThrown', ({ exceptionDetails }) => exceptions.push(
      exceptionDetails.exception?.description || exceptionDetails.text));
    cdp.on('Network.requestWillBeSent', ({ request }) => {
      if (!/^https?:/.test(request.url) || new URL(request.url).origin === origin) return;
      if (FONT_ORIGINS.has(new URL(request.url).origin)) fontRequests.push(request.url);
      else remote.push(request.url);
    });
    cdp.on('Fetch.requestPaused', ({ requestId, request }) => {
      const blocked = /^https?:/.test(request.url) && new URL(request.url).origin !== origin
        && !FONT_ORIGINS.has(new URL(request.url).origin);
      if (blocked) remote.push(request.url);
      cdp.send(blocked ? 'Fetch.failRequest' : 'Fetch.continueRequest',
        blocked ? { requestId, errorReason: 'BlockedByClient' } : { requestId })
        .catch((error) => interceptionErrors.push(error.message));
    });
    await cdp.send('Runtime.enable');
    await cdp.send('Network.enable');
    await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
    await cdp.send('Page.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
      source: uxBootstrap({ now: fixtures.now, theme, state: scene.id }),
    });
    await loadPage(cdp, 'Page.navigate', { url: `${origin}/index.html` });
    await until(cdp, 'Boolean(document.getElementById("version")?.textContent)', 'initial boot');
    if (scene.dataset) {
      await cdp.evaluate(`(async () => {
        const { seedUxDataset } = await import(${JSON.stringify(MODULE)});
        await seedUxDataset(${JSON.stringify(fixtures.datasets[scene.dataset])});
      })()`);
      await loadPage(cdp, 'Page.reload', { ignoreCache: true });
      await until(cdp, 'Boolean(document.getElementById("version")?.textContent)', 'seeded boot');
    }
    const started = performance.now();
    await cdp.evaluate(`(async () => {
      const { presentUxState } = await import(${JSON.stringify(MODULE)});
      window.__uxSelectors = await presentUxState(window, ${JSON.stringify(scene)}, ${JSON.stringify(fixtures)});
    })()`);
    const settledMs = performance.now() - started;
    await cdp.evaluate(`(async () => {
      const { requireUxFont } = await import(${JSON.stringify(MODULE)});
      await requireUxFont(document);
    })()`);
    const expression = `(async () => {
      const { measureUxState } = await import(${JSON.stringify(MODULE)});
      return measureUxState(window, ${JSON.stringify(scene.id)}, window.__uxSelectors);
    })()`;
    const geometry = await cdp.evaluate(expression);
    if (Math.abs(geometry.fontSize - 16 * options.textScale) > .1) {
      throw new Error(`Requested text scale was not applied: ${geometry.fontSize}px`);
    }
    const checks = [];
    for (const width of [320, 360, 600, 840, 1440]) {
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width, height: width <= 360 ? 640 : viewport.height, deviceScaleFactor: 1, mobile: false,
      });
      checks.push(await cdp.evaluate(expression));
    }
    await cdp.send('Emulation.setDeviceMetricsOverride', { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp.evaluate(expression);
    await cdp.evaluate('window.scrollTo(0, 0)');
    if (exceptions.length) throw new Error(`Uncaught capture errors: ${exceptions.join('; ')}`);
    if (interceptionErrors.length) throw new Error(`Capture network guard failed: ${interceptionErrors.join('; ')}`);
    if (remote.length) throw new Error(`Fixture attempted external network: ${remote.join('; ')}`);
    const name = `${scene.id}-${theme}-${viewport.width}x${viewport.height}-text${options.textScale}`;
    const image = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    await writeFile(join(options.out, `${name}.png`), Buffer.from(image.data, 'base64'));
    const page = await cdp.send('Page.getLayoutMetrics');
    const extent = page.cssContentSize;
    const pageArtifacts = [];
    for (let y = 0, part = 1; y < extent.height; y += 12000, part++) {
      const full = await cdp.send('Page.captureScreenshot', {
        format: 'png', captureBeyondViewport: true,
        clip: { x: 0, y, width: viewport.width, height: Math.min(12000, extent.height - y), scale: 1 },
      });
      const path = `${name}-page-${part}.png`;
      await writeFile(join(options.out, path), Buffer.from(full.data, 'base64'));
      pageArtifacts.push({ artifact: path, y, height: Math.min(12000, extent.height - y) });
    }
    return {
      state: scene.id, evidence: scene.evidence, theme, viewport, density: 1,
      textScale: options.textScale, geometry, geometryChecks: checks,
      artifact: `${name}.png`, pageArtifacts,
      lab: { setup: 'isolated local Chromium, scripted DOM actions, no external model/media',
        approvedFontRequests: [...new Set(fontRequests)], stateSettleMs: settledMs, fieldWebVitals: false },
    };
  } finally {
    cdp?.close();
    browser.kill();
  }
}

export async function runUxCaptures(options) {
  if (!findChrome()) throw new Error('UX captures require the discovered Chrome/Edge browser');
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim();
  const dirty = Boolean(execFileSync('git', ['status', '--porcelain'], { cwd: ROOT, encoding: 'utf8' }).trim());
  if (!/^[0-9a-f]{40}$/.test(sha)) throw new Error('Missing tested Git SHA');
  if (dirty && !options.allowDirty) throw new Error('UX evidence requires a clean head; --allow-dirty labels development captures');
  await mkdir(options.out, { recursive: true });
  const manifestPath = join(options.out, 'manifest.json');
  let primaryError;
  try {
    await readFile(manifestPath);
    throw new Error(`Refusing to overwrite an existing capture manifest: ${manifestPath}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const scratch = await mkdtemp(join(tmpdir(), 'ideaforge-ux-site-'));
  let server;
  const fixtures = buildUxFixtures();
  const manifest = { schema: 1, testedSha: sha, dirty, status: 'running', expected: [], captures: [] };
  for (const scene of options.states) for (const theme of options.themes) for (const viewport of options.viewports) {
    manifest.expected.push({ state: scene.id, theme, viewport, textScale: options.textScale });
  }
  try {
    await assembleSite({ outDir: scratch });
    server = await serveRepo({
      root: scratch,
      before(_req, _res, url) {
        if ([MODULE, '/test/browser/fixtures/inference.js'].includes(url.pathname)) return { root: ROOT };
      },
    });
    const origin = `http://127.0.0.1:${server.address().port}`;
    for (const scene of options.states) for (const theme of options.themes) for (const viewport of options.viewports) {
      const entry = await captureOne(options, scene, viewport, theme, fixtures, origin);
      manifest.captures.push({ ...entry, testedSha: sha, dirty });
      console.log(`Captured ${scene.id} ${theme} ${viewport.width}x${viewport.height} text ${options.textScale}`);
      await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
    }
    if (manifest.captures.length !== manifest.expected.length) throw new Error('Missing requested UX evidence');
    if (execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim() !== sha) {
      throw new Error('HEAD changed during UX capture');
    }
    if (!dirty && execFileSync('git', ['status', '--porcelain'], { cwd: ROOT, encoding: 'utf8' }).trim()) {
      throw new Error('The working tree changed during clean-head UX capture');
    }
    manifest.status = 'passed';
  } catch (error) {
    manifest.status = 'failed';
    manifest.error = error.message;
    primaryError = error;
  } finally {
    await finishUxEvidence({
      primaryError,
      persist: () => writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n'),
      close: () => server ? new Promise((resolveClose, reject) =>
        server.close((error) => error ? reject(error) : resolveClose())) : Promise.resolve(),
      remove: () => rm(scratch, { recursive: true, force: true }),
    });
  }
  console.log(`UX manifest: ${manifestPath}`);
  return manifest;
}

export async function finishUxEvidence({ primaryError, persist, close, remove }) {
  const failures = primaryError ? [primaryError] : [];
  for (const operation of [persist, close, remove]) {
    try { await operation(); } catch (error) { failures.push(error); }
  }
  if (failures.length === 1) throw failures[0];
  if (failures.length) throw new AggregateError(failures, 'UX capture and cleanup failed');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await runUxCaptures(captureOptions(process.argv.slice(2)));
}
