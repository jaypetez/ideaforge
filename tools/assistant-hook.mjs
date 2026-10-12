import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, createGuidance } from './assistant-process.mjs';
import { readHookInput } from './assistant.mjs';

export async function claudeHook(event, input, { root = ROOT, env = process.env } = {}) {
  // Copilot also reads .claude/settings.json, but its timestamped payload uses its own adapter.
  if (Object.hasOwn(input, 'timestamp')) return '';
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

export async function copilotHook(event, input, { root = ROOT, env = process.env } = {}) {
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
    const input = await readHookInput(process.stdin);
    const text = process.argv[2] === 'copilot'
      ? await copilotHook(process.argv[3], input)
      : await claudeHook(process.argv[2], input);
    if (text) process.stdout.write(`${text}\n`);
  } catch (error) {
    // Non-blocking, but visible: a failed persona hint must not become a permission gate.
    process.stderr.write(`IdeaForge assist hook unavailable: ${error.message}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
