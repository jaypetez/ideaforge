// The parts of IdeaForge that Node cannot test.
//
// IndexedDB, WebCrypto non-extractable keys, MediaRecorder, the Web Speech API, service
// workers and the Content-Security-Policy only exist in a browser. `npm test` cannot see
// any of them, so this harness serves the repo, runs every probe under test/browser/ in
// headless Chrome, and collects the results.
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

import { createServer } from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { join, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const PROBE_DIR = join(ROOT, 'test', 'browser');
const TIMEOUT_MS = Number(process.env.BROWSER_CHECK_TIMEOUT || 90000);
/** The app is also served here, so probes can verify it works on a GitHub Pages subpath. */
const SUBPATH = '/subpath-check/';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const candidates = {
    win32: [
      'C:/Program Files/Google/Chrome/Application/chrome.exe',
      'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
      'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    ],
    darwin: [
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
    ],
    linux: [
      '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
      '/usr/bin/chromium-browser', '/usr/bin/chromium', '/snap/bin/chromium',
    ],
  }[process.platform] || [];
  return candidates.find((p) => existsSync(p)) || null;
}

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

async function serve(probes) {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let path = decodeURIComponent(url.pathname);

    if (req.method === 'POST' && path === '/__result') {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      res.writeHead(204).end();
      server.emit('results', JSON.parse(Buffer.concat(chunks).toString('utf8')));
      return;
    }

    const onSubpath = path.startsWith(SUBPATH);
    if (onSubpath) path = path.slice(SUBPATH.length - 1);
    if (path === '/__run.html') {
      res.writeHead(200, { 'content-type': MIME['.html'] }).end(runnerHtml(probes));
      return;
    }
    if (path === '/' || path === '') path = '/index.html';

    // Everything resolves under ROOT; anything that escapes it is refused.
    const file = join(ROOT, path);
    if (!file.startsWith(ROOT)) {
      res.writeHead(403).end('forbidden');
      return;
    }
    try {
      const body = await readFile(file);
      res.writeHead(200, {
        'content-type': MIME[extname(file)] || 'application/octet-stream',
        'service-worker-allowed': onSubpath ? SUBPATH : '/',
      }).end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return server;
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
    if (process.env.BROWSER_CHECK_REQUIRED) {
      console.error('no Chrome found, and BROWSER_CHECK_REQUIRED is set');
      return 1;
    }
    console.log('SKIP browser checks - no Chrome found. Set CHROME_PATH to run them.');
    return 0;
  }

  const server = await serve(probes);
  const { port } = server.address();
  const profile = mkdtempSync(join(tmpdir(), 'ideaforge-chrome-'));

  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--user-data-dir=' + profile,
    // Grant and synthesise a microphone so the recorder and the silence gate run for real.
    '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
    '--autoplay-policy=no-user-gesture-required',
    'http://127.0.0.1:' + port + '/__run.html',
  ];
  if (process.platform === 'linux') args.unshift('--no-sandbox');

  const child = spawn(chrome, args, { stdio: 'ignore' });
  const payload = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), TIMEOUT_MS);
    server.once('results', (r) => { clearTimeout(timer); resolve(r); });
  });

  child.kill();
  server.close();
  // Best-effort. On Windows Chrome keeps a handle on CrashpadMetrics for a moment after
  // being killed, and a leftover temp profile must never be the thing that fails a run.
  try {
    rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  } catch { /* the OS will reap it */ }

  if (!payload) {
    console.error('browser checks TIMED OUT after ' + TIMEOUT_MS + 'ms - nothing was posted');
    return 1;
  }

  let failed = 0;
  let lastProbe = '';
  for (const r of payload.results) {
    if (r.probe !== lastProbe) { console.log('\n' + r.probe); lastProbe = r.probe; }
    if (r.ok) console.log('  ok    ' + r.name + (r.detail ? '  (' + r.detail + ')' : ''));
    else { failed++; console.log('  FAIL  ' + r.name + '  ' + r.detail); }
  }
  console.log('\n' + payload.results.length + ' checks, ' + failed + ' failed');
  return failed ? 1 : 0;
}

process.exitCode = await main();
