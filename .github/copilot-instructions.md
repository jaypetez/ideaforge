# Copilot instructions for IdeaForge

Before editing, read the [shared workflow](../AGENTS.md) and
[implementation invariants](../CLAUDE.md), unless they are already loaded into your
context. They are the sources of truth for both Claude Code and GitHub Copilot;
this file is an entry point, not a second copy of their rules.

- There are no dependencies, no install step, and no build step. Use the existing
  Node scripts rather than adding tooling.
- Keep `src/core/` and `src/runtime/` platform-free. Inject providers and time;
  do not weaken the purity lint to accommodate a platform API.
- Run focused tests while editing and the full suite before pushing. Browser checks
  must have `BROWSER_CHECK_REQUIRED=1` set; use the shell-specific examples in
  `AGENTS.md` so a missing browser cannot silently pass.
- Preserve unrelated working-tree changes. Deliver requested changes through a PR,
  respect the required `ci` check, and do not merge unless explicitly asked.

Use the shared [update-readme skill](../.claude/skills/update-readme/SKILL.md) for
README maintenance and the changes named in its description. Keep its single
definition in `.claude/skills`; both Copilot CLI and VS Code discover it there.
Use the current client's normal tools and permissions, not a second skill copy or
blanket tool pre-approvals.

See [coding assistant setup](../CONTRIBUTING.md#coding-assistants) for discovery
commands and troubleshooting. Copilot support here is for developing the repository,
not a new inference provider inside the app.
