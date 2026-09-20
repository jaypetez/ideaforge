# Contributing

Thanks for looking. This is a small project with a couple of deliberate constraints, and
knowing them up front will save you time.

## Getting it running

```sh
git clone https://github.com/jaypetez/ideaforge.git
cd ideaforge
npm test        # no install step — there is nothing to install
npm run serve   # then open http://127.0.0.1:8765
```

Node 22 or newer. There is no build step, no bundler, and no dependencies — not even dev
dependencies. `npm test` runs the purity lint and then Node's built-in test runner.

`file://` will not work: ES modules, IndexedDB and the service worker all need an origin.

## Coding assistants

Claude Code and GitHub Copilot (CLI and VS Code) use the same working rules and README
skill. Open the repository root, not just `src/`, so the clients can discover them.
There is nothing to install into the project to enable this support.

| Client | Repository instructions | Shared skill |
|---|---|---|
| Claude Code | `CLAUDE.md`, which imports `AGENTS.md` | `.claude/skills/update-readme/SKILL.md` |
| Copilot CLI | `.github/copilot-instructions.md`, `AGENTS.md`, and `CLAUDE.md` | The same file |
| Copilot in VS Code | `.github/copilot-instructions.md`; root `AGENTS.md` and `CLAUDE.md` with their instruction support enabled | The same file |

[AGENTS.md](AGENTS.md) owns the working loop, including the required browser checks and
PowerShell equivalents. [CLAUDE.md](CLAUDE.md) owns the implementation invariants.
The Copilot entry point directs the agent to both; it does not maintain a competing
rulebook. A link is also useful for navigation, but is not a guarantee that a client has
automatically loaded the linked file.

The [update-readme skill](.claude/skills/update-readme/SKILL.md) regenerates the real
screenshots and worked example, checks the README against source, and verifies the result.
Invoke `/update-readme` in any of the three clients when updating the README, or let the
agent select it for a task matching its description. It writes documentation artifacts;
it is not a read-only discovery command. The skill uses your client's normal permissions,
without per-skill pre-approval of shell commands.

**Check what was discovered before relying on it.** In a terminal at the repository root:

```sh
copilot instruction list --json
copilot skill list --json
```

The instruction listing should include the three Copilot entry points above. The skill
listing should show one enabled project `update-readme` from this repository's `.claude`
directory, not an unrelated personal or plugin skill with the same name. In an interactive
Copilot CLI session, `/instructions` and `/skills list` inspect the loaded configuration.

In VS Code, open the Chat customization view or its Diagnostics view and inspect the
instruction and skill sources. `/skills` opens the skill configuration menu; confirm
`update-readme` is available before invoking it. In Claude Code, `/memory` shows loaded
instructions and imports; confirm the shared workflow is included and `/update-readme`
appears in the command picker.

If something is missing, check the workspace root and whether customizations have been
disabled, then check the client's own documentation and version. Respect workspace trust
and tool-approval prompts; do not fix discovery by disabling those controls or overwriting
someone's global settings. File checks in `npm test` catch broken references and duplicate
skills, but only the client can prove what it actually loaded.

The shared location is deliberate: both
[Copilot CLI](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference)
and [VS Code](https://code.visualstudio.com/docs/agent-customization/agent-skills) support
`.claude/skills`, which is [Claude Code's native project location](https://code.claude.com/docs/en/skills).
Do not create a second copy, a symlink, or a synchronisation script in `.github/skills`.
For instruction discovery, see the
[VS Code guidance](https://code.visualstudio.com/docs/agent-customization/custom-instructions)
and [Claude's import documentation](https://code.claude.com/docs/en/memory).

This covers local development with the two assistants, not Copilot cloud-agent setup or
an additional model provider in the app. Open a PR for review and leave merging to the
reviewer unless you were explicitly asked to merge.

## The one rule that is enforced mechanically

```
src/core/       pure interview logic — no DOM, no network, no clock, no randomness
src/runtime/    the turn loop — provider and clock are injected
src/providers/  the only place allowed to touch the network
src/store/      IndexedDB and the encrypted key
src/voice/      microphone, transcription, speech
src/ui/         the app shell
```

`tools/lint-purity.mjs` fails the build if anything under `src/core/` or `src/runtime/`
mentions `window`, `document`, `localStorage`, `sessionStorage`, `navigator`, `fetch`,
`alert` or `claude`. It runs as part of `npm test`.

This is not stylistic. It is what lets the interview engine be tested without a browser
and the turn loop be driven without a network, and it is why the whole test suite runs in
under a second with no mocking framework. If you need a platform API, it belongs in one of
the other directories, and the thing that needs it should take it as an argument.

## Adding a provider

Most providers are an OpenAI-shaped `/chat/completions` endpoint, and those are a preset
rather than code. Four files, and the fourth is the one people forget:

1. `src/providers/openaiCompat.js` — an entry in `OPENAI_COMPAT_PRESETS`: a label, a base
   URL, how it authenticates, what its output-budget parameter is called, and either a
   `tiers` map of model ids or `local: true` and a model the user picks.
   `PROVIDER_CHOICES` derives itself from that object, so `src/providers/index.js` needs
   no edit at all.
2. `index.html` — the origin goes in `connect-src`. That list is an allowlist and the app
   talks to nothing outside it. A missing host throws nothing and logs nothing to the
   page: the request is simply blocked, the app carries on as though the network were
   down, and you lose an afternoon.
3. `sw.js` — only if you added a *file*. `SHELL` is hand-maintained, and a module missing
   from it fails on a cold offline start and nowhere else, because any online visit caches
   it anyway.
4. `test/wiring.test.mjs` — nothing to write. It already asserts every preset's origin is
   in the CSP and every file under `src/` is in `SHELL`, so steps 2 and 3 fail the build
   instead of failing on someone's phone. That test exists because `src/version.js` was
   missing from `SHELL` and broke cold offline launches for months without a symptom
   anyone could see.

How a credential is attached is data, not code. `src/providers/http.js` has the descriptor
— `bearer`, a named `header`, a `query` parameter, or `none` — so a provider that signs
requests differently is a preset field rather than a new adapter.

A provider that is not OpenAI-shaped needs its own adapter beside
`src/providers/anthropic.js` and a branch in `createProvider`. Whatever you write exposes
`sample`, `sampleJson`, `listModels` and `validateKey`, and throws `ProviderError` with one
of the codes in `src/providers/errors.js` — the turn loop reads that code to choose between
retrying, falling back to the question bank, and telling the user their key is wrong.
Returning a plain `Error` makes every failure look like the same failure.

Two things to check by hand, because `npm test` cannot see either: that a *bad* key
produces a useful message rather than an opaque `TypeError` — browsers strip CORS headers
from some 401s, so the real error never reaches the page — and that the preflight survives
a real browser. `npm run serve`, paste a key, and name the browser in the PR.

## Where everything else goes

`docs/ARCHITECTURE.md` is the map: what owns the session, what happens when a turn fails,
where to cut in for a new dimension or panel, and which costs this codebase has knowingly
taken on. Worth ten minutes before a first change of any size.

## Things worth knowing before you change them

- **The turn prompt must stay a pure function of session state.** No timestamps, no
  randomness. An interrupted turn is recovered by re-issuing the byte-identical prompt;
  two tests assert this, and if it breaks the failure is silent.
- **Never trust model output.** `parseTurnResult` and `parseSynthesisResult` assume the
  model lies about shape, and every field is optional. Add validation there, not at the
  call site.
- **The coverage ratchet is the product.** Coverage never falls, never rises more than one
  level per turn, caps at two dimensions per turn, and refuses `covered` without a
  verbatim user quote. Loosening it makes the interview stop early and feel useless.
- **Voice answers are a distinct provenance.** `source: 'voice'` exempts an answer from the
  terse and dodge thresholds because dictation rambles. Chip and unedited-draft answers are
  capped at `partial` because they are the model's words, not the user's.
- **Hands-free must never come to rest waiting for a tap.** `src/runtime/drive.js` recovers
  from an empty capture, a recogniser error and a dead recogniser by talking to the user, and
  the one test that matters asserts literally that no recovery mentions tapping or typing.
  A new spoken command is a phrase list in `src/core/driving.js` plus a branch in the loop —
  and it must yield empty answer text, or `RE_REFUSAL` will record the command as a refusal.

## Tests

`node:test` and `node:assert/strict`, flat tests, no `describe`. Every test must run with
no network and no real timers — HTTP is tested with an injected `fetch`, and the silence
detector is clock-injected precisely so it can be tested without a microphone.

Prefer a test that would have caught a real bug over one that restates the implementation.
Several tests here exist because the behaviour they check was wrong the first time; the
comments say which.

## Pull requests

Small and focused is easier to merge. CI runs the suite on Linux, Windows and macOS across
Node 22 and 24 — Windows especially, because two toolchain bugs in this repo's short life
were Windows-only path handling.

Say how you verified it. If it touches voice, storage or a provider, name the browser.

Label the PR before it merges. Release notes are generated from labels — `providers`,
`voice`, `bug`, `enhancement`, `documentation` — and an unlabelled PR lands under
"Everything else" for good. `.github/release.yml` has the full list.

## Cutting a release

The version lives in **two** files, because a browser ES module cannot import JSON without an
import attribute and this project has no build step to inline it. Bump both, in the same
commit:

```sh
# package.json      "version": "0.3.0"
# src/version.js    export const VERSION = '0.3.0';
npm test            # one of the 226 asserts the two agree
```

Then merge, and push a tag matching them:

```sh
git tag v0.3.0 && git push origin v0.3.0
```

The tag is the whole trigger — there is no manual release button, deliberately.
`.github/workflows/release.yml` re-checks that the tag, `package.json` and `src/version.js`
all say the same thing and refuses to publish if they do not, because a release whose archive
reports a different version than its tag is a support problem forever afterwards.

The tag produces three things: a GitHub release with `.tar.gz`, `.zip` and `SHA256SUMS` built
straight from the tagged tree with `git archive`; a container image at
`ghcr.io/jaypetez/ideaforge`, tagged with the version and — unless the tag is a prerelease —
`latest`; and a Pages deploy, which happens on the merge rather than the tag.

**Check the package is publicly pullable after the first release.** Publishing from a public
repository with `GITHUB_TOKEN` made it public here without anyone touching a setting — an
anonymous token against `ghcr.io/v2/jaypetez/ideaforge/manifests/<version>` answered 200 —
but that is a registry default rather than a promise, and if it ever lands private there is
no API to change it. Someone has to open the package settings and switch it by hand. The
check is one command, and it is worth running before announcing a release whose notes tell
people to `docker pull` something they might not be able to read:

```sh
docker manifest inspect ghcr.io/jaypetez/ideaforge:0.3.0   # after: docker logout ghcr.io
```
