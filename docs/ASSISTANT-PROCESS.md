# Assistant process

Ponytail-inspired developer assistance for Claude Code and GitHub Copilot CLI. It helps
choose a complete, smaller implementation; it is not an application feature, an in-app
Copilot provider, or permission to change what the user asked for.

## Ownership and boundaries

[Lean coding](../AGENTS.md#lean-coding) is the sole owner of the scope-first ladder and
the Lite, Full, Ultra and Off meanings. [CLAUDE.md](../CLAUDE.md) continues to own
implementation invariants; [AGENTS.md](../AGENTS.md) owns validation and delivery.
This reference describes controls, integration and evidence, not a competing rulebook.

All shared procedures have one definition under `.claude/skills/`:

| Procedure | Purpose |
|---|---|
| [`ideaforge-assist`](../.claude/skills/ideaforge-assist/SKILL.md) | Explicit mode/default/reset control |
| [`audit-ideaforge-codebase`](../.claude/skills/audit-ideaforge-codebase/SKILL.md) | Explicit, read-only map-first audit |
| [`ideaforge-debt`](../.claude/skills/ideaforge-debt/SKILL.md) | Shortcut decisions and upgrade triggers |
| [`ideaforge-gain`](../.claude/skills/ideaforge-gain/SKILL.md) | Reproducible local comparison facts |
| [`ideaforge-assist-help`](../.claude/skills/ideaforge-assist-help/SKILL.md) | Read-only one-shot help body |
| [`review-ideaforge-change`](../.claude/skills/review-ideaforge-change/SKILL.md) | Existing exact-diff defect review, and challenge of supplied findings |
| [`review-ideaforge-before-pr`](../.claude/skills/review-ideaforge-before-pr/SKILL.md) | Adversarial multi-agent review recorded per HEAD before any PR |

Implementation owners are separate from the shared procedures:

| Owner | Responsibility |
|---|---|
| [`assistant-process.mjs`](../tools/assistant-process.mjs) | State, exact controls, map/debt/gain, canonical-rule rendering and guidance lifecycle |
| [`assistant.mjs`](../tools/assistant.mjs) | CLI and bounded stdin parsing |
| [`assistant-review.mjs`](../tools/assistant-review.mjs) | Pre-PR review diff and record, and the `gh pr create` gate decision |
| [Claude project settings](../.claude/settings.json) | `CLAUDE_PROJECT_DIR`-guarded inline bootstrap before imports |
| [`assistant-hook.mjs`](../tools/assistant-hook.mjs) | Claude adapter and shared `copilotHook` command-hook output contracts |
| [Copilot command hooks](../.github/hooks/ideaforge-assist.json) | Basic startup/child context and persisted prompt controls, without requiring an extension, and the refuse-only `preToolUse` pre-PR review gate |
| [`assistant-copilot.mjs`](../tools/assistant-copilot.mjs) | Optional SDK prompt/pre-tool callbacks and compaction event; no startup/child callbacks |
| [Copilot extension entry](../.github/extensions/ideaforge-assist/extension.mjs) | Joins a supported foreground session when the native extension host loads it |

There are no copied skills in `.github/skills`, symlinks or client-specific rule copies.
The two explicitly invoked control/audit skills declare `disable-model-invocation: true`;
no shared skill pre-approves tools or chooses a model. Client trust, policy and approvals
still apply. One-shot review, audit, debt, gain and help do not select or latch a mode.
Their read-only bodies are separate from
[automatic session initialisation](#session-initialisation-and-one-shot-bodies).

The helper and adapters are repository-development utilities, outside `src/`, Android,
the service-worker shell and the publishable tree owned by
[`assemble-site.mjs`](../tools/assemble-site.mjs). They add no web dependency or build step.
They do not send network requests, collect telemetry, retain prompts or transcripts,
read app credentials, or make default paid model calls. The one retained source copy is
the [pre-PR review](#pre-pr-review-gate) diff, kept locally beside its record. Existing client/model operation
is separate from this local utility.

## Controls

Supported native adapters select balanced Full automatically after normal workspace trust
and adapter loading, unless the configured default says otherwise. "Automatic" describes
guidance context, not permission to start audits, install tooling, implement extra scope,
delegate work, merge or release.

Use one of these as the entire user message:

| Message | Effect |
|---|---|
| `/ideaforge-assist` | Report the current mode; if Off, select the configured default |
| `/ideaforge-assist lite` | Select Lite for this assistant session |
| `/ideaforge-assist full` | Select Full for this assistant session |
| `/ideaforge-assist ultra` | Select Ultra for this assistant session |
| `/ideaforge-assist off` | Disable the additional guidance for this session |
| `/ideaforge-assist default LEVEL` | Explicitly set the repository-local default |
| `/ideaforge-assist reset` | Remove only this session's owned mode record |
| `normal mode` or `stop ideaforge assist` | Deactivate when used as a whole message |

`LEVEL` is `lite`, `full`, `ultra` or `off`. Quoted examples, code blocks, a longer sentence,
source text and requests to implement a "normal mode" toggle are not controls. Unknown
arguments are errors, not a fifth mode. The native adapters process recognised controls
before the model; a skill must not apply the same reset or change a second time.
These effects describe the stored mode. Context refresh depends on the client path:
Copilot command hooks persist prompt controls but cannot refresh the model in that turn.
See [Copilot without SDK refresh](#copilot-without-sdk-refresh) for the explicit fallback.

When a native adapter is unavailable, the dependency-free
[`tools/assistant.mjs`](../tools/assistant.mjs) exposes the same state operations.
Run from the repository/worktree root. The following are PowerShell command forms;
replace `CLIENT` with `claude` or `copilot` and `SESSION_ID` with the actual id supplied
by that client, not an interview/session id from the app:

```powershell
node tools\assistant.mjs status --client CLIENT --session SESSION_ID
node tools\assistant.mjs mode LEVEL --client CLIENT --session SESSION_ID
node tools\assistant.mjs default LEVEL
node tools\assistant.mjs reset --client CLIENT --session SESSION_ID
```

Status is read-only. Mode and reset require explicit identity. Never guess an id or use a
shared last-writer flag. If identity is unavailable, show that limitation. A CLI state
change by itself is not proof that a running model consumed refreshed context.
Off removes the extra guidance, not root instructions, baseline permissions, required
tests, privacy/accessibility, complete requested scope, or PR/merge/release controls.

## State, defaults and cleanup

State and local configuration live only in the ignored `.ideaforge-assist/` directory;
the local configuration file is `.ideaforge-assist/config.json`.
Session keys are hashed from the trusted repository/worktree root, client and session id;
one worktree, client or concurrent session must not borrow another's mode. Raw untrusted
identifiers do not become paths. Mode records contain bounded schema/mode metadata,
not prompts, source copies, transcripts, secrets or environment dumps.

Pre-PR review files are the exception. `review-<head>.diff` is the full branch diff, a
copy of source, and `review-<head>.json` is its record. Both are keyed by worktree and
commit only, so every client and session in that worktree shares one result for a HEAD,
and they are never pruned automatically; delete them whenever you like, at the cost of
reviewing that HEAD again.

For a new session, the default resolves in this order:

1. `IDEAFORGE_ASSIST_DEFAULT_MODE` in the environment;
2. repository-local configuration's `defaultMode`;
3. `full`.

An explicit session mode survives valid resume for that identity. Changing the local default
does not relabel existing session records, and an environment override still takes
precedence for new starts. Invalid/corrupt/unreadable configuration is an error to disclose,
not permission to claim Full or a successful write.
For a session-aware default control after reset, the helper pins the current effective
mode if its record is missing before writing the new default, preserving that active level.

Local configuration also provides bounded integer `mapChars` and boolean `mapEnabled`,
`quietStartup` and `hideStatus` controls. Read their accepted values, defaults and bounds
from `readConfig` in the [shared implementation](../tools/assistant-process.mjs).
`mapChars` accepts 256 through 4000 characters and defaults to 2000; `mapEnabled` defaults
to true, while `quietStartup` and `hideStatus` default to false. These configure map
context, the SDK supplement's first-prompt notice and status text respectively, not scope
or permission gates. Command adapters do not display that notice; `quietStartup` does
not remove their startup context. SDK reload can produce a new first-prompt notice;
ordinary later prompts and compaction restoration do not repeat it.
Do not infer that hiding a banner or badge disables the mode, or that enlarging a map
makes it complete.
Default changes preserve unrelated configuration fields and never write personal/global
assistant settings.

Use Off to stand down guidance for a session. Use the explicit reset to remove only that
session's owned record; the default and unrelated files remain. A subsequent lifecycle
initialisation uses the configured default. Reset is not a blanket deletion of
`.ideaforge-assist/`, another worktree's data, native client settings or existing statusline.
No automatic uninstall or global cleanup is performed.

### Session initialisation and one-shot bodies

Native startup may seed a missing session record with the configured default.
The SDK supplement's first prompt can do the same after late attachment, including when
the request is a one-shot help, audit, debt, gain or review. That is automatic lifecycle
initialisation of ignored mode metadata, not the body selecting a mode or gaining write
permissions. Existing session modes are preserved.

One-shot bodies must not call mode/default/reset, write configuration or change state.
The CLI map/debt/gain/status operations remain read-only; a whole native session is not
promised to leave the metadata directory byte-identical merely because its body is read-only.
Explicitly requested debt-ledger persistence remains the separate opt-in exception.

## Native clients and status

Discovery and lifecycle context are different capabilities. See the
[contributor setup](../CONTRIBUTING.md#coding-assistants) for normal client discovery.
A file existing, a skill appearing in a list, and a model receiving a new mode are not
interchangeable evidence.

The implemented paths have different contracts; this is not a blanket native-test pass:

| Client path | Basic guidance | Refresh boundary |
|---|---|---|
| Claude project hooks | Startup and supported child context; explicit prompt controls | Startup execution/output observed; remaining lifecycle cases need their own evidence |
| Copilot command hooks | Startup and supported child `{additionalContext}` in foreground/non-interactive sessions | Prompt output is `{}` and dropped; state changes alone do not refresh same-turn context |
| Copilot SDK supplement, when loaded | Prompt/pre-tool context only; first prompt handles late attachment | Native project loading and first-prompt LITE consumption observed; compaction restoration has protocol coverage, no startup/child callbacks |

### Claude Code

The project adapter uses [`.claude/settings.json`](../.claude/settings.json) lifecycle
hooks and [`assistant-hook.mjs`](../tools/assistant-hook.mjs). Startup, resume, clear,
compact/fork and child context must use the correct event contracts: session/prompt
context is not the same output envelope as `SubagentStart`'s
`hookSpecificOutput.additionalContext`.
Output is bounded; ordinary logs must not pollute the context protocol.

The lifecycle hooks are context hooks, not tool-decision or permission hooks. They must
not install global configuration, choose a model or alter a user's request. The one
exception is the [pre-PR review gate](#pre-pr-review-gate), a `PreToolUse` hook that can
only refuse.
[Native project discovery and startup-hook execution/output](#verified-native-discovery)
are verified. Model adherence and the remaining lifecycle cases are
[separate pending checks](#pending-native-consumption).

### Copilot CLI

Copilot also reads `.claude/settings.json` as a combined `reposettings` source.
Its authoritative command handlers remain
[`.github/hooks/ideaforge-assist.json`](../.github/hooks/ideaforge-assist.json):
the Claude inline bootstrap checks `CLAUDE_PROJECT_DIR` before importing anything, and
`claudeHook` returns without action for timestamped Copilot-compatibility payloads.
The documented Copilot payload has `timestamp`; the Claude payload does not. These
compatibility-loaded Claude actions are no-ops, avoiding Claude-client state or a second
persona under Copilot rather than treating that source as another active adapter.

The basic integration is
[`.github/hooks/ideaforge-assist.json`](../.github/hooks/ideaforge-assist.json), using
`copilotHook` in [`assistant-hook.mjs`](../tools/assistant-hook.mjs).
`sessionStart` and supported `subagentStart` events return `{additionalContext}`.
`userPromptSubmitted` processes the exact control and returns `{}` intentionally:
command-hook prompt output is dropped by the native contract. This provides basic
startup Full and supported child guidance in foreground/non-interactive sessions even
when the SDK extension is not loaded, without granting permissions.

The optional SDK supplement's project entry is
[`.github/extensions/ideaforge-assist/extension.mjs`](../.github/extensions/ideaforge-assist/extension.mjs).
The native host supplies `@github/copilot-sdk/extension`; it is not an npm/app dependency.
When loaded in a supported foreground host, the thin adapter joins the existing session
and registers only `onUserPromptSubmitted` and `onPreToolUse`, plus the
`session.compaction_complete` event. Native command hooks own startup and supported child
context; the SDK supplement deliberately registers no startup/child callbacks to avoid
double context. Its first prompt restores guidance after late attachment, with the
[initialisation boundary](#session-initialisation-and-one-shot-bodies) above.
It does not rewrite prompts, tool arguments/results, models, permissions or execution mode,
send another model turn, or request sensitive environment variables.
The foreground client may separately ask to let `project:ideaforge-assist` register
hooks after workspace trust. That approval is not a tool/path/URL allowance or secret
request. Inspect the reviewed entry and shared handlers before allowing it; a
session-only choice is sufficient, and this setup does not require an always-allow
personal setting. Native project loading and mode-specific first-prompt consumption
were observed; see [verified native discovery](#verified-native-discovery) and the
[remaining coverage boundaries](#pending-native-consumption).

Command/HTTP `userPromptSubmitted` hook stdout is dropped by Copilot CLI. Writing a state
file through that route alone cannot refresh guidance. The SDK's
`onUserPromptSubmitted.additionalContext` can close that gap only when the supplement
is actually loaded and its callback runs.
In that host, [`assistant-copilot.mjs`](../tools/assistant-copilot.mjs) observes
`session.compaction_complete` and restores context at the next supported prompt or
pre-tool hook, without a background loop or `session.send`. The SDK pre-tool hook returns
passive context only; it does not approve/deny tools or change their arguments. The
command-hook `preToolUse` is the separate [pre-PR review gate](#pre-pr-review-gate).

The programmatic SDK server has no TUI/foreground extension host. Its `extensions.list`
result is not evidence that a project extension did or did not load in the native TUI.
Native logs showing a project session or SDK-hook registration are useful registration
evidence, not proof that this extension executed or refreshed context.

### Copilot without SDK refresh

A stored mode or badge can change while the model still has older context. If the SDK
supplement is not loaded, explicitly invoke the
[control skill](../.claude/skills/ideaforge-assist/SKILL.md) for a manual mode/helper refresh:
read the actual session status, then apply its selected canonical mode to the requested
work. Do not repeat a state change already handled by the command hook.
Running CLI `mode` or `status` alone does not inject refreshed context.

A subsequent supported `sessionStart` for the same identity emits the stored mode.
A new session instead starts at the configured default. Command hooks alone do not
promise automatic post-compaction restoration; request a manual refresh when needed.
These are explicit fallback procedures, not native runtime or model-adherence proof.
A manual refresh still requires the current client's normal trust and approved tools.
It is not a way around blocked workspace execution; if trust or real session identity
is unavailable, report that boundary rather than fabricating state or granting permission.

### Pre-PR review gate

[`review-ideaforge-before-pr`](../.claude/skills/review-ideaforge-before-pr/SKILL.md)
prepares the exact branch diff as a file the read-only review agents can read, runs two
change reviewers blind (plus a documentation reviewer when documentation changed) and a
fresh reviewer to challenge every candidate, and records a passing result
for the exact HEAD in `.ideaforge-assist/`. The record is per commit; any later commit,
amend or rebase needs another round. A waiver is recorded only on the user's explicit
request, with their reason.

Claude Code's `PreToolUse` hook (matcher `Bash|PowerShell`) and Copilot CLI's
`preToolUse` command hook pass the command to `prGateDecision` in
[`assistant-review.mjs`](../tools/assistant-review.mjs). It does not parse shell:
three review rounds kept finding quoting forms a parser missed. It joins line
continuations, drops quote and backtick characters, reads backslashes as path
separators, then looks for `gh pr create` or `gh pr new` (allowing a few flags such as
`-R owner/repo` before the subcommand) in command position: at the start of a line,
after `;`, `&`, `|`, a bracket or `$(`, optionally behind a few prefixes: an assignment (`A=b`, or PowerShell's
`$x =` and `[string]$x =`), a control keyword such as `if` or `while`, or one of the wrappers
`exec`, `command`, `time`, `env`, `nohup`, `xargs`, `timeout`, `nice`, `sudo` and
`doas` with up to three flag or number arguments. A mention mid-sentence is not in command position and passes. The matcher is
linear in the command's length, since it runs on every shell command.

For a match, because the hook sees the checkout before the command runs, it first
refuses, in any checkout and whatever the review state, a PR command whose text changes
directory, commits, rebases, pulls, merges, switches or resets, or contains `--head` or
`-H` anywhere after the PR command, even naming the current branch. Run `gh` in its
own command from the reviewed branch. Only then does it look at the review: in a checkout
that does not carry the review tool (another repository, or a branch from before the gate
existed) it abstains, since nothing could be recorded there; otherwise it refuses unless
the tree is clean and HEAD has a `clean` or `waived` record, and a record it cannot
read also refuses.

The cost of the blunt rule is that quoted text is read as commands too. A quoted `;`,
`|`, `&`, bracket or line break before the PR command puts it in command position, so
a search pattern such as `"gh pr view|gh pr create"` or a message such as
`"Gate (gh pr create)"` is refused while HEAD is unreviewed. Inline title, body or
message text that starts a line or clause with `cd` or a HEAD-moving `git` command, or
contains `--head` or `-H`, refuses the PR even after a clean review. Pass PR bodies and
commit messages from a file (`--body-file`, `-F`) or use `--fill`, and put scripts
that mention these commands in a file rather than inline.

Recording names the reviewed commit, and refuses when HEAD has moved since, so one
session cannot record another session's unreviewed commit. For any other command it gives no
decision at all, so the client's normal permission flow still decides; it never approves,
asks, or changes arguments. Copilot denies a tool whose `preToolUse` command exits
non-zero, so every failure in that adapter, including locating the repository, is
reported on stderr and answered with `{}` rather than blocking every tool. Hook input that
cannot be read is likewise left to the normal flow.

This is a workflow guard, not a security boundary: the same model can run the record
command, and `gh api`, a quoted path to `gh` that contains spaces, a command handed to
another interpreter as a string (`bash -c "…"`), PowerShell splatting (`gh @args`),
legacy backtick substitution, any other wrapper word, a wrapper with more than three
arguments or with arguments that are not flags or numbers, more than six prefixes before
`gh`, or a person's own terminal are not covered.

### Child inheritance limits

The built-in `general-purpose` dispatch does not emit the normal child lifecycle hooks.
For that dispatch, an explicit line in the delegating prompt must carry the actual parent
mode and canonical owner, for example:

```text
Parent guidance mode is full; follow AGENTS.md#lean-coding within this task's scope
and your existing tools/permissions. This does not authorise mode/state changes.
```

Replace `full` with the actual parent's level, including Off. This is context, not a new
task or authority to broaden work. Restricted review agents retain their supplied exact
diff, no-shell/no-edit tools and passive review-only guidance. A fallback prompt does not
prove automatic inheritance for an unsupported dispatch.

### Optional statusline

Native custom status commands accept JSON on stdin and return display text on stdout.
The local Node status command uses the explicit client, its trusted checkout root and
the real session id read from that payload. It requires an absolute working directory
checked by `trustedWorkingDirectory`: canonical realpaths must remain within that root,
and an intervening `.git` boundary rejects a nested repository or different worktree.
Scalar path containment alone is not the trust gate. Missing identity, malformed input
or a rejected directory is an error, never permission to infer another session's most
recent mode. Off must remain truthful too. It is a read-only display, not a state initialiser.

The CLI accepts session identity from `sessionId`, `session_id` or `session.id`, and
working directory from `cwd`, `workingDirectory` or `workspace.current_dir`. Those are
the helper's supported input fields, not proof that every native client/version supplies
each variant. Failures are reported on stderr with a non-zero exit, not a healthy badge.

Statusline setup is opt-in. Inspect the helper and the current client's supported setup
before choosing a command. Use one Node invocation with a quoted absolute helper path,
preserve any existing combined statusline, and never overwrite personal/global config as
part of activation. Copilot runs its command under `cmd.exe` on Windows and `sh` on Unix;
a PowerShell-only expression is not a portable Windows statusline command. Configure only
through the client's explicit user-approved setup and supported scope; no automatic copy
into a home directory or change to the current user's configuration is required.

The native status command form is:

```text
node "ABSOLUTE_WORKTREE_PATH\tools\assistant.mjs" status --client copilot
```

Replace the placeholder with the actual trusted worktree path; use `--client claude` for
Claude. Unlike a manual CLI call, this form deliberately omits `--session`: the native
client supplies its real id and working directory on stdin. Do not invoke it with a
fabricated payload or configure a path into a different worktree. The helper is rooted in
its own checkout; matching the active client's root and observing the real payload remain
native validation requirements, not something a fixture or command listing proves.

## Map, audit, debt and gain

These are local, bounded, one-shot surfaces. They do not alter modes or replace the
owning source and validation.

### Bounded map

```powershell
node tools\assistant.mjs map
```

The map is a tracked-only heuristic orientation, within explicit file/character budgets.
It excludes private settings, credentials, signing material, generated/dependency outputs,
nested worktrees and external research snapshots, and must not follow a link outside the
root. Treat omissions/truncation as a coverage boundary, not an empty directory.

Symbol extraction is heuristic, not an LSP, type analysis or call graph. Check owners in
[ARCHITECTURE.md](ARCHITECTURE.md), then inspect the actual caller/consumer and tests.
A symbol appearing in a map does not mean it has been read or is safe to reuse unchanged.

### Audit and exact-diff review

[`audit-ideaforge-codebase`](../.claude/skills/audit-ideaforge-codebase/SKILL.md) starts with
an explicitly requested repository/path scope and map, traces main flows, derives realistic
load, and reports at most 20 high-confidence concrete findings with coverage and not-read
boundaries. It never implements findings automatically.

It references the [shared defect criteria](../.claude/skills/review-ideaforge-change/SKILL.md#scale-races-test-quality-and-reuse),
not another copy. The existing diff review remains an exact supplied-change-set procedure;
current files are not proof that an unavailable diff is clean. Its restricted native
adapters cannot obtain a missing diff by running shell commands.

Ordinary audits can identify concrete trust-boundary problems, but do not claim all
vulnerabilities were found. A dedicated security-vulnerability request uses the current
client's dedicated security-review workflow and permissions, not an improvised broad audit.

### Shortcut debt

```powershell
node tools\assistant.mjs debt
```

Comment markers use `shortcut: <decision>; limit: <bound>; upgrade: <trigger>`.
The legacy `ponytail:` marker is recognised too. The report gives file/line, decision, limit
and trigger, and marks missing fields rather than inventing them. It uses a bounded,
format-limited lexer, not a full parser or universal authored-comment detector.
Verify candidate source context read-only before attributing a decision; literal text
and documentation examples are not shortcuts. Generated/private/excluded files are not
scanned to inflate coverage.

Eligible `.yaml`/`.yml` files are explicitly not inspected: block-scalar data can contain
`// shortcut:` without being a comment. The reported reason is
`YAML literal contexts are not supported by the debt lexer`.
The CLI prints each `Not inspected: FILE: REASON` entry, including missing/deleted tracked
source, and reports unread and beyond-budget counts. Preserve these boundaries instead
of interpreting an unsupported or unread file as having no markers.

An illustrative comment, not a shipped library annotation:

```js
// shortcut: filter the loaded library directly; limit: all rows fit in memory; upgrade: measured filtering misses the agreed input-latency budget
```

This demonstrates an inspected JavaScript-style comment form, not text in a YAML literal.
Regexp escapes/classes, including quotes in `/["']/`, are handled so they do not hide a
following comment; that regression coverage is not a guarantee for every language context.

The comment makes a bound discussable, not correct. Current data loss, security,
accessibility or functional defects are still defects even with a marker. Do not annotate
old code proactively. [`ideaforge-debt`](../.claude/skills/ideaforge-debt/SKILL.md) reports
in the reply by default; persist a local ledger only when explicitly asked. A clean marker
scan reports no recognised markers in its inspected scope, not the absence of comments
in unsupported formats, design costs or bugs.

### Local gain, not savings

```powershell
node tools\assistant.mjs gain
node tools\assistant.mjs gain --base REF
```

[`ideaforge-gain`](../.claude/skills/ideaforge-gain/SKILL.md) displays reproducible local
Git/diff/debt facts. With no base, the tracked working tree and index are compared with
HEAD; `--base REF` resolves that local reference to a commit and compares with it directly,
not an automatic merge base. Untracked files are not counted. Binary files are counted
separately, outside text addition/deletion totals.
Its JSON carries debt coverage through `debt.unread` files/reasons and `debt.omitted`.
YAML literal contexts and missing/deleted tracked source remain not-inspected boundaries,
even when the marker count is zero.

Label the root, base/HEAD identity actually checked, tracked worktree scope, reproducible
command and any omitted content. An unavailable base or failed/malformed Git result is a
limitation, not zero gain. If a resolved HEAD id was not checked, do not invent one or
represent the report as an exact committed snapshot.

Changed files and additions/deletions are counts, not saved lines. The version that was
never written is not a baseline. No local token/cost/time savings, prevented-bug count or
statistical LLM benefit is inferred from a smaller diff or a passing fixture test.
Upstream evidence, if cited, must be labelled upstream rather than an IdeaForge result.

## Worked choices from IdeaForge

These examples teach how to apply the canonical ladder after finding complete scope.
They are not new product features, implemented redesign screens or a second rule owner.

### Search and reuse

For a library-search change, orient with the map, then find `filterSessions` and
`sessionSearchText` in [`src/core/library.js`](../src/core/library.js) and their
[`library.test.mjs`](../test/library.test.mjs) contracts. The existing implementation
normalises search text and includes titles, tags, turns, facts and synthesis. A new UI-only
title filter would silently narrow that behaviour. Use available symbol/code-intelligence
tools, or a narrow file search, before introducing a search service or a second index.
Measure an actual latency problem before trading the direct scan for cache invalidation.

Provider auth, loopback parsing, deadlines and HTTP errors already have owners in
[`src/providers/http.js`](../src/providers/http.js): `applyAuth`, `isLoopback`,
`withDeadline` and `httpError`. Reusing only the happy path while dropping `ProviderError`
or cancellation semantics is not simplification. Check
[`providers.test.mjs`](../test/providers.test.mjs) and the relevant provider/browser
contract rather than adding another hostname-prefix regex.

### Native APIs and complete behaviour

`withDeadline` already uses `AbortSignal.timeout` and `AbortSignal.any`; a new homemade
deadline promise is unnecessary unless a demonstrated compatibility requirement demands
one. Keep platform access out of the pure runtime and preserve cancellation versus timeout.

`show` in [`src/ui/app.js`](../src/ui/app.js) toggles panels with `hidden`, preserving
the no-hash-routing microphone contract. `shareSession` uses the existing sharing helper
and `exportFor`/`buildExport`, rather than reading rendered markdown from the DOM or
yielding an unrelated async operation before the sharing gesture.

Illustrative future choice, **not an API currently used for IdeaForge's panel routing**:
a genuine future modal could consider native `<dialog>`/`showModal()` before a custom
focus trap. That would still require supported-browser, focus-return, keyboard, dismissal,
layout and microphone checks. This reference does not claim that such a dialog or the
unimplemented design target has shipped.

For a pure search/parser change, start with the focused Node test. For a change to UI,
storage, voice or service-worker behaviour, use `npm run test:browser:required`; an
all-green Node suite is insufficient. Follow [AGENTS.md](../AGENTS.md#the-ladder) and the
owning subsystem skill, including vectors/Kotlin validation when behaviour crosses that
boundary. Lean guidance does not remove those consumers or checks.

### A voice simplification to refuse

Replacing behavioural recognition probing with "the constructor exists", finishing every
hands-free answer on a short pause, flattening Android recognition results, or removing
noise calibration would be fewer lines and incomplete behaviour.
[`src/voice/vad.js`](../src/voice/vad.js) owns `DEFAULTS`, `DRIVING_GATE` and
`createSilenceGate`; the thresholds account for noise and thinking time, not stylistic
flexibility. Keep terminal finish words, faithful result sequencing, draft retention,
cancelled-effect guards and explicit recovery.

Use [`change-voice-and-driving`](../.claude/skills/change-voice-and-driving/SKILL.md)
and the [voice invariants](../CLAUDE.md#voice-probe-by-behaviour-never-by-feature-detection).
A scripted-media pass is not a physical-device or real driving calibration result.
No mode, including Ultra, authorises silently cutting those protections.

## Evidence and validation

Keep three evidence categories separate:

| Evidence | What it can prove | What it cannot prove |
|---|---|---|
| Offline structural/fixture/protocol checks | Owners, links, exclusions, command grammar, state isolation, output schemas and known-good/known-bad cases | Native discovery, a running model's consumed context or coding benefit |
| Fresh native client checks | Actual discovered sources, adapter loading and observed lifecycle consumption for that client/version | Universal child inheritance, every future client version or empirical savings |
| Separately authorised LLM experiments | Results for the declared model/tasks, complete output and comparison method | Guaranteed benefit across clients/models or unmeasured IdeaForge work |

### Verified native discovery

Evidence gathered on 2026-10-11:

- **Copilot CLI `1.0.96-2`:** fresh `copilot skill list --json` reported all 15 shared
  skills enabled and project-sourced. The installed official extension guide/types and
  custom-status stdin-JSON/stdout-text contract were also inspected. Discovery and API
  availability do not prove that the new extension loaded.
- **Copilot native hook discovery:** `hooks.discover` returned the expected
  `sessionStart`, `subagentStart` and `userPromptSubmitted` project hook types enabled,
  with no parse errors. Duplicate entries from the parent-project walk were observed;
  the shared code/root guard prevents cross-worktree execution. Discovery still does
  not prove that a workspace hook ran.
- **Claude Code `2.1.292`:** the official portable client was restored only under session
  artifacts, with SHA-256 matching winget metadata and no global installation,
  authentication or configuration changes. `claude plugin validate "<worktree>\.claude"`
  passed. Fresh stream-JSON initialisation with project settings and strict MCP returned
  success and listed all 15 project skills plus both existing Claude review agents.
  It also emitted a system `hook_response` for `SessionStart:startup`, exit 0, with
  4194 stdout characters containing Full and the canonical rules. This proves native
  startup-hook execution and reported output, not a model following those rules.
  No user/model prompts were sent.
- **Copilot native startup, 2026-10-12:** the official ACP stdio interface advertised
  the project skills and hooks. A normal integration prompt returned the exact Full
  context header with its own session identity, and that session's Full mode record
  existed afterwards. ACP reported no loaded project extensions: this proves the
  command-hook startup path, not the foreground SDK supplement.
- **Copilot native foreground, 2026-10-12:** the exact reviewed commit was temporarily
  checked out at the already-trusted original repository, under clean-tree/HEAD guards.
  The native extension tools reported `ideaforge-assist` ready and project-sourced.
  In an owned, genuine Windows console, `/env` reported it running. The CLI's separate
  request to register this reviewed extension's hooks was allowed for that test session
  only, not with an always-allow setting or tool/path/URL grants.
  After the explicit CLI helper prepared that owned session as Lite, its first normal
  prompt returned the exact LITE context header and its own session identity. This
  distinguishes SDK prompt consumption from the earlier Full startup context.
  The real session also produced `[IDEAFORGE:LITE]` through the status helper; no personal
  statusline was installed. The original main branch and exact HEAD were restored,
  owned test-mode records removed, and owned clients/extensions stopped. The isolated
  feature worktree's trust remained unchanged; no folder-trust grant was made.

The controlled Claude/parser and no-input hidden-UI checks supplied no model input.
One earlier piped UI probe submitted a single CLI turn (`/exit`); it is not part of that
zero-model-input evidence. The ACP and foreground context checks each used one normal
Copilot integration prompt, not a coding-benefit benchmark or an app/provider API key.
No claim is made that all native checks performed zero inference. No additional
hosted-provider/API checks or paid benchmarks were run.
Actual command-string protocol fixtures, including hostile Windows paths, and pure SDK
handler tests are separate from native workspace execution.

### Pending native consumption

Native evidence now covers Claude discovery/startup output, Copilot command-hook startup
consumption, and Copilot foreground project loading and mode-specific first-prompt SDK
consumption. Offline fixtures cover controls/Off, reset, identity isolation, queued events,
resume/clear, compaction restoration and child envelopes. Those fixtures are not a claim
that every such permutation was driven live in both clients, or that an opt-in custom
statusline was installed and visually checked. The built-in general-purpose child
boundary remains documented and requires explicit delegating context; no universal
automatic inheritance is claimed. These are evidence limits, not permission to bypass
trust or treat a native SDK-server listing as foreground loading proof.

For delivery, record actual client/version/command/result for discovery, fresh start,
resume, mode switch/Off, restored context, supported child context and the unsupported-child
fallback. Inspect extension load status/logs through native tools. Do not call models or
paid providers merely to make a discovery check look end-to-end.
When a check remains unavailable, state the exact boundary instead of converting a fixture
pass into native proof.

The [pre-PR review gate](#pre-pr-review-gate) has protocol coverage for both clients: the
shipped Claude and Copilot commands are run against a real Git repository in the unit
suite. On 2026-10-18, Claude Code `2.1.302` loaded the new `PreToolUse` entry into an
already-running session and refused `gh pr create --help` (gh `2.87.3`) on an uncommitted
tree with the gate's reason; nothing was sent to GitHub. Copilot CLI was not installed on
that machine, so a native Copilot `preToolUse` refusal and Copilot's dispatch of its
review agents as subagents remain to be observed and recorded here. Assistant checks in standard tests/CI remain offline and add no
dependencies; no upstream installer, benchmark stack or paid evaluation is run by this
feature.

For an optional future benefit experiment, obtain explicit model/key/spending-cap authority
and a bounded task set first. Validate the instrument with complete known-good and
deliberately incomplete/incorrect examples; score correctness, affected consumers and test
rigour before size. Declare model/task/base scope, repeatability, timeouts and missing data.
Do not claim reduced cost from lines of code or fewer tokens from an unmeasured diff.

## Provenance

The adapted workflow, control, map/debt/help and review/audit ideas come from
[DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail/tree/9b58c1ffb790c075ca32e70a89cf1d80588f4abf)
at pinned commit `9b58c1ffb790c075ca32e70a89cf1d80588f4abf`. Upstream source was read as
evidence, not executed or treated as instructions. The copyright and full MIT notice are
retained in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md); the app's
[LICENSE](../LICENSE) is unchanged.

The upstream authors'
[2026-10-07 agentic report](https://github.com/DietrichGebert/ponytail/blob/9b58c1ffb790c075ca32e70a89cf1d80588f4abf/benchmarks/results/2026-10-07-agentic.md)
is **upstream Ponytail evidence, not an IdeaForge result**. It used Opus 5.5 and repeated
tasks across no-skill, previous and new Ponytail arms, including a FastAPI + React fixture.
Only part of the task set had hidden correctness checks; test presence and test rigour
differ, and baseline timeouts/missing deliveries affect interpretation. These model/task
results do not establish benefit for this browser-only/Kotlin project or Copilot's selected
model. No headline percentage is used as a local guarantee.

Native contracts should be checked against the current
[Claude hooks](https://code.claude.com/docs/en/hooks) and
[skills](https://code.claude.com/docs/en/skills), the
[Copilot hooks reference](https://docs.github.com/en/copilot/reference/hooks-reference),
and the installed official Copilot extension guide/types. Documentation and SDK signatures
identify supported surfaces; fresh native evidence establishes what actually loaded.
