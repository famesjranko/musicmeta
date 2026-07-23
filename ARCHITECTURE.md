# ARCHITECTURE

What `./check` runs, and the gaps in it worth knowing about. **This is not a complete inventory of
the project's invariants and does not try to be.** It used to claim otherwise — "every invariant is
in exactly one of the two lists below" — and that claim manufactured work: it turned every
preference into either a gate somebody had to build or a debt somebody had to record, which is how a
convention scanner grew a Kotlin lexer, an oracle and a mutation suite. Most conventions are
review's job, and that is fine.

The check command is the authority. Where a document and a config disagree, the config wins,
because the config is the thing that fails.

```bash
./scripts/bootstrap.sh   # once: installs the pinned tools ./check requires
./check                  # everything
./check --fast           # skips detekt, the build and the demo canary — edit loop, not a push
```

## What `./check` runs

| Step | Tool | Covers |
|---|---|---|
| Python format and lint | ruff | `scripts/**` |
| Python types | mypy | `scripts/**` |
| Shell | shellcheck | `scripts/**`, `check`, `demo/run.sh` |
| Conventions | `scripts/checks/check_conventions.py` | no `!!` and no `@Serializable` under `provider/`/`http/` in main sources; conflict markers anywhere |
| Script self-tests | `scripts/**/test_*.py` | the release-note and convention scripts still behave |
| Kotlin format | ktlint | all modules; `demo/` exempt on purpose |
| Kotlin static analysis | detekt, **type-resolved** (`detektMain`/`detektTest`) | complexity, dead code, bug patterns |
| Build | `./gradlew build` | compile, all unit tests, `apiCheck` against `api/*.api` |
| Consumer canary | `demo/` composite build | an external consumer still compiles |

Beyond `./check`: `release-readiness` asserts the three module versions agree with `CHANGELOG.md`
before a release PR merges, `main`'s ruleset requires build + canary + readiness and forbids
squashes, and `release.yml` refuses to publish from any ref but `main`.

**A missing *or mismatched* tool fails the run — it never skips.** A gate that silently skips when
its tool is absent reports green while checking nothing, which is worse than no gate. `./check`
verifies the pinned version too: formatter output differs between releases, so an unpinned tool
reintroduces exactly the local/CI disagreement one command is supposed to remove.

Format-on-write (`scripts/format-kotlin.sh`, wired in `.claude/settings.json`) is a convenience, not
a gate. It no-ops when the ktlint CLI is absent; `ktlintCheck` is what actually fails.

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
- **detekt is not in `--fast`.** The typed tasks compile before they analyse and the Android
  variants need `ANDROID_HOME`. The edit loop is ktlint plus the conventions check; detekt runs on
  every push and in CI. `--fast` was never evidence for a push.
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
  itself the defect. `CacheGuard` and `StrategyGuard` still carry that blanket form — #61.
- **Bash-written Kotlin is not formatted on write.** The hook only sees files an `Edit`/`Write`
  payload names. Sweeping everything dirty at end of turn was built and deleted: it reformats
  uncommitted work the agent never touched. `ktlintCheck` catches it, one `./check` later.
- **`dev` has no required status checks.** Its ruleset carries only `deletion` and
  `non_fast_forward`. `build` and `demo-canary` run on every push but do not block, because
  `release.yml` fast-forwards with `git push origin main:dev`, which a `pull_request` rule rejects,
  and `github-actions[bot]` lacks the bypass a required check would need. The real fix is one
  protected branch rather than two.
- **`demo/` is exempt from house style.** Neither ktlint nor the convention rules cover it: its job
  is to compile against the published surface like an external consumer, not to match our style.
  `demo/run.sh` *is* shellchecked — that exemption is about Kotlin style, not correctness.
