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
import { ROOT, MIME, findChrome, serveRepo, launchChrome } from './lib/harness.mjs';
import { assembleSite } from './assemble-site.mjs';

const PROBE_DIR = join(ROOT, 'test', 'browser');
/**
 * Generous, because several probes are deliberately waiting out a real deadline rather than
 * a simulated one — a behavioural liveness probe, a recording, and speak.js's watchdog cap,
 * which scales with word count and is the longest single wait here. The suite ran at 90s
 * against a 90s budget on the machine this was raised on, which is not a margin.
 */
const TIMEOUT_MS = Number(process.env.BROWSER_CHECK_TIMEOUT || 180000);
const REQUIRED = process.argv.includes('--required') || Boolean(process.env.BROWSER_CHECK_REQUIRED);
/** The app is also served here, so probes can verify it works on a GitHub Pages subpath. */
const SUBPATH = '/subpath-check/';

async function probeNames() {
  const files = await readdir(PROBE_DIR).catch(() => []);
  return files.filter((f) => f.endsWith('.browser.mjs')).sort();
}

/** The page that runs every probe and posts the results back. */
function runnerHtml(probes) {
  const list = JSON.stringify(probes);
  return [
    '<!doctype html><meta charset="utf-8"><title>browser checks</title>',
    '<body><pre id="log">running</pre>',
    '<script type="module">',
    'const results = [];',
    // A CSP violation never throws; it only fires this event. Without listening, a broken
    // policy looks exactly like a passing run.
    'addEventListener("securitypolicyviolation", (e) => results.push(',
    '  { probe: "page", name: "CSP violation", ok: false,',
    '    detail: e.violatedDirective + " blocked " + e.blockedURI }));',
    'addEventListener("error", (e) => results.push(',
    '  { probe: "page", name: "uncaught error", ok: false, detail: String(e.message || e) }));',
    '',
    `for (const name of ${list}) {`,
    '  const check = (label, ok, detail = "") =>',
    '    results.push({ probe: name, name: label, ok: !!ok, detail: String(detail) });',
    '  try {',
    '    const mod = await import("/test/browser/" + name);',
    `    await mod.default(check, { subpath: ${JSON.stringify(SUBPATH)} });`,
    '  } catch (err) {',
    '    check("probe threw", false, (err && err.stack) || String(err));',
    '  }',
    '  document.getElementById("log").textContent = results.length + " checks";',
    '}',
    'await fetch("/__result", { method: "POST",',
    '  headers: { "content-type": "application/json" },',
    '  body: JSON.stringify({ done: true, results }) });',
    '</script>',
  ].join('\n');
}

async function serve(probes, siteRoot, onResults) {
  return serveRepo({
    root: siteRoot,
    async before(req, res, url) {
      let path = decodeURIComponent(url.pathname);

      if (req.method === 'POST' && path === '/__result') {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        res.writeHead(204).end();
        onResults(JSON.parse(Buffer.concat(chunks).toString('utf8')));
        return { handled: true };
      }

      if (path.startsWith('/test/browser/')) return { path, root: ROOT };

      const onSubpath = path.startsWith(SUBPATH);
      if (onSubpath) path = path.slice(SUBPATH.length - 1);
      if (path === '/__run.html') {
        const only = url.searchParams.get('probe');
        const selected = only ? probes.filter((name) => name === only) : probes;
        res.writeHead(200, { 'content-type': MIME['.html'] }).end(runnerHtml(selected));
        return { handled: true };
      }
      return { path, swScope: onSubpath ? SUBPATH : '/' };
    },
  });
}

async function main() {
  const probes = await probeNames();
  if (!probes.length) {
    console.log('no browser probes found - nothing to do');
    return 0;
  }

  const chrome = findChrome();
  if (!chrome) {
    // Locally this is a skip, so a contributor without Chrome can still run everything
    // else. In CI it is a failure: a check that silently skips is a false green, which is
    // worse than having no check at all.
    if (REQUIRED) {
      console.error('no Chrome found, and browser checks are required');
      return 1;
    }
    console.log('SKIP browser checks - no Chrome found. Set CHROME_PATH to run them.');
    return 0;
  }

  let server = null;
  let siteDir = null;
  let postResults = null;
  /** @type {Array<{probe: string, name: string, ok: boolean, detail: string}>} */
  const allResults = [];

  try {
    siteDir = await mkdtemp(join(tmpdir(), 'ideaforge-browser-site-'));
    await assembleSite({ outDir: siteDir, clean: false });
    server = await serve(probes, siteDir, (posted) => postResults?.(posted));
    const { port } = server.address();

    for (const probe of probes) {
      let resolveResults;
      const results = new Promise((r) => { resolveResults = r; });
      let browser = null;
      postResults = resolveResults;

      try {
        browser = launchChrome(chrome, {
          url: 'http://127.0.0.1:' + port + '/__run.html?probe=' + encodeURIComponent(probe),
          // Grant and synthesise a microphone so the recorder and the silence gate run for real.
          extraArgs: [
            '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
            '--autoplay-policy=no-user-gesture-required',
          ],
        });

        const payload = await Promise.race([
          results,
          new Promise((r) => setTimeout(() => r(null), TIMEOUT_MS)),
        ]);

        if (!payload) {
          console.error('browser checks TIMED OUT after ' + TIMEOUT_MS + 'ms while running ' + probe);
          return 1;
        }
        allResults.push(...payload.results);
      } finally {
        postResults = null;
        browser?.kill();
      }
    }
  } finally {
    await new Promise((resolve) => {
      if (!server) return resolve();
      server.close(() => resolve());
    });
    if (siteDir) await rm(siteDir, { recursive: true, force: true });
  }

  let failed = 0;
  let lastProbe = '';
  for (const r of allResults) {
    if (r.probe !== lastProbe) { console.log('\n' + r.probe); lastProbe = r.probe; }
    if (r.ok) console.log('  ok    ' + r.name + (r.detail ? '  (' + r.detail + ')' : ''));
    else { failed++; console.log('  FAIL  ' + r.name + '  ' + r.detail); }
  }
  console.log('\n' + allResults.length + ' checks, ' + failed + ' failed');
  return failed ? 1 : 0;
}

process.exitCode = await main();
