import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { CDP, observeChrome } from '../../tools/lib/harness.mjs';

class Socket extends EventTarget {
  sent = [];
  send(message) { this.sent.push(JSON.parse(message)); }
  emit(message) {
    const event = new Event('message');
    event.data = JSON.stringify(message);
    this.dispatchEvent(event);
  }
  close() { this.dispatchEvent(new Event('close')); }
}

test('CDP subscriptions retain early events without consuming command responses', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const socket = new Socket();
  const cdp = new CDP(socket);
  const seen = [];
  const unsubscribe = cdp.on('Runtime.exceptionThrown', (event) => seen.push(event));
  const command = cdp.send('Runtime.enable');
  socket.emit({ method: 'Runtime.exceptionThrown', params: { text: 'early boot failure' } });
  socket.emit({ id: socket.sent[0].id, result: { enabled: true } });
  assert.deepEqual(await command, { enabled: true });
  assert.deepEqual(seen, [{ text: 'early boot failure' }]);
  unsubscribe();
  socket.emit({ method: 'Runtime.exceptionThrown', params: { text: 'after unsubscribe' } });
  assert.equal(seen.length, 1);
  const pending = cdp.send('Page.navigate');
  cdp.close();
  await assert.rejects(pending, /client closed/);
  assert.equal(cdp.pending.size, 0);
  assert.equal(cdp.handlers.size, 0);
});

async function observed() {
  const events = [];
  const methods = [];
  const handlers = new Map();
  await observeChrome({
    on(method, handler) { handlers.set(method, handler); },
    async send(method, params) {
      methods.push({ method, params });
      if (method === 'Runtime.enable') {
        handlers.get('Runtime.exceptionThrown')({
          exceptionDetails: { text: 'before document initialization', url: 'http://127.0.0.1/app.js' },
        });
      }
      return {};
    },
  }, (kind, data) => events.push({ kind, ...data }));
  return { events, methods, handlers };
}

test('diagnostics subscribe before enabling Runtime and preserve resource response provenance', async () => {
  const { events, methods, handlers } = await observed();
  assert.equal(events[0].text, 'before document initialization');
  assert.ok(methods.some(({ method }) => method === 'Page.addScriptToEvaluateOnNewDocument'));
  handlers.get('Network.requestWillBeSent')({
    requestId: 'module', type: 'Script', frameId: 'iframe', loaderId: 'navigation',
    request: { url: 'http://127.0.0.1/src/ui/app.js', method: 'GET', headers: { secret: 'not logged' } },
  });
  handlers.get('Network.responseReceived')({
    requestId: 'module', response: { status: 503, mimeType: 'text/plain', fromServiceWorker: true },
  });
  handlers.get('Network.loadingFailed')({ requestId: 'module', errorText: 'net::ERR_FAILED' });
  assert.deepEqual(events.at(-1), {
    kind: 'resource-failed', id: 'module', frameId: 'iframe', loaderId: 'navigation',
    type: 'Script', method: 'GET', url: 'http://127.0.0.1/src/ui/app.js', status: 503,
    error: 'net::ERR_FAILED', canceled: undefined, blockedReason: undefined,
  });
  assert.ok(!JSON.stringify(events).includes('not logged'));
});

test('pre-document storage observers preserve native objects and pending request semantics', async () => {
  const { methods } = await observed();
  const script = methods.find(({ method }) => method === 'Page.addScriptToEvaluateOnNewDocument').params.source;
  const logs = [];
  const request = new EventTarget();
  Object.defineProperty(request, 'error', {
    get() { throw new Error('a pending request error must not be read'); },
  });
  const tx = new EventTarget();
  tx.objectStoreNames = ['secrets'];
  tx.mode = 'readonly';
  tx.error = null;
  let receiver;
  let openArgs;
  let transactionArgs;
  const indexedDB = {
    open(...args) { receiver = this; openArgs = args; return request; },
  };
  class Database {
    transaction(...args) { transactionArgs = args; return tx; }
  }
  class Element {
    get hidden() { return this.value; }
    set hidden(value) { this.value = value; }
  }
  runInNewContext(script, {
    indexedDB, IDBDatabase: Database, HTMLElement: Element,
    console: { debug: (...args) => logs.push(args) },
    document: { getElementById: () => null, hasFocus: () => true, readyState: 'loading' },
    location: { href: 'http://127.0.0.1/index.html' }, navigator: {},
    addEventListener() {}, setTimeout() {},
  });
  assert.equal(indexedDB.open('ideaforge', 1), request);
  assert.equal(receiver, indexedDB);
  assert.deepEqual(openArgs, ['ideaforge', 1]);
  request.dispatchEvent(new Event('upgradeneeded'));
  request.dispatchEvent(new Event('blocked'));
  request.dispatchEvent(new Event('success'));
  assert.equal(new Database().transaction('secrets', 'readonly'), tx);
  assert.deepEqual(transactionArgs, ['secrets', 'readonly']);
  tx.dispatchEvent(new Event('complete'));
  const element = new Element();
  element.id = 'panel-setup';
  element.hidden = false;
  assert.equal(element.hidden, false);
  const events = logs.map(([, data]) => JSON.parse(data).event).filter(Boolean);
  assert.deepEqual(events, [
    'open', 'open-upgradeneeded', 'open-blocked', 'open-success', 'transaction', 'transaction-complete',
  ]);
});
