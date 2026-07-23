# Shipping Workflow — musicmeta

This document is authoritative for branch topology, work isolation, issue lifecycle, work selection,
and verification selection. `CLAUDE.md` owns code and API constraints; [release.md](release.md) owns
release preparation, tagging, and publication. These documents have distinct ownership and must not
restate one another.

## Work isolation

Use a dedicated worktree for every issue, epic, or PR branch so concurrent work never takes over the
shared checkout.

- Put worktrees at `../musicmeta-worktrees/<branch-slug>`, outside the repository tree. Gradle scans
  the project directory, so an in-tree worktree looks like a second copy of every module.
- A fresh worktree needs no copied build inputs. `secrets.properties` is runtime-only, Android uses
  `ANDROID_HOME`, and Gradle dependencies resolve through the shared user cache.
- The `demo/` composite build uses `includeBuild("..")`; inside a worktree it correctly resolves that
  worktree's library. Do not replace the relative path with an absolute one.
- `.claude/settings.json` and `.claude/rules/` are tracked, so the format-on-write hook reaches
  every worktree. Only `.claude/settings.local.json` is ignored, and it holds local tool
  permissions — its absence affects prompting, not build correctness.
- A fresh worktree needs the pinned tools on `PATH`. They install to `~/.local/bin`, which is shared
  across worktrees, so `./scripts/bootstrap.sh` is a once-per-machine step, not once per worktree.

## Branch topology

`dev` is the integration branch. `main` is the release branch.

| Stage | From → to | Landing | Branch after |
|---|---|---|---|
| Independent child | `<area>/<issue#>` → `dev` | Squash PR | Delete |
| Epic child | Directly on `epic/<slug>` | Verified commit + push; no child PR | Keep |
| Epic consolidation | `epic/<slug>` → `dev` | Merge-commit PR; never squash | Delete |
| Release | `dev` → `main` | Merge-commit PR; never squash | Keep `dev` |

Independent children use their own PRs. Use the epic model only when children form one coherent
feature that should be reviewed together.

### Shared-branch epics

- Advance exactly one child per run on `epic/<slug>`.
- Keep the tracked handoff at `docs/project/worklogs/<slug>.md`; append the child, decisions,
  verification evidence, deferrals, and next child before committing.
- Local verification is the child landing gate because there is no child PR or child CI run.
- Push the implementation and worklog together, then confirm the remote SHA before bookkeeping.
- Finalize through one clearly identified epic PR into `dev`. That PR belongs to the epic workflow,
  not ordinary PR processing, and must land with a merge commit.

## Selection

Labels are `priority/p0` through `priority/p3`, plus `area/core`, `area/android`, `area/okhttp`,
`area/provider`, `area/ci`, and `area/docs`.

For `next`, take P0 first, then P1 by issue number, then P2/P3 in epic rank order. Always respect
`Depends on #N`; a blocked issue is not next regardless of priority.

**Explicit approval is required before editing** for P0/P1, any feature-scope work, and anything
under [High-risk surfaces](#high-risk-surfaces) regardless of priority.

Unattended handling is for cheap, independent **P3** work only. Skipping an independent review is
for trivial **P3** form and documentation changes only. Neither ever applies to a high-risk surface.
These bind behaviour, not a flag: **P2 and above get a human in the loop even when the intent looks
obvious.**

## Issue lifecycle

`dev` is the default branch, so a closing keyword on a PR merged into `dev` closes its issue
automatically. `main` is not the default branch: a keyword on the `dev` → `main` release PR closes
nothing, so nothing may depend on one.

Work that never opens a PR still needs closing by hand — that is the epic-child case below, not an
oversight.

### Independent children

1. Merge the child PR into `dev` with `Closes #<issue>` in its body.
2. Confirm the issue actually closed. The keyword fires on merge, but a typo or a retarget is
   silent, and an issue left open is easier to miss than one closed twice.
3. Tick its epic checklist item while keeping the epic open.

### Shared-branch epic children

These land as a direct push to `epic/<slug>` with no PR of their own, so no keyword can fire and
the transition is manual.

1. Push the verified child commit and its worklog entry to `epic/<slug>`.
2. Confirm the remote SHA, then close the child as `completed` and tick its checklist item.
3. Keep the epic open. If the unintegrated epic is explicitly abandoned, reopen every child closed
   at this push-time boundary and repair the checklist.

### The epic itself

The epic closes through `Closes #<epic>` on the **epic consolidation PR into `dev`**, because that
is the PR that reaches the default branch. Do not put it on the release PR into `main` — see
[release.md](release.md).

## Verification

Run these for every change:

```bash
./check
git diff --check -- ':!*/api/*.api'
```

`./check` is the whole verification surface and is exactly what CI runs — formatter, linter, type
checker and custom linter across Kotlin, Python and shell, then the build and the demo canary. Use
`./check --fast` during the edit loop, never as the evidence for a push.
[ARCHITECTURE.md](../../ARCHITECTURE.md) records which rules it enforces and which are only
intended.

The API dump exclusion is intentional: its generator writes a trailing blank line that `apiCheck`
compares byte-for-byte. Do not hand-edit generated dumps to satisfy whitespace checking.

Gradle may report `UP-TO-DATE` without executing tests. Verification evidence must either use
`--rerun-tasks` or report test counts from `*/build/test-results/**/*.xml`.

| Changed surface | Additional evidence |
|---|---|
| `musicmeta-core/**` | `./gradlew :musicmeta-core:test` |
| Public core API or any `api/*.api` | `./gradlew apiCheck`, review the API diff against `CLAUDE.md` → **Backwards Compatibility**, then `cd demo && ../gradlew compileKotlin` |
| `provider/<name>/**` | Matching provider tests, then the full core suite |
| `musicmeta-android/**` | `ANDROID_HOME=~/Android/Sdk ./gradlew :musicmeta-android:test` plus `apiCheck` |
| Android Room cache | Android tests plus explicit schema and migration review |
| `musicmeta-okhttp/**` | `./gradlew :musicmeta-okhttp:test` plus `apiCheck` |
| Build logic or version catalog | Full build; confirm dependency versions changed only in the catalog |
| `.github/workflows/**` | Syntax validation plus an honest statement of behavior not exercised locally |
| Documentation only | The two always-checks |

E2E tests hit live third-party APIs and are never merge-gating. Record useful E2E coverage as a
deferred maintainer-run proof surface rather than allowing rate limits or outages to block a PR.

## High-risk surfaces

These always need **explicit approval before editing** and a **full independent** review, at any
priority. Never handle them unattended, never abbreviate the review, and always run the complete
mapped verification:

- Public API and committed API baselines — `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/*.kt`
  and every `api/*.api`. The backwards-compatibility boundary; consumers exist on Maven Central and JitPack.
- `@Serializable` payloads — `EnrichmentData` subtypes, `EnrichmentIdentifiers`. Changing field order
  or names breaks persisted JSON in consumers' caches, not just compilation.
- Android Room entities and migrations — `musicmeta-android/**/cache/**`. User data on device.
- Release workflows, publishing configuration, and versions — `.github/workflows/**`, publishing
  blocks in `build.gradle.kts`, anything that changes what reaches Maven Central.
- Provider credentials, authentication headers, and User-Agent construction.

## Commit and documentation routing

Use conventional commits scoped by area, for example `fix(provider): handle null release year`.
Follow the no-attribution and destructive-Git rules in `CLAUDE.md`.

| Change | Update |
|---|---|
| New or changed gate, or a rule that gains/loses a mechanism | `ARCHITECTURE.md` — the *Enforced by* table or the *Not enforced* list |
| Architectural decision or completed milestone | `STORIES.md` |
| Feature or bug fix | `CHANGELOG.md` |
| Breaking public API change | `CHANGELOG.md` under `### Breaking Changes` |
| Coverage, gap, or milestone shift | `ROADMAP.md` |
| New provider | `docs/providers/<name>.md` and provider index |
| New public capability | Relevant `docs/guides/*.md` |
| Release preparation or publication | [release.md](release.md) |
