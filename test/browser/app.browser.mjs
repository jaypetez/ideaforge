// The real page, loaded in an iframe, twice: once at the origin root and once under a
// subpath, because GitHub Pages serves this app from /ideaforge/ and a root-absolute path
// anywhere would 404 there while working perfectly on localhost.
//
// The CSP is checked by listening for securitypolicyviolation rather than by reading the
// meta tag. A policy that blocks a script the app needs throws nothing and logs nothing to
// the page — it just silently does less, which is indistinguishable from working.

const BOOT_MS = 4000;

function loadFrame(src) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 700;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('iframe failed to load ' + src));
    frame.src = src;
    document.body.append(frame);
  });
}

const settle = (ms) => new Promise((r) => setTimeout(r, ms));

export default async function run(check, { subpath }) {
  // ── boots at the origin root ──────────────────────────────────────────────
  const frame = await loadFrame('/index.html');
  const win = frame.contentWindow;
  const doc = frame.contentDocument;

  const violations = [];
  win.addEventListener('securitypolicyviolation',
    (e) => violations.push(e.violatedDirective + ' blocked ' + e.blockedURI));
  const errors = [];
  win.addEventListener('error', (e) => errors.push(String(e.message || e)));

  await settle(BOOT_MS);
  const $ = (id) => doc.getElementById(id);

  check('the app boots with no uncaught error', errors.length === 0, errors.join('; '));
  check('the CSP blocks nothing the app needs', violations.length === 0, violations.join('; '));

  // These only get populated at the end of boot(), so they double as proof that the whole
  // async startup path — credential load included — actually completed.
  check('every provider is offered', $('provider').options.length >= 6,
    $('provider').options.length + ' providers');
  check('the version is shown', /^IdeaForge v\d+\.\d+\.\d+$/.test($('version').textContent),
    $('version').textContent);
  check('the dictation note is rendered', $('stt-note').textContent.trim().length > 0);
  check('no key stored, so Forget my key is hidden', $('b-forget').hidden === true);
  check('the interview panel starts hidden', $('panel-interview').hidden === true);
  check('the mic button is hidden until an interview starts', $('b-mic').hidden === true);
  check('#note is a polite atomic status region',
    $('note').getAttribute('role') === 'status'
      && $('note').getAttribute('aria-live') === 'polite'
      && $('note').getAttribute('aria-atomic') === 'true',
    `${$('note').getAttribute('role')} / ${$('note').getAttribute('aria-live')} / ${$('note').getAttribute('aria-atomic')}`);
  check('#err is an assertive atomic alert region',
    $('err').getAttribute('role') === 'alert'
      && $('err').getAttribute('aria-live') === 'assertive'
      && $('err').getAttribute('aria-atomic') === 'true',
    `${$('err').getAttribute('role')} / ${$('err').getAttribute('aria-live')} / ${$('err').getAttribute('aria-atomic')}`);

  // `hidden` is a property, not a guarantee. app.css gives section, .field, .meter, .btns,
  // .listening and .toggle an explicit `display`, and an author display beats the UA
  // stylesheet's `[hidden] { display: none }` — so every panel can report hidden === true
  // and be on screen at the same time. Asserting the property is exactly what let that ship,
  // so assert what a person actually sees instead.
  const rendered = (id) => win.getComputedStyle($(id)).display !== 'none';
  check('the hidden interview panel is not rendered', !rendered('panel-interview'));
  check('the hidden done panel is not rendered', !rendered('panel-done'));
  check('the Base URL field is not rendered for a hosted provider', !rendered('field-base'));
  check('the hidden listening indicator is not rendered', !rendered('listening'));
  check('the hidden coverage meter is not rendered', !rendered('meter'));

  // ── the local-server fields are reachable, which they were not ────────────
  //
  // This block used to assert only that #field-base stayed invisible. That was true, but
  // it was pinning down dead UI: onProviderChange showed the field when the choice was
  // 'custom', and the registry offered no such choice, so no user could ever reach it.
  $('provider').value = 'custom';
  $('provider').dispatchEvent(new win.Event('change'));

  check('choosing a local server reveals the address field', rendered('field-base'));
  check('…and the model picker', rendered('field-model'));
  check('…and asks for no API key', !rendered('field-key'));

  const typeBase = (value) => {
    $('baseurl').value = value;
    $('baseurl').dispatchEvent(new win.Event('input'));
  };

  // The prefix match this replaces called localhost.evil.com local: no key required, and
  // its failures blamed on CORS. The field being unreachable was all that hid it.
  typeBase('http://localhost.evil.com/v1');
  check('a remote address dressed as localhost is refused',
    /not on this machine/.test($('base-note').textContent), $('base-note').textContent);
  check('…and Start is disabled while it is refused', $('b-start').disabled === true);

  typeBase('http://127.0.0.1:11435/v1');
  check('a loopback address on a non-default port is accepted', $('b-start').disabled === false);
  check('…with nothing to complain about', $('base-note').textContent === '');

  // ── what the widened connect-src actually parses ─────────────────────────
  //
  // A CSP source expression the browser cannot parse is dropped silently: no error, no
  // securitypolicyviolation, just a directive that quietly permits less than it reads as.
  // The only way to know is to make a request and watch which way it fails. A CSP refusal
  // fires a violation BEFORE any socket is opened; a permitted request to a dead port
  // fails as an ordinary network error and fires nothing. Port 9 is discard, and is not
  // listening anywhere.
  const csp = (url) => new Promise((resolve) => {
    const seen = [];
    const onViolation = (e) => { if (e.blockedURI.includes(url.split('/')[2])) seen.push(e); };
    win.addEventListener('securitypolicyviolation', onViolation);
    win.fetch(url).catch(() => {}).finally(() => setTimeout(() => {
      win.removeEventListener('securitypolicyviolation', onViolation);
      resolve(seen.length === 0);
    }, 60));
  });

  check('connect-src permits a loopback port nothing hardcoded', await csp('http://127.0.0.1:9/'));
  check('connect-src permits localhost on any port', await csp('http://localhost:9/'));
  // This is why there is no http://[::1]:* in the CSP. The grammar has no IPv6-literal
  // form, so the token is dropped without a word and the directive permits less than it
  // reads as. Asserting the refusal keeps the settings screen's warning honest: if a
  // future Chrome starts parsing it, this check fails and tells us to revisit both.
  check('the IPv6 loopback literal is still refused, which is why we warn about it',
    !(await csp('http://[::1]:9/')));

  frame.remove();

  // The root-scoped service worker that boot() just registered shares this origin's cache
  // storage with everything below, so tear it down before asserting on the subpath's cache
  // — otherwise the two sets of entries are indistinguishable.
  for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  for (const n of await caches.keys()) await caches.delete(n);

  // ── boots identically on a Pages-style subpath ────────────────────────────
  const sub = await loadFrame(subpath + 'index.html');
  const subWin = sub.contentWindow;
  const subViolations = [];
  subWin.addEventListener('securitypolicyviolation',
    (e) => subViolations.push(e.violatedDirective + ' blocked ' + e.blockedURI));
  await settle(BOOT_MS);

  check('the app boots on a subpath too',
    sub.contentDocument.getElementById('provider').options.length >= 6);
  check('no CSP violations on the subpath', subViolations.length === 0, subViolations.join('; '));

  // The manifest's start_url, scope and icons are resolved against the manifest URL, so a
  // root-absolute value would break the installed app on Pages while looking fine locally.
  const manifest = await (await fetch(subpath + 'manifest.webmanifest')).json();
  const base = new URL(subpath + 'manifest.webmanifest', location.href);
  const resolved = (v) => new URL(v, base).pathname;
  check('manifest start_url resolves under the subpath',
    resolved(manifest.start_url).startsWith(subpath), resolved(manifest.start_url));
  check('manifest scope resolves under the subpath',
    resolved(manifest.scope) === subpath, resolved(manifest.scope));
  check('manifest icons resolve under the subpath',
    manifest.icons.every((i) => resolved(i.src).startsWith(subpath)));
  check('the manifest declares an id, so identity survives a start_url change',
    typeof manifest.id === 'string' && manifest.id.length > 0, manifest.id);

  // A service worker's scope is its own directory, and GitHub Pages cannot set the header
  // that would widen it — so registering relatively is the only thing that works there.
  const reg = await subWin.navigator.serviceWorker.register(subpath + 'sw.js', { scope: subpath });
  check('the service worker registers scoped to the subpath',
    new URL(reg.scope).pathname === subpath, new URL(reg.scope).pathname);

  await settle(2500);
  const names = await caches.keys();
  const cache = names.length ? await caches.open(names[0]) : null;
  const cached = cache ? (await cache.keys()).map((r) => new URL(r.url).pathname) : [];
  check('the service worker precached the shell', cached.length > 20, cached.length + ' entries');
  check('and cached it under the subpath, not the root',
    cached.length > 0 && cached.every((p) => p.startsWith(subpath)),
    cached.find((p) => !p.startsWith(subpath)) || 'all correct');

  await reg.unregister();
  await Promise.all(names.map((n) => caches.delete(n)));
  sub.remove();
}
