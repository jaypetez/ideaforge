// Wiring checks, not an emulation of any client's loader. Native discovery is a separate check.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, lstatSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SKILL_NAMES = ['update-readme', 'release'];
const skillPath = (name) => join(ROOT, '.claude', 'skills', name, 'SKILL.md');
const read = (...parts) => readFileSync(join(ROOT, ...parts), 'utf8');
const FENCES = /^(`{3,}|~{3,})([\w-]*)[ \t]*\r?\n([\s\S]*?)^\1[ \t]*\r?$/gm;

function checkLinks(md, from, required) {
  const targets = [...md.matchAll(/\]\(([^)\s]+)\)/g)]
    .map((m) => m[1].split('#')[0])
    .filter((p) => p && !/^[a-z][a-z\d+.-]*:/i.test(p))
    .map((p) => resolve(ROOT, from, p));
  for (const target of targets) {
    assert.ok(existsSync(target), `broken local reference: ${target}`);
    assert.ok(lstatSync(target).isFile(), `reference is not a regular file: ${target}`);
  }
  for (const target of required) {
    assert.ok(targets.includes(target), `missing required link: ${target}`);
  }
}

function checkCopilotLinks(md) {
  checkLinks(md, '.github', [
    join(ROOT, 'AGENTS.md'), join(ROOT, 'CLAUDE.md'), ...SKILL_NAMES.map(skillPath),
  ]);
}

function checkClaudeImport(md) {
  assert.match(md.replace(FENCES, ''), /^@AGENTS\.md[ \t]*\r?$/m,
    'CLAUDE.md must import AGENTS.md outside a code fence');
  assert.ok(lstatSync(join(ROOT, 'AGENTS.md')).isFile());
}

function frontmatter(md) {
  const match = md.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
  assert.ok(match, 'missing skill frontmatter');
  return match[1];
}

function skillName(header) {
  const match = header.match(
    /^name:[ \t]*(?:"([a-z0-9-]+)"|'([a-z0-9-]+)'|([a-z0-9-]+))[ \t]*(?:#[^\r\n]*)?\r?$/m);
  assert.ok(match, 'project skills need a plain or quoted name');
  return match.slice(1).find(Boolean);
}

function skillDefinitions() {
  const definitions = [];
  for (const root of ['.claude', '.github', '.agents']) {
    const dir = join(ROOT, root, 'skills');
    if (!existsSync(dir)) continue;
    const files = [join(dir, 'SKILL.md'),
      ...readdirSync(dir).map((name) => join(dir, name, 'SKILL.md'))];
    for (const file of files) {
      if (!existsSync(file)) continue;
      const header = frontmatter(readFileSync(file, 'utf8'));
      definitions.push({ name: skillName(header), file });
    }
  }
  return definitions;
}

function checkSingleSkill(definitions, name) {
  const matches = definitions.filter((d) => d.name === name);
  assert.equal(matches.length, 1, `expected one shared ${name} definition`);
  assert.equal(matches[0].file, skillPath(name));
}

function checkReleaseInvocation(md) {
  const header = frontmatter(md);
  assert.match(header, /^disable-model-invocation:[ \t]*true[ \t]*\r?$/m,
    'release must require explicit invocation');
  assert.doesNotMatch(header, /^user-invocable:[ \t]*false[ \t]*\r?$/m,
    'release must remain user-invocable');
}

function checkBrowserCommands(md) {
  let checked = 0;
  for (const [, , language, body] of md.matchAll(FENCES)) {
    let required = false;
    for (const raw of body.split(/\r?\n/)) {
      const line = raw.trim();
      if (line.startsWith('#')) continue;
      if (language === 'powershell' && line.startsWith('$env:BROWSER_CHECK_REQUIRED')) {
        required = /^\$env:BROWSER_CHECK_REQUIRED\s*=\s*['"]1['"]$/.test(line);
      }
      if (!/\bnpm run test:(?:browser|all)\b/.test(line)) continue;
      checked++;
      const guarded = language === 'sh'
        ? /^BROWSER_CHECK_REQUIRED=1[ \t]+npm run test:(?:browser|all)\b/.test(line)
        : language === 'powershell' && required;
      assert.ok(guarded, `unguarded browser command: ${line}`);
    }
  }
  assert.ok(checked > 0, 'no browser commands checked');
}

test('the Copilot entry point links to the real shared instructions and skill', () => {
  assert.ok(lstatSync(join(ROOT, '.github', 'copilot-instructions.md')).isFile());
  const md = read('.github', 'copilot-instructions.md');
  checkCopilotLinks(md);
  assert.throws(() => checkCopilotLinks(md.replace('../AGENTS.md', '../missing-agent-file.md')),
    /broken local reference/);
  assert.throws(() => checkCopilotLinks(
    md.replace(/\[[^\]]+\]\(\.\.\/AGENTS\.md\)/, 'AGENTS.md')), /missing required link/);
});

test('Claude imports the shared workflow rather than only describing it', () => {
  const md = read('CLAUDE.md');
  checkClaudeImport(md);
  const without = md.replace(/^@AGENTS\.md[ \t]*\r?$/m, '');
  assert.throws(() => checkClaudeImport(without), /must import AGENTS/);
  for (const fence of ['```', '~~~']) {
    assert.throws(() => checkClaudeImport(`${without}\n${fence}md\n@AGENTS.md\n${fence}\n`),
      /must import AGENTS/);
  }
  checkClaudeImport('@AGENTS.md\r\n');
});

for (const name of SKILL_NAMES) {
  test(`${name} has discoverable metadata and a single regular-file definition`, () => {
    const file = skillPath(name);
    assert.ok(lstatSync(file).isFile(), 'the shared skill must not depend on a symlink');
    const header = frontmatter(readFileSync(file, 'utf8'));
    assert.equal(skillName(header), name);
    assert.match(header, /^description:[ \t]*\S/m);
    assert.doesNotMatch(header, /^allowed-tools:/m, 'use normal client permissions');

    const definitions = skillDefinitions();
    checkSingleSkill(definitions, name);
    assert.throws(() => checkSingleSkill([], name), /one shared/);
    for (const scalar of [name, `'${name}'`, `"${name}" # shared`]) {
      assert.throws(() => checkSingleSkill([...definitions, {
        name: skillName(`name: ${scalar}\r\n`),
        file: join(ROOT, '.github', 'skills', name, 'SKILL.md'),
      }], name), /one shared/);
    }
  });
}

test('release remains explicitly invoked rather than selected automatically', () => {
  const md = readFileSync(skillPath('release'), 'utf8');
  checkReleaseInvocation(md);
  const without = md.replace(/^disable-model-invocation:[^\r\n]*\r?\n/m, '');
  assert.throws(() => checkReleaseInvocation(without), /must require explicit invocation/);
  assert.throws(() => checkReleaseInvocation(
    `${without}\n\`\`\`yaml\ndisable-model-invocation: true\n\`\`\`\n`),
    /must require explicit invocation/);
  assert.throws(() => checkReleaseInvocation(md.replace(
    'disable-model-invocation: true', 'disable-model-invocation: false')),
    /must require explicit invocation/);
  assert.throws(() => checkReleaseInvocation(md.replace(
    /^---\r?\n/, '---\nuser-invocable: false\n')), /must remain user-invocable/);
});

test('release references the existing version files and delivery workflows', () => {
  const md = readFileSync(skillPath('release'), 'utf8');
  const required = [
    join(ROOT, 'AGENTS.md'), join(ROOT, 'CONTRIBUTING.md'), join(ROOT, 'package.json'),
    join(ROOT, 'src', 'version.js'), join(ROOT, '.github', 'release.yml'),
    join(ROOT, '.github', 'workflows', 'ci.yml'),
    join(ROOT, '.github', 'workflows', 'release.yml'),
    join(ROOT, '.github', 'workflows', 'pages.yml'), skillPath('update-readme'),
  ];
  const check = (text) => checkLinks(text, join('.claude', 'skills', 'release'), required);
  check(md);
  assert.throws(() => check(md.replace(
    '../../../.github/workflows/release.yml', '../../../.github/workflows/missing-release.yml')),
    /broken local reference/);
  assert.throws(() => check(md.replace(
    /\[[^\]]+\]\(\.\.\/\.\.\/\.\.\/src\/version\.js\)/, 'src/version.js')), /missing required link/);
});

test('agent browser-check examples cannot silently skip a missing browser', () => {
  const skills = SKILL_NAMES.map((name) => readFileSync(skillPath(name), 'utf8'));
  for (const md of [read('AGENTS.md'), ...skills]) {
    checkBrowserCommands(md);
    assert.throws(() => checkBrowserCommands(md.replace(
      /^BROWSER_CHECK_REQUIRED=1[ \t]+/m, '')), /unguarded browser command/);
    assert.throws(() => checkBrowserCommands(md.replace(
      /^\$env:BROWSER_CHECK_REQUIRED[^\r\n]*/m, '')), /unguarded browser command/);
  }
  assert.throws(() => checkBrowserCommands(
    'BROWSER_CHECK_REQUIRED=1\n```sh\nnpm run test:browser\n```\n'),
    /unguarded browser command/);
  assert.throws(() => checkBrowserCommands('npm run test:browser'), /no browser commands/);
  checkBrowserCommands("```powershell\r\n$env:BROWSER_CHECK_REQUIRED = '1'\r\n" +
    'npm run test:all\r\n```\r\n');
});
