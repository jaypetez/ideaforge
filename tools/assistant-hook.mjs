import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, createGuidance } from './assistant-process.mjs';
import { readHookInput } from './assistant.mjs';
import { prGateDecision } from './assistant-review.mjs';

// Copilot documents toolArgs as unknown; its shell tools send a JSON string.
function commandOf(args) {
  let value = args;
  if (typeof value === 'string') {
    try { value = JSON.parse(value); } catch { return null; }
  }
  return value && typeof value.command === 'string' ? value.command : null;
}

// Quoting and escapes differ by shell; an unrecognised tool is read both ways.
function shellOf(tool) {
  if (/^powershell$/i.test(tool ?? '')) return 'powershell';
  if (/^(?:bash|shell)$/i.test(tool ?? '')) return 'posix';
  return null;
}

export async function claudeHook(event, input, { root = ROOT, env = process.env, git } = {}) {
  // Copilot also reads .claude/settings.json, but its timestamped payload uses its own adapter.
  if (Object.hasOwn(input, 'timestamp')) return '';
  if (event === 'PreToolUse') {
    const reason = await prGateDecision({
      command: input.tool_input?.command, shell: shellOf(input.tool_name), cwd: input.cwd, root, git,
    });
    return reason ? JSON.stringify({ hookSpecificOutput: {
      hookEventName: event, permissionDecision: 'deny', permissionDecisionReason: reason,
    } }) : '';
  }
  const guidance = createGuidance({ root, client: 'claude', env, restoreFirstPrompt: false });
  let context;
  if (event === 'SessionStart') context = await guidance.start(input);
  else if (event === 'UserPromptSubmit') context = await guidance.prompt(input);
  else if (event === 'SubagentStart') context = await guidance.child(input);
  else throw new Error('Unsupported IdeaForge Claude hook event');
  if (!context) return '';
  return event === 'SubagentStart'
    ? JSON.stringify({ hookSpecificOutput: { hookEventName: event, additionalContext: context } })
    : context;
}

export async function copilotHook(event, input, { root = ROOT, env = process.env, git } = {}) {
  if (event === 'preToolUse') {
    const reason = await prGateDecision({
      command: commandOf(input.toolArgs), shell: shellOf(input.toolName), cwd: input.cwd, root, git,
    });
    return JSON.stringify(reason ? { permissionDecision: 'deny', permissionDecisionReason: reason } : {});
  }
  const guidance = createGuidance({ root, client: 'copilot', env, restoreFirstPrompt: false });
  let context;
  if (event === 'sessionStart') context = await guidance.start(input);
  else if (event === 'userPromptSubmitted') {
    // Command-hook output is dropped here; the SDK adapter provides same-turn context.
    await guidance.prompt(input);
    return '{}';
  } else if (event === 'subagentStart') context = await guidance.child(input);
  else throw new Error('Unsupported IdeaForge Copilot hook event');
  return JSON.stringify(context ? { additionalContext: context } : {});
}

async function main() {
  try {
    // A tool call can carry a whole PR body, far past a lifecycle event's budget.
    const preTool = ['PreToolUse', 'preToolUse'].includes(process.argv[process.argv[2] === 'copilot' ? 3 : 2]);
    const input = await readHookInput(process.stdin, preTool ? { maxBytes: 1024 * 1024 } : undefined);
    const text = process.argv[2] === 'copilot'
      ? await copilotHook(process.argv[3], input)
      : await claudeHook(process.argv[2], input);
    if (text) process.stdout.write(`${text}\n`);
  } catch (error) {
    // Non-blocking, but visible: a failed persona hint must not become a permission gate.
    process.stderr.write(`IdeaForge assist hook unavailable: ${error.message}\n`);
    // Copilot denies a tool whose preToolUse command exits non-zero; report and abstain.
    if (process.argv[2] === 'copilot' && process.argv[3] === 'preToolUse') process.stdout.write('{}\n');
    else process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
