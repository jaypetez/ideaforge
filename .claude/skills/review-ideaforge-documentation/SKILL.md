---
name: review-ideaforge-documentation
description: Review a supplied IdeaForge documentation diff for factual drift and missing
  integration without edits.
---

# Reviewing IdeaForge documentation

This is a read-only review. Do not edit files, create commits, push, merge, tag, publish,
change settings, regenerate artifacts, or make live provider calls. Never request or expose
API keys or stored transcripts.

## 1. Establish the exact change set

Inspect the exact staged, unstaged, branch, or PR diff supplied by the caller. If the exact
change set is unavailable, stop and report that limitation. Never infer a clean review from
the current files or from a prose summary of an unseen diff.

Read the changed document in context, then use the
[documentation source map](../update-documentation/references/source-map.md) to open the
owning source. Read the [working loop](../../../AGENTS.md),
[implementation invariants](../../../CLAUDE.md), and
[architecture map](../../../docs/ARCHITECTURE.md) only where they bear on the changed claim.

Review only defects introduced or exposed by the supplied diff. Do not turn unrelated
pre-existing drift into findings.

## 2. Check claims against their owners

- Commands exist in `package.json` and use the required-browser form where browser coverage
  is claimed.
- Architecture, state ownership, purity, and extension seams agree with the owning modules,
  lint, and tests.
- Provider, voice, storage, privacy, and local-model guidance matches current browser and
  adapter behaviour.
- CI, Pages, and release descriptions match the actual workflow triggers, permissions,
  gates, and outputs.
- Counts, versions, model names, defaults, and generated examples are re-derived rather than
  copied forward.
- Public user guidance does not expose internal detail that belongs only in maintainer
  references, and maintainer guidance does not replace a canonical invariant with a weaker
  paraphrase.

## 3. Check document structure and integration

For a public guide change:

- the page uses the exact shell, navigation, Content-Security-Policy, and footer from
  [`guide/index.html`](../../../guide/index.html);
- exactly one navigation item has `aria-current="page"`;
- relative assets work at both the origin root and a Pages-style subpath;
- the page is represented in the guide browser contract and remains outside the app shell
  cache.

For skills and assistant guidance:

- each skill has valid frontmatter and one canonical definition under `.claude/skills/`;
- local references resolve;
- shared skills do not grant blanket tool permission;
- review adapters expose read-only tools, link to the shared skill, and repeat the
  exact-change-set stop condition;
- discovery documentation and structural expectations include additions or removals.

For README artifacts, verify that the diff follows the
[`update-readme`](../update-readme/SKILL.md) procedure rather than hand-editing generated
screenshots or the worked example.

## 4. Look for omitted companion changes

Use the source map to check both directions:

- a source change that should update documentation but does not;
- a documentation claim changed without the source, workflow, generated artifact, navigation,
  or registry change needed to make it true.

An omitted companion file is actionable only when the supplied diff and current source prove
it is required. Name that file and the failed user or maintainer path.

## 5. Report actionable findings

For each finding, give:

- severity and confidence;
- the documentation file and smallest useful line range;
- the exact claim or omission;
- the source that contradicts it;
- the concrete consequence for a reader, contributor, or release;
- the smallest safe direction for correction.

Do not report style preferences, speculative future drift, or praise. If there are no
high-confidence defects, say so plainly and state the exact diff reviewed.
