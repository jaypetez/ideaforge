---
name: ideaforge-assist-help
description: Explain IdeaForge assistance controls and shared procedures in a read-only one-shot response.
---

# Explaining IdeaForge assistance

This is a read-only, one-shot help response. Do not activate/deactivate assistance, change
mode/defaults, reset state, edit configuration, install anything, or invoke an audit/review
as a side effect of explaining one.

Read the [assistant reference](../../../docs/ASSISTANT-PROCESS.md) and
[Lean coding](../../../AGENTS.md#lean-coding). Explain the part the caller needs, linking
the owners instead of copying the complete ladder or inventing client capabilities.

## Quick navigation

- [Controls](../../../docs/ASSISTANT-PROCESS.md#controls):
  `/ideaforge-assist`, `LEVEL`, `default LEVEL` and `reset`; the levels are
  `lite`, `full`, `ultra`, `off`, with automatic balanced Full unless configured otherwise.
- [Mode/default/reset procedure](../ideaforge-assist/SKILL.md):
  explicitly invoked controls, real client/session identity and truthful results.
- [State and cleanup](../../../docs/ASSISTANT-PROCESS.md#state-defaults-and-cleanup):
  local/default/environment precedence and narrow session reset, never global edits.
- [Native clients and status](../../../docs/ASSISTANT-PROCESS.md#native-clients-and-status):
  basic Copilot command hooks, the conditional SDK supplement, optional read-only status
  and unsupported inheritance boundaries.
- [Copilot without SDK refresh](../../../docs/ASSISTANT-PROCESS.md#copilot-without-sdk-refresh):
  persisted controls are not same-turn context; explain the explicit manual fallback
  without changing mode or performing a refresh as a help side effect.
- [Bounded map](../../../docs/ASSISTANT-PROCESS.md#bounded-map):
  `node tools\assistant.mjs map`, tracked-only orientation rather than an LSP.
- [Exact-diff review](../review-ideaforge-change/SKILL.md):
  an exact supplied change set, using the existing restricted client adapters.
- [Pre-PR review](../review-ideaforge-before-pr/SKILL.md):
  two blind reviewers and a challenger before every PR, recorded per HEAD and enforced
  by a pre-tool hook on `gh pr create`.
- [Codebase audit](../audit-ideaforge-codebase/SKILL.md):
  an explicitly requested, map-first scope, not automatic remediation.
- [Debt](../ideaforge-debt/SKILL.md):
  comment decisions, limits and upgrade triggers; report-only unless persistence is asked for.
- [Gain](../ideaforge-gain/SKILL.md):
  reproducible local facts, not hypothetical savings or foreign benchmark guarantees.
- [Worked choices](../../../docs/ASSISTANT-PROCESS.md#worked-choices-from-ideaforge) and
  [evidence](../../../docs/ASSISTANT-PROCESS.md#evidence-and-validation):
  source-owned examples, protocol/native proof and unmeasured LLM benefit kept distinct.

If current status was explicitly requested and a trusted client/session id is available,
the shared `status` command is read-only. Otherwise explain how to inspect it without
inventing an identity. The help body must leave local state unchanged.
[Automatic session initialisation](../../../docs/ASSISTANT-PROCESS.md#session-initialisation-and-one-shot-bodies)
may separately seed default-mode metadata before the body; that does not permit help to
change modes or configuration.

Finish with any material compatibility limitation relevant to the question. Off does not
remove the repository's baseline rules, and no control grants tool, merge or release
authority.
