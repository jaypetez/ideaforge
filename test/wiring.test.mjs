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
import {
  mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { OPENAI_COMPAT_PRESETS } from '../src/providers/openaiCompat.js';
import { STT_PRESETS } from '../src/voice/transcribe.js';
import { TTS_PRESETS } from '../src/providers/tts.js';
import { isLoopback } from '../src/providers/http.js';
import { assembleSite, ROOT_DIRS, ROOT_FILES } from '../tools/assemble-site.mjs';

// fileURLToPath, not .pathname: on Windows the latter yields /C:/... and readdirSync then
// resolves it to C:\C:\... and throws. The purity linter learned this the hard way.
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SW = readFileSync(join(ROOT, 'sw.js'), 'utf8');
const INDEX = readFileSync(join(ROOT, 'index.html'), 'utf8');
const DOCKERFILE = readFileSync(join(ROOT, 'docker', 'Dockerfile.app'), 'utf8');

/** Every file under src/ the browser would actually load. */
function sourceFiles(dir = 'src') {
  const out = [];
  for (const entry of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) out.push(...sourceFiles(rel));
    else if (/\.(js|css|svg|png)$/.test(entry.name)) out.push(rel);
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
    ...Object.values(TTS_PRESETS).map((p) => p.baseUrl),
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

function tree(dir, base = dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...tree(file, base));
    else out.push(file.slice(base.length + 1).replace(/\\/g, '/'));
  }
  return out.sort();
}

test('the assembled site contains exactly the publishable tree', async () => {
  const expected = [
    ...ROOT_FILES,
    ...ROOT_DIRS.flatMap((dir) => tree(join(ROOT, dir)).map((file) => `${dir}/${file}`)),
  ].sort();
  const outDir = mkdtempSync(join(tmpdir(), 'ideaforge-site-test-'));

  try {
    const written = await assembleSite({ root: ROOT, outDir, clean: false });
    const actual = tree(outDir);

    assert.deepEqual(written, expected);
    assert.deepEqual(actual, expected);
    assert.ok(actual.includes('index.html'));
    assert.ok(actual.includes('manifest.webmanifest'));
    assert.ok(actual.includes('sw.js'));
    assert.ok(actual.includes('LICENSE'));
    assert.ok(actual.includes('src/ui/app.js'));
    assert.ok(actual.includes('src/version.js'));
    assert.ok(actual.includes('guide/index.html'));
    assert.ok(actual.includes('guide/assets/guide.css'));
    assert.ok(actual.includes('docs/screenshots/01-setup.light.png'));
    assert.ok(!actual.some((file) => file.startsWith('test/')), 'test files must not ship');
    assert.ok(!actual.some((file) => file.startsWith('docs/') && !file.startsWith('docs/screenshots/')),
      'only generated screenshots may ship from docs/');
    assert.ok(!actual.some((file) => /\.test\.mjs$/.test(file)), 'test modules must not ship');
    assert.ok(!actual.some((file) => file.startsWith('android/')), 'the Android project must not ship');
    assert.ok(!actual.some((file) => /^(?:tools|\.claude|\.github|\.ideaforge-assist)\//.test(file)),
      'developer-assistance code, native host SDK entry points and local state must not ship');
  } finally {
    rmSync(outDir, { recursive: true, force: true });
  }
});

// The native app in android/ has real dependencies (Gradle, Compose, OkHttp) and signing
// configuration. The web app's "no dependencies, no build step" promise holds only while none
// of that is ever served, so this checks packaging against a tree that actually contains an
// Android project, rather than relying on the repository not having one yet.
test('packaging never publishes the Android project, even when it exists', async () => {
  const root = mkdtempSync(join(tmpdir(), 'ideaforge-android-root-'));
  const outDir = mkdtempSync(join(tmpdir(), 'ideaforge-android-out-'));
  const put = (file, text = 'x') => {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    writeFileSync(join(root, file), text);
  };
  try {
    for (const file of ROOT_FILES) put(file);
    for (const dir of ROOT_DIRS) put(`${dir}/placeholder.txt`);
    put('android/settings.gradle.kts');
    put('android/gradlew.bat');
    put('android/app/build.gradle.kts');
    put('android/app/src/main/AndroidManifest.xml');
    put('android/keystore.properties', 'storePassword=secret');
    // Signing material is gitignored, but a stray local copy inside a published root must
    // still never be copied into a served or Pages tree (#95).
    put('src/release.jks');
    put('src/keystore.properties', 'storePassword=secret');
    put('guide/upload.keystore');
    put('tools/assistant.mjs');
    put('.github/extensions/ideaforge-assist/extension.mjs', 'host SDK, not an app dependency');
    put('.claude/settings.json');
    put('.ideaforge-assist/session-fixture.json', 'local assistant state');

    const written = await assembleSite({ root, outDir, clean: false });
    assert.ok(written.length > 0, 'the fixture must publish something');
    assert.deepEqual(written.filter((file) => file.startsWith('android/')), []);
    assert.deepEqual(tree(outDir).filter((file) => file.startsWith('android/')), []);
    const signing = /\.(jks|keystore)$|keystore\.properties$/;
    assert.deepEqual(written.filter((file) => signing.test(file)), [], 'signing material was published');
    assert.deepEqual(tree(outDir).filter((file) => signing.test(file)), []);
    assert.deepEqual(written.filter((file) =>
      /^(?:tools|\.claude|\.github|\.ideaforge-assist)\//.test(file)), []);
  } finally {
    rmSync(root, { recursive: true, force: true });
    rmSync(outDir, { recursive: true, force: true });
  }
});

test('the container copies the same publishable roots as the site assembler', () => {
  const copyLines = DOCKERFILE.split(/\r?\n/).filter((line) => /^COPY\s+/.test(line));
  const sources = copyLines.flatMap((line) => line.trim().split(/\s+/).slice(1, -1));

  for (const file of ROOT_FILES) {
    assert.ok(sources.includes(file), `docker/Dockerfile.app does not copy ${file}`);
  }
  for (const dir of ROOT_DIRS) {
    assert.ok(sources.includes(`${dir}/`), `docker/Dockerfile.app does not copy ${dir}/`);
  }
  assert.ok(!sources.includes('.'), 'the container must not copy the whole repository');
});

test('the app service worker leaves the public guide alone', () => {
  assert.match(SW, /GUIDE_PATH/);
  assert.match(SW, /GUIDE_SCREENSHOTS_PATH/);
  assert.match(SW, /url\.pathname\.startsWith\(GUIDE_PATH\)[\s\S]*GUIDE_SCREENSHOTS_PATH/);
  assert.doesNotMatch(SW, /^\s*'\.\/guide\//m,
    'guide pages must not join the app shell cache');
});

// The signed-APK release job (#95). There is no YAML parser here (no dependencies), so jobs
// are cut out by their two-space indentation, which is how every workflow in this repo is
// written.
const RELEASE = readFileSync(join(ROOT, '.github', 'workflows', 'release.yml'), 'utf8').replace(/\r\n/g, '\n');
const CI = readFileSync(join(ROOT, '.github', 'workflows', 'ci.yml'), 'utf8').replace(/\r\n/g, '\n');
const SIGNING_SECRETS = ['ANDROID_KEYSTORE_B64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD'];

function job(workflow, name) {
  const start = workflow.search(new RegExp(`^  ${name}:\n`, 'm'));
  assert.ok(start >= 0, `no ${name} job`);
  const rest = workflow.slice(start + 1);
  const end = rest.search(/^ {2}\S/m);
  return end < 0 ? rest : rest.slice(0, end);
}

test('release verification refuses an incomplete privacy policy before publication', () => {
  const verify = job(RELEASE, 'verify');
  assert.match(verify, /- name: Verify the privacy policy\n\s+run: node tools\/check-privacy-policy\.mjs/);
  assert.doesNotMatch(verify, /continue-on-error:\s*true/);
  assert.ok(verify.indexOf('node tools/check-privacy-policy.mjs')
    < verify.indexOf('npm run test:all'), 'check policy readiness before the release ladder');
});

test('the android-apk job is the only one given the signing secrets, and it fails closed', () => {
  const apk = job(RELEASE, 'android-apk');
  assert.match(apk, /^ {4}needs: verify$/m);
  assert.match(apk, /^ {4}environment: release$/m);
  assert.match(apk, /^ {6}contents: read$/m);
  assert.doesNotMatch(apk, /: write/, 'the signing job must not hold a write token');

  const inApk = new Set([...apk.matchAll(/secrets\.(\w+)/g)].map((m) => m[1]));
  assert.deepEqual([...inApk].sort(), [...SIGNING_SECRETS].sort(), 'android-apk reads exactly the four secrets');
  const outside = RELEASE.replace(apk, '');
  assert.doesNotMatch(outside, /secrets\.ANDROID_/, 'a signing secret is exposed outside android-apk');

  assert.ok(apk.includes('::error::release keystore secrets are not configured'),
    'a missing secret must fail the release, never skip the APK');
  const required = apk.match(/for name in ([\w ]+); do/)?.[1].split(' ');
  assert.deepEqual(required?.sort(), [...SIGNING_SECRETS].sort(), 'the presence check covers all four secrets');
  assert.match(apk, /\$RUNNER_TEMP\/release\.jks/, 'the keystore is decoded outside the checkout');
  assert.match(apk, /:app:assembleRelease :app:verifyReleaseApk/);
  assert.match(apk, /apksigner" verify --print-certs/);
  assert.match(apk, /node tools\/apk-cert-pin\.mjs "\$RUNNER_TEMP\/apksigner\.txt" SECURITY\.md/);
  assert.match(apk, /ideaforge-\$\{VERSION\}\.apk/);
  assert.match(apk, /- name: Delete the release keystore\n\s+if: always\(\)\n\s+run: rm -f "\$RUNNER_TEMP\/release\.jks"/,
    'the keystore is deleted whether the job passed or not');
});

test('the android-apk job pins the same actions as the ci android job', () => {
  const apk = job(RELEASE, 'android-apk');
  const android = job(CI, 'android');
  const pins = (text) => new Map([...text.matchAll(/uses: ([\w/-]+)@(\S+)/g)].map((m) => [m[1], m[2]]));
  const ours = pins(apk);
  const theirs = pins(android);
  for (const action of ['actions/checkout', 'actions/setup-java', 'gradle/actions/setup-gradle', 'actions/upload-artifact']) {
    assert.ok(ours.has(action), `android-apk does not use ${action}`);
    assert.equal(ours.get(action), theirs.get(action), `${action} is pinned differently from ci.yml`);
  }
  for (const [action, ref] of pins(RELEASE)) {
    assert.match(ref, /^[0-9a-f]{40}$/, `${action} is not pinned to a full commit SHA`);
  }
  assert.match(apk, /distribution: temurin\n\s+java-version: '21'/);
});

test('the release waits for the signed APK and publishes it with its checksum', () => {
  const release = job(RELEASE, 'release');
  assert.match(release, /^ {4}needs: \[verify, android-apk\]$/m);
  const uploaded = job(RELEASE, 'android-apk').match(/name: (ideaforge-[\w-]+)\n\s+path: dist\/\*\.apk/)?.[1];
  assert.ok(uploaded, 'android-apk uploads no APK artifact');
  assert.match(release, new RegExp(String.raw`actions/download-artifact@[0-9a-f]{40}[^\n]*\n\s+with:\n\s+name: ${uploaded}\n\s+path: dist`));
  assert.match(release, /sha256sum "\$\{NAME\}\.tar\.gz" "\$\{NAME\}\.zip" "\$\{NAME\}\.apk" > SHA256SUMS/);
  assert.match(release, /gh release create[\s\S]*dist\/\*\.apk[\s\S]*dist\/SHA256SUMS/);
});

test('release signing material is ignored by git and docker', () => {
  const gitignore = readFileSync(join(ROOT, '.gitignore'), 'utf8').split(/\r?\n/);
  const dockerignore = readFileSync(join(ROOT, '.dockerignore'), 'utf8').split(/\r?\n/);
  for (const pattern of ['android/keystore.properties', '*.jks', '*.keystore']) {
    assert.ok(gitignore.includes(pattern), `.gitignore lost ${pattern}`);
  }
  for (const pattern of ['**/*.jks', '**/*.keystore', '**/keystore.properties']) {
    assert.ok(dockerignore.includes(pattern), `.dockerignore lost ${pattern}`);
  }
});
