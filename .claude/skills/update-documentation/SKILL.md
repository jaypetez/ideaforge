---
name: update-documentation
description: Update IdeaForge documentation from its owning source and verify
  drift-sensitive surfaces.
---

# Updating IdeaForge documentation

This is a mutating maintenance procedure. Use it to change the public guide, repository
documentation, assistant guidance, or documentation-facing generated artifacts. Run from the
repository root with the current client's normal tools and permissions; this skill does not
pre-approve tools. For a review that must not edit anything, use
[`review-ideaforge-documentation`](../review-ideaforge-documentation/SKILL.md).

## 1. Establish the requested surface

Read the exact issue, diff, or source change that prompted the update. Inspect the working tree
and preserve unrelated edits. Identify which documentation surfaces are in scope before
writing; do not turn a focused request into a general rewrite.

Read:

- the [documentation source map](references/source-map.md);
- the [working loop](../../../AGENTS.md) and
  [implementation invariants](../../../CLAUDE.md);
- the [architecture map](../../../docs/ARCHITECTURE.md) and
  [contributor guide](../../../CONTRIBUTING.md) when the change concerns development or
  delivery.

Treat implementation, scripts, workflows, and generated output as evidence. Existing prose is
not proof that a claim is still true.

## 2. Re-derive every changed claim

- Read values, supported paths, defaults, commands, and gates from their owning source.
- Prefer durable descriptions over counts and version strings when the exact number is not
  useful to the reader.
- Cite files and symbols, not line numbers.
- Link to the canonical invariant instead of copying a long internal trap into another page.
- Keep user guidance task-oriented and repository guidance precise about ownership and
  validation.
- Do not change implementation merely to make old documentation true unless the requested
  task includes that implementation change.

If the README, screenshots, worked example, provider table, dimensions, version, or other
README-derived facts changed, follow the shared
[`update-readme`](../update-readme/SKILL.md) procedure. Do not hand-edit generated screenshots
or `docs/examples/remember-names.md`.

## 3. Keep each documentation surface internally consistent

For the public guide:

- copy the shell, navigation, Content-Security-Policy, and footer from
  [`guide/index.html`](../../../guide/index.html);
- keep every page in the same navigation order and give exactly one link
  `aria-current="page"`;
- use relative application, stylesheet, icon, and screenshot URLs so root and Pages-style
  subpaths both work;
- keep the guide static and compatible with its restrictive CSP;
- update the guide browser page list when a page is added or removed.

For repository guidance:

- keep `AGENTS.md` as the working loop, `CLAUDE.md` as the implementation reference,
  `docs/ARCHITECTURE.md` as the map, and `CONTRIBUTING.md` as contributor-facing setup;
- keep one definition of each shared skill under `.claude/skills/`;
- make client review agents thin adapters with read-only tools and local links to their
  shared skill;
- never add blanket tool preapproval or duplicate a shared skill under `.github/skills/`.

## 4. Check the integration points

Documentation changes often need small registry updates outside the prose. Inspect, without
assuming they are in scope:

- the expected guide page list in
  [`test/browser/guide.browser.mjs`](../../../test/browser/guide.browser.mjs);
- skill and agent expectations in
  [`test/agent-support.test.mjs`](../../../test/agent-support.test.mjs);
- assistant discovery text in
  [`.github/copilot-instructions.md`](../../../.github/copilot-instructions.md) and
  [`CONTRIBUTING.md`](../../../CONTRIBUTING.md);
- the publishable roots in
  [`tools/assemble-site.mjs`](../../../tools/assemble-site.mjs);
- CI, Pages, and release wording against their actual workflows.

If an integration file needs a change but is outside the caller's ownership, do not edit it.
Report the exact required follow-up.

## 5. Validate what changed

Check local links and referenced files first. Then use the smallest repository checks that
cover the surface:

```sh
npm run test:docs
npm run test:browser:required
```

The browser check is required for public guide changes because it proves the assembled pages,
CSP, service-worker bypass, narrow layout, and Pages-style subpath. Before pushing a
documentation branch, run:

```sh
npm run test:all
```

When assistant files change, also inspect native discovery from a fresh client session.
Structural tests prove repository shape; only the client can prove what it loaded.

## 6. Finish with evidence

Review the exact documentation diff. Report:

- the files and surfaces updated;
- the source used for each material claim;
- generated artifacts refreshed, if any;
- checks that passed or could not run;
- integration changes still needed outside the task's ownership.

Do not commit, push, merge, tag, or publish unless the caller explicitly requested that
separate action.
