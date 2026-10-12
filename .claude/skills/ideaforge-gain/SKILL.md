---
name: ideaforge-gain
description: Report reproducible local Git and shortcut facts without inventing IdeaForge savings.
---

# Reporting local evidence

This is a read-only, one-shot display body. It changes no mode, default, setting, source or
state, and writes no report file.
[Automatic session initialisation](../../../docs/ASSISTANT-PROCESS.md#session-initialisation-and-one-shot-bodies)
is separate and grants no write permission to this body. Do not call models, run paid
evaluations, read secrets/transcripts, or infer counterfactual savings.
The [evidence reference](../../../docs/ASSISTANT-PROCESS.md#local-gain-not-savings) defines
what a gain report can honestly claim.

## 1. Establish the comparison

Use the [local helper](../../../tools/assistant.mjs) from the repository/worktree root:

```powershell
node tools\assistant.mjs gain
node tools\assistant.mjs gain --base REF
```

Use only a locally available Git reference explicitly requested by the caller, or the
helper's documented default comparison. Do not fetch, switch branches, amend commits or
invent a baseline. The default compares the tracked working tree and index with HEAD;
`--base REF` compares directly with that reference's resolved commit, not an automatic
merge base. Record the root, base/HEAD identity actually checked and worktree scope.
If the helper omits a resolved HEAD id, obtain it only with an approved read-only Git
query or mark it not checked; do not invent snapshot metadata.

## 2. Report only counted facts

Give actual changed-file/addition/deletion counts and shortcut-marker facts emitted by the
helper, with the comparison scope and reproducible command. State that untracked files
are not counted and binary files are separate from text addition/deletion totals; preserve
the debt scanner's own exclusions and omission report. Counts describe the diff, not code
quality or savings caused by assistance.
Include `debt.unread` files/reasons and `debt.omitted` in the coverage summary. YAML literal
contexts are unsupported; deleted/missing tracked source is not inspected. Zero recognised
markers with these gaps is not evidence that those files contain no authored shortcuts.

An unavailable/invalid base, failed Git command, incomplete scan, binary diff or malformed
output is a limitation to report, not evidence of zero change. Do not estimate "lines not
written", saved tokens, reduced cost, time saved, prevented defects or return on investment.
The unbuilt alternative is not a measured baseline.

## 3. Keep upstream evidence separate

If upstream results are relevant to the request, link the pinned report in
[provenance](../../../docs/ASSISTANT-PROCESS.md#provenance) and label it **upstream
Ponytail evidence, not an IdeaForge result**. Include model/task-specific limitations;
do not present a marketing scoreboard as a local measurement or imply that fixture tests
demonstrate empirical LLM benefit.

For current trade-offs, point to
[`ideaforge-debt`](../ideaforge-debt/SKILL.md); for explicitly requested source inspection,
point to [`audit-ideaforge-codebase`](../audit-ideaforge-codebase/SKILL.md).
Stop after the evidence display; neither suggestion starts another procedure.
