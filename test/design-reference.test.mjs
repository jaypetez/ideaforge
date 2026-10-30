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
  'wrap-offer', 'voice-blocked', 'result-failed', 'long-content', 'large-text'];
const nativeStates = ['native-onboarding', 'native-start', 'native-interview',
  'native-voice-listening', 'native-result', 'native-settings'];
const nativeEdges = ['ideas-empty', 'ideas-unavailable', 'interview-busy', 'save-failure',
  'result-failed', 'long-content', 'voice-paused', 'voice-recovering', 'voice-blocked',
  'settings-unsaved', 'connection-error', 'large-text'].map((state) => `native-${state}`);
const read = (path) => readFileSync(path, 'utf8');
const boards = (html) => [...html.matchAll(/<article class="reference-board"([^>]*)>([\s\S]*?)<\/article>/g)]
  .map((match) => ({
    state: match[1].match(/data-reference-state="([^"]+)"/)?.[1],
    theme: match[1].match(/data-reference-theme="([^"]+)"/)?.[1],
    platform: match[1].match(/data-reference-platform="([^"]+)"/)?.[1],
    width: Number(match[1].match(/data-reference-width="(\d+)"/)?.[1]),
    content: match[2],
  }));

test('reference boards remain distinct from the delivered foundations, adaptive shells and pending screens', () => {
  assert.ok(existsSync(HTML), 'missing static Focus Studio reference boards');
  assert.ok(existsSync(CSS), 'missing local reference stylesheet');
  const design = read(join(ROOT, 'docs', 'DESIGN.md'));
  assert.match(design, /Focus Studio/);
  assert.match(design.replace(/\s+/g, ' '), /not (?:yet )?implemented/i);
  assert.match(design, /\(design\/focus-studio\.html/);
  assert.match(design, /Current shipped tokens/);
  assert.match(design, /Current native fallback/);
  assert.doesNotMatch(design, /not yet migrated|earlier scheme until/);
  assert.match(design, /#F5F7FA/);
  const status = design.replace(/\s+/g, ' ');
  assert.match(status, /foundations and adaptive web \(#191\) and native \(#192\) shells, progressive web Start \(#193\), compact web Ideas \(#197\), native Ideas \(#198\), native Interview \(#200\), native voice presentation \(#202\) and native Start \(#195\), Settings \(#196\) and Result \(#204\) are implemented here/);
  assert.match(status, /guide's own Focus Studio migration is \*\*not yet implemented\*\*/);
  assert.match(status, /Forge Studio proposal/);
  assert.match(status, /owner-approved direction/);
  assert.match(status, /not yet implemented in the apps/);
});

test('DESIGN records owning web and native fallback roles and their composites exactly', () => {
  const design = read(join(ROOT, 'docs', 'DESIGN.md'));
  const web = design.slice(design.indexOf('## Colour roles'), design.indexOf('### Current native fallback'));
  const css = read(join(ROOT, 'src', 'ui', 'app.css'));
  const roles = [...css.matchAll(/(--[\w-]+):\s*light-dark\((#[\da-f]{6}),\s*(#[\da-f]{6})\)/gi)];
  assert.ok(roles.length >= 12, 'must check the full palette, not a single convenient role');
  for (const [, name, light, dark] of roles) {
    const row = web.split('\n').find((line) => line.startsWith(`| \`${name}\` |`));
    assert.ok(row, `DESIGN omits ${name}`);
    assert.ok(row.toLowerCase().includes(`\`${light.toLowerCase()}\``)
      && row.toLowerCase().includes(`\`${dark.toLowerCase()}\``), `DESIGN drifts from ${name}`);
  }
  const nativeTable = design.slice(design.indexOf('### Current native fallback'), design.indexOf('## Theme choice'));
  const native = read(join(ROOT, 'android', 'app', 'src', 'main', 'kotlin', 'io', 'github',
    'jaypetez', 'ideaforge', 'ui', 'theme', 'Theme.kt'));
  const mappings = [
    ['ground', 'surfaceContainerLow'], ['lowest', 'surfaceContainerLowest'],
    ['container', 'surfaceContainer'], ['high', 'surfaceContainerHigh'],
    ['onSurface', 'onSurface'], ['onSurfaceVariant', 'onSurfaceVariant'],
    ['outline', 'outline'],
    ['primary', 'primary'], ['onPrimary', 'onPrimary'], ['error', 'error'],
  ];
  for (const [name, role] of mappings) {
    const values = [...native.matchAll(new RegExp(`${name} = Color\\(0xFF([\\dA-F]{6})\\)`, 'g'))];
    assert.equal(values.length, 2, `must read both native ${name} owners`);
    const row = nativeTable.split('\n').find((line) => line.startsWith(`| \`${role}\``));
    assert.ok(row && values.every((match) => row.includes(`#${match[1]}`)), `native ${role} table drift`);
  }
  for (const name of ['success', 'warning']) {
    const values = ['Light', 'Dark'].map((theme) => {
      const value = native.match(new RegExp(`val ${name}${theme} = Color\\(0xFF([\\dA-F]{6})\\)`))?.[1];
      assert.ok(value, `must read native ${name}${theme}`);
      return value;
    });
    const row = nativeTable.split('\n').find((line) => line.startsWith(`| \`ExtraColors.${name}\``));
    assert.ok(row && values.every((value) => row.includes(`#${value}`)), `native ${name} table drift`);
  }
  assert.match(native, /val primaryContainer = primary\.copy\(alpha = 0\.10f\)\.compositeOver\(ground\)/);
  assert.match(native, /val errorContainer = error\.copy\(alpha = 0\.12f\)\.compositeOver\(ground\)/);
  const scheme = native.match(/return base\.copy\(([\s\S]*?)\n {4}\)/)?.[1];
  assert.ok(scheme, 'must inspect the actual fallback ColorScheme mapping');
  assert.match(scheme, /outlineVariant = onSurface\.copy\(alpha = 0\.16f\)\.compositeOver\(ground\)/);
  assert.match(scheme, /surfaceTint = primary/);
  assert.match(scheme, /onError = onPrimary/);
  assert.match(scheme, /inverseSurface = if \(dark\) LightTokens\.high else DarkTokens\.high/);
  assert.match(scheme, /inverseOnSurface = if \(dark\) LightTokens\.onSurface else DarkTokens\.onSurface/);
  assert.match(scheme, /inversePrimary = if \(dark\) LightTokens\.primary else DarkTokens\.primary/);
  assert.match(nativeTable, /\| `outlineVariant` \| ink at 16% over ground \| ink at 16% over ground \|/);
  assert.match(nativeTable, /`primaryContainer` \(10%\), `errorContainer` \(12%\)/);
  assert.match(nativeTable, /surfaceTint/);
  assert.match(nativeTable, /Inverse roles/);
});

test('DESIGN records native content measures and all system-family typography roles', () => {
  const design = read(join(ROOT, 'docs', 'DESIGN.md'));
  const directory = join(ROOT, 'android', 'app', 'src', 'main', 'kotlin', 'io', 'github',
    'jaypetez', 'ideaforge', 'ui', 'theme');
  const roles = read(join(directory, 'Roles.kt'));
  const widths = roles.match(/object IdeaForgeWidths \{([\s\S]*?)\n\}/)?.[1];
  assert.ok(widths, 'native content-width owner is missing');
  const values = [...widths.matchAll(/val (\w+) = (\d+)\.dp/g)];
  assert.equal(values.length, 10, 'check every native content measure, table/index/support and adaptive rail');
  for (const [, name, value] of values) {
    assert.ok(design.includes(`| \`${name}\` | ${value}dp |`), `native ${name} width drift`);
  }
  const native = read(join(directory, 'Theme.kt'));
  const types = [...native.matchAll(/(\w+) = t\.\1\.role\(([\d.]+)f, ([\d.]+)f, FontWeight\.(\w+)\)/g)];
  assert.equal(types.length, 15, 'all Material typography roles must be explicit');
  assert.match(native, /private val AppFont = FontFamily\.Default/);
  const typeTable = design.slice(design.indexOf('All 15 native'), design.indexOf('## Spacing and shape'));
  for (const [, name, size, line] of types) {
    const row = typeTable.split('\n').find((text) => text.startsWith('|') && text.includes(`\`${name}\``));
    assert.ok(row && row.includes(`${size}sp / ${line}`), `native ${name} typography drift`);
  }
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
    for (const state of nativeStates) for (const width of [390, 840]) {
      assert.equal(all.filter((board) => board.state === state && board.theme === theme
        && board.width === width && board.platform === 'android').length, 1,
      `${state}/${theme}/${width} native proposal is missing`);
    }
    for (const state of nativeEdges) {
      assert.equal(all.filter((board) => board.state === state && board.theme === theme
        && board.width === 390 && board.platform === 'android').length, 1,
      `${state}/${theme} native recovery proposal is missing`);
    }
  }
  assert.equal(all.length, webStates.length * 4 + edgeStates.length * 2 + 4
    + nativeStates.length * 4 + nativeEdges.length * 2);
});

test('reference accessible names agree with their platform and logical window', () => {
  for (const board of boards(read(HTML))) {
    const label = board.content.match(/class="reference-viewport"[^>]+aria-label="([^"]+)"/)?.[1];
    const height = board.width === 390 ? 844 : 900;
    assert.match(label || '', new RegExp(
      `\\b${board.platform} ${board.width}x${height} ${board.theme}\\b`, 'i'),
      `${board.state}/${board.theme}/${board.width} announces the wrong platform or window`);
  }
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

test('original brand studies remain local references rather than shipped or remotely loaded artwork', () => {
  const html = read(HTML);
  const css = read(CSS);
  for (const name of ['forge-mark.svg', 'forge-anvil.svg', 'idea-workbench.svg']) {
    const svg = read(join(DIR, name));
    assert.match(svg, /<svg\b/);
    assert.doesNotMatch(svg, /<(?:script|foreignObject|image)\b|\bon\w+\s*=|\b(?:href|src)=/i);
    assert.ok(html.includes(name) || css.includes(name), `unreferenced artwork ${name}`);
  }
  for (const match of css.matchAll(/url\(["']?([^"')]+)["']?\)/g)) {
    assert.ok(existsSync(resolve(DIR, match[1])), `missing local reference artwork ${match[1]}`);
  }
  assert.match(html, /owner-approved direction, not yet shipped/i);
  assert.match(html, /not Android adaptive-icon or PWA safe-zone certification/);
  assert.match(css, /\[data-reference-state="ideas-empty"\] \.ref-empty::before/);
  assert.doesNotMatch(css, /(?:^|\n)\.ref-empty::before/);
});

test('Ideas references separate a labeled coverage measure from write-up availability', () => {
  const all = boards(read(HTML)).filter((board) => ['ideas', 'native-ideas'].includes(board.state));
  assert.equal(all.length, 8);
  for (const board of all) {
    const values = [...board.content.matchAll(/<span>(\d+)%<\/span><\/span>\s*<meter min="0" max="100" value="(\d+)" aria-label="Exploration coverage" aria-valuetext="[^"]*not write-up completion"/g)];
    assert.equal(values.length, 6, 'every example row has a labeled real-valued measure');
    for (const [, visible, value] of values) assert.equal(visible, value, 'the graphic and text agree');
    assert.ok(new Set(values.map((match) => match[1])).size > 1, 'mixed progress, not a constant bar');
    assert.match(board.content, /ref-writeup-state[^>]*>Interviewing/);
    assert.match(board.content, /ref-writeup-state[^>]*>Write-up available/);
    assert.match(board.content, /ref-writeup-state[^>]*>No write-up yet/);
  }
});

test('native proposals retain platform-specific appearance and output behavior', () => {
  const all = boards(read(HTML));
  for (const board of all.filter((entry) => entry.state === 'native-settings')) {
    assert.match(board.content, /IdeaForge brand \(default\)/);
    assert.match(board.content, /wallpaper-opt-in/);
    assert.match(board.content, /separate from light or dark appearance/);
    assert.match(board.content, /Device voice/);
    assert.doesNotMatch(board.content, /Browser voice/);
  }
  for (const board of all.filter((entry) => entry.state === 'native-result')) {
    assert.doesNotMatch(board.content, /download-markdown|Download \.md|assumes file sharing/);
    assert.match(board.content, /data-action="save-markdown"/);
    assert.match(board.content, /data-action="copy-markdown"/);
    assert.match(board.content, /data-action="share-file"/);
  }
});

test('voice-first and direct output action priorities are unambiguous in the references', () => {
  const all = boards(read(HTML));
  for (const board of all.filter((entry) => ['start-ready', 'native-start'].includes(entry.state))) {
    assert.match(board.content, /class="ref-button primary"[^>]*data-action="start-voice"[^>]*>Start with voice/);
    assert.match(board.content, /data-action="start-type"[^>]*>Type instead/);
  }
  for (const board of all.filter((entry) => entry.state === 'result')) {
    const primary = board.content.match(/class="ref-button primary"[^>]*data-action="([^"]+)"/)?.[1];
    assert.equal(primary, board.width === 390 ? 'share-file' : 'copy-markdown');
    assert.match(board.content, /data-action="copy-markdown"/);
    if (board.width === 1280) assert.match(board.content, /data-action="download-markdown"/);
  }
  for (const board of all.filter((entry) => entry.state.includes('voice-'))) {
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
  for (const board of all.filter((entry) => ['result-failed', 'native-result-failed'].includes(entry.state))) {
    assert.match(board.content, /The refined prompt was not generated/);
    assert.match(board.content, /transcript/);
    assert.match(board.content, /data-action="retry-writeup"/);
    assert.match(board.content, /No extra answer is needed/);
    assert.doesNotMatch(board.content, /Otherwise Copy is primary/i,
      'Share capability cannot promote an export above failed-generation recovery');
    const primary = [...board.content.matchAll(/class="ref-button primary"[^>]*data-action="([^"]+)"/g)];
    assert.deepEqual(primary.map((match) => match[1]), ['retry-writeup'],
      'recovering the missing write-up is the sole primary, not a successful-looking export');
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
