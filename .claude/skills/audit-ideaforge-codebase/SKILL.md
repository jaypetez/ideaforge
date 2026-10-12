---
name: audit-ideaforge-codebase
description: Audit an explicitly requested IdeaForge repository or path scope without edits.
disable-model-invocation: true
---

# Auditing the IdeaForge codebase

This is an explicitly requested, read-only, one-shot audit. Do not select a broad audit
merely because you are implementing or reviewing a change. Do not edit, remediate, create
a ledger, commit, push, merge, tag, publish, change modes/settings, or call live providers.
Never inspect credentials, stored transcripts, browser profiles or personal configuration.

For an exact diff, use
[`review-ideaforge-change`](../review-ideaforge-change/SKILL.md) instead. An audit does not
relax that skill's supplied-change-set requirement. A dedicated request to find exploitable
security vulnerabilities belongs to the current client's dedicated security-review
procedure and permissions; this audit is not a substitute or a claim of exhaustive
vulnerability coverage.

## 1. Name the scope and map it first

Establish the repository or in-root paths explicitly requested by the caller. State the
revision and working-tree context available to you, the requested concerns, and any
exclusions. If the requested source is unavailable, stop and name that limitation rather
than auditing a different checkout.

Read [AGENTS.md](../../../AGENTS.md), [CLAUDE.md](../../../CLAUDE.md),
the [architecture map](../../../docs/ARCHITECTURE.md), and the relevant package/build,
entry-point and test owners. Start orientation with the bounded local map when approved
shell tools are available:

```powershell
node tools\assistant.mjs map
```

The [map](../../../docs/ASSISTANT-PROCESS.md#bounded-map) lists tracked source heuristically;
it is not an LSP, call graph or evidence that the listed code was read. Respect its budget
and omitted-file disclosure. If tools cannot run it, use a supplied map or accessible
tracked-source inventory and report the missing map step. Do not acquire shell permissions
through this skill, traverse excluded/private files, or replace the budget with an
unbounded filesystem scan.

## 2. Trace the requested flows

Follow actual callers, inputs, state changes, failure paths and tests in scope. For an
interview audit, that means the answer-to-turn-to-synthesis/export flow, not just the prompt
builder. For storage or voice, include identity, delayed completion, cancellation and
recovery. Include the Kotlin consumer when the requested behaviour affects shared vectors.

Derive expected load and concurrency from the implementation and deployment, not an
imagined server fleet. Distinguish an on-device library, concurrent tabs, asynchronous
effects and separate assistant sessions. Mark unknown limits as unknown; do not invent a
benchmark or extrapolate a regex map into a whole-repository trace.

## 3. Apply the shared defect criteria

Use the architectural, browser/security and validation criteria in
[`review-ideaforge-change`](../review-ideaforge-change/SKILL.md), particularly
[scale, races, test quality and reuse](../review-ideaforge-change/SKILL.md#scale-races-test-quality-and-reuse).
Those criteria are references, not permission to invoke an exact-diff review with no diff.
[Lean coding](../../../AGENTS.md#lean-coding) remains the sole ladder owner.

Report only concrete, high-confidence defects or demonstrably unmet current requirements.
For each candidate, identify an input/event sequence, the observed code path, the user
impact, and the smallest safe fix direction. A shortcut marker neither excuses a real bug
nor makes a deliberately bounded design a defect before its bound is exceeded.
Do not report formatting, speculative future scale, hypothetical abstractions, line-count
targets, or nice-to-have refactors.

## 4. Return the bounded report and stop

Report at most 20 findings, continuously numbered and ranked by severity. Each finding
needs confidence, file and useful line range, concrete failing scenario, impact, why current
validation misses it, and a safe fix direction. Consolidate repeated manifestations of the
same cause; do not pad the report to reach the cap.

End with coverage: requested scope, flows/files actually read, checks actually run and their
results, and important not-read/not-checked boundaries. Label a capped or partial report.
If none qualify, say "No high-confidence defects found in the inspected scope", not that
the entire repository is proven correct or secure. Do not implement findings automatically.
