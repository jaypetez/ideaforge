# Repository configuration

`ruleset.json` is the branch protection on `main`, kept in the repo so it can be restored
exactly if it is ever edited into a corner:

```sh
# inspect what is live
gh api repos/jaypetez/ideaforge/rulesets

# re-apply (replace ID with the one from the list above)
gh api repos/jaypetez/ideaforge/rulesets/ID -X PUT --input .github/ruleset.json

# or create it fresh
gh api repos/jaypetez/ideaforge/rulesets -X POST --input .github/ruleset.json
```

## The bypass block is the important part

Unlike classic branch protection — where `enforce_admins: false` exempts admins by
default — **a ruleset applies to the repository owner like everyone else** unless they are
named in `bypass_actors`. That is the usual way a solo maintainer locks themselves out of
their own default branch.

The owner is therefore named twice: once as `User` (id `78129710`) and once as
`RepositoryRole` id `5`. GitHub does not document the role-id mapping, and `5` = admin is
only community-confirmed, so the `User` entry is the one guaranteed to work and the role
entry is a spare.

`bypass_mode` is `always`, not `pull_request`. The latter only permits a bypass *while
merging a PR*, which is no use when the thing you need is a direct force-push to repair a
bad merge.

## If you ever do get locked out

A repository admin can always edit or delete a repo-level ruleset from Settings → Rules,
or with `gh api repos/jaypetez/ideaforge/rulesets/ID -X DELETE`. Repository settings are
not themselves governed by the ruleset. The genuinely unrecoverable case is an
*organization*-level ruleset with no bypass for you — which does not apply to a
personally-owned repo like this one.

## The required check is a gate job, not the matrix

`required_status_checks` names exactly one context: `ci`. That is the aggregating job at
the bottom of `workflows/ci.yml`, not any individual matrix leg. Requiring the legs by
name would mean every change to the OS or Node matrix silently orphans a required context,
which then blocks every PR forever at "Expected — waiting for status to be reported".

A required check that never runs is the other way to wedge this repo, so do not add
`paths:` filters to `ci.yml`.
