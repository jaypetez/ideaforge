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
export async function serveRepo({ root = ROOT, before, onResponse } = {}) {
  const defaultRoot = resolve(root);
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let path = decodeURIComponent(url.pathname);
    let servedRoot = defaultRoot;
    let swScope = '/';
    let fileError = null;
    if (onResponse) {
      const started = Date.now();
      res.once('close', () => onResponse({
        method: req.method, path: url.pathname, status: res.statusCode,
        requestBytes: Number(req.headers['content-length'] || 0),
        elapsedMs: Date.now() - started, aborted: !res.writableFinished, fileError,
      }));
    }

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
    } catch (error) {
      fileError = { code: error.code, message: error.message };
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
export class CDP {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.handlers = new Map();
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id == null) {
        for (const handler of this.handlers.get(message.method) || []) handler(message.params);
        return;
      }
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

  on(method, handler) {
    if (!this.handlers.has(method)) this.handlers.set(method, new Set());
    const handlers = this.handlers.get(method);
    handlers.add(handler);
    return () => {
      handlers.delete(handler);
      if (!handlers.size) this.handlers.delete(method);
    };
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
    this.handlers.clear();
    this.ws.close();
  }
}

/** Install before navigation so a failed iframe module cannot outrun its probe's onload. */
export async function observeChrome(cdp, record) {
  const contexts = new Map();
  const requests = new Map();
  const on = (method, handler) => cdp.on(method, handler);
  on('Runtime.executionContextCreated', ({ context }) => {
    contexts.set(context.id, context.auxData?.frameId);
    record('context', { id: context.id, frameId: context.auxData?.frameId, origin: context.origin });
  });
  on('Runtime.exceptionThrown', ({ exceptionDetails: error }) => record('exception', {
    frameId: contexts.get(error.executionContextId), text: error.exception?.description || error.text,
    url: error.url, line: error.lineNumber, column: error.columnNumber, stack: error.stackTrace,
  }));
  on('Runtime.consoleAPICalled', (event) => record('console', {
    frameId: contexts.get(event.executionContextId), level: event.type,
    text: event.args.map((arg) => String(arg.value ?? arg.description ?? arg.type).slice(0, 4000)).join(' '),
    stack: event.stackTrace,
  }));
  on('Log.entryAdded', ({ entry }) => record('log', {
    level: entry.level, source: entry.source, text: entry.text, url: entry.url,
    line: entry.lineNumber, stack: entry.stackTrace,
  }));
  on('Page.frameNavigated', ({ frame }) => record('frame', {
    frameId: frame.id, parentId: frame.parentId, loaderId: frame.loaderId, url: frame.url,
  }));
  on('Page.frameDetached', (event) => record('frame-detached', event));
  on('Network.requestWillBeSent', (event) => {
    const request = {
      id: event.requestId, frameId: event.frameId, loaderId: event.loaderId,
      type: event.type, method: event.request.method, url: event.request.url,
    };
    requests.set(event.requestId, request);
    record('request', request);
  });
  on('Network.responseReceived', (event) => {
    const request = requests.get(event.requestId);
    if (request) request.status = event.response.status;
    record('response', {
      ...request, id: event.requestId, status: event.response.status,
      mime: event.response.mimeType, fromDiskCache: event.response.fromDiskCache,
      fromServiceWorker: event.response.fromServiceWorker,
      serviceWorkerResponseSource: event.response.serviceWorkerResponseSource,
    });
  });
  on('Network.loadingFailed', (event) => {
    record('resource-failed', { ...requests.get(event.requestId), id: event.requestId,
      error: event.errorText, canceled: event.canceled, blockedReason: event.blockedReason });
    requests.delete(event.requestId);
  });
  on('Network.loadingFinished', ({ requestId }) => requests.delete(requestId));
  on('ServiceWorker.workerVersionUpdated', ({ versions }) => record('workers', { versions }));
  on('ServiceWorker.workerErrorReported', ({ errorMessage }) => record('worker-error', errorMessage));
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Log.enable');
  await cdp.send('Network.enable');
  await cdp.send('ServiceWorker.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    const report = (event, error = '') => console.debug('__ideaforge_boot__', JSON.stringify({
      event, error, url: location.href, readyState: document.readyState,
      version: document.getElementById('version')?.textContent,
      providers: document.getElementById('provider')?.options.length,
      startHandler: typeof document.getElementById('b-start')?.onclick,
      focused: document.hasFocus(), visibility: document.visibilityState,
      controller: navigator.serviceWorker?.controller?.scriptURL || null
    }));
    addEventListener('error', (event) =>
      report('error', event.message || event.target?.src || event.target?.href || 'resource error'), true);
    addEventListener('unhandledrejection', (event) => report('rejection', String(event.reason?.stack || event.reason)));
    addEventListener('DOMContentLoaded', () => report('dom-ready'), { once: true });
    addEventListener('load', () => report('load'), { once: true });
    navigator.serviceWorker?.addEventListener('controllerchange', () => report('controller-change'));
    let operation = 0;
    const storage = (event, detail) => console.debug('__ideaforge_storage__', JSON.stringify({
      event, url: location.href, ...detail
    }));
    const open = indexedDB.open;
    indexedDB.open = function (...args) {
      const request = open.apply(this, args);
      const id = ++operation;
      storage('open', { id, name: args[0], version: args[1] });
      for (const event of ['success', 'error', 'blocked', 'upgradeneeded']) {
        request.addEventListener(event, () => storage('open-' + event, {
          id, error: event === 'error' ? request.error?.name : undefined
        }));
      }
      return request;
    };
    const transaction = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function (...args) {
      const tx = transaction.apply(this, args);
      const id = ++operation;
      storage('transaction', { id, stores: [...tx.objectStoreNames], mode: tx.mode });
      for (const event of ['complete', 'abort', 'error']) {
        tx.addEventListener(event, () => storage('transaction-' + event, { id, error: tx.error?.name }));
      }
      return tx;
    };
    const hidden = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'hidden');
    Object.defineProperty(HTMLElement.prototype, 'hidden', {
      ...hidden,
      set(value) {
        if (this.id === 'panel-setup' || this.id === 'panel-library') {
          console.debug('__ideaforge_panel__', JSON.stringify({
            id: this.id, hidden: value, stack: new Error().stack
          }));
        }
        hidden.set.call(this, value);
      }
    });
    setTimeout(() => {
      if (document.getElementById('version')?.textContent === '') report('boot-still-pending');
    }, 2500);
  })();` });
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
