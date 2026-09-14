// Three hand-maintained registries with no single source of truth, all of which fail
// invisibly:
//
//   sw.js's SHELL         a module missing from it fails only on a COLD offline start.
//                         Any online visit caches it anyway, so the bug hides from every
//                         test you would think to run — and from every manual check.
//   index.html connect-src  a provider host missing from it is not an error. The request
//                         is simply blocked, nothing is logged to the page, and the app
//                         carries on as though the network were down.
//   the provider presets  the thing the other two have to stay in step with.
//
// src/version.js was missing from SHELL when this test was written, and src/ui/app.js
// imports it at module scope — so a genuinely cold offline launch answered 503 on that
// import and the whole module graph died. That is precisely the scenario the service
// worker exists for, and precisely the one nothing could see.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { OPENAI_COMPAT_PRESETS } from '../src/providers/openaiCompat.js';
import { STT_PRESETS } from '../src/voice/transcribe.js';
import { isLoopback } from '../src/providers/http.js';

// fileURLToPath, not .pathname: on Windows the latter yields /C:/... and readdirSync then
// resolves it to C:\C:\... and throws. The purity linter learned this the hard way.
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SW = readFileSync(join(ROOT, 'sw.js'), 'utf8');
const INDEX = readFileSync(join(ROOT, 'index.html'), 'utf8');

/** Every file under src/ the browser would actually load. */
function sourceFiles(dir = 'src') {
  const out = [];
  for (const entry of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) out.push(...sourceFiles(rel));
    else if (/\.(js|css|svg)$/.test(entry.name)) out.push(rel);
  }
  return out;
}

test('every file under src/ is precached by the service worker', () => {
  const files = sourceFiles();
  // If the walk ever stops matching, this test would pass while checking nothing.
  assert.ok(files.length >= 20, `only found ${files.length} source files`);

  const missing = files.filter((f) => !SW.includes(`'./${f}'`));
  assert.deepEqual(missing, [], `sw.js SHELL is missing: ${missing.join(', ')}`);
});

/**
 * The connect-src directive out of the real meta tag — not out of the comment above it,
 * which also says the words "connect-src" and which an unanchored match happily returns.
 */
function connectSrc() {
  const tag = INDEX.match(/<meta http-equiv="Content-Security-Policy" content="([^"]*)"/);
  assert.ok(tag, 'no Content-Security-Policy meta tag in index.html');
  const m = tag[1].match(/connect-src([^;]*);/);
  assert.ok(m, 'no connect-src directive in the CSP');
  return m[1];
}

test('every host a provider talks to is in the CSP allowlist', () => {
  const directive = connectSrc();
  const origins = [
    'https://api.anthropic.com',
    ...Object.values(OPENAI_COMPAT_PRESETS).map((p) => p.baseUrl),
    ...Object.values(STT_PRESETS).map((p) => p.baseUrl),
  ];

  for (const url of origins) {
    const { protocol, hostname, port } = new URL(url);
    // Loopback is wildcarded by port, so match the host and accept any port.
    const needle = isLoopback(url) ? `${protocol}//${hostname}:` : `${protocol}//${hostname}`;
    assert.ok(directive.includes(needle),
      `connect-src does not permit ${url} — the request will be blocked with no error`);
    if (!isLoopback(url) && port) {
      assert.ok(directive.includes(`${protocol}//${hostname}:${port}`), `${url} needs its port listed`);
    }
  }
});

test('the CSP permits a local server on any port, not two hardcoded ones', () => {
  const directive = connectSrc();
  // The point of widening it: Ollama on 11435, llama.cpp on 8080, anything a user runs.
  for (const host of ['http://localhost:*', 'http://127.0.0.1:*']) {
    assert.ok(directive.includes(host), `connect-src should permit ${host}`);
  }
  // And the remote side stays a tight allowlist — a wildcard here would defeat the one
  // thing standing between a stored API key and a script that wants to post it away.
  assert.ok(!/https:\/\/\*/.test(directive), 'connect-src must not wildcard remote hosts');
  assert.ok(!/\s\*[\s;]/.test(directive), 'connect-src must not contain a bare wildcard');
});
