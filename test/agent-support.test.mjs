// Structural checks, not emulations of any client's loader. Native discovery is checked
// separately because client behavior changes faster than this repository should.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  existsSync, lstatSync, readFileSync, readdirSync,
} from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { playwrightMcpCommand } from '../tools/playwright-mcp.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const read = (...parts) => readFileSync(join(ROOT, ...parts), 'utf8');
const PACKAGE = JSON.parse(read('package.json'));
const BROWSER_CHECK_SOURCE = read('tools', 'browser-check.mjs');
const REQUIRED_BROWSER_SCRIPT = 'node tools/browser-check.mjs --required';
const ALL_TESTS_SCRIPT = 'npm test && npm run test:graph && npm run test:browser:required';
const EXPECTED_SKILLS = new Set([
  'add-provider',
  'android-verify',
  'audit-android-app',
  'change-voice-and-driving',
  'ideaforge-assist',
  'audit-ideaforge-codebase',
  'ideaforge-debt',
  'ideaforge-gain',
  'ideaforge-assist-help',
  'release',
  'review-ideaforge-documentation',
  'review-ideaforge-change',
  'review-ideaforge-before-pr',
  'update-documentation',
  'update-readme',
  'validate-local-model',
  'work-issues',
]);
const EXPLICIT_SKILLS = new Set([
  'release', 'work-issues', 'ideaforge-assist', 'audit-ideaforge-codebase', 'audit-android-app',
]);
const skillPath = (name) => join(ROOT, '.claude', 'skills', name, 'SKILL.md');

function filesUnder(dir) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...filesUnder(path));
    else out.push(path);
  }
  return out;
}

function frontmatter(md, label) {
  const match = md.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
  assert.ok(match, `${label}: missing frontmatter`);
  return match[1];
}

function parseFrontmatter(header, label) {
  assert.doesNotMatch(header, /\t/, `${label}: tabs are not allowed in frontmatter`);
  const lines = header.split(/\r?\n/);
  const config = {};
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i]) continue;
    const match = lines[i].match(/^([A-Za-z][\w-]*):(?: (.*))?$/);
    assert.ok(match, `${label}: unsupported frontmatter syntax: ${lines[i]}`);
    const [, key, raw = ''] = match;
    assert.ok(!(key in config), `${label}: duplicate frontmatter key: ${key}`);
    if (!raw) {
      const values = [];
      while (i + 1 < lines.length) {
        const item = lines[i + 1].match(/^  - ([A-Za-z][A-Za-z0-9-]*)$/);
        if (!item) break;
        values.push(item[1]);
        i++;
      }
      assert.ok(values.length, `${label}: ${key} must have a value`);
      config[key] = values;
      continue;
    }
    if (raw.startsWith('[')) {
      const list = raw.match(/^\[([A-Za-z][A-Za-z0-9-]*(?:, [A-Za-z][A-Za-z0-9-]*)*)?\]$/);
      assert.ok(list, `${label}: invalid inline list for ${key}`);
      config[key] = list[1] ? list[1].split(', ') : [];
      continue;
    }
    assert.doesNotMatch(raw, /^[{["']|[}\]]$/,
      `${label}: only plain scalar values are supported for ${key}`);
    const continuation = [];
    while (i + 1 < lines.length) {
      const next = lines[i + 1].match(/^  (?!- )(.+)$/);
      if (!next) break;
      continuation.push(next[1].trim());
      i++;
    }
    config[key] = [raw, ...continuation].join(' ');
  }
  return config;
}

function plainString(value, label) {
  assert.equal(typeof value, 'string', `${label}: expected a string`);
  assert.match(value, /^[A-Za-z]/, `${label}: unsupported YAML scalar`);
  assert.doesNotMatch(value, /^(?:false|null|no|off|on|true|yes|~)$/i,
    `${label}: YAML special value`);
  assert.doesNotMatch(value, /:(?:$|[ \t])/, `${label}: YAML mapping syntax is not supported`);
  assert.doesNotMatch(value, /(?:^|[ \t])#/, `${label}: YAML comments are not supported`);
  return value;
}

function unquote(line) {
  let out = line;
  while (true) {
    const match = out.match(/^[ \t]{0,3}>[ \t]?/);
    if (!match) return out;
    out = out.slice(match[0].length);
  }
}

function stripContainer(line) {
  const content = unquote(line);
  const item = content.match(/^[ \t]{0,3}(?:[-+*]|\d+[.)])[ \t]+(.*)$/);
  return item ? item[1] : content;
}

function openingFence(line) {
  const match = stripContainer(line).match(/^[ \t]{0,4}(`{3,}|~{3,})(.*)$/);
  if (!match) return null;
  const marker = match[1];
  const info = match[2].trim();
  if (marker[0] === '`' && info.includes('`')) return null;
  return { marker, language: info.split(/\s+/)[0] || '' };
}

function fencedBlocks(md) {
  const lines = md.split(/\r?\n/);
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    const opening = openingFence(lines[i]);
    if (!opening) continue;
    const { marker, language } = opening;
    const close = new RegExp(
      `^[ \\t]{0,4}${marker[0] === '`' ? '`' : '~'}{${marker.length},}[ \\t]*$`);
    const body = [];
    for (i += 1; i < lines.length; i++) {
      if (close.test(unquote(lines[i]))) {
        break;
      }
      body.push(unquote(lines[i]));
    }
    blocks.push({ language, body: body.join('\n') });
  }
  return blocks;
}

function stripFences(md) {
  const lines = md.split(/\r?\n/);
  const kept = [];
  for (let i = 0; i < lines.length; i++) {
    const opening = openingFence(lines[i]);
    if (!opening) {
      kept.push(lines[i]);
      continue;
    }
    const { marker } = opening;
    const close = new RegExp(
      `^[ \\t]{0,4}${marker[0] === '`' ? '`' : '~'}{${marker.length},}[ \\t]*$`);
    for (i += 1; i < lines.length && !close.test(unquote(lines[i])); i++);
  }
  return kept.join('\n');
}

function stripCode(md) {
  return stripFences(md).split(/\r?\n/)
    .filter((line) => !/^(?: {4}|\t)/.test(unquote(line)))
    .join('\n');
}

function stripHtmlComments(md) {
  return md.replace(/<!--[\s\S]*?(?:-->|$)/g, '');
}

function markdownSection(md, heading) {
  const section = stripHtmlComments(md).replace(/\r\n/g, '\n').split(/(?=^## )/m)
    .find((part) => part.startsWith(`## ${heading}\n`));
  assert.ok(section, `missing documentation section: ${heading}`);
  return section.slice(`## ${heading}\n`.length).trim();
}

function localLinks(file, md) {
  return [...md.matchAll(/\]\(([^)\s]+)\)/g)]
    .map((match) => match[1].split('#')[0])
    .filter((target) => target && !/^[a-z][a-z\d+.-]*:/i.test(target))
    .map((target) => resolve(dirname(file), target));
}

function skillDefinitions() {
  const definitions = [];
  for (const root of ['.claude', '.github', '.agents']) {
    const dir = join(ROOT, root, 'skills');
    for (const file of filesUnder(dir).filter((path) => path.endsWith(`${join('', 'SKILL.md')}`))) {
      const md = readFileSync(file, 'utf8');
      const label = relative(ROOT, file);
      const header = frontmatter(md, label);
      const config = parseFrontmatter(header, label);
      definitions.push({
        file,
        md,
        name: config.name || '',
        description: config.description || '',
        header,
        config,
      });
    }
  }
  return definitions;
}

function agentDefinitions() {
  const roots = [
    join(ROOT, '.claude', 'agents'),
    join(ROOT, '.github', 'agents'),
  ];
  return roots.flatMap((dir) => filesUnder(dir))
    .filter((file) => file.endsWith('.md'))
    .map((file) => {
      const md = readFileSync(file, 'utf8');
      const label = relative(ROOT, file);
      const header = frontmatter(md, label);
      const config = parseFrontmatter(header, label);
      return { file, md, header, config, name: config.name || '' };
    });
}

function safeBrowserCommand(line, { language = '' } = {}) {
  const command = '^(?:BROWSER_CHECK_REQUIRED=1[ \\t]+)?npm run ' +
    '(test:all|test:browser(?::required)?)(?:[ \\t]+&&)?(?:[ \\t]+#.*)?$';
  const match = line.match(new RegExp(command));
  if (!match) return false;
  if (match[1] === 'test:all') {
    return PACKAGE.scripts['test:all'] === ALL_TESTS_SCRIPT;
  }
  if (match[1] === 'test:browser:required') {
    return PACKAGE.scripts['test:browser:required'] === REQUIRED_BROWSER_SCRIPT;
  }
  return ['sh', 'bash', ''].includes(language)
    && line.startsWith('BROWSER_CHECK_REQUIRED=1 ');
}

function checkBrowserCommands(md, label) {
  let checked = 0;
  for (const { language, body } of fencedBlocks(md)) {
    for (const raw of body.split(/\r?\n/)) {
      const line = raw.trim();
      if (!/\bnpm run test:(?:browser|all)\b/.test(line)) continue;
      checked++;
      assert.ok(safeBrowserCommand(line, { language }),
        `${label}: unguarded browser command: ${line}`);
    }
  }
  return checked;
}

function checkIndentedBrowserCommands(md, label) {
  let checked = 0;
  for (const raw of stripFences(md).split(/\r?\n/)) {
    const line = unquote(raw);
    if (!/^(?: {4}|\t)/.test(line)) continue;
    const code = line.trim();
    if (!/\bnpm run test:(?:browser|all)\b/.test(code)) continue;
    checked++;
    assert.ok(safeBrowserCommand(code),
      `${label}: unguarded indented browser command: ${code}`);
  }
  return checked;
}

function checkInlineBrowserCommands(md, label) {
  let checked = 0;
  for (const match of md.matchAll(/`([^`\r\n]*\bnpm run test:(?:browser|all)\b[^`\r\n]*)`/g)) {
    checked++;
    assert.ok(safeBrowserCommand(match[1]),
      `${label}: unguarded inline browser command: ${match[1]}`);
  }
  return checked;
}

function checkDeliveryGates(md, label) {
  let checked = 0;
  for (const { language, body } of fencedBlocks(md)) {
    const lines = body.split(/\r?\n/).map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'));
    const pushes = lines.filter((line) =>
      /^git push\b/.test(line) && !/refs\/tags\//.test(line));
    if (!pushes.length) continue;
    assert.equal(pushes.length, 1, `${label}: expected exactly one push command`);
    for (const push of pushes) {
      assert.match(push, /^git push -u origin HEAD(?: &&)?$/,
        `${label}: delivery must push the tested HEAD: ${push}`);
    }
    const browser = lines.findIndex((line) => /\bnpm run test:(?:browser|all)\b/.test(line));
    assert.ok(browser >= 0, `${label}: delivery block has no full-suite command`);
    const push = lines.findIndex((line) => /^git push\b/.test(line));
    assert.ok(browser < push, `${label}: delivery pushes before validation`);
    checked++;
    if (['sh', 'bash'].includes(language)) {
      const clean = lines
        .map((line, index) => ({ line, index }))
        .filter(({ line }) => /^test -z "\$\(git status --porcelain\)" &&$/.test(line));
      const capture = lines.findIndex((line) => /^tested=\$\(git rev-parse HEAD\) &&$/.test(line));
      const verify = lines.findIndex((line) =>
        /^test "\$\(git rev-parse HEAD\)" = "\$tested" &&$/.test(line));
      assert.equal(clean.length, 2, `${label}: expected clean-tree checks before and after tests`);
      assert.ok(clean[0].index < capture && capture < browser,
        `${label}: HEAD was not captured from a clean tree before validation`);
      assert.ok(browser < verify && verify < clean[1].index && clean[1].index < push,
        `${label}: tested HEAD was not rechecked before push`);
      for (let i = browser; i < lines.length - 1; i++) {
        assert.match(lines[i], /&&$/,
          `${label}: delivery command is not failure-chained: ${lines[i]}`);
      }
      continue;
    }
    assert.equal(language, 'powershell', `${label}: unsupported delivery shell ${language}`);
    const cleanBefore = lines.findIndex((line) =>
      /^if \(git status --porcelain\) \{ throw '[^']+' \}$/.test(line));
    const capture = lines.findIndex((line) => /^\$tested = git rev-parse HEAD$/.test(line));
    const verify = lines.findIndex((line) =>
      /^if \(\(git rev-parse HEAD\) -ne \$tested -or \(git status --porcelain\)\) \{$/.test(line));
    assert.ok(cleanBefore >= 0 && cleanBefore < capture && capture < browser,
      `${label}: HEAD was not captured from a clean tree before validation`);
    assert.ok(browser < verify && verify < push,
      `${label}: tested HEAD was not rechecked before push`);
    for (let i = browser; i < lines.length - 1; i++) {
      if (!/^(?:npm|git|gh)\b/.test(lines[i])) continue;
      assert.equal(lines[i + 1], 'if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }',
        `${label}: missing native exit check after: ${lines[i]}`);
    }
  }
  return checked;
}

function checkUnitTestCommands(md, label) {
  let checked = 0;
  for (const { body } of fencedBlocks(md)) {
    for (const raw of body.split(/\r?\n/)) {
      const line = raw.trim();
      if (!/^node --test\b/.test(line)) continue;
      checked++;
      const directFile = /\btest\/[^"\s]+\.test\.mjs\b/.test(line);
      const unitGlob = /"test\/\*\*\/\*\.test\.mjs"/.test(line);
      assert.ok(directFile || unitGlob, `${label}: unscoped node test command: ${line}`);
    }
  }
  return checked;
}

test('Claude imports the shared workflow and Copilot links to both sources of truth', () => {
  const claude = read('CLAUDE.md');
  assert.match(stripCode(stripHtmlComments(claude)), /^@AGENTS\.md[ \t]*\r?$/m);
  for (const fenced of [
    '``` md\n@AGENTS.md\n```\n',
    '```md title="example"\n@AGENTS.md\n```\n',
    '```md\n@AGENTS.md\n',
    '> ```md\n> @AGENTS.md\n> ```\n',
    '    @AGENTS.md\n',
    '<!--\n@AGENTS.md\n-->\n',
  ]) {
    assert.doesNotMatch(stripCode(stripHtmlComments(fenced)), /^@AGENTS\.md$/m);
  }

  const file = join(ROOT, '.github', 'copilot-instructions.md');
  const copilot = readFileSync(file, 'utf8');
  const links = localLinks(file, copilot);
  for (const expected of [
    join(ROOT, 'AGENTS.md'),
    join(ROOT, 'CLAUDE.md'),
    join(ROOT, '.claude', 'skills'),
    skillPath('release'),
    join(ROOT, 'CONTRIBUTING.md'),
  ]) {
    assert.ok(links.includes(expected), `Copilot entry point does not link to ${expected}`);
  }
  for (const target of links) assert.ok(existsSync(target), `broken instruction link: ${target}`);
});

test('workspace Playwright MCP uses a pinned, isolated browser outside app dependencies', () => {
  const config = JSON.parse(read('.mcp.json'));
  assert.deepEqual(Object.keys(config), ['mcpServers']);
  assert.deepEqual(Object.keys(config.mcpServers), ['playwright']);

  const server = config.mcpServers.playwright;
  assert.deepEqual(Object.keys(server).sort(), ['args', 'command', 'type']);
  assert.equal(server.type, 'stdio');
  assert.equal(server.command, 'node');
  assert.deepEqual(server.args, ['tools/playwright-mcp.mjs']);

  const unix = playwrightMcpCommand('/usr/bin/google-chrome', 'linux', '/tmp/ideaforge-mcp');
  assert.equal(unix.command, 'npx');
  assert.deepEqual(unix.args.slice(0, 1), ['--yes']);
  assert.match(unix.args[1], /^@playwright\/mcp@\d+\.\d+\.\d+$/);
  assert.deepEqual(unix.args.slice(2), ['--headless', '--isolated']);
  assert.deepEqual(unix.env, {
    PLAYWRIGHT_MCP_EXECUTABLE_PATH: '/usr/bin/google-chrome',
    PLAYWRIGHT_MCP_OUTPUT_DIR: '/tmp/ideaforge-mcp',
  });
  const win = playwrightMcpCommand(
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'win32', 'C:\\Temp\\ideaforge-mcp',
  );
  assert.equal(win.command, process.env.ComSpec || 'cmd.exe');
  assert.deepEqual(win.args.slice(0, 3), ['/d', '/s', '/c']);
  assert.match(win.args[3], /^npx --yes @playwright\/mcp@\d+\.\d+\.\d+ --headless --isolated$/);
  assert.deepEqual(win.env, {
    PLAYWRIGHT_MCP_EXECUTABLE_PATH: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    PLAYWRIGHT_MCP_OUTPUT_DIR: 'C:\\Temp\\ideaforge-mcp',
  });
  assert.deepEqual(PACKAGE.dependencies || {}, {});
  assert.deepEqual(PACKAGE.devDependencies || {}, {});
});

test('shared skills use valid metadata, unique names, and existing resources', () => {
  const definitions = skillDefinitions();
  assert.deepEqual(new Set(definitions.map((skill) => skill.name)), EXPECTED_SKILLS);
  assert.equal(definitions.length, EXPECTED_SKILLS.size, 'skill names must be unique');

  for (const skill of definitions) {
    const label = relative(ROOT, skill.file);
    assert.ok(lstatSync(skill.file).isFile(), `${skill.file} must be a regular file`);
    // State changes and broad audits, like release/backlog work, need an explicit request.
    const keys = EXPLICIT_SKILLS.has(skill.name)
      ? ['description', 'disable-model-invocation', 'name']
      : ['description', 'name'];
    assert.deepEqual(new Set(Object.keys(skill.config)), new Set(keys));
    if (EXPLICIT_SKILLS.has(skill.name)) assert.equal(skill.config['disable-model-invocation'], 'true');
    plainString(skill.name, `${skill.name}: name`);
    plainString(skill.description, `${skill.name}: description`);
    assert.match(skill.name, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    assert.ok(skill.name.length <= 64, `${skill.name}: name is too long`);
    assert.equal(skill.name, relative(join(ROOT, '.claude', 'skills'), dirname(skill.file)));
    assert.ok(skill.description.length > 0 && skill.description.length <= 1024,
      `${skill.name}: invalid description`);
    assert.doesNotMatch(skill.header, /^allowed-tools:/m,
      `${skill.name}: shared skills must not pre-approve tools`);
    for (const target of localLinks(skill.file, skill.md)) {
      assert.ok(existsSync(target), `${skill.name}: broken local link ${target}`);
    }
  }
  assert.throws(() => parseFrontmatter('"allowed-tools": shell', 'fixture'),
    /unsupported frontmatter syntax/);
  assert.throws(() => parseFrontmatter('description: [unterminated', 'fixture'),
    /invalid inline list/);
  assert.throws(() => parseFrontmatter('skills:\n\t- review-ideaforge-change', 'fixture'),
    /tabs are not allowed/);
  for (const value of ['[not-a-string]', '# comment', '|', 'null', '123', 'foo: bar']) {
    const parsed = parseFrontmatter(`description: ${value}`, 'fixture');
    assert.throws(() => plainString(parsed.description, 'fixture: description'));
  }
});

test('release remains explicit and references the existing delivery workflow', () => {
  const release = skillDefinitions().find((skill) => skill.name === 'release');
  assert.ok(release, 'missing release skill');
  assert.equal(release.config['disable-model-invocation'], 'true');
  assert.ok(!('user-invocable' in release.config), 'release must remain user-invocable');

  const links = localLinks(release.file, release.md);
  for (const expected of [
    join(ROOT, 'AGENTS.md'),
    join(ROOT, 'CONTRIBUTING.md'),
    join(ROOT, 'package.json'),
    join(ROOT, 'src', 'version.js'),
    join(ROOT, '.github', 'release.yml'),
    join(ROOT, '.github', 'workflows', 'ci.yml'),
    join(ROOT, '.github', 'workflows', 'release.yml'),
    join(ROOT, '.github', 'workflows', 'pages.yml'),
    skillPath('update-readme'),
  ]) {
    assert.ok(links.includes(expected), `release is missing reference: ${expected}`);
  }
});

// These protect the documented protocol, not model adherence or measured speedups.
test('backlog protocol uses sequential fresh workers only for explicit multi-issue work', () => {
  const md = read('.claude', 'skills', 'work-issues', 'SKILL.md');
  const boundary = markdownSection(md, 'Execution boundary').replace(/\s+/g, ' ');
  assert.match(boundary, /single-issue invocation runs directly/i);
  assert.match(boundary, /only an explicitly requested multi-issue run/i);
  assert.match(boundary, /current client's ordinary task\/subagent tool/i);
  assert.match(boundary, /one fresh implementation worker per issue/i);
  assert.match(boundary, /workers run sequentially with one active issue/i);
  assert.match(boundary, /no fleet/i);
  assert.match(boundary, /worker must not spawn another implementation worker or coordinator, pick another issue, or merge/i);
  assert.match(boundary, /same-issue follow-ups.*same worker.*round.*failure/i);
  assert.match(boundary, /next worker starts only after.*green merged main/i);
  assert.match(boundary, /fresh worker contexts are unavailable.*report.*stop/i);
  assert.match(boundary, /coordinator.*merge gate.*closeout/i);
});

test('backlog protocol dispatch and handoff carry evidence rather than history', () => {
  const boundary = markdownSection(read('.claude', 'skills', 'work-issues', 'SKILL.md'),
    'Execution boundary').replace(/\s+/g, ' ');
  for (const [pattern, label] of [
    [/issue body and acceptance criteria/i, 'issue specification'],
    [/worktree, branch and base SHA/i, 'checkout identity'],
    [/owning instructions and skills/i, 'canonical instructions'],
    [/inherited mode/i, 'inherited guidance'],
    [/not earlier transcripts/i, 'fresh context boundary'],
    [/exact tested SHA/i, 'tested commit'],
    [/commands\/results/i, 'command evidence'],
    [/review verdicts and combined code\/docs round/i, 'persistent review state'],
    [/CI\/device run IDs and attempts/i, 'actual run provenance'],
    [/artifact\/profile\/SHA provenance/i, 'artifact provenance'],
    [/unresolved errors/i, 'remaining failures'],
    [/not raw source\/log\/history dumps/i, 'compact handoff'],
  ]) {
    assert.match(boundary, pattern, `worker packet/handoff omits ${label}`);
  }
  assert.match(boundary, /coordinator verifies.*actual required.*ci/i);
  assert.match(boundary, /does not redo.*exploration.*reviews.*tests.*screenshots/i);
});

test('backlog protocol checks actual green main and the requested dependency closure', () => {
  const md = read('.claude', 'skills', 'work-issues', 'SKILL.md');
  const preflight = markdownSection(md, '1. Preflight').replace(/\s+/g, ' ');
  assert.ok(preflight.includes('--commit "$(git rev-parse HEAD)"'),
    'main CI lookup must be pinned to the actual updated commit');
  assert.match(preflight, /headSha.*completed.*success/i);
  assert.match(preflight, /red main stops the run/i);
  assert.match(preflight, /outside the requested scope.*stop and report/i);
  const queue = markdownSection(md, '2. Pick the next issue').replace(/\s+/g, ' ');
  assert.match(queue, /requested queue and dependency closure/i);
  assert.match(queue, /transitive.*blockers/i);
  assert.match(queue, /outside-request prerequisites.*explicit authorisation/i);
});

test('backlog protocol separates readiness, bounded review and exact-head final evidence', () => {
  const md = read('.claude', 'skills', 'work-issues', 'SKILL.md');
  const implementation = markdownSection(md, '4. Implement').replace(/\s+/g, ' ');
  for (const pattern of [
    /absent\/saved fields/i, /native touch targets/i, /visible named errors\/loading/i,
    /semantic selectors and affected walkthroughs/i, /before final validation/i,
    /phase changes or failures, not every routine command/i,
  ]) assert.match(implementation, pattern);
  const proof = markdownSection(md, '5. Prove it').replace(/\s+/g, ' ');
  assert.match(proof, /focused regression proof before review/i);
  assert.match(proof, /final inventory once on the clean final head/i);
  assert.match(proof, /do not retry an identical unchanged failure without a specific transient-infrastructure diagnosis/i);
  const review = markdownSection(md, '6. Independent review').replace(/\s+/g, ' ');
  assert.match(review, /review agents do not run commands/i);
  assert.match(review, /combined code\/docs review-and-fix rounds per issue/i);
  assert.match(review, /compaction or resume never resets the count/i);
  assert.match(review, /stop after three rounds/i);
  assert.match(review, /clean review of an unchanged diff is terminal/i);
  const delivery = markdownSection(md, '7. Deliver').replace(/\s+/g, ' ');
  assert.match(delivery, /code, documentation and capture drivers are settled/i);
  assert.match(delivery, /including test-only changes/i);
  assert.match(delivery, /--draft/);
  assert.match(delivery, /actual CI\/native acceptance/i);
  assert.match(delivery, /metadata-only/);
});

test('backlog protocol preserves the literal-SHA merge guard and required ci', () => {
  const md = read('.claude', 'skills', 'work-issues', 'SKILL.md');
  const gates = fencedBlocks(markdownSection(md, '8. Merge gate'))
    .filter(({ body }) => /\bgh pr merge\b/.test(body));
  assert.equal(gates.length, 1, 'one indivisible merge guard must own the merge');
  const expected = String.raw`PR=99 TESTED=0123456789abcdef0123456789abcdef01234567 &&
gate=$(gh pr view "$PR" --json headRefOid,mergeStateStatus,isDraft,statusCheckRollup \
  --jq '[.headRefOid, ((.statusCheckRollup | map(select(.name == "ci")) | .[0].conclusion) // "MISSING"), .mergeStateStatus, (.isDraft | tostring)] | join(" ")') &&
set -- $gate &&
echo "head=$1 ci=$2 state=$3 draft=$4" &&
[[ "$1" =~ ^[0-9a-f]{40}$ ]] && [ "$1" = "$TESTED" ] &&
[ "$2" = SUCCESS ] && [ "$3" != BEHIND ] && [ "$3" != DIRTY ] && [ "$4" = false ] &&
gh pr merge "$PR" --squash --admin --match-head-commit "$1" --delete-branch`;
  assert.equal(gates[0].language, 'sh');
  assert.equal(gates[0].body, expected, 'do not weaken, split or replace the canonical merge guard');
  const ci = read('.github', 'workflows', 'ci.yml');
  assert.match(ci, /^  ci:\r?\n    name: ci\r?\n    if: always\(\)\r?\n    needs: \[test, browser, android\]/m);
  assert.doesNotMatch(ci, /^[ \t]*paths(?:-ignore)?:/m);
});

test('native workflow preserves early acceptance, warm state and sequential emulators', () => {
  const md = read('.claude', 'skills', 'android-verify', 'SKILL.md');
  const readiness = markdownSection(md, '1a. Native readiness during development')
    .replace(/\s+/g, ' ');
  for (const pattern of [
    /absent\/saved fields/i, /touchBoundsInRoot/, /paint bounds/i,
    /visible named errors\/loading/i, /semantic selectors/i, /walkthrough/i,
    /emulator jobs sequential/i, /focused capture is not.*full-matrix/i,
  ]) assert.match(readiness, pattern);
  const focused = markdownSection(md, "1. The cheapest rung: one module's unit tests")
    .replace(/\s+/g, ' ');
  assert.match(focused, /warm Gradle state/i);
  assert.match(focused, /routine `clean`, `--rerun-tasks`, cache deletion or daemon shutdown/i);
  assert.match(markdownSection(md, '6. Windows test-host quirks'), /\.\/gradlew --stop/);
});

test('workflow references retain one owner and explicit general-purpose child fallback', () => {
  for (const parts of [
    ['AGENTS.md'],
    ['CONTRIBUTING.md'],
    ['docs', 'ASSISTANT-PROCESS.md'],
    ['.claude', 'skills', 'update-documentation', 'references', 'source-map.md'],
  ]) {
    const file = join(ROOT, ...parts);
    assert.ok(localLinks(file, readFileSync(file, 'utf8')).includes(skillPath('work-issues')),
      `${relative(ROOT, file)} must reference the canonical backlog skill`);
  }
  const limits = markdownSection(read('docs', 'ASSISTANT-PROCESS.md'),
    'Native clients and status').replace(/\s+/g, ' ');
  assert.match(limits, /general-purpose.*does not emit the normal child lifecycle hooks/i);
  assert.match(limits, /explicit.*delegating prompt.*actual parent mode/i);
  assert.match(limits, /AGENTS\.md#lean-coding/);
});

test('review agents are distinct, explicit, read-only adapters for the shared skill', () => {
  const expected = new Map([
    [join('.claude', 'agents', 'ideaforge-review-claude.md'), {
      name: 'ideaforge-review-claude',
      skill: 'review-ideaforge-change',
      tools: ['Read', 'Grep', 'Glob'],
      keys: ['description', 'name', 'permissionMode', 'skills', 'tools'],
    }],
    [join('.claude', 'agents', 'ideaforge-docs-review-claude.md'), {
      name: 'ideaforge-docs-review-claude',
      skill: 'review-ideaforge-documentation',
      tools: ['Read', 'Grep', 'Glob'],
      keys: ['description', 'name', 'permissionMode', 'skills', 'tools'],
    }],
    [join('.github', 'agents', 'ideaforge-review-copilot.agent.md'), {
      name: 'ideaforge-review-copilot',
      skill: 'review-ideaforge-change',
      tools: ['read', 'search', 'grep', 'glob'],
      keys: ['description', 'name', 'tools'],
    }],
    [join('.github', 'agents', 'ideaforge-docs-review-copilot.agent.md'), {
      name: 'ideaforge-docs-review-copilot',
      skill: 'review-ideaforge-documentation',
      tools: ['read', 'search', 'grep', 'glob'],
      keys: ['description', 'name', 'tools'],
    }],
  ]);
  const agents = agentDefinitions();
  const paths = new Set(agents.map((agent) => relative(ROOT, agent.file)));
  assert.deepEqual(paths, new Set(expected.keys()), 'unexpected or missing agent definition');
  assert.equal(new Set(agents.map((agent) => agent.name)).size, agents.length);

  for (const definition of agents) {
    const path = relative(ROOT, definition.file);
    const agent = expected.get(path);
    const { md, config } = definition;
    plainString(config.name, `${path}: name`);
    plainString(config.description, `${path}: description`);
    assert.equal(config.name, agent.name);
    assert.deepEqual(new Set(Object.keys(config)), new Set(agent.keys));
    assert.equal(relative(dirname(definition.file), definition.file)
      .replace(/\.(?:agent\.)?md$/, ''),
      agent.name);
    assert.deepEqual(config.tools, agent.tools);
    assert.doesNotMatch(config.tools.join(' '), /\b(?:bash|edit|execute|shell|write)\b/i);
    assert.match(config.description, /\bsupplied\b/i);
    assert.match(md, new RegExp(agent.skill));
    assert.match(md, /read-only/i);
    assert.match(md, /exact\s+change set is unavailable,\s+stop/i);
    for (const target of localLinks(definition.file, md)) {
      assert.ok(existsSync(target), `${agent.name}: broken local link ${target}`);
    }
  }

  for (const [file, skill] of [
    ['ideaforge-review-claude.md', 'review-ideaforge-change'],
    ['ideaforge-docs-review-claude.md', 'review-ideaforge-documentation'],
  ]) {
    const claude = readFileSync(join(ROOT, '.claude', 'agents', file), 'utf8');
    assert.match(claude, new RegExp(
      `^skills:\\r?\\n[ \\t]+-[ \\t]+${skill}[ \\t]*\\r?$`, 'm'));
    assert.match(claude, /^permissionMode:[ \t]*plan[ \t]*\r?$/m);
  }
});

test('documented node test commands exclude browser probe modules', () => {
  let checked = 0;
  for (const file of [join(ROOT, 'AGENTS.md'), join(ROOT, 'CLAUDE.md')]) {
    checked += checkUnitTestCommands(readFileSync(file, 'utf8'), relative(ROOT, file));
  }
  assert.ok(checked >= 5, `only checked ${checked} node test commands`);
  assert.throws(() => checkUnitTestCommands('```sh\nnode --test\n```\n', 'fixture'),
    /unscoped node test command/);
  assert.throws(() => checkUnitTestCommands(
    '```sh\nnode --test --test-name-pattern="zero gain"\n```\n', 'fixture'),
  /unscoped node test command/);
});

test('the all-up test script includes graph and required-browser validation', () => {
  assert.equal(PACKAGE.scripts['test:browser:required'], REQUIRED_BROWSER_SCRIPT);
  assert.equal(PACKAGE.scripts['test:all'], ALL_TESTS_SCRIPT);
  assert.match(BROWSER_CHECK_SOURCE,
    /if \(REQUIRED\) \{\s*console\.error\('no browser probes found,[^']*'\);\s*return 1;\s*\}/);
  assert.match(BROWSER_CHECK_SOURCE,
    /if \(REQUIRED\) \{\s*throw new Error\(`cannot read browser probe directory:/);
});

test('agent guidance never permits a missing browser to look green', () => {
  const files = new Set([
    join(ROOT, 'AGENTS.md'),
    join(ROOT, 'CLAUDE.md'),
    join(ROOT, 'CONTRIBUTING.md'),
    join(ROOT, 'README.md'),
    join(ROOT, '.github', 'copilot-instructions.md'),
    ...filesUnder(join(ROOT, '.claude')).filter((file) => file.endsWith('.md')),
    ...filesUnder(join(ROOT, '.github', 'agents')).filter((file) => file.endsWith('.md')),
    ...filesUnder(join(ROOT, '.github', 'instructions')).filter((file) => file.endsWith('.md')),
  ]);
  let checked = 0;
  for (const file of files) {
    const md = readFileSync(file, 'utf8');
    checked += checkBrowserCommands(md, relative(ROOT, file));
    checked += checkIndentedBrowserCommands(md, relative(ROOT, file));
    checked += checkInlineBrowserCommands(md, relative(ROOT, file));
    checked += checkDeliveryGates(md, relative(ROOT, file));
  }
  assert.ok(checked >= 8, `only checked ${checked} browser commands`);

  assert.throws(() => checkBrowserCommands(
    '```sh\nnpm run test:browser\n```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '```powershell\nnpm run test:browser\n```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    "```powershell\n$env:BROWSER_CHECK_REQUIRED = '1'\n" +
    'npm run test:browser\n```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '  ```sh\n  npm run test:browser\n  ````\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '``` sh\nnpm run test:browser\n```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '```sh title="verification"\nnpm run test:browser\n```\n', 'fixture'),
  /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '```sh\nnpm run test:browser\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '- Example:\n  ```powershell\n  npm run test:browser\n  ```\n', 'fixture'),
  /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '> ```sh\n> npm run test:browser\n> ```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    '- ```sh\n  npm run test:browser\n  ```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkIndentedBrowserCommands(
    '    npm run test:browser\n', 'fixture'), /unguarded indented browser command/);
  assert.throws(() => checkInlineBrowserCommands(
    'Run `npm run test:browser` before pushing.', 'fixture'), /unguarded inline browser command/);
  assert.equal(checkInlineBrowserCommands(
    'Run `BROWSER_CHECK_REQUIRED=1 npm run test:browser`.', 'fixture'), 1);
  assert.equal(checkInlineBrowserCommands(
    'Run `npm run test:browser:required`.', 'fixture'), 1);
  assert.equal(checkInlineBrowserCommands('Run `npm run test:all`.', 'fixture'), 1);
  assert.throws(() => checkBrowserCommands(
    '```sh\nBROWSER_CHECK_REQUIRED=1 npm run test:browser || true\n```\n', 'fixture'),
  /unguarded browser command/);
  assert.throws(() => checkBrowserCommands(
    "```powershell\n$env:BROWSER_CHECK_REQUIRED = '1'\n" +
    'npm run test:browser; exit 0\n```\n', 'fixture'), /unguarded browser command/);
  assert.throws(() => checkDeliveryGates(
    '```sh\n' +
    'test -z "$(git status --porcelain)" &&\n' +
    'tested=$(git rev-parse HEAD) &&\n' +
    'npm run test:all\n' +
    'test "$(git rev-parse HEAD)" = "$tested" &&\n' +
    'test -z "$(git status --porcelain)" &&\n' +
    'git push -u origin HEAD\n' +
    'gh pr create --base main --fill\n' +
    '```\n',
    'fixture'), /not failure-chained/);
  assert.throws(() => checkDeliveryGates(
    '```sh\nnpm run test:all &&\ngit push -u origin other-branch &&\n' +
    'gh pr create --base main --fill\n```\n',
    'fixture'), /push the tested HEAD/);
  assert.throws(() => checkDeliveryGates(
    '```sh\ngit push -u origin HEAD &&\nnpm run test:all &&\n' +
    'gh pr create --base main --fill\n```\n',
    'fixture'), /pushes before validation/);
  assert.throws(() => checkDeliveryGates(
    '```sh\ngit push -u origin HEAD &&\ngh pr create --base main --fill\n```\n',
    'fixture'), /no full-suite command/);
});

test('personal assistant settings stay out of the repository', () => {
  const ignore = read('.gitignore').split(/\r?\n/);
  for (const entry of [
    'CLAUDE.local.md',
    '.claude/settings.local.json',
    '.github/copilot/settings.local.json',
    '.ideaforge-assist/',
  ]) {
    assert.ok(ignore.includes(entry), `${entry} is not ignored`);
  }
});

test('native assist adapters are project-local consumers, not permissions or duplicated rules', () => {
  const claude = JSON.parse(read('.claude', 'settings.json'));
  assert.deepEqual(Object.keys(claude), ['hooks']);
  assert.deepEqual(Object.keys(claude.hooks), ['SessionStart', 'UserPromptSubmit', 'SubagentStart', 'PreToolUse']);
  assert.match(claude.hooks.SessionStart[0].matcher, /fork/);
  // The PR gate sees only the two shell tools, whose names Claude Code documents.
  assert.equal(claude.hooks.PreToolUse[0].matcher, 'Bash|PowerShell');
  for (const entries of Object.values(claude.hooks)) {
    assert.equal(entries.length, 1);
    assert.equal(entries[0].hooks.length, 1);
    const hook = entries[0].hooks[0];
    assert.deepEqual(Object.keys(hook), ['type', 'command']);
    assert.equal(hook.type, 'command');
    assert.match(hook.command, /process\.env\.CLAUDE_PROJECT_DIR/);
    assert.doesNotMatch(hook.command, /\$\{CLAUDE_PROJECT_DIR\}|%CLAUDE_PROJECT_DIR%|--allow-all/);
  }
  const extension = read('.github', 'extensions', 'ideaforge-assist', 'extension.mjs');
  assert.match(extension, /from '@github\/copilot-sdk\/extension'/);
  assert.match(extension, /from '\.\.\/\.\.\/\.\.\/tools\/assistant-copilot\.mjs'/);
  assert.match(extension, /await joinAssistant\(joinSession\)/);
  const adapter = read('tools', 'assistant-copilot.mjs');
  assert.doesNotMatch(adapter, /requestedEnvironmentVariables|onPermissionRequest|modifiedPrompt|modifiedArgs|modifiedResult|sendAndWait|\.send\(/);
  assert.match(adapter, /additionalContext/);
  assert.match(adapter, /session\.compaction_complete/);
  const copilot = JSON.parse(read('.github', 'hooks', 'ideaforge-assist.json'));
  assert.equal(copilot.version, 1);
  assert.deepEqual(Object.keys(copilot.hooks), ['sessionStart', 'userPromptSubmitted', 'subagentStart', 'preToolUse']);
  // A failed Copilot preToolUse command denies the tool, so the gate's must not exit non-zero.
  assert.doesNotMatch(copilot.hooks.preToolUse[0].args[1], /exitCode/);
  assert.match(copilot.hooks.preToolUse[0].args[1], /catch\(e=>\{[^}]*process\.stdout\.write\('\{\}/);
  // The gate may refuse, never approve: an approval would bypass the client's permission flow.
  for (const file of ['tools/assistant-hook.mjs', 'tools/assistant-review.mjs']) {
    assert.doesNotMatch(read(file), /permissionDecision: '(?:allow|ask)'|modifiedArgs|updatedInput/);
  }
  for (const actions of Object.values(copilot.hooks)) {
    assert.equal(actions.length, 1);
    assert.deepEqual(Object.keys(actions[0]), ['type', 'exec', 'args']);
    assert.equal(actions[0].type, 'command');
    assert.equal(actions[0].exec, 'node');
    assert.deepEqual(actions[0].args.slice(0, 1), ['-e']);
    assert.match(actions[0].args[1], /execFileSync\('git'/);
    assert.doesNotMatch(actions[0].args[1], /--allow-all|permissionDecision|modifiedPrompt/);
  }
  const rules = read('AGENTS.md');
  assert.match(rules, /^## Lean coding$/m);
  assert.ok(rules.indexOf('## Lean coding') < rules.indexOf('## The short version'));
  assert.match(read('tools', 'assistant-process.mjs'), /leanRules\(root/);
  for (const file of ['tools/assistant.mjs', 'tools/assistant-hook.mjs', 'tools/assistant-copilot.mjs']) {
    assert.doesNotMatch(read(file), /Establish the complete scope before choosing/);
  }
  assert.deepEqual(PACKAGE.dependencies || {}, {});
  assert.deepEqual(PACKAGE.devDependencies || {}, {});
});
