// Public documentation makes claims about code, commands and generated assets. These checks
// target facts the repository can disprove mechanically; they are not a prose or style lint.
// Narrative review remains the job of the shared documentation skills.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { DIMENSIONS } from '../src/core/dimensions.js';
import { HARD_TURN_CEILING, SOFT_TURN_CEILING } from '../src/core/engine.js';
import { PROVIDER_CHOICES } from '../src/providers/index.js';
import { TTS_PRESETS } from '../src/providers/tts.js';
import { STT_PRESETS } from '../src/voice/transcribe.js';
import { checkPrivacyPolicy } from '../tools/check-privacy-policy.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const README = readFileSync(join(ROOT, 'README.md'), 'utf8');
const PACKAGE = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'));
const GUIDE_DIR = join(ROOT, 'guide');
const GUIDE_PAGES = [
  'index.html',
  'getting-started.html',
  'interviews-and-coverage.html',
  'ideas-library.html',
  'mobile-and-voice.html',
  'android.html',
  'providers.html',
  'local-models-and-docker.html',
  'privacy-policy.html',
  'privacy-and-security.html',
  'troubleshooting.html',
  'development-and-delivery.html',
];
const GUIDE_HREFS = GUIDE_PAGES.map((page) => page === 'index.html' ? './' : `./${page}`);
const guide = (page) => readFileSync(join(GUIDE_DIR, page), 'utf8');

/** Every local target the README points at: markdown links, img src, and picture srcset. */
function localTargets(md) {
  const found = new Set();
  const add = (raw) => {
    if (!raw) return;
    const path = raw.trim().split('#')[0];
    // Skip absolute URLs, anchors, and mailto.
    if (!path || /^(https?:|mailto:|#)/.test(path)) return;
    found.add(path);
  };
  for (const m of md.matchAll(/\]\(([^)\s]+)\)/g)) add(m[1]);
  for (const m of md.matchAll(/\bsrc="([^"]+)"/g)) add(m[1]);
  for (const m of md.matchAll(/\bsrcset="([^"]+)"/g)) add(m[1]);
  return [...found];
}

function htmlTargets(file, html) {
  const targets = [];
  const add = (raw) => {
    if (!raw) return;
    for (const value of raw.split(',').map((part) => part.trim().split(/\s+/)[0])) {
      if (!value || /^(?:https?:|mailto:|tel:|data:)/.test(value)) continue;
      const [path, anchor = ''] = value.split('#');
      let target = path ? resolve(dirname(file), path) : file;
      if (path.endsWith('/') || !path) {
        if (path.endsWith('/')) target = join(target, 'index.html');
      }
      targets.push({ raw: value, target, anchor });
    }
  };
  for (const match of html.matchAll(/\b(?:href|src)="([^"]+)"/g)) add(match[1]);
  for (const match of html.matchAll(/\bsrcset="([^"]+)"/g)) add(match[1]);
  return targets;
}

function ids(html) {
  return [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
}

function navHrefs(html) {
  const nav = html.match(/<nav class="docs-nav"[\s\S]*?<\/nav>/);
  assert.ok(nav, 'guide page has no .docs-nav');
  return [...nav[0].matchAll(/\bhref="([^"]+)"/g)].map((match) => match[1]);
}

test('every file the README links to or shows actually exists', () => {
  const targets = localTargets(README);
  // A README that references nothing local means the regexes above stopped matching, which
  // would make this whole test pass while checking nothing.
  assert.ok(targets.length >= 10, `only found ${targets.length} local targets`);

  const missing = targets.filter((p) => !existsSync(join(ROOT, p)));
  assert.deepEqual(missing, [], `README points at files that do not exist: ${missing.join(', ')}`);
});

test('adaptive web shell measurements stay tied to their CSS owner', () => {
  const css = readFileSync(join(ROOT, 'src', 'ui', 'app.css'), 'utf8');
  const design = readFileSync(join(ROOT, 'docs', 'DESIGN.md'), 'utf8');
  const sourceMap = readFileSync(join(ROOT, '.claude', 'skills', 'update-documentation',
    'references', 'source-map.md'), 'utf8');
  for (const token of ['rail-compact', 'rail-expanded']) {
    const width = css.match(new RegExp(`--${token}:\\s*(\\d+)px;`))?.[1];
    assert.ok(width, `missing --${token} width`);
    assert.ok(design.includes(`\`--${token}\` is ${width}px`), `DESIGN has a stale --${token} width`);
    assert.match(sourceMap, token === 'rail-compact'
      ? new RegExp(`from 600px a ${width}px labelled rail`)
      : new RegExp(`expands to ${width}px at 1200px`));
  }
  for (const width of [600, 1200]) {
    assert.ok(css.includes(`@media (min-width: ${width}px)`), `missing ${width}px rail query`);
    assert.ok(design.includes(`${width}px`), `DESIGN has a stale ${width}px boundary`);
    assert.match(guide('getting-started.html'), new RegExp(`\\b${width}\\b`));
  }
});

test('the public Markdown surfaces have no broken local links', () => {
  const files = [
    'README.md',
    'SECURITY.md',
    'CONTRIBUTING.md',
    'docs/ARCHITECTURE.md',
    'docs/DESIGN.md',
  ];
  const missing = [];
  for (const path of files) {
    const file = join(ROOT, path);
    for (const target of localTargets(readFileSync(file, 'utf8'))) {
      const resolved = resolve(dirname(file), target);
      if (!existsSync(resolved)) missing.push(`${path} -> ${target}`);
    }
  }
  assert.deepEqual(missing, []);
});

test('the guide inventory, navigation and page shell stay complete', () => {
  const actual = readdirSync(GUIDE_DIR)
    .filter((file) => file.endsWith('.html'))
    .sort();
  assert.deepEqual(actual, [...GUIDE_PAGES].sort());

  for (const page of GUIDE_PAGES) {
    const html = guide(page);
    const pageIds = ids(html);
    assert.match(html, /^<!doctype html>/i, `${page}: missing doctype`);
    assert.match(html, /<html lang="en">/, `${page}: missing language`);
    assert.match(html, /<title>[^<]+<\/title>/, `${page}: missing title`);
    assert.match(html, /<meta name="description"[\s\S]*?content="[^"]+"/,
      `${page}: missing description`);
    assert.equal([...html.matchAll(/<h1\b/g)].length, 1, `${page}: expected one h1`);
    assert.match(html, /<a class="skip-link" href="#main">/,
      `${page}: missing skip link`);
    assert.match(html, /<main id="main">/, `${page}: missing main landmark`);
    assert.equal(new Set(pageIds).size, pageIds.length, `${page}: duplicate id`);
    assert.deepEqual(navHrefs(html), GUIDE_HREFS, `${page}: navigation drift`);
    assert.match(html, /<ul tabindex="0" aria-label="Guide pages">/,
      `${page}: the bounded navigation list needs an accessible keyboard scroll target`);

    const current = [...html.matchAll(/<a href="([^"]+)" aria-current="page">/g)]
      .map((match) => match[1]);
    const expected = page === 'index.html' ? './' : `./${page}`;
    assert.deepEqual(current, [expected], `${page}: wrong current navigation item`);

    assert.match(html, /default-src 'none'/, `${page}: weak CSP`);
    assert.match(html, /style-src 'self'/, `${page}: stylesheet CSP drift`);
    assert.match(html, /img-src 'self' data:/, `${page}: image CSP drift`);
    assert.doesNotMatch(html, /<script\b/i, `${page}: guide must not require JavaScript`);
    assert.doesNotMatch(html, /<style\b/i, `${page}: styles belong in guide.css`);
    assert.doesNotMatch(html, /<(?:link|img|source)\b[^>]+(?:href|src|srcset)="https?:/i,
      `${page}: guide assets must stay local`);
    for (const image of html.matchAll(/<img\b[^>]*>/gi)) {
      assert.match(image[0], /\balt="[^"]*"/, `${page}: image without alt text`);
    }
  }
});

test('every guide link, image, stylesheet and anchor resolves', () => {
  const missing = [];
  for (const page of GUIDE_PAGES) {
    const file = join(GUIDE_DIR, page);
    const html = guide(page);
    for (const { raw, target, anchor } of htmlTargets(file, html)) {
      if (!existsSync(target)) {
        missing.push(`${page} -> ${raw}`);
        continue;
      }
      if (!anchor || !target.endsWith('.html')) continue;
      const targetIds = ids(readFileSync(target, 'utf8'));
      if (!targetIds.includes(anchor)) missing.push(`${page} -> ${raw} (missing anchor)`);
    }
  }
  assert.deepEqual(missing, []);
});

test('the privacy policy covers both platforms and the current provider choices', () => {
  const html = guide('privacy-policy.html');
  assert.match(html, /<title>Privacy policy\b/);
  for (const source of ['IndexedDB', 'localStorage', 'JSON', 'Keystore', 'DataStore']) {
    assert.ok(html.includes(source), `privacy policy omits ${source}`);
  }
  for (const provider of PROVIDER_CHOICES.filter((choice) => choice.needsKey)) {
    assert.ok(html.includes(provider.label), `privacy policy omits ${provider.label}`);
  }
  for (const preset of [...Object.values(STT_PRESETS), ...Object.values(TTS_PRESETS)]) {
    assert.ok(html.includes(preset.label), `privacy policy omits ${preset.label}`);
  }
  for (const section of ['developer', 'data-map', 'model-requests', 'voice', 'network',
    'retention', 'age', 'changes']) {
    assert.ok(html.includes(`id="${section}"`), `privacy policy omits ${section}`);
  }
  assert.match(html, /http:\/\//, 'privacy policy must explain plain-http local servers');
  assert.match(html, /18 or over/, 'privacy policy must state the intended adult audience');
  const contact = html.match(/<address id="privacy-contact" data-status="(pending|ready)">([\s\S]*?)<\/address>/);
  assert.ok(contact, 'privacy contact must explicitly be pending or confirmed');
  const result = checkPrivacyPolicy(html);
  assert.equal(result.ok, contact[1] === 'ready', result.message);
  if (contact[1] === 'pending') assert.match(contact[2], /PENDING maintainer confirmation/);
});

test('the privacy policy distinguishes local credentials and Pause by platform', () => {
  const html = guide('privacy-policy.html');
  const cell = (id) => html.match(new RegExp(`<td id="${id}">([\\s\\S]*?)</td>`))?.[1] || '';
  assert.match(cell('web-provider-settings'), /nonempty key[\s\S]*loopback server/);
  assert.match(cell('android-provider-settings'), /omit credentials/);
  const paragraph = (id) => html.match(new RegExp(`<p id="${id}">([\\s\\S]*?)</p>`))?.[1] || '';
  assert.match(paragraph('android-pause'), /cancels the capture and pending transcription/);
  assert.match(paragraph('android-pause'), /completed transcribed segments/);
  assert.match(paragraph('web-pause'), /may finish transcription/);
});

test('the guide only documents npm scripts that exist and keeps browser checks fail-closed', () => {
  let commands = 0;
  for (const page of GUIDE_PAGES) {
    const html = guide(page);
    for (const match of html.matchAll(/\bnpm run ([a-z][a-z0-9:-]*)/g)) {
      commands++;
      assert.ok(PACKAGE.scripts[match[1]], `${page}: unknown npm script ${match[1]}`);
    }
    assert.doesNotMatch(html, /npm run test:browser(?!:required)/,
      `${page}: skip-capable browser command`);
  }
  assert.ok(commands >= 6, `only found ${commands} documented npm commands`);
});

test('guide facts stay tied to the interview and provider registries', () => {
  const interview = guide('interviews-and-coverage.html');
  const providers = guide('providers.html');
  const mobile = guide('mobile-and-voice.html');

  assert.match(interview, /seven dimensions/i);
  for (const dimension of DIMENSIONS) {
    assert.ok(interview.includes(dimension.label),
      `interview guide omits dimension label: ${dimension.label}`);
  }
  const bank = DIMENSIONS.reduce((sum, dimension) => sum + dimension.bank.length, 0);
  assert.match(interview, new RegExp(`built-in bank of ${bank}\\b`));
  assert.match(interview, new RegExp(`soft ceiling[^\\d]+${SOFT_TURN_CEILING}\\b`, 'i'));
  assert.match(interview, new RegExp(`hard ceiling[^\\d]+${HARD_TURN_CEILING}\\b`, 'i'));

  for (const provider of PROVIDER_CHOICES) {
    assert.ok(providers.includes(provider.label),
      `provider guide omits registry label: ${provider.label}`);
  }
  for (const preset of Object.values(STT_PRESETS)) {
    assert.ok(mobile.includes(preset.label),
      `mobile guide omits transcription preset: ${preset.label}`);
  }
  for (const preset of Object.values(TTS_PRESETS)) {
    assert.ok(providers.includes(preset.model),
      `provider guide omits speech model: ${preset.model}`);
    assert.ok(providers.includes(preset.url),
      `provider guide omits speech endpoint: ${preset.url}`);
    for (const voice of preset.voices) {
      assert.ok(providers.toLowerCase().includes(`<strong>${voice}</strong>`),
        `provider guide omits speech voice: ${voice}`);
    }
  }
});

test('setup instructions use the disclosure names in the application', () => {
  const app = readFileSync(join(ROOT, 'index.html'), 'utf8');
  const sections = [
    ['model-settings', ['getting-started.html', 'providers.html', 'local-models-and-docker.html']],
    ['speech-options', ['getting-started.html', 'providers.html', 'mobile-and-voice.html',
      'troubleshooting.html']],
  ];
  for (const [id, pages] of sections) {
    const summary = app.match(new RegExp(
      `<details\\b[^>]*\\bid="${id}"[^>]*>\\s*<summary>([^<]+)</summary>`,
    ));
    assert.ok(summary, `application disclosure is missing: ${id}`);
    const label = summary[1].trim();
    assert.ok(README.includes(label), `README omits disclosure name: ${label}`);
    for (const page of pages) {
      assert.ok(guide(page).includes(label), `${page} omits disclosure name: ${label}`);
    }
  }
});

test('the guide uses local assets and the README points readers to it', () => {
  assert.match(README, /https:\/\/jaypetez\.github\.io\/ideaforge\/guide\//);
  const stylesheet = join(GUIDE_DIR, 'assets', 'guide.css');
  assert.ok(existsSync(stylesheet));
  const css = readFileSync(stylesheet, 'utf8');
  assert.doesNotMatch(css, /url\(\s*['"]?https?:/i, 'guide CSS must not load remote assets');
  assert.doesNotMatch(css, /@import\b/i, 'guide styles must not introduce a stylesheet dependency');
  assert.match(css, /prefers-color-scheme:\s*dark/);
  assert.match(css, /prefers-reduced-motion:\s*reduce/);
  assert.match(css, /@media \(max-width:\s*820px\)/);
  assert.match(css, /@media print/);

  const images = readdirSync(join(ROOT, 'docs', 'screenshots'))
    .filter((file) => file.endsWith('.png'));
  assert.ok(images.length >= 14, `only found ${images.length} generated screenshots`);
  const names = new Set(images);
  for (const name of images.filter((file) => file.endsWith('.light.png'))) {
    assert.ok(names.has(name.replace('.light.png', '.dark.png')),
      `missing dark screenshot for ${name}`);
  }
});

const SHOT_STEMS = [
  '01-setup', '02-first-question', '03-mid-interview', '04-coverage',
  '05-ready-to-wrap', '06-refined-prompt', '07-ideas-library',
  '08-voice-listening', '09-voice-paused', '10-settings', '11-library-wide',
];
const SCREENSHOTS_TOOL = readFileSync(join(ROOT, 'tools', 'screenshots.mjs'), 'utf8');
const APP_HTML = readFileSync(join(ROOT, 'index.html'), 'utf8');
const SOURCE_MAP = readFileSync(join(ROOT, '.claude', 'skills', 'update-documentation',
  'references', 'source-map.md'), 'utf8');

test('the generated inventory retains every manual and voice screenshot pair', () => {
  const stems = SHOT_STEMS;
  const expected = stems.flatMap((stem) => ['light', 'dark'].map((theme) => `${stem}.${theme}.png`));
  const missing = expected.filter((file) => !existsSync(join(ROOT, 'docs', 'screenshots', file)));
  assert.deepEqual(missing, [], `regenerate the missing screenshot pairs: ${missing.join(', ')}`);
});

test('the harness shot list, the expected inventory and the source map name the same stems', () => {
  // A capture added to tools/screenshots.mjs without a line here, or a stem the source map
  // never learned about, is how a screenshot ends up generated, committed and documented
  // nowhere — or documented and no longer generated.
  const shot = [...SCREENSHOTS_TOOL.matchAll(/\bapp\.shot\('([^']+)'/g)].map((m) => m[1]);
  assert.ok(shot.length >= 10, `only found ${shot.length} app.shot calls; the regex stopped matching`);
  assert.deepEqual([...shot].sort(), [...SHOT_STEMS].sort(),
    'tools/screenshots.mjs and SHOT_STEMS disagree about the screenshot inventory');
  const unmapped = SHOT_STEMS.filter((stem) => !SOURCE_MAP.includes(`\`${stem}\``));
  assert.deepEqual(unmapped, [], `the documentation source map omits: ${unmapped.join(', ')}`);
  const pages = GUIDE_PAGES.filter((page) => !SOURCE_MAP.includes(`\`${page}\``));
  assert.deepEqual(pages, [], `the documentation source map omits guide pages: ${pages.join(', ')}`);
});

test('every element id the screenshot harness hardcodes still exists in index.html', () => {
  // The harness finds about thirty controls by id. A renamed one makes it throw at best and
  // wait out a step timeout at worst; this fails in a second instead (CLAUDE.md, "The look").
  const found = new Set();
  for (const m of SCREENSHOTS_TOOL.matchAll(/getElementById\('([^']+)'\)/g)) found.add(m[1]);
  for (const m of SCREENSHOTS_TOOL.matchAll(/\.\.\.\[((?:'[\w-]+',?\s*)+)\]\s*\.map\(\(id\)/g)) {
    for (const id of m[1].matchAll(/'([\w-]+)'/g)) found.add(id[1]);
  }
  for (const m of SCREENSHOTS_TOOL.matchAll(/querySelector(?:All)?\('([^']+)'\)/g)) {
    for (const id of m[1].matchAll(/#([\w-]+)/g)) found.add(id[1]);
  }
  assert.ok(found.size >= 20, `only found ${found.size} ids; the patterns stopped matching`);
  const missing = [...found].filter((id) => !APP_HTML.includes(`id="${id}"`));
  assert.deepEqual(missing, [], `tools/screenshots.mjs looks for ids index.html lacks: ${missing.join(', ')}`);
});

test('the Settings groups the docs name are the ones the app shows', () => {
  // Settings' groups are named once, by the desktop index in index.html. The README and the
  // getting-started guide both list them and count them, which is what drifts when a group
  // is added or renamed.
  const nav = APP_HTML.match(/<nav class="settings-index[^>]*>([\s\S]*?)<\/nav>/);
  assert.ok(nav, 'index.html has no #settings-index');
  const groups = [...nav[1].matchAll(/<button[^>]*>([^<]+)<\/button>/g)].map((m) => m[1].trim());
  assert.ok(groups.length >= 3, `only found ${groups.length} settings groups`);
  const words = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];
  const count = words[groups.length];
  const start = guide('getting-started.html');
  for (const [name, text] of [['README', README], ['getting-started guide', start]]) {
    assert.ok(text.includes(` ${count} groups`), `${name} no longer counts ${count} settings groups`);
    for (const group of groups) {
      assert.ok(text.includes(group), `${name} omits the Settings group: ${group}`);
    }
  }
  for (const group of groups) {
    assert.ok(start.includes(`<strong>${group}</strong>`),
      `getting-started guide does not name the Settings group: ${group}`);
  }
});

test('the README and mobile guide show both voice states with theme pairs and useful alt text', () => {
  for (const [name, text, prefix] of [
    ['README', README, ''],
    ['mobile guide', guide('mobile-and-voice.html'), '../'],
  ]) {
    const pictures = [...text.matchAll(/<picture>[\s\S]*?<\/picture>/g)].map((match) => match[0]);
    for (const stem of ['08-voice-listening', '09-voice-paused']) {
      const picture = pictures.find((block) => block.includes(`src="${prefix}docs/screenshots/${stem}.light.png"`));
      assert.ok(picture, `${name} must show ${stem}, not just link to the file`);
      assert.ok(picture.includes(`srcset="${prefix}docs/screenshots/${stem}.dark.png"`),
        `${name} must use the dark ${stem} variant`);
      assert.match(picture, /media="\(prefers-color-scheme: dark\)"/);
      const alt = picture.match(/\balt="([^"]+)"/)?.[1] || '';
      assert.match(alt, stem.endsWith('listening') ? /listening/i : /paused/i,
        `${name}: alt text must identify the captured voice state`);
      assert.match(alt, /Exit/, `${name}: alt text must describe the visible exit control`);
    }
  }
});

test('the constants the README states match the source it states them about', () => {
  // Written as "seven dimensions" and "a built-in bank of 21", so match the words rather
  // than digits for the first one.
  assert.match(README, /seven dimensions/i);
  assert.equal(DIMENSIONS.length, 7, 'DIMENSIONS changed; the README still says seven');

  const bank = DIMENSIONS.reduce((n, d) => n + d.bank.length, 0);
  assert.match(
    README,
    new RegExp(`built-in bank of ${bank}\\b`),
    `the bank holds ${bank} questions; the README says otherwise`,
  );

  assert.match(
    README,
    new RegExp(`hard ceiling at ${HARD_TURN_CEILING}\\b`),
    `HARD_TURN_CEILING is ${HARD_TURN_CEILING}; the README says otherwise`,
  );
  assert.match(
    README,
    new RegExp(`soft ceiling at ${SOFT_TURN_CEILING} questions`),
    `SOFT_TURN_CEILING is ${SOFT_TURN_CEILING}; the README says otherwise`,
  );
});

test('every source file the architecture doc points at exists', () => {
  // The one mechanical check on docs/ARCHITECTURE.md, and deliberately the only one. That
  // file cites code by `file.js` plus a symbol name rather than a line number, so a moved
  // symbol cannot rot it — but a deleted or renamed FILE can, silently, and this catches
  // exactly that. Policing its prose would be the tax this suite's header warns about.
  const doc = readFileSync(join(ROOT, 'docs', 'ARCHITECTURE.md'), 'utf8');
  const paths = new Set();
  for (const m of doc.matchAll(/`((?:src|test|tools|docs|android|\.github)\/[^`\s]+\.\w+)`/g)) {
    paths.add(m[1]);
  }
  assert.ok(paths.size >= 15, `only found ${paths.size} cited paths; the regex stopped matching`);

  const missing = [...paths].filter((p) => !existsSync(join(ROOT, p)));
  assert.deepEqual(missing, [], `ARCHITECTURE.md cites files that do not exist: ${missing.join(', ')}`);
});

// The Android build's facts are owned by Gradle: SDK levels by android/app/build.gradle.kts,
// plugin versions by the version catalog, Gradle itself by the wrapper. The docs state them
// once each; this fails when a bump in any owner leaves a document describing the old value.
const ANDROID_DIR = join(ROOT, 'android');
const androidFile = (...parts) => readFileSync(join(ANDROID_DIR, ...parts), 'utf8');

function androidFacts() {
  const app = androidFile('app', 'build.gradle.kts');
  const catalog = androidFile('gradle', 'libs.versions.toml');
  const wrapper = androidFile('gradle', 'wrapper', 'gradle-wrapper.properties');
  const sdk = (name) => {
    const m = app.match(new RegExp(`^\\s*${name}\\s*=\\s*(\\d+)\\s*$`, 'm'));
    assert.ok(m, `android/app/build.gradle.kts has no literal ${name}`);
    return m[1];
  };
  const version = (name) => {
    const m = catalog.match(new RegExp(`^${name}\\s*=\\s*"([^"]+)"`, 'm'));
    assert.ok(m, `libs.versions.toml has no ${name} version`);
    return m[1];
  };
  const gradle = wrapper.match(/gradle-(\d+(?:\.\d+)+)-bin\.zip/);
  assert.ok(gradle, 'gradle-wrapper.properties names no Gradle distribution');
  const toolchains = new Set([...androidFile('core', 'build.gradle.kts').matchAll(/jvmToolchain\((\d+)\)/g),
    ...androidFile('providers', 'build.gradle.kts').matchAll(/jvmToolchain\((\d+)\)/g),
    ...app.matchAll(/jvmToolchain\((\d+)\)/g)].map((m) => m[1]));
  assert.equal(toolchains.size, 1, `modules disagree about the JDK toolchain: ${[...toolchains]}`);
  return {
    minSdk: sdk('minSdk'),
    targetSdk: sdk('targetSdk'),
    compileSdk: sdk('compileSdk'),
    agp: version('agp'),
    kotlin: version('kotlin'),
    gradle: gradle[1],
    jdk: [...toolchains][0],
  };
}

// Android release names for the API levels a minSdk could plausibly be. A minSdk outside
// this table fails loudly so whoever moved it also checks how the guide names it.
const ANDROID_RELEASE = { 26: '8.0', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 33: '13',
  34: '14', 35: '15', 36: '16' };

test('documented Android SDK levels and toolchain versions match the Gradle files', () => {
  const facts = androidFacts();
  const contributing = readFileSync(join(ROOT, 'CONTRIBUTING.md'), 'utf8');
  const page = guide('android.html');
  const surfaces = [
    ['CONTRIBUTING.md', contributing],
    ['guide/android.html', page],
    ['README.md', README],
    ['CLAUDE.md', readFileSync(join(ROOT, 'CLAUDE.md'), 'utf8')],
    ['docs/ARCHITECTURE.md', readFileSync(join(ROOT, 'docs', 'ARCHITECTURE.md'), 'utf8')],
    ['android-verify skill', readFileSync(join(ROOT, '.claude', 'skills', 'android-verify',
      'SKILL.md'), 'utf8')],
  ];

  // Wherever a document names one of these, it names the current value.
  const named = [
    ['minSdk', /\bminSdk (\d+)/g, facts.minSdk],
    ['targetSdk', /\btargetSdk (\d+)/g, facts.targetSdk],
    ['compileSdk', /\bcompileSdk (\d+)/g, facts.compileSdk],
    ['AGP', /\bAGP (\d+(?:\.\d+)+)/g, facts.agp],
    ['Kotlin', /\bKotlin (\d+\.\d+(?:\.\d+)*)/g, facts.kotlin],
    ['Gradle', /\bGradle (\d+\.\d+(?:\.\d+)*)/g, facts.gradle],
    ['JDK', /\bJDK (\d+)/g, facts.jdk],
  ];
  for (const [label, text] of surfaces) {
    for (const [name, pattern, expected] of named) {
      for (const m of text.matchAll(pattern)) {
        assert.equal(m[1], expected, `${label} says ${name} ${m[1]}; Gradle says ${expected}`);
      }
    }
  }

  // And the contributor guide states every one of them, so the check above has teeth.
  for (const [name, , expected] of named) {
    assert.ok(contributing.includes(`${name} ${expected}`),
      `CONTRIBUTING.md does not state ${name} ${expected}`);
  }

  const release = ANDROID_RELEASE[facts.minSdk];
  assert.ok(release, `minSdk ${facts.minSdk} has no Android release name in ANDROID_RELEASE`);
  for (const [label, text] of [['guide/android.html', page], ['README.md', README]]) {
    assert.ok(text.includes(`Android ${release} (API ${facts.minSdk}) or newer`),
      `${label} must say the app needs Android ${release} (API ${facts.minSdk}) or newer`);
    for (const m of text.matchAll(/\bAPI (\d+)\b/g)) {
      assert.equal(m[1], facts.minSdk, `${label} names API ${m[1]}; minSdk is ${facts.minSdk}`);
    }
  }
});

test('the documented debug application id and APK artifact match the build and CI', () => {
  const app = androidFile('app', 'build.gradle.kts');
  const id = app.match(/applicationId = "([^"]+)"/)?.[1];
  const suffix = app.match(/applicationIdSuffix = "([^"]+)"/)?.[1];
  assert.ok(id && suffix, 'android/app/build.gradle.kts lost its applicationId or debug suffix');
  const ci = readFileSync(join(ROOT, '.github', 'workflows', 'ci.yml'), 'utf8');
  const artifact = ci.match(/name: (\S+)\n\s+path: android\/app\/build\/outputs\/apk\/debug/)?.[1];
  assert.ok(artifact, 'ci.yml no longer uploads the debug APK');
  const retention = ci.match(new RegExp(`name: ${artifact}[\\s\\S]*?retention-days: (\\d+)`))?.[1];
  const page = guide('android.html');
  assert.ok(page.includes(`<code>${id}${suffix}</code>`), `guide omits the debug id ${id}${suffix}`);
  assert.ok(page.includes(`<code>${artifact}</code>`), `guide omits the CI artifact ${artifact}`);
  assert.ok(page.includes(`expire after ${retention} days`), `guide omits the ${retention}-day retention`);
});

test('the signed-APK instructions match the release workflow and SECURITY.md (#95)', () => {
  const app = androidFile('app', 'build.gradle.kts');
  const id = app.match(/applicationId = "([^"]+)"/)?.[1];
  const release = readFileSync(join(ROOT, '.github', 'workflows', 'release.yml'), 'utf8');
  const security = readFileSync(join(ROOT, 'SECURITY.md'), 'utf8');
  const apkName = release.match(/dist\/(ideaforge-)\$\{VERSION\}(\.apk)/);
  assert.ok(apkName, 'release.yml no longer names the APK ideaforge-${VERSION}.apk');
  const documented = `${apkName[1]}VERSION${apkName[2]}`;

  const section = security.split(/\r?\n## /).find((part) => part.startsWith('Release signing certificate'));
  assert.ok(section, 'SECURITY.md has no "Release signing certificate" section');
  for (const fact of ['Signer certificate SHA-256: `', 'apksigner verify --print-certs', 'SHA256SUMS',
    'never enters the repository', documented]) {
    assert.ok(section.includes(fact), `SECURITY.md's signing section omits ${fact}`);
  }

  const page = guide('android.html');
  assert.ok(page.includes(`<code>${id}</code>`), `guide omits the release id ${id}`);
  assert.ok(page.includes(`<code>${documented}</code>`), `guide omits ${documented}`);
  assert.ok(page.includes('SECURITY.md#release-signing-certificate'), 'guide does not link the pinned fingerprint');
  assert.ok(page.includes('apksigner verify --print-certs'), 'guide does not show how to verify the signer');
  for (const [name, text] of [
    ['guide/android.html', page],
    ['README.md', README],
    ['CONTRIBUTING.md', readFileSync(join(ROOT, 'CONTRIBUTING.md'), 'utf8')],
  ]) {
    assert.ok(!text.includes('TODO(#95)'), `${name} still has a TODO(#95) marker`);
  }
});

test('the device workflow runs the declared managed device, on demand, outside the ci gate', () => {
  const workflow = readFileSync(join(ROOT, '.github', 'workflows', 'android-device.yml'), 'utf8');
  const ci = readFileSync(join(ROOT, '.github', 'workflows', 'ci.yml'), 'utf8');
  const app = androidFile('app', 'build.gradle.kts');
  const device = workflow.match(/:app:(\w+)DebugAndroidTest/)?.[1];
  assert.ok(device, 'android-device.yml no longer runs a managed-device task');
  assert.ok(app.includes(`create<com.android.build.api.dsl.ManagedVirtualDevice>("${device}")`),
    `app/build.gradle.kts declares no managed device ${device}`);
  // The maintainer starts the first run by hand, so workflow_dispatch must stay.
  for (const trigger of ['workflow_dispatch:', 'schedule:', 'branches: [main]']) {
    assert.ok(workflow.includes(trigger), `android-device.yml lost its ${trigger} trigger`);
  }
  // Not required: the ci gate must never wait on an emulator.
  assert.ok(!/android-device|walkthrough/.test(ci), 'ci.yml must not depend on the device workflow');
  assert.equal((workflow.match(/#requestedPrivacyPolicyLinksAreReachable/g) || []).length, 2,
    'both privacy visual devices must select the actual test method');
  assert.equal((workflow.match(/inputs\.evidence == 'privacy-before' && '-Pandroid\.testInstrumentationRunnerArguments\.uxPrivacyProbe=before' \|\| inputs\.evidence == 'privacy' && '-Pandroid\.testInstrumentationRunnerArguments\.uxPrivacyProbe=after' \|\| ''/g) || []).length, 2,
    'before/after provenance must reach both privacy probes without an empty runner argument');
  assert.equal((workflow.match(/!startsWith\(inputs\.evidence, 'privacy'\) && !startsWith\(inputs\.evidence, 'consent'\) && format\('-Pandroid\.testInstrumentationRunnerArguments\.uxShellProbe=\{0\}'/g) || []).length, 2,
    'privacy and consent requests must not inherit the unrelated empty shell runner argument');
  assert.equal((workflow.match(/#requestedInferenceConsentIsProminent/g) || []).length, 2,
    'both consent visual devices must select the actual test method');
  assert.equal((workflow.match(/inputs\.evidence == 'consent-before' && '-Pandroid\.testInstrumentationRunnerArguments\.uxInferenceProbe=before' \|\| inputs\.evidence == 'consent' && '-Pandroid\.testInstrumentationRunnerArguments\.uxInferenceProbe=after' \|\| ''/g) || []).length, 2,
    'before/after provenance must reach both consent probes without an empty runner argument');
  for (const [name, text] of [
    ['CONTRIBUTING.md', readFileSync(join(ROOT, 'CONTRIBUTING.md'), 'utf8')],
    ['docs/ARCHITECTURE.md', readFileSync(join(ROOT, 'docs', 'ARCHITECTURE.md'), 'utf8')],
    ['guide/development-and-delivery.html', guide('development-and-delivery.html')],
    ['android-verify', readFileSync(join(ROOT, '.claude', 'skills', 'android-verify', 'SKILL.md'), 'utf8')],
  ]) {
    assert.ok(text.includes('android-device.yml'), `${name} omits android-device.yml`);
    assert.ok(text.includes(device), `${name} omits the managed device ${device}`);
  }
});

test('the worked example quoted in the README is the one on disk', () => {
  const example = join(ROOT, 'docs', 'examples', 'remember-names.md');
  assert.ok(existsSync(example), 'docs/examples/remember-names.md is missing; run npm run screenshots');
  const generated = readFileSync(example, 'utf8');

  // The README quotes an excerpt of the generated export. If someone edits the excerpt to
  // read better, or regenerates the example and the excerpt drifts, this catches it — the
  // point of the example is that it is real output, not that it reads well.
  const quoted = [
    '_2026-09-13 · 8 questions · coverage 86% · synthesised by Claude_',
    'It fails on sight "if it made me type while',
    '"Three seconds, one thumb, and it has to work with no signal."',
  ];
  for (const line of quoted) {
    assert.ok(README.includes(line), `the README no longer quotes: ${line}`);
    assert.ok(generated.includes(line), `the generated example no longer contains: ${line}`);
  }
});
