# ARCHITECTURE

What `./check` runs, and the gaps in it worth knowing about. **This is not a complete inventory of
the project's invariants and does not try to be.** Most conventions are review's job, and that is
fine.

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
| Shell | shellcheck | `scripts/**`, `check`, `demo/run.sh` |
| Conventions | `scripts/checks/check_conventions.py` | no `!!` and no `@Serializable` under `provider/`/`http/` in main sources; conflict markers anywhere |
| Release-note caps | `build_release_notes.py Unreleased` | `CHANGELOG.md`'s `[Unreleased]` stays under 3000 chars and 400 per line — the same `check_caps()` the release runs, so it fails here rather than at release prep. An empty section passes: `pin_release.py` opens one on every release branch |
| Script self-tests | `scripts/**/test_*.py` | discovered, not listed — every script with a `test_*.py` beside it still behaves: release notes, conventions |
| Kotlin format | ktlint (version pinned in `libs.versions.toml`) | all modules, `demo/`, and `demo-web/` |
| Kotlin static analysis | detekt, **type-resolved** (`detektMain`/`detektTest`) | complexity, dead code, bug patterns |
| Build | `./gradlew build` | compile, all unit tests, `apiCheck` against `api/*.api` |
| Consumer canary | `demo/` and `demo-web/` composite builds | an external consumer still compiles |

Gates exist beyond `./check` and this table does not list them: `main`'s branch protection lives in
`docs/project/workflow.md`, the release workflow's own verification in `docs/project/release.md`.

**A missing *or mismatched* tool fails the run — it never skips.** A gate that silently skips when
its tool is absent reports green while checking nothing, which is worse than no gate. `./check`
verifies the pinned version too: formatter output differs between releases, so an unpinned tool
reintroduces exactly the local/CI disagreement one command is supposed to remove.

Format-on-write (`scripts/format-on-write.sh`, wired in `.claude/settings.json`) is a convenience,
not a gate. It runs ktlint on `.kt`/`.kts` and ruff on `.py`, and no-ops when either CLI is absent;
`ktlintCheck` and the ruff check are what actually fail. It also no-ops on `demo/`, and when the
`ktlint` on `PATH` is not the `ktlint-cli` version pinned in `libs.versions.toml` — a CLI running a
different rule set writes formatting the gate never asked for, and nothing fails to say so.

## Known gaps

Not an audit of everything unenforced — these are the specific places where a green run means less
than it looks like, each learned the hard way.

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
- **Bash-written Kotlin is not formatted on write.** The hook only sees files an `Edit`/`Write`
  payload names. Sweeping everything dirty at end of turn was built and deleted: it reformats
  uncommitted work the agent never touched. `ktlintCheck` catches it, one `./check` later.

- **`demo/` is exempt from house conventions, not from formatting.** It is a separate composite
  build, never compiled by `./gradlew build`, so a green build says nothing about it — that is what
  the canary is for. The convention rules govern how we build internals, and `demo/`'s job is to
  compile against the published surface like an external consumer; holding it to them would make the
  canary about us instead of about consumers. Formatting is the opposite case: it cannot affect that
  job, and `demo/` is the worked example people read, so it applies the same ktlint against the same
  `.editorconfig` and `./check` gates it. `demo/run.sh` is shellchecked — that was never about style.
