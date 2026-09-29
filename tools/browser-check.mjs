// The parts of IdeaForge that Node cannot test.
//
// IndexedDB, WebCrypto non-extractable keys, MediaRecorder, the Web Speech API, service
// workers and the Content-Security-Policy only exist in a browser. `npm test` cannot see
// any of them, so this harness serves the assembled site tree, keeps the probes on the repo
// tree, runs every probe under test/browser/ in headless Chrome, and collects the results.
//
// Two deliberate choices, both learned the hard way:
//
//   Results come back over HTTP, not by scraping the DOM. Chrome's --dump-dom snapshots
//   the page before async work finishes, so a probe that awaits IndexedDB reports nothing
//   and looks like a failure.
//
//   No --virtual-time-budget. It fast-forwards timers while real IndexedDB and media I/O
//   keep taking real time, which silently truncates probes and reports empty results as
//   though the code were broken.
//
// Chrome discovery, the static server and the temp profile are shared with
// tools/screenshots.mjs and live in tools/lib/harness.mjs.

import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { ROOT, MIME, findChrome, serveRepo, launchChrome } from './lib/harness.mjs';
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

/** Each page owns one probe; assertions are posted while it runs, not only after it returns. */
function runnerHtml(probe) {
  return [
    '<!doctype html><meta charset="utf-8"><title>browser checks</title>',
    '<body><pre id="log">running</pre>',
    '<script type="module">',
    `const probe = ${JSON.stringify(probe)};`,
    'const results = [];',
    'let reports = Promise.resolve();',
    'let reportError = null;',
    'function report(done = false) {',
    '  const body = JSON.stringify({ probe, done, results });',
    '  reports = reports.then(async () => {',
    '    const response = await fetch("/__result", { method: "POST",',
    '      headers: { "content-type": "application/json" }, body,',
    '      signal: AbortSignal.timeout(10000) });',
    '    if (!response.ok) throw new Error("result reporting returned HTTP " + response.status);',
    '  }).catch((error) => { reportError = String(error.stack || error); });',
    '  return reports;',
    '}',
    'function record(result) {',
    '  results.push(result);',
    '  document.getElementById("log").textContent = results.length + " checks: " + result.name;',
    '  report();',
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
    'await reports;',
    'if (reportError) check("progress reporting failed", false, reportError);',
    'await report(true);',
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
      // A late POST from a browser already torn down belongs to neither the next probe nor
      // its watchdog. The server rejects it rather than counting it as progress.
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

async function serve(probes, siteRoot, onResults, onError) {
  return serveRepo({
    root: siteRoot,
    async before(req, res, url) {
      let path = decodeURIComponent(url.pathname);

      if (req.method === 'POST' && path === '/__result') {
        try {
          const chunks = [];
          for await (const c of req) chunks.push(c);
          const accepted = onResults(JSON.parse(Buffer.concat(chunks).toString('utf8')));
          res.writeHead(accepted ? 204 : 409).end();
        } catch (error) {
          onError(new Error(`could not receive browser results: ${error.message}`, { cause: error }));
          res.writeHead(400).end('invalid browser results');
        }
        return { handled: true };
      }

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
      + 'BROWSER_CHECK_TIMEOUT sets the per-probe no-progress budget (default 180000ms).\n'
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
      if (r.probe !== lastProbe) { console.log('\n' + r.probe); lastProbe = r.probe; }
      if (r.ok) console.log('  ok    ' + r.name + (r.detail ? '  (' + r.detail + ')' : ''));
      else console.log('  FAIL  ' + r.name + '  ' + r.detail);
    }
  };
  console.log(`browser checks: ${probes.length} probe(s), ${TIMEOUT_MS}ms no-progress watchdog, `
    + `${overallMs}ms overall budget; audio policy required`);

  try {
    siteDir = await mkdtemp(join(tmpdir(), 'ideaforge-browser-site-'));
    await assembleSite({ outDir: siteDir, clean: false });
    server = await serve(probes, siteDir,
      (posted) => monitor?.accept(posted) || false, (error) => monitor?.fail(error));
    const { port } = server.address();

    // Separate fresh profiles: the synthetic-media suite's autoplay bypass cannot prove
    // first-gesture output policy, and an active microphone can itself permit playback.
    const policyResults = await runBrowserPolicyChecks({ chrome, siteRoot: siteDir });
    record(policyResults);
    const policyFailures = policyResults.filter((result) => !result.ok).length;
    console.log(`audio policy phase: ${policyResults.length} checks, ${policyFailures} failed (separate fresh profiles)`);

    for (const probe of probes) {
      let browser = null;
      let outcome;
      monitor = monitorProbe(probe, { timeoutMs: TIMEOUT_MS, overallDeadline, onResults: record });
      console.log(`\nstarting ${probe} (${completed + 1}/${probes.length})`);

      try {
        browser = launchChrome(chrome, {
          url: 'http://127.0.0.1:' + port + '/__run.html?probe=' + encodeURIComponent(probe),
          // Grant and synthesise a microphone so the recorder and the silence gate run for real.
          extraArgs: [
            '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
            '--autoplay-policy=no-user-gesture-required',
          ],
        });

        const active = monitor;
        browser.child.once('error', (error) => active.fail(error));
        browser.child.once('exit', (code, signal) =>
          active.fail(new Error(`Chrome exited before ${probe} completed (${signal || code})`)));
        outcome = await monitor.promise;
      } catch (error) {
        monitor.fail(error);
        outcome = await monitor.promise;
      } finally {
        monitor = null;
        browser?.kill();
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
