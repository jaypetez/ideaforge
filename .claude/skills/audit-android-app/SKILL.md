---
name: audit-android-app
description: Audit Android main for concrete bugs using source, tests and available device evidence without fixing or publishing findings.
disable-model-invocation: true
---

# Auditing the Android app

Invoke `/audit-android-app` explicitly. The default is **main-only, report-only**, including
source review, existing tests and available device evidence. A caller can explicitly narrow
or change the audit scope; do not silently substitute an unfinished branch for shipped code.
This is not backlog execution, implementation, release work or an automatic Android gate.

Reuse sections 1-3 of
[`audit-ideaforge-codebase`](../audit-ideaforge-codebase/SKILL.md) for map-first orientation,
flow tracing and the
[shared defect criteria](../review-ideaforge-change/SKILL.md#scale-races-test-quality-and-reuse).
They are references, not permission to invoke an exact-diff review without a diff.
Use [`android-verify`](../android-verify/SKILL.md) for native commands, prerequisites,
Windows seams, device ownership and artifact acceptance. This entry adds Android coverage
and reporting, not another runner or rulebook.

## 1. Pin the scope without changing it

Read [AGENTS.md](../../../AGENTS.md), [CLAUDE.md](../../../CLAUDE.md#android-invariants),
the [architecture map](../../../docs/ARCHITECTURE.md) and
[native contributor guidance](../../../CONTRIBUTING.md#the-android-app).
Record the actual audited revision (`main` by default) and working-tree context. Preserve unrelated edits;
do not pull, switch, stash, reset or repair a checkout implicitly. If the requested source
cannot be inspected without mixing revisions, stop and disclose the limit.

Supplement the bounded map with a tracked Android inventory: omitted paths are not empty
areas, and inventory entries are not files actually read. Include first-party production
code, resources/manifests, debug/release variants, Gradle and relevant test/CI/package owners.
Follow JS/vector/backup owners where they define native behavior, not into unrelated web
redesign. Read existing issues only for deduplication, not as proof of a defect.

Do not edit application code, tracked tests/fixtures/vectors or unfinished drafts; create
issues/comments/labels; commit, push or merge; publish; inspect real stores, transcripts,
credentials or production signing; or call live/paid providers. Keep reports, synthetic
inputs and diagnostic artifacts in the session workspace. No new dependency, evidence
runner, agent, plugin, persistent audit ledger or tool preapproval is part of this audit.

## 2. Trace the native coverage matrix

| Area | Follow the real owners and failure paths |
|---|---|
| Entry and lifecycle | Activity, graph, navigation and onboarding; fresh/saved setup, rotation, entry identity, stale admission and permission results. |
| Interview | Typed/chip answers, turns, parsing, ratchet, fallback, wrap, synthesis and interrupted recovery; durable intent and settled persistence. |
| Library and storage | Session worker/store, atomic writes, drafts, metadata/filter/archive/deletion races, corruption and newer schemas; never turn read failure into empty success. |
| Credentials and consent | Independent keyring branches, Forget generations, destination-bound permits, preview cancellation during persistence, failed withdrawal and explicit operation-local errors. |
| Voice and background | Manual dictation versus hands-free, recognizer/output/Whisper and service ownership; serial effects, Pause/Resume/Exit/deletion, draft preservation and late callbacks. |
| Documents and reports | Export bytes/provenance, FileProvider/intent limits, share/save cancellation, backup round-trips/bounds/collisions and report snapshots; no keys in backups. |
| Providers | Actual adapter/check paths, malformed successful responses, retries/cancellation, auth/DNS/redirect policy and intentional native/private-network differences. |
| Access and packaging | Compact/expanded/short windows, IME, long text, both themes/enlarged fonts, actual targets/labels; debug-only exclusion, release packages and existing disposable-signing controls. |

Inspect affected callers and actual assertions/fixtures before classifying a candidate.
Exercise overlapping operations and failure recovery, not just normal screen states.
An intentional native policy, an unimplemented feature or a missing test alone is not a
new defect. Consolidate manifestations sharing one root cause.

## 3. Establish meaningful execution evidence

Follow the cheapest applicable rungs in `android-verify`, including shared vector/backup
checks and the whole Android gate. Node tests do not build Android. Preserve warm Gradle
state and record cached/up-to-date/skipped results rather than claiming fresh execution.
A valid completed check is not rerun merely after compaction or while waiting.

Use existing injection seams and focused selectors for counterexamples. Session-only
reproducers may use the existing runner, but do not plant faults in tracked sources or
introduce another framework. If stronger proof needs unauthorized changes, mark the
candidate unconfirmed instead. Derive thresholds from the owning constants; establish that
the fixture reaches the real guard, provider, writer or callback under investigation.
An unused fake, zero discovery, wrong source set or fixture-setup assertion is not evidence
for the intended behavior. Separate deliberate diagnostic failures from baseline failures.

Read back existing same-SHA native evidence first. Fill material gaps only through existing
permitted routes, checking competing work before dispatch and keeping device jobs
sequential. Preserve failures and diagnose them before any retry; no unchanged retry
without a specific transient-infrastructure explanation.

Use the existing selected-test guard with the route's actual method and profile XML.
Verify run/attempt, tested SHA/clean state, exact expected capture identities, actual
font/window/API metadata, per-capture geometry/files and downloaded artifact hashes.
A successful workflow, fixture count or generated sheet is not visual acceptance.
Inspect actual app/system PNGs and semantics, including the canonical journey and
error/IME/short-window/reachability states. Count only pixels actually rendered and read.
Faithful labelled contact sheets supplement selected originals; state their resolution
and provenance. A diagnostic current-UI image is not the requested passing state, and
remaining scroll/timing must be assessed before calling an action unreachable.

Keep scripted effects, real system interactions and physical evidence distinct. Do not
infer microphone, TalkBack, live-provider, share delivery, all-API or installed minified
runtime behavior from fixture paint. ZIP alignment is not ELF/physical 16-KB certification.
Unavailable tools, image limits and unperformed checks remain explicit coverage gaps,
not reasons to weaken acceptance or acquire production keys/Play access.

## 4. Report every confirmed root and stop

For this Android specialization, report **all confirmed, deduplicated root causes without
a finding cap**, ranked by severity and continuously numbered. This replaces only the
general audit's report cap for this entry; its existing 20-finding cap stays unchanged.
Use its finding fields: confidence, useful file/line range, concrete event/input sequence,
expected/actual behavior, impact, actual evidence, test gap and smallest safe fix direction.

Distinguish runtime-reproduced findings, source-confirmed reasoning, known duplicates,
unconfirmed candidates and harness/setup problems. Mark debug-only defects explicitly.
Include the inspected/read inventory, exact commands/results and SHA, actual selected
execution/artifact/image provenance and not-read/not-run boundaries. Label partial
coverage as partial; if nothing qualifies, say so only for the inspected scope.
Do not implement fixes or publish/file findings automatically. Any separately requested
changes must follow the normal review, validation and PR gates.
