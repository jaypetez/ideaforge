---
name: ideaforge-debt
description: Report tracked IdeaForge shortcut comments, limits, and missing upgrade triggers.
---

# Reporting shortcut debt

This is a read-only, one-shot report body by default. It does not change mode, defaults,
source, settings or assistant state.
[Automatic session initialisation](../../../docs/ASSISTANT-PROCESS.md#session-initialisation-and-one-shot-bodies)
is separate and grants no write permission to this body. Never add markers to old code
merely to produce a ledger, remediate findings, scan credentials/transcripts, or make
network/model calls.
See [shortcut debt](../../../docs/ASSISTANT-PROCESS.md#shortcut-debt) and the canonical
[lean guidance](../../../AGENTS.md#lean-coding).

## 1. Scan the requested tracked scope

Run the [portable local helper](../../../tools/assistant.mjs) from the repository/worktree
root using normal approved tools:

```powershell
node tools\assistant.mjs debt
```

The helper uses a bounded, format-limited lexer to identify comment-form candidates:

```text
shortcut: <decision>; limit: <bound>; upgrade: <trigger>
```

It also recognises the legacy `ponytail:` marker. This is not a full language parser or a
promise to recognise authored comments in every format. Eligible `.yaml`/`.yml` files are
not inspected and appear in `unread` with the reason
`YAML literal contexts are not supported by the debt lexer`.

Preserve every `Not inspected: FILE: REASON` row, unread count and budget omission.
Missing tracked files, including working-tree deletions, are coverage gaps rather than
zero-marker files. Prose, strings and documentation examples are not deliberate code
shortcuts; do not turn an unavailable or partial scan into a zero-debt result.
If the caller asks for a path or keyword subset, filter the reported rows and label that
scope instead of claiming the subset covers the repository.

## 2. Report decisions, not invented excuses

Read the candidate's source and surrounding literal/comment context using approved read
tools before attributing it as an authored decision. The lexer handles regexp escapes
and character classes, including quotes in `/["']/`, but remains format-limited.
If source verification is unavailable or ambiguous, label the candidate and limitation
rather than claiming a confirmed comment. Do not expand into excluded/private files to
fill coverage gaps.

For each confirmed marker, give file/line, decision, limit and upgrade trigger. Mark a
missing limit or trigger explicitly; do not supply a plausible value as though the author
wrote it.
Summarise marker and missing-trigger counts within the stated scan scope.

A named limit is context, not proof of correctness. If its trigger has already occurred,
or the shortcut loses data or violates a current requirement, say so with source evidence.
Otherwise report it as a bounded decision rather than calling every small implementation
technical debt. No markers found means no recognised markers in the reported inspected
scope, not no comments in unsupported formats, design costs or bugs.

## 3. Persist only when explicitly asked

Return the ledger in the reply. Do not create a report file automatically. If the user
explicitly asks to persist it, write only the requested repository-local report using the
current client's normal edit permissions; preserve any existing content and name the scope
and limitations. This explicit report write is not authority to annotate source, repair
shortcuts, alter mode/state, or create a recurring audit.
