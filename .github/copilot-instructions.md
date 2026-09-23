# Copilot instructions for IdeaForge

Before editing, read the [shared workflow](../AGENTS.md) and
[implementation invariants](../CLAUDE.md), unless they are already loaded into your
context. They are the sources of truth for both Claude Code and GitHub Copilot;
this file is an entry point, not a second copy of their rules.

## Commands

Node 22+; there are no dependencies, install step, or build step. Use the existing
Node scripts rather than adding tooling. `npm run serve` requires Python and a real
origin (`file://` will not work).

```sh
npm run serve                         # app at http://127.0.0.1:8765
npm run lint:purity                   # platform-free core and runtime
node --test test/voice.test.mjs       # one unit test file
node --test --test-name-pattern="a pause after enough speech ends the answer" test/voice.test.mjs
npm test                              # purity lint and all unit tests
npm run test:docs                     # docs, links, and assistant wiring
npm run test:graph                    # syntax and module imports
npm run test:browser:required         # assembled site in Chrome; fails if Chrome is missing
npm run test:all                      # unit, graph, and required browser checks
```

`npm run screenshots` regenerates README screenshots and the worked example after UI
or README changes; `npm run validate:local` exercises a real Ollama model (see the
shared `validate-local-model` skill).

## Architecture

This is a browser-only PWA: `src/ui/app.js` owns the active session and DOM;
`src/core/` owns pure session reducers, interview decisions, prompts, and export;
`src/runtime/` orchestrates turns and synthesis using an injected provider and clock.
The UI also connects `src/providers/` (model requests), `src/store/` (IndexedDB
sessions and encrypted credentials), and `src/voice/` (microphone, dictation, and
speech). The dependency direction is `ui → runtime → core`, not the reverse.
See the [architecture map](../docs/ARCHITECTURE.md) for extension seams.

A turn runs from `seedTurn` through `submitAnswer` and `runTurn` to
`runSynthesis` and `buildExport`; provider failures use a built-in question
bank instead of stranding the interview. `tools/assemble-site.mjs` copies the
committed static tree for browser checks and Pages; `sw.js` caches the app shell
for offline use but bypasses the separately published `guide/` and provider
traffic.

## Key conventions

- Keep `src/core/` and `src/runtime/` platform-free. Inject providers and time;
  do not weaken the purity lint to accommodate a platform API.
- Change sessions through reducers in `src/core/session.js`, not in place. Persist
  the session returned by a settled `runTurn` once; debounce unfinished answer
  drafts separately.
- Turn prompts must be deterministic from session state so interrupted calls
  can replay the same prompt. Parse untrusted model output in the core parsers
  and apply the coverage ratchet rather than trusting claimed progress.
- Add each new `src/` asset to `sw.js`'s `SHELL`; keep provider/transcription
  origins in the CSP `connect-src` in `index.html`. `test/wiring.test.mjs`
  checks both registries against the shipped sources and presets.
- Browser-only behavior (voice, storage, UI, service worker, CSP) needs the
  required-browser checks: `npm test` cannot exercise those APIs. Keep UI panels
  toggled with `hidden`, not hash navigation, to avoid mobile mic re-prompts.
- Run focused tests while editing and `npm run test:all` before pushing. Use
  `npm run test:browser:required` for a focused browser run so missing Chrome cannot
  silently pass.
- Preserve unrelated working-tree changes. Deliver requested changes through a PR,
  respect the required `ci` check, and do not merge unless explicitly asked.

Use the shared [project skills](../.claude/skills/) for provider, voice, local-model,
documentation, README artifacts, implementation review, documentation review, and release
workflows. The
[release skill](../.claude/skills/release/SKILL.md) is explicitly invoked to prepare,
publish, or verify a release; do not select publication automatically or act in place
of a designated maintainer. Keep one definition of each skill in `.claude/skills`;
Claude Code, Copilot CLI, and VS Code all discover that location. Use the current
client's normal tools and permissions, not duplicate skill copies or blanket tool
pre-approvals.

The optional [Playwright MCP configuration](../.mcp.json) uses the browser
discovered by the existing harness for exploratory, isolated inspection; it does
not replace `npm run test:browser:required`. See the contributor guide for
discovery and workspace-trust guidance.

See [coding assistant setup](../CONTRIBUTING.md#coding-assistants) for discovery
commands and troubleshooting. Copilot support here is for developing the repository,
not a new inference provider inside the app.
