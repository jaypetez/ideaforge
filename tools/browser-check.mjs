// The parts of IdeaForge that Node cannot test.
//
// IndexedDB, WebCrypto non-extractable keys, MediaRecorder, the Web Speech API, service
// workers and the Content-Security-Policy only exist in a browser. `npm test` cannot see
// any of them, so this harness serves the assembled site tree, keeps the probes on the repo
// tree, runs every probe under test/browser/ in headless Chrome, and collects the results.
//
// Two deliberate choices, both learned the hard way:
//
//   Results use a browser-protocol binding, outside the page's fetch/service-worker
//   lifecycle. A worker stopped by an app probe can cancel the runner's own HTTP reports.
//   Chrome's --dump-dom also snapshots before async work finishes, so neither is a safe
//   reporting channel for probes that await browser APIs.
//
//   No --virtual-time-budget. It fast-forwards timers while real IndexedDB and media I/O
//   keep taking real time, which silently truncates probes and reports empty results as
//   though the code were broken.
//
// Chrome discovery, the static server and the temp profile are shared with
// tools/screenshots.mjs and live in tools/lib/harness.mjs.

import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { once } from 'node:events';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import {
  ROOT, MIME, findChrome, serveRepo, launchChrome, connectChrome, observeChrome,
} from './lib/harness.mjs';
import { assembleSite } from './assemble-site.mjs';
import { runBrowserPolicyChecks } from './browser-policy-check.mjs';

const PROBE_DIR = join(ROOT, 'test', 'browser');
/**
 * Generous, because several probes are deliberately waiting out a real deadline rather than
 * a simulated one. This is a no-progress budget per probe, not a limit on the whole suite.
 * Only newly received assertions reset it; repeated reports cannot keep a stuck probe alive.
 */
const TIMEOUT_MS = Number(process.env.BROWSER_CHECK_TIMEOUT || 180000);
const REQUIRED = process.argv.includes('--required') || Boolean(process.env.BROWSER_CHECK_REQUIRED);
/** The app is also served here, so probes can verify it works on a GitHub Pages subpath. */
const SUBPATH = '/subpath-check/';
export const REPORT_BINDING = '__ideaforgeReport';

export function parseOptions(args) {
  let probe = null;
  let help = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--required') continue;
    if (args[i] === '--help') { help = true; continue; }
    if (args[i] !== '--probe') throw new Error(`unknown browser-check option: ${args[i]}`);
    if (probe !== null) throw new Error('--probe may only be specified once');
    if (!args[i + 1] || args[i + 1].startsWith('--')) {
      throw new Error('--probe requires an exact name.browser.mjs');
    }
    probe = args[++i];
  }
  return { probe, help };
}

export function selectProbes(probes, name) {
  if (name === null) return probes;
  if (!probes.includes(name)) {
    throw new Error(`unknown browser probe: ${name}\navailable: ${probes.join(', ') || '(none)'}`);
  }
  return [name];
}

async function probeNames() {
  const files = await readdir(PROBE_DIR).catch((err) => {
    if (REQUIRED) {
      throw new Error(`cannot read browser probe directory: ${err.message}`, { cause: err });
    }
    return [];
  });
  return files.filter((f) => f.endsWith('.browser.mjs')).sort();
}

/** Serialized into the runner; the emitter is captured before a probe can replace globals. */
export function createReporter(probe, emit) {
  const results = [];
  let failed = false;
  let failure;
  function send(done) {
    if (failed) throw failure;
    try {
      emit(JSON.stringify({ probe, done, results }));
    } catch (error) {
      failed = true;
      failure = error;
      throw error;
    }
  }
  return {
    results,
    record(result) { results.push(result); send(false); },
    complete() { send(true); },
  };
}

/** Receive ordered reports independently of page networking; a broken channel is fatal. */
export async function attachReporter(cdp, monitor) {
  const closed = () => monitor.fail(new Error('browser report channel closed before completion'));
  cdp.ws.addEventListener('close', closed);
  const unsubscribe = cdp.on('Runtime.bindingCalled', ({ name, payload }) => {
    if (name !== REPORT_BINDING) return;
    try {
      if (!monitor.accept(JSON.parse(payload))) {
        monitor.fail(new Error('browser report channel received an invalid or unexpected report'));
      }
    } catch (error) {
      monitor.fail(new Error(`invalid browser report: ${error.message}`, { cause: error }));
    }
  });
  const dispose = () => {
    unsubscribe();
    cdp.ws.removeEventListener('close', closed);
  };
  try {
    await cdp.send('Runtime.enable');
    await cdp.send('Runtime.addBinding', { name: REPORT_BINDING });
    return dispose;
  } catch (error) {
    dispose();
    throw error;
  }
}

/** Each page owns one probe; assertions are emitted while it runs, not only at the end. */
export function runnerHtml(probe) {
  return [
    '<!doctype html><meta charset="utf-8"><title>browser checks</title>',
    '<body><pre id="log">running</pre>',
    '<script type="module">',
    `const probe = ${JSON.stringify(probe)};`,
    `const reporter = (${createReporter.toString()})(probe, globalThis.${REPORT_BINDING}.bind(globalThis));`,
    'function record(result) {',
    '  reporter.record(result);',
    '  document.getElementById("log").textContent = reporter.results.length + " checks: " + result.name;',
    '}',
    // A CSP violation never throws; it only fires this event. Without listening, a broken
    // policy looks exactly like a passing run.
    'addEventListener("securitypolicyviolation", (e) => record(',
    '  { probe: "page", name: "CSP violation", ok: false,',
    '    detail: e.violatedDirective + " blocked " + e.blockedURI }));',
    'addEventListener("error", (e) => record(',
    '  { probe: "page", name: "uncaught error", ok: false, detail: String(e.message || e) }));',
    'addEventListener("unhandledrejection", (e) => record(',
    '  { probe: "page", name: "unhandled rejection", ok: false,',
    '    detail: String(e.reason?.stack || e.reason) }));',
    '',
    'const check = (label, ok, detail = "") =>',
    '  record({ probe, name: label, ok: !!ok, detail: String(detail) });',
    'try {',
    '  const mod = await import("/test/browser/" + probe);',
    `  await mod.default(check, { subpath: ${JSON.stringify(SUBPATH)} });`,
    '} catch (err) {',
    '  check("probe threw", false, (err && err.stack) || String(err));',
    '}',
    'reporter.complete();',
    '</script>',
  ].join('\n');
}

/** Two independent deadlines: useful progress extends only the idle deadline, never the run. */
export function monitorProbe(probe, { timeoutMs, overallDeadline, onResults = () => {} }) {
  const started = Date.now();
  let lastProgress = started;
  let results = [];
  let finished = false;
  let idleTimer;
  let overallTimer;
  let resolve;
  const promise = new Promise((r) => { resolve = r; });

  function finish(error = null) {
    if (finished) return;
    finished = true;
    clearTimeout(idleTimer);
    clearTimeout(overallTimer);
    resolve({ error, results, elapsed: Date.now() - started });
  }

  function timedOut(reason) {
    finish(new Error(`TIMED OUT: ${reason}; ${Date.now() - started}ms in ${probe}; `
      + `${results.length} checks received; last: ${results.at(-1)?.name || '(none)'}`));
  }

  function armIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() =>
      timedOut(`no new assertion for ${Date.now() - lastProgress}ms (budget ${timeoutMs}ms)`), timeoutMs);
  }
  armIdle();
  overallTimer = setTimeout(() => timedOut('overall browser-run deadline reached'),
    Math.max(0, overallDeadline - started));

  return {
    promise,
    fail: finish,
    accept(payload) {
      // A late report belongs to neither the next probe nor its watchdog.
      if (finished || payload?.probe !== probe) return false;
      if (typeof payload.done !== 'boolean' || !Array.isArray(payload.results)
          || payload.results.length < results.length
          || payload.results.some((r) => !r || ![probe, 'page'].includes(r.probe)
            || typeof r.name !== 'string' || typeof r.ok !== 'boolean' || typeof r.detail !== 'string')
          || results.some((r, i) => ['probe', 'name', 'ok', 'detail']
            .some((key) => r[key] !== payload.results[i][key]))) {
        finish(new Error(`invalid or rewritten result payload for ${probe}`));
        return false;
      }
      const added = payload.results.slice(results.length);
      results = payload.results;
      if (added.length) {
        lastProgress = Date.now();
        armIdle();
        onResults(added);
      }
      if (payload.done) {
        finish(results.some((r) => r.probe === probe)
          ? null : new Error(`${probe} completed without any probe assertions`));
      }
      return true;
    },
  };
}

async function serve(probes, siteRoot, onResponse) {
  return serveRepo({
    root: siteRoot,
    onResponse,
    async before(req, res, url) {
      let path = decodeURIComponent(url.pathname);

      if (path.startsWith('/test/browser/')) return { path, root: ROOT };

      const onSubpath = path.startsWith(SUBPATH);
      if (onSubpath) path = path.slice(SUBPATH.length - 1);
      if (path === '/__run.html') {
        const only = url.searchParams.get('probe');
        if (!probes.includes(only)) {
          res.writeHead(404).end('an exact known browser probe is required');
          return { handled: true };
        }
        res.writeHead(200, { 'content-type': MIME['.html'] }).end(runnerHtml(only));
        return { handled: true };
      }
      return { path, swScope: onSubpath ? SUBPATH : '/' };
    },
  });
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  if (options.help) {
    console.log('Usage: node tools\\browser-check.mjs [--required] [--probe <name.browser.mjs>]\n'
      + 'Omit --probe to run every browser probe. Names must match exactly; unknown names fail.\n'
      + 'Every run with Chrome includes the independent audio-policy checks, including focused runs.\n'
      + 'Results use CDP rather than the page fetch/service-worker path.\n'
      + 'BROWSER_CHECK_TIMEOUT sets the per-probe no-progress budget (default 180000ms).\n'
      + 'BROWSER_CHECK_TRACE writes pre-document CDP and server diagnostics to a new JSONL file.\n'
      + 'The overall budget is 180000ms for policy/startup plus that budget per selected probe.');
    return 0;
  }
  const probes = selectProbes(await probeNames(), options.probe);
  if (!probes.length) {
    if (REQUIRED) {
      console.error('no browser probes found, and browser checks are required');
      return 1;
    }
    console.log('no browser probes found - nothing to do');
    return 0;
  }

  const chrome = findChrome();
  if (!chrome) {
    // Locally this is a skip, so a contributor without Chrome can still run everything
    // else. In CI it is a failure: a check that silently skips is a false green, which is
    // worse than having no check at all.
    if (REQUIRED || options.probe) {
      console.error('no Chrome found, and browser checks are required');
      return 1;
    }
    console.log('SKIP browser checks - no Chrome found. Set CHROME_PATH to run them.');
    return 0;
  }

  let server = null;
  let siteDir = null;
  let monitor = null;
  let completed = 0;
  let lastProbe = '';
  let traceStream = null;
  let traceError = null;
  let activeProbe = 'setup';
  const issues = [];
  const trace = (kind, data) => {
    const entry = { at: Date.now(), probe: activeProbe, kind, ...data };
    const acknowledgedReport = kind === 'resource-failed' && data.status === 204
      && data.method === 'POST' && data.url?.endsWith('/__result')
      && data.canceled && data.error === 'net::ERR_ABORTED';
    if (kind === 'exception' || (kind === 'resource-failed' && !acknowledgedReport) || kind === 'worker-error'
        || (kind === 'log' && data.level === 'error')) {
      if (issues.length === 100) issues.shift();
      issues.push(entry);
    }
    traceStream?.write(JSON.stringify(entry) + '\n');
  };
  /** @type {Array<{probe: string, name: string, ok: boolean, detail: string}>} */
  const allResults = [];
  const overallMs = 180000 + TIMEOUT_MS * probes.length;
  if (!Number.isSafeInteger(TIMEOUT_MS) || TIMEOUT_MS <= 0 || overallMs > 2147483647) {
    throw new Error('BROWSER_CHECK_TIMEOUT must be a positive integer within the finite run timer budget');
  }
  const overallDeadline = Date.now() + overallMs;
  const record = (results) => {
    allResults.push(...results);
    for (const r of results) {
      trace('assertion', { name: r.name, ok: r.ok, detail: r.ok ? '' : r.detail });
      if (r.probe !== lastProbe) { console.log('\n' + r.probe); lastProbe = r.probe; }
      if (r.ok) console.log('  ok    ' + r.name + (r.detail ? '  (' + r.detail + ')' : ''));
      else console.log('  FAIL  ' + r.name + '  ' + r.detail);
    }
  };
  console.log(`browser checks: ${probes.length} probe(s), ${TIMEOUT_MS}ms no-progress watchdog, `
    + `${overallMs}ms overall budget; audio policy required`);

  try {
    if (process.env.BROWSER_CHECK_TRACE) {
      traceStream = createWriteStream(process.env.BROWSER_CHECK_TRACE, { flags: 'wx' });
      traceStream.on('error', (error) => { traceError = error; monitor?.fail(error); });
      await once(traceStream, 'open');
      console.log('browser diagnostic trace: ' + process.env.BROWSER_CHECK_TRACE);
    }
    siteDir = await mkdtemp(join(tmpdir(), 'ideaforge-browser-site-'));
    await assembleSite({ outDir: siteDir, clean: false });
    server = await serve(probes, siteDir, traceStream ? (event) => trace('server', event) : undefined);
    const { port } = server.address();

    // Separate fresh profiles: the synthetic-media suite's autoplay bypass cannot prove
    // first-gesture output policy, and an active microphone can itself permit playback.
    const policyResults = await runBrowserPolicyChecks({ chrome, siteRoot: siteDir });
    record(policyResults);
    const policyFailures = policyResults.filter((result) => !result.ok).length;
    console.log(`audio policy phase: ${policyResults.length} checks, ${policyFailures} failed (separate fresh profiles)`);

    for (const probe of probes) {
      let browser = null;
      let cdp = null;
      let detachReporter = null;
      let outcome;
      activeProbe = probe;
      issues.length = 0;
      monitor = monitorProbe(probe, { timeoutMs: TIMEOUT_MS, overallDeadline, onResults: record });
      console.log(`\nstarting ${probe} (${completed + 1}/${probes.length})`);

      try {
        const url = 'http://127.0.0.1:' + port + '/__run.html?probe=' + encodeURIComponent(probe);
        browser = launchChrome(chrome, {
          url: 'about:blank',
          // Grant and synthesise a microphone so the recorder and the silence gate run for real.
          extraArgs: [
            '--remote-debugging-port=0',
            '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
            '--autoplay-policy=no-user-gesture-required',
          ],
        });

        const active = monitor;
        browser.child.once('error', (error) => active.fail(error));
        browser.child.once('exit', (code, signal) =>
          active.fail(new Error(`Chrome exited before ${probe} completed (${signal || code})`)));
        cdp = await connectChrome(browser);
        detachReporter = await attachReporter(cdp, active);
        await cdp.send('Page.enable');
        if (traceStream) await observeChrome(cdp, trace);
        trace('launch', { profile: browser.profile, pid: browser.child.pid });
        await cdp.send('Page.bringToFront');
        await cdp.send('Page.navigate', { url });
        outcome = await monitor.promise;
      } catch (error) {
        monitor.fail(error);
        outcome = await monitor.promise;
      } finally {
        detachReporter?.();
        cdp?.close();
        monitor = null;
        browser?.kill();
      }
      if (traceStream && (outcome.error || outcome.results.some((r) => !r.ok))) {
        console.log(`pre-document diagnostics for ${probe}: ${issues.length} issue(s)`);
        for (const issue of issues) console.log('  DIAG  ' + JSON.stringify(issue));
      }
      if (outcome.error) {
        record([{ probe, name: 'probe did not complete', ok: false,
          detail: outcome.error.stack || String(outcome.error) }]);
        break;
      }
      completed++;
      console.log(`completed ${probe}: ${outcome.results.length} checks, `
        + `${outcome.results.filter((r) => !r.ok).length} failed, ${outcome.elapsed}ms`);
    }
  } catch (error) {
    record([{ probe: 'harness', name: 'browser harness failed', ok: false,
      detail: error.stack || String(error) }]);
  } finally {
    await new Promise((resolve) => {
      if (!server) return resolve();
      server.close(() => resolve());
      server.closeAllConnections();
    });
    if (siteDir) await rm(siteDir, { recursive: true, force: true });
    const stream = traceStream;
    traceStream = null;
    if (stream && !stream.destroyed) {
      stream.end();
      await once(stream, 'finish');
    }
    if (traceError) record([{ probe: 'harness', name: 'diagnostic trace failed', ok: false,
      detail: traceError.message }]);
  }

  const failed = allResults.filter((r) => !r.ok).length;
  console.log(`\n${allResults.length} checks, ${failed} failed; ${completed}/${probes.length} probes completed`);
  if (completed < probes.length) {
    console.error('incomplete browser probes: ' + probes.slice(completed).join(', '));
  }
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
