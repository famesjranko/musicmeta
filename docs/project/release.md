# Release Workflow — musicmeta

This document is authoritative for preparing, merging, tagging, and verifying a musicmeta release.
Branch topology and ordinary issue handling live in [workflow.md](workflow.md); version compatibility
rules live in `CLAUDE.md` → **Backwards Compatibility**.

## Release boundary

- Release PR: `dev` → `main`, merge commit only; keep `dev`.
- A `v*` tag on `main` triggers `.github/workflows/publish.yml` and publishes the versions declared
  by the three modules. Merging without tagging does not publish.
- The release PR is the review point for the accumulated public API diff.

## Prepare on `dev`

1. Choose the version under the `0.x` compatibility policy in `CLAUDE.md`. A patch release cannot
   contain a breaking public API change.
2. Set the same version in the core, Android, and OkHttp `build.gradle.kts` files.
3. Pin the current `CHANGELOG.md` `[Unreleased]` section to that version and date, then add a new empty
   `[Unreleased]` section above it. Put every intentional API break under `### Breaking Changes`.
4. Update `ROADMAP.md` “Where We Are” and both Maven Central and JitPack coordinates in `README.md`.
5. Run the workflow verification surface, review the committed `api/*.api` diff, and confirm the demo
   composite build still compiles.

Nothing in CI derives or reconciles module versions from the tag. A mismatched tag attempts to
publish the build-file version and can leave an immutable tag for an artifact that never shipped.

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
5. Confirm `publish.yml` succeeds and the artifacts resolve from Maven Central.

## Deliberately deferred tagging

If publication is intentionally deferred, leave README installation snippets at the previous
resolvable version. Update them through a docs-only PR into `main` when tagging, then fast-forward
`dev` again. `main` still requires a merge commit and its required build check even for that exception.
