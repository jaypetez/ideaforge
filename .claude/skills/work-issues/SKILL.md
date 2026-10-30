---
name: work-issues
description: Work the IdeaForge GitHub backlog one issue at a time. Picks the next unblocked
  agent-ready issue, implements it, proves every acceptance criterion, gets an independent
  review, and squash-merges only once the required ci check is green on the exact tested
  head. Use only when explicitly invoked.
disable-model-invocation: true
---

# Working the IdeaForge backlog

This skill is shared by Claude Code and GitHub Copilot (CLI and VS Code). Invoke it
explicitly:

- `/work-issues` works the next ready issue and stops.
- `/work-issues 42` works issue #42, if it is ready.
- `/work-issues --max 5` works up to five issues in a row.
- `/loop /work-issues` keeps going, self-paced, until nothing is ready.

Invoking this skill **is** the maintainer's permission to merge green PRs that close
`agent-ready` issues, which is the one thing it adds to the normal rule in
[AGENTS.md](../../../AGENTS.md#landing-a-change). It is not permission to tag, release,
publish Pages by hand, change repository settings or rulesets, spend money, or handle
secrets. The [release skill](../release/SKILL.md) remains a separate, explicit act.

Read [AGENTS.md](../../../AGENTS.md) and [CLAUDE.md](../../../CLAUDE.md) before the first
issue of a run. They are the working loop and the invariants; this file only sequences them.

## Execution boundary

A single-issue invocation runs directly, without an extra coordinator or implementation
worker. Only an explicitly requested multi-issue run (`--max`, a requested queue, or
`/loop /work-issues`) uses the current client's ordinary task/subagent tool to dispatch
one fresh implementation worker per issue. Workers run sequentially with one active issue:
no fleet, parallel issue implementation or parallel emulator jobs. Do not change model,
effort, hooks, permissions or required gates, or add an orchestrator or custom worker agent.

| Owner in a multi-issue run | Responsibility |
|---|---|
| Coordinator | Preflight actual green main and the requested queue/dependencies (sections 1–2), claim one issue and create its branch (section 3), verify the evidence handoff, then own the literal-SHA merge gate and closeout (sections 8–10). |
| Issue worker | Own the complete scoped implementation, affected callers/fixtures/docs, focused regressions, independent code/docs reviews and corrections, clean-head final inventory, draft PR, actual CI/native acceptance and image inspection (sections 4–7). |

The worker may use the existing read-only independent review adapters. The worker must not
spawn another implementation worker or coordinator, pick another issue, or merge. Merge
authority from the original invocation stays with the coordinator, not the delegated packet.

Send a bounded dispatch packet: issue body and acceptance criteria; worktree, branch and
base SHA; owning instructions and skills; inherited mode (Full by default) and the
[canonical lean guidance](../../../AGENTS.md#lean-coding), not earlier transcripts.
For dispatches without child lifecycle hooks, include that canonical section in the prompt;
preserve the [general-purpose fallback](../../../docs/ASSISTANT-PROCESS.md#child-inheritance-limits)
and the review adapters' read-only limits.

Return a compact persistent handoff from the per-issue record: exact tested SHA and
commands/results; review verdicts and combined code/docs round; actual CI/device run IDs
and attempts; artifact/profile/SHA provenance (including any evidence helper's source);
unresolved errors and the next action. Use references to bounded artifacts, not raw
source/log/history dumps. The coordinator verifies the actual required `ci` and acceptance
results against that SHA, but does not redo the worker's exploration, reviews, tests or
screenshots.

Send same-issue follow-ups to the same worker with its combined review round and current
failure state; never restart context to evade review limits. The next worker starts only
after the coordinator verifies actual green merged main and the remaining requested
dependencies (or rechecks unchanged green main after an escalation). If fresh worker
contexts are unavailable, or the same worker cannot be continued, report the limitation
and stop rather than silently replacing it with an unbounded conversation.

## The labels this skill reads and writes

| Label | Meaning |
|---|---|
| `agent-ready` | Fully specified; this skill may take it. |
| `agent-in-progress` | Claimed by a run. At most one open issue carries it. |
| `needs-human` | Needs a person: a physical device, a secret, paid usage, or a decision. Never taken. |
| `blocked` | Waiting on something outside the dependency graph. Never taken. |
| `epic` | A parent issue. Never taken; its sub-issues are. |
| `priority:P0`–`priority:P3` | Ordering within a milestone. |

## 1. Preflight

Stop the whole run, rather than one issue, if any of these fail.

```sh
git status --porcelain                       # must print nothing
git switch main && git pull --ff-only
gh api user --jq .login                      # the maintainer account the run acts as
gh run list --workflow CI --branch main --commit "$(git rev-parse HEAD)" --limit 1 \
  --json databaseId,attempt,headSha,status,conclusion
```

- The working tree must be clean. Never stash, reset or discard someone else's work to make
  it so; stop and report.
- The latest `CI` run for the actual updated main SHA must have matching `headSha`,
  status `completed` and conclusion `success`. Pending, missing, cancelled and skipped
  are not green. Red main stops the run; do not silently start a CI-repair project.
- If an open issue already carries `agent-in-progress`, resume it (section 4 onwards on its
  existing branch and PR) only within this run's scope, preserving the recorded review/
  failure state and, for a multi-issue run, the same worker. If another run owns the claim
  or it is outside the requested scope, stop and report instead of claiming another.

## 2. Pick the next issue

Preflight the requested queue and dependency closure before claiming anything. Respect an
explicit issue list, order and maximum count; do not substitute an unrelated ready issue.

```sh
gh issue list --state open --label agent-ready --limit 200 \
  --json number,title,labels,milestone
```

Exclude anything also labelled `epic`, `needs-human`, `blocked` or `agent-in-progress`.
Then drop every issue with an open blocker. Read blockers from both sources, because either
may be the only one present:

```sh
gh api graphql -F n=42 -f query='query($n:Int!){repository(owner:"jaypetez",name:"ideaforge"){issue(number:$n){blockedBy(first:50){nodes{number state} pageInfo{hasNextPage endCursor}}}}}'
gh issue view 42 --json body --jq .body     # "Blocked by #N" lines
```

Follow transitive open blockers for the requested queue, inspecting both sources; paginate
incomplete listings rather than assuming the first page is the closure. Disclose
outside-request prerequisites before implementation: they require explicit authorisation,
not extra repair projects silently added to the requested issue count.

Within the requested scope and order, sort remaining choices by milestone (`M1` before `M2`
and so on), then priority (`P0` first), then issue number. Take the first. If none remains,
report what is blocked and on what, and stop.

## 3. Claim it

```sh
gh issue edit 42 --add-label agent-in-progress
git switch -c issue/42-short-slug main
```

Comment on the issue with a short plan: the approach, the files you expect to touch, and
how each acceptance criterion will be proved. If the issue is ambiguous enough that a
reasonable plan needs a product decision, escalate (section 9) now rather than guessing.
For a multi-issue run, the coordinator now sends the dispatch packet to one fresh worker
on this branch. For one issue, continue directly.

## 4. Implement

- Treat the issue body as the specification. The acceptance criteria are the definition of
  done. The "Invariants at risk" section names the CLAUDE.md rules to re-read.
- Map the owning code and every affected caller, test, fixture, configuration and
  documentation surface before choosing the implementation; use AGENTS.md's lean ladder.
- Batch independent file/range reads and GitHub metadata queries. Keep known inspect/act/
  verify steps together; investigate separately when a result determines the next action.
- Use the area skill when one applies:
  [change-voice-and-driving](../change-voice-and-driving/SKILL.md),
  [add-provider](../add-provider/SKILL.md),
  [android-verify](../android-verify/SKILL.md) for anything under `android/` or a change
  that regenerates the golden vectors,
  [update-documentation](../update-documentation/SKILL.md) (which invokes
  [update-readme](../update-readme/SKILL.md)), or
  [validate-local-model](../validate-local-model/SKILL.md).
- Climb the test ladder in AGENTS.md from the cheapest rung. Where practical, first show
  a meaningful regression failing for the intended bug or absent feature, then implement.
- Bring acceptance risks forward before final validation: truthful absent/saved fields,
  actual native touch targets, visible named errors/loading, semantic selectors and affected
  walkthroughs. Inspect changed states during focused development, not first after the final
  gates. Use [native readiness](../android-verify/SKILL.md#1a-native-readiness-during-development)
  for native geometry, captures and walkthrough consumers.
- Keep the change to the issue. An unrelated defect found on the way gets its own issue
  (labelled, with acceptance criteria), not a drive-by fix.
- Public behaviour changes update documentation in the same PR. Documentation is one
  synchronized surface.
- Keep one per-issue record in the session workspace: phase, acceptance evidence,
  command/result, tested SHA, review round and reviewed diff, CI/device run/attempt,
  artifact/profile provenance, and the current failure. Update it at phase changes or
  failures, not every routine command. Do not commit it, raw logs or private timing data.
  Restore that record after compaction or resume instead of restarting completed phases.
  Existing browser elapsed logs, Gradle reports and GitHub job timestamps distinguish
  validation from model/tool waiting; do not infer model speed from total issue time.

## 5. Prove it

Every acceptance criterion needs evidence: a test name, a command and its result, or a
screenshot you have looked at. "It should work" is not evidence.

During implementation, run the cheapest relevant checks first: unit selectors, an exact
`node tools/browser-check.mjs --required --probe theme.browser.mjs`, or Gradle's `--tests`
selector as appropriate. A focused browser run still includes the independent policy and
accessibility phase. Inspect a failure before rerunning a broader command.

The table is the required final evidence inventory, not a second full-validation phase
before section 7. Complete focused regression proof before review; after confirmed fixes,
collect the final inventory once on the clean final head in section 7.

| Touched | Required final evidence |
|---|---|
| Anything | `npm run test:all` (includes `npm test`, graph and required-browser checks), plus `npm run test:docs` |
| Web UI, README or its generated artifacts | `npm run screenshots`, then open the changed images, light and dark |
| `android/` or regenerated golden vectors | `./gradlew check :app:assembleDebug` in `android/` (`gradlew.bat` on Windows), plus the emulator smoke run in [android-verify](../android-verify/SKILL.md) when an emulator is available |
| `.github/workflows/` | `npm run test:docs`, and read the workflow run on the PR |

Native-only visible changes need their native acceptance evidence, not regeneration of
unchanged web README images. A focused capture is not a full-matrix pass. Preserve local
Gradle state: no routine `clean`, `--rerun-tasks`, cache deletion or daemon shutdown between
fixes; follow the Android skill's shutdown rule when actually removing a worktree.

When a check fails, work out whether the assertion or the code is wrong before changing
either. Both have happened here. Do not retry an identical unchanged failure without a
specific transient-infrastructure diagnosis. Preserve failed output; a recurring failure
needs correction or section 9 escalation, not retries until green.

## 6. Independent review

Commit, then run [`review-ideaforge-before-pr`](../review-ideaforge-before-pr/SKILL.md).
It gives the exact diff to at least two read-only reviewers who cannot see each
other and has a fresh one challenge their findings, using the current client's review agents
(see [the client table](../../../CONTRIBUTING.md#coding-assistants)). The caller owns validation:
the issue worker in a multi-issue run, not its coordinator. Review agents do not run commands
or repeat the test ladder.

Fix every confirmed finding, run the focused regression checks and review the new HEAD,
carrying forward unresolved findings. Count combined code/docs review-and-fix rounds per
issue, not per agent; compaction or resume never resets the count.
Stop after three rounds that still leave confirmed findings, and escalate. The skill records
the result that lets `gh pr create` run.

A clean review of an unchanged diff is terminal. CI completion, a progress question or a
context restore is not a reason for another review. Changed code, documentation or relevant
review context invalidates that verdict; do not reuse it for an unseen diff.

## 7. Deliver

Once review corrections are complete and code, documentation and capture drivers are
settled, complete section 5's final inventory and validate/push exactly as AGENTS.md requires.
The `test:all` below supplies the unit, graph and required-browser evidence together; do not
precede it with another full browser run merely to mark section 5 complete. Stage and commit
generated artifacts, including them in the reviewed diff, before the clean-head gate. Any
later head, working-tree, fixture or validation-input change, including test-only changes,
invalidates corresponding proof and requires final validation again before pushing. Neither
an old-SHA pass nor a focused capture can substitute for the required final inventory on the
new head.

```sh
test -z "$(git status --porcelain)" &&
tested=$(git rev-parse HEAD) &&
npm run test:all &&
test "$(git rev-parse HEAD)" = "$tested" &&
test -z "$(git status --porcelain)" &&
git push -u origin HEAD
```

In PowerShell, use the PowerShell block from AGENTS.md up to and including `git push` and
its exit check, then continue below. Its trailing `gh pr create --fill` would open a PR
without `Closes #42` or the evidence checklist.

Write down the full SHA that `tested` held. Shell state does not survive between tool
calls, so the merge gate needs it as a literal value. Record each command's result and the
CI run/attempt alongside that SHA. Do not rerun a passed gate on unchanged inputs merely
while waiting, or push metadata-only checkpoints just to start another CI run.

Open a draft PR using the repository's template, preserving its headings, comments and
checklists. Its body must contain `Closes #42`, a checklist mapping each acceptance criterion
to its evidence, and a summary of the commands run with their results. End it with the
attribution line the session's instructions require.

```sh
gh pr create --draft --base main --title "Short imperative title (#42)" --body-file pr-body.md
```

Write `pr-body.md` in the scratchpad, not the repository.

Then wait for `ci` on the head you pushed. Do not rely on `gh pr checks --watch --required`
alone: right after a push the workflow has not registered its checks yet, and the watch
exits 0 with "no required checks reported" having waited for nothing. Poll until `ci`
exists on that exact head and has completed:

```sh
PR=99 TESTED=0123456789abcdef0123456789abcdef01234567 &&
for i in $(seq 1 120); do
  state=$(gh pr view "$PR" --json headRefOid,statusCheckRollup --jq '[.headRefOid, (((.statusCheckRollup | map(select(.name == "ci")) | .[0]) // {}) | "\(.status // "NONE"):\(.conclusion // "")")] | join(" ")')
  echo "$i $state"
  case "$state" in
    "$TESTED COMPLETED:"*) break ;;
    "$TESTED "*) sleep 30 ;;
    "") echo "gh query failed; retrying" && sleep 30 ;;
    *) echo "head moved; re-validate the new head" && break ;;
  esac
done
```

Use one bounded watcher per required run/check; run it in the background when useful and
the client allows it, then consume completion/output once. A timeout is a pending result to
report, not a pass or a reason to start duplicate watchers.

Complete actual CI/native acceptance required by the issue, not just dispatch or build
success. Record the real run IDs/attempts and tested SHA; for native evidence, verify
nonzero selected-test execution, the requested profiles/matrices and artifact provenance,
and inspect the required PNGs. Verify uploaded evidence by reading it back. Reuse valid
existing evidence helpers with their source identity; a helper in a retained worktree is
not proof that it shipped on main.

Keep the PR draft while evidence or findings remain unresolved. In a multi-issue run the
worker returns the handoff; the coordinator verifies it against the real results, then uses
`gh pr ready 99` before section 8. A direct single-issue caller performs both roles.

## 8. Merge gate

The maintainer account is an `always` bypass actor on **both** rulesets: `main review`
(the approval an agent cannot give itself) and `main protection` (the required `ci`
check and the up-to-date rule). So `--admin` gets past all of them at once, and the check
below is the only thing standing between a merge and an untested commit. Never weaken it,
split it, or run the merge without it.

In a multi-issue run, only the coordinator executes this gate, using the verified handoff's
literal tested SHA and actual required `ci` result. A worker report alone is not the gate.

It is one command, in a POSIX shell (Git Bash on Windows), so nothing can change between
the check and the merge. Put in the PR number and the literal SHA you validated:

```sh
PR=99 TESTED=0123456789abcdef0123456789abcdef01234567 &&
gate=$(gh pr view "$PR" --json headRefOid,mergeStateStatus,isDraft,statusCheckRollup \
  --jq '[.headRefOid, ((.statusCheckRollup | map(select(.name == "ci")) | .[0].conclusion) // "MISSING"), .mergeStateStatus, (.isDraft | tostring)] | join(" ")') &&
set -- $gate &&
echo "head=$1 ci=$2 state=$3 draft=$4" &&
[[ "$1" =~ ^[0-9a-f]{40}$ ]] && [ "$1" = "$TESTED" ] &&
[ "$2" = SUCCESS ] && [ "$3" != BEHIND ] && [ "$3" != DIRTY ] && [ "$4" = false ] &&
gh pr merge "$PR" --squash --admin --match-head-commit "$1" --delete-branch
```

What each condition means, so a refusal can be diagnosed rather than worked around:

- The head is a full SHA and equals the commit you validated and pushed. A different head
  means someone (or `update-branch`) moved it: re-run section 5 on the new head first.
- `ci` concluded `SUCCESS` on that head. Pending, failure, cancelled, skipped and missing
  all refuse. Wait with the polling loop in section 7 and try again.
- The branch is not `BEHIND` `main` (or conflicted). If it is, run
  `gh pr update-branch 99`, pull, re-run section 5 on the new head, and wait for `ci`.
  `BLOCKED` is expected and fine: that is the missing approval.
- The PR is not a draft, and no review finding is unresolved.

`--match-head-commit` with the verified SHA makes GitHub itself refuse the merge if the
head moves after the check. Never use `--no-verify`, never force-push, never push to
`main` directly, and never edit a ruleset or a required check to get a PR through.

## 9. Close out, or escalate

The coordinator owns closeout in a multi-issue run; a worker reports escalation rather than
selecting another issue.

**After a merge:**

```sh
git switch main && git pull --ff-only
gh issue view 42 --json state --jq .state     # CLOSED, via "Closes #42"
gh issue edit 42 --remove-label agent-in-progress
npm test
```

Comment on the issue with the PR link and one line on what shipped. If the merge closed the
last open blocker of another issue, nothing else is needed; the next pick will see it.
The next issue must still satisfy the green-main preflight on the actual merged commit.
Observe that CI result; do not add unrelated optional device, Pages or release workflows
to the wait. Device evidence required by this issue's acceptance criteria remains required.

**Escalate when** three fix rounds still fail, when the work needs a physical device, a
secret, paid usage or a product decision, or when the issue turns out to be wrong:

- Comment with what was tried, the exact failing output, and what a person needs to decide
  or do.
- Swap `agent-in-progress` for `needs-human`.
- Leave any PR open as a draft (`gh pr ready 99 --undo`) and switch back to `main`.
- Continue with the next issue, unless the failure is in `main` or `ci` itself; then stop
  the run.

## 10. Stop

Stop when nothing is ready, when `--max` is reached, or on any preflight failure. Finish
with a short report: issues merged (with PR links), issues escalated and why, and what is
next in the queue.
