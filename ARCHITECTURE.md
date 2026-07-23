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
./check --fast           # skips build and demo canary — for the edit loop, not for a push
```

`./check` runs four layers — a formatter, a linter, a type checker, and a custom linter for rules
no off-the-shelf tool checks — across both languages in the repo:

| Layer | Kotlin | Python | Shell |
|---|---|---|---|
| Formatter | ktlint | `ruff format` | — |
| Linter | detekt | `ruff check` | shellcheck |
| Type checker | kotlinc (in `build`) | mypy | — |
| Custom | `check_conventions.py` | — | — |

**A missing tool fails the run — it never skips.** A gate that silently skips when its tool is
absent reports green while checking nothing, which is worse than no gate, because this file would
then assert something false.

## Enforced by

| Invariant | Mechanism | Fails via |
|---|---|---|
| Public ABI matches the committed baseline | `api/*.api` + binary-compatibility-validator | `./gradlew apiCheck` (inside `build`) |
| Kotlin formatting and import hygiene | `.editorconfig` + ktlint | `./gradlew ktlintCheck` |
| Kotlin complexity, dead code, bug patterns | `config/detekt.yml` + baselines | `./gradlew detekt` |
| No `!!` in main sources | `scripts/checks/check_conventions.py` | `./check` |
| `@Serializable` stays off `provider/` and `http/` types | `scripts/checks/check_conventions.py` | `./check` |
| Main-source files ≤ 300 lines | `scripts/checks/check_conventions.py` | `./check` |
| Python formatting and lint | `pyproject.toml` + ruff | `./check` |
| Python types | `pyproject.toml` + mypy | `./check` |
| Shell correctness | shellcheck | `./check` |
| STORIES entries ≤ 1500 chars | `scripts/github-workflows/check_doc_caps.py` | `./check` |
| Release-note scripts still behave | `scripts/*/test_*.py` | `./check` |
| Module versions agree with CHANGELOG | `scripts/github-workflows/check_versions.sh` | `release-readiness` job |
| An external consumer still compiles | `demo/` composite build | `./check`, `demo-canary` job |
| `main` merges are merge commits, not squashes | `main protection` ruleset | GitHub, on merge |
| `main` requires build + canary + readiness | `main protection` ruleset | GitHub, on merge |
| Release publishes only from `main` | `release.yml` ref guard | the workflow refusing to run |

Format-on-write (`scripts/format-kotlin.sh`, wired in `.claude/settings.json`) is a convenience,
not a gate. It no-ops when the ktlint CLI is absent; `ktlintCheck` is what actually fails.

## Not enforced

Each line is an invariant nothing checks. This list is the honest cost of the enforced list above,
and it is where drift accumulates silently — so it gets read, not skimmed.

- **Function length (20 target / 40 max).** detekt's `LongMethod` covers the 60-line end of this
  with a baseline, but the 20/40 numbers in `CLAUDE.md` are stricter than anything enforced. Either
  the doc or the threshold should move; nothing currently holds the gap.
- **46 pre-existing detekt findings** sit in `config/detekt-baseline-*.xml` so new code must be
  clean while existing debt stays visible. One deserves a decision rather than a baseline:
  `DefaultEnrichmentEngine` takes an unused `private val httpClient` constructor property, and
  `Builder.build()` allocates a second `DefaultHttpClient` to fill it. Removing the parameter
  changes a public constructor signature, so it is a documented breaking change, not a cleanup —
  tracked in #48.
- **Test-file length.** Excluded from the 300-line cap on purpose: given-when-then narratives are
  legitimately long, and a cap here would push people to split coherent suites for the wrong reason.
- **Test-source style.** Wildcard imports and SCREAMING_CASE fixture names are allowed in tests
  (`.editorconfig`). Both stay enforced in main sources, where they currently pass clean.
- **Comment density and placement.** "Explain non-obvious constraints, not what the code says" is
  a judgement call no linter makes well.
- **Conventional commit format.** Nothing validates commit messages. A `commit-msg` hook or a PR
  title check would close this.
- **"Write each change down once."** The doc-ownership split between CHANGELOG, STORIES, ROADMAP
  and the issue is prose. Only the STORIES length cap is mechanised.
- **Four files over the 300-line cap.** `DefaultEnrichmentEngine`, `DeezerProvider`,
  `GenreTaxonomy`, `MusicBrainzEnricher` predate the rule and are grandfathered in
  `check_conventions.py`, which prints them on every run so they stay visible.
- **`dev` has no required status checks.** Its ruleset carries only `deletion` and
  `non_fast_forward`. `build` and `demo-canary` run on every push but do not block. Adding a
  `pull_request` rule would break `release.yml`'s `git push origin main:dev` fast-forward unless
  the Actions app is given a bypass.
- **`demo/` is exempt from house style.** Neither ktlint nor the convention rules cover it, because
  its job is to compile against the published surface like an external consumer would.

## Decisions

Reversals and rejected options — what was tried, what was declined, and why. Empty for now: the
existing entries live in `STORIES.md` and migrate here when the doc restructure lands.

### 2026-07-23: ktlint configured against the codebase, not against its own default

ktlint's `ktlint_official` style flagged 6022 violations; `intellij_idea` flagged 3568, of which
~3000 were wrapping rules relocating line breaks in already-readable code. Both were rejected as
written. The disabled-rule list in `.editorconfig` is the decision: a formatter earns its place by
making formatting stop being a decision, and rewriting 171 files to move arguments onto their own
lines is the opposite trade. What remains enabled — indentation, imports, spacing, line length —
took the codebase from 6022 violations to 47, and 19 unused imports fell out as real defects.

Rejected: adopting `ktlint_official` and reformatting wholesale. It would have made every
subsequent `git blame` cross a formatting commit for no correctness gain.

### 2026-07-23: detekt tuned to 46 findings rather than baselined at 898

detekt's defaults report 898 findings here, which is not a signal — it is a reason to stop reading
the report. 477 were `MagicNumber` flagging provider priorities and confidence thresholds, which
are the domain; 198 were `MaxLineLength` and 23 `WildcardImport`, both already owned by ktlint.
Two tools disagreeing about line length means whichever runs first owns it and the other is noise.
Disabling those plus the guard-clause rules (`ReturnCount`, `TooGenericExceptionCaught`, which
argue against how providers deliberately degrade) left 46, which is small enough to act on.

Rejected: baselining all 898. A baseline that large is indistinguishable from not running the tool,
and it would have hidden the one finding worth having — an unused constructor property on a public
class.

### 2026-07-23: Python tooling via uv, because there is no system pip

The machine has Python 3.13 with no `pip`, `pipx`, or `uv`, so mypy was initially written off as
unavailable. It is not: `uv` is a single static binary that installs both ruff and mypy without a
system Python package manager. `scripts/bootstrap.sh` pins all four tool versions so a toolchain
change is a reviewed commit rather than a CI failure nobody caused.

Rejected: black + flake8 + isort. ruff is one binary covering all three, and this repo needed a
formatter and a linter for 940 lines of script, not three dependencies.
