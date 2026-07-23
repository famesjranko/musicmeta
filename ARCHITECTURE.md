# ARCHITECTURE

The register of what this project enforces and what it merely intends. Every invariant is in
exactly one of the two lists below: **enforced**, meaning a command fails when it is violated, or
**not enforced**, meaning it is admitted here with the reason. There is no third place, and a rule
living only in prose is the failure this file exists to prevent.

The check command is the authority. Where a document and a config disagree, the config wins,
because the config is the thing that fails.

```bash
./check          # everything: ktlint, conventions, doc caps, script tests, build, demo canary
./check --fast   # skips build and demo canary — for the edit loop, not for a push
```

## Enforced by

| Invariant | Mechanism | Fails via |
|---|---|---|
| Public ABI matches the committed baseline | `api/*.api` + binary-compatibility-validator | `./gradlew apiCheck` (inside `build`) |
| Formatting and import hygiene | `.editorconfig` + ktlint | `./gradlew ktlintCheck` |
| No `!!` in main sources | `scripts/checks/check_conventions.py` | `./check` |
| `@Serializable` stays off `provider/` and `http/` types | `scripts/checks/check_conventions.py` | `./check` |
| Main-source files ≤ 300 lines | `scripts/checks/check_conventions.py` | `./check` |
| STORIES entries ≤ 1500 chars | `scripts/github-workflows/check_doc_caps.py` | `./check` |
| Release-note scripts still behave | `scripts/*/test_*.py` | `./check` |
| Module versions agree with CHANGELOG | `scripts/github-workflows/check_versions.sh` | `release-readiness` job |
| An external consumer still compiles | `demo/` composite build | `./check`, `demo-canary` job |
| `main` merges are merge commits, not squashes | `main protection` ruleset | GitHub, on merge |
| `main` requires build + canary + readiness | `main protection` ruleset | GitHub, on merge |
| Release publishes only from `main` | `release.yml` ref guard | the workflow refusing to run |

Format-on-write (`scripts/format-kotlin.sh`, wired in `.claude/settings.json`) is a convenience,
not a gate. It no-ops unless the ktlint CLI is installed; `ktlintCheck` is what actually fails.

## Not enforced

Each line is an invariant nothing checks. This list is the honest cost of the enforced list above,
and it is where drift accumulates silently — so it gets read, not skimmed.

- **Function length (20 target / 40 max).** Unmeasured across the codebase and likely to fail
  widely. A gate that fails on day one does not ship, so this needs measuring and a grandfather
  list before it can move up.
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
