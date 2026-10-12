import { createGuidance } from './assistant-process.mjs';

export function copilotHooks(guidance) {
  const output = async (method, input) => {
    const text = await guidance[method](input);
    return text ? { additionalContext: text } : undefined;
  };
  return {
    onUserPromptSubmitted: (input) => output('prompt', input),
    onPreToolUse: (input) => output('beforeTool', input),
  };
}

export async function joinAssistant(joinSession, { root, env = process.env, mapSource } = {}) {
  let session;
  const guidance = createGuidance({
    root, client: 'copilot', env, mapSource,
    notify: async (message, level) => {
      if (session) await session.log(message, { level });
    },
  });
  session = await joinSession({ hooks: copilotHooks(guidance) });
  session.on('session.compaction_complete', () => guidance.compacted(session.sessionId));
  return session;
}
