---
name: review-ideaforge-change
description: Review a supplied IdeaForge diff for high-confidence defects without edits.
---

# Reviewing an IdeaForge change

This is a read-only review. Do not edit files, create commits, push, merge, tag, publish,
change settings, inspect browser profiles, or make live provider calls. Never request or
expose API keys or stored transcripts.

## 1. Establish the change set

Inspect the exact staged, unstaged, branch, or PR diff named by the caller. If the scope is
ambiguous, report what you reviewed. Read the surrounding implementation, tests, and the
canonical guidance in `AGENTS.md`, `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `SECURITY.md`.
If the exact change set is unavailable through the supplied context and tools, stop and
report that limitation. Never treat the current files as proof that an unseen diff is clean.

Review behavior, not formatting. Report only defects that are actionable and supported by
the code.

## 2. Check the architectural boundaries

- `src/core/` and `src/runtime/` remain free of platform globals, real clocks, randomness,
  and direct network access.
- Prompt construction remains deterministic and the prompt join/hash definitions agree.
- Coverage never falls or leaps, quoted evidence is required for `covered`, and zero gain is
  measured after the ratchet.
- Bank fallback cannot dead-end the interview or alter `zeroGainStreak`.
- `runTurn` results are persisted once rather than through intermediate revisions.

## 3. Check browser and security boundaries

- New source files are in the service-worker shell.
- Provider and transcription origins are present in the exact CSP allowlist without a remote
  wildcard.
- Provider auth uses descriptors, errors use `ProviderError`, and local URLs are parsed as
  loopback rather than prefix-matched.
- API keys are never logged, embedded, sent to a new origin, or weakened by a dependency or
  third-party script.
- Voice and driving changes preserve behavioral probing, terminal triggers, command isolation,
  no-tap recovery, and faithful browser fakes.
- UI/storage/service-worker changes have browser coverage; Node-only green is not enough.

## 4. Check tests and delivery

- Tests would fail on the reported bug rather than restating the implementation.
- No test uses real network or real time where injection is available.
- Browser commands use `test:browser:required` or the graph-inclusive `test:all`.
- Documentation claims and generated artifacts are updated when their source changed.
- Version and release files remain synchronized when a release change is in scope.
- The PR does not bypass the required `ci` check or introduce a dependency/build step.

### Scale, races, test quality and reuse

Apply these checks only to the exact supplied change set and its affected consumers.
[Lean coding](../../../AGENTS.md#lean-coding) owns the complexity ladder, not this review.

- Derive realistic load from the current input/deployment path. A repeated full scan,
  quadratic loop or unbounded buffer is a finding only with a concrete reachable workload
  and a resulting failure, unacceptable latency or resource exhaustion; do not invent a
  future server fleet or demand caching for an unmeasured small collection.
- Trace overlapping work by identity, not just a busy flag. Test the reasoning against a
  delayed request after Pause/Exit, navigation to another idea, a deleted row, or concurrent
  assistant sessions/worktrees. A stale completion must not overwrite another owner's
  state or restart a retired effect.
- Read assertions and fixtures, not just test filenames or counts. Would a flipped
  comparison, dropped guard, malformed input, replay or reordered completion still pass?
  A permissive fake, implementation-shaped assertion or swallowed failure is actionable
  when it conceals a concrete changed contract. A missing test alone is not a defect
  finding; identify the failing behaviour the current validation does not exercise.
- Check existing helpers, reducers, error types and native APIs before recommending more
  machinery. Report duplicated logic only when a demonstrated contract mismatch, lost
  error handling or broken consumer follows from it; "could be shorter" is not a bug.
  A shorter fix must still preserve all requested behaviour and affected callers/tests.
- A `shortcut:` or legacy `ponytail:` comment records a decision, not an exemption. Check
  its stated limit and upgrade trigger against the failing scenario. Do not flag a bound
  merely for being finite, and do not dismiss a current correctness, privacy, security,
  accessibility or data-loss defect because a comment names it.

Running existing read-only checks is allowed when the caller permits it:

```sh
npm run test:all
```

## 5. Report

For each finding, give:

- severity and confidence;
- file and the smallest useful line range;
- the concrete failing scenario;
- why existing validation does not catch it;
- the smallest safe direction for a fix.

Do not list style preferences, speculative risks, or praise. If there are no high-confidence
defects, say so plainly.
