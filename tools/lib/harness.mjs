// Shared plumbing for the headless-Chrome harnesses in this directory.
//
// tools/browser-check.mjs runs the probes Node cannot; tools/screenshots.mjs drives the real
// app and photographs it. The audio-policy checks also need a Chrome, a real HTTP origin,
// and a temp profile that gets cleaned up, so those live here once rather than diverging.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { join, extname, resolve, sep } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

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
 * `{ handled: true }`) or rewrite what gets served (`{ path, root, swScope }`). Everything
 * else falls through to static file serving rooted at ROOT, and anything escaping that root
 * is refused.
 *
 * @returns {Promise<import('node:http').Server>} already listening; `.address().port` is live.
 */
export async function serveRepo({ root = ROOT, before } = {}) {
  const defaultRoot = resolve(root);
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let path = decodeURIComponent(url.pathname);
    let servedRoot = defaultRoot;
    let swScope = '/';

    if (before) {
      const out = await before(req, res, url);
      if (out && out.handled) return;
      if (out && out.path) path = out.path;
      if (out && out.root) servedRoot = resolve(out.root);
      if (out && out.swScope) swScope = out.swScope;
    }
    if (!path || path.endsWith('/')) path += 'index.html';

    const relativePath = path.replace(/^\/+/, '').split('/').join(sep);
    const file = resolve(servedRoot, relativePath);
    if (file !== servedRoot && !file.startsWith(servedRoot + sep)) {
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

/**
 * The same dependency-free CDP transport used by screenshots and the local validator.
 * This shared variant bounds requests and never fabricates Runtime user activation.
 */
class CDP {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
    });
    ws.addEventListener('close', () => this.rejectPending(new Error('Chrome CDP connection closed')));
    ws.addEventListener('error', () => this.rejectPending(new Error('Chrome CDP connection failed')));
  }

  rejectPending(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }

  send(method, params = {}, timeout = 10000) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP ${method} timed out after ${timeout}ms`));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.ws.send(JSON.stringify({ id, method, params }));
      } catch (error) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(error);
      }
    });
  }

  async evaluate(expression) {
    const reply = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true, userGesture: false,
    });
    if (reply.exceptionDetails) {
      throw new Error(reply.exceptionDetails.exception?.description || reply.exceptionDetails.text);
    }
    return reply.result.value;
  }

  /** A browser-dispatched input sequence, not an HTMLElement.click() or evaluation override. */
  async click(selector) {
    const point = await this.evaluate(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)});
      if (!element) throw new Error('missing click target');
      const rect = element.getBoundingClientRect();
      if (!rect.width || !rect.height) throw new Error('click target is not visible');
      return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
    })()`);
    await this.send('Input.dispatchMouseEvent', {
      type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1,
    });
    await this.send('Input.dispatchMouseEvent', {
      type: 'mouseReleased', ...point, button: 'left', buttons: 0, clickCount: 1,
    });
  }

  close() {
    this.rejectPending(new Error('Chrome CDP client closed'));
    this.ws.close();
  }
}

/** Attach only to the debugging port written by this throwaway Chrome profile. */
export async function connectChrome(browser, timeout = 15000) {
  const deadline = Date.now() + timeout;
  const portFile = join(browser.profile, 'DevToolsActivePort');
  let lastError;
  let launchError;
  const onError = (error) => { launchError = error; };
  browser.child.on('error', onError);
  try {
    while (Date.now() < deadline) {
      if (launchError) throw launchError;
      if (browser.child.exitCode !== null || browser.child.signalCode !== null) {
        throw new Error('Chrome exited before its debugging port was ready');
      }
      let target;
      try {
        const [port] = (await readFile(portFile, 'utf8')).split(/\r?\n/);
        if (!/^\d+$/.test(port)) throw new Error('invalid Chrome debugging port');
        const response = await fetch(`http://127.0.0.1:${port}/json/list`, {
          signal: AbortSignal.timeout(1000),
        });
        if (!response.ok) throw new Error(`Chrome target discovery returned HTTP ${response.status}`);
        target = (await response.json()).find((page) => page.type === 'page' && page.webSocketDebuggerUrl);
      } catch (error) {
        lastError = error;
      }
      if (target) {
        const ws = new WebSocket(target.webSocketDebuggerUrl);
        try {
          await new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('Chrome CDP attach timed out')), 5000);
            ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
            ws.addEventListener('error', () => {
              clearTimeout(timer);
              reject(new Error('could not attach to Chrome CDP'));
            }, { once: true });
          });
          return new CDP(ws);
        } catch (error) {
          ws.close();
          throw error;
        }
      }
      await sleep(50);
    }
    throw new Error('Chrome never opened a debugging port', { cause: lastError });
  } finally {
    browser.child.removeListener('error', onError);
  }
}
