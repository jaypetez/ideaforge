---
name: review-ideaforge-before-pr
description: Run the adversarial multi-agent review of the committed branch before creating any IdeaForge pull request.
---

# Reviewing an IdeaForge branch before its pull request

Run this before every `gh pr create`. A pre-tool hook refuses that command until this
procedure records a passing result for the exact HEAD being proposed, so a later commit,
amend or rebase needs a new round. The record lives in the gitignored `.ideaforge-assist/`
directory, owned by [`tools/assistant-review.mjs`](../../../tools/assistant-review.mjs).

You orchestrate; the reviewers only read. They are the existing read-only review agents,
so they cannot run commands and need the diff supplied as a file. You own every command,
fix and commit, under [AGENTS.md](../../../AGENTS.md) and [CLAUDE.md](../../../CLAUDE.md).

## 1. Prepare the exact change set

Commit everything first; a review covers a commit, not a working tree. Then:

```sh
node tools/assistant.mjs review prepare
```

It diffs HEAD against its merge-base with `origin/main` (falling back to `main`), writes the
diff to `.ideaforge-assist/review-<head>.diff`, and prints that absolute path, the changed
files and whether documentation changed. Pass `--base REF` when the PR targets another
branch. It refuses a dirty tree or an empty diff.

## 2. Find: reviewers who cannot see each other

Dispatch two change reviewers, plus the documentation reviewer when documentation changed,
all in the same turn, so none reads another's findings. Use the current client's agents:

| Client | Change reviewer | Documentation reviewer |
|---|---|---|
| Claude Code | `ideaforge-review-claude` | `ideaforge-docs-review-claude` |
| Copilot CLI | `ideaforge-review-copilot` | `ideaforge-docs-review-copilot` |

Give each change reviewer the absolute diff path, the base and head commits, the changed
file list, and one lens. Both apply the whole
[`review-ideaforge-change`](../review-ideaforge-change/SKILL.md) skill; the lens says
where to dig deepest:

- **Lens A, contracts:** §2 and §3. Purity, prompt determinism, the coverage ratchet,
  persistence, service-worker shell, CSP, provider auth, key handling, voice sequencing.
- **Lens B, failure:** §4. Stale completions and identity, error paths, data loss, test
  assertions that would still pass with the bug, Android parity through the vectors.

When documentation changed, also dispatch the documentation reviewer with the same diff
path. Ask every reviewer for the skill's §5 report format and nothing else.

## 3. Challenge every candidate

Merge duplicates: the same file, range and mechanism is one candidate. Two reviewers
agreeing is not confirmation; it is still a candidate.

If any candidate remains, dispatch a fresh change reviewer (not either finder) with the
diff path and the numbered candidates, asking it to follow §6 of
[`review-ideaforge-change`](../review-ideaforge-change/SKILL.md) and refute each one.
Documentation candidates go to a fresh documentation reviewer instead. Every candidate
comes back CONFIRMED, REFUTED or UNCERTAIN, with its evidence.

A round always uses two change reviewers, three finders when documentation changed, and
then one fresh challenger for each kind of candidate it produced.

## 4. Resolve

- **CONFIRMED:** fix it here, following the change's own skill and the
  [ladder](../../../AGENTS.md#the-ladder). Add a test that fails without the fix. Commit,
  then start again at step 1: the new HEAD is a new change set.
- **UNCERTAIN:** does not block. Carry each into the PR body under "Review notes", with
  the scenario and what would settle it.
- **REFUTED:** drop it.

After three rounds that still end with a CONFIRMED finding, stop. Report the findings and
your fix attempts to the user; do not record a result.

## 5. Record, then deliver

When a round ends with no CONFIRMED finding, record it against the full commit that
step 1 printed for that round:

```sh
node tools/assistant.mjs review record --head <sha> --result clean
```

Only when the user explicitly asks to skip or override the review:

```sh
node tools/assistant.mjs review record --head <sha> --result waived --reason "the user's reason"
```

Never record `clean` while a confirmed finding stands, and never waive on your own
judgement. Recording refuses a dirty tree, a HEAD that was not prepared, and a `--head`
that is no longer HEAD: records are shared by every session in the worktree, so another
session's commit must not inherit your result. Then deliver
through [Landing a change](../../../AGENTS.md#landing-a-change).

## Limits

The gate is a workflow guard, not a security boundary. It matches `gh pr create` or
`gh pr new` in command position, with quotes ignored, in the Claude Code Bash/PowerShell
tools and any Copilot CLI tool with a command argument. Run `gh` in its own command
from the reviewed branch, without `--head`: a PR command that also changes directory,
commits, rebases, pulls, merges or switches branch, or contains `--head` or `-H`, is
refused in any checkout, whatever the review state. Only the review-state check abstains
in a checkout without the review tool.
Quoted text counts too, so pass PR bodies and commit messages from a file
(`--body-file`, `-F`) or use `--fill`. A quoted path to `gh` that contains spaces, a
command passed to another interpreter as a string, PowerShell splatting, legacy backtick
substitution, a wrapper whose arguments are not flags or numbers, `gh api` and a
person's own terminal are not covered. Hook input that
cannot be read leaves the command to the client's normal permission flow.
