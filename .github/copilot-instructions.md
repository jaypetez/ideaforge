# Copilot instructions for IdeaForge

Before editing, read the [shared workflow](../AGENTS.md) and
[implementation invariants](../CLAUDE.md), unless they are already loaded into your
context. They are the sources of truth for both Claude Code and GitHub Copilot;
this file is an entry point, not a second copy of their rules.

- There are no dependencies, no install step, and no build step. Use the existing
  Node scripts rather than adding tooling.
- Keep `src/core/` and `src/runtime/` platform-free. Inject providers and time;
  do not weaken the purity lint to accommodate a platform API.
- Run focused tests while editing and `npm run test:all` before pushing. Use
  `npm run test:browser:required` for a focused browser run so missing Chrome cannot
  silently pass.
- Preserve unrelated working-tree changes. Deliver requested changes through a PR,
  respect the required `ci` check, and do not merge unless explicitly asked.

Use the shared [project skills](../.claude/skills/) for provider, voice, local-model,
README, and review workflows. Keep one definition of each skill in `.claude/skills`;
Claude Code, Copilot CLI, and VS Code all discover that location. Use the current
client's normal tools and permissions, not duplicate skill copies or blanket tool
pre-approvals.

See [coding assistant setup](../CONTRIBUTING.md#coding-assistants) for discovery
commands and troubleshooting. Copilot support here is for developing the repository,
not a new inference provider inside the app.
