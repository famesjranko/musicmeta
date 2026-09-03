# VERIFICATION

What `./check` runs, and the gaps in it worth knowing about. **This is not a complete inventory of
the project's invariants and does not try to be.** Most conventions are review's job, and that is
fine. How the system itself is put together is `ARCHITECTURE.md`.

The check command is the authority. Where a document and a config disagree, the config wins,
because the config is the thing that fails.

```bash
./scripts/bootstrap.sh   # once: installs the pinned tools ./check requires
./check                  # everything — `check`'s header lists the flags and what each skips
```

## What `./check` runs

| Step | Tool | Covers |
|---|---|---|
| Python format and lint | ruff | `scripts/**` |
| Python types | mypy | `scripts/**` |
| Shell | shellcheck | `scripts/**`, `check`, `demo-cli/run.sh`, `demo-web/run.sh` |
| Conventions | `scripts/checks/check_conventions.py` | no `!!` and no `@Serializable` under `provider/`/`http/` in main sources; only `*Provider` public under `provider/` in the committed `api/*.api`; conflict markers anywhere |
| Pitfall citations | `scripts/checks/check_pitfall_citations.py` | every `§N` reference to `docs/pitfalls.md` resolves to a `## N.` heading in it — catches a renumbered or deleted section orphaning its citers silently |
| Provider call scope | `scripts/checks/check_provider_call_scope.py` | every `provider/<name>/` directory with a `*Provider.kt` mentions `ProviderCallScope` somewhere in the directory, or is named in the script's own allowlist with a reason; plain substring match, so it proves the mention exists, not that the memo is correct or reached — the per-provider request-count tests and `ProviderMemoLifetimeTest` cover that |
| Public vocabulary | `scripts/checks/check_public_vocabulary.py` | no upstream's word for a concept this library already names — `recording`, `release-group`, `master` — appears in a public identifier in the committed `api/*.api` unless a provider's name is attached to it or to its enclosing type. `docs/glossary.md` holds the mapping and the rule. It reads names, never meaning: a public `albumId` holding a release id passes, and so does a name that is simply wrong rather than borrowed. `collection` is not banned — erased JVM descriptors put `Ljava/util/Collection;` on a large share of lines |
| Test shape | `scripts/checks/check_test_shape.py` | every `@Test` body has `// Given -`/`// When -`/`// Then -`, each on its own line with a plain hyphen and a real clause — Kotlin test sources only, on both the `check` gate and the `format-on-write.sh` hook |
| Release-note caps | `build_release_notes.py Unreleased` | `CHANGELOG.md`'s `[Unreleased]` stays under 48000 chars and 200 per line — the same `check_caps()` the release runs, so it fails here rather than at release prep. An empty section passes: `pin_release.py` opens one on every release branch |
| Release coordinates | `scripts/checks/check_release_coordinates.py` | every version-bearing line outside `CHANGELOG.md` equals the version `gradle.properties` declares: `ROADMAP.md`'s `## Where We Are` block and its `### Unreleased` subsection, and the `musicmeta-*` coordinates in `README.md` and the three `docs/guides/`. Regions are found by heading, so renaming one fails the check rather than silently guarding nothing. Versions elsewhere in `ROADMAP.md` name the release a capability landed in and are left alone. It reads a version, never a claim: nothing checks that the artifact it names actually resolves |
| Script self-tests | `scripts/**/test_*.py` | discovered, not listed |
| Demo frontend | `node --test` via `demo-web/package.json` | the demo browser code's wire-protocol reading and its "is this worth retrying" decision, as the pure functions in `demo-web/src/main/resources/stream-protocol.js`. Runs on every `./check`, including `--fast`. node is a **minimum major** (`NODE_MIN_MAJOR` in `scripts/bootstrap.sh`) rather than an exact pin, and bootstrap reports it rather than installing it — it is the machine's runtime, not an executable the script can fetch and verify by digest |
| Kotlin format | ktlint (version pinned in `libs.versions.toml`) | all modules, `demo-cli/`, and `demo-web/` |
| Kotlin static analysis | detekt, **type-resolved** (`detektMain`/`detektTest`/`detektTestFixtures`) | complexity, dead code, bug patterns |
| Build | `./gradlew build` | compile, all unit tests, `apiCheck` against `api/*.api` |
| Explicit API | Kotlin's `explicitApi()` on `musicmeta-core`, `musicmeta-okhttp`, `musicmeta-android` | every declaration reaching the published surface states its visibility and its return type, or the compile fails. It is part of the compile, so it runs wherever the modules are compiled — the Build step above, CI, and anything that depends on those compile tasks, including detekt's type-resolved tasks — but **not under `./check --fast`, which exits before any of them**. What it buys a consumer: a public signature's types are readable in the `.kt` rather than reconstructed from the dump's erased descriptors. Main source sets only, and only in these three modules: `src/test` (including its `e2e` package), `src/testFixtures` and `src/androidTest` are exempt by the compiler — the last confirmed by a green `./gradlew :musicmeta-android:compileDebugAndroidTestKotlin`, since no gate compiles that source set — while the `demo-cli/`, `demo-web/`, `docs-samples/` and `docs-samples-android/` builds are unaffected because they are separate Gradle builds that never set the flag. `internal` and `private` declarations are exempt anywhere. Kotlin exempts four kinds of declaration even in main sources — primary constructors, **properties of a `data class`** (constructor and body alike), property getters and setters, and `override` members — so a `data class`'s properties carry no modifier and are checked by nothing here. It reads declarations, never intent: it forces a visibility to be written, it cannot tell that the one written is the right one |
| Consumer canary | `demo-cli/` and `demo-web/` composite builds | an external consumer still compiles, and their tests run (`demo-web/`'s 50 `ProfileMapperTest` cases; `demo-cli/` has none yet) |
| Doc samples | `scripts/checks/check_doc_samples.py` + `docs-samples/`/`docs-samples-android/` composite builds | every ```` ```kotlin ```` fence in `docs/guides/*.md` compiles against the real API, or carries a `<!-- no-compile: <reason> -->` marker with a mandatory reason; compiled per guide, in reading order, as one narrative, not one fence at a time — see "Known gaps". One extractor, two targets: every guide but `android.md` compiles as a plain JVM module, `android.md` compiles against a real `com.android.library` build (Room/Hilt/WorkManager). 61 of 75 non-`android.md` fences and 11 of 12 `android.md` fences compile today |

Gates exist beyond `./check` and this table does not list them: `main`'s branch protection lives in
`docs/project/workflow.md`, the release workflow's own verification in `docs/project/release.md`.

**A missing *or mismatched* tool fails the run — it never skips.** A gate that silently skips when
its tool is absent reports green while checking nothing, which is worse than no gate. `./check`
verifies the pinned version too: formatter output differs between releases, so an unpinned tool
reintroduces exactly the local/CI disagreement one command is supposed to remove.

Format-on-write (`scripts/format-on-write.sh`, wired in `.claude/settings.json`) is a convenience,
not a gate. It runs ktlint on `.kt`/`.kts` and ruff on `.py`, and no-ops when either CLI is absent;
`ktlintCheck` and the ruff check are what actually fail. It also no-ops when the `ktlint` on `PATH`
is not the `ktlint-cli` version pinned in `libs.versions.toml` — a CLI running a different rule set
writes formatting the gate never asked for, and nothing fails to say so. It does not skip
`demo-cli/` or `demo-web/`: formatting is shared with the parent build even though house
conventions are not.

## Known gaps

Not an audit of everything unenforced — these are the specific places where a green run means less
than it looks like, each learned the hard way.

- **CI's `demo-canary` job compiles and tests the demos; it does not lint them.** It runs
  `../gradlew compileKotlin test` in each demo, while `make check` also runs the demos' ktlint.
  A demo style violation therefore merges green and breaks `make check` on `main` for whoever
  pulls next — PR #285 did exactly that, fixed by #290. A green `demo-canary` is not evidence the
  demo tree passes `make check`.

- **`!!` on a Java platform type is invisible to detekt.** Measured with a three-cell probe: detekt
  catches `!!` on a nullable receiver (`UnsafeCallOnNullableType`) and on a definitely-non-null one
  (`UnnecessaryNotNullOperator`), and catches **neither** on `System.getProperty("x")!!`, because
  the rule tests for `TypeNullability.NULLABLE` and a flexible type is not that. That is the whole
  reason `check_conventions.py` still bans the operator textually.
- **The `!!` and `@Serializable` bans do not skip comments or string literals.** Deliberate: making
  them skip comments is what previously cost a 155-line hand-written Kotlin scanner, a 118-line
  `KotlinLexer` oracle and a 337-line differential test. There are no such comments in the tree. If
  one is ever needed, reword it.
- **Nothing checks `docs/providers.md`.** Two mechanisms for it were built and both were deleted: a
  Kotlin-parsing Python script, then a unit test comparing a per-capability table against each
  package's runtime `capabilities`. The test worked — 8/8 mutations killed, including a Gradle
  up-to-date hole it exposed — and was cut anyway, as too much standing machinery for one column of
  prose. `git log -S ProviderFeatureDocsTest` has it if the judgement changes. The tables it checked
  went with it; what the doc kept is what no compiler or test can see. It states its own scope and
  the date it was last hand-verified — that date is the only warranty.
- **Nothing checks core's dependency list.** `ARCHITECTURE.md`'s "core is dependency-minimal JVM"
  is held by review alone: adding one compiles, passes every test, and moves no `api/*.api` line,
  because a transitive is not part of the ABI. A check that parsed `musicmeta-core/build.gradle.kts`
  was built and deleted — the build script is not where the invariant lives. The published POM is,
  and `./gradlew :musicmeta-core:generatePomFileForMavenPublication` already writes a fourth
  dependency, `kotlin-stdlib`, that no build script declares, so a parse can report "three, all
  allowed" while the artifact a consumer resolves disagrees. A baseline of that POM, diffed the way
  `api/*.api` is, would enforce it exactly — `.scratch/core-dependency-pom-baseline/`. Nothing reads
  a dependency's *version* either, in any module: moving one compiles, passes every test and moves
  no `api/*.api` line, and `.github/dependabot.yml` now deliberately does not offer those bumps, so
  review is the only reader left.
- **Nothing verifies that a security PR still arrives.** `.github/dependabot.yml` ignores the
  consumer-facing dependencies for version updates only. Upstream asserts that a security update
  ignores those conditions — `dependabot-core`'s `silent/tests/testdata/su-err-all-versions-ignored`
  expects a PR to be raised with every version ignored — but GitHub's published reference states the
  opposite, that `ignore` suppresses security updates too. So the behaviour is what the tool tests
  today, not what its documentation promises, and nothing here would notice if it changed: the
  failure is silent and looks exactly like having no vulnerabilities.
- **`minSdk = 21` is untestable.** Robolectric 4.16 removed SDK 21 and 22 — `L` and `LMR1` are gone
  from its `DefaultSdkProvider`, present at 4.13 — so no Robolectric test can pin the floor the
  library declares. The three cache tests pin `sdk = [34]`, and `androidTest/` runs nowhere, so
  nothing exercises API 21 and nothing reports that it does not.
- **A Robolectric bump moves the SQL engine under the cache tests.** `sqliteMode` defaults to
  `NATIVE`, so `MigrationTestHelper`, Room's schema validator and every DAO query run against
  Robolectric's bundled `nativeruntime-dist-compat` — 1.0.12 at 4.13, 1.0.18 at 4.16.1. With no
  connected test, that engine is the whole evidence base for the cache schema: a green suite after a
  bump proves the migrations pass on the new engine, not that it still agrees with a device's.
- **No test or skip count is recoverable from CI.** Gradle's `Test` task prints nothing on success
  and `build.yml` uploads reports `if: failure()` only, so no run on any branch can show that a suite
  ran rather than passed by producing no runnable methods. It is inferable — the task is neither
  `UP-TO-DATE` nor `NO-SOURCE` — never stated.
- **No connected Android test runs anywhere.** `musicmeta-android/src/androidTest/` is absent from
  `check`, from the `Makefile` and from every workflow in `.github/workflows/`, so the two
  device suites — the only thing that exercises a Room migration against framework SQLite — have
  never gated a merge and are not meant to. Between them they exercise every registered migration —
  `MIGRATION_1_2`, `2_3`, `3_4` and `4_5` each on its own, and `1` to `5` as one walk — and each
  suite has one case that opens its migrated file through `EnrichmentCacheDatabase.create`. The
  Robolectric suite in `src/test/` has a case per migration too and runs on every `./check`, so it
  proves the SQL; what it cannot prove is the platform an installed app actually upgrades on. That
  evidence is a hand run on a named device, and is only ever evidence for a commit that states it
  (`CLAUDE.md`), because a migration is the one change here that reverting the code does not undo.
- **detekt is not in `--fast`.** The typed tasks compile before they analyse and the Android
  variants need `ANDROID_HOME`. The edit loop is ktlint plus the conventions check; detekt runs on
  every push and in CI.
- **Type resolution in detekt is EXPERIMENTAL**, and so is every alternative in 1.23.x — hand-wiring
  `classpath`, the CLI flags, the compiler plugin. Accepted: the stable task does not run the rules
  this exists for. detekt 1.23.8 is built against Kotlin 2.0.21 / AGP 8.8.1 while this repo runs
  Kotlin 2.1.0 / AGP 8.7.3, so a detekt or AGP bump needs all three modules' tasks re-run, not just
  core's.
- **Serialization tests round-trip the same version.** They encode and decode with the code in the
  tree, so they cannot detect a payload change breaking data a consumer already persisted — the
  failure that broke every Room cache entry in v0.4.0. Goldens from the last published version are
  the fix and are not written yet.
- **Cancellation handling is enforced by behaviour, not by a rule.**
  `ProviderChainCancellationTest` pins that a cancelled call records no circuit-breaker failure and
  that a *foreign* `CancellationException` stays contained as one provider's error. A textual rule
  was written for this and deleted: it could not see the fallback-returning catches that were the
  actual bugs, and the remediation it printed (`catch (CancellationException) { throw e }`) was
  itself the defect. `CacheGuard` and `StrategyGuard` carried that blanket form until #61 and now
  match; `EnrichCacheFailureTest` and `EnrichStrategyFailureTest` pin both directions for them.
- **14 of 75 non-`android.md` doc-sample fences, and 1 of 12 `android.md` fences, are opted out, not
  compiled.** Down from 66 of 87 under this check's first version, which compiled each fence alone —
  a guide reads as one running narrative, and a fence forty lines down routinely assumed a `val` an
  earlier fence in the same guide declared. The second version compiles each guide as one
  accumulating `narrative()` instead, with a small mechanism-owned prelude
  (`docs-samples/src/main/kotlin/doc/samples/prelude/Prelude.kt`) supplying the handful of names
  ("your existing `OkHttpClient`", a default `engine`) more than one guide assumes without ever
  declaring. `android.md` gets its own Android-flavoured prelude
  (`docs-samples-android/src/main/kotlin/doc/samples/android/prelude/AndroidPrelude.kt`) for the
  same reason: a `Context`, an already-batched `albumIds`, a domain `AlbumRepository` the guide
  never defines. What is left opted out is genuinely elided pseudo-code (`/* ... */`, an undefined
  helper like `mapError`), a Gradle build-script fragment, another library's API neither target wires
  in (JUnit, `android.util.Log`), or a fence that shows two alternative values under one name and is
  not meant to compile as one program. A green run proves the 61 JVM-target fences and 11
  Android-target fences match the API as the guide actually reads, start to finish; it says nothing
  about the other 15 — still a human's job.
- **Bash-written Kotlin is not formatted on write.** The hook only sees files an `Edit`/`Write`
  payload names. Sweeping everything dirty at end of turn was built and deleted: it reformats
  uncommitted work the agent never touched. `ktlintCheck` catches it, one `./check` later.

- **`demo-cli/` is exempt from house conventions, not from formatting.** It is a separate composite
  build, never compiled by `./gradlew build`, so a green build says nothing about it — that is what
  the canary is for. The convention rules govern how we build internals, and `demo-cli/`'s job is to
  compile against the published surface like an external consumer; holding it to them would make the
  canary about us instead of about consumers. Formatting is the opposite case: it cannot affect that
  job, and `demo-cli/` is the worked example people read, so it applies the same ktlint against the same
  `.editorconfig` and `./check` gates it. `demo-cli/run.sh` is shellchecked — that was never about style.
- **The composed-stack identity harness (`musicmeta-core/src/test/kotlin/…/harness/`) proves
  composition, never a provider's live behaviour.** It drives the real default provider stack —
  real matchers, real rankers, real mappers — against canned upstream pools, offline, so it is the
  one place the #210 family of wrong-entity defects (MusicBrainz suggestions short-circuiting the
  fan-out, a provider accepting on artist match alone) is observable at all; every other test layer
  either drives a matcherless fake or wires one provider in isolation. It does not, and cannot,
  prove a provider's real endpoint still returns what a pool says it does — a green run here means
  "the pieces compose correctly," not "MusicBrainz/Deezer/iTunes/Discogs still answer this way
  today." That is what the daily `provider-drift.yml` e2e job is for, and each pool's `scenario.md`
  records whether the provider it covers has that live coverage.
- **No live check remains behind the `cover-art-archive` endpoint claim.** `docs/pitfalls.md` §22
  and `docs/providers.md` state that a `/release?query=` hit carries no `cover-art-archive` object
  while a `/release/{mbid}` lookup does. The e2e test that re-checked that against the live API went
  with the field it asserted, so the claim is dated (2026-08-22) rather than watched — and no
  fixture can re-establish it, because a hand-written one says whatever it was written to say.
- **The demo's browser code is untested above `stream-protocol.js`.** That module — SSE framing,
  `Retry-After`, and what an ended stream means — is covered because it is deliberately free of the
  DOM. Everything in `index.js` that renders, binds an event, or drives the page is not: there is no
  DOM harness in this repo and nothing loads the page in CI. The split is the coverage boundary, so
  logic that needs proving belongs on the tested side of it. Verified once by hand at the commit
  that introduced the `fetch` reader: a real lookup streamed and painted, and a refused one issued
  exactly one request rather than falling back to a second.
- **The demo's admission gate is a bound on one process, and nothing checks it is the only one.**
  `demo-web` admits a fixed number of enrichments at a time through a semaphore held in the server
  process, and refuses the rest with a 429. That cap is therefore per instance: run the service at
  two and the real bound is twice what the code says, with every test still green. Nothing in the
  process can read the instance limit it depends on, so the invariant lives in a comment beside the
  gate and in the deploy command, and in neither case does anything fail when they disagree. What
  `AdmissionGateTest` does prove is the part that is checkable inside one JVM: that the bound admits
  its whole width at once rather than serialising, that the endpoint over it refuses as a status
  rather than as an event on an already-committed 200, and that a permit survives a failure the
  handler does not catch.
- **`DefaultEnrichmentEngine`'s `detachedScope` `CoroutineExceptionHandler` is untested.** It is the
  backstop for a detached `enrichProgressive` run's failure reaching neither a collector (none left
  attached) nor anything deeper in the pipeline (which already catches and logs via the
  `catch (Exception) { ensureActive(); ... }` convention) — a rare path, not a primary one the
  ordinary run of the code exercises. Forcing it would mean bypassing every one of those catches on
  purpose, which tests the harness rather than the handler.
