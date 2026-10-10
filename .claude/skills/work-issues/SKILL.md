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
  [android-verify](../android-verify/SKILL.md) for anything under `android/` or a change
  that regenerates the golden vectors,
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
| `android/` | `./gradlew check :app:assembleDebug` in `android/` (`gradlew.bat` on Windows), plus the emulator smoke run in [android-verify](../android-verify/SKILL.md) when an emulator is available |
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

In PowerShell, use the PowerShell block from AGENTS.md up to and including `git push` and
its exit check, then continue below. Its trailing `gh pr create --fill` would open a PR
without `Closes #42` or the evidence checklist.

Write down the full SHA that `tested` held. Shell state does not survive between tool
calls, so the merge gate needs it as a literal value.

Open the PR. Its body must contain `Closes #42`, a checklist mapping each acceptance
criterion to its evidence, and a summary of the commands run with their results. End it
with the attribution line the session's instructions require.

```sh
gh pr create --base main --title "Short imperative title (#42)" --body-file pr-body.md
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

Run it in the background when the client allows that; a full run is several minutes.

## 8. Merge gate

The maintainer account is an `always` bypass actor on **both** rulesets: `main review`
(the approval an agent cannot give itself) and `main protection` (the required `ci`
check and the up-to-date rule). So `--admin` gets past all of them at once, and the check
below is the only thing standing between a merge and an untested commit. Never weaken it,
split it, or run the merge without it.

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
