# Documentation source map

Use this map to find the owner of a claim before changing its wording. The nearest executable
source wins over prose, and a generated artifact wins over a description of that artifact.

## Canonical repository references

- **Working and validation loop.** Sources:
  [`AGENTS.md`](../../../../AGENTS.md) and
  [`package.json`](../../../../package.json). Re-check `README.md`, `CONTRIBUTING.md`, the
  development guide, and skills.
- **Implementation invariants.** Sources:
  [`CLAUDE.md`](../../../../CLAUDE.md), the owning modules, and their tests. Re-check
  `docs/ARCHITECTURE.md`, focused skills, and the development guide.
- **Architecture and extension seams.** Sources:
  [`docs/ARCHITECTURE.md`](../../../../docs/ARCHITECTURE.md) and `src/`. Re-check
  `CONTRIBUTING.md` and the development guide.
- **Purity boundary.** Source:
  [`tools/lint-purity.mjs`](../../../../tools/lint-purity.mjs). Re-check `AGENTS.md`,
  `CLAUDE.md`, `CONTRIBUTING.md`, and the development guide.
- **Available commands.** Source:
  [`package.json`](../../../../package.json). Re-check every command block in repository
  documentation and skills.
- **Public guide shell and navigation.** Sources:
  [`guide/index.html`](../../../../guide/index.html) and
  [`guide/assets/guide.css`](../../../../guide/assets/guide.css). Re-check every guide page.
- **Public guide runtime contract.** Sources:
  [`test/browser/guide.browser.mjs`](../../../../test/browser/guide.browser.mjs),
  [`tools/assemble-site.mjs`](../../../../tools/assemble-site.mjs), and
  [`sw.js`](../../../../sw.js). Re-check the page list, relative assets, CSP, and
  service-worker wording.
- **CI gate.** Source:
  [`.github/workflows/ci.yml`](../../../../.github/workflows/ci.yml). Re-check `AGENTS.md`,
  `CONTRIBUTING.md`, the development guide, and the release procedure.
- **Pages deployment.** Sources:
  [`.github/workflows/pages.yml`](../../../../.github/workflows/pages.yml) and
  [`tools/assemble-site.mjs`](../../../../tools/assemble-site.mjs). Re-check the development
  guide, architecture, and release procedure.
- **Release publication.** Sources:
  [`.github/workflows/release.yml`](../../../../.github/workflows/release.yml),
  [release-note configuration](../../../../.github/release.yml),
  [`package.json`](../../../../package.json), and
  [`src/version.js`](../../../../src/version.js). Re-check `CONTRIBUTING.md`, the development
  guide, and [`release`](../../release/SKILL.md).
- **Security and privacy.** Sources:
  [`SECURITY.md`](../../../../SECURITY.md), [`index.html`](../../../../index.html),
  `src/store/`, and `src/providers/`. Re-check the README, privacy guide, and provider guide.
- **Assistant discovery.** Sources:
  [Copilot instructions](../../../../.github/copilot-instructions.md),
  [`AGENTS.md`](../../../../AGENTS.md), [`CLAUDE.md`](../../../../CLAUDE.md), and
  [`CONTRIBUTING.md`](../../../../CONTRIBUTING.md). Re-check the development guide, skills,
  and review adapters.
- **Optional browser MCP.** Source:
  [`.mcp.json`](../../../../.mcp.json) and
  [`tools/playwright-mcp.mjs`](../../../../tools/playwright-mcp.mjs). Re-check the
  Copilot instructions, `CONTRIBUTING.md`, and `test/agent-support.test.mjs`.
- **Skill and agent structure.** Source:
  [`test/agent-support.test.mjs`](../../../../test/agent-support.test.mjs), plus
  `.claude/skills/`, `.claude/agents/`, and `.github/agents/`. Re-check assistant guidance
  and adapters.

## Change triggers

- Changes to `src/core/dimensions.js`, `src/core/engine.js`, coverage, or synthesis require a
  re-check of README facts and example, the interviews guide, and architecture.
- Changes to `src/providers/`, provider CSP origins, or transcription presets require a
  re-check of the README provider table, providers and privacy guides, contributor provider
  instructions, and [`add-provider`](../../add-provider/SKILL.md).
- Changes to `src/voice/`, driving commands, or browser voice fixtures require a re-check of
  the mobile and voice guide, troubleshooting, architecture, and
  [`change-voice-and-driving`](../../change-voice-and-driving/SKILL.md).
- Changes to `src/store/`, backup, sharing, or key handling require a re-check of the ideas
  guide, privacy guide, `SECURITY.md`, and architecture.
- Changes to `src/ui/`, `index.html`, `sw.js`, or `manifest.webmanifest` require a re-check of
  relevant guide pages, screenshots and worked example, and architecture.
- Changes to `package.json` or `tools/` commands require a re-check of the README, `AGENTS.md`,
  `CONTRIBUTING.md`, the development guide, and affected skills.
- Changes to CI, Pages, release, Docker, or site assembly require a re-check of the
  development guide, architecture, contributor guidance, and release guidance.
- Changes to instructions, skills, agents, or discovery behaviour require a re-check of
  `CONTRIBUTING.md`, Copilot instructions, the development guide, and agent-support
  expectations.

## Generated and hand-authored boundaries

- [`README.md`](../../../../README.md), screenshots, and the worked example follow the
  [`update-readme`](../../update-readme/SKILL.md) procedure. The images and example are
  generated; refresh them through the harness rather than editing them by hand.
- The public guide is hand-authored but shares one shell. Copy structural markup from the
  index instead of reconstructing it from memory.
- `docs/ARCHITECTURE.md` maps ownership and extension seams. Long invariants belong in
  `CLAUDE.md`; iteration and proof belong in `AGENTS.md`.
- Review agents are adapters, not second skill definitions. Their body should link locally to
  the shared skill and add only client-specific discovery or read-only restrictions.

## Stable-writing rules

- Derive facts from source on every update; never copy a number from the previous document.
- Prefer a symbol or workflow name over a line number.
- State which component owns behaviour and which check proves it.
- Keep historical rationale only when it explains a current constraint.
- Do not copy the full internal trap catalogue into the public guide. Link maintainers to the
  canonical repository references instead.
