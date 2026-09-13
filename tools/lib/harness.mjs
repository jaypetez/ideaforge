// Shared plumbing for the two headless-Chrome harnesses in this directory.
//
// tools/browser-check.mjs runs the probes Node cannot; tools/screenshots.mjs drives the real
// app and photographs it. Both need the same three things — a Chrome, a real HTTP origin to
// serve the repo from, and a temp profile that gets cleaned up — so they live here once
// rather than being copied and then quietly diverging.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { join, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('../..', import.meta.url));

export const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};

export function findChrome() {
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

/**
 * Serve the repo over a real origin on an ephemeral port.
 *
 * `before(req, res, url)` runs first and may either handle the request itself (return
 * `{ handled: true }`) or rewrite what gets served (`{ path, swScope }`). Everything else
 * falls through to static file serving rooted at ROOT, and anything escaping ROOT is refused.
 *
 * @returns {Promise<import('node:http').Server>} already listening; `.address().port` is live.
 */
export async function serveRepo({ root = ROOT, before } = {}) {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let path = decodeURIComponent(url.pathname);
    let swScope = '/';

    if (before) {
      const out = await before(req, res, url);
      if (out && out.handled) return;
      if (out && out.path) path = out.path;
      if (out && out.swScope) swScope = out.swScope;
    }
    if (path === '/' || path === '') path = '/index.html';

    const file = join(root, path);
    if (!file.startsWith(root)) {
      res.writeHead(403).end('forbidden');
      return;
    }
    try {
      const body = await readFile(file);
      res.writeHead(200, {
        'content-type': MIME[extname(file)] || 'application/octet-stream',
        'service-worker-allowed': swScope,
      }).end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return server;
}

/**
 * Launch headless Chrome against a throwaway profile.
 *
 * @returns {{child: import('node:child_process').ChildProcess, profile: string, kill: () => void}}
 */
export function launchChrome(chrome, { url, extraArgs = [] }) {
  const profile = mkdtempSync(join(tmpdir(), 'ideaforge-chrome-'));
  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--user-data-dir=' + profile,
    ...extraArgs,
  ];
  if (process.platform === 'linux') args.unshift('--no-sandbox');
  if (url) args.push(url);

  const child = spawn(chrome, args, { stdio: 'ignore' });
  return {
    child,
    profile,
    kill() {
      child.kill();
      // Best-effort. On Windows Chrome keeps a handle on CrashpadMetrics for a moment after
      // being killed, and a leftover temp profile must never be the thing that fails a run.
      try {
        rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
      } catch { /* the OS will reap it */ }
    },
  };
}
