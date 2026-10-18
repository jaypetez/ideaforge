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

export const SHELLS = Object.freeze(['posix', 'powershell']);
// Flags may sit between `pr` and its subcommand: `gh pr -R owner/repo create`. Each flag
// token can match only one way, so a long run of flags cannot backtrack exponentially.
const PR_CREATE = /(?:^|[^\w.-])gh(?:\.exe)?\s+pr\s+(?:-{1,2}[A-Za-z][\w-]*(?:[ =][^\s-]\S*)?\s+)*(?:create|new)\b/gi;
const CHANGES_DIRECTORY = /(?:^|[\s;&|({])(?:cd|chdir|pushd|popd|sl|set-location|push-location|pop-location)(?=[\s;&|)}]|$)/i;
const NAMES_HEAD = /(?:^|\s)(?:--head\b|-H)/;

// A POSIX heredoc operator at `i`: its terminator, whether its body still expands
// (an unquoted delimiter), and the operator's length. `<<<` and `1 << n` are not heredocs.
function heredocAt(text, i) {
  if (!text.startsWith('<<', i) || text[i + 2] === '<' || text[i - 1] === '<') return null;
  const marker = /^<<-?[ \t]*(['"]?)([A-Za-z_]\w*)\1/.exec(text.slice(i));
  return marker && { end: marker[2], expand: !marker[1], length: marker[0].length };
}

// From the start of the line after a heredoc operator, past each pending body in turn.
function heredocBodies(text, start, docs) {
  let i = start;
  const bodies = [];
  for (const doc of docs) {
    const from = i;
    let to = text.length;
    while (i < text.length) {
      const stop = text.indexOf('\n', i);
      const line = text.slice(i, stop === -1 ? text.length : stop);
      const next = stop === -1 ? text.length : stop + 1;
      if (line.trim() === doc.end) { to = i; i = next; break; }
      i = next;
    }
    bodies.push({ body: text.slice(from, to), expand: doc.expand });
  }
  return { next: i, bodies };
}

// From `open`, just past an opening paren, the index of the paren that closes it, counting
// parens only. Used for arithmetic, where `<<` is a shift, not a heredoc.
function balanced(text, open) {
  let depth = 1;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '(') depth += 1;
    else if (text[i] === ')' && --depth === 0) return i;
  }
  return -1;
}

// From `open`, just past `$(`, the index of its closing paren, skipping quoted text and
// POSIX heredoc bodies, whose parens and quotes are data; -1 when unbalanced.
function closingParen(text, open, posix) {
  let depth = 1;
  const pending = [];
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    const doc = posix ? heredocAt(text, i) : null;
    if (c === '\n' && pending.length) {
      i = heredocBodies(text, i + 1, pending.splice(0)).next - 1;
    } else if (doc) {
      pending.push(doc);
      i += doc.length - 1;
    } else if (posix && text.startsWith('((', i)) {
      const end = balanced(text, i + 1);
      if (end === -1) return -1;
      i = end;
    } else if (c === "'" || c === '"') {
      const end = text.indexOf(c, i + 1);
      if (end === -1) return -1;
      i = end;
    } else if (c === '(') depth += 1;
    else if (c === ')' && --depth === 0) return i;
  }
  return -1;
}

// Within an expandable body, the command substitutions the shell would run.
function substitutions(body, shell) {
  const posix = shell === 'posix';
  const out = [];
  for (let i = 0; i < body.length; i++) {
    if (body[i] === (posix ? '\\' : '`')) i += 1;
    else if (posix && body.startsWith('$((', i)) {
      const end = balanced(body, i + 2);
      if (end === -1) break;
      i = end;
    } else if (body.startsWith('$(', i)) {
      const end = closingParen(body, i + 2, posix);
      if (end === -1) { out.push(body.slice(i + 2)); break; }
      out.push(body.slice(i + 2, end));
      i = end;
    } else if (posix && body[i] === '`') {
      const end = body.indexOf('`', i + 1);
      out.push(body.slice(i + 1, end === -1 ? body.length : end));
      if (end === -1) break;
      i = end;
    }
  }
  return out;
}

// The text one shell would execute: quoted data, comments and quoted heredoc bodies become
// spaces, while command substitutions inside double quotes and unquoted heredocs are kept,
// because they run. An unterminated quote is read as a literal, erring toward refusal.
function executedText(command, shell) {
  const posix = shell === 'posix';
  const escape = posix ? '\\' : '`';
  // `{` and `}` separate words in PowerShell; in POSIX `${#var}` is not a comment.
  const boundary = posix ? /[\s;&|()]/ : /[\s;&|(){}]/;
  const at = (i) => i === 0 || boundary.test(command[i - 1]);
  const run = (parts) => ` ${parts.map((part) => executedText(part, shell)).join(' ; ')} `;
  const pending = [];
  let out = '';
  for (let i = 0; i < command.length;) {
    const c = command[i];
    const doc = posix ? heredocAt(command, i) : null;
    if (c === '\n' && pending.length) {
      const { next, bodies } = heredocBodies(command, i + 1, pending.splice(0));
      out += `\n${run(bodies.filter((one) => one.expand).flatMap((one) => substitutions(one.body, shell)))}\n`;
      i = next;
    } else if (c === escape) {
      out += '  ';
      i += 2;
    } else if (c === '#' && at(i)) {
      while (i < command.length && command[i] !== '\n') i += 1;
    } else if (!posix && command.startsWith('<#', i)) {
      const end = command.indexOf('#>', i + 2);
      out += ' ';
      i = end === -1 ? command.length : end + 2;
    } else if (doc) {
      pending.push(doc);
      out += ' ';
      i += doc.length;
    } else if (posix && (command.startsWith('$((', i) || (command.startsWith('((', i) && at(i)))) {
      const end = balanced(command, i + (command[i] === '$' ? 3 : 2));
      if (end === -1) { out += c; i += 1; continue; }
      out += ' ';
      i = end + 1;
    } else if (!posix && /^@['"]\r?\n/.test(command.slice(i, i + 4))) {
      const quote = command[i + 1];
      const close = command.indexOf(`\n${quote}@`, i + 2);
      if (close === -1) { out += c; i += 1; continue; }
      if (quote === '"') out += run(substitutions(command.slice(i + 2, close), shell));
      else out += ' ';
      i = close + 3;
    } else if (c === "'") {
      const end = command.indexOf("'", i + 1);
      if (end === -1) { out += c; i += 1; continue; }
      out += ' ';
      i = end + 1;
    } else if (c === '"') {
      let end = -1;
      for (let j = i + 1; j < command.length; j++) {
        if (command[j] === escape) j += 1;
        else if (command.startsWith('$(', j)) {
          j = closingParen(command, j + 2, posix);
          if (j === -1) break;
        } else if (command[j] === '"') { end = j; break; }
      }
      if (end === -1) { out += c; i += 1; continue; }
      out += run(substitutions(command.slice(i + 1, end), shell));
      i = end + 1;
    } else {
      out += c;
      i += 1;
    }
  }
  return out;
}

// The executed text under the tool's own shell, or under both when it is unknown, so an
// ambiguous command errs toward refusal rather than toward an unreviewed PR.
function executed(command, shell) {
  if (typeof command !== 'string') return [];
  return (SHELLS.includes(shell) ? [shell] : SHELLS).map((one) => executedText(command, one));
}

// Each executed PR invocation's arguments, up to the end of its command.
function pullRequests(command, shell) {
  return executed(command, shell).flatMap((text) => [...text.matchAll(PR_CREATE)]
    .map((match) => text.slice(match.index + match[0].length).split(/[;&|\n]/, 1)[0]));
}

// Quoted data, comments and quoted heredoc bodies are not commands, so a commit message
// or PR body that merely mentions gh is not refused. A quoted path to gh, or a command
// handed to another interpreter as a string (`bash -c "…"`), is not recognised.
export const opensPullRequest = (command, shell = null) => pullRequests(command, shell).length > 0;

// The hook knows the session's directory, not one the command moves to on its way to gh.
export const changesDirectory = (command, shell = null) =>
  executed(command, shell).some((text) => CHANGES_DIRECTORY.test(text));

// `--head` proposes a branch other than the HEAD whose review the gate can see.
export const namesOtherHead = (command, shell = null) =>
  pullRequests(command, shell).some((args) => NAMES_HEAD.test(args));

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
export async function prGateDecision({
  command, shell = null, cwd = null, root = ROOT, git = runGit,
} = {}) {
  if (!opensPullRequest(command, shell)) return null;
  if (changesDirectory(command, shell)) {
    return refusal('the same command changes directory, so the gate cannot tell which checkout ' +
      'gh runs in; change directory in its own command, then run gh from the reviewed checkout');
  }
  if (namesOtherHead(command, shell)) {
    return refusal('the command names its head branch with --head, but the gate can only see ' +
      'the review of this checkout\'s HEAD; check out that branch and run gh from it');
  }
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
