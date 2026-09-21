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
import { STT_PRESETS } from '../src/voice/transcribe.js';

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
  'providers.html',
  'local-models-and-docker.html',
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

test('the public Markdown surfaces have no broken local links', () => {
  const files = [
    'README.md',
    'SECURITY.md',
    'CONTRIBUTING.md',
    'docs/ARCHITECTURE.md',
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
});

test('the guide uses local assets and the README points readers to it', () => {
  assert.match(README, /https:\/\/jaypetez\.github\.io\/ideaforge\/guide\//);
  const stylesheet = join(GUIDE_DIR, 'assets', 'guide.css');
  assert.ok(existsSync(stylesheet));
  const css = readFileSync(stylesheet, 'utf8');
  assert.doesNotMatch(css, /url\(\s*['"]?https?:/i, 'guide CSS must not load remote assets');
  assert.match(css, /prefers-color-scheme:\s*dark/);
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
  for (const m of doc.matchAll(/`((?:src|test|tools|docs|\.github)\/[^`\s]+\.\w+)`/g)) {
    paths.add(m[1]);
  }
  assert.ok(paths.size >= 15, `only found ${paths.size} cited paths; the regex stopped matching`);

  const missing = [...paths].filter((p) => !existsSync(join(ROOT, p)));
  assert.deepEqual(missing, [], `ARCHITECTURE.md cites files that do not exist: ${missing.join(', ')}`);
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
