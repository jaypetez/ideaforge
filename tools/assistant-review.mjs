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

// Quoted text and heredoc bodies are data, so a commit message or PR body that merely
// mentions the command is not refused as if it ran it. A quoted path to gh is the cost.
export function opensPullRequest(command) {
  if (typeof command !== 'string') return false;
  const code = command
    .replace(/<<-?[ \t]*(['"]?)([A-Za-z_]\w*)\1[^\n]*\n[\s\S]*?\n[ \t]*\2[ \t]*(?=\r?\n|$)/g, ' ')
    .replace(/@'[\s\S]*?'@|@"[\s\S]*?"@/g, ' ')
    .replace(/'[^']*'|"(?:[^"\\]|\\.)*"/g, ' ');
  return /(?:^|[^\w.-])gh(?:\.exe)?\s+pr\s+(?:create|new)\b/i.test(code);
}

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

export async function recordReview(root = ROOT, { result, reason = null, git = runGit } = {}) {
  if (!PASSING.has(result)) throw new Error('Record a review result of clean or waived');
  if (result === 'waived') {
    if (typeof reason !== 'string' || !reason.trim() || reason.length > MAX_REASON ||
        /[\u0000-\u001f\u007f]/.test(reason)) {
      throw new Error(`A waiver needs the user's reason, on one line of at most ${MAX_REASON} characters`);
    }
  } else if (reason !== null) throw new Error('Only a waived review takes a reason');
  requireClean(root, git);
  const head = headOf(root, git);
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
  '--result waived --reason "<their reason>"` instead.';

// Returns a deny reason, or null for "no opinion". Never an approval: the client's normal
// permission flow still decides every command this lets through.
export async function prGateDecision({ command, cwd = null, root = ROOT, git = runGit } = {}) {
  if (!opensPullRequest(command)) return null;
  try {
    const repo = typeof cwd === 'string' && isAbsolute(cwd)
      ? git(cwd, ['rev-parse', '--show-toplevel']).trim()
      : root;
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
