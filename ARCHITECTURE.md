# ARCHITECTURE

The register of what this project enforces and what it merely intends. Every invariant is in
exactly one of the two lists below: **enforced**, meaning a command fails when it is violated, or
**not enforced**, meaning it is admitted here with the reason. There is no third place, and a rule
living only in prose is the failure this file exists to prevent.

The check command is the authority. Where a document and a config disagree, the config wins,
because the config is the thing that fails.

```bash
./scripts/bootstrap.sh   # once: installs the pinned tools ./check requires
./check                  # everything
./check --fast           # skips detekt, build and demo canary — edit loop only, not evidence for a push
```

`./check` runs four layers — a formatter, a linter, a type checker, and a custom linter for rules
no off-the-shelf tool checks — across both languages in the repo:

| Layer | Kotlin | Python | Shell |
|---|---|---|---|
| Formatter | ktlint | `ruff format` | — |
| Linter | detekt | `ruff check` | shellcheck |
| Type checker | kotlinc (in `build`) | mypy | — |
| Custom | `check_conventions.py` | — | — |

**A missing *or mismatched* tool fails the run — it never skips.** A gate that silently skips when
its tool is absent reports green while checking nothing, which is worse than no gate, because this
file would then assert something false. `./check` verifies the pinned version too, not just
presence: formatter output differs between releases, so an unpinned tool reintroduces exactly the
local/CI disagreement one command is supposed to remove.

## Enforced by

| Invariant | Mechanism | Fails via |
|---|---|---|
| Public ABI matches the committed baseline | `api/*.api` + binary-compatibility-validator | `./gradlew apiCheck` (inside `build`) |
| Kotlin formatting and import hygiene | `.editorconfig` + ktlint | `./gradlew ktlintCheck` |
| Kotlin complexity, dead code, bug patterns | `config/detekt.yml` + baselines | `./gradlew detektMain detektTest` |
| No `!!` in main sources — *including on platform types* | `scripts/checks/check_conventions.py` | `./check` |
| `@Serializable` stays off `provider/` and `http/` types | `scripts/checks/check_conventions.py` | `./check` |
| Main-source files ≤ 300 lines | `scripts/checks/check_conventions.py` | `./check` |
| The conventions scanner classifies Kotlin the way Kotlin does | `scripts/checks/test_code_mask.py` vs `KotlinLexer` | `./check` |
| Python formatting and lint | `pyproject.toml` + ruff | `./check` |
| Python types | `pyproject.toml` + mypy | `./check` |
| Shell correctness | shellcheck | `./check` |
| Release-note scripts still behave | `scripts/*/test_*.py` | `./check` |
| Module versions agree with CHANGELOG | `scripts/github-workflows/check_versions.sh` | `release-readiness` job |
| An external consumer still compiles | `demo/` composite build | `./check`, `demo-canary` job |
| `main` merges are merge commits, not squashes | `main protection` ruleset | GitHub, on merge |
| `main` requires build + canary + readiness | `main protection` ruleset | GitHub, on merge |
| Release publishes only from `main` | `release.yml` ref guard | the workflow refusing to run |

Format-on-write (`scripts/format-kotlin.sh`, wired in `.claude/settings.json`) is a convenience,
not a gate. It no-ops when the ktlint CLI is absent, and swallows formatter failures because a file
mid-edit may not parse; `ktlintCheck` is what actually fails. Two hooks: `PostToolUse` on
`Edit|Write` formats the file the payload names, and `Stop` sweeps every dirty Kotlin file at the
end of a turn — which is what covers writes through Bash, since a Bash payload names no file. Target
selection is pinned by `scripts/checks/test_format_kotlin.py`, because a hook that formats nothing
exits 0 exactly like one that works.

## Not enforced

Each line is an invariant nothing checks. This list is the honest cost of the enforced list above,
and it is where drift accumulates silently — so it gets read, not skimmed.

- **Function length (20 target / 40 max).** detekt's `LongMethod` covers the 60-line end of this
  with a baseline, but the 20/40 numbers in `CLAUDE.md` are stricter than anything enforced. Either
  the doc or the threshold should move; nothing currently holds the gap.
- **43 pre-existing detekt findings** sit in `config/detekt-baseline-*.xml` so new code must be
  clean while existing debt stays visible. One deserves a decision rather than a baseline:
  `DefaultEnrichmentEngine` takes an unused `private val httpClient` constructor property, and
  `Builder.build()` allocates a second `DefaultHttpClient` to fill it. Removing the parameter
  changes a public constructor signature, so it is a documented breaking change, not a cleanup —
  tracked in #48.
- **detekt in `--fast`.** The typed tasks compile before they analyse and the Android variants need
  `ANDROID_HOME`, so they sit below the `--fast` exit. The edit loop is ktlint plus
  `check_conventions.py`; detekt runs on every push and in CI. `--fast` was never evidence for a
  push, and this widens the gap it already had.
- **`!!` on a platform type, in detekt.** Measured with a three-cell probe: detekt catches a `!!` on
  a nullable receiver (`UnsafeCallOnNullableType`) and on a definitely-non-null one
  (`UnnecessaryNotNullOperator`), and catches **neither** on a Java platform type —
  `System.getProperty("x")!!` passes both, because the check tests for `TypeNullability.NULLABLE`
  and a flexible type is not that. `check_conventions.py` catches all three, which is why that rule
  stays after the switch to typed detekt rather than being deleted as redundant (#60).
- **Type resolution in detekt is EXPERIMENTAL.** `detektMain`/`detektTest` carry the plugin's
  experimental marking, and so does every alternative in 1.23.x — hand-wiring `classpath`, the CLI
  flags, the compiler plugin. Accepted deliberately: the stable task does not check the rules this
  row exists for. detekt 1.23.8 is also built against Kotlin 2.0.21 / AGP 8.8.1 while this repo runs
  Kotlin 2.1.0 / AGP 8.7.3, so a detekt or AGP bump needs all three modules' tasks re-run, not just
  core's.
- **Kotlin written and committed inside one Bash command.** The `Stop` sweep reads `git status`, so
  a command that writes a file and commits it in the same breath leaves a clean tree and nothing to
  format. `ktlintCheck` still fails on it in `./check`, which is the point — the hook shortens the
  loop, it has never been what holds the line.
- **Test-file length.** Excluded from the 300-line cap on purpose: given-when-then narratives are
  legitimately long, and a cap here would push people to split coherent suites for the wrong reason.
- **Test-source style.** Wildcard imports and SCREAMING_CASE fixture names are allowed in tests
  (`.editorconfig`). Both stay enforced in main sources, where they currently pass clean.
- **Comment density and placement.** "Explain non-obvious constraints, not what the code says" is
  a judgement call no linter makes well.
- **Cancellation handling.** Enforced by behaviour, not by a rule:
  `ProviderChainCancellationTest` pins that a cancelled call records no circuit-breaker failure and
  that a *foreign* `CancellationException` stays contained as one provider's error. A textual rule
  was written for this during #53 and then deleted — it could not see the fallback-returning
  catches that were the actual bugs, and the remediation it printed
  (`catch (CancellationException) { throw e }`) was itself the defect, because it cannot tell our
  cancellation from a provider's own `withTimeout`. `ensureActive()` makes that distinction and a
  regex cannot. The judgement is review's, backed by those tests.

- **Conventional commit format.** Nothing validates commit messages. A `commit-msg` hook or a PR
  title check would close this.
- **"Write each change down once."** The doc-ownership split between CHANGELOG, ROADMAP and the
  issue is prose, and nothing checks it.
- **Four files over the 300-line cap.** `DefaultEnrichmentEngine`, `DeezerProvider`,
  `GenreTaxonomy`, `MusicBrainzEnricher` predate the rule and are grandfathered in
  `check_conventions.py`, which prints them on every run so they stay visible.
- **`dev` has no required status checks.** Its ruleset carries only `deletion` and
  `non_fast_forward`. `build` and `demo-canary` run on every push but do not block. Two things
  block closing this: `release.yml` fast-forwards with `git push origin main:dev`, which a
  `pull_request` rule rejects, and `github-actions[bot]` lacks the `RepositoryRole 5` bypass, so a
  required check would reject the very version-bump push it exists to protect. A PAT would work and
  was rejected — a rotating credential to paper over a broken chain.
- **`demo/` is exempt from house style.** Neither ktlint nor the convention rules cover it, because
  its job is to compile against the published surface like an external consumer would. Its shell
  (`demo/run.sh`) *is* shellchecked — that exemption is about Kotlin style, not correctness.
- **`check_conventions.py` lexes Kotlin partially.** `strip_noise()` is a hand-written scanner
  covering comments (including nested block comments), strings, raw strings, char literals,
  escapes, and `${...}` interpolation with arbitrary nesting of strings and braces inside it. It
  is not a full parser and does not need to be — it only has to decide "is this character code".
  Length and newline positions are preserved, verified against all main sources and 200k fuzz
  inputs, so a reported line number is never wrong even if a classification is. The one known
  false positive is repeated negation (`!!!flag`), reported as a violation; that trade is
  deliberate, because a false positive is loud and a false negative is silent.
