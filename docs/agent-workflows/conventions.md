# Shipping Conventions — musicmeta

How this repository ships work: branch model, issue closure, priority ordering, which checks map to
which changed files, and which areas are never automatable. `CLAUDE.md` says what project
documentation applies and what the code-level rules are; this file says how work gets from an issue
to a published release. It holds only facts about *this* project that cannot be inferred from the
repository.

> **Code-level rules are not restated here.** Review every diff against `CLAUDE.md` directly —
> **Code Style**, **Modeling Rules**, **Testing Patterns**, **Backwards Compatibility**, **Git
> Rules**, **Key Conventions**. A copy in this file would have no re-sync trigger: the previous one
> silently went stale on the load-bearing versioning rule within hours of being written.
>
> Where this file conflicts with `CLAUDE.md` or a doc it routes to, **the canonical doc wins.**
> Update this file rather than working around it.

---

## 1. Target & access

- **Target repo:** `famesjranko/musicmeta`. Verify the local `origin` agrees before any write.
- Use `gh` for issue/PR/label/milestone/merge operations, and plain `git` for
  checkout/branch/diff/commit/push/rebase. Confirm `gh auth status` is clean before the first write.
- Never stage unrelated user changes, never stash silently, never force-push without
  `--force-with-lease`, never use `--admin` to bypass a failing required check.

**Worktrees.** Every branch gets its own git worktree, so a run never takes over the shared checkout.

- **Location:** `../musicmeta-worktrees/<branch-slug>` — a sibling of the repo. It must stay outside the
  repo tree: Gradle scans the project directory, and a worktree placed inside becomes a second copy of
  every module.
- **What a fresh worktree is missing: nothing the build needs.** Verified — no `local.properties`
  exists, and the gitignored `secrets.properties` in the primary checkout is read only at *runtime*
  by the demo CLI (`demo/src/.../Main.kt`), never by a build script, so a worktree builds and
  compiles without it (`./gradlew run` in a worktree falls back to environment variables for API
  keys). The Android SDK is located through the `ANDROID_HOME`
  environment variable (`~/android-sdk`), which a worktree inherits from the shell. Gradle re-resolves
  dependencies into the shared user cache. So `./gradlew build` works in a fresh worktree unchanged.
- **One caveat:** `.claude/` is gitignored, so a worktree does not inherit local Claude Code permission
  settings. That affects tool prompts, not build correctness.
- The `demo/` composite build resolves core via `includeBuild("..")`, a *relative* path — it therefore
  resolves to the worktree's own copy of the library, which is what you want. Do not "fix" it to an
  absolute path.

## 2. Branch model — `two-stage`

| Stage | From → to | Merge method | Branch after |
|---|---|---|---|
| Child PR | `<area>/<issue#>` → `dev` | **squash** | delete |
| Epic PR | `epic/<slug>` → `dev` | **merge commit** | delete |
| Release PR | `dev` → `main` | **merge commit, never squash** | **keep `dev`** |

- **`dev` is the integration branch.** Never default child work to `main`.
- **`main` is the release branch.** A tag `v*` pushed to `main` triggers `.github/workflows/publish.yml`,
  which publishes to Maven Central. Merging to `main` does not itself publish — tagging does.
- After a release PR lands, fast-forward `dev` up to `main` and push, leaving both 0-ahead / 0-behind:

  ```bash
  git fetch origin && git checkout dev && git merge --ff-only origin/main && git push origin dev
  ```

- The release PR is the designated review point for the accumulated **public API diff**. Read the
  `.api` dump diff there before tagging, against `CLAUDE.md` → **Backwards Compatibility** — this is
  the gate that would have caught the v0.9.2 break.

## 3. Priority & selection

Labels: `priority/p0` … `priority/p3`, plus `area/core`, `area/android`, `area/okhttp`,
`area/provider`, `area/ci`, `area/docs`.

- `next` picks: P0 first, then P1 by issue number, then P2/P3 from the epic checklist in rank order.
  Respect `Depends on #X` — never start an issue before its dependency lands.
- **Explicit approval required before editing** for P0/P1, any feature-scope work, and anything in
  §7's danger zones regardless of priority.
- Unattended handling is for cheap, independent **P3** work only; skipping an independent review is
  for trivial **P3** form and documentation changes only. Neither ever applies to §7. These bind
  behaviour, not a flag: P2 and above get a human in the loop even when the intent looks obvious.

## 4. Issue closure

`dev` is **not** the default branch, so `closes #N` does **not** auto-close on a child merge. Closure
is manual:

1. Child PR merges into `dev` → close each linked issue immediately, state reason **`completed`**.
2. Tick the corresponding `[EPIC]` checklist item, but **keep the epic open**.
3. The epic closes on the release PR (`dev` → `main`), which carries `Closes #<epic>` and fires on the
   default branch.

Keep `closes #N` in child PR bodies and squash messages regardless — it cross-links for traceability
even when it does not fire.

## 5. Always-checks

Run on every change, whatever was touched:

```bash
./gradlew build          # assembles + tests all three modules; also runs apiCheck via `check`
git diff --check -- ':!*/api/*.api'   # trailing whitespace / conflict markers
```

> **Why the `api/*.api` exclusion.** `binary-compatibility-validator` writes its dumps ending in a
> blank line, and `apiCheck` compares byte-for-byte — stripping it to satisfy `git diff --check`
> makes the check fail instead. Without the exclusion the always-check is red on every `apiDump`
> commit, which trains reviewers to ignore it. The generated file is not hand-edited, so nothing is
> lost by excluding it.

`apiCheck` compares the public ABI against the committed `api/*.api` baselines. A failure there is
not a flaky check — it means the public API changed. Either the change was unintended and should be
reverted, or it was intended and needs `./gradlew apiDump` plus a reviewed `.api` diff in the PR.

There is **no linter configured** in this repo (no ktlint, detekt, or spotless) — do not invent one or
invoke a raw linter that the build does not pin. Kotlin compiler warnings are the only static signal;
do not add new ones.

> **Gradle honesty guard.** Gradle reports `UP-TO-DATE` and silently skips a test task when nothing it
> depends on changed. A green `BUILD SUCCESSFUL` is therefore **not** evidence that tests ran. When
> reporting verification evidence, either pass `--rerun-tasks`, or read the actual counts out of
> `*/build/test-results/**/*.xml`. Never report a suite as passing on the strength of an `UP-TO-DATE`
> line.

## 6. Verification surface

| Changed files | Verification |
|---|---|
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/*.kt` (**public API**) | `./gradlew apiCheck` + read the `.api` diff against `CLAUDE.md` → **Backwards Compatibility**; **plus** the demo canary below |
| `musicmeta-core/**` (anything) | `./gradlew :musicmeta-core:test` |
| Any `api/*.api` baseline | The `.api` diff **is** the review. A change here means the public ABI changed — confirm it was intended, and that `CLAUDE.md`'s append-at-the-end rule was followed — including its caveat that appending is the source-compatible floor, not an ABI guarantee |
| `musicmeta-core/**/provider/<name>/**` | `:musicmeta-core:test --tests "*<Name>*"`, then the full core suite. E2E is a deferred manual proof surface — see below |
| `musicmeta-android/**` | `./gradlew :musicmeta-android:test` (needs `ANDROID_HOME`; set to `~/android-sdk`) **plus** `apiCheck` — this module is published too, and its baseline is `musicmeta-android/api/` |
| `musicmeta-android/**/cache/**` (Room) | Android tests **plus** an explicit schema/migration review — persisted user data |
| `musicmeta-okhttp/**` | `./gradlew :musicmeta-okhttp:test` **plus** `apiCheck` — published module, baseline in `musicmeta-okhttp/api/` |
| Any public API change | **`./gradlew apiCheck` and read the `.api` diff — this is the guard for parameter position.** Then `cd demo && ../gradlew compileKotlin`: `demo/` is a separate composite build (`includeBuild("..")`) that `./gradlew build` never compiles, so it is the only in-tree consumer compiling against the published surface. It is **not** a position guard — Kotlin rebinds positional arguments silently, so a mid-list insertion carrying a default fails to compile only if it also produces a type error. A green canary means consumers compile, not that order is unchanged (see `CLAUDE.md`) |
| `gradle/libs.versions.toml`, any `build.gradle.kts` | full `./gradlew build`, and confirm the version catalog is the only place versions changed |
| `.github/workflows/**` | Review against §7 — release machinery. Never merge on assumption; state what was and was not exercised |
| Docs only | always-checks only |

**E2E tests are never gating.** The 43 tests under `e2e/` hit real third-party APIs (MusicBrainz,
Deezer, Last.fm, ListenBrainz) behind `-Dinclude.e2e=true`. They are a separate, maintainer-run proof
surface that this loop neither drives nor gates on — third-party rate limits and outages must never be
able to block a merge. When provider work would benefit from e2e proof, declare it in the PR body as a
deferred proof surface rather than running it as a gate.

## 7. Danger zones

These areas always need explicit approval and a full independent review, at any priority. Never
handle them unattended, and never abbreviate the review:

- **Public API surface** — `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/*.kt`. The
  backwards-compat boundary; consumers exist on Maven Central and JitPack.
- **Anything `@Serializable`** — `EnrichmentData` subtypes, `EnrichmentIdentifiers`. Changing field
  order or names breaks persisted JSON in consumers' caches, not just compilation.
- **Room cache** — `musicmeta-android/**/cache/**`. Entities and migrations are user data on device.
- **Release machinery** — `.github/workflows/**`, publishing blocks in `build.gradle.kts`, version
  properties, anything that changes what reaches Maven Central.
- **Provider credentials** — API-key handling, User-Agent construction, auth headers.

## 8. Commit & docs

Commit format — conventional commits, scope by area, no co-author trailer, no AI attribution:

```
feat(core): add TrackPreview accessor (closes #12)
fix(provider): handle null Discogs release year (closes #14)
```

Doc-update triggers:

| Change | Update |
|---|---|
| Architectural decision or completed milestone | `STORIES.md` — the *why* |
| Any feature or bugfix | `CHANGELOG.md` (Keep a Changelog) |
| **Breaking public API change** | `CHANGELOG.md` under a `### Breaking Changes` heading, and flag it to the user before proceeding |
| Coverage / gap / milestone shift | `ROADMAP.md` |
| New provider | `docs/providers/<name>.md` + the provider README table |
| New public capability | the relevant `docs/guides/*.md` |
| **Cutting a release** (see the checklist below) | `version` in all three `build.gradle.kts`, the `CHANGELOG.md` heading, `ROADMAP.md` "Where We Are", the `README.md` install snippets |

**Release checklist.** Nothing in CI enforces version consistency — `publish.yml` derives no version
from the tag, it publishes whatever the build files declare. A `v0.10.0` tag against `version = "0.9.2"`
uploads GAV `0.9.2`, which Central rejects as a duplicate, leaving an immutable tag for a version that
was never published. So:

1. **On `dev`, before merging the release PR** — bump `version` in `musicmeta-core`,
   `musicmeta-android` and `musicmeta-okhttp` `build.gradle.kts`; pin the `CHANGELOG.md`
   `[Unreleased]` heading to the version and date, and open a fresh empty `[Unreleased]` above it;
   update `ROADMAP.md` "Where We Are"; update the `README.md` install snippets — **both** the Maven
   Central and the JitPack coordinates. Choose the number against the 0.x carve-out in `CLAUDE.md`:
   a patch may **not** carry an API break.

   The README bump belongs here, in one commit with the rest, because that is what the repo has
   always done and because splitting it costs an extra PR and an extra sync to guard against a
   documentation inconsistency lasting only until step 2. See #13 for automating the version half.
2. **Merge, then tag promptly.** Merging does not publish; the `v*` tag does. Between the two, the
   README on `main` names a version that is not yet on Central — which is the reason step 3 says
   *promptly*, and the reason to avoid merging a release you are not ready to tag.
3. **After the tag** — confirm `publish.yml` went green and the artifacts resolve from Central.

> **If tagging is deliberately deferred** — the release merges but is not published yet — leave the
> `README.md` snippets at the previous version instead, since they would otherwise document an
> artifact nobody can resolve. Bump them when you tag, as a docs-only PR into `main` (the one
> sanctioned exception to §2's "child work never targets `main`"), then re-run §2's
> `git merge --ff-only origin/main` so `dev` picks the commit up. Note `main` accepts **merge commits
> only** — its ruleset forbids squash, so §2's squash rule for child PRs does not apply to it — and
> `build` is a required context there, so even a docs-only PR must go green on a full `./gradlew build`.
