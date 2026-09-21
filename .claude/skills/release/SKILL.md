---
name: release
description: Prepare, publish, or verify a stable IdeaForge release through the existing
  tag-driven workflow. Use only for an explicit release request. Preparation opens a PR;
  publication requires a merged green commit and an authorised upstream writer; verification
  checks the published archives and public container image.
disable-model-invocation: true
---

# Releasing IdeaForge

This skill is shared by Claude Code and GitHub Copilot (CLI and VS Code). Invoke it
explicitly as `/release prepare VERSION`, `/release publish VERSION PR`, or
`/release verify VERSION`. `VERSION` is a confirmed stable `MAJOR.MINOR.PATCH`, without
the `v`; `TAG` is `v` followed by `VERSION`. Prereleases are outside this procedure.

An unspecified action means preflight/preparation, never publication. Confirm a missing
version or publisher before mutations. If the user chose a maintainer to push the tag,
prepare the handoff and leave that action to them. Do not bypass a client's manual-only
invocation control, grant tools permission automatically, or switch accounts silently.

Run commands from the Git root with the client's normal tools and permissions. Keep
variable assignments and dependent commands in the same shell invocation, or use the
validated literal values: tool calls do not necessarily share shell state.

Read the [working loop](../../../AGENTS.md) and
[contributor guidance](../../../CONTRIBUTING.md#cutting-a-release). The sources of truth are:

- [package.json](../../../package.json) and [src/version.js](../../../src/version.js)
  for the paired app version and available scripts.
- [CI](../../../.github/workflows/ci.yml) and the
  [release workflow](../../../.github/workflows/release.yml) for the actual gates.
- [release-note configuration](../../../.github/release.yml) for generated notes.
- [Pages](../../../.github/workflows/pages.yml) for deployment, which is separate from tagging.

The upstream release target is **`jaypetez/ideaforge`**, and the image is
**`ghcr.io/jaypetez/ideaforge:VERSION`** (no `v` in the image tag). A writable fork can
host a preparation branch, but it must never receive the production release tag.

## 1. Preflight

Inspect the live state rather than copying a version, SHA, run id, or digest from old
documentation. These commands are read-only:

```sh
git status --short --branch
git remote -v
gh api repos/jaypetez/ideaforge --jq '{name: .full_name, branch: .default_branch, permissions: .permissions}'
gh release list --repo jaypetez/ideaforge --limit 20 --json tagName,isDraft,isPrerelease,publishedAt
gh api repos/jaypetez/ideaforge/git/matching-refs/tags/v --jq '.[] | {ref, object}'
```

Verify the exact upstream owner/repository; a remote named `origin` is not proof. If its
default branch or release workflow differs from this procedure, stop and reconcile the
change before using it. Inspect permissions for the action being requested: preparing a
fork PR does not require upstream tag-write access, but publishing does.

Inventory existing edits and preserve unrelated work. Preparation can include approved
changes from the same task, including this skill's initial creation; do not sweep other
changes into a release. Use a separate branch/worktree when needed. Never reset someone
else's work, alter global Git/Docker credentials, or bypass branch protection.

Read both version files and the latest stable release. Confirm the requested version is
appropriate and available. An existing tag or release is not permission to overwrite it:
if it belongs to this same completed release, use `verify`; if it conflicts, stop.

## 2. Prepare: a PR, not a release

Start or reuse the intended preparation branch based on current upstream `main`, not an
unrelated feature branch. Update `package.json` and `src/version.js` together to the
confirmed version. Do not let a version-bump command implicitly commit or create a tag.

Review the changes since the previous release and prepare a concise PR summary. Reuse
the generated-notes configuration rather than adding a changelog or publishing notes
yourself. Follow the [README skill](../update-readme/SKILL.md) when its documentation
or facts change; derive counts from the current runs.

Run the focused checks while editing, then the complete ladder before pushing. In a
POSIX shell:

```sh
BROWSER_CHECK_REQUIRED=1 npm run test:all
```

In PowerShell:

```powershell
$env:BROWSER_CHECK_REQUIRED = '1'
npm run test:all
```

The current complete script includes the unit/purity suite, module graph, and required
browser checks on the assembled site. A missing browser or a skipped check is not success.
Keep the no-dependency/no-build architecture; do not weaken a gate to make a release green.

Review the final diff, commit only the intended changes, and open a new PR against
upstream `main`, using an authorised fork when necessary. Record what actually ran.
Ask for the appropriate release-note label if the author cannot apply it.

The handoff must state the version/tag, PR, intended publisher, and the remaining merge,
CI and tag gates. The future tag must name the PR's actual merged commit, not the
pre-merge head. If review, merge or tagging belongs to a maintainer, stop at that boundary.
Do not announce the release as shipped because the preparation PR exists.

## 3. Publish: only an explicitly authorised upstream writer

Do this action only when the user explicitly requested publication by the current
operator. If the maintainer owns tagging, give them this procedure instead.

Resolve the PR from upstream:

```sh
gh pr view "$PR" --repo jaypetez/ideaforge --json state,baseRefName,mergeCommit
```

Require `state == MERGED`, `baseRefName == main`, and a non-empty, 40-character hexadecimal
merge commit id. Set `SHA` from that id only. **Never let a missing SHA fall back to
`HEAD`.** Before constructing tag commands, validate the stable version and tag again.

Fetch `main` from the verified upstream remote and require `SHA` to be its exact current
tip, not merely an ancestor. Read both version files at **that SHA**, not from the working
directory; both must equal `VERSION`. Recheck upstream write access and that the tag/release
has not appeared or been superseded while the PR was under review.

Find the upstream CI push run for that exact merged SHA:

```sh
gh run list --repo jaypetez/ideaforge --workflow ci.yml --branch main --commit "$SHA" --event push --json databaseId,headSha,status,conclusion
gh run view "$CI_RUN_ID" --repo jaypetez/ideaforge --json headSha,event,headBranch,status,conclusion,jobs
```

Use the latest matching run, not an unrelated successful run or a fork/PR-head check.
Require the run and its single `ci` aggregator job to be completed successfully. Missing,
pending, skipped, cancelled and failed are all stop conditions.

Find the Pages workflow for that same SHA:

```sh
gh run list --repo jaypetez/ideaforge --workflow pages.yml \
  --commit "$SHA" --event workflow_run \
  --json databaseId,headSha,status,conclusion
gh run view "$PAGES_RUN_ID" --repo jaypetez/ideaforge \
  --json headSha,event,headBranch,status,conclusion,jobs
curl -fsSL https://jaypetez.github.io/ideaforge/src/version.js
```

Require a completed successful `deploy` job whose `headSha` is `SHA`, and require the live
module to report `VERSION`. Re-fetch upstream `main` immediately before tagging and stop if
its tip moved. A newer main commit means another release preparation is required; do not tag
the older tree merely because it is still an ancestor.

Only after those checks, with a clean worktree and `UPSTREAM` verified to target
`jaypetez/ideaforge`, create and push the one tag at the explicit commit. Keep the input
guard with the commands; an empty SHA must never become an implicit `HEAD`.

Git cannot atomically compare `main` and create an unrelated tag in this workflow. The
maintainer must hold a short no-merge release window, recheck the remote `main` tip
immediately before the push, and stop if another merge is possible. The workflow's ancestry
check is a backstop, not exact-tip serialization.
In a POSIX shell:

```sh
(
  : "${VERSION:?confirmed version required}" "${UPSTREAM:?verified upstream required}"
  printf '%s\n' "$VERSION" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' || {
    printf '%s\n' 'Expected a stable version' >&2; exit 1;
  }
  case "$SHA" in
    ''|*[!0-9a-fA-F]*) printf '%s\n' 'Expected the approved merge SHA' >&2; exit 1 ;;
  esac
  [ "${#SHA}" -eq 40 ] || { printf '%s\n' 'Expected a 40-character merge SHA' >&2; exit 1; }
  CURRENT_SHA="$(git ls-remote "$UPSTREAM" refs/heads/main | awk 'NR == 1 { print $1 }')"
  [ "$CURRENT_SHA" = "$SHA" ] || {
    printf '%s\n' "main moved to ${CURRENT_SHA:-an unknown SHA}; expected $SHA" >&2
    exit 1
  }
  TAG="v$VERSION"
  git push "$UPSTREAM" "$SHA:refs/tags/$TAG"
)
```

In PowerShell:

```powershell
if ($VERSION -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$' -or
    $SHA -notmatch '^[0-9a-f]{40}$' -or
    [string]::IsNullOrWhiteSpace($UPSTREAM)) {
  throw 'Expected a stable version, approved merge SHA, and verified upstream remote'
}
$ref = git ls-remote $UPSTREAM refs/heads/main
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the current upstream main tip' }
$CURRENT_SHA = ($ref -split '\s+')[0]
if ($CURRENT_SHA -ne $SHA) { throw "main moved to $CURRENT_SHA; expected $SHA" }
$TAG = "v$VERSION"
git push $UPSTREAM "$($SHA):refs/tags/$TAG"
if ($LASTEXITCODE -ne 0) {
  throw 'Tag push failed; inspect upstream main and the tag before retrying'
}
```

Check each command's exit status before the next action. Do not use `--force`, push all
tags, move or delete an existing tag, or publish from the preparation fork.
Do not use `gh release create` or the GitHub release UI: the tag-triggered workflow owns
publication and must run its read-only verification before it writes release assets.

## 4. Verify: the public outputs, not just a tag

Resolve the upstream tag to its commit, peeling an annotated tag if necessary, and match
it to the approved merge SHA. Find the release workflow for that exact tag and SHA:

```sh
gh run list --repo jaypetez/ideaforge --workflow release.yml --commit "$SHA" --event push --json databaseId,headBranch,headSha,status,conclusion
gh run watch "$RELEASE_RUN_ID" --repo jaypetez/ideaforge --exit-status
gh run view "$RELEASE_RUN_ID" --repo jaypetez/ideaforge --json headSha,headBranch,status,conclusion,jobs
```

Do not select a run by recency alone. Require the matching tag and commit, a completed
successful run, and successful `verify`, `release` and `image` jobs. If no matching run
exists, publication is pending or blocked; creating a release by hand is not a repair.

Inspect and download the actual release into a new scratch directory:

```sh
gh release view "$TAG" --repo jaypetez/ideaforge --json tagName,isDraft,isPrerelease,assets,url
gh release download "$TAG" --repo jaypetez/ideaforge --dir "$SCRATCH" --pattern "ideaforge-$VERSION.tar.gz" --pattern "ideaforge-$VERSION.zip" --pattern SHA256SUMS
```

Require a published stable release and all three expected uploaded assets. Verify both
archive hashes against `SHA256SUMS`, accepting only the two expected filenames. Inspect
both archives with standard-library/platform tools: require the `ideaforge-VERSION/`
prefix and matching `package.json`/`src/version.js` contents. Reject unsafe archive paths
and never execute downloaded content. An HTTP 200 or a release page is not this check.

Check the public image without using the user's saved registry credentials. If Docker is
available, use a new, empty `--config` directory; never run `docker logout` against the
normal configuration. Docker is not required: use the anonymous Registry HTTP flow:

1. Request an anonymous pull token from
   `https://ghcr.io/token?service=ghcr.io&scope=repository:jaypetez/ideaforge:pull`.
2. Read `https://ghcr.io/v2/jaypetez/ideaforge/manifests/VERSION` with that token and
   OCI-index/Docker-manifest-list `Accept` types. Never log tokens. Decode vendor JSON
   before parsing; PowerShell can return its content as `byte[]`.
3. Require a valid index and both `linux/amd64` and `linux/arm64`. Check version and
   revision metadata against `VERSION` and `SHA`, following the platform manifests and
   image config blobs where necessary. Record the registry's manifest digest.
4. If this is still GitHub's latest stable release, require the `latest` image reference
   to resolve to the same index. If a newer release has superseded it, report that fact
   rather than rolling `latest` back.

Do not claim a container was started unless it actually was. Registry inspection is a
different check and needs no local daemon or dependency installation.

Pages is independently triggered by the merge, but coordinated verification still requires
its successful deployment to the same `SHA` and a live `src/version.js` equal to `VERSION`.
Do not claim the tag deployed Pages or redeploy an older SHA to manufacture a matching
result.

## 5. Recovery and completion

If a push fails, inspect the local and upstream tag targets before retrying. A local tag
already matching the approved SHA does not need recreating: after rechecking the gates,
push that same ref if it is still absent upstream. If the matching upstream tag already
exists, proceed to verification instead. A different SHA is a stop condition, not a
reason to force or delete anything.

A published GitHub release with a failed image job is a partial release, not success.
If only the image job failed, an authorised operator can retry the failed job after
checking for a superseding release. Never rerun a successful publication job blindly.
If release creation/upload itself partially failed, stop for explicit recovery rather
than deleting the release, clobbering assets or moving its tag.

Report the tag, approved SHA, release and workflow references, verified asset checksums,
image digest/platforms, and any outstanding state. Keep preparation, waiting for the
maintainer, partial publication and fully verified publication distinct. Clean only the
scratch files and profiles created for this run. Do not start an unattended polling
service or claim completion while the required publication evidence is missing.
