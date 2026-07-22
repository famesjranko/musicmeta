# Release Workflow — musicmeta

This document is authoritative for preparing, merging, tagging, and verifying a musicmeta release.
Branch topology and ordinary issue handling live in [workflow.md](workflow.md); version compatibility
rules live in `CLAUDE.md` → **Backwards Compatibility**.

## Release boundary

- Release PR: `dev` → `main`, merge commit only; keep `dev`.
- A `v*` tag on `main` triggers `.github/workflows/publish.yml` and publishes the version declared in
  root `gradle.properties` and inherited by the three modules. Merging without tagging does not publish.
- The release PR is the review point for the accumulated public API diff.
- On the release PR, `build.yml`'s `release-readiness` check asserts the three module versions match
  each other and the pinned `CHANGELOG` heading before any tag exists. A red run means step 2 or 3
  of *Prepare on `dev`* is wrong; fix it before tagging (issue #35).

## Prepare on `dev`

1. Choose the version under the `0.x` compatibility policy in `CLAUDE.md`. A patch release cannot
   contain a breaking public API change.
2. Set the release version in one place — `version` in the root `gradle.properties`; all three modules inherit it.
3. Pin the current `CHANGELOG.md` `[Unreleased]` section to that version and date, then add a new empty
   `[Unreleased]` section above it. Put every intentional API break under `### Breaking Changes`.
4. Update `ROADMAP.md` “Where We Are” and both Maven Central and JitPack coordinates in `README.md`.
5. Run the workflow verification surface, review the committed `api/*.api` diff, and confirm the demo
   composite build still compiles.

CI still derives no version from the tag — it publishes what the build declares (now a single
`gradle.properties` value) — but `publish.yml` asserts the tag matches all three modules before running anything else, so a
mismatch fails the run instead of leaving an immutable tag for an artifact that never shipped.
Getting step 2 wrong is now a red publish, not a tag you have to delete and re-push.

## Land and publish

1. Open `dev` → `main` with `Closes #<epic>` and the release evidence.
2. Require the current-head checks and merge with a merge commit, never squash.
3. Fast-forward `dev` to the resulting `main` head and push it:

   ```bash
   git fetch origin
   git checkout dev
   git merge --ff-only origin/main
   git push origin dev
   ```

4. Tag the merged `main` commit promptly with `v<version>` and push the tag.
5. Confirm `publish.yml` succeeds — it uploads, signs and validates the deployment.
6. Release the deployment in the Central Portal. The build sets no `automaticRelease`, so publish
   leaves it validated-but-unpublished by design (a human gate). Release it at central.sonatype.com →
   Deployments; the artifacts resolve from Maven Central ~15–30 min later.
7. Create the GitHub Release for `v<version>` — see **Release notes** below.

## Release notes

Keep the GitHub Release **concise and skimmable**. **Do not paste the `CHANGELOG.md` section
verbatim** — the changelog's per-entry rationale belongs in the changelog and `STORIES.md`, and a
verbatim dump produces the 6–9k-char walls v0.10.0 and v0.10.1 first shipped with (both since
rewritten). The release note is the summary; the changelog is one click away for the depth.

Structure (matches v0.8–v0.9):

- A one-line summary of the release.
- One-line bullets under `### Added` / `### Changed` / `### Fixed`, each with its `#issue` ref.
- `### Breaking Changes` only when the ABI changed — the one section that stays detailed, since a
  consumer must read it before upgrading.
- An `## Installation` block: Maven Central and JitPack coordinates at the new version.
- A `**Full Changelog**` compare link: `.../compare/v<prev>...v<new>`.

Create it from a notes file (not a heredoc of the changelog):

```bash
gh release create v<version> --title "v<version>" --notes-file notes.md --latest --verify-tag
```

## Deliberately deferred tagging

If publication is intentionally deferred, leave README installation snippets at the previous
resolvable version. Update them through a docs-only PR into `main` when tagging, then fast-forward
`dev` again. `main` still requires a merge commit and its required build check even for that exception.
