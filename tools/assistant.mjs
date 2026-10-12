import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import {
  ROOT, CLIENTS, buildMap, scanDebt, measureGain, readMode, writeMode, writeDefault, resetMode,
  trustedWorkingDirectory,
} from './assistant-process.mjs';

export async function readHookInput(stream, { maxBytes = 64 * 1024, timeoutMs = 1000 } = {}) {
  return new Promise((accept, reject) => {
    let text = '';
    let bytes = 0;
    const finish = (error) => {
      clearTimeout(timer);
      stream.removeListener('data', data);
      stream.removeListener('end', end);
      stream.removeListener('error', failed);
      stream.pause();
      if (error) reject(error);
      else {
        let value;
        try { value = JSON.parse(text); } catch (cause) {
          reject(new Error('Assistant hook requires complete JSON input', { cause }));
          return;
        }
        if (!value || typeof value !== 'object' || Array.isArray(value)) {
          reject(new Error('Assistant hook input must be an object'));
          return;
        }
        accept(value);
      }
    };
    const data = (chunk) => {
      bytes += Buffer.byteLength(chunk);
      if (bytes > maxBytes) { finish(new Error('Assistant hook input exceeds its byte budget')); return; }
      text += chunk;
    };
    const end = () => finish();
    const failed = (error) => finish(new Error('Could not read assistant hook input', { cause: error }));
    // Ref'd: an open PowerShell stdin pipe must not strand a hook at process shutdown.
    const timer = setTimeout(() => finish(new Error('Assistant hook stdin did not finish in time')), timeoutMs);
    stream.setEncoding('utf8');
    stream.on('data', data);
    stream.once('end', end);
    stream.once('error', failed);
    stream.resume();
  });
}

function parseArgs(args) {
  const [command, ...rest] = args;
  const positional = [];
  const options = {};
  for (let i = 0; i < rest.length; i++) {
    const part = rest[i];
    if (part.startsWith('--')) {
      if (!['--client', '--session', '--base'].includes(part) || i + 1 >= rest.length ||
          rest[i + 1].startsWith('--') || Object.hasOwn(options, part.slice(2))) {
        throw new Error('Use only --client CLIENT, --session ID or --base REF once each');
      }
      options[part.slice(2)] = rest[++i];
    } else positional.push(part);
  }
  return { command, positional, options };
}

export async function runAssistant(args, { root = ROOT, stdin = process.stdin, env = process.env } = {}) {
  const { command, positional, options } = parseArgs(args);
  const valid = ['map', 'debt', 'gain', 'status', 'mode', 'default', 'reset'];
  if (!valid.includes(command)) throw new Error(`Use assistant ${valid.join('|')}`);
  if (positional.length !== (['mode', 'default'].includes(command) ? 1 : 0)) {
    throw new Error('mode/default requires one level; other commands take no positional arguments');
  }
  if (options.base && command !== 'gain') throw new Error('--base is only valid for gain');
  if ((options.client || options.session) && !['status', 'mode', 'reset'].includes(command)) {
    throw new Error('--client/--session is only valid for mode, reset or status');
  }
  if (command === 'map') return (await buildMap(root)).text;
  if (command === 'debt') {
    const out = await scanDebt(root);
    const rows = out.markers.map((item) =>
      `${item.file}:${item.line} ${item.text}${item.upgrade ? '' : ' [no-trigger]'}`);
    return [...rows, ...out.unread.map((item) => `Not inspected: ${item.file}: ${item.reason}`),
      `${out.markers.length} markers, ${out.missingTriggers} with no trigger.`,
      `Unread: ${out.unread.length}; beyond file budget: ${out.omitted}.`].join('\n');
  }
  if (command === 'gain') return JSON.stringify(await measureGain(root, { base: options.base ?? null }), null, 2);
  if (command === 'default') {
    const out = await writeDefault(root, positional[0], env);
    return `Local default: ${out.requested}; effective startup default: ${out.effective}. Current sessions are unchanged.`;
  }
  if (!CLIENTS.includes(options.client)) throw new Error('Use --client claude or --client copilot');
  let sessionId = options.session;
  if (!sessionId && command === 'status') {
    const input = await readHookInput(stdin);
    const cwd = input.cwd ?? input.workingDirectory ?? input.workspace?.current_dir;
    await trustedWorkingDirectory(root, cwd);
    sessionId = input.sessionId ?? input.session_id ?? input.session?.id;
  }
  if (!sessionId) throw new Error('Mode/reset requires the real native --session ID; do not invent one');
  if (command === 'mode') await writeMode(root, options.client, sessionId, positional[0]);
  if (command === 'reset') await resetMode(root, options.client, sessionId);
  const out = await readMode(root, options.client, sessionId, env);
  return command === 'status' && out.config.hideStatus ? '' : `[IDEAFORGE:${out.mode.toUpperCase()}]`;
}

async function main() {
  try {
    const text = await runAssistant(process.argv.slice(2));
    if (text) process.stdout.write(`${text}\n`);
  } catch (error) {
    process.stderr.write(`IdeaForge assist unavailable: ${error.message}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
