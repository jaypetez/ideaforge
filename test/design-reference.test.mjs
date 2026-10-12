import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { publishableFiles } from '../tools/assemble-site.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DIR = join(ROOT, 'docs', 'design');
const HTML = join(DIR, 'focus-studio.html');
const CSS = join(DIR, 'focus-studio.css');
const webStates = ['start-provider', 'start-ready', 'ideas', 'interview', 'voice-listening',
  'voice-paused', 'voice-recovering', 'result', 'settings'];
const edgeStates = ['ideas-empty', 'ideas-filtered', 'ideas-unavailable', 'connection-checking',
  'connection-error', 'model-unavailable', 'settings-unsaved', 'interview-busy', 'save-failure',
  'wrap-offer', 'voice-blocked', 'result-failed', 'long-content'];
const read = (path) => readFileSync(path, 'utf8');
const boards = (html) => [...html.matchAll(/<article class="reference-board"([^>]*)>([\s\S]*?)<\/article>/g)]
  .map((match) => ({
    state: match[1].match(/data-reference-state="([^"]+)"/)?.[1],
    theme: match[1].match(/data-reference-theme="([^"]+)"/)?.[1],
    platform: match[1].match(/data-reference-platform="([^"]+)"/)?.[1],
    width: Number(match[1].match(/data-reference-width="(\d+)"/)?.[1]),
    content: match[2],
  }));

test('the proposed design has local reference assets and explicitly remains unshipped', () => {
  assert.ok(existsSync(HTML), 'missing static Focus Studio reference boards');
  assert.ok(existsSync(CSS), 'missing local reference stylesheet');
  const design = read(join(ROOT, 'docs', 'DESIGN.md'));
  assert.match(design, /Focus Studio/);
  assert.match(design.replace(/\s+/g, ' '), /not (?:yet )?implemented/i);
  assert.match(design, /\(design\/focus-studio\.html/);
  assert.match(design, /Current shipped tokens/);
  assert.match(design, /#f5f6f2/);
  assert.match(design, /#F5F7FA/);
});

test('every main web reference has both themes at actual 390 and 1280 logical widths', () => {
  const all = boards(read(HTML));
  for (const state of webStates) {
    for (const theme of ['light', 'dark']) {
      for (const width of [390, 1280]) {
        assert.equal(all.filter((board) => board.state === state && board.theme === theme
          && board.width === width && board.platform === 'web').length, 1,
        `${state}/${theme}/${width} must have exactly one reference`);
      }
    }
  }
  for (const state of edgeStates) {
    for (const theme of ['light', 'dark']) {
      assert.equal(all.filter((board) => board.state === state && board.theme === theme
        && board.width === 390 && board.platform === 'web').length, 1,
      `${state}/${theme} recovery/long-content reference is missing`);
    }
  }
  for (const theme of ['light', 'dark']) {
    for (const width of [390, 840]) {
      assert.equal(all.filter((board) => board.state === 'native-ideas' && board.theme === theme
        && board.width === width && board.platform === 'android').length, 1);
    }
  }
  assert.equal(all.length, webStates.length * 4 + edgeStates.length * 2 + 4);
});

test('reference boards cannot run app effects or load third-party resources', () => {
  const html = read(HTML);
  const css = read(CSS);
  assert.match(html, /Design reference[^<]*not the application/i);
  assert.match(html, /default-src 'none'/);
  assert.match(html, /style-src 'self'/);
  assert.doesNotMatch(html, /<(?:script|style)\b/i);
  assert.doesNotMatch(html, /\bon\w+\s*=/i);
  assert.doesNotMatch(html, /<(?:link|img|source|iframe)\b[^>]+(?:href|src|srcset)="https?:/i);
  assert.doesNotMatch(css, /@import|https?:|@font-face|animation\s*:/i);
  assert.match(html, /system fonts/);
  assert.match(html, /dynamic colour/);
  assert.match(html, /not (?:an? )?(?:emulator|physical)[^<]*capture/i);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  assert.equal(new Set(ids).size, ids.length, 'duplicated reference/control IDs');
  const iconCss = read(join(ROOT, 'src', 'ui', 'icons.css'));
  const iconNames = new Set([...iconCss.matchAll(/\[data-icon="([^"]+)"\]/g)].map((match) => match[1]));
  for (const match of html.matchAll(/\bdata-icon="([^"]+)"/g)) {
    assert.ok(iconNames.has(match[1]), `reference uses an unshipped icon ${match[1]}`);
  }
  for (const match of html.matchAll(/\b(?:href|src)="([^"]+)"/g)) {
    if (/^(?:https?:|mailto:)/.test(match[1])) continue;
    const [path, anchor] = match[1].split('#');
    const target = path ? resolve(dirname(HTML), path) : HTML;
    assert.ok(existsSync(target), `missing local asset ${match[1]}`);
    if (anchor && target === HTML) assert.ok(ids.includes(anchor), `missing reference anchor ${anchor}`);
  }
});

test('the design drawings are not copied into the assembled application', async () => {
  const files = await publishableFiles();
  assert.ok(files.length > 0);
  assert.ok(!files.some((path) => path.startsWith('docs/design/')));
});

test('voice-first and direct output action priorities are unambiguous in the references', () => {
  const all = boards(read(HTML));
  for (const board of all.filter((entry) => entry.state === 'start-ready')) {
    assert.match(board.content, /class="ref-button primary"[^>]*data-action="start-voice"[^>]*>Start with voice/);
    assert.match(board.content, /data-action="start-type"[^>]*>Type instead/);
  }
  for (const board of all.filter((entry) => entry.state === 'result')) {
    const primary = board.content.match(/class="ref-button primary"[^>]*data-action="([^"]+)"/)?.[1];
    assert.equal(primary, board.width === 390 ? 'share-file' : 'copy-markdown');
    assert.match(board.content, /data-action="copy-markdown"/);
    if (board.width === 1280) assert.match(board.content, /data-action="download-markdown"/);
  }
  for (const board of all.filter((entry) => entry.state.startsWith('voice-'))) {
    assert.match(board.content, /No level reading/);
    assert.doesNotMatch(board.content, /waveform|ref-bars|[<>]audio\b/i);
    assert.match(board.content, /data-action="exit-voice"/);
  }
});

test('reference recovery states do not masquerade as zero ideas or a completed AI write-up', () => {
  const all = boards(read(HTML));
  for (const board of all.filter((entry) => entry.state === 'ideas-unavailable')) {
    assert.match(board.content, /Ideas unavailable/);
    assert.doesNotMatch(board.content, />No ideas yet</);
  }
  for (const board of all.filter((entry) => entry.state === 'ideas-filtered')) {
    assert.match(board.content, /No ideas match/);
    assert.match(board.content, /Clear filters/);
  }
  for (const board of all.filter((entry) => entry.state === 'ideas')) {
    assert.match(board.content, /No write-up yet/);
    assert.doesNotMatch(board.content, /could not be generated/);
  }
  for (const board of all.filter((entry) => entry.state === 'save-failure')) {
    assert.match(board.content, /Not saved to this device/);
    assert.match(board.content, /Export before closing/);
  }
  for (const board of all.filter((entry) => entry.state === 'result-failed')) {
    assert.match(board.content, /The refined prompt was not generated/);
    assert.match(board.content, /transcript/);
  }
});

function luminance(hex) {
  const channels = hex.match(/[0-9a-f]{2}/gi).map((part) => parseInt(part, 16) / 255)
    .map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return channels.reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
}

test('the proposed opaque palette meets contrast floors instead of only looking calm', () => {
  const css = read(CSS);
  for (const theme of ['light', 'dark']) {
    const block = css.match(new RegExp(`\\[data-reference-theme="${theme}"\\] \\{([^}]+)\\}`))?.[1];
    assert.ok(block, `${theme} palette missing`);
    const tokens = Object.fromEntries([...block.matchAll(/--ref-([a-z-]+):\s*(#[0-9a-f]{6})/gi)]
      .map((match) => [match[1], match[2]]));
    const contrast = (a, b) => {
      const x = luminance(tokens[a]);
      const y = luminance(tokens[b]);
      return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    };
    for (const surface of ['ground', 'surface', 'container', 'raised']) {
      for (const text of ['ink', 'muted', 'primary', 'error', 'warning']) {
        assert.ok(contrast(text, surface) >= 4.5, `${theme} ${text}/${surface}`);
      }
      assert.ok(contrast('outline', surface) >= 3, `${theme} outline/${surface}`);
    }
    assert.ok(contrast('on-primary', 'primary') >= 4.5, `${theme} primary button text`);
  }
});
