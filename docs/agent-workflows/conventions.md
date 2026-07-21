# Workflow Contract — musicmeta

The project-specific half of the `ship-issue` and `ship-pr` procedures: branch model, issue closure,
priority ordering, which checks map to which changed files, and which areas are never automatable.
Those skills supply the procedure; this file supplies the project.

> When this contract conflicts with the project's canonical docs (`CLAUDE.md` / `AGENTS.md` or a doc
> they route to), **the canonical doc wins.** Update this file rather than working around it.

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
  `.api` dump diff there before tagging (see §9) — this is the gate that would have caught the v0.9.2
  break.

## 3. Priority & selection

Labels: `priority/p0` … `priority/p3`, plus `area/core`, `area/android`, `area/okhttp`,
`area/provider`, `area/ci`, `area/docs`.

- `next` picks: P0 first, then P1 by issue number, then P2/P3 from the epic checklist in rank order.
  Respect `Depends on #X` — never start an issue before its dependency lands.
- **Explicit approval required before editing** for P0/P1 and any feature-scope work.
- `--grind` (autonomous) — cheap, independent **P3** nits only.
- `--no-review` (skip adversarial review) — trivial **P3** form/documentation work only.
- Both flags are rejected outright in the danger zones of §7, regardless of priority.

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
| `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/*.kt` (**public API**) | `./gradlew apiCheck` + read the `.api` diff against §9 backwards-compat rules; **plus** the demo canary below |
| `musicmeta-core/**` (anything) | `./gradlew :musicmeta-core:test` |
| Any `api/*.api` baseline | The `.api` diff **is** the review. A change here means the public ABI changed — confirm it was intended, and that §9's append-at-the-end rule was followed |
| `musicmeta-core/**/provider/<name>/**` | `:musicmeta-core:test --tests "*<Name>*"`, then the full core suite. E2E is a deferred manual proof surface — see below |
| `musicmeta-android/**` | `./gradlew :musicmeta-android:test` (needs `ANDROID_HOME`; set to `~/android-sdk`) **plus** `apiCheck` — this module is published too, and its baseline is `musicmeta-android/api/` |
| `musicmeta-android/**/cache/**` (Room) | Android tests **plus** an explicit schema/migration review — persisted user data |
| `musicmeta-okhttp/**` | `./gradlew :musicmeta-okhttp:test` **plus** `apiCheck` — published module, baseline in `musicmeta-okhttp/api/` |
| Any public API change | `cd demo && ../gradlew compileKotlin` — the **positional-caller canary**; `demo/` is a separate composite build (`includeBuild("..")`) that `./gradlew build` never compiles |
| `gradle/libs.versions.toml`, any `build.gradle.kts` | full `./gradlew build`, and confirm the version catalog is the only place versions changed |
| `.github/workflows/**` | Review against §7 — release machinery. Never merge on assumption; state what was and was not exercised |
| Docs only | always-checks only |

**E2E tests are never gating.** The 43 tests under `e2e/` hit real third-party APIs (MusicBrainz,
Deezer, Last.fm, ListenBrainz) behind `-Dinclude.e2e=true`. They are a separate, maintainer-run proof
surface that this loop neither drives nor gates on — third-party rate limits and outages must never be
able to block a merge. When provider work would benefit from e2e proof, declare it in the PR body as a
deferred proof surface rather than running it as a gate.

## 7. Danger zones

`--grind` and `--no-review` are **always rejected** here, at any priority:

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

## 9. Invariants

Checked by the adversarial reviewer subagent against every diff. Sourced from `CLAUDE.md` — that file
remains authoritative.

**Backwards compatibility (the load-bearing one).** The library is published to Maven Central and
JitPack; assume external consumers exist.

- No breaking changes to public API without a major version bump. Breaking = removing/renaming public
  classes, functions, or parameters; changing return types; reordering non-named parameters; changing
  enum or sealed-class variants consumers may match on.
- **Append new parameters at the end with a default — never insert mid-list.** A mid-list insertion
  silently re-binds every positional argument after it. This is not hypothetical: it shipped in v0.9.2
  (a patch release) and broke `demo/`, and the same mistake is present in v0.5.0, v0.7.0 and v0.9.0.
- Prefer new overloads or default parameters over modifying existing signatures.
- Deprecate before removing — `@Deprecated` with `ReplaceWith`, kept for at least one minor release.
- Internal code (`internal` visibility, `provider/*/` internals, `http/` infrastructure) may change
  freely — and as of 2026-07-21 (issue #5) this is enforced by visibility, not just aspiration. The
  `*Api`/`*Mapper`/`*Models` behind each provider, `MusicBrainzParser`, `http/CircuitBreaker`, and the
  `engine/` mergers/synthesizers are `internal` and absent from the `.api` baselines, so a refactor
  confined to them no longer trips `apiCheck` or needs an `apiDump` commit. The public surface is the
  `*Provider` classes, `HttpClient`/`HttpResult`/`HttpResponse`/`DefaultHttpClient`/`RateLimiter`, and
  the `ResultMerger`/`CompositeSynthesizer` extension-point interfaces. `RateLimiter` stays public
  deliberately — it is a parameter of nearly every public `*Provider` constructor. Engine wiring left
  public (`ProviderRegistry`, `ProviderChain`, `DefaultEnrichmentEngine`, `ArtistMatcher`,
  `ConfidenceCalculator`) is a candidate for a later pass. The one exception forced here:
  `ProviderChain`'s constructor became `internal` because its default `circuitBreakers` parameter
  referenced the now-internal `CircuitBreaker`; the class stays public (reachable via `chainFor()`).

**Code style.** No `!!` — handle nullability properly. Files 200 lines target / 300 max; functions 20
lines target / 40 max. Pure functions where possible. Explicit over implicit.

**Modeling.** Enums for fixed sets, never string constants. Sealed classes for variants with different
data, each variant a data class. Interfaces for contracts and strategies. Data classes over
Pair/Triple/Map for structured fields. `@Serializable` only on public API payload types — never on
provider models or infrastructure.

**Testing.** Fakes over mocks (`testutil/`: `FakeProvider`, `FakeHttpClient`, `FakeEnrichmentCache`).
`runTest` for coroutines. Backtick test names. Given-When-Then structure with comments that state what
is given, what action is taken, and what outcome is expected — not bare section markers.

**Conventions.** Java 17. Dependencies via `gradle/libs.versions.toml` only. `org.json` for parsing,
`kotlinx.serialization` for serialization. MusicBrainz requires a descriptive User-Agent.

**Git.** No `Co-Authored-By`, no "Generated with Claude", no AI or tool attribution in any commit, PR,
issue comment, or anything else that leaves this machine. Never use `git revert` — use `git reset` or
manual edits. Always ask permission before destructive git commands.
