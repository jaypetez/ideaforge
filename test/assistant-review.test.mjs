import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { ROOT, STATE_DIR } from '../tools/assistant-process.mjs';
import {
  opensPullRequest, prGateDecision, prepareReview, recordReview, readReview,
} from '../tools/assistant-review.mjs';
import { runAssistant } from '../tools/assistant.mjs';
import { claudeHook, copilotHook } from '../tools/assistant-hook.mjs';

const TOOLS = ['assistant-process.mjs', 'assistant.mjs', 'assistant-hook.mjs', 'assistant-review.mjs'];

function git(repo, ...args) {
  const out = spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8', windowsHide: true });
  assert.equal(out.status, 0, `git ${args.join(' ')}: ${out.stderr}`);
  return out.stdout.trim();
}

async function put(repo, file, text) {
  await mkdir(dirname(join(repo, file)), { recursive: true });
  await writeFile(join(repo, file), text);
}

// No .gitignore here, so the gate's own exclusion of the state directory is what keeps the
// tree clean after prepare; staging must exclude it the same way.
function commit(repo, message) {
  git(repo, 'add', '-A', '--', '.', `:(exclude)${STATE_DIR}`);
  git(repo, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
    '-c', 'commit.gpgsign=false', 'commit', '--quiet', '--no-verify', '-m', message);
  return git(repo, 'rev-parse', 'HEAD');
}

// A repository on a feature branch one commit ahead of main, with the real tools committed
// so the shipped hook commands can run inside it against a clean tree.
async function repository(t) {
  const dir = await mkdtemp(join(tmpdir(), 'ideaforge-review-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const repo = join(dir, "repo & quote' $");
  const init = spawnSync('git', ['init', '--quiet', '--template=', '-b', 'main', repo],
    { encoding: 'utf8', windowsHide: true });
  assert.equal(init.status, 0, init.stderr);
  git(repo, 'config', 'core.autocrlf', 'false');
  await put(repo, 'AGENTS.md', '# Fixture\n');
  for (const file of TOOLS) {
    await mkdir(join(repo, 'tools'), { recursive: true });
    await copyFile(join(ROOT, 'tools', file), join(repo, 'tools', file));
  }
  const base = commit(repo, 'base');
  git(repo, 'switch', '--quiet', '-c', 'feature');
  await put(repo, 'src/feature.js', 'export const value = 1;\n');
  const head = commit(repo, 'feature');
  return { repo, base, head };
}

test('only an executed gh pr create or new is a pull request, not quoted or heredoc text', () => {
  for (const command of [
    'gh pr create',
    'gh  pr   create --fill',
    'gh.exe pr create --base main',
    'C:\\Progra~1\\GitHubCLI\\gh.exe pr create',
    'gh pr new --title x',
    'npm run test:all && gh pr create --base main --fill',
    'if ($LASTEXITCODE -eq 0) { gh pr create --fill }',
    'cat <<\'EOF\' > body.md\nmentions gh pr create\nEOF\ngh pr create --body-file body.md',
    'gh pr create --title "unterminated',
    'GH PR CREATE',
    // Each of these ran a PR past the first matcher, which stripped them as quoted data.
    'url="$(gh pr create --fill)"',
    'url="$(gh pr create --title "x" --fill)"',
    'echo "PR: `gh pr create --fill`"',
    '# don\'t wait\ngh pr create --fill\necho \'opened\'',
    'gh pr -R owner/repo create --fill',
    'gh pr --repo=owner/repo new',
    'cat <<\'EOF\' | gh pr create --body-file -\nBody\nEOF',
    'cat > b.md <<\'EOF\' && gh pr create --body-file b.md\nBody\nEOF',
  ]) assert.equal(opensPullRequest(command), true, command);
  // PowerShell has no backslash escape, so this string ends at its own closing quote.
  const powershell = 'Set-Location "C:\\repo\\"; gh pr create --title "x"';
  assert.equal(opensPullRequest(powershell, 'powershell'), true);
  assert.equal(opensPullRequest(powershell), true, 'an unknown shell is read both ways');
  assert.equal(opensPullRequest('$url = "$(gh pr create --fill)"', 'powershell'), true);
  assert.equal(opensPullRequest("$b = @\"\n$(gh pr create)\n\"@", 'powershell'), true);
  assert.equal(opensPullRequest("git commit -m @'\ngh pr create\n'@", 'powershell'), false);
  assert.equal(opensPullRequest('git commit -m "say `"gh pr create`""', 'powershell'), false);
  assert.equal(opensPullRequest('<# gh pr create #> git status', 'powershell'), false);
  // A command handed to another interpreter as a string is documented as unrecognised.
  assert.equal(opensPullRequest('bash -c "gh pr create"', 'posix'), false);
  for (const command of [
    'gh pr view 12',
    'gh pr checks --watch',
    'gh pr created',
    'git push -u origin HEAD',
    'git commit -m "explain why gh pr create is gated"',
    "git commit -m 'gh pr create'",
    "git commit -m @'\ngh pr create\n'@",
    'mygh pr create',
    'echo gh-pr-create',
    undefined,
    { command: 'gh pr create' },
  ]) assert.equal(opensPullRequest(command), false, String(command));
  // Heredocs are POSIX syntax; an unknown shell also reads them as PowerShell and refuses.
  for (const command of [
    'git commit -F - <<\'EOF\'\nGate it\ngh pr create waits for review\nEOF',
    'git commit -m "$(cat <<\'EOF\'\nGate\ngh pr create\nEOF\n)"',
    'git commit -m "say \\"gh pr create\\" later"',
    'git log --grep="gh pr create"',
    'echo hi # then gh pr create',
  ]) assert.equal(opensPullRequest(command, 'posix'), false, command);
});

test('a PR command that changes directory first is refused, since the hook knows only the session cwd', async (t) => {
  const { repo } = await repository(t);
  await prepareReview(repo);
  await recordReview(repo, { result: 'clean' });
  assert.equal(await prGateDecision({ command: 'gh pr create --fill', shell: 'posix', cwd: repo }), null);
  for (const [command, shell] of [
    ['cd ../unreviewed && gh pr create --fill', 'posix'],
    ['pushd /tmp; gh pr create', 'posix'],
    ['Set-Location ..\\other; gh pr create', 'powershell'],
    ['sl ..; gh pr new', 'powershell'],
  ]) {
    assert.match(await prGateDecision({ command, shell, cwd: repo }), /changes directory/, command);
  }
  assert.equal(await prGateDecision({ command: 'cd src && npm test', shell: 'posix', cwd: repo }), null);
  assert.equal(await prGateDecision({ command: 'gh pr create --title "cd later"', shell: 'posix', cwd: repo }),
    null, 'a quoted word is not a directory change');
});

test('the gate refuses an unreviewed, pending, stale or dirty HEAD and abstains once it passed', async (t) => {
  const { repo, head } = await repository(t);
  const gate = (command = 'gh pr create --fill') => prGateDecision({ command, cwd: repo, root: ROOT });
  assert.match(await gate(), /no review is recorded for HEAD/);
  assert.match(await gate(), /review-ideaforge-before-pr/);
  await prepareReview(repo);
  assert.match(await gate(), /still pending/);
  await recordReview(repo, { result: 'clean' });
  assert.equal(await gate(), null);
  assert.equal((await readReview(repo, head)).result, 'clean');

  await put(repo, 'src/feature.js', 'export const value = 2;\n');
  assert.match(await gate(), /uncommitted changes/);
  git(repo, 'checkout', '--', 'src/feature.js');
  await put(repo, 'scratch.txt', 'untracked\n');
  assert.match(await gate(), /uncommitted changes/, 'an untracked file is outside the reviewed diff');
  await rm(join(repo, 'scratch.txt'));
  assert.equal(await gate(), null);

  await put(repo, 'src/later.js', 'export {};\n');
  commit(repo, 'after review');
  assert.match(await gate(), /no review is recorded for HEAD/, 'a passed review does not cover a later commit');
  assert.equal(await gate('git push -u origin HEAD'), null);
});

test('the gate reads the repository it runs in and fails closed when it cannot', async (t) => {
  const { repo, head } = await repository(t);
  await prepareReview(repo);
  await recordReview(repo, { result: 'clean' });
  // The session cwd is a subdirectory of the reviewed repository; root is elsewhere.
  assert.equal(await prGateDecision({ command: 'gh pr create', cwd: join(repo, 'src'), root: ROOT }), null);

  await writeFile(join(repo, STATE_DIR, `review-${head}.json`), '{"kind":"ideaforge-pr-review","schema":2}');
  assert.match(await prGateDecision({ command: 'gh pr create', cwd: repo }), /could not check/);

  const broken = () => { throw new Error('git is missing'); };
  assert.match(await prGateDecision({ command: 'gh pr create', cwd: repo, git: broken }),
    /could not check this repository \(git is missing\)/);
  assert.equal(await prGateDecision({ command: 'npm test', cwd: repo, git: broken }), null,
    'an ordinary command never reaches Git');
});

test('prepare writes the exact branch diff through Git, past the stdout buffer, and flags documentation', async (t) => {
  const { repo, base, head } = await repository(t);
  const out = await prepareReview(repo);
  assert.equal(out.base, base);
  assert.equal(out.head, head);
  assert.deepEqual(out.files, ['src/feature.js']);
  assert.equal(out.docsChanged, false);
  assert.ok(out.diff.endsWith(join(STATE_DIR, `review-${head}.diff`)), out.diff);
  assert.match(await readFile(out.diff, 'utf8'), /^\+export const value = 1;$/m);

  await put(repo, 'docs/large.md', `${'0123456789abcdef'.repeat(4)}\n`.repeat(48 * 1024));
  const next = commit(repo, 'large documentation');
  const large = await prepareReview(repo);
  assert.equal(large.docsChanged, true);
  const expected = spawnSync('git', ['-C', repo, 'diff', '--no-color', '--no-ext-diff', '--no-textconv',
    base, next, '--'], { windowsHide: true, maxBuffer: 64 * 1024 * 1024 }).stdout;
  assert.ok(expected.length > 2 * 1024 * 1024, 'the fixture must exceed runGit\'s buffer');
  assert.ok((await readFile(large.diff)).equals(expected), 'the diff file is byte-identical to git diff');

  // origin/main wins over a local main, so a stale local branch cannot widen the diff.
  git(repo, 'update-ref', 'refs/remotes/origin/main', head);
  const narrowed = await prepareReview(repo);
  assert.equal(narrowed.base, head);
  assert.deepEqual(narrowed.files, ['docs/large.md']);
  assert.equal((await prepareReview(repo, { base: 'main' })).base, base);
  await assert.rejects(prepareReview(repo, { base: 'no-such-ref' }));
  await assert.rejects(prepareReview(repo, { base: 'bad\nref' }), /exact Git commit/);
});

test('prepare and record refuse what a review cannot cover', async (t) => {
  const { repo } = await repository(t);
  await assert.rejects(recordReview(repo, { result: 'clean' }), /run review prepare first/);
  await put(repo, 'src/feature.js', 'dirty\n');
  await assert.rejects(prepareReview(repo), /Commit or remove every change/);
  git(repo, 'checkout', '--', 'src/feature.js');
  await prepareReview(repo);
  await assert.rejects(recordReview(repo, { result: 'pending' }), /clean or waived/);
  await assert.rejects(recordReview(repo, { result: 'waived' }), /user's reason/);
  await assert.rejects(recordReview(repo, { result: 'waived', reason: 'two\nlines' }), /user's reason/);
  await assert.rejects(recordReview(repo, { result: 'clean', reason: 'why' }), /Only a waived/);
  const waived = await recordReview(repo, { result: 'waived', reason: ' user asked to skip ' });
  assert.equal(waived.reason, 'user asked to skip');
  assert.equal(await prGateDecision({ command: 'gh pr create', cwd: repo }), null);
  assert.equal((await recordReview(repo, { result: 'clean' })).reason, undefined);

  await put(repo, 'src/feature.js', 'dirty\n');
  await assert.rejects(recordReview(repo, { result: 'clean' }), /Commit or remove every change/);
  git(repo, 'checkout', '--', 'src/feature.js');
  git(repo, 'switch', '--quiet', 'main');
  await assert.rejects(prepareReview(repo), /nothing to review/);
});

test('the review CLI prepares, records and rejects misplaced options', async (t) => {
  const { repo, head } = await repository(t);
  const prepared = await runAssistant(['review', 'prepare'], { root: repo });
  assert.match(prepared, new RegExp(`HEAD ${head}`));
  assert.match(prepared, /Diff: .*review-[a-f0-9]+\.diff/);
  assert.match(prepared, /Documentation changed: no/);
  assert.match(prepared, /^ {2}src\/feature\.js$/m);
  assert.equal(await runAssistant(['review', 'record', '--result', 'clean'], { root: repo }),
    `Review recorded for HEAD ${head}: clean.`);
  for (const args of [
    ['review'], ['review', 'bogus'], ['review', 'prepare', '--result', 'clean'],
    ['review', 'record', '--base', 'main'], ['gain', '--reason', 'x'], ['review', 'record', '--force', 'x'],
  ]) await assert.rejects(runAssistant(args, { root: repo }), undefined, args.join(' '));
});

test('both adapters deny in their native envelope and abstain without approving', async (t) => {
  const { repo } = await repository(t);
  const claude = (command, extra = {}) => claudeHook('PreToolUse',
    { cwd: repo, session_id: 's', tool_name: 'Bash', tool_input: { command }, ...extra }, { root: repo });
  const denied = JSON.parse(await claude('gh pr create --fill'));
  assert.deepEqual(Object.keys(denied.hookSpecificOutput),
    ['hookEventName', 'permissionDecision', 'permissionDecisionReason']);
  assert.equal(denied.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.equal(denied.hookSpecificOutput.permissionDecision, 'deny');
  assert.equal(await claude('git status'), '');
  assert.equal(await claude('gh pr create', { timestamp: 1 }), '', 'a Copilot compatibility payload is not Claude\'s');

  const copilot = (toolArgs) => copilotHook('preToolUse',
    { cwd: repo, sessionId: 's', timestamp: 1, toolName: 'powershell', toolArgs }, { root: repo });
  assert.equal(JSON.parse(await copilot(JSON.stringify({ command: 'gh pr create' }))).permissionDecision, 'deny');
  assert.equal(JSON.parse(await copilot({ command: 'gh pr new' })).permissionDecision, 'deny');
  assert.equal(await copilot(JSON.stringify({ command: 'git status' })), '{}');
  assert.equal(await copilot('{not json'), '{}');
  assert.equal(await copilot(JSON.stringify({ path: 'gh pr create' })), '{}');

  await prepareReview(repo);
  await recordReview(repo, { result: 'clean' });
  assert.equal(await claude('gh pr create --fill'), '');
  assert.equal(await copilot(JSON.stringify({ command: 'gh pr create' })), '{}');
});

test('the shipped pre-tool commands gate a real repository and never deny on adapter failure', async (t) => {
  const { repo } = await repository(t);
  const claudeCommand = JSON.parse(await readFile(join(ROOT, '.claude', 'settings.json'), 'utf8'))
    .hooks.PreToolUse[0].hooks[0].command;
  const runClaude = (input, env = { ...process.env, CLAUDE_PROJECT_DIR: repo }) =>
    spawnSync(claudeCommand, { shell: true, cwd: repo, encoding: 'utf8', timeout: 10000, input, env });
  const claude = runClaude(JSON.stringify({ cwd: repo, session_id: 's', tool_name: 'Bash',
    tool_input: { command: 'gh pr create --dry-run' } }));
  assert.equal(claude.status, 0, claude.stderr);
  assert.equal(JSON.parse(claude.stdout).hookSpecificOutput.permissionDecision, 'deny');
  // A PR body far past a lifecycle event's 64 KB budget still reaches the gate.
  const long = runClaude(JSON.stringify({ cwd: repo, session_id: 's', tool_name: 'Bash',
    tool_input: { command: `gh pr create --body "${'x'.repeat(200 * 1024)}"` } }));
  assert.equal(JSON.parse(long.stdout).hookSpecificOutput.permissionDecision, 'deny');

  const action = JSON.parse(await readFile(join(ROOT, '.github', 'hooks', 'ideaforge-assist.json'), 'utf8'))
    .hooks.preToolUse[0];
  const runCopilot = (input) => spawnSync(action.exec, action.args,
    { cwd: repo, encoding: 'utf8', windowsHide: true, timeout: 10000, input });
  const copilot = runCopilot(JSON.stringify({ cwd: repo, sessionId: 's', timestamp: 1,
    toolName: 'bash', toolArgs: JSON.stringify({ command: 'gh pr create' }) }));
  assert.equal(copilot.status, 0, copilot.stderr);
  assert.equal(JSON.parse(copilot.stdout).permissionDecision, 'deny');
  const garbled = runCopilot('{not json');
  assert.equal(garbled.status, 0, 'a non-zero exit would make Copilot deny every tool call');
  assert.equal(garbled.stdout.trim(), '{}');
  assert.match(garbled.stderr, /review gate unavailable/);
  // Locating the repository can fail too (no checkout, dubious ownership, no git on PATH).
  const outside = await mkdtemp(join(tmpdir(), 'ideaforge-review-outside-'));
  t.after(() => rm(outside, { recursive: true, force: true }));
  const lost = spawnSync(action.exec, action.args, { cwd: outside, encoding: 'utf8', windowsHide: true,
    timeout: 10000, input: JSON.stringify({ cwd: outside, toolName: 'bash',
      toolArgs: JSON.stringify({ command: 'git status' }) }) });
  assert.equal(lost.status, 0, 'a failed rev-parse must not deny every tool call');
  assert.equal(lost.stdout.trim(), '{}');
  assert.match(lost.stderr, /review gate unavailable/);

  await prepareReview(repo);
  await recordReview(repo, { result: 'clean' });
  const passed = runClaude(JSON.stringify({ cwd: repo, session_id: 's', tool_name: 'Bash',
    tool_input: { command: 'gh pr create --dry-run' } }));
  assert.equal(passed.status, 0, passed.stderr);
  assert.equal(passed.stdout, '');
});
