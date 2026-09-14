// A whole interview against a real model, with nobody watching.
//
// Every other check in this repo fakes something. The unit suite injects `fetch`, the
// browser probes stub the provider, and tools/screenshots.mjs installs a scripted
// `window.claude.use('sample')`. All of that is deliberate and none of it can tell you
// whether IdeaForge actually works against a local model, because the one thing they all
// replace is the model.
//
// This replaces nothing. Real Ollama, real HTTP, real CSP, real IndexedDB, the real UI
// driven by clicks. It exists to be run by an agent and walked away from, so it exits
// non-zero and says which claim failed.
//
// The hard part is not driving the app — it is proving the run was real. A failed model
// call is answered from the static question bank and the interview carries on, so an
// interview can complete end to end having never once reached the model and still look
// perfectly healthy on screen. Three independent signals are checked for that, and the only
// one that cannot be faked by the app's own bookkeeping is the count of POST requests that
// actually left the browser.

import { ROOT, findChrome, serveRepo, launchChrome } from './lib/harness.mjs';

const OLLAMA = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
const MODEL = process.env.IDEAFORGE_MODEL || 'qwen2.5:7b-instruct';
/** Generous: a cold model load plus a 48 KiB prompt on a mid-range GPU. */
const TURN_MS = Number(process.env.VALIDATE_TURN_MS || 180000);
const BOOT_MS = 20000;
/** How many questions to answer before asking for the wrap-up. */
const TURNS = Number(process.env.VALIDATE_TURNS || 4);
/**
 * Where the app itself is served from. Unset, we serve the working tree — what you want
 * while changing it. Set, we drive whatever is already at that origin, which is how the
 * built container gets validated rather than merely built.
 */
const APP_URL = process.env.IDEAFORGE_URL || '';

const ANSWERS = [
  'A tool that helps me remember the names of people I meet at conferences, because I '
  + 'lose them within about a minute of the handshake and it is embarrassing.',
  'At the last conference I met maybe forty people over two days and could name four of '
  + 'them by the evening. The ones I lost were the ones I met in corridors, standing up.',
  'Good looks like walking into the second day and greeting six people by name without '
  + 'checking anything, and never once calling somebody by the wrong name.',
  'It has to work in three seconds with one thumb, standing up, holding a drink, with no '
  + 'signal in a conference centre basement.',
  'It is for me and people like me — consultants and founders who go to a lot of events '
  + 'and whose job depends on remembering who they already met.',
  'Plain and quiet. It should never be chirpy about my memory failing, and it should not '
  + 'gamify anything. A notebook, not a coach.',
  'Like a paper notebook I already keep, and nothing like a CRM. Definitely not LinkedIn.',
  'The riskiest part is whether I will actually type anything in during the conversation '
  + 'rather than promising myself I will do it later and never doing it.',
];

// ───────────────────────────────────────────────────────────── claims

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail: String(detail) });
  console.log(ok ? `  ok    ${name}${detail ? `  (${detail})` : ''}` : `  FAIL  ${name}  ${detail}`);
  return !!ok;
};

// ───────────────────────────────────────────── a minimal CDP client

/**
 * Same shape as the one in tools/screenshots.mjs, with two additions a real run needs:
 * a persistent subscription, because console output and network events arrive throughout
 * rather than once, and a timeout on send, because a socket that dies mid-run otherwise
 * leaves a promise pending forever and the harness hangs instead of failing.
 */
class CDP {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.handlers = new Map();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id != null) {
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new Error(msg.error.message));
        else p.resolve(msg.result);
        return;
      }
      for (const fn of this.handlers.get(msg.method) || []) fn(msg.params);
    });
  }

  static async attach(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true });
      ws.addEventListener('error', () => reject(new Error('could not attach to ' + url)), { once: true });
    });
    return new CDP(ws);
  }

  on(method, fn) {
    if (!this.handlers.has(method)) this.handlers.set(method, []);
    this.handlers.get(method).push(fn);
  }

  send(method, params = {}, timeout = 30000) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error(`CDP ${method} timed out`));
      }, timeout).unref?.();
    });
  }

  close() { try { this.ws.close(); } catch { /* already gone */ } }
}

// ─────────────────────────────────────────────────────── driving it

class App {
  constructor(cdp) { this.cdp = cdp; }

  async eval(expression) {
    const r = await this.cdp.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    });
    if (r.exceptionDetails) {
      throw new Error('page threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    }
    return r.result.value;
  }

  async waitFor(expression, what, timeout) {
    const deadline = Date.now() + timeout;
    for (;;) {
      if (await this.eval(`!!(${expression})`)) return;
      if (Date.now() > deadline) throw new Error(`timed out after ${timeout}ms waiting for ${what}`);
      await sleep(150);
    }
  }

  text(id) { return this.eval(`(document.getElementById(${JSON.stringify(id)})||{}).textContent || ''`); }
  hidden(id) { return this.eval(`!!(document.getElementById(${JSON.stringify(id)})||{}).hidden`); }

  set(id, value, event) {
    return this.eval(`(() => {
      const el = document.getElementById(${JSON.stringify(id)});
      el.value = ${JSON.stringify(value)};
      el.dispatchEvent(new Event(${JSON.stringify(event)}));
    })()`);
  }

  click(id) { return this.eval(`document.getElementById(${JSON.stringify(id)}).click()`); }

  /**
   * The turn index out of #turnline, which reads "question N of up to 25 · dimension".
   *
   * Deliberately not the screenshots harness's trick of waiting for the question TEXT to
   * change: a real model repeats itself often enough that waiting on a difference hangs the
   * run, and a bank fallback repeats by construction.
   */
  async turn() {
    const line = await this.text('turnline');
    const m = line.match(/question (\d+)/);
    return m ? Number(m[1]) : 0;
  }

  /** The session as the app actually stored it — the run's own record, not a rendering. */
  session() {
    return this.eval(`(async () => {
      const db = await new Promise((res, rej) => {
        const r = indexedDB.open('ideaforge');
        r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
      });
      const rows = await new Promise((res, rej) => {
        const r = db.transaction('sessions').objectStore('sessions').getAll();
        r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
      });
      rows.sort((a, b) => b.updatedAt - a.updatedAt);
      return rows[0] || null;
    })()`);
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ────────────────────────────────────────────────────── the ollama end

async function ollama(path) {
  const res = await fetch(`${OLLAMA}${path}`, { signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error(`${path} answered ${res.status}`);
  return res.json();
}

/**
 * Prove the model is on the GPU, not merely running.
 *
 * A CUDA setup that is subtly wrong does not fail — Ollama falls back to CPU and everything
 * still works, just ten to fifty times slower. That is the single easiest way to spend an
 * afternoon concluding the app is slow. `size_vram` against `size` is the honest answer.
 */
async function gpuResidency() {
  const { models = [] } = await ollama('/api/ps');
  const loaded = models.find((m) => m.name === MODEL || m.model === MODEL);
  if (!loaded) return null;
  const total = Number(loaded.size || 0);
  const vram = Number(loaded.size_vram || 0);
  return { total, vram, share: total ? vram / total : 0 };
}

// ───────────────────────────────────────────────────────────── the run

async function main() {
  const chrome = findChrome();
  if (!chrome) {
    console.error('no Chrome found. Set CHROME_PATH.');
    return 1;
  }

  console.log(`ollama ${OLLAMA}  ·  model ${MODEL}\n`);

  console.log('the model');
  let version;
  try {
    version = (await ollama('/api/version')).version;
    check('Ollama is reachable', true, `v${version}`);
  } catch (err) {
    check('Ollama is reachable', false, `${err.message} — is it running?`);
    return 1;
  }
  const tags = await ollama('/api/tags');
  const names = (tags.models || []).map((m) => m.name);
  if (!check('the model is pulled', names.includes(MODEL), names.join(', ') || 'none')) {
    console.error(`\n  pull it first:  ollama pull ${MODEL}`);
    return 1;
  }

  const server = APP_URL ? null : await serveRepo({ root: ROOT });
  const appUrl = APP_URL || `http://127.0.0.1:${server.address().port}/`;
  console.log(`
app ${appUrl}${APP_URL ? '' : '  (the working tree)'}`);
  const browser = launchChrome(chrome, {
    url: 'about:blank',
    extraArgs: [
      '--remote-debugging-port=0',
      // The voice probe constructs a recogniser on every interview start even with
      // dictation off. Grant rather than prompt, so nothing waits on a dialog.
      '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
    ],
  });

  const chatCalls = [];
  const consoleLines = [];
  const pageErrors = [];
  const cspViolations = [];

  try {
    await run();
  } catch (err) {
    // A timeout or a page exception is a result, not a crash. Recording it keeps the
    // summary — and the exit code — meaningful to whoever reads the output later.
    check('the run got all the way through', false, err && err.message ? err.message : String(err));
  } finally {
    browser.kill();
    if (server) server.close();
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n${results.length} claims, ${failed} failed`);
  return failed ? 1 : 0;

  async function run() {
    const cdp = await CDP.attach(await pageTarget(browser.profile));
    const app = new App(cdp);

    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Network.enable');

    // The one signal the app cannot fake for us: what actually left the browser.
    cdp.on('Network.requestWillBeSent', (p) => {
      if (p.request.method === 'POST' && p.request.url.includes('/chat/completions')) {
        chatCalls.push(p.request.url);
      }
    });
    cdp.on('Runtime.consoleAPICalled', (p) => {
      const text = (p.args || []).map((a) => a.value ?? a.description ?? '').join(' ');
      consoleLines.push({ type: p.type, text });
    });
    cdp.on('Runtime.exceptionThrown', (p) => {
      pageErrors.push(p.exceptionDetails?.exception?.description || p.exceptionDetails?.text || 'error');
    });

    await cdp.send('Page.navigate', { url: appUrl });
    await app.waitFor(`document.getElementById('version').textContent`, 'the app to boot', BOOT_MS);

    // A CSP refusal never throws; it only fires an event. Without listening, a broken
    // policy looks exactly like a passing run.
    await app.eval(`(() => {
      window.__csp = [];
      addEventListener('securitypolicyviolation',
        (e) => window.__csp.push(e.violatedDirective + ' blocked ' + e.blockedURI));
    })()`);

    console.log('\nthe app');
    check('it boots with no uncaught error', pageErrors.length === 0, pageErrors.join('; '));

    // ── settings ────────────────────────────────────────────────────
    console.log('\nsettings');
    await app.set('provider', 'ollama', 'change');
    check('the local server fields appear', !(await app.hidden('field-base')));

    await app.set('baseurl', `${OLLAMA}/v1`, 'input');
    check('the address is accepted as local', (await app.text('base-note')) === '');

    await app.set('stt', 'off', 'change');
    await app.click('b-check');
    await app.waitFor(
      `!/Reading the model list/.test(document.getElementById('model-note').textContent)`,
      'the model list', 60000,
    );
    const modelNote = await app.text('model-note');
    // #model-note, never #note: the success message used to be printed unconditionally.
    check('the model list is read off the real server', /\d+ models? installed/.test(modelNote), modelNote);

    const listed = await app.eval(
      `[...document.getElementById('model-list').options].map((o) => o.value)`,
    );
    check('the model we mean to use is in it', listed.includes(MODEL), `${listed.length} listed`);

    await app.set('model', MODEL, 'input');

    // ── the interview ───────────────────────────────────────────────
    console.log('\nthe interview');
    await app.click('b-start');
    await app.waitFor(`!document.getElementById('panel-interview').hidden`, 'the interview panel', BOOT_MS);
    check('turn 1 needs no model call', chatCalls.length === 0, `${chatCalls.length} calls so far`);

    let banked = 0;
    let residency = null;
    for (let i = 0; i < TURNS; i++) {
      const before = await app.turn();
      await app.set('answer', ANSWERS[i % ANSWERS.length], 'input');
      await app.click('b-send');
      await app.waitFor(
        `document.getElementById('asking').hidden && ${JSON.stringify(before)} !== (
          (document.getElementById('turnline').textContent.match(/question (\\d+)/) || [])[1] | 0
        )`,
        `question ${before + 1}`, TURN_MS,
      );
      const line = await app.text('turnline');
      if (/built-in checklist/.test(line)) banked++;
      const err = (await app.hidden('err')) ? '' : await app.text('err');
      if (err) {
        // Report it rather than throwing: a thrown error skips the summary, and the whole
        // point of this tool is that it says which claim failed.
        check(`turn ${before + 1} reached the model`, false, err);
        break;
      }

      // After the first turn, not before it: Ollama loads a model lazily on the first
      // request, so /api/ps is legitimately empty until the app has actually asked for
      // something. Checking too early reports a CPU fallback that has not happened yet.
      if (residency === null) residency = (await gpuResidency()) || false;
    }

    if (residency) {
      check('the model is resident in VRAM, not on the CPU',
        residency.share > 0.9,
        `${(residency.share * 100).toFixed(0)}% of ${(residency.total / 1e9).toFixed(1)}GB in VRAM`);
    } else {
      check('the model is resident in VRAM, not on the CPU', false,
        'nothing loaded per /api/ps after a turn — running on CPU, or a different model');
    }

    check('every question after the seed came from the model', banked === 0,
      banked ? `${banked} came from the built-in checklist` : `${TURNS} turns`);

    // ── the wrap-up ─────────────────────────────────────────────────
    console.log('\nthe wrap-up');
    await app.waitFor(`!document.getElementById('b-wrap').hidden`, 'the wrap-up button', 10000);
    await app.click('b-wrap');
    await app.waitFor(
      `document.getElementById('done-title').textContent !== 'Writing it up…'`,
      'the synthesis', TURN_MS,
    );
    await app.waitFor(
      `document.getElementById('output').textContent.includes('Forged with IdeaForge')`,
      'the export', 30000,
    );

    const meta = await app.text('done-meta');
    check('no turn fell back to the checklist', !/built-in checklist/.test(meta), meta);

    // Read the pane, not the download button: wrapUp never calls busy(), so the button is
    // live while #output is still empty and a click there writes a zero-byte file.
    const md = await app.text('output');
    check('the export carries a refined prompt', /## Refined prompt/.test(md) && md.length > 800,
      `${md.length} chars`);
    check('the export is not the checklist fallback', !/_Not generated\./.test(md));
    check('the export does not disclaim bank questions', !/built-in checklist/.test(md));

    // ── the record, and the wire ────────────────────────────────────
    console.log('\nwhat actually happened');
    const s = await app.session();
    const sources = (s?.turns || []).map((t) => t.questionSource);
    check('the stored session records every question as the model’s',
      sources.length > 1 && sources[0] === 'seed' && sources.slice(1).every((x) => x === 'model'),
      sources.join(','));

    // The claim that cannot be faked by the app's own bookkeeping: what left the browser.
    // At least one call per model-sourced question plus the synthesis — more is fine and
    // expected, because runTurn regenerates once when a reply fails its tripwires or comes
    // back misshapen, and a small local model earns that fairly often.
    const wanted = sources.filter((x) => x === 'model').length + 1;
    check('a chat request left the browser for every turn',
      chatCalls.length >= wanted,
      `${chatCalls.length} POSTs for ${wanted - 1} questions + 1 synthesis` +
      (chatCalls.length > wanted ? ` (${chatCalls.length - wanted} regenerated)` : ''));

    // Whether coverage RISES is the model's call, not the app's: a 7B frequently grades
    // everything `thin`, honestly, and a four-turn interview then ends at 0% with nothing
    // wrong. Gating on that makes the validator cry wolf. What must be true is that the
    // claims arrived and were applied at all — a gap recorded against a dimension is proof
    // the coverage path ran end to end, and the levels are reported for the reader to judge.
    const cov = s?.coverage || {};
    const levels = Object.entries(cov).map(([k, v]) => `${k}:${v.level}`).join(' ');
    const applied = Object.values(cov).some((c) => c.level !== 'thin' || c.gap);
    check('the coverage path ran and the model’s claims were applied', applied, levels);
    const risen = Object.values(cov).filter((c) => c.level !== 'thin').length;
    console.log(`  note  coverage after ${TURNS} turns: ${risen}/7 dimensions above thin`);

    // Not every warning means the run went wrong. The runtime also reports quirks it
    // absorbed — a model mis-keying its coverage block, say — and those are worth printing
    // but not worth failing: the whole point of the parse layer is that it survives them.
    // These are the ones that mean the model was not reached or its reply was unusable.
    const warnings = consoleLines
      .filter((l) => /^\[ideaforge\]/.test(l.text))
      .flatMap((l) => l.text.replace(/^\[ideaforge\]\s*/, '').split('; '));
    const serious = warnings.filter(
      (w) => /^(provider |unfixable |no question|no prompt|the model returned no usable)/.test(w));
    check('nothing went wrong that the runtime had to paper over',
      serious.length === 0, serious.join(' | '));

    const absorbed = warnings.filter((w) => !serious.includes(w));
    if (absorbed.length) {
      const counts = new Map();
      for (const w of absorbed) counts.set(w, (counts.get(w) || 0) + 1);
      console.log(`  note  the parser absorbed: ${
        [...counts].map(([w, n]) => `${w}${n > 1 ? ` ×${n}` : ''}`).join(', ')}`);
    }

    const csp = await app.eval('window.__csp || []');
    check('the CSP blocked nothing the app needed', csp.length === 0, csp.join('; '));
    check('no uncaught error for the whole run', pageErrors.length === 0, pageErrors.join('; '));

    cdp.close();
  }
}

/** Chrome writes the port it actually chose into the profile once it is listening. */
async function pageTarget(profile, timeout = 20000) {
  const { readFile } = await import('node:fs/promises');
  const { join } = await import('node:path');
  const portFile = join(profile, 'DevToolsActivePort');
  const deadline = Date.now() + timeout;
  for (;;) {
    try {
      const [port] = (await readFile(portFile, 'utf8')).split('\n');
      const res = await fetch(`http://127.0.0.1:${port.trim()}/json/list`);
      const page = (await res.json()).find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch { /* not listening yet */ }
    if (Date.now() > deadline) throw new Error('Chrome never opened a debugging port');
    await sleep(100);
  }
}

process.exitCode = await main();
