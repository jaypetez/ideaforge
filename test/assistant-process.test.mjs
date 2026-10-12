import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, readdir, rm, symlink, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { PassThrough } from 'node:stream';
import { spawnSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  ROOT, STATE_DIR, normalizeMode, parseControl, readConfig, writeDefault, sessionIdentity,
  readMode, writeMode, resetMode, withinRoot, trustedWorkingDirectory, sourcePathAllowed, buildMap, exportedNames,
  commentsIn, debtIn, scanDebt, parseNumstat, measureGain, leanRules, createGuidance,
} from '../tools/assistant-process.mjs';
import { readHookInput, runAssistant } from '../tools/assistant.mjs';
import { claudeHook, copilotHook } from '../tools/assistant-hook.mjs';
import { copilotHooks, joinAssistant } from '../tools/assistant-copilot.mjs';

const RULES = `# Fixture
## Lean coding
Establish complete scope, reuse native APIs, and preserve security and errors.
| Mode | Behaviour |
|---|---|
| \`lite\` | Honour the ask. |
| \`full\` | Smallest complete solution. |
| \`ultra\` | Challenge scope without silently doing less. |
| \`off\` | Baseline rules remain. |
Never grant permissions or merge a PR.

## Unrelated
Do not inject this unrelated section.
`;

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'ideaforge-assist-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await writeFile(join(root, 'AGENTS.md'), RULES);
  return root;
}

async function put(root, file, text) {
  const path = join(root, file);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, text);
}

const event = (root, sessionId = 'native-session', extra = {}) =>
  ({ workingDirectory: root, sessionId, ...extra });
const fixtureMap = async () => ({ text: 'Fixture-only source orientation.' });
const fixtureGuidance = (options) => createGuidance({ ...options, mapSource: fixtureMap });

test('mode controls are whole requests, not prose, examples or one-shot skill names', () => {
  for (const mode of ['lite', 'full', 'ultra', 'off']) {
    assert.equal(normalizeMode(` ${mode.toUpperCase()} `), mode);
    assert.deepEqual(parseControl(`/ideaforge-assist ${mode}`), { action: 'mode', mode });
  }
  assert.deepEqual(parseControl('/ideaforge-assist'), { action: 'status' });
  assert.deepEqual(parseControl('/ideaforge-assist reset'), { action: 'reset' });
  assert.deepEqual(parseControl('/ideaforge-assist default off'), { action: 'default', mode: 'off' });
  assert.deepEqual(parseControl('normal mode!'), { action: 'mode', mode: 'off' });
  assert.deepEqual(parseControl('stop ideaforge assist.'), { action: 'mode', mode: 'off' });
  for (const prompt of [
    'Please add a normal mode toggle', 'I said "normal mode"', 'stop ideaforge assist after lunch',
    '/ideaforge-assist-help', '/ideaforge-debt', '/ideaforge-gain', '/audit-ideaforge-codebase',
    'Example: /ideaforge-assist ultra', '`/ideaforge-assist off`',
  ]) assert.equal(parseControl(prompt), null, prompt);
  for (const prompt of ['/ideaforge-assist review', '/ideaforge-assist default', '/ideaforge-assist lite extra']) {
    assert.throws(() => parseControl(prompt));
  }
  assert.throws(() => normalizeMode('review'));
  assert.throws(() => normalizeMode(null));
});

test('local defaults have explicit precedence, preserve unknown fields and surface bad config', async (t) => {
  const root = await fixture(t);
  assert.equal((await readConfig(root, {})).defaultMode, 'full');
  await put(root, `${STATE_DIR}/config.json`, '\uFEFF{"defaultMode":"lite","customNote":"keep","mapEnabled":false}');
  assert.equal((await readConfig(root, {})).defaultMode, 'lite');
  assert.equal((await readConfig(root, { IDEAFORGE_ASSIST_DEFAULT_MODE: 'ultra' })).defaultMode, 'ultra');
  assert.deepEqual(await writeDefault(root, 'off', { IDEAFORGE_ASSIST_DEFAULT_MODE: 'full' }),
    { requested: 'off', effective: 'full' });
  assert.equal(JSON.parse(await readFile(join(root, STATE_DIR, 'config.json'))).customNote, 'keep');
  await assert.rejects(readConfig(root, { IDEAFORGE_ASSIST_DEFAULT_MODE: 'invalid' }), /Mode must/);
  for (const value of ['{bad', '[]', '{"defaultMode":"review"}', '{"mapChars":0}', '{"hideStatus":"true"}']) {
    await put(root, `${STATE_DIR}/config.json`, value);
    await assert.rejects(readConfig(root, {}));
  }
});

test('mode state isolates native sessions, clients and worktree roots, never stores prompts or raw IDs', async (t) => {
  const root = await fixture(t);
  const other = await fixture(t);
  await writeMode(root, 'claude', 'same-id', 'ultra');
  await writeMode(root, 'copilot', 'same-id', 'lite');
  await writeMode(root, 'claude', '../../not-a-path', 'off');
  assert.equal((await readMode(root, 'claude', 'same-id', {})).mode, 'ultra');
  assert.equal((await readMode(root, 'copilot', 'same-id', {})).mode, 'lite');
  assert.equal((await readMode(root, 'claude', 'new-id', {})).mode, 'full');
  assert.equal((await readMode(other, 'claude', 'same-id', {})).mode, 'full');
  const files = await readdir(join(root, STATE_DIR));
  assert.ok(files.every((file) => /^session-[a-f0-9]{64}\.json$/.test(file)));
  const state = await readFile((await sessionIdentity(root, 'claude', 'same-id')).file, 'utf8');
  assert.doesNotMatch(state, /same-id|prompt|transcript|secret|timestamp/);
  await assert.rejects(sessionIdentity(root, 'other', 'id'));
  await assert.rejects(sessionIdentity(root, 'claude', ''));
  await assert.rejects(sessionIdentity(root, 'claude', 'bad\nid'));
});

test('corrupt or future state refuses changes; reset deletes only an owned exact session', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'claude', 'a', 'off');
  await writeMode(root, 'claude', 'b', 'lite');
  await writeDefault(root, 'ultra', {});
  await put(root, `${STATE_DIR}/unrelated.txt`, 'keep');
  assert.equal(await resetMode(root, 'claude', 'a'), true);
  assert.equal(await resetMode(root, 'claude', 'a'), false);
  assert.equal((await readMode(root, 'claude', 'a', {})).mode, 'ultra');
  assert.equal((await readMode(root, 'claude', 'b', {})).mode, 'lite');
  assert.equal(await readFile(join(root, STATE_DIR, 'unrelated.txt'), 'utf8'), 'keep');
  const identity = await sessionIdentity(root, 'claude', 'b');
  await writeFile(identity.file, '{"schema":999,"mode":"full"}');
  await assert.rejects(readMode(root, 'claude', 'b', {}), /Invalid or newer/);
  await assert.rejects(writeMode(root, 'claude', 'b', 'off'), /Invalid or newer/);
  await assert.rejects(resetMode(root, 'claude', 'b'), /Invalid or newer/);
  assert.equal(await readFile(identity.file, 'utf8'), '{"schema":999,"mode":"full"}');
});

test('owned state cannot be redirected through a symlink or junction', async (t) => {
  const root = await fixture(t);
  const outside = await fixture(t);
  await symlink(outside, join(root, STATE_DIR), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(readConfig(root, {}), /real directory/);
  await assert.rejects(writeMode(root, 'claude', 'id', 'full'), /real directory/);
  assert.deepEqual((await readdir(outside)).sort(), ['AGENTS.md']);
});

test('canonical rules are extracted fully, mode-filtered and bounded without fallback copies', async (t) => {
  const root = await fixture(t);
  const rules = await leanRules(root, 'ultra');
  assert.match(rules, /preserve security and errors/);
  assert.match(rules, /Never grant permissions/);
  assert.match(rules, /\| `ultra` \|/);
  assert.doesNotMatch(rules, /\| `lite` \||\| `full` \||Unrelated/);
  await writeFile(join(root, 'AGENTS.md'), '# No rules');
  await assert.rejects(leanRules(root, 'full'), /missing or oversized/);
});

test('inventory excludes private settings, signing material, generated outputs and nested worktrees', () => {
  for (const file of [
    '.env', '.env.local', '.claude/settings.local.json', 'CLAUDE.local.md',
    '.github/copilot/settings.local.json', 'android/local.properties', 'android/keystore.properties',
    'src/secret.pem', 'android/app/build/generated.json', 'node_modules/a.js', 'dist/app.js',
    '.worktrees/other/src/app.js', '.ideaforge-assist/config.json',
  ]) assert.equal(sourcePathAllowed(file), false, file);
  for (const file of ['src/core/session.js', 'tools/assistant.mjs', 'android/core/src/Session.kt', 'AGENTS.md']) {
    assert.equal(sourcePathAllowed(file), true, file);
  }
  assert.equal(withinRoot(join(tmpdir(), 'project'), join(tmpdir(), 'project-evil')), false);
});

test('bounded maps name omissions and label symbol extraction as heuristic', async (t) => {
  const root = await fixture(t);
  const files = [];
  for (let i = 0; i < 8; i++) {
    const file = `dir-${i}/file.mjs`;
    files.push(file);
    await put(root, file, `export function ${'long'.repeat(15)}${i}() {}`);
  }
  const map = await buildMap(root, { files, budget: 256 });
  assert.ok(map.text.length <= 256, map.text.length);
  assert.ok(map.omitted > 0);
  assert.match(map.text, /heuristic, not a call graph/);
  assert.match(map.text, /Omitted:/);
  assert.deepEqual(exportedNames('export async function run() {}\nexport const X = 1;\nfun kotlin() {}'),
    ['run', 'X', 'kotlin']);
  await assert.rejects(buildMap(root, { files, budget: 100 }));
});

test('debt counts actual comment markers, not strings/prose, including legacy and missing triggers', () => {
  const text = `const quoted = "// shortcut: not a comment";
const template = \`/* shortcut: also not a comment */\`;
// shortcut: in-memory cache; limit: one tab; upgrade: shared writers
const x = 1; /* ponytail: short list; ceiling: ten items; when: larger import */
/*
 * shortcut: literal table; limit: stable inputs
 */
// mention shortcut: prose, not a marker
`;
  const debt = debtIn(text, 'src/a.js');
  assert.equal(debt.length, 3);
  assert.deepEqual(debt.map((entry) => entry.line), [3, 4, 6]);
  assert.equal(debt[0].upgrade, 'shared writers');
  assert.equal(debt[1].marker, 'ponytail');
  assert.equal(debt[2].upgrade, null);
  assert.equal(commentsIn('x = "# shortcut: string"\n# shortcut: actual', { hashComments: true }).length, 1);
});

test('regexp quotes, escapes and character classes cannot hide subsequent real debt comments', () => {
  const text = [
    'const quotes = /["\\\']/;',
    'const slashes = /[/"\\\']\\//g;',
    'const division = 12 / 2;',
    '// shortcut: direct scan; limit: one file; upgrade: larger input',
  ].join('\n');
  const markers = debtIn(text, 'src/a.js');
  assert.equal(markers.length, 1);
  assert.equal(markers[0].line, 4);
  assert.equal(markers[0].upgrade, 'larger input');
});

test('unsupported YAML literal contexts are disclosed rather than reported as authored debt', async (t) => {
  const root = await fixture(t);
  await put(root, 'example.yml', 'message: |\n  // shortcut: string; limit: example; upgrade: edit example\n');
  const report = await scanDebt(root, { files: ['example.yml'] });
  assert.equal(report.markers.length, 0);
  assert.deepEqual(report.unread, [{
    file: 'example.yml', reason: 'YAML literal contexts are not supported by the debt lexer',
  }]);
});

test('debt scan is report-only and includes unread coverage, excluding documentation examples', async (t) => {
  const root = await fixture(t);
  await put(root, 'src/a.js', '// shortcut: a; limit: b; upgrade: c\n');
  await put(root, 'README.md', '// shortcut: example; upgrade: never\n');
  await put(root, 'src/large.js', '// shortcut: too large\n'.repeat(15000));
  const report = await scanDebt(root, { files: ['src/a.js', 'README.md', 'src/large.js'] });
  assert.equal(report.markers.length, 1);
  assert.deepEqual(report.unread, [{ file: 'src/large.js', reason: 'oversized' }]);
  assert.equal(await readFile(join(root, 'src', 'a.js'), 'utf8'), '// shortcut: a; limit: b; upgrade: c\n');
  assert.equal((await readdir(root)).includes(STATE_DIR), false);
});

test('a tracked source deleted from the working tree is reported as unread, not a broken startup', async (t) => {
  const root = await fixture(t);
  const map = await buildMap(root, { files: ['removed.js'], budget: 256 });
  assert.equal(map.unread, 1);
  assert.match(map.text, /1 unread/);
  const report = await scanDebt(root, { files: ['removed.js'] });
  assert.deepEqual(report.unread, [{ file: 'removed.js', reason: 'deleted from working tree' }]);
});

test('gain is exact-scope counted facts, rejects malformed measurements and never invents savings', async (t) => {
  const root = await fixture(t);
  const git = (_root, args) => {
    if (args[0] === 'ls-files') return 'AGENTS.md\0';
    if (args[0] === 'rev-parse') return `${'a'.repeat(40)}\n`;
    return '3\t1\ta.js\n-\t-\timage.png\n';
  };
  const result = await measureGain(root, { base: 'main', git });
  assert.match(result.scope, new RegExp('a'.repeat(40)));
  assert.equal(result.added, 3);
  assert.equal(result.removed, 1);
  assert.equal(result.binaryFiles, 1);
  assert.match(result.note, /not saved code, tokens, time or cost/);
  assert.doesNotMatch(JSON.stringify(result), /you saved|this repo saved|percent/i);
  assert.throws(() => parseNumstat('bad'));
  assert.throws(() => parseNumstat('-\t2\ta.js'));
});

test('one-shot helpers do not rewrite session/default state; explicit controls do', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'copilot', 'native-session', 'lite');
  await writeDefault(root, 'full', {});
  const guidance = fixtureGuidance({ root, client: 'copilot', env: {} });
  await guidance.start(event(root));
  const file = (await sessionIdentity(root, 'copilot', 'native-session')).file;
  const before = await readFile(file, 'utf8');
  for (const prompt of ['/ideaforge-assist-help', '/ideaforge-debt', '/ideaforge-gain', '/audit-ideaforge-codebase']) {
    assert.equal(await guidance.prompt(event(root, 'native-session', { prompt })), undefined);
    assert.equal(await readFile(file, 'utf8'), before);
  }
  const output = await guidance.prompt(event(root, 'native-session', { prompt: '/ideaforge-assist ultra' }));
  assert.match(output, /ULTRA/);
  assert.equal((await readMode(root, 'copilot', 'native-session', {})).mode, 'ultra');
  await guidance.prompt(event(root, 'native-session', { prompt: '/ideaforge-assist default off' }));
  assert.equal((await readMode(root, 'copilot', 'native-session', {})).mode, 'ultra');
  assert.equal((await readMode(root, 'copilot', 'new-session', {})).mode, 'off');
});

test('startup/resume/clear and compaction honor real session identity without repeated context', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'copilot', 'resume-me', 'ultra');
  const guidance = fixtureGuidance({ root, client: 'copilot', env: {} });
  assert.match(await guidance.start(event(root, 'resume-me', { source: 'resume' })), /ULTRA/);
  assert.equal(await guidance.prompt(event(root, 'resume-me', { prompt: 'continue' })), undefined);
  guidance.compacted('resume-me');
  assert.match(await guidance.beforeTool(event(root, 'resume-me')), /ULTRA/);
  assert.equal(await guidance.beforeTool(event(root, 'resume-me')), undefined);
  assert.match(await guidance.start(event(root, 'clear-new', { source: 'new' })), /FULL/);
  await guidance.prompt(event(root, 'resume-me', { prompt: '/ideaforge-assist off' }));
  assert.equal(await guidance.child(event(root, 'resume-me', { agentName: 'research' })), undefined);
  assert.match(await guidance.prompt(event(root, 'resume-me', { prompt: '/ideaforge-assist' })), /FULL/);
  await assert.rejects(guidance.start(event(dirname(root))), /outside/);
});

test('late SDK attachment seeds one session default; later default changes cannot alter it', async (t) => {
  const root = await fixture(t);
  const guidance = fixtureGuidance({ root, client: 'copilot', env: {} });
  assert.match(await guidance.prompt(event(root, 'late', { prompt: '/ideaforge-assist-help' })), /FULL/);
  assert.equal((await readMode(root, 'copilot', 'late', {})).exists, true);
  await guidance.prompt(event(root, 'late', { prompt: '/ideaforge-assist default off' }));
  assert.equal((await readMode(root, 'copilot', 'late', {})).mode, 'full');
  assert.equal((await readMode(root, 'copilot', 'fresh', {})).mode, 'off');
});

test('rapid native controls settle in arrival order, and a failed control does not strand the queue', async (t) => {
  const root = await fixture(t);
  const guidance = fixtureGuidance({ root, client: 'copilot', env: {} });
  const outputs = await Promise.all([
    guidance.prompt(event(root, 'rapid', { prompt: '/ideaforge-assist lite' })),
    guidance.prompt(event(root, 'rapid', { prompt: '/ideaforge-assist ultra' })),
    guidance.prompt(event(root, 'rapid', { prompt: '/ideaforge-assist off' })),
  ]);
  assert.match(outputs[0], /LITE/);
  assert.match(outputs[1], /ULTRA/);
  assert.match(outputs[2], /OFF/);
  assert.equal((await readMode(root, 'copilot', 'rapid', {})).mode, 'off');
  await assert.rejects(guidance.prompt(event(root, 'rapid', { prompt: '/ideaforge-assist wrong' })));
  assert.match(await guidance.prompt(event(root, 'rapid', { prompt: '/ideaforge-assist full' })), /FULL/);
});

test('reset then changing the default preserves the current session mode in both native adapter shapes', async (t) => {
  const root = await fixture(t);
  const guidance = fixtureGuidance({ root, client: 'copilot', env: {} });
  await guidance.start(event(root));
  await guidance.prompt(event(root, 'native-session', { prompt: '/ideaforge-assist reset' }));
  const out = await guidance.prompt(event(root, 'native-session', { prompt: '/ideaforge-assist default off' }));
  assert.match(out, /mode is unchanged/);
  assert.match(out, /FULL/);
  assert.equal((await readMode(root, 'copilot', 'native-session', {})).mode, 'full');
  await writeDefault(root, 'full', {});
  const input = { cwd: root, session_id: 'claude-reset', prompt: '/ideaforge-assist reset' };
  await claudeHook('UserPromptSubmit', input, { root, env: {} });
  const claude = await claudeHook('UserPromptSubmit', {
    ...input, prompt: '/ideaforge-assist default off',
  }, { root, env: {} });
  assert.match(claude, /FULL/);
  assert.equal((await readMode(root, 'claude', 'claude-reset', {})).mode, 'full');
});

test('a lexical descendant that is a nested worktree is not the main helper trusted root', async (t) => {
  const root = await fixture(t);
  const nested = join(root, '.worktrees', 'other');
  await mkdir(nested, { recursive: true });
  await writeFile(join(nested, '.git'), 'gitdir: elsewhere');
  await assert.rejects(trustedWorkingDirectory(root, nested), /different worktree/);
  await put(root, 'src/core/fixture.js', 'export const a = 1');
  assert.equal(await trustedWorkingDirectory(root, join(root, 'src', 'core')), join(root, 'src', 'core'));
});

test('child guidance preserves review restrictions, and never grants authority or edits tool data', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'copilot', 'native-session', 'ultra');
  const guidance = fixtureGuidance({ root, client: 'copilot', env: {} });
  assert.match(await guidance.child(event(root, 'native-session', { agentName: 'research' })), /ULTRA/);
  for (const agentName of ['ideaforge-review-claude', 'ideaforge-docs-review-copilot']) {
    const out = await guidance.child(event(root, 'native-session', { agentName }));
    assert.match(out, /strictly read-only and exact-diff-scoped/);
    assert.match(out, /Do not change modes, run commands or implement/);
  }
  const hooks = copilotHooks(guidance);
  const input = event(root, 'native-session', { prompt: '/ideaforge-assist lite' });
  const saved = structuredClone(input);
  const out = await hooks.onUserPromptSubmitted(input);
  assert.deepEqual(input, saved);
  assert.deepEqual(Object.keys(out), ['additionalContext']);
  assert.doesNotMatch(JSON.stringify(out), /modifiedPrompt|modifiedArgs|permissionDecision|modifiedResult/);
});

test('Copilot joins the foreground with hooks only, no tools/secrets/model calls or session creation', async (t) => {
  const root = await fixture(t);
  const subscriptions = [];
  let config;
  const session = {
    sessionId: 'native-session',
    log: async () => {},
    on: (name, handler) => subscriptions.push({ name, handler }),
  };
  const joined = await joinAssistant(async (options) => { config = options; return session; },
    { root, env: {}, mapSource: fixtureMap });
  assert.equal(joined, session);
  assert.deepEqual(Object.keys(config), ['hooks']);
  assert.deepEqual(Object.keys(config.hooks).sort(),
    ['onPreToolUse', 'onUserPromptSubmitted']);
  assert.equal(subscriptions[0].name, 'session.compaction_complete');
  await config.hooks.onUserPromptSubmitted(event(root, 'native-session', { prompt: 'ordinary task' }));
  subscriptions[0].handler();
  const restored = await config.hooks.onPreToolUse(event(root, 'native-session', { toolName: 'view', toolArgs: {} }));
  assert.deepEqual(Object.keys(restored), ['additionalContext']);
});

test('Claude emits the native child envelope, but ordinary prompt hooks do not re-inject the full map', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'claude', 'native-session', 'lite');
  const payload = { cwd: root, session_id: 'native-session', agent_type: 'Explore' };
  const out = JSON.parse(await claudeHook('SubagentStart', payload, { root, env: {} }));
  assert.equal(out.hookSpecificOutput.hookEventName, 'SubagentStart');
  assert.match(out.hookSpecificOutput.additionalContext, /LITE/);
  const ordinary = await claudeHook('UserPromptSubmit', { ...payload, prompt: 'fix the bug' }, { root, env: {} });
  assert.equal(ordinary, '');
  await assert.rejects(claudeHook('Unknown', payload, { root }));
});

test('Copilot command hooks supply startup/child context without pretending dropped prompt output refreshed it', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'copilot', 'native-session', 'lite');
  const input = { cwd: root, sessionId: 'native-session', agentName: 'research' };
  const child = JSON.parse(await copilotHook('subagentStart', input, { root, env: {} }));
  assert.match(child.additionalContext, /LITE/);
  assert.equal(await copilotHook('userPromptSubmitted', {
    ...input, prompt: '/ideaforge-assist ultra',
  }, { root, env: {} }), '{}');
  assert.equal((await readMode(root, 'copilot', 'native-session', {})).mode, 'ultra');
});

test('Copilot compatibility-loading Claude settings cannot emit a second persona or write Claude state', async (t) => {
  const root = await fixture(t);
  const input = { cwd: root, session_id: 'compat-session', timestamp: '2026-01-01T00:00:00Z' };
  for (const name of ['SessionStart', 'UserPromptSubmit', 'SubagentStart']) {
    assert.equal(await claudeHook(name, { ...input, prompt: '/ideaforge-assist off' },
      { root, env: {} }), '');
  }
  assert.equal((await readMode(root, 'claude', 'compat-session', {})).exists, false);
  const command = JSON.parse(await readFile(join(ROOT, '.claude', 'settings.json'), 'utf8'))
    .hooks.SessionStart[0].hooks[0].command;
  const env = { ...process.env };
  delete env.CLAUDE_PROJECT_DIR;
  const out = spawnSync(command, { shell: true, encoding: 'utf8', timeout: 5000,
    input: JSON.stringify(input), env });
  assert.equal(out.status, 0, out.stderr);
  assert.equal(out.stdout, '');
  assert.equal(out.stderr, '');
});

test('bounded hook input detects complete JSON, malformed and oversized input', async () => {
  const input = new PassThrough();
  const result = readHookInput(input);
  input.end('{"sessionId":"test"}');
  assert.deepEqual(await result, { sessionId: 'test' });
  for (const text of ['{bad', '[]', 'null']) {
    const stream = new PassThrough();
    const out = readHookInput(stream);
    stream.end(text);
    await assert.rejects(out);
  }
  const large = new PassThrough();
  const out = readHookInput(large, { maxBytes: 2 });
  large.end('{"long":"text"}');
  await assert.rejects(out, /byte budget/);
});

test('status uses a real native ID, supports hidden badges and never guesses last-writer state', async (t) => {
  const root = await fixture(t);
  await writeMode(root, 'claude', 'a', 'lite');
  await writeMode(root, 'claude', 'b', 'ultra');
  assert.equal(await runAssistant(['status', '--client', 'claude', '--session', 'a'], { root, env: {} }),
    '[IDEAFORGE:LITE]');
  const stdin = new PassThrough();
  const out = runAssistant(['status', '--client', 'claude'], { root, env: {}, stdin });
  stdin.end(JSON.stringify({ session_id: 'b', cwd: root }));
  assert.equal(await out, '[IDEAFORGE:ULTRA]');
  await put(root, `${STATE_DIR}/config.json`, '{"hideStatus":true}');
  assert.equal(await runAssistant(['status', '--client', 'claude', '--session', 'a'], { root, env: {} }), '');
  await assert.rejects(runAssistant(['mode', 'off', '--client', 'claude'], { root, env: {} }), /real native/);
  await assert.rejects(runAssistant(['status', '--client', 'unknown', '--session', 'a'], { root, env: {} }));
});

test('the shipped Claude command reads its root inside Node, including spaces and hostile shell characters', async (t) => {
  const root = await fixture(t);
  const commands = JSON.parse(await readFile(join(ROOT, '.claude', 'settings.json'), 'utf8')).hooks;
  assert.deepEqual(Object.keys(commands), ['SessionStart', 'UserPromptSubmit', 'SubagentStart']);
  assert.match(commands.SessionStart[0].matcher, /fork/);
  for (const eventName of Object.keys(commands)) {
    const cmd = commands[eventName][0].hooks[0].command;
    assert.doesNotMatch(cmd, /\$\{CLAUDE_PROJECT_DIR\}|%CLAUDE_PROJECT_DIR%|ExecutionPolicy|--allow-all/);
    assert.match(cmd, /process\.env\.CLAUDE_PROJECT_DIR/);
  }
  const hostile = join(root, "spaces & apostrophe' dollar$ semicolon;");
  await mkdir(join(hostile, 'tools'), { recursive: true });
  await writeFile(join(hostile, 'AGENTS.md'), RULES);
  for (const file of ['assistant-process.mjs', 'assistant.mjs', 'assistant-hook.mjs']) {
    await copyFile(join(ROOT, 'tools', file), join(hostile, 'tools', file));
  }
  const actual = spawnSync(commands.SubagentStart[0].hooks[0].command, {
    shell: true, cwd: hostile, encoding: 'utf8', timeout: 5000,
    input: JSON.stringify({ cwd: hostile, session_id: 'hostile-path', agent_type: 'Explore' }),
    env: { ...process.env, CLAUDE_PROJECT_DIR: hostile, IDEAFORGE_ASSIST_DEFAULT_MODE: 'full' },
  });
  assert.equal(actual.status, 0, actual.stderr);
  assert.match(JSON.parse(actual.stdout).hookSpecificOutput.additionalContext, /FULL/);

  const script = fileURLToPath(new URL('../tools/assistant-hook.mjs', import.meta.url));
  const out = spawnSync(process.execPath, [script, 'SubagentStart'], {
    encoding: 'utf8', timeout: 5000,
    input: JSON.stringify({ cwd: ROOT, session_id: 'fixture-protocol-only', agent_type: 'Explore' }),
    env: { ...process.env, IDEAFORGE_ASSIST_DEFAULT_MODE: 'off' },
  });
  assert.equal(out.status, 0, out.stderr);
  assert.equal(out.stdout, '');
});

test('a native hook with an open never-written stdin exits visibly instead of stranding PowerShell', async () => {
  const script = fileURLToPath(new URL('../tools/assistant-hook.mjs', import.meta.url));
  const child = spawn(process.execPath, [script, 'UserPromptSubmit'], { windowsHide: true });
  let stderr = '';
  child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
  const deadline = setTimeout(() => child.kill(), 5000);
  const code = await new Promise((accept, reject) => {
    child.once('error', reject);
    child.once('close', accept);
  });
  clearTimeout(deadline);
  assert.equal(code, 1);
  assert.match(stderr, /stdin did not finish in time/);
});

test('the real Copilot exec manifest command consumes JSON and hostile paths as data without a shell', async (t) => {
  const root = await fixture(t);
  const repo = join(root, "git & quote' dollar$ ;");
  await mkdir(join(repo, 'tools'), { recursive: true });
  await writeFile(join(repo, 'AGENTS.md'), RULES);
  for (const file of ['assistant-process.mjs', 'assistant.mjs', 'assistant-hook.mjs']) {
    await copyFile(join(ROOT, 'tools', file), join(repo, 'tools', file));
  }
  const init = spawnSync('git', ['init', '--quiet', '--template=', repo], { encoding: 'utf8', windowsHide: true });
  assert.equal(init.status, 0, init.stderr);
  const hooks = JSON.parse(await readFile(join(ROOT, '.github', 'hooks', 'ideaforge-assist.json'), 'utf8')).hooks;
  const action = hooks.subagentStart[0];
  const out = spawnSync(action.exec, action.args, {
    cwd: repo, encoding: 'utf8', windowsHide: true, timeout: 5000,
    input: JSON.stringify({ cwd: repo, sessionId: 'native-exec', agentName: 'research' }),
    env: { ...process.env, IDEAFORGE_ASSIST_DEFAULT_MODE: 'full' },
  });
  assert.equal(out.status, 0, out.stderr);
  assert.match(JSON.parse(out.stdout).additionalContext, /FULL/);
});
