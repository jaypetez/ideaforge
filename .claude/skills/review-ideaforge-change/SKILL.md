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
