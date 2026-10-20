import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import {
  loadPrefs, PREFS_KEY, savePrefs, speechPreferences, themePreference,
} from '../src/store/prefs.js';
import { applyTheme, THEME_COLOURS } from '../src/ui/theme.js';

test('speech preferences migrate without a voice or rate override', () => {
  assert.deepEqual(speechPreferences({ trigger: 'finished', handsFree: true }), {
    speechVoice: '', speechRate: 1,
  });
});

test('a selected voice and supported rate are retained', () => {
  assert.deepEqual(speechPreferences({ speechVoice: 'device:voice', speechRate: 0.85 }), {
    speechVoice: 'device:voice', speechRate: 0.85,
  });
});

test('invalid or unbounded speech preferences cannot reach the audio engine', () => {
  for (const rate of [NaN, Infinity, -1, 0, 0.74, 1.26, 10, '1', null]) {
    assert.equal(speechPreferences({ speechRate: rate }).speechRate, 1);
  }
  assert.equal(speechPreferences({ speechVoice: {} }).speechVoice, '');
  assert.equal(speechPreferences({ speechVoice: 'x'.repeat(900) }).speechVoice.length, 512);
});

function withStorage(value, run) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { value, configurable: true });
  try { run(); } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original);
    else delete globalThis.localStorage;
  }
}

test('hosted speech requires a literal consent flag and revocation is synchronous', () => {
  let stored = null;
  withStorage({
    getItem: () => stored,
    setItem: (_key, value) => { stored = value; },
  }, () => {
    assert.equal(loadPrefs().hostedSpeechAllowed, false);
    for (const value of ['true', 1, null]) {
      stored = JSON.stringify({ hostedSpeechAllowed: value });
      assert.equal(loadPrefs().hostedSpeechAllowed, false);
    }
    assert.equal(savePrefs({ hostedSpeechAllowed: true }), true);
    assert.equal(loadPrefs().hostedSpeechAllowed, true);
    assert.equal(savePrefs({ hostedSpeechAllowed: false }), true);
    assert.equal(loadPrefs().hostedSpeechAllowed, false);
    savePrefs({ speechVoice: 'device:voice', speechRate: 0.85 });
    assert.equal(loadPrefs().hostedSpeechAllowed, false);
  });
});

test('preference writes report failure so consent changes cannot claim a saved choice', () => {
  withStorage({
    getItem: () => null,
    setItem: () => { throw new Error('storage full'); },
  }, () => assert.equal(savePrefs({ hostedSpeechAllowed: false }), false));
});

// ── theme (#112) ─────────────────────────────────────────────────────────────

test('the theme reads as system unless it is literally light or dark', () => {
  assert.equal(themePreference('light'), 'light');
  assert.equal(themePreference('dark'), 'dark');
  for (const value of ['system', 'garbage', 'Dark', ' dark', '', null, undefined, 1, {}, ['dark']]) {
    assert.equal(themePreference(value), 'system', JSON.stringify(value));
  }
});

test('a stored theme survives a round trip and garbage loads and saves as system', () => {
  let stored = null;
  withStorage({
    getItem: () => stored,
    setItem: (_key, value) => { stored = value; },
  }, () => {
    assert.equal(loadPrefs().theme, 'system', 'nothing stored');
    assert.equal(savePrefs({ theme: 'dark' }), true);
    assert.equal(JSON.parse(stored).theme, 'dark');
    assert.equal(loadPrefs().theme, 'dark');
    // Saving something else keeps the theme.
    savePrefs({ handsFree: true });
    assert.equal(loadPrefs().theme, 'dark');
    stored = JSON.stringify({ theme: 'garbage' });
    assert.equal(loadPrefs().theme, 'system');
    // A bad value handed to savePrefs never reaches storage, where theme-boot.js reads it raw.
    savePrefs({ theme: 'sepia' });
    assert.equal(JSON.parse(stored).theme, 'system');
  });
});

// theme-boot.js is a classic script, so it cannot import themePreference or PREFS_KEY. Run
// the real file against a fake page and hold it to the same rule.
const BOOT = readFileSync(new URL('../src/ui/theme-boot.js', import.meta.url), 'utf8');

test('the classic theme script is inert when the module graph imports it without a page', () => {
  assert.doesNotThrow(() => runInNewContext(BOOT, {}));
});

function boot({ stored, storageThrows = false, preset } = {}) {
  const dataset = preset === undefined ? {} : { theme: preset };
  const metas = [
    { media: '(prefers-color-scheme: light)', content: THEME_COLOURS.light },
    { media: '(prefers-color-scheme: dark)', content: THEME_COLOURS.dark },
  ].map((m) => ({ ...m, setAttribute(name, value) { this[name] = value; } }));
  const reads = [];
  const localStorage = {
    getItem(key) {
      reads.push(key);
      if (storageThrows) throw new Error('SecurityError');
      return stored === undefined ? null : stored;
    },
  };
  let loaded;
  const addEventListener = (type, handler, capture) => {
    assert.equal(type, 'load');
    assert.equal(capture, true);
    loaded = handler;
  };
  const removeEventListener = (type, handler, capture) => {
    assert.equal(type, 'load');
    assert.equal(capture, true);
    assert.equal(handler, loaded);
    loaded = null;
  };
  const document = {
    readyState: 'loading',
    documentElement: { dataset },
    querySelectorAll: (selector) => (selector === 'meta[name="theme-color"]' ? metas : []),
    addEventListener,
    removeEventListener,
  };
  let pageLoaded;
  runInNewContext(BOOT, {
    localStorage,
    document,
    addEventListener(type, handler, options) {
      assert.equal(type, 'load');
      assert.equal(options.once, true);
      pageLoaded = handler;
    },
    removeEventListener(type, handler) {
      assert.equal(type, 'load');
      if (pageLoaded) assert.equal(handler, pageLoaded);
      pageLoaded = null;
    },
  });
  return {
    theme: dataset.theme, metas: metas.map((m) => m.content), reads,
    loaded(target) { loaded?.({ target }); },
    complete() {
      document.readyState = 'complete';
      const handler = pageLoaded;
      pageLoaded = null;
      handler?.();
    },
  };
}

test('optional font CSS activates after loading even when theme storage is unavailable', () => {
  for (const options of [{}, { storageThrows: true }, { preset: 'dark' }]) {
    const page = boot(options);
    page.complete();
    const other = { id: 'another-preload', rel: 'preload' };
    page.loaded(other);
    assert.equal(other.rel, 'preload');
    const fonts = { id: 'font-styles', rel: 'preload' };
    page.loaded(fonts);
    assert.equal(fonts.rel, 'stylesheet');
    fonts.rel = 'preload';
    page.loaded(fonts);
    assert.equal(fonts.rel, 'preload', 'the completed font listener is retired');
  }
});

test('an early font preload cannot activate a stylesheet before the page load event', () => {
  const page = boot();
  const fonts = { id: 'font-styles', rel: 'preload' };
  page.loaded(fonts);
  assert.equal(fonts.rel, 'preload', 'a fast CSS response must not acquire a load-blocking dependency');
  page.complete();
  assert.equal(fonts.rel, 'stylesheet');
  fonts.rel = 'preload';
  page.loaded(fonts);
  assert.equal(fonts.rel, 'preload', 'activation retires the listener before another link load');
});

test('theme-boot reads the same key prefs.js writes', () => {
  assert.deepEqual(boot().reads, [PREFS_KEY]);
});

test('theme-boot applies an explicit choice and retints the browser chrome to match', () => {
  assert.equal(boot({ stored: JSON.stringify({ theme: 'dark' }) }).theme, 'dark');
  assert.deepEqual(boot({ stored: JSON.stringify({ theme: 'dark' }) }).metas,
    [THEME_COLOURS.dark, THEME_COLOURS.dark]);
  assert.equal(boot({ stored: JSON.stringify({ theme: 'light' }) }).theme, 'light');
  assert.deepEqual(boot({ stored: JSON.stringify({ theme: 'light' }) }).metas,
    [THEME_COLOURS.light, THEME_COLOURS.light]);
});

test('theme-boot leaves data-theme unset for system, garbage, nothing stored or a storage error', () => {
  for (const stored of [undefined, '', 'not json', 'null', '{}', JSON.stringify({ theme: 'system' }),
    JSON.stringify({ theme: 'garbage' }), JSON.stringify({ theme: 'Dark' })]) {
    const out = boot({ stored });
    assert.equal(out.theme, undefined, String(stored));
    assert.deepEqual(out.metas, [THEME_COLOURS.light, THEME_COLOURS.dark], String(stored));
  }
  assert.equal(boot({ storageThrows: true }).theme, undefined);
});

test('theme-boot preserves a harness-set theme and matches its chrome even without readable prefs', () => {
  // tools/screenshots.mjs sets dataset.theme before any page script, in a fresh profile.
  for (const preset of ['light', 'dark']) {
    for (const stored of [undefined, JSON.stringify({ theme: 'system' }), JSON.stringify({ theme: 'x' })]) {
      const out = boot({ preset, stored });
      assert.equal(out.theme, preset, `${preset} / ${stored}`);
      assert.deepEqual(out.metas, [THEME_COLOURS[preset], THEME_COLOURS[preset]]);
    }
    const out = boot({ preset, storageThrows: true });
    assert.equal(out.theme, preset);
    assert.deepEqual(out.metas, [THEME_COLOURS[preset], THEME_COLOURS[preset]]);
  }
});

test('the theme-color metas in index.html match the colours the theme code writes', () => {
  const index = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  assert.ok(index.includes(`<meta name="theme-color" content="${THEME_COLOURS.light}" media="(prefers-color-scheme: light)">`));
  assert.ok(index.includes(`<meta name="theme-color" content="${THEME_COLOURS.dark}" media="(prefers-color-scheme: dark)">`));
});

test('index.html loads theme-boot as a blocking classic script in the head, before the styles', () => {
  const index = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const head = index.slice(0, index.indexOf('</head>'));
  const tag = head.match(/<script\b[^>]*src="\.\/src\/ui\/theme-boot\.js"[^>]*><\/script>/);
  assert.ok(tag, 'theme-boot.js is not loaded in <head>');
  // A module, defer or async script runs after first paint: the flash this exists to prevent.
  assert.doesNotMatch(tag[0], /\b(type|defer|async)\b/);
  assert.ok(head.indexOf(tag[0]) > head.lastIndexOf('name="theme-color"'), 'it retints the metas, so it follows them');
  assert.ok(head.indexOf(tag[0]) < head.indexOf('rel="stylesheet"'), 'it should precede the stylesheets');
  const fonts = head.match(/<link\b[^>]*id="font-styles"[^>]*>/)?.[0];
  assert.ok(fonts, 'the optional font preload must be named');
  assert.match(fonts, /\brel="preload"/);
  assert.match(fonts, /\bas="style"/);
  assert.ok(head.indexOf(tag[0]) < head.indexOf(fonts), 'install the load listener before a cached preload can finish');
});

test('applyTheme sets or clears data-theme and returns what it applied', () => {
  const dataset = {};
  const metas = ['(prefers-color-scheme: light)', '(prefers-color-scheme: dark)']
    .map((media) => ({ attrs: { media }, getAttribute(n) { return this.attrs[n]; }, setAttribute(n, v) { this.attrs[n] = v; } }));
  const doc = { documentElement: { dataset }, querySelectorAll: () => metas };
  assert.equal(applyTheme(doc, 'dark'), 'dark');
  assert.equal(dataset.theme, 'dark');
  assert.deepEqual(metas.map((m) => m.attrs.content), [THEME_COLOURS.dark, THEME_COLOURS.dark]);
  assert.equal(applyTheme(doc, 'system'), 'system');
  assert.equal('theme' in dataset, false);
  assert.deepEqual(metas.map((m) => m.attrs.content), [THEME_COLOURS.light, THEME_COLOURS.dark]);
  assert.equal(applyTheme(doc, 'garbage'), 'system');
  assert.equal('theme' in dataset, false);
});
