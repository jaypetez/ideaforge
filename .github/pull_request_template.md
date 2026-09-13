## What this changes

<!-- One or two sentences. What is different afterwards, and why. -->

## Why

<!-- The problem behind the change. If it fixes an issue: Fixes #123 -->

## How it was verified

<!-- Not "tests pass" — what did you actually run or click? If it touches voice, storage
     or a provider, say which browser and which provider you tried it against. -->

---

- [ ] `npm test` passes (this runs `lint:purity` too)
- [ ] Nothing under `src/core/` or `src/runtime/` gained a `window`, `document`, `fetch`
      or `navigator` reference — the purity lint enforces this, and it is the seam that
      keeps the engine portable and the turn loop testable offline
- [ ] No new runtime dependency (this project has zero, deliberately)
- [ ] If it touches the exported markdown, an example export is pasted above
