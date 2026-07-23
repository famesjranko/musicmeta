# Release Workflow — musicmeta

This document is authoritative for preparing, merging, tagging, and verifying a musicmeta release.
Branch topology and ordinary issue handling live in [workflow.md](workflow.md); version compatibility
rules live in `CLAUDE.md` → **Backwards Compatibility**.

A release is three human actions. Everything between them is CI.

| | You do | CI does |
|---|---|---|
| Gate 1 | Run **Prepare release** | pins the `CHANGELOG` and `ROADMAP` headings, writes the version into `gradle.properties` and all 9 README coordinates, proves the notes assemble, pushes to `dev` |
| Gate 2 | Open the `dev` → `main` PR and merge it | `build`, `demo-canary`, `release-readiness` |
| Gate 3 | Run **Release** from `main` | verifies, fast-forwards `dev`, tests, publishes to Central, waits for it to resolve, tags, creates the GitHub Release |

## Before gate 1

**Write the `[Unreleased]` section of `CHANGELOG.md` as you go.** That is the whole preparation, and
it is the ordinary habit of keeping a changelog — nothing release-specific.

It matters because **that section becomes the release note verbatim**: one line per change, headline
plus `(#issue)`, reasoning left in the issue or PR. Capped at 3000 characters and 400 per line. Check
it any time with `python3 scripts/github-workflows/build_release_notes.py <next-version>` after
pinning locally, or just run gate 1 as a dry run and read what it prints.

Choose the version under the `0.x` compatibility policy in `CLAUDE.md` — a patch release cannot
contain a breaking public API change. That choice is the one judgement call in the release.

## Gate 1 — Prepare release

Actions → **Prepare release** → Run workflow, from `dev`. Enter the version. Leave `dry_run` ticked
the first time: it prints the full diff and the assembled notes without pushing anything.

It then does every mechanical edit, so none of them is a chance to typo the value every later check
reads:

- pins `## [Unreleased]` to `## [<version>] - <date>` and opens a fresh empty `[Unreleased]` above it
- moves `ROADMAP.md`'s “Where We Are” heading to the new version
- sets `version` in `gradle.properties`
- rewrites all nine README coordinate lines, then greps to prove the old version is gone
- assembles the release notes and fails if they do not fit the caps

Re-run with `dry_run` unticked to commit `release: prepare v<version>` to `dev`.

It refuses to prepare a version that is already tagged, and `pin_release.py` refuses to pin twice or
to pin an empty `[Unreleased]` — so a mistaken re-run stops rather than double-pinning.

## Gate 2 — the release PR

Open `dev` → `main` yourself with the release evidence, and merge it with a **merge commit, never
squash**. `main` requires `build`, `demo-canary` and `release-readiness`.

Do not put `Closes #<epic>` here. `main` is not the default branch, so a closing keyword on this PR
closes nothing; the epic closes at its consolidation PR into `dev` instead. See
[workflow.md](workflow.md) → Issue lifecycle.

The PR must be opened by a person. A PR opened by CI receives no check runs at all — GitHub does not
trigger workflows from `GITHUB_TOKEN` events — so it could never satisfy those required checks.

This PR is the review point for the accumulated public API diff.

## Gate 3 — Release

Actions → **Release** → Run workflow, from `main`. Pick a `mode`:

| mode | does | leaves behind |
|---|---|---|
| `verify` | every check; publishes nothing, tags nothing | nothing |
| `stage` | tests, then uploads a **droppable rehearsal** to the portal | one deployment for you to drop |
| `release` | the full path | a published release |

### Rehearsing with `stage`

`stage` uploads under `<version>-rc.<run number>` with `automaticRelease` off, so the deployment
lands in the Central Portal as *validated but unpublished*. It exercises the credentials, the GPG
signing, the upload and Central's own validation — every mechanism the real path depends on — and
then you drop it at
[central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).

Leave `version` blank and it stages the currently declared version, e.g. `0.10.1-rc.7`. **No version
is consumed and nothing in the repo changes.** The `-rc.N` qualifier sorts *below* the plain version
in Maven's ordering, so a staged artifact could never outrank a real release even if it were
published by mistake — which is why a four-segment scheme like `0.10.1.1` is wrong here: that sorts
*above* `0.10.1`.

`stage` skips the release-correctness checks (tag, version agreement, README, notes) because those
guard a real release and `verify` already covers them. It only needs to be on `main` and pass tests.

**Drop the deployment when you are done.** Sonatype documents dropping a `VALIDATED` deployment, but
does not document whether the exact version can then be re-uploaded — which is precisely why the
rehearsal uses a throwaway `-rc.N` coordinate you would never ship.

### `release` — the full path

Three jobs run in order, recoverable before irreversible:

1. **verify** — refuses a non-`main` ref; asserts the tag is free, that all three modules and the
   `CHANGELOG` heading agree, that README pins the version, and that the notes assemble. Then
   fast-forwards `dev` to `main`, which fails harmlessly if someone has landed on `dev` since the
   merge.
2. **publish** — tests plus `apiCheck`, then uploads to Central and waits up to 30 minutes for all
   three modules to resolve. `automaticRelease` is on, so there is no portal click; the poll is what
   proves the deployment passed Central's asynchronous validation. **A Maven Central release is
   immutable.**
3. **release** — pushes the annotated `v<version>` tag, creates the GitHub Release from the
   `CHANGELOG` section plus a generated install block and compare link, then warms JitPack.

**JitPack is a pull, not a push.** Nothing publishes to it: it clones and builds on the first
artifact request, from the **git tag** alone — not from the GitHub Release, and not from anything
this workflow uploads. So pushing the tag *is* the entire JitPack integration. The warm-up step
requests each module's POM so the build happens during the release rather than under the first
consumer who follows the JitPack link in the notes. It is `continue-on-error` on purpose: JitPack is
a secondary channel, and its build is not something to fix mid-release.

**If a job fails, use "Re-run failed jobs", not a fresh run.** Publishing is not idempotent — Central
rejects a duplicate version — so a re-run must not repeat a successful upload. Job boundaries make
that safe, and the publish job additionally skips its own upload when all three modules already
resolve.

Nothing derives a version from a tag, because the tag is created last from a version already
verified. A tag that disagrees with what was published is no longer possible to create.

## Release notes

The `## [x.y.z]` section of `CHANGELOG.md` **is** the note. Only the `## Installation` block and the
`**Full Changelog**` link are generated, which is what stops them going stale: every release from
v0.8.1 to v0.10.1 carried a versionless badge that rendered the live-latest version on every page.

Check any release's notes at any time:

```bash
python3 scripts/github-workflows/build_release_notes.py <version>          # what would be published
gh workflow run release-notes-check.yml -f tag=v<version>                  # re-check a published one
```

`release-notes-check.yml` is a manual tool now, not a guard: its `release` trigger does not fire for
a CI-created release, and `release.md` already measured that `gh release edit` does not fire it
either.

## Deliberately deferred tagging

If publication is intentionally deferred, leave the README installation snippets at the previous
resolvable version and do not run gate 1. Land docs-only changes through a PR into `main`, then
fast-forward `dev` again. `main` still requires a merge commit and its required checks even for that
exception — and `release-readiness` deliberately does not check README coordinates, so such a PR
passes with README still on the older version.
