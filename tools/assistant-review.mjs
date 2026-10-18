// The pre-PR review record and the gate that reads it. A workflow guard for assistant
// sessions, not a security boundary: the review CLI is callable by the same model, and a
// person can always open a PR from their own terminal.
import { randomUUID } from 'node:crypto';
import { rename, unlink } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import {
  ROOT, STATE_DIR, runGit, stateDirectory, readJson, writeJson, statOrMissing,
} from './assistant-process.mjs';

export const REVIEW_SKILL = 'review-ideaforge-before-pr';
export const REVIEW_RESULTS = Object.freeze(['pending', 'clean', 'waived']);
const PASSING = new Set(['clean', 'waived']);
const COMMIT = /^[a-f0-9]{40,64}$/;
const DIFF_MS = 60_000;
const MAX_REASON = 500;

// Parsing shell to tell a quoted mention from a real call was an arms race three review
// rounds kept finding new gaps in. The rule is blunt instead: line continuations are
// joined, quotes and backticks dropped, backslashes read as path separators, and a word
// counts in command position: after a line start, a separator, a bracket or `$(`,
// optionally behind assignments (`A=b`, PowerShell `$x =`), a control keyword or a
// wrapper word. Quoted text is read the same way, so inline message, title or body text
// can be refused; pass it from a file instead.
// Linear by construction, because this runs on every shell command: a token stops at a
// separator, whitespace inside a prefix never crosses a line, a wrapper takes only flags,
// numbers and flag values as arguments, and the prefix chain is bounded.
const TOKEN = String.raw`[^\s;&|(){}]`;
const SPACE = String.raw`[^\S\n]`;
const WRAPPER = String.raw`(?:exec|command|time|env|nohup|xargs|timeout|nice|sudo|doas)` +
  String.raw`(?:${SPACE}+(?:-${TOKEN}*(?:${SPACE}+[^\s\-;&|(){}]${TOKEN}*)?|\d${TOKEN}*)){0,3}`;
const PREFIX = String.raw`(?:\w+=${TOKEN}*|if|elif|while|until|!|then|do|else|${WRAPPER})${SPACE}+` +
  String.raw`|(?:\[[\w.]+\])*\$[\w:]+${SPACE}*=${SPACE}*`;
const COMMAND = String.raw`(?:^|[\n;&|(){}]|\$\()${SPACE}*(?:${PREFIX}){0,6}(?:${TOKEN}*/)?`;
// Up to four flags, each with an optional value, may sit before the subcommand:
// `gh pr -R owner/repo create`. Bounded, so it cannot backtrack exponentially.
const FLAGS = String.raw`(?:-\S+\s+(?:[^\s-]\S*\s+)?){0,4}`;
const PR_CREATE = new RegExp(String.raw`${COMMAND}gh(?:\.exe)?\s+pr\s+${FLAGS}(?:create|new)\b`, 'i');
const CHANGES_DIRECTORY = new RegExp(
  String.raw`${COMMAND}(?:cd|chdir|pushd|popd|sl|set-location|push-location|pop-location)(?=[\s;&|)}]|$)`, 'i');
const MOVES_HEAD = new RegExp(
  String.raw`${COMMAND}git(?:\.exe)?\s+${FLAGS}(?:commit|rebase|pull|merge|switch|checkout|reset|am|cherry-pick|revert)\b`, 'i');
// `--head`, `-H`, or `-H` combined with other short flags (`-fH`).
const NAMES_HEAD = /(?:^|\s)(?:--head\b|-[A-Za-z]*H)/;

const words = (command) => (typeof command === 'string'
  ? command.replace(/[\\`]\r?\n/g, ' ').replace(/['"`]/g, '').replace(/\\/g, '/')
  : '');

// The first PR invocation from `gh` to the end of the whole command, flags before the
// subcommand included, or null. A quoted separator in its own arguments must not hide a
// later `--head`, so a `-H` in a later command is refused as well. Every later invocation
// is a suffix of this one, so reading only the first keeps the check linear.
function firstPullRequest(command) {
  const text = words(command);
  const match = PR_CREATE.exec(text);
  return match ? text.slice(match.index) : null;
}

// A quoted path containing spaces, a command handed to another interpreter as a string
// (`bash -c "…"`) and PowerShell splatting (`gh @args`) are not recognised.
export const opensPullRequest = (command) => firstPullRequest(command) !== null;

// The hook sees the session's directory and HEAD before the command runs, not a directory
// the command moves to, a commit it makes or a branch it switches to on the way to gh.
export const changesDirectory = (command) => CHANGES_DIRECTORY.test(words(command));
export const movesHead = (command) => MOVES_HEAD.test(words(command));
// `--head` proposes a branch whose review the gate cannot see, even when it names this one.
export const namesHead = (command) => NAMES_HEAD.test(firstPullRequest(command) ?? '');

export const isDocumentation = (file) =>
  /\.md$/i.test(file) || /^(?:guide|docs)\//.test(file);

const reviewFile = (dir, head, ext) => join(dir, `review-${head}.${ext}`);

// The state directory is excluded so the review's own files never make the tree look dirty.
const dirty = (root, git) =>
  Boolean(git(root, ['status', '--porcelain', '--', '.', `:(exclude)${STATE_DIR}`]).trim());

function requireClean(root, git) {
  if (dirty(root, git)) {
    throw new Error('Commit or remove every change first; a review covers a commit, not a working tree');
  }
}

function headOf(root, git) {
  const head = git(root, ['rev-parse', '--verify', 'HEAD']).trim();
  if (!COMMIT.test(head)) throw new Error('HEAD did not resolve to a commit');
  return head;
}

function resolveBase(root, base, git) {
  const refs = base === null ? ['origin/main', 'main'] : [base];
  if (base !== null && (typeof base !== 'string' || !base || base.length > 256 ||
      /[\u0000-\u001f]/.test(base))) {
    throw new Error('Review base must identify an exact Git commit or ref');
  }
  for (const ref of refs) {
    let commit;
    try {
      commit = git(root, ['merge-base', '--end-of-options', ref, 'HEAD']).trim();
    } catch (error) {
      if (base !== null) throw error;
      continue;
    }
    if (COMMIT.test(commit)) return commit;
  }
  throw new Error('No review base: neither origin/main nor main shares history with HEAD');
}

function validReview(value, head) {
  if (value === null) return null;
  if (value.kind !== 'ideaforge-pr-review' || value.schema !== 1 || value.head !== head ||
      !REVIEW_RESULTS.includes(value.result)) {
    throw new Error(`Invalid or newer review record for ${head}`);
  }
  return value;
}

export async function readReview(root, head) {
  return validReview(await readJson(reviewFile(await stateDirectory(root), head, 'json')), head);
}

export async function prepareReview(root = ROOT, { base = null, git = runGit } = {}) {
  requireClean(root, git);
  const head = headOf(root, git);
  const baseCommit = resolveBase(root, base, git);
  const files = git(root, ['diff', '--name-only', '-z', baseCommit, head, '--'])
    .split('\0').filter(Boolean);
  if (!files.length) throw new Error(`HEAD has no changes against ${baseCommit}; there is nothing to review`);
  const dir = await stateDirectory(root, true);
  const diff = reviewFile(dir, head, 'diff');
  const existing = await statOrMissing(diff);
  if (existing && (!existing.isFile() || existing.isSymbolicLink())) {
    throw new Error(`Refusing to replace non-regular review data: ${diff}`);
  }
  // Git writes the file itself: no stdout buffer cap, and no PowerShell 5.1 re-encoding.
  const temp = join(dir, `.write-${randomUUID()}.diff`);
  try {
    git(root, ['diff', '--no-color', '--no-ext-diff', '--no-textconv', `--output=${temp}`,
      baseCommit, head, '--'], { timeout: DIFF_MS });
    await rename(temp, diff);
  } finally {
    if (await statOrMissing(temp)) await unlink(temp);
  }
  const review = {
    kind: 'ideaforge-pr-review', schema: 1, head, base: baseCommit,
    fileCount: files.length, docsChanged: files.some(isDocumentation), result: 'pending',
  };
  await writeJson(root, reviewFile(dir, head, 'json'), review);
  return { ...review, files, diff };
}

// `head` is the commit the round reviewed, as prepare printed it. Records are shared by
// every session in the worktree, so recording whatever HEAD is current could pass a commit
// another session prepared but nobody reviewed.
export async function recordReview(root = ROOT, {
  head: reviewed, result, reason = null, git = runGit,
} = {}) {
  if (typeof reviewed !== 'string' || !COMMIT.test(reviewed)) {
    throw new Error('Record names the full commit the round reviewed, as review prepare printed it');
  }
  if (!PASSING.has(result)) throw new Error('Record a review result of clean or waived');
  if (result === 'waived') {
    if (typeof reason !== 'string' || !reason.trim() || reason.length > MAX_REASON ||
        /[\u0000-\u001f\u007f]/.test(reason)) {
      throw new Error(`A waiver needs the user's reason, on one line of at most ${MAX_REASON} characters`);
    }
  } else if (reason !== null) throw new Error('Only a waived review takes a reason');
  requireClean(root, git);
  const head = headOf(root, git);
  if (head !== reviewed) {
    throw new Error(`HEAD is ${head}, not the reviewed ${reviewed}; review the current HEAD instead`);
  }
  const review = await readReview(root, head);
  if (!review) throw new Error(`No review was prepared for HEAD ${head}; run review prepare first`);
  const next = { ...review, result };
  if (result === 'waived') next.reason = reason.trim();
  else delete next.reason;
  await writeJson(root, reviewFile(await stateDirectory(root), head, 'json'), next);
  return next;
}

const refusal = (why) =>
  `IdeaForge opens a pull request only after the adversarial pre-PR review passes: ${why}. ` +
  `Run the ${REVIEW_SKILL} skill, which records its result for HEAD, then retry. Only when ` +
  'the user explicitly asked to skip review, record `node tools/assistant.mjs review record ' +
  '--head <sha> --result waived --reason "<their reason>"` instead.';

// Returns a deny reason, or null for "no opinion". Never an approval: the client's normal
// permission flow still decides every command this lets through.
export async function prGateDecision({ command, cwd = null, root = ROOT, git = runGit } = {}) {
  if (!opensPullRequest(command)) return null;
  if (changesDirectory(command)) {
    return refusal('the same command changes directory, so the gate cannot tell which checkout ' +
      'gh runs in; run gh in its own command from the reviewed checkout');
  }
  if (movesHead(command)) {
    return refusal('the same command also commits, rebases, merges or switches branch, so the ' +
      'HEAD gh proposes is not the one checked; run gh in its own command once HEAD is reviewed');
  }
  if (namesHead(command)) {
    return refusal('the command passes --head, but the gate can only see the review of this ' +
      'checkout\'s HEAD; drop --head and run gh from the reviewed branch');
  }
  try {
    const repo = typeof cwd === 'string' && isAbsolute(cwd)
      ? git(cwd, ['rev-parse', '--show-toplevel']).trim()
      : root;
    // The review CLI records only in a checkout that carries it, so another repository, or
    // a branch from before the gate existed, has nothing this gate could ever read.
    if (!await statOrMissing(join(repo, 'tools', 'assistant-review.mjs'))) return null;
    if (dirty(repo, git)) {
      return refusal('the working tree has uncommitted changes, so HEAD is not what was reviewed');
    }
    const head = headOf(repo, git);
    const review = await readReview(repo, head);
    if (!review) return refusal(`no review is recorded for HEAD ${head.slice(0, 12)}`);
    if (!PASSING.has(review.result)) {
      return refusal(`the review of HEAD ${head.slice(0, 12)} is still ${review.result}`);
    }
    return null;
  } catch (error) {
    // A gate that cannot read its record fails closed for the one command it guards.
    return refusal(`the review gate could not check this repository (${error.message})`);
  }
}
