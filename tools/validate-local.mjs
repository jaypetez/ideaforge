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

import { connect } from 'node:net';
import { lookup } from 'node:dns/promises';

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

import { ROOT, findChrome, serveRepo, launchChrome } from './lib/harness.mjs';
import { spokenAnswers, YES, TRIGGER } from './fixtures/spoken.mjs';

const OLLAMA = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
const MODEL = process.env.IDEAFORGE_MODEL || 'qwen2.5:7b-instruct';
/** Generous: a cold model load plus a 48 KiB prompt on a mid-range GPU. */
const TURN_MS = Number(process.env.VALIDATE_TURN_MS || 180000);
const BOOT_MS = 20000;
/** How many questions to answer before asking for the wrap-up. */
const TURNS = Number(process.env.VALIDATE_TURNS || 4);
/**
 * The speed floor, expressed as memory bandwidth rather than tokens per second.
 *
 * Decode reads essentially the whole weight set once per token, so tok/s x weight bytes
 * estimates the bandwidth the model is actually being read at. That is a property of the
 * device, not of the model, so one number covers a 7B and a 14B without being retuned every
 * time the default changes — which is how a tok/s threshold rots. Dual-channel DDR5 is
 * ~90 GB/s theoretical and llama.cpp realises about half; a mid-range discrete card is
 * 300-500. 100 sits in the empty band between the two populations rather than beside either.
 */
const MIN_GBPS = Number(process.env.VALIDATE_MIN_GBPS || 100);
/** For someone who knowingly has no GPU and wants the rest of the run regardless. */
const ALLOW_CPU = process.env.VALIDATE_ALLOW_CPU === '1';
/**
 * Where the app itself is served from. Unset, we serve the working tree — what you want
 * while changing it. Set, we drive whatever is already at that origin, which is how the
 * built container gets validated rather than merely built.
 */
const APP_URL = process.env.IDEAFORGE_URL || '';
/**
 * `typed` is the original contract and stays the default. `handsfree` drives the same
 * interview entirely by voice, with the recogniser and synthesiser scripted and the model
 * still real — so what is faked is how the answers arrive, never what the model does with
 * them. Run it twice for both; a single process cannot hold two browser sessions without
 * restructuring everything around it for no benefit.
 */
const MODE = process.env.VALIDATE_MODE || 'typed';
const HANDS_FREE = MODE === 'handsfree';

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

async function ollamaPost(path, body, ms) {
  const res = await fetch(`${OLLAMA}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(ms),
  });
  if (!res.ok) throw new Error(`${path} answered ${res.status}`);
  return res.json();
}

const pct = (x) => `${(x * 100).toFixed(0)}%`;
const gb = (b) => `${(b / 1e9).toFixed(2)}GB`;

/**
 * Which socket actually answered — printed, never asserted.
 *
 * undici and net.connect each run their own Happy Eyeballs race, so on a genuinely split
 * name they can in principle disagree. The claim that has to be right is the fingerprint,
 * which is taken over the same fetch path as everything else.
 */
function peerOf(url, ms = 3000) {
  const u = new URL(url);
  return new Promise((resolve) => {
    const s = connect({ host: u.hostname, port: Number(u.port || 80) });
    const done = (v) => { s.destroy(); resolve(v); };
    s.setTimeout(ms, () => done(null));
    s.once('error', () => done(null));
    s.once('connect', () => done(`${s.remoteAddress}:${s.remotePort}`));
  });
}

/** What this particular server IS, rather than what we hoped it was. */
function fingerprint(version, tags) {
  const models = (tags && tags.models) || [];
  const mine = models.find((m) => m.name === MODEL || m.model === MODEL);
  return {
    version,
    names: models.map((m) => m.name).sort().join(','),
    digest: mine ? String(mine.digest || '').slice(0, 12) : '',
    bytes: mine ? Number(mine.size || 0) : 0,
  };
}

const sameServer = (a, b) => !!a && !!b && a.version === b.version && a.names === b.names;

/**
 * Every address the configured name could mean.
 *
 * Two Ollamas on one machine is the normal state, not the exotic one: a native install
 * answers 127.0.0.1 while a Docker-published one answers [::1], both on 11434, and
 * `localhost` picks between them per resolver. Node's pick and Chrome's need not agree, so a
 * run can read /api/ps off one server while the page talks to the other and every claim
 * still passes. Ask each address separately and compare what comes back.
 */
async function everyMeaning(url) {
  const u = new URL(url);
  if (!/^[a-z]/i.test(u.hostname)) return [];
  const addrs = await lookup(u.hostname, { all: true, verbatim: true }).catch(() => []);
  if (addrs.length < 2) return [];
  return addrs.map(({ address, family }) =>
    `${u.protocol}//${family === 6 ? `[${address}]` : address}:${u.port}`);
}

/**
 * The cheapest thing that proves a GPU, run before four slow turns prove nothing.
 *
 * /api/ps only ever describes a *loaded* model, so it cannot be read cold — which is why
 * this issues the load itself rather than waiting for the app to. Two independent readings
 * come out of the one request: size_vram/size is the device answer and contains no timing at
 * all, and eval_count/eval_duration is the speed answer. Ollama reports load and prompt
 * evaluation as separate durations, so what is left really is decode.
 */
async function deviceProbe(weightBytes) {
  // Two calls, deliberately.
  //
  // The first forces the lazy load, so /api/ps becomes readable and load_duration is honest.
  // Its own decode is useless as a speed sample and measuring it was a mistake worth
  // recording: "Say OK." answers in about two tokens, and over two tokens the average is
  // almost entirely first-token latency and CUDA graph capture. It read 3 tok/s on a card
  // that sustains 84 — a false CPU verdict on a perfectly healthy GPU.
  //
  // The second runs warm and long enough for that overhead to amortise. num_predict is a
  // ceiling rather than a target, so the prompt has to be one the model will answer at
  // length; a short answer is caught by the token-count guard at the call site instead of
  // being quietly averaged.
  const load = await ollamaPost('/api/generate', {
    model: MODEL,
    prompt: 'Say OK.',
    stream: false,
    // Explicit, so the default five-minute timer cannot evict the model between a slow turn
    // and the next one.
    keep_alive: '15m',
    options: { num_predict: 8, temperature: 0 },
  }, TURN_MS);
  const gen = await ollamaPost('/api/generate', {
    model: MODEL,
    prompt: 'Write one paragraph about the sea.',
    stream: false,
    keep_alive: '15m',
    options: { num_predict: 80, temperature: 0 },
  }, TURN_MS);
  const toks = Number(gen.eval_count || 0);
  const secs = Number(gen.eval_duration || 0) / 1e9;
  const rate = secs > 0 ? toks / secs : 0;
  return {
    toks,
    rate,
    gbps: (rate * weightBytes) / 1e9,
    loadS: Number(load.load_duration || 0) / 1e9,
    residency: await gpuResidency(),
  };
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
  let peer;
  try {
    version = (await ollama('/api/version')).version;
    peer = await peerOf(OLLAMA);
    check('Ollama is reachable', true, `v${version} at ${peer || 'an unnamed socket'}`);
  } catch (err) {
    check('Ollama is reachable', false, `${err.message} — is it running?`);
    return 1;
  }

  const tags = await ollama('/api/tags');
  const mine = fingerprint(version, tags);

  const meanings = await everyMeaning(OLLAMA);
  if (!meanings.length) {
    check('the address names exactly one server', true,
      `${new URL(OLLAMA).hostname} — nothing to resolve`);
  } else {
    const seen = [];
    for (const url of meanings) {
      try {
        const v = await (await fetch(`${url}/api/version`, { signal: AbortSignal.timeout(3000) })).json();
        const t = await (await fetch(`${url}/api/tags`, { signal: AbortSignal.timeout(5000) })).json();
        seen.push({ url, fp: fingerprint(v.version, t) });
      } catch { /* nothing listening on that family, which is the happy case */ }
    }
    const split = seen.length > 1 && !seen.every((x) => sameServer(x.fp, seen[0].fp));
    check('the address names exactly one server', !split,
      split
        ? `${seen.map((x) => `${x.url} is v${x.fp.version} with ${x.fp.names.split(',').length} models`).join(' but ')} — set OLLAMA_URL to whichever you meant`
        : `${seen.map((x) => x.url).join(' and ')} are the same server`);
    if (split) return 1;
  }

  if (!check('the model is pulled', !!mine.digest,
    mine.digest ? `${mine.digest} · ${gb(mine.bytes)}` : (mine.names || 'none'))) {
    console.error(`\n  pull it first:  ollama pull ${MODEL}`);
    return 1;
  }

  // Before Chrome, deliberately. A CPU-only server answers every probe above perfectly and
  // then takes tens of minutes to fail an interview that was never going to mean anything.
  let probe;
  try {
    probe = await deviceProbe(mine.bytes);
  } catch (err) {
    check('the model loads onto the GPU', false, `a short generate failed: ${err.message}`);
    return 1;
  }

  const r0 = probe.residency;
  const onGpu = !!r0 && r0.share > 0.9;
  check('the model loads onto the GPU', onGpu,
    r0 ? `${pct(r0.share)} of ${gb(r0.total)} in VRAM, loaded in ${probe.loadS.toFixed(1)}s`
      : 'nothing in /api/ps after a generate that succeeded — this server reports no GPU at all');

  const floor = (MIN_GBPS * 1e9) / (mine.bytes || 1);
  if (probe.toks < 16) {
    // Below about sixteen tokens the average is mostly first-token latency, which is a
    // property of the sample rather than of the machine. Say so instead of failing: the VRAM
    // claim above is the authoritative one and does not depend on timing at all.
    console.log(`  note  only ${probe.toks} tokens came back, too short to time — ` +
                'skipping the speed claim');
  } else {
    check('it decodes at GPU speed', probe.rate >= floor,
      `${probe.rate.toFixed(0)} tok/s ≈ ${probe.gbps.toFixed(0)} GB/s` +
      (probe.rate >= floor ? ''
        : ` — want ${floor.toFixed(0)} tok/s (${MIN_GBPS} GB/s); that is CPU memory bandwidth`));
  }

  if (!onGpu && !ALLOW_CPU) {
    console.error('\n  This server has no GPU, or CUDA is not reaching it. A four-turn');
    console.error('  interview here takes tens of minutes and tells you nothing you cannot');
    console.error('  learn now. Point OLLAMA_URL at the GPU server, or set');
    console.error('  VALIDATE_ALLOW_CPU=1 to go on anyway.');
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

    if (HANDS_FREE) {
      // Before the app's modules, and outside the page CSP — the same mechanism
      // tools/screenshots.mjs uses for window.claude. Read off disk rather than fetched,
      // so this still works when IDEAFORGE_URL points at a container that serves no /test/.
      const fake = await readFile(join(ROOT, 'test', 'browser', 'fixtures', 'fake-voice.js'), 'utf8');
      await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
        source: `${fake}
;window.__FakeVoice.install(window, ${JSON.stringify({
          script: [...spokenAnswers(TURNS), YES],
          speakMs: 40,
        })});`,
      });
    }

    await cdp.send('Page.navigate', { url: appUrl });
    await app.waitFor(`document.getElementById('version').textContent`, 'the app to boot', BOOT_MS);

    // The browser resolves the base URL for itself. Everything else in this file reads the
    // server through Node, so a name meaning two things gives a run where the harness
    // inspects one Ollama's /api/ps while the page talks to another — and every claim still
    // passes. The digest is the only field the two sides can compare byte for byte. Run
    // before the CSP listener below, so that if this ever does trip the policy it cannot
    // contaminate the claim about what the app itself needed.
    const theirs = await app.eval(`(async () => {
      try { return await (await fetch(${JSON.stringify(`${OLLAMA}/api/tags`)})).json(); }
      catch (e) { return { error: String(e) }; }
    })()`);
    if (theirs && theirs.error) {
      console.log(`  note  the page could not read /api/tags itself (${theirs.error}) — ` +
                  'falling back to the model list for identity');
    } else {
      const t = fingerprint(version, theirs);
      check('the browser reached the same server the harness did',
        !!t.digest && t.digest === mine.digest && t.names === mine.names,
        t.digest === mine.digest ? `${t.digest} on both sides`
          : `harness ${mine.digest || 'absent'} vs browser ${t.digest || 'absent'}`);
    }

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

    // The scripted recogniser IS the browser's, so the browser setting is the one to pick.
    await app.set('stt', HANDS_FREE ? 'browser' : 'off', 'change');
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
    let afterTurn1;
    // The hands-free analogue of the POST count: a claim the app's own bookkeeping cannot
    // fake, because it counts what this harness did rather than what the app reports.
    let keyboardWrites = 0;

    if (HANDS_FREE) {
      await app.waitFor(`!document.getElementById('handsfree-wrap').hidden`,
        'hands-free to be offered', 15000);
      await app.eval(`(() => {
        const el = document.getElementById('handsfree');
        el.checked = true;
        el.dispatchEvent(new Event('change'));
      })()`);
    }

    for (let i = 0; i < TURNS; i++) {
      const before = await app.turn();
      if (HANDS_FREE) {
        // Nothing to do. The loop reads the question, the scripted recogniser answers it,
        // and the next question arrives without this harness touching the page at all.
      } else {
        keyboardWrites += 1;
        await app.set('answer', ANSWERS[i % ANSWERS.length], 'input');
        await app.click('b-send');
      }
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

      if (afterTurn1 === undefined) afterTurn1 = await gpuResidency();
    }

    check('every question after the seed came from the model', banked === 0,
      banked ? `${banked} came from the built-in checklist` : `${TURNS} turns`);

    if (HANDS_FREE) {
      console.log('\ndriven by voice');
      const heard = await app.eval(`window.__FakeVoice.synthesis.spoken.map((u) => u.text)`);
      const sessions = await app.eval(`window.__FakeVoice.recognition.startCount`);
      const stored = await app.session();
      const answered = (stored ? stored.turns : []).filter((t) => t.answer);

      check('every question was read out loud',
        answered.every((t) => heard.some((u) => u.includes(t.question))),
        `${heard.length} utterances for ${answered.length} answers`);
      check('every answer was captured by voice, not typed',
        answered.length > 0 && answered.every((t) => t.answerSource === 'voice'),
        [...new Set(answered.map((t) => t.answerSource))].join(', '));
      // Compared as a word rather than matched with a regex. The first version built the
      // pattern in a template literal, where \b is a backspace character and not a word
      // boundary, so it could never match — and a run with a deliberately wrong trigger
      // passed this claim while every answer still ended in the trigger word.
      //
      // It is also the only claim here that a broken trigger would fail. The deaf watchdog
      // rescues the capture either way, so the answer still arrives, just slower and with
      // the word left on the end of it.
      const lastWord = (t) => (String(t).toLowerCase().match(/[a-z0-9']+/g) || []).pop() || '';
      check('the trigger word ended the answer rather than joining it',
        !answered.some((t) => lastWord(t.answer) === TRIGGER.toLowerCase()),
        JSON.stringify(answered.map((t) => t.answer.slice(-24))));
      check('the microphone was opened for every answer',
        sessions > answered.length, `${sessions} sessions for ${answered.length} answers`);
    }

    // ── the wrap-up ─────────────────────────────────────────────────
    console.log('\nthe wrap-up');
    if (HANDS_FREE) {
      // Said, not clicked. The loop is still listening, so the next scripted utterance is
      // "wrap it up" arriving as an answer — which is exactly how a driver ends an
      // interview.
      // "wrap it up" below the four-turn floor is refused, which is the point of the floor;
      // TURNS is 4 by default, so by here it is honoured. YES follows in case the engine
      // offers the wrap-up itself first and the command lands on the confirm instead.
      await app.eval(`window.__FakeVoice.script(${JSON.stringify([
        [{ at: 120, interim: 'wrap it up' }, { at: 300, final: 'wrap it up' }],
        YES,
      ])})`);
    } else {
      keyboardWrites += 1;
      await app.waitFor(`!document.getElementById('b-wrap').hidden`, 'the wrap-up button', 10000);
      await app.click('b-wrap');
    }
    await app.waitFor(
      `document.getElementById('done-title').textContent !== 'Writing it up…'`,
      'the synthesis', TURN_MS,
    );
    await app.waitFor(
      `document.getElementById('output').textContent.includes('Forged with IdeaForge')`,
      'the export', 30000,
    );

    // Not just after turn 1. The synthesis sends the whole transcript, which is where the KV
    // cache is largest and where Ollama will resize the context and reload — and if anything
    // else has taken the card meanwhile, that reload lands partly on the CPU and the run
    // merely gets slow. One sample at the start cannot see it.
    const end = await gpuResidency();
    const trail = `${afterTurn1 ? pct(afterTurn1.share) : 'not loaded'} after turn 1 → ` +
                  `${end ? pct(end.share) : 'not loaded'} after the wrap-up`;
    if (!end) {
      // Unloaded is not the same event as moved-to-CPU: an eviction-and-reload leaves a
      // present entry with a low share, which fails below. A plain keep_alive expiry leaves
      // nothing, and failing on that would redden a run for something innocent.
      console.log(`  note  nothing in /api/ps after the wrap-up — unloaded, not moved to the CPU (${trail})`);
    } else {
      check('the model stayed in VRAM for the whole run', end.share > 0.9,
        `${trail}, ${gb(end.total)}`);
    }

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

    // The hands-free counterpart of the POST count, and the same kind of claim: it counts
    // what this harness did rather than what the app says happened. Asserted from a counter
    // rather than from a comment, because a future edit that quietly types one answer would
    // leave a comment saying "no keyboard" perfectly intact.
    if (HANDS_FREE) {
      check('the harness never touched the keyboard', keyboardWrites === 0,
        `${keyboardWrites} writes to #answer or clicks on #b-send`);
    }

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
