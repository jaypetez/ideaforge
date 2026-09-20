# Architecture

Where a change goes, and what it will cost you.

This does not restate the rules that fail silently when broken — those live in
[CLAUDE.md](../CLAUDE.md) and are linked rather than copied, because a rule written down
twice is a rule that will disagree with itself. [AGENTS.md](../AGENTS.md) covers how to
prove a change works. This file is the map in between.

Four rules keep it from rotting, and they are worth honouring in any edit:

1. **No number source owns.** No line counts, no test counts, no ceilings. "`app.js` is the
   one file with no seams inside it" stays true; "`app.js` is 625 lines" does not.
2. **Cite `file.js` and a symbol name, never a line number.** Line numbers are the
   fastest-rotting citation there is.
3. **No invariant `CLAUDE.md` owns.** Link to it.
4. **The costs section names triggers, not plans.** A plan needs editing whenever
   priorities move; a trigger does not.

`test/docs.test.mjs` asserts every source path named below actually exists. That is the
only mechanical check on this file, and deliberately so — a doc test that polices wording
becomes a tax on every PR and gets deleted.

## The shape in one screen

```
src/core/       pure interview logic — no DOM, no network, no clock, no randomness
src/runtime/    the turn loop — provider and clock are injected
src/providers/  the only directory allowed to touch the network
src/store/      IndexedDB sessions and the encrypted keyring
src/voice/      microphone, transcription, speech synthesis
src/ui/         the app shell — the only place that touches the DOM
```

Dependencies run one way: `ui → runtime → core`, and `ui → providers | store | voice`.
Nothing under `core/` imports anything outside `core/`. That direction is not an agreement,
it is enforced — `tools/lint-purity.mjs` fails the build if `src/core/` or `src/runtime/`
so much as names a platform global, and it runs before the tests do. Its `BANNED` array is
the authoritative copy of that list; the prose copies in `CLAUDE.md` and `CONTRIBUTING.md`
are summaries.

Three things are deliberately absent, and each has a reason that is easy to undo by
accident:

- **No dependencies and no lockfile.** This is what makes the strict CSP affordable and
  keeps a supply chain out of an app holding the user's API key. `.github/workflows/ci.yml`
  has no install step for the same reason: the npm registry is not in this pipeline's
  trust boundary.
- **No build step.** The tree that ships is the tree in git. `assembleSite` in
  `tools/assemble-site.mjs` only copies the publishable files into a directory for browser
  tests and Pages; it does not compile, bundle or rewrite anything. That is what lets
  `.github/workflows/release.yml` produce a byte-stable archive anyone can reproduce.
- **No `CHANGELOG.md`.** Release notes are generated from PR labels via
  `.github/release.yml`, so an unlabelled PR lands under "Everything else" for good.

That split is deliberate in the delivery pipeline too. `serve` in `tools/browser-check.mjs`
mounts the assembled site as the app under test and serves `test/browser/` from the
repository root, so probes stay test-only while the browser sees the same tree Pages will
publish.

The publishing workflows defend against shipping the wrong tree. `.github/workflows/pages.yml`
only runs after a successful `CI` workflow run for a push to `main`, then re-checks that the
tested SHA is still the exact tip of `main` before deploying. `.github/workflows/release.yml`
puts a read-only `verify` job — version triplet, `main` ancestry, full `npm run test:all`
ladder — in front of the write-enabled release job.

## What owns the session

The session is the only mutable state in the app, and exactly one value holds it:
`state.session` in `src/ui/app.js`.

Every legal mutation is a named reducer in `src/core/session.js`. They are pure, they take
the session and return a **new** one, and they bump `rev`. Nothing outside that file
mutates a session, which is what makes the whole interview engine testable with no browser.

Three consequences worth knowing before you touch any of it:

- **Persist once per settled turn.** `runTurn` in `src/runtime/turn.js` bumps `rev` five or
  six times internally as it applies coverage, adds facts and asks the question. Saving
  each intermediate object is wasted writes and a torn session if one fails. `persist()` in
  `src/ui/app.js` is called once, on the object `runTurn` returns.
- **`rev` is a mutation counter, not a mechanism.** It exists so accidental in-place
  mutation is detectable and so a future reconciliation has something to compare. Nothing
  reads it: `saveSession` in `src/store/sessions.js` does an unconditional put, and two
  tabs on one session are last-write-wins with no merge.
- **Schema migration happens on read, not on upgrade.** `migrate` in `src/core/session.js`
  is applied by `src/store/sessions.js` as rows come back, and it **refuses** a session
  whose `schema` is newer than this build rather than guessing at it. The credential
  keyring in `src/store/secrets.js` follows the same pattern for the same reason.

## When a turn fails

The happy path is in [CLAUDE.md](../CLAUDE.md). This is the other one, and it is the
property most worth not breaking: **a failure never dead-ends an interview.**

| What went wrong | Where it is handled | What the user sees |
|---|---|---|
| The provider threw | `bankFallback` in `src/runtime/turn.js` | A question from the built-in bank; the turnline says so |
| The model returned unparseable JSON | One repair directive, then a regeneration, in `runTurn` | Nothing |
| The question tripped a tripwire | `questionTripwire` in `src/core/engine.js`; one regeneration, then the bank | Nothing |
| There is no provider at all | `runTurn`'s null-provider branch | Checklist mode, no calls made |
| A call was interrupted | `resumeTurn` in `src/runtime/turn.js` | The interview picks up where it stopped |
| The wrap-up call failed | `runSynthesis` in `src/runtime/synthesize.js`, then `buildExport` in `src/core/markdown.js` | The whole document, minus the refined prompt |
| Nothing usable was heard, hands-free | `createDriveLoop` in `src/runtime/drive.js` | It says so, listens again, and eventually moves on |

Two subtleties inside that table. In `resumeTurn`, the turn id is the *correctness*
guarantee — it is what stops the same turn being created twice — while the prompt hash is
only a cache optimisation, and a miss is routine rather than a bug. And a bank fallback
deliberately leaves `zeroGainStreak` alone in both directions: incrementing it would let
two network blips offer the wrap-up exit for a reason that never happened.

Errors reaching the UI are always a `ProviderError` carrying one of the codes in
`src/providers/errors.js`. Nothing above that directory parses an HTTP status or an error
string, which is what lets the UI stay ignorant of which backend it is talking to.

## Extension seams

**A provider.** See the walkthrough in [CONTRIBUTING.md](../CONTRIBUTING.md) — it is the
one place that list lives, because it spans four files and forgetting the fourth fails
invisibly.

**A dimension.** Append to `DIMENSIONS` in `src/core/dimensions.js`; coverage init,
selection, the prompt's coverage block, the synthesis section list and the export table all
derive from it. Then three things that do not: `legalMoves` in `src/core/engine.js` has a
per-dimension `switch` a new entry falls through to a sane default of, `docs/hero.svg`
hardcodes the seven labels, and `test/docs.test.mjs` will fail on the README still saying
"seven dimensions" — which is the system working.

**A UI panel.** A `<section>` in `index.html`, its ids added to the `els` list in
`src/ui/app.js`, and the panel id added to `show()`. Three sites, and nothing enforces that
you hit all three.

**A dictation backend.** `STT_PRESETS` in `src/voice/transcribe.js`, plus an `<option>` in
`index.html` — the select is not data-driven. Read the voice section of
[CLAUDE.md](../CLAUDE.md) first: this is the subsystem where feature detection lies.

**A spoken command.** A phrase list in `COMMANDS` in `src/core/driving.js`, and a branch in
`createDriveLoop` in `src/runtime/drive.js`. Nothing else: the loop's `io` is what turns the
branch into an effect, so a new command is testable in `node --test` before the UI knows it
exists. The split mirrors `core/synthesis.js` ↔ `runtime/synthesize.js` — pure material in
`core/`, the orchestration that consumes it in `runtime/`.

## The costs we have accepted

Each of these is a deliberate debt with a trigger for paying it down, not an oversight.

**`src/ui/app.js` has no seams inside it.** One god `state` object, a flat map of element
ids, and `show()` enumerating the panels by name. It is well sectioned and holds no
business logic — every classification, coverage and wrap decision lives in `core/`, and the
hands-free sequencing now lives in `src/runtime/drive.js` — but it is still the one file two
people cannot comfortably work in at once.
*Trigger: a fourth panel, or a second contributor working in it concurrently.*

**One interview profile is assumed throughout.** `DIMENSIONS`, `SEED_QUESTION` and the two
prompt rule blocks in `src/core/engine.js` and `src/core/synthesis.js` are module-level
singletons reached directly by most of `core/`. A second interview type means threading a
profile object through `createSession`, `buildTurnPromptParts` and
`buildSynthesisPromptParts`. The dependency-injection discipline already in place makes it
mechanical rather than a rewrite, but it touches nearly everything.
*Trigger: anyone asks for a second interview type. This is the highest-leverage next
investment.*

**No multi-tab reconciliation.** Two tabs on one session, last write wins, no merge. The
cheap version — read before write, keep the higher `rev` — is worse than nothing, because
silently returning a different session while the UI still holds the old one loses work more
confusingly than overwriting does. A correct version needs a conflict policy and a story
for "another tab moved this".
*Trigger: someone reports losing work.*

**Three hand-maintained registries** — the CSP `connect-src` in `index.html`, the `SHELL`
list in `sw.js`, and the provider presets — with no single source of truth, all of which
fail invisibly. `test/wiring.test.mjs` now asserts the first two stay in step with the
third, which is how a missing `src/version.js` in `SHELL` was found: it broke cold offline
launches and nothing else, so nothing caught it.
*Trigger: a fourth registry appears, or the test starts needing exceptions.*
