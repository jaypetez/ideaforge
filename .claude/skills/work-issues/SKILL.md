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

For long runs, a fresh context per issue is better than one long conversation. A shell loop
over `claude -p "/work-issues"` gives each issue its own context.

Invoking this skill **is** the maintainer's permission to merge green PRs that close
`agent-ready` issues, which is the one thing it adds to the normal rule in
[AGENTS.md](../../../AGENTS.md#landing-a-change). It is not permission to tag, release,
publish Pages by hand, change repository settings or rulesets, spend money, or handle
secrets. The [release skill](../release/SKILL.md) remains a separate, explicit act.

Read [AGENTS.md](../../../AGENTS.md) and [CLAUDE.md](../../../CLAUDE.md) before the first
issue of a run. They are the working loop and the invariants; this file only sequences them.

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
gh run list --workflow CI --branch main --limit 1 --json conclusion,headSha
```

- The working tree must be clean. Never stash, reset or discard someone else's work to make
  it so; stop and report.
- The latest `CI` run on `main` must be `success`. Building on a red `main` makes every
  later failure ambiguous.
- If an open issue already carries `agent-in-progress`, resume it (section 4 onwards on its
  existing branch and PR) instead of claiming another.

## 2. Pick the next issue

```sh
gh issue list --state open --label agent-ready --limit 200 \
  --json number,title,labels,milestone
```

Exclude anything also labelled `epic`, `needs-human`, `blocked` or `agent-in-progress`.
Then drop every issue with an open blocker. Read blockers from both sources, because either
may be the only one present:

```sh
gh api graphql -F n=42 -f query='query($n:Int!){repository(owner:"jaypetez",name:"ideaforge"){issue(number:$n){blockedBy(first:50){nodes{number state}}}}}'
gh issue view 42 --json body --jq .body     # "Blocked by #N" lines
```

Order what remains by milestone (`M1` before `M2` and so on), then priority (`P0` first),
then issue number. Take the first. If none remains, report what is blocked and on what, and
stop.

## 3. Claim it

```sh
gh issue edit 42 --add-label agent-in-progress
git switch -c issue/42-short-slug main
```

Comment on the issue with a short plan: the approach, the files you expect to touch, and
how each acceptance criterion will be proved. If the issue is ambiguous enough that a
reasonable plan needs a product decision, escalate (section 9) now rather than guessing.

## 4. Implement

- Treat the issue body as the specification. The acceptance criteria are the definition of
  done. The "Invariants at risk" section names the CLAUDE.md rules to re-read.
- Use the area skill when one applies:
  [change-voice-and-driving](../change-voice-and-driving/SKILL.md),
  [add-provider](../add-provider/SKILL.md),
  [update-documentation](../update-documentation/SKILL.md) (which invokes
  [update-readme](../update-readme/SKILL.md)), or
  [validate-local-model](../validate-local-model/SKILL.md).
- Climb the test ladder in AGENTS.md from the cheapest rung. Write the test that would have
  caught the bug, or that fails without the feature, before trusting the change.
- Keep the change to the issue. An unrelated defect found on the way gets its own issue
  (labelled, with acceptance criteria), not a drive-by fix.
- Public behaviour changes update documentation in the same PR. Documentation is one
  synchronized surface.

## 5. Prove it

Every acceptance criterion needs evidence: a test name, a command and its result, or a
screenshot you have looked at. "It should work" is not evidence.

| Touched | Run |
|---|---|
| Anything | `npm test`, `npm run test:docs`, `npm run test:graph` |
| `src/ui/`, `src/voice/`, `src/store/`, `index.html`, `sw.js`, `manifest.webmanifest`, `guide/` | `npm run test:browser:required` |
| Anything visible | `npm run screenshots`, then open the changed images, light and dark |
| `android/` | `./gradlew check :app:assembleDebug` in `android/` (`gradlew.bat` on Windows), plus an emulator smoke run once an emulator exists |
| `.github/workflows/` | `npm run test:docs`, and read the workflow run on the PR |

When a check fails, work out whether the assertion or the code is wrong before changing
either. Both have happened here.

## 6. Independent review

Commit, then give the exact diff (`git diff main...HEAD`) to the read-only review agent
`ideaforge-review-claude`, and to `ideaforge-docs-review-claude` when documentation changed.
Fix every confirmed finding, then re-run section 5. Stop after three review-and-fix rounds
that still leave confirmed findings, and escalate.

## 7. Deliver

Validate and push exactly as AGENTS.md requires:

```sh
test -z "$(git status --porcelain)" &&
tested=$(git rev-parse HEAD) &&
npm run test:all &&
test "$(git rev-parse HEAD)" = "$tested" &&
test -z "$(git status --porcelain)" &&
git push -u origin HEAD
```

In PowerShell, use the PowerShell block from AGENTS.md unchanged.

Open the PR. Its body must contain `Closes #42`, a checklist mapping each acceptance
criterion to its evidence, and a summary of the commands run with their results. End it
with the attribution line the session's instructions require.

```sh
gh pr create --base main --title "Short imperative title (#42)" --body-file pr-body.md
gh pr checks --watch --required
```

Write `pr-body.md` in the scratchpad, not the repository.

## 8. Merge gate

The `main review` ruleset requires an approval that an agent cannot give itself. The
maintainer account is a bypass actor, so `--admin` can satisfy that one rule. It must never
be used to get past `ci`. Merge only when **all** of these hold:

```sh
gh pr view 99 --json headRefOid,mergeStateStatus,statusCheckRollup,isDraft
```

- `statusCheckRollup` contains `ci` with conclusion `SUCCESS`, for the commit that equals
  `headRefOid`. Pending, failure, cancelled, skipped or missing all mean no.
- `headRefOid` equals the commit you validated and pushed.
- `mergeStateStatus` is not `BEHIND`. If it is, run `gh pr update-branch 99`, pull the
  result, re-run section 5 on the new head, and wait for `ci` again.
- The PR is not a draft, and no review finding is unresolved.

Then:

```sh
gh pr merge 99 --squash --admin --match-head-commit "$HEAD_SHA" --delete-branch
```

Never use `--no-verify`, never force-push, never push to `main` directly, and never edit a
ruleset or a required check to get a PR through.

## 9. Close out, or escalate

**After a merge:**

```sh
git switch main && git pull --ff-only
gh issue view 42 --json state --jq .state     # CLOSED, via "Closes #42"
gh issue edit 42 --remove-label agent-in-progress
npm test
```

Comment on the issue with the PR link and one line on what shipped. If the merge closed the
last open blocker of another issue, nothing else is needed; the next pick will see it.

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
