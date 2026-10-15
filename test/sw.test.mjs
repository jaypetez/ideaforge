import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const SOURCE = readFileSync(new URL('../sw.js', import.meta.url), 'utf8');
const BASE = 'https://ideaforge.test/subpath/';
const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

function worker({ cached, shell, fresh = Promise.resolve(new Response('fresh module')) } = {}) {
  const listeners = new Map();
  const opened = deferred();
  const cacheRequested = deferred();
  const jobs = [];
  const errors = [];
  const writes = [];
  const warnings = [];
  let fetches = 0;
  const cache = { async put(request, response) {
    writes.push({ url: request.url, text: await response.text() });
  } };
  const caches = {
    match: async (request) => request === './index.html' ? shell : cached,
    open() {
      cacheRequested.resolve();
      return { then(complete) {
        const job = opened.promise.then(complete);
        jobs.push(job);
        job.catch((error) => errors.push(error.message));
        return job;
      } };
    },
  };
  runInNewContext(SOURCE, {
    URL, Response, caches,
    fetch: () => { fetches++; return fresh; },
    console: { warn: (...args) => warnings.push(args) },
    self: {
      location: new URL('sw.js', BASE),
      addEventListener: (name, listener) => listeners.set(name, listener),
    },
  });
  return {
    cacheRequested: cacheRequested.promise, errors, writes, warnings, jobs,
    releaseCache: () => opened.resolve(cache),
    failCache: (error) => opened.reject(error),
    fetches: () => fetches,
    request(path = 'src/ui/dialog.js', { method = 'GET', mode = 'cors' } = {}) {
      const waits = [];
      let response;
      listeners.get('fetch')({
        request: { url: new URL(path, BASE).href, method, mode },
        respondWith: (promise) => { response = promise; },
        waitUntil: (promise) => waits.push(promise),
      });
      return {
        get response() { return response; }, waits,
        async finish() {
          for (let index = 0; index < waits.length; index++) await waits[index];
          await Promise.allSettled(jobs);
        },
      };
    },
  };
}

test('a cache-miss response is cloned before its consumer uses the body', async () => {
  const fixture = worker();
  const event = fixture.request();
  const response = await event.response;
  assert.equal(await response.text(), 'fresh module');
  await fixture.cacheRequested;
  fixture.releaseCache();
  await event.finish();
  assert.deepEqual(fixture.errors, [], 'deferred cache storage must not clone an already consumed response');
  assert.deepEqual(fixture.writes, [{ url: BASE + 'src/ui/dialog.js', text: 'fresh module' }]);
  assert.ok(event.waits.length > 0, 'the fetch event must keep its asynchronous refresh alive');
});

test('a cached response is immediate but its network and cache refresh remain in the event lifetime', async () => {
  const network = deferred();
  const fixture = worker({ cached: new Response('cached module'), fresh: network.promise });
  const event = fixture.request();
  assert.equal(await (await event.response).text(), 'cached module');
  let finished = false;
  const lifetime = event.finish().then(() => { finished = true; });
  network.resolve(new Response('updated module'));
  await fixture.cacheRequested;
  await Promise.resolve();
  assert.equal(finished, false, 'responding from cache must not retire the in-flight refresh');
  fixture.releaseCache();
  await lifetime;
  assert.equal(fixture.writes[0].text, 'updated module');
  assert.deepEqual(fixture.errors, []);
});

test('a failed cache write reports its error without replacing a valid network response', async () => {
  const fixture = worker();
  const event = fixture.request();
  assert.equal(await (await event.response).text(), 'fresh module');
  await fixture.cacheRequested;
  fixture.failCache(new Error('cache storage is full'));
  await event.finish();
  assert.equal(fixture.writes.length, 0);
  assert.equal(fixture.warnings.length, 1);
  assert.equal(fixture.warnings[0][1].message, 'cache storage is full');
});

test('offline navigation falls back to the cached app shell', async () => {
  const fixture = worker({
    shell: new Response('cached app shell'),
    fresh: Promise.reject(new Error('offline')),
  });
  const event = fixture.request('idea', { mode: 'navigate' });
  assert.equal(await (await event.response).text(), 'cached app shell');
  await event.finish();
});

test('an offline navigation without a cached shell returns an explicit offline response', async () => {
  const fixture = worker({ fresh: Promise.reject(new Error('offline')) });
  const event = fixture.request('idea', { mode: 'navigate' });
  const response = await event.response;
  assert.ok(response instanceof Response);
  assert.equal(response.status, 503);
  assert.equal(await response.text(), 'offline');
  await event.finish();
});

test('a missing offline module never receives cached app HTML', async () => {
  const fixture = worker({
    shell: new Response('cached app shell'),
    fresh: Promise.reject(new Error('offline')),
  });
  const event = fixture.request();
  assert.equal((await event.response).status, 503);
  await event.finish();
});

test('provider, POST and guide traffic never starts an app cache operation', () => {
  for (const [path, options] of [
    ['https://api.openai.com/v1/models', {}],
    ['model', { method: 'POST' }],
    ['guide/privacy-policy.html', {}],
    ['docs/screenshots/10-settings.light.png', {}],
  ]) {
    const fixture = worker();
    assert.equal(fixture.request(path, options).response, undefined);
    assert.equal(fixture.fetches(), 0);
  }
});
