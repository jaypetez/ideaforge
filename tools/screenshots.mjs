// Photographs of the real app, for the README.
//
// The screenshots in docs/ are not mockups and the worked example in docs/examples/ was not
// typed by hand. Both come out of this harness, which serves the repo, drives the actual UI
// in headless Chrome, and captures what it finds. A README that shows a drawing of an app is
// a README that starts lying the first time the app changes.
//
// The one thing that is faked is the model. src/providers/artifact.js builds a provider out
// of `window.claude.use('sample')` — no key, no network, no CORS — so installing a scripted
// stub at that seam leaves src/core, src/runtime, src/providers and src/ui all running for
// real. The stub is injected with Page.addScriptToEvaluateOnNewDocument, which is not subject
// to the page's Content-Security-Policy; an inline <script> would be refused by it.
//
// Three details that are load-bearing rather than incidental:
//
//   Date.now is frozen. buildExport stamps the document with session.updatedAt, so without a
//   fixed clock docs/examples/remember-names.md has a different first line on every run and
//   the diff is never reviewable.
//
//   prefers-reduced-motion is emulated as `reduce`. app.css transitions the coverage meter
//   over .35s, and a screenshot taken mid-transition catches the bar at an arbitrary width.
//   The app's own last CSS rule turns that off, so we ask for it rather than sleeping and
//   hoping.
//
//   Results are read out of the live DOM over CDP, never from --dump-dom, for the same reason
//   tools/browser-check.mjs posts its results over HTTP: --dump-dom snapshots the page before
//   the awaited IndexedDB and provider work has finished.

import { writeFile, mkdir, readFile, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { ROOT, findChrome, serveRepo, launchChrome } from './lib/harness.mjs';
import { ANSWERS, TURNS, SYNTHESIS } from './fixtures/walkthrough.mjs';

const SHOT_DIR = join(ROOT, 'docs', 'screenshots');
const EXAMPLE = join(ROOT, 'docs', 'examples', 'remember-names.md');
const WIDTH = 760;
const HEIGHT = 820;
/** 1.5 rather than 2: still sharp at the width GitHub renders a README at, 40% fewer bytes. */
const SCALE = 1.5;
const MAX_SHOT_HEIGHT = 1700;
/** 2026-09-13T09:00:00Z. Any fixed instant will do; it only has to stop moving. */
const FIXED_NOW = Date.parse('2026-09-13T09:00:00Z');
const STEP_TIMEOUT = 20000;

// ─────────────────────────────────────────────────────── a minimal CDP client

class CDP {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.waiters = [];
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id != null) {
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new Error(`${msg.error.message} (${JSON.stringify(msg.error)})`));
        else p.resolve(msg.result);
        return;
      }
      for (const w of this.waiters.splice(0)) {
        if (w.method === msg.method) w.resolve(msg.params);
        else this.waiters.push(w);
      }
    });
  }

  /** Node 22 ships a global WebSocket, which is the entire reason this needs no dependency. */
  static async attach(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true });
      ws.addEventListener('error', () => reject(new Error('could not attach to ' + url)), { once: true });
    });
    return new CDP(ws);
  }

  send(method, params = {}) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  once(method, timeout = STEP_TIMEOUT) {
    return new Promise((resolve, reject) => {
      const w = { method, resolve };
      this.waiters.push(w);
      setTimeout(() => {
        const i = this.waiters.indexOf(w);
        if (i >= 0) { this.waiters.splice(i, 1); reject(new Error('timed out waiting for ' + method)); }
      }, timeout);
    });
  }

  close() { try { this.ws.close(); } catch { /* already gone */ } }
}

// ─────────────────────────────────────────────────────────── driving the app

/** Runs before any of the app's own modules, and outside the page CSP. */
function bootstrap(theme) {
  return `(() => {
  const TURNS = ${JSON.stringify(TURNS)};
  const SYNTHESIS = ${JSON.stringify(SYNTHESIS)};
  Date.now = () => ${FIXED_NOW};
  window.__trace = [];

  window.claude = {
    use: async (capability) => {
      if (capability !== 'sample') {
        throw Object.assign(new Error('only sample is stubbed'), { code: 'not_granted' });
      }
      return async (prompt, opts = {}) => {
        const modelTier = opts.modelTier || 'default';
        if (modelTier === 'complex') {
          window.__trace.push({ synthesis: true });
          return { text: JSON.stringify(SYNTHESIS), modelTierApplied: modelTier };
        }
        const turn = Number((prompt.match(/^Turn number: (\\d+)/m) || [])[1] || 0);
        const target = (prompt.match(/^Target dimension: ([a-z_]+)/m) || [])[1] || '';
        const reply = TURNS[String(turn)];
        window.__trace.push({ turn, target, wroteFor: reply ? reply.forDimension : null });
        if (!reply) {
          throw Object.assign(new Error('no scripted reply for turn ' + turn), { code: 'bad_response' });
        }
        return { text: JSON.stringify(reply), modelTierApplied: modelTier };
      };
    },
  };

  // documentElement does not exist yet on the very first evaluation, so set it twice.
  const paint = () => { if (document.documentElement) document.documentElement.dataset.theme = ${JSON.stringify(theme)}; };
  paint();
  document.addEventListener('DOMContentLoaded', paint);
})()`;
}

class App {
  constructor(cdp, theme) {
    this.cdp = cdp;
    this.theme = theme;
    this.written = [];
  }

  async eval(expression) {
    const r = await this.cdp.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    });
    if (r.exceptionDetails) {
      throw new Error('page threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    }
    return r.result.value;
  }

  async waitFor(expression, what, timeout = STEP_TIMEOUT) {
    const deadline = Date.now() + timeout;
    for (;;) {
      if (await this.eval(`!!(${expression})`)) return;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 80));
    }
  }

  /**
   * Size the viewport to the app shell, then photograph the viewport.
   *
   * Not captureBeyondViewport: `.output` is capped at `max-height: 60vh`, so a capture that
   * silently grows the viewport also grows the export pane it is trying to frame, and the
   * shot runs away to the height clamp. Measuring, resizing and re-measuring converges
   * instead.
   */
  async fit() {
    let height = 0;
    for (let pass = 0; pass < 4; pass++) {
      // `.wrap` carries 72px of bottom padding for thumbs on a phone, which in a screenshot
      // is just dead space. Keep a little of it and crop the rest.
      const measured = await this.eval(
        `Math.ceil(document.querySelector('.wrap').getBoundingClientRect().bottom) - 48`,
      );
      const next = Math.min(Math.max(measured, 260), MAX_SHOT_HEIGHT);
      if (next === height) break;
      height = next;
      await this.cdp.send('Emulation.setDeviceMetricsOverride', {
        width: WIDTH, height, deviceScaleFactor: SCALE, mobile: false,
      });
    }
    return height;
  }

  async shot(name) {
    const height = await this.fit();
    const { data } = await this.cdp.send('Page.captureScreenshot', { format: 'png' });
    const file = join(SHOT_DIR, `${name}.${this.theme}.png`);
    const bytes = Buffer.from(data, 'base64');
    await writeFile(file, bytes);
    this.written.push({ file, bytes: bytes.length, px: pngSize(bytes), css: `${WIDTH}x${height}` });
    // Back to the working viewport, so the next step lays out the way the driver expects.
    await this.cdp.send('Emulation.setDeviceMetricsOverride', {
      width: WIDTH, height: HEIGHT, deviceScaleFactor: SCALE, mobile: false,
    });
    return file;
  }

  async answer(text) {
    await this.eval(`(() => {
      const box = document.getElementById('answer');
      box.value = ${JSON.stringify(text)};
      box.dispatchEvent(new Event('input', { bubbles: true }));
      document.getElementById('b-send').click();
    })()`);
  }

  question() {
    return this.eval(`document.getElementById('question').textContent`);
  }

  /** Settled means: not mid-call, and showing a question we have not already photographed. */
  async settled(previous) {
    await this.waitFor(
      `document.getElementById('asking').hidden
       && document.getElementById('question').textContent !== ${JSON.stringify(previous)}`,
      'the next question',
    );
  }
}

function pngSize(buf) {
  // IHDR width/height are the two big-endian uint32s at byte 16.
  return `${buf.readUInt32BE(16)}x${buf.readUInt32BE(20)}`;
}

async function run(theme, chrome, port) {
  const browser = launchChrome(chrome, {
    extraArgs: ['--remote-debugging-port=0', '--hide-scrollbars', '--mute-audio'],
    url: 'about:blank',
  });

  const wsUrl = await pageTarget(browser.profile);
  const cdp = await CDP.attach(wsUrl);
  const app = new App(cdp, theme);

  try {
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: WIDTH, height: HEIGHT, deviceScaleFactor: SCALE, mobile: false,
    });
    // The app's own final CSS rule kills every transition under this, which is what makes the
    // coverage meter land on an exact width instead of wherever .35s of easing got to.
    await cdp.send('Emulation.setEmulatedMedia', {
      features: [{ name: 'prefers-reduced-motion', value: 'reduce' }],
    });
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: bootstrap(theme) });

    const loaded = cdp.once('Page.loadEventFired');
    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${port}/` });
    await loaded;

    await app.waitFor(`document.getElementById('version').textContent`, 'the app to boot');
    await app.eval('document.fonts.ready');
    const plex = await app.eval(`document.fonts.check('600 25px "IBM Plex Sans"')`);
    if (!plex) {
      console.warn('  WARN  IBM Plex did not load — these shots are in fallback faces.');
      console.warn('        The CSP allows fonts only from Google Fonts; this needs network.');
    }

    // 01 — the setup screen as someone outside a Claude viewer meets it. The stub makes
    // `artifact` the default, so select the provider most people will actually pick.
    await app.eval(`(() => {
      const p = document.getElementById('provider');
      p.value = 'anthropic';
      p.dispatchEvent(new Event('change'));
    })()`);
    await app.shot('01-setup');

    // Back to the stubbed provider, and dictation off so no microphone probe or cost banner
    // lands in a shot that is meant to be about the interview.
    await app.eval(`(() => {
      const p = document.getElementById('provider');
      p.value = 'artifact';
      p.dispatchEvent(new Event('change'));
      const s = document.getElementById('stt');
      s.value = 'off';
      s.dispatchEvent(new Event('change'));
      document.getElementById('b-start').click();
    })()`);

    await app.waitFor(`!document.getElementById('panel-interview').hidden`, 'the interview panel');
    await app.waitFor(`document.getElementById('question').textContent.length > 5`, 'the seed question');
    await app.shot('02-first-question');

    let shown = await app.question();
    for (let i = 0; i < ANSWERS.length; i++) {
      await app.answer(ANSWERS[i]);
      await app.settled(shown);
      shown = await app.question();

      // Question 7: a bridge grounded in the previous answer, four chips, the meter past
      // halfway, and a coverage panel showing all three levels at once. Earlier turns show
      // less; later ones are about wrapping up.
      if (i === 5) {
        await app.shot('03-mid-interview');
        await app.eval(`document.querySelector('details.cov').open = true`);
        await app.shot('04-coverage');
        await app.eval(`document.querySelector('details.cov').open = false`);
      }

      const offered = await app.eval(`!document.getElementById('note').hidden`);
      if (offered) break;
    }

    const note = await app.eval(`document.getElementById('note').textContent`);
    if (!note) throw new Error('the interview never offered to wrap up — the fixture ran out');
    await app.shot('05-ready-to-wrap');

    await app.eval(`document.getElementById('b-wrap').click()`);
    await app.waitFor(`!document.getElementById('panel-done').hidden`, 'the done panel');
    await app.waitFor(
      `document.getElementById('output').textContent.includes('Forged with IdeaForge')`,
      'the export to render',
    );
    await app.shot('06-refined-prompt');

    const markdown = await app.eval(`document.getElementById('output').textContent`);
    await app.eval(`document.getElementById('b-library').click()`);
    await app.waitFor(
      `!document.getElementById('panel-library').hidden
       && document.querySelectorAll('#library-rows .idea-card').length === 1`,
      'the ideas library',
    );
    await app.shot('07-ideas-library');

    const trace = await app.eval('window.__trace');
    const err = await app.eval(
      `document.getElementById('err').hidden ? '' : document.getElementById('err').textContent`,
    );
    if (err) throw new Error('the app reported an error during capture: ' + err);

    return { app, markdown, trace, note, plex };
  } finally {
    cdp.close();
    browser.kill();
  }
}

/** Chrome writes the port it actually chose into the profile once it is listening. */
async function pageTarget(profile, timeout = 20000) {
  const portFile = join(profile, 'DevToolsActivePort');
  const deadline = Date.now() + timeout;
  for (;;) {
    try {
      const [port] = (await readFile(portFile, 'utf8')).split('\n');
      const res = await fetch(`http://127.0.0.1:${port.trim()}/json/list`);
      const targets = await res.json();
      const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch { /* not listening yet */ }
    if (Date.now() > deadline) throw new Error('Chrome never opened a debugging port');
    await new Promise((r) => setTimeout(r, 100));
  }
}

async function main() {
  const chrome = findChrome();
  if (!chrome) {
    console.error('no Chrome found. Set CHROME_PATH to point at one.');
    return 1;
  }
  await mkdir(SHOT_DIR, { recursive: true });
  await mkdir(join(ROOT, 'docs', 'examples'), { recursive: true });

  const server = await serveRepo();
  const { port } = server.address();
  let first = null;

  try {
    for (const theme of ['light', 'dark']) {
      console.log(`\n${theme}`);
      const out = await run(theme, chrome, port);
      for (const w of out.app.written) {
        console.log(`  ${w.file.slice(ROOT.length).replace(/\\/g, '/')}  ${w.px}  ${kb(w.bytes)}`);
      }
      if (!first) first = out;
      else if (out.markdown !== first.markdown) {
        throw new Error('the two runs exported different markdown — the clock is not frozen');
      }
    }
  } finally {
    server.close();
  }

  await writeFile(EXAMPLE, first.markdown, 'utf8');
  console.log(`\n  ${EXAMPLE.slice(ROOT.length).replace(/\\/g, '/')}  ${kb(Buffer.byteLength(first.markdown))}`);

  console.log('\nthe engine chose:');
  for (const t of first.trace) {
    if (t.synthesis) { console.log('  synthesis'); continue; }
    const flag = t.wroteFor && t.wroteFor !== t.target ? `  MISMATCH (written for ${t.wroteFor})` : '';
    console.log(`  turn ${String(t.turn).padStart(2)}  ${t.target}${flag}`);
  }
  const mismatched = first.trace.filter((t) => t.wroteFor && t.wroteFor !== t.target);
  if (mismatched.length) {
    console.log(`\n${mismatched.length} question(s) were written for a dimension the engine did not pick.`);
    console.log('Rewrite them in tools/fixtures/walkthrough.mjs rather than leaving them.');
  }

  console.log(`\nwrapped because: ${first.note}`);
  console.log(`docs/ is now ${kb(await dirBytes(join(ROOT, 'docs')))}`);
  return mismatched.length ? 1 : 0;
}

function kb(n) { return `${(n / 1024).toFixed(1)} KiB`; }

async function dirBytes(dir) {
  let total = 0;
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    total += entry.isDirectory() ? await dirBytes(p) : (await stat(p)).size;
  }
  return total;
}

process.exitCode = await main();
