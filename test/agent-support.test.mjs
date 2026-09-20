// Wiring checks, not an emulation of any client's loader. Native discovery is a separate check.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, lstatSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SKILL_PATH = join(ROOT, '.claude', 'skills', 'update-readme', 'SKILL.md');
const read = (...parts) => readFileSync(join(ROOT, ...parts), 'utf8');
const FENCES = /^(`{3,}|~{3,})([\w-]*)[ \t]*\r?\n([\s\S]*?)^\1[ \t]*\r?$/gm;

function checkCopilotLinks(md) {
  const targets = [...md.matchAll(/\]\(([^)\s]+)\)/g)]
    .map((m) => m[1].split('#')[0])
    .filter((p) => p && !/^[a-z][a-z\d+.-]*:/i.test(p))
    .map((p) => resolve(ROOT, '.github', p));
  for (const target of targets) {
    assert.ok(existsSync(target), `broken instruction reference: ${target}`);
    assert.ok(lstatSync(target).isFile(), `reference is not a regular file: ${target}`);
  }
  for (const target of [join(ROOT, 'AGENTS.md'), join(ROOT, 'CLAUDE.md'), SKILL_PATH]) {
    assert.ok(targets.includes(target), `missing shared instruction link: ${target}`);
  }
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

function checkSingleSkill(definitions) {
  const matches = definitions.filter((d) => d.name === 'update-readme');
  assert.equal(matches.length, 1, 'expected one shared update-readme definition');
  assert.equal(matches[0].file, SKILL_PATH);
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
    /broken instruction reference/);
  assert.throws(() => checkCopilotLinks(
    md.replace(/\[[^\]]+\]\(\.\.\/AGENTS\.md\)/, 'AGENTS.md')), /missing shared instruction link/);
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

test('update-readme has discoverable metadata and a single regular-file definition', () => {
  assert.ok(lstatSync(SKILL_PATH).isFile(), 'the shared skill must not depend on a symlink');
  const header = frontmatter(readFileSync(SKILL_PATH, 'utf8'));
  assert.equal(skillName(header), 'update-readme');
  assert.match(header, /^description:[ \t]*\S/m);
  assert.doesNotMatch(header, /^allowed-tools:/m, 'use normal client permissions');

  const definitions = skillDefinitions();
  checkSingleSkill(definitions);
  assert.throws(() => checkSingleSkill([]), /one shared update-readme/);
  for (const name of ['update-readme', "'update-readme'", '"update-readme" # shared']) {
    assert.throws(() => checkSingleSkill([...definitions, {
      name: skillName(`name: ${name}\r\n`),
      file: join(ROOT, '.github', 'skills', 'update-readme', 'SKILL.md'),
    }]), /one shared update-readme/);
  }
});

test('agent browser-check examples cannot silently skip a missing browser', () => {
  for (const md of [read('AGENTS.md'), readFileSync(SKILL_PATH, 'utf8')]) {
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
