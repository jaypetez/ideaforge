// Ponytail-inspired developer assistance; see THIRD_PARTY_NOTICES.md. Never shipped by the app.
import { createHash, randomUUID } from 'node:crypto';
import { lstat, mkdir, open, readFile, realpath, rename, unlink } from 'node:fs/promises';
import { dirname, extname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('..', import.meta.url));
export const MODES = Object.freeze(['lite', 'full', 'ultra', 'off']);
export const CLIENTS = Object.freeze(['claude', 'copilot']);
export const STATE_DIR = '.ideaforge-assist';
const MAX_JSON = 16 * 1024;
const MAX_SOURCE = 256 * 1024;
const MAX_FILES = 4000;
const MAX_CONTEXT = 9000;
const hash = (text) => createHash('sha256').update(text).digest('hex');
const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const displayPath = (file) => file.split('/').join(sep);

export function normalizeMode(value) {
  if (typeof value !== 'string' || !MODES.includes(value.trim().toLowerCase())) {
    throw new Error('Mode must be lite, full, ultra or off');
  }
  return value.trim().toLowerCase();
}

export function parseControl(prompt) {
  if (typeof prompt !== 'string') throw new Error('Assistant control prompt must be text');
  const text = prompt.trim();
  if (/^(?:normal mode|stop ideaforge assist)[.!?]*$/i.test(text)) {
    return { action: 'mode', mode: 'off' };
  }
  const match = text.match(/^\/ideaforge-assist(?:\s+(.*))?$/i);
  if (!match) return null;
  const parts = (match[1] || '').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return { action: 'status' };
  if (parts.length === 1 && parts[0].toLowerCase() === 'reset') return { action: 'reset' };
  if (parts.length === 2 && parts[0].toLowerCase() === 'default') {
    return { action: 'default', mode: normalizeMode(parts[1]) };
  }
  if (parts.length === 1) return { action: 'mode', mode: normalizeMode(parts[0]) };
  throw new Error('Use /ideaforge-assist [lite|full|ultra|off|default LEVEL|reset]');
}

export async function statOrMissing(file) {
  try {
    return await lstat(file);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

export async function stateDirectory(root, create = false) {
  const path = join(await realpath(root), STATE_DIR);
  const stat = await statOrMissing(path);
  if (stat && (!stat.isDirectory() || stat.isSymbolicLink())) {
    throw new Error(`Assistant state directory must be a real directory: ${path}`);
  }
  if (!stat && create) {
    await mkdir(path, { mode: 0o700, recursive: true });
    const created = await lstat(path);
    if (!created.isDirectory() || created.isSymbolicLink()) {
      throw new Error(`Assistant state directory changed during creation: ${path}`);
    }
  }
  return path;
}

export async function readJson(file) {
  const stat = await statOrMissing(file);
  if (!stat) return null;
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_JSON) {
    throw new Error(`Refusing non-regular or oversized assistant data: ${file}`);
  }
  const text = (await readFile(file, 'utf8')).replace(/^\uFEFF/, '');
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    throw new Error(`Invalid assistant JSON: ${file}`, { cause: error });
  }
  if (!record(value)) throw new Error(`Assistant JSON must be an object: ${file}`);
  return value;
}

export async function readConfig(root, env = process.env) {
  const dir = await stateDirectory(root);
  const value = await readJson(join(dir, 'config.json')) || {};
  const defaultMode = normalizeMode(
    env.IDEAFORGE_ASSIST_DEFAULT_MODE ?? value.defaultMode ?? 'full',
  );
  for (const key of ['mapEnabled', 'quietStartup', 'hideStatus']) {
    if (Object.hasOwn(value, key) && typeof value[key] !== 'boolean') {
      throw new Error(`Assistant ${key} must be a boolean`);
    }
  }
  const mapChars = value.mapChars ?? 2000;
  if (!Number.isInteger(mapChars) || mapChars < 256 || mapChars > 4000) {
    throw new Error('Assistant mapChars must be an integer from 256 to 4000');
  }
  return {
    ...value, defaultMode, mapChars,
    mapEnabled: value.mapEnabled ?? true,
    quietStartup: value.quietStartup ?? false,
    hideStatus: value.hideStatus ?? false,
  };
}

export async function writeJson(root, file, value) {
  const dir = await stateDirectory(root, true);
  if (dirname(file) !== dir) throw new Error('Assistant write escaped its state directory');
  const existing = await statOrMissing(file);
  if (existing && (!existing.isFile() || existing.isSymbolicLink())) {
    throw new Error(`Refusing to replace non-regular assistant data: ${file}`);
  }
  const temp = join(dir, `.write-${randomUUID()}.tmp`);
  const text = `${JSON.stringify(value, null, 2)}\n`;
  if (Buffer.byteLength(text) > MAX_JSON) throw new Error('Assistant data exceeds its byte budget');
  let saved = false;
  try {
    const handle = await open(temp, 'wx', 0o600);
    try {
      await handle.writeFile(text, 'utf8');
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(temp, file);
    saved = true;
  } finally {
    if (!saved && await statOrMissing(temp)) await unlink(temp);
  }
}

export async function writeDefault(root, mode, env = process.env) {
  const config = await readConfig(root, {});
  const requested = normalizeMode(mode);
  await writeJson(root, join(await stateDirectory(root), 'config.json'),
    { ...config, defaultMode: requested });
  const effective = await readConfig(root, env);
  return { requested, effective: effective.defaultMode };
}

export async function sessionIdentity(root, client, sessionId) {
  if (!CLIENTS.includes(client)) throw new Error('Assistant client must be claude or copilot');
  if (typeof sessionId !== 'string' || !sessionId.trim() || sessionId.length > 256 ||
      /[\u0000-\u001f\u007f]/.test(sessionId)) {
    throw new Error('Assistant operation requires a valid native session ID');
  }
  const actualRoot = await realpath(root);
  const rootKey = process.platform === 'win32' ? actualRoot.toLowerCase() : actualRoot;
  const identity = hash(`${rootKey}\0${client}\0${sessionId}`);
  return { root: actualRoot, client, identity,
    file: join(await stateDirectory(actualRoot), `session-${identity}.json`) };
}

function validateSession(value, identity) {
  if (value.kind !== 'ideaforge-assist-session' || value.schema !== 1 ||
      value.identity !== identity.identity || value.client !== identity.client ||
      Object.keys(value).sort().join(',') !== 'client,identity,kind,mode,schema') {
    throw new Error(`Invalid or newer assistant session data: ${identity.file}`);
  }
  return normalizeMode(value.mode);
}

export async function readMode(root, client, sessionId, env = process.env) {
  const identity = await sessionIdentity(root, client, sessionId);
  const value = await readJson(identity.file);
  const config = await readConfig(root, env);
  return { ...identity, config, exists: value !== null,
    mode: value ? validateSession(value, identity) : config.defaultMode };
}

export async function writeMode(root, client, sessionId, mode) {
  const identity = await sessionIdentity(root, client, sessionId);
  const existing = await readJson(identity.file);
  if (existing) validateSession(existing, identity);
  const selected = normalizeMode(mode);
  await writeJson(root, identity.file, {
    kind: 'ideaforge-assist-session', schema: 1,
    client, identity: identity.identity, mode: selected,
  });
  return selected;
}

export async function resetMode(root, client, sessionId) {
  const identity = await sessionIdentity(root, client, sessionId);
  const value = await readJson(identity.file);
  if (!value) return false;
  validateSession(value, identity);
  await unlink(identity.file);
  return true;
}

export function withinRoot(root, path) {
  const rel = relative(resolve(root), resolve(path));
  return rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel);
}

export async function trustedWorkingDirectory(root, cwd) {
  if (typeof cwd !== 'string' || !isAbsolute(cwd)) {
    throw new Error('Assistant event is outside its trusted repository/worktree');
  }
  const actualRoot = await realpath(root);
  const actualCwd = await realpath(cwd);
  if (!withinRoot(actualRoot, actualCwd)) throw new Error('Assistant event resolves outside its root');
  let path = actualRoot;
  for (const part of relative(actualRoot, actualCwd).split(sep).filter(Boolean)) {
    path = join(path, part);
    if (await statOrMissing(join(path, '.git'))) {
      throw new Error('Assistant event belongs to a nested repository or different worktree');
    }
  }
  return actualCwd;
}

export function runGit(root, args, { timeout = 3000 } = {}) {
  const out = spawnSync('git', ['-C', root, ...args], {
    encoding: 'utf8', windowsHide: true, timeout, maxBuffer: 2 * 1024 * 1024,
  });
  if (out.error) throw new Error(`Assistant Git command failed: ${args[0]}`, { cause: out.error });
  if (out.status !== 0) throw new Error(`Assistant Git ${args[0]} failed: ${out.stderr.trim()}`);
  return out.stdout;
}

const SOURCE_EXTENSIONS = new Set([
  '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.kt', '.kts', '.java', '.css',
  '.html', '.py', '.sh', '.ps1', '.go', '.rs', '.json', '.yml', '.yaml', '.md',
]);

export function sourcePathAllowed(file) {
  const path = file.replace(/\\/g, '/');
  return !/(^|\/)(?:\.git|\.worktrees|\.ideaforge-assist|node_modules|dist|build|\.gradle|\.kotlin|captures)(\/|$)/.test(path) &&
    !/(^|\/)(?:\.env(?:\..*)?|settings\.local\.json|CLAUDE\.local\.md|local\.properties|keystore\.properties)$/.test(path) &&
    !/\.(?:jks|keystore|pem|key|p12|pfx|log)$/i.test(path) &&
    SOURCE_EXTENSIONS.has(extname(path).toLowerCase());
}

export function trackedSources(root, git = runGit) {
  const files = git(root, ['ls-files', '-z', '--cached']).split('\0').filter(Boolean);
  const out = [];
  for (const file of files) {
    if (!withinRoot(root, join(root, file))) throw new Error('Git inventory escaped the repository');
    if (sourcePathAllowed(file)) out.push(file.replace(/\\/g, '/'));
  }
  return [...new Set(out)].sort();
}

async function sourceText(root, file) {
  const path = join(root, ...file.split('/'));
  if (!withinRoot(root, path)) throw new Error('Assistant source escaped the repository');
  const stat = await statOrMissing(path);
  if (!stat) return { text: null, reason: 'deleted from working tree' };
  if (!stat.isFile() || stat.isSymbolicLink()) return { text: null, reason: 'non-regular' };
  const actual = await realpath(path);
  if (!withinRoot(await realpath(root), actual)) {
    throw new Error(`Assistant source resolves outside the repository: ${file}`);
  }
  if (stat.size > MAX_SOURCE) return { text: null, reason: 'oversized' };
  return { text: await readFile(path, 'utf8'), reason: null };
}

export function exportedNames(text) {
  const names = [];
  const pattern = /^\s*(?:export\s+(?:default\s+)?(?:async\s+)?(?:function|class|const|let)|(?:suspend\s+)?fun|(?:data\s+)?class|object)\s+([A-Za-z_$][\w$]*)/gm;
  for (const match of text.matchAll(pattern)) {
    if (!names.includes(match[1])) names.push(match[1]);
    if (names.length === 12) break;
  }
  return names;
}

export async function buildMap(root, { budget = 2000, files = trackedSources(root) } = {}) {
  if (!Number.isInteger(budget) || budget < 256 || budget > 4000) {
    throw new Error('Map budget must be from 256 to 4000 characters');
  }
  const groups = new Map();
  let unread = 0;
  for (const file of files.slice(0, MAX_FILES)) {
    if (!sourcePathAllowed(file)) continue;
    const { text } = await sourceText(root, file);
    if (text === null) { unread++; continue; }
    const dir = file.includes('/') ? file.slice(0, file.lastIndexOf('/')) : '.';
    if (!groups.has(dir)) groups.set(dir, []);
    const names = ['.md', '.html', '.json', '.yml', '.yaml'].includes(extname(file))
      ? [] : exportedNames(text);
    groups.get(dir).push(`${file.slice(file.lastIndexOf('/') + 1)}${names.length ? ` (${names.join(', ')})` : ''}`);
  }
  const rank = (dir) => /^src\/(?:core|runtime|providers)(?:\/|$)/.test(dir) ? 0
    : /^(?:src|tools|android\/(?:core|providers))(?:\/|$)/.test(dir) ? 1
      : /(?:^|\/)(?:util|utils|helper|helpers|lib|common)(?:\/|$)/.test(dir) ? 2 : 3;
  const entries = [...groups].sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b));
  const header = 'Tracked source map; exported names are heuristic, not a call graph.\n';
  let body = '';
  let shown = 0;
  let omittedFiles = 0;
  for (const [dir, names] of entries) {
    let line = `${displayPath(dir)}: `;
    let count = 0;
    for (const name of names) {
      const next = `${count ? '; ' : ''}${name}`;
      if (header.length + body.length + line.length + next.length + 140 > budget) break;
      line += next;
      count++;
    }
    if (!count) { omittedFiles += names.length; continue; }
    omittedFiles += names.length - count;
    body += `${line}${count < names.length ? `; (${names.length - count} more files)` : ''}\n`;
    shown++;
  }
  const omitted = entries.length - shown;
  const footer = `Omitted: ${omitted} directories, ${omittedFiles} files; ${unread} unread; ${Math.max(0, files.length - MAX_FILES)} beyond file budget.`;
  return { text: header + body + footer, shown, omitted, omittedFiles, unread,
    fileLimit: Math.max(0, files.length - MAX_FILES) };
}

// A small lexer, rather than grep, keeps markers in string literals out of the debt report.
export function commentsIn(text, { hashComments = false } = {}) {
  const comments = [];
  let line = 1;
  let quote = '';
  let regexpAllowed = true;
  for (let i = 0; i < text.length;) {
    if (quote) {
      if (text.startsWith(quote, i)) {
        i += quote.length; quote = ''; regexpAllowed = false; continue;
      }
      if (text[i] === '\\') { if (text[i + 1] === '\n') line++; i += 2; continue; }
      if (text[i] === '\n') line++;
      i++;
      continue;
    }
    if (text[i] === '"' || text[i] === "'" || text[i] === '`') {
      quote = text.startsWith(text[i].repeat(3), i) ? text[i].repeat(3) : text[i];
      i += quote.length;
      continue;
    }
    const block = !hashComments && text.startsWith('/*', i);
    const single = (!hashComments && text.startsWith('//', i)) || (hashComments && text[i] === '#');
    if (block || single) {
      const start = line;
      const offset = block || text.startsWith('//', i) ? 2 : 1;
      const end = block ? text.indexOf('*/', i + offset) : text.indexOf('\n', i + offset);
      const stop = end === -1 ? text.length : end;
      const body = text.slice(i + offset, stop);
      for (const [index, part] of body.split(/\r?\n/).entries()) {
        comments.push({ line: start + index, text: part.replace(/^\s*\*\s?/, '').trim() });
      }
      line += body.split('\n').length - 1;
      i = stop + (block && end !== -1 ? 2 : 0);
      continue;
    }
    if (!hashComments && text[i] === '/' && regexpAllowed) {
      let end = i + 1;
      let inClass = false;
      let closed = false;
      for (; end < text.length && text[end] !== '\n'; end++) {
        if (text[end] === '\\') { end++; continue; }
        if (text[end] === '[') inClass = true;
        else if (text[end] === ']') inClass = false;
        else if (text[end] === '/' && !inClass) { closed = true; end++; break; }
      }
      if (closed) {
        while (/[A-Za-z]/.test(text[end] || '')) end++;
        i = end;
        regexpAllowed = false;
        continue;
      }
    }
    if (/[A-Za-z_$]/.test(text[i])) {
      const word = /^[A-Za-z_$][\w$]*/.exec(text.slice(i))[0];
      regexpAllowed = /^(?:return|throw|case|typeof|void|delete|yield|await|else|do|in|of)$/.test(word);
      i += word.length;
      continue;
    }
    if (text.startsWith('++', i) || text.startsWith('--', i)) {
      i += 2;
      continue;
    }
    if (!/\s/.test(text[i])) regexpAllowed = '=([{,;:?!&|+-*%^~<>/'.includes(text[i]);
    if (text[i] === '\n') line++;
    i++;
  }
  return comments;
}

export function debtIn(text, file) {
  const hashComments = ['.py', '.sh', '.ps1', '.yml', '.yaml'].includes(extname(file).toLowerCase());
  const out = [];
  for (const comment of commentsIn(text, { hashComments })) {
    const match = comment.text.match(/^(shortcut|ponytail):\s*(.+)$/i);
    if (!match) continue;
    const decision = match[2];
    const limit = decision.match(/(?:^|;\s*)(?:limit|ceiling):\s*([^;]+)/i)?.[1]?.trim() || null;
    const upgrade = decision.match(/(?:^|;\s*)(?:upgrade|when):\s*([^;]+)/i)?.[1]?.trim() || null;
    out.push({ file, line: comment.line, marker: match[1].toLowerCase(), text: decision,
      limit, upgrade });
  }
  return out;
}

export async function scanDebt(root, { files = trackedSources(root) } = {}) {
  const markers = [];
  const unread = [];
  for (const file of files.slice(0, MAX_FILES)) {
    const extension = extname(file).toLowerCase();
    if (!sourcePathAllowed(file) || ['.md', '.json', '.html'].includes(extension)) continue;
    if (['.yaml', '.yml'].includes(extension)) {
      unread.push({ file, reason: 'YAML literal contexts are not supported by the debt lexer' });
      continue;
    }
    const source = await sourceText(root, file);
    if (source.text === null) { unread.push({ file, reason: source.reason }); continue; }
    markers.push(...debtIn(source.text, file));
  }
  return { markers, missingTriggers: markers.filter((entry) => !entry.upgrade).length,
    unread, omitted: Math.max(0, files.length - MAX_FILES) };
}

export function parseNumstat(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/).filter(Boolean)) {
    const match = line.match(/^(\d+|-)\t(\d+|-)\t(.+)$/);
    if (!match || (match[1] === '-') !== (match[2] === '-')) {
      throw new Error('Invalid Git numstat output; no gain measurement was produced');
    }
    rows.push({ file: match[3], binary: match[1] === '-',
      added: match[1] === '-' ? null : Number(match[1]),
      removed: match[2] === '-' ? null : Number(match[2]) });
  }
  return rows;
}

export async function measureGain(root, { base = null, git = runGit } = {}) {
  let commit = 'HEAD';
  if (base !== null) {
    if (typeof base !== 'string' || !base || base.length > 256 || /[\u0000-\u001f]/.test(base)) {
      throw new Error('Gain base must identify an exact Git commit or ref');
    }
    commit = git(root, ['rev-parse', '--verify', '--end-of-options', `${base}^{commit}`]).trim();
    if (!/^[a-f0-9]{40,64}$/.test(commit)) throw new Error('Gain base did not resolve to a commit');
  }
  const rows = parseNumstat(git(root, ['diff', '--no-ext-diff', '--no-textconv', '--numstat', commit, '--']));
  const debt = await scanDebt(root, { files: trackedSources(root, git) });
  return {
    scope: base === null ? 'Working tree and index compared with HEAD' : `Working tree and index compared with ${commit}`,
    files: rows.length, binaryFiles: rows.filter((row) => row.binary).length,
    added: rows.reduce((n, row) => n + (row.added ?? 0), 0),
    removed: rows.reduce((n, row) => n + (row.removed ?? 0), 0),
    debt, note: 'These are measured diff/debt facts, not saved code, tokens, time or cost.',
  };
}

export async function leanRules(root, mode) {
  const text = await readFile(join(root, 'AGENTS.md'), 'utf8');
  const heading = /^## Lean coding\r?\n/m.exec(text);
  const tail = heading ? text.slice(heading.index + heading[0].length) : '';
  const next = tail.search(/^## /m);
  const section = next === -1 ? tail : tail.slice(0, next);
  if (!section || section.length > 6500) throw new Error('Canonical AGENTS.md lean section is missing or oversized');
  if (!section.includes(`| \`${mode}\` |`)) throw new Error('Canonical lean mode table is missing');
  return section.split(/\r?\n/).filter((line) => {
    const row = line.match(/^\| `(lite|full|ultra|off)` \|/);
    return !row || row[1] === mode;
  }).join('\n').trim();
}

export function createGuidance({
  root = ROOT, client, env = process.env, notify = async () => {},
  mapSource = buildMap, restoreFirstPrompt = true,
} = {}) {
  if (!CLIENTS.includes(client)) throw new Error('Guidance requires a supported client');
  const seen = new Set();
  const pending = new Set();
  const running = new Map();
  async function inputContext(input) {
    const cwd = input.workingDirectory ?? input.cwd;
    const sessionId = input.sessionId ?? input.session_id;
    await trustedWorkingDirectory(root, cwd);
    const state = await readMode(root, client, sessionId, env);
    return { sessionId, state };
  }
  async function context(sessionId, state, { rules = false, map = false } = {}) {
    const heading = `IdeaForge assist: ${state.mode.toUpperCase()}; client ${client}; session ${sessionId}.`;
    const baseline = 'Passive guidance only. AGENTS.md and CLAUDE.md, read-only agent restrictions, tool permissions and delivery gates always remain in force.';
    if (state.mode === 'off') return `${heading}\n${baseline}\nExtra lean guidance is off.`;
    const body = rules ? await leanRules(root, state.mode) : 'Use the canonical AGENTS.md#lean-coding section for this mode.';
    const orientation = map && state.config.mapEnabled ? `\n${(await mapSource(root, { budget: state.config.mapChars })).text}` : '';
    const output = `${heading}\n${baseline}\n${body}${orientation}`;
    if (output.length > MAX_CONTEXT) throw new Error('Assistant context exceeds its native output budget');
    return output;
  }
  async function remember(sessionId) {
    if (seen.size >= 200 && !seen.has(sessionId)) throw new Error('Too many assistant sessions in one adapter');
    seen.add(sessionId);
    pending.delete(sessionId);
  }
  const handlers = {
    async start(input) {
      const { sessionId, state } = await inputContext(input);
      if (!state.exists) await writeMode(root, client, sessionId, state.mode);
      await remember(sessionId);
      if (!state.config.quietStartup && state.mode !== 'off') {
        await notify(`IdeaForge assist started in ${state.mode}.`, 'info');
      }
      return state.mode === 'off' ? undefined : context(sessionId, state, { rules: true, map: true });
    },
    async prompt(input) {
      const { sessionId, state } = await inputContext(input);
      const command = parseControl(input.prompt);
      const initial = restoreFirstPrompt && !seen.has(sessionId);
      if (initial && !state.exists) await writeMode(root, client, sessionId, state.mode);
      let changed = false;
      let message = '';
      if (command?.action === 'default') {
        if (!state.exists) await writeMode(root, client, sessionId, state.mode);
        const out = await writeDefault(root, command.mode, env);
        message = `Local assistant default is ${out.requested}; effective startup default is ${out.effective}. Current session mode is unchanged.`;
      } else if (command?.action === 'reset') {
        await resetMode(root, client, sessionId);
        changed = true;
        message = 'This assistant session was reset to its effective default; other sessions and configuration are unchanged.';
      } else if (command?.action === 'mode') {
        await writeMode(root, client, sessionId, command.mode);
        changed = true;
      } else if (command?.action === 'status' && state.mode === 'off') {
        await writeMode(root, client, sessionId, state.config.defaultMode);
        changed = true;
      }
      const fresh = await readMode(root, client, sessionId, env);
      const restore = pending.has(sessionId);
      if (command) {
        await notify(message || `IdeaForge assist is ${fresh.mode}.`, 'info');
      } else if (initial && !fresh.config.quietStartup && fresh.mode !== 'off') {
        await notify(`IdeaForge assist started in ${fresh.mode}.`, 'info');
      }
      await remember(sessionId);
      if (!command && !initial && !restore) return undefined;
      return `${message ? `${message}\n` : ''}${await context(sessionId, fresh, {
        rules: changed || initial || restore, map: initial,
      })}`;
    },
    async child(input) {
      const { sessionId, state } = await inputContext(input);
      if (state.mode === 'off') return undefined;
      const agent = input.agentName ?? input.agent_type ?? '';
      if (/^ideaforge-(?:docs-)?review-(?:claude|copilot)$/.test(agent)) {
        return 'IdeaForge review remains strictly read-only and exact-diff-scoped. Do not change modes, run commands or implement findings; use its existing shared review skill.';
      }
      return context(sessionId, state, { rules: true });
    },
    compacted(sessionId) {
      if (seen.has(sessionId)) pending.add(sessionId);
    },
    async beforeTool(input) {
      const sessionId = input.sessionId ?? input.session_id;
      if (!pending.has(sessionId)) return undefined;
      const { state } = await inputContext(input);
      pending.delete(sessionId);
      return context(sessionId, state, { rules: true });
    },
  };
  // Startup and rapid controls for the same native session must settle in arrival order.
  for (const name of ['start', 'prompt', 'child', 'beforeTool']) {
    const handler = handlers[name];
    handlers[name] = (input) => {
      const id = input.sessionId ?? input.session_id;
      const enter = () => handler(input);
      const result = (running.get(id) || Promise.resolve()).then(enter, enter);
      running.set(id, result);
      const clear = () => { if (running.get(id) === result) running.delete(id); };
      result.then(clear, clear);
      return result;
    };
  }
  return handlers;
}
