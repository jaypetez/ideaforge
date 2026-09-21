---
name: update-readme
description: Refresh README facts after UI, provider, core, dimension, version, or README changes.
---

# Updating the README

This is the shared README skill for Claude Code and GitHub Copilot (CLI and VS Code).
Run every command from the repository root, not this skill's directory. Use the current
client's native file and terminal tools with its normal permissions; this skill does not
pre-approve tools or require a particular shell.
Never use a live provider key or a paid provider to generate the committed walkthrough.

The README makes claims about code that moves. This skill re-derives them instead of
re-wording them.

The rule for everything below: **read the value out of source, never out of the previous
README.** A number copied forward from the last version is how a README ends up describing an
app that no longer exists.

## 1. Regenerate the artifacts first

```sh
npm run screenshots
```

This drives the real app in headless Chrome against `tools/fixtures/walkthrough.mjs` and
rewrites all twelve PNGs in `docs/screenshots/` plus `docs/examples/remember-names.md`. It
needs Chrome (`CHROME_PATH` overrides discovery) and a network connection — the app's CSP
loads IBM Plex from Google Fonts, and the harness prints a loud warning if the shots came out
in fallback faces. **A run that warns about fonts is a run to throw away.**

Read the harness output before moving on. It reports three things that matter:

- **`MISMATCH` lines.** The fixture writes each question for a particular dimension; the
  engine chooses the dimension independently. A mismatch means a question in the walkthrough
  is now being asked under the wrong heading. Fix it by rewriting that question in
  `tools/fixtures/walkthrough.mjs` to suit the dimension the engine actually picked — never
  by editing the engine to suit the fixture.
- **`wrapped because:`** — the walkthrough is supposed to end on *"There is enough here to
  write it up whenever you like."* If it now ends on the exhaustion or ceiling message, the
  interview is no longer demonstrating the coverage exit and the fixture's coverage claims
  need rebalancing.
- **`docs/ is now …`** — keep it under about 1.5 MiB. If it jumps, something is rendering
  wrong rather than something being bigger.

Two failure modes worth recognising, because both have happened:

- Shots suddenly much taller than the content, with several panels visible at once, means
  something has broken `[hidden]` in `src/ui/app.css`. That is an app bug, not a harness bug.
- The two theme runs exporting different markdown means the frozen clock is not holding; the
  harness fails loudly on this, since it is what keeps the committed example reviewable.

Then confirm the export is byte-stable:

```sh
npm run screenshots && git diff --stat docs/examples/
```

A second run must leave `docs/examples/remember-names.md` unchanged.

## 2. Re-derive every number in the prose

Check each of these against source and fix the README where they disagree:

| Claim in the README | Source of truth |
|---|---|
| seven dimensions, and their labels | `DIMENSIONS` in `src/core/dimensions.js` |
| "a built-in bank of 21" | sum of `bank.length` across `DIMENSIONS` |
| the soft and hard ceilings | `SOFT_TURN_CEILING` / `HARD_TURN_CEILING`, `src/core/engine.js` |
| the provider table | `PROVIDER_CHOICES` in `src/providers/index.js` |
| the unit test count | the tail of `npm test` |
| the browser check count | the tail of `npm run test:browser:required` |
| the ratchet rules | `applyCoverage` in `src/core/session.js` |
| the walkthrough's question count and coverage | the meta line of the generated example |
| every `npm run …` shown | `scripts` in `package.json` |

A one-liner for the numeric ones:

```sh
node -e "
import('./src/core/dimensions.js').then(d => {
  console.log('dimensions', d.DIMENSIONS.length);
  console.log('bank', d.DIMENSIONS.reduce((n, x) => n + x.bank.length, 0));
});
import('./src/core/engine.js').then(e =>
  console.log('ceilings', e.SOFT_TURN_CEILING, e.HARD_TURN_CEILING));
"
```

If `DIMENSIONS` has changed, `docs/hero.svg` needs the same edit — it hard-codes the seven
labels and their final levels, and those levels are the real end state of the walkthrough, so
take them from the coverage table in `docs/examples/remember-names.md` rather than inventing
them. The hero has no script and loads nothing remotely, on purpose: GitHub serves it through
an `<img>`, where scripts never run and remote fonts never load. Keep it that way, keep the
`prefers-reduced-motion` block working, and keep `transform-box: fill-box` on `.fill` — without
it a half-width meter scales out of the left edge of the image.

## 3. Hold the voice

The README is prose that explains *why*, not a feature list. Match what is already there:

- British spelling — licence, recogniser, behaviour, synthesised.
- Hard-wrapped at about 95 columns; `.editorconfig` sets a 100-column limit.
- No marketing register, no emoji headings, no "🚀 Features" bullet lists, no exclamation
  marks. If a sentence would be at home on a landing page, it does not belong.
- Prefer the concrete failure over the abstract benefit. "It fails on sight if it asks me to
  type while someone is still talking" beats "delivers a great user experience".
- Every screenshot needs real `alt` text describing what is on screen, not "screenshot".
- Deep reference material belongs in `CLAUDE.md`; the README links to it rather than
  absorbing it.

## 4. Verify before claiming done

```sh
npm test
npm run test:all
```

`npm run test:all` includes the module graph and the required-browser variant; a missing
Chrome is a failure rather than a successful skip.

`test/docs.test.mjs` checks that every image and relative link the README references exists,
and that the dimension count and hard ceiling it states match source. It does not check the
prose — that part is the job above.

Finally, look at the rendered page. `<picture>` with `prefers-color-scheme`, and whether the
hero actually animates, can only be confirmed on github.com, so push the branch and open it
in both colour schemes before calling it finished.
