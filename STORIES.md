# musicmeta - Project Stories

> A living document of architectural decisions, progress, and lessons learned.
> Updated as the project evolves. Newest entries first within each section.

---

## Decisions

### 2026-07-23: A convention gate cannot lex Kotlin with regex

`check_conventions.py` must know which characters are code before it can ban `!!`. Two regex
attempts each shipped a silent false negative, and both were found only by review.

**Attempt 1** — five sequential `re.sub` passes. The line-comment pass ran over text the string
pass had not consumed, so the `//` in `"https://host/"` blanked the rest of the line and
`"https://host/" + u!!.trim()` reported clean. This repo builds URLs on nearly every provider line.

**Attempt 2** — one alternation, single pass. Its string branch could not span the nested quote in
`"${enc(id!!, "UTF-8")}"`, so it truncated the literal and blanked the expression it existed to
preserve. `WikidataApi.kt` already ships that shape. Leaked literal text then reached the `'` and
`/*` branches, which swallowed every line to the next match.

**Rejected — a third regex.** The failure is structural: Kotlin nests, and regex cannot express
recursion. A third attempt would have been the same bet twice.

**Rejected — a full Kotlin parser.** The question is only "is this character code", which a
scanner answers completely and a parser answers expensively.

Now a character scanner over a context stack: correct by construction for nesting, and length- and
newline-preserving, so a reported line number is never wrong even if a classification is.

### 2026-07-22: Pin Kotlin's jvmTarget, don't adopt a toolchain

**Context**: only `musicmeta-android` declared `jvmTarget`. The two JVM modules set
`sourceCompatibility`/`targetCompatibility` alone, which govern `compileJava` — and the repo has no
`.java` files, so that task is NO-SOURCE. Kotlin fell back to the JDK running Gradle, so `./gradlew
build` and the demo canary both failed on any machine defaulting to JDK 21, which current Debian and
Ubuntu do. On a JDK 17 box the two values coincided by accident; nothing in the repo made that
happen. Unnoticed since the first commit because every workflow and `jitpack.yml` pin 17.

**Rejected — `jvmToolchain(17)`**, the more correct fix. On a JDK-21-only machine it swaps one error
for "no matching toolchains" unless the foojay resolver is also added, which downloads a JDK from a
third-party service at build time — a supply-chain surface this repo does not otherwise have. The
residual gap is accepted: Kotlin resolves JDK classes from the running JDK, so a JDK-18+ API could
compile locally and fail at runtime on 17. CI on 17 catches that.

**Not an ABI change**: signatures for all 537 core classes are byte-identical either way; only the
class-file version moves, which BCV does not record. `apiCheck` green, baselines untouched.

### 2026-07-22: Release from one dispatched workflow, tag last

**The design that does not work.** Dispatch → bot-opened PR → merge → bot-pushed tag → `publish.yml`
is inert: GitHub creates no workflow runs for `GITHUB_TOKEN` events, so the PR gets zero checks and
can never meet `main`'s required contexts, and the tag never starts `publish.yml`. `android-ip-camera`
works only because a human creates the release in the UI.

**Decision — dispatch → verify → publish → tag-last, one workflow.** The inversion #35 deferred; the
trigger rule makes it the cheap option. No cross-workflow triggers, `publish.yml` deleted, no new
secrets, and the tag derived from a verified version, making #13's failure mode impossible rather
than caught. Jobs, not steps: **publishing is not idempotent**, so a re-run must not repeat an
upload.

**Reverses #13 on `automaticRelease`**, kept there as a human gate. The compensating control is a
three-POM poll — Central validates asynchronously and Gradle goes green on upload. **A published
version is immutable.**

**Rejected**: a PAT for bot-triggered workflows (rotating credential, keeps
the broken chain); required checks on `dev` (`github-actions[bot]` lacks the `RepositoryRole 5`
bypass, so it would reject the bump push it protects); CI commit signing (no `required_signatures`);
a README check in `release-readiness` (required on `main`, breaks deferred tagging); a `setup-jvm`
action (Dependabot covers bumps). Verification evidence is in the PR.

### 2026-07-22: Collapse the three version declarations to one source (issue #37)

**Context**: the two version guards (#13 in `publish.yml`, `release-readiness` in `build.yml`, #35)
both *detect* cross-module drift; neither makes it impossible. Each module declared `version`
independently, so a release meant editing the same string in three files and keeping them in sync by
hand — the footgun that produced the 0.10.0 mismatch on `dev`.

**Decision — a single `version` in root `gradle.properties`.** Gradle applies a `version` property
from the root `gradle.properties` to every project, so all three modules inherit one value and
cross-module drift becomes *unrepresentable* rather than merely detected — the stronger fix deferred
under #13. The per-module `version = "0.10.1"` lines are removed; the bump happens in one place.

**Why `version` only, not `group`.** `group` is identical across the three too, but it has never
drifted, and collapsing it is messier: each `coordinates("io.github.famesjranko", …)` call passes the
group as a *string literal* separate from the `group` property, so "one source" would mean touching
two sites per module on the same high-risk surface for no demonstrated benefit. Left per-module.

**Why the guards survive unchanged.** Both read the *effective* Gradle version
(`./gradlew :module:properties | awk '/^version: /'`), not build-file source text, and
`coordinates(…, version.toString())` still reads `project.version`. So the guards keep reporting
0.10.1 for all three and stay as backstops; the published GAV is unchanged.

**Verification**: POMs generated for all three modules before and after the change diff byte-for-byte
identical (the `api/*.api` baselines can't catch a coordinate regression, so the POM is the check).
The CI guard's exact `awk` parse still yields 0.10.1 for all three, matching the CHANGELOG heading.
Full `./gradlew build` (all modules, tests, `apiCheck`) and the `demo/` composite build both pass.

### 2026-07-22: Catch version drift before the tag, not after (issue #35)

**Context**: `publish.yml`'s tag-vs-version guard (#13) is inherent to the tag-as-trigger model — it
can only run once the immutable tag exists, so a mismatch it catches still costs a delete-and-re-push.
But the three modules declare `version` independently and can drift from each other or from the pinned
`CHANGELOG` heading, and that drift is fully knowable *before* any tag.

**Decision — a `release-readiness` job in `build.yml`, gated to the release PR.** Runs only on
`pull_request` with `base_ref == main` (the `dev` → `main` release PR, which `release.md` names as the
review point). Reads all three versions from Gradle — as #31's guard does, so it sees what would
actually publish — and asserts each equals the top `## [x.y.z]` CHANGELOG heading. Matching all three
to that one value also proves they match each other, so a single comparison covers both facts the
issue asks for. By tag time `publish.yml`'s guard is already known-green for version mismatch; it
stays as the backstop.

**Why a job in `build.yml`, not a new workflow or a dispatchable check**: the issue offered both; the
job reuses the existing PR trigger and adds no new file. It is deliberately *not* a required check —
the live `main` ruleset requires only `build` — so its value is the red X a human sees on the release
PR, not enforcement. Promoting it to a required context is a ruleset change, a follow-up. A docs-only
PR into `main` (the deferred-tagging exception) leaves versions and heading untouched, so it passes.

**Deferred (issue's non-goals)**: collapsing the three `version` declarations to one source (resolved
2026-07-22, #37), and inverting the trigger to `workflow_dispatch` → verify → publish → tag-last,
which makes the bad state unrepresentable but rewrites the release trigger.

**Verification**: check body extracted and exercised against injected versions — all-agree exits 0; a
single module drift, a CHANGELOG drift, and an empty version each exit 1 with an `::error::`. The
`sed` heading parse and the Gradle version read were run against the real tree (both yield 0.10.1).

### 2026-07-22: Detect the tag/version mismatch, don't make it unrepresentable (issue #13)

**Context**: `publish.yml` derived nothing from the pushed tag — it published whatever the three
`build.gradle.kts` files declared. A tag ahead of the build files uploads the old GAV, Central
rejects the duplicate, and an immutable tag survives for a version that never shipped. Not
hypothetical: it was live on `dev` during the 0.10.0 release and was fixed by hand-editing three
files before merge. The release checklist in `docs/project/release.md` documented the discipline;
nothing enforced it.

**Decision — assert the match, keep three declarations.** The guard checks all three modules (they
declare `version` independently and can drift from each other, not just from the tag), reads the
value from Gradle rather than grepping the build file (so it sees what would actually be published),
treats an unreadable version as a failure, and runs before the test step so it fails at the cheapest
possible point while the tag is still the only artifact.

**Deferred: collapsing `version` to a single source.** A shared `gradle.properties` value would make
cross-module drift *unrepresentable* rather than merely detected, which is the stronger fix. It also
edits publishing configuration in all three modules — a high-risk surface — for a failure mode this
guard already catches before it can reach Central. Wants its own issue and its own review. *(Resolved
2026-07-22, #37 — see the entry at the top of this section.)*

**Also deliberately out of scope**: auto-creating GitHub Releases (rejected — hand-written notes beat
a generated template) and `automaticRelease` (a deliberate human gate; it wants documenting, not
automating).

**Verification**: guard body extracted from the parsed YAML and exercised both ways against the real
build — `v0.10.0` exits 0 with all three modules matching, `v9.9.9` exits 1 with one `::error::` per
module. The workflow itself only fires on a `v*` tag and was reviewed by inspection.

### 2026-07-22: The third extension point gets a guard (issue #28)

**Context**: `ResultMerger` and `CompositeSynthesizer` are public and consumer-implementable, and
`resolveTypes` called both with no `try`/`catch`. `enrich()`'s only handler is for
`TimeoutCancellationException`, so a consumer's exception propagated straight out.

The obvious counter-argument — a consumer's own callback should fail loudly — does not survive the
precedent. `EnrichmentProvider` is equally consumer-registrable and *is* caught, at both
`ProviderChain` call sites; `EnrichmentCache` got the same treatment in #22. That left mergers and
synthesizers as the one unguarded extension point out of three. **The inconsistency is the argument**,
not a "never throws" promise on its own.

**Decision — report a typed `Error`, do not degrade silently.** The cache's degrade-to-miss does not
transfer: a merger *produces* the type's result, so swallowing would be indistinguishable from a
genuine `NotFound`. `guardedStrategy` mirrors `CacheGuard`, including rethrowing
`CancellationException` — but for the same reason the cache gives, not a stronger one. Both call
sites do sit inside `enrich()`'s `withTimeout`, yet neither `merge` nor `synthesize` is a suspending
function, so the deadline can never be delivered into the guarded block. The rethrow is hygiene
against one of those interfaces becoming `suspend` later; it is not what enforces the deadline.

**Found while testing**: the pre-existing `?: NotFound(type, "no_merger")` and
`"no_composite_handler"` fallbacks are unreachable. `mergeableTypes` and `compositeDependencies` are
derived from the registered maps, so a type only reaches those call sites when its strategy exists.
Left in place — removing dead branches is not this fix's job — but no test can assert them, and the
test that tried now documents the real behaviour instead.

**Also settled**: `README.md`'s known-limitation paragraph, added under #22's scope discipline,
recorded a deferral rather than a rejection. It is now false and was deleted.

**Not a defect**: `resolveIdentity`'s bare `catch (e: Exception)` sits inside the `withTimeout` block
and was suspected of masking the deadline. Probed at 100ms against a 5s identity provider — returns
`Error kind=TIMEOUT`, because cancellation re-asserts at the next suspension point. Recorded so the
same false suspicion is not re-derived.

### 2026-07-22: Project workflow docs own facts, not agent procedures

**Context**: `docs/agent-workflows/conventions.md` was generated as the “project-specific half” of
the old `ship-init`/`ship-issue`/`ship-pr` design. It mixed durable repository decisions with generic
tool instructions, autonomy flags, duplicated code rules, and a release checklist. Its copied
backwards-compatibility rule drifted from `CLAUDE.md` within hours, and the later removal of that copy
still left a subordinate “canonical docs win” hierarchy rather than clear ownership. It also named an
epic PR class without defining how children land on the shared branch.

**Decision**: Replace the generated contract with canonical project documents. `docs/project/workflow.md`
owns branch topology, work isolation, issue lifecycle, selection, and verification; it now defines
both independent-child and shared-epic landing models. `docs/project/release.md` owns release
preparation, tagging, and publication. `CLAUDE.md` continues to own code and API constraints and
routes to both documents. No document is a fallback copy of another.

**Consequences**: Agents and humans read the same project-owned workflow without depending on the
implementation details of a particular shipping skill. Shared-branch epic children now have an
explicit push-time closure and abandonment-recovery rule. Future workflow changes update one owner
instead of re-synchronizing a generated contract.

### 2026-07-21: CI canary + scheduled API-drift watch (issues #3, #6)

**Context**: PR #9 landed `build.yml` running `./gradlew build` (build + test + `apiCheck`) on PRs/pushes to `main`/`dev`. Two silent-rot gaps remained. First, `demo/` is a composite build (`includeBuild("..")`) outside `settings.gradle.kts`, so `./gradlew build` never compiles it — yet it is the tree's only consumer compiling against the published surface as an external consumer would, and it broke unnoticed in v0.9.2. (It was a fully positional consumer at the time. Note it was never a reliable *position* guard even then — a mid-list insertion carrying a default can compile clean, and `CLAUDE.md` owns the rule for when. v0.9.2 was caught by the type check, not by position: `Set<EnrichmentType>` could not bind to `EnrichmentIdentifiers?`.) Second, `apiCheck` on a PR catches an API *change* on the PR that caused it, but not *drift* — a committed `.api` baseline that stops matching reality between releases (a dependency/Kotlin bump moving the emitted ABI, a stale `.api` merge). A red PR run also has no memory.

**Decision — a demo-canary job in `build.yml` and a separate scheduled `api-drift.yml`.** The canary job (`cd demo && ../gradlew compileKotlin`; demo has no wrapper of its own, it borrows the root one) runs under the same triggers as `build`, **including drafts**. Drafts cannot merge anyway, so running on them costs little and means a draft that is about to be marked ready has already reported — chosen over a `draft == false` filter for that reason. E2E stays opt-in (`-Dinclude.e2e=true`), never in CI.

*Corrected 2026-07-21 (release review):* an earlier draft of this entry justified the draft triggers by asserting that `build.yml` "is a required check on `main`/`dev`". The live rulesets say otherwise — `main protection` requires exactly one context, `build`; `dev protection` requires **no** status checks at all; and `demo-canary` is a required check on neither branch. So a red `demo-canary` blocks nothing today. The job is still worth running — it is the only thing that compiles `demo/` — but its value is the signal, not an enforcement contract that does not exist. Promoting `demo-canary` to a required context on `main` is a follow-up, not something this entry may assume.

The drift watch (weekly + `workflow_dispatch`) checks out `dev`, runs `apiDump`, and `git diff`s the `api/*.api` files. It folds in the demo compile canary too (the issue's note — the other silent-rot check). On drift or demo breakage it files, or updates in place, a **single** `[api-drift-bot]`-prefixed tracking issue (searched by that collision-proof title marker via `gh issue list --search … in:title` + a `startswith` jq filter, so it never matches a human issue and never duplicates), with the diff in the body; when `dev` is clean it auto-closes that issue with a comment. Minimal permissions (`contents: read`, `issues: write`), `GITHUB_TOKEN` only, no new secrets — modelled on MediaStack's `image-drift.yml` drift-warn half.

*Superseded 2026-07-22 (#14):* the title-marker search described above is now the **fallback**, not the primary lookup. The tracker is identified by a dedicated `api-drift` label, matching `provider-drift.yml`. The title search was never a prefix search — GitHub's tokenizer strips `[` and `]`, so it degraded to relevance-ranked words and the real tracker could page out of 100 results, filing a duplicate every week. Consequence worth stating plainly: **the label is an ownership marker.** Putting it on a hand-filed issue hands that issue to the watch to retitle, rewrite and auto-close.

**Verification**: both YAMLs `yaml.safe_load`-parsed (no actionlint available). Locally exercised the exact commands the workflows run: `cd demo && ../gradlew compileKotlin` green; `./gradlew apiDump` + `git diff --quiet -- '*/api/*.api'` reports no drift (baselines match `dev`); the `gh issue list … in:title` search + jq `startswith` filter confirmed collision-free against existing issue #6; the drift body-assembly bash dry-run under `set -euo pipefail` produces well-formed Markdown. Not exercisable locally: the scheduled trigger, GitHub-hosted `gh issue create`/`edit`/`close` calls, and the runner's Android SDK provisioning — syntax-checked only. `git diff --check` clean.

**Status**: Landed on `epic/api-compat`.

### 2026-07-21: Narrowing the public API surface — provider/http/engine internals made `internal` (issue #5)

**Context**: The ABI baselines from issue #2 recorded reality, and reality was that ~84 declarations under `provider/` and the `http/`/`engine/` infrastructure were public *by omission* — `class DeezerApi`, `data class DiscogsRelease`, `object GenreMerger` with no visibility modifier default to `public`, so they shipped on Maven Central and JitPack. Nobody intended them as API; each exists to serve the `*Provider` in its own package. The cost was noise: a provider-internal rename turned `apiCheck` red and demanded an `apiDump` commit, burying the one signal that matters (a real `EnrichmentEngine`/`EnrichmentData` change). The 0.x carve-out settled in the entry below licenses the documented removal.

**Decision — mark the incidentally-public declarations `internal`; keep the intended surface public.** 80 top-level types moved to `internal`: the 11 `*Api`, 11 `*Mapper` and 49 `*Models` response classes behind the providers, `MusicBrainzParser`, `http/CircuitBreaker`, and the 7 `engine/` mergers/synthesizers. The `.api` baseline shrank ~1350 lines. Kept public: the `*Provider` classes (the registration surface), `HttpClient`/`HttpResult`/`HttpResponse`/`DefaultHttpClient`, and the `ResultMerger`/`CompositeSynthesizer` interfaces.

**Two decisions where an internal type leaked into a public signature** (the compiler's "public exposes internal" is the arbiter here):

- **`RateLimiter` stays public, not internal.** The issue listed it as accidental infrastructure, but it is a parameter of nearly every public `*Provider` constructor (`DeezerProvider(HttpClient, RateLimiter, …)`). Hiding it would force removing or reshaping ~12 provider constructors — a far larger break than it removes. The least-break resolution is to keep it public; it is genuinely part of how a consumer tunes a provider. `CircuitBreaker`, which leaks nowhere a consumer supplies it, went internal as planned.
- **`SimilarAlbumsProvider` and `ProviderChain` constructors changed rather than keeping their Api/CircuitBreaker params public.** `SimilarAlbumsProvider`'s only constructor took `DeezerApi`; keeping `DeezerApi` public just to preserve it would have dragged all of Deezer's models back into the ABI. Instead its `DeezerApi` constructor became `internal` and a public `(HttpClient, RateLimiter)` constructor was added, matching every sibling provider and building the `DeezerApi` internally. `ProviderChain`'s default `circuitBreakers` parameter exposed `CircuitBreaker`; its constructor became `internal` (nothing outside `ProviderRegistry` constructed it; consumers reach a chain via `chainFor()`).

**Deliberately not done**: the remaining public `engine/` wiring — `ProviderRegistry`, `ProviderChain` (class), `DefaultEnrichmentEngine`, and the `ArtistMatcher`/`ConfidenceCalculator` helper objects — is also incidental but out of the issue's "mergers" scope, and `DefaultEnrichmentEngine`/`ProviderRegistry` are constructed inside `EnrichmentEngine.Builder`, so narrowing them needs its own pass. `musicmeta-okhttp` is clean by design (only `OkHttpEnrichmentClient : HttpClient`); `musicmeta-android`'s Room DAO/Database/Entity are Room-required and a persisted-data danger zone, so both modules were left untouched.

**Verification**: `./gradlew build --rerun-tasks` green; test counts read from XML — core 718 (43 e2e skipped), okhttp 35, android 26, 0 failures. `./gradlew apiDump` regenerated the core baseline (okhttp/android unchanged). Demo canary `cd demo && ../gradlew compileKotlin` green — a real composite-build consumer that references none of the removed types. `git diff --check` clean.

**Status**: Landed on `epic/api-compat` for the epic PR.

### 2026-07-21: Reconciling the already-shipped API breaks — 0.x semver stance, CHANGELOG backfill, v0.9.2 position

**Context**: The ABI-baseline work (below) stopped *new* drift but left three questions about the breaks already on Maven Central and JitPack. All nine claimed breaks were re-verified against the tag history (`git diff v0.X.0..v0.Y.0`) before any of this was written down; every one is real.

**Decision 1 — the v0.9.2 `identifiers` position: leave it and document, do not correct.** In v0.9.2 (a *patch*), `identifiers: EnrichmentIdentifiers? = null` was inserted between `mbid` and `types` on the `artistProfile`/`albumProfile`/`trackProfile` extensions, shifting every positional argument after `mbid`. The committed `api/*.api` baseline already records this shape as the source of truth. The reasoning for leaving it:

- **Named callers are unaffected in every scenario** — and named arguments are the idiomatic Kotlin form. The in-repo `demo/` and the whole extensions test suite call these functions with named `types =`/`identifiers =`/`forceRefresh =`, so no in-repo caller is exposed. The only callers who could break are positional ones.
- **On the JVM, appending would not have saved binary consumers anyway.** Adding a parameter to a function with default parameters changes the generated method descriptor (and the `$default` synthetic), so a pre-compiled positional caller gets a `NoSuchMethodError` regardless of *where* the parameter goes. Appending only preserves *source* compatibility; it is not a full ABI guarantee. So the practical upside of "correct it" is narrow: restore *source* compat for one cohort.
- **Correcting cannot restore both cohorts — it only moves the break.** There are now two positional shapes in the wild: the v0.9.1 shape and the v0.9.2 shape. A v0.9.3 that moved `identifiers` to the end would recompile pre-0.9.2 positional callers who skipped 0.9.2, but would break anyone who *adopted* v0.9.2 positionally (their third positional `identifiers` argument would rebind to `types: Set`). It also adds a *third* signature shape to the historical record and spends a release + another reviewed `.api` diff to do it.
- **The 0.x carve-out (Decision 3) licenses the documented break**, and `apiCheck` + the append-at-the-end rule now prevent recurrence. Chasing source-compat for a hypothetical external positional adopter of a four-month-old patch parameter is not worth a corrective release that itself re-shuffles the signature.

Condition under which to revisit: concrete evidence of external consumers pinned to the v0.9.1 positional shape (an issue report, telemetry). None exists today — the only observed callers are in-repo and all named.

**Decision 2 — backfill `### Breaking Changes` in `CHANGELOG.md`: yes.** `CLAUDE.md` has always required breaking changes under a `### Breaking Changes` heading; that heading had never appeared. Backfilled sections were added to the historical entries for every verified break: **v0.4.0** (four removals/renames with no deprecation cycle — `EnrichmentConfig.preferredArtworkSize` + `DEFAULT_ARTWORK_SIZE` deletion, which had been undocumented entirely; the `ProviderCapability.requiresIdentifier` → `identifierRequirement` rename; the `EnrichmentData.IdentifierResolution` removal; and `SimilarArtist`/`PopularTrack.musicBrainzId` → `identifiers`, both `@Serializable` so persisted JSON breaks too); **v0.5.0** (`Metadata.genreTags` mid-list, `@Serializable`); **v0.7.0** (`EnrichmentCacheEntity` three-parameter mid-list, plus the already-flagged `enrich()` return-type and interface-method breaks restated under the heading); **v0.9.0** (`EnrichmentConfig.radioDiscoveryMode` mid-list); **v0.9.2** (the `identifiers` mid-list above, and the corrected "backwards compatible" wording). The v0.7.0 `enrich()` breaks were the only ones already labelled "Breaking" at the time, under `### Changed`.

**Decision 3 — the semver stance: adopt the 0.x carve-out.** `CLAUDE.md`'s backwards-compatibility section stated a flat "no breaks without a major bump" with no 0.x exception — a rule that was never true of practice, and the gap between the stated rule and reality is what let the audit findings accumulate. The section now says: during `0.x`, **minor** releases MAY break (always documented under `### Breaking Changes` and visible in the reviewed `.api` diff); **patch** releases may NOT break; the full semver rule takes effect at `1.0.0`. The additive-evolution guidance (prefer overloads/defaults, deprecate before removing, append-at-the-end) is retained, with the JVM binary-break nuance added to the append rule.

**Verification**: docs-only change; `./gradlew build` green (including `apiCheck` — no `.api` baseline touched), `git diff --check` clean.

**Status**: Active. Decision 1 is a recommendation carried into the epic PR for the team lead; Decisions 2 and 3 are landed in `CHANGELOG.md` and `CLAUDE.md`.

---

### 2026-07-21: Public API compatibility enforced by a committed ABI baseline

**Context**: `CLAUDE.md` has always stated a no-breaking-changes-without-a-major-bump contract, enforced by review alone. An audit of the tag history found review had missed it repeatedly: four mid-list parameter insertions (v0.5.0 `Metadata.genreTags`, v0.7.0 `EnrichmentCacheEntity`, v0.9.0 `EnrichmentConfig.radioDiscoveryMode`, v0.9.2 `identifiers`), four removals or renames with no deprecation cycle in v0.4.0, and `@Deprecated` never once appearing in `musicmeta-core/src/main/`. It surfaced when `demo/` stopped compiling — the v0.9.2 insertion shifted every positional argument after `mbid`, and it shipped in a *patch* release.

**Decision**: Adopt `binary-compatibility-validator`. Each module's public ABI is dumped to `api/*.api` and committed; `apiCheck` compares against it and is wired into `check`, so `./gradlew build` fails on divergence. Reviewing an intentional break becomes reviewing the `.api` diff rather than inferring it from the source diff.

**`apiCheck` added to `publish.yml` as well**: that job runs `:musicmeta-core:test :musicmeta-okhttp:test` — not `build`, not `check` — so without naming the task explicitly the only automated path to Maven Central would have gained no gate at all. The PR-time gate is a separate concern, tracked with the rest of the CI work.

**Key finding — the public surface is far wider than this document claimed.** The first dump recorded **282 public classes in `musicmeta-core`**: 157 in the root package (the intended API) and 2 under `cache/`, but also **93 under `provider/`**, 16 under `engine/`, and 14 under `http/`. `CLAUDE.md` says internal code — "`provider/*/` internals, `http/` infrastructure" — can change freely. For `provider/` that was simply false: those classes are published on Maven Central, and nothing marks them `internal` (84 public top-level declarations vs 2 `internal`). They are public by omission. `DiscogsMapper` and `DiscogsRelease`, for instance, have zero references outside their own package.

The `http/` count is a different story and worth stating precisely, because acting on it carelessly would break a published module: of those 14, only `CircuitBreaker` (with its nested `Companion` and `State`) and `RateLimiter` are accidental. `HttpClient`, `HttpResponse` and `HttpResult` with its five sealed variants are the contract `musicmeta-okhttp` implements — `OkHttpEnrichmentClient` implements `HttpClient` and returns `HttpResult` — so they must stay public.

**Decision — record reality rather than suppress it.** The baselines include those declarations. Hiding them behind `ignoredPackages` would have made the baseline describe an API that does not exist, and would have quietly kept the docs wrong. The cost is accepted and known: until the surface is narrowed, a provider-internal refactor turns `apiCheck` red and requires an `apiDump` commit. Narrowing it is an ABI removal in its own right and needs the 0.x semver stance settled first, so it is tracked as separate work.

**The one thing suppressed**: KSP output lands in the same classes directory BCV reads, so Room's `_Impl` classes and Hilt's generated factories appeared in the android baseline. Those are generated, not authored, and would churn on every Room or Hilt version bump. They are listed in `ignoredClasses` — an exact-match set with no wildcard support, so each new `@Dao` interface, `@Database`, or Hilt `@Provides` method adds an entry by hand. (A new *method* on the existing DAO does not: it lands on the already-ignored `_Impl` class.) The authored types they stand in for are still tracked.

**Note on the tool**: `binary-compatibility-validator` is in maintenance mode; JetBrains points new projects at the ABI validation built into the Kotlin Gradle plugin. That successor needs a newer Kotlin than this project's 2.1.0, so it is a future migration, not a current option.

**Status**: Active

---

### 2026-03-27: v0.9.2 — Track preview fast path and identifiers passthrough

**Context**: Cascade's discovery page loaded in ~20-30 seconds because resolving preview URLs for top tracks required MusicBrainz identity resolution (1 req/sec rate-limited) before each Deezer lookup. But the top tracks already carried `deezerId` in their `identifiers.extra` from the initial fetch — the round-trip through MusicBrainz was unnecessary.

**Decisions**:

- **`identifiers` parameter on factory methods, not a separate API**: Added `identifiers: EnrichmentIdentifiers? = null` to `forTrack()`, `forArtist()`, `forAlbum()` and their profile extension counterparts. This is general-purpose — any provider can check for pre-resolved identifiers, not just Deezer. Backwards compatible via default parameter.

- **Fast path in DeezerProvider, not engine-level bypass**: The deezerId check lives in `enrichTrackPreview()` (same pattern already used by `enrichTopTracks`, `enrichArtistRadio`, `enrichSimilarArtists`). This keeps identity resolution as the engine's concern and provider-specific optimizations in providers.

- **`resolveTrackPreviews()` batch method**: A convenience extension that fans out via `coroutineScope { async {} }`, requesting only `TRACK_PREVIEW` per track. Deezer's 100ms rate limiter serializes the API calls naturally. 10 tracks ≈ 1s + overhead vs 20-30s before.

**Result**: Cold preview resolution dropped from ~2-3s to ~540ms per track. Batch of 10 tracks: ~5.5s. Batch of 20: ~10.8s (previously would timeout).

**Status**: Shipped

---

### 2026-03-26: v0.9.0 — Two new enrichment types: TRACK_PREVIEW and ARTIST_RADIO_DISCOVERY

**Context**: musicmeta's `ARTIST_RADIO` type was single-source (Deezer). Deezer's catalog skews mainstream, leaving niche and indie artists with thin or empty results. Separately, when radio or discovery results include tracks the user doesn't own, consumers had no way to let users audition them. Both gaps were addressable with existing Deezer and ListenBrainz infrastructure.

**Decisions**:

- **Separate types, not merged**: `ARTIST_RADIO` stays Deezer (curated, proprietary algorithm, familiar-adjacent). `ARTIST_RADIO_DISCOVERY` is new (ListenBrainz, community listening data, configurable depth). Radio playlists are ordered sequences, not unordered sets — merging destroys curation intent from both sources. Consumers choose which flavor to request, or both.

- **`TRACK_PREVIEW` as standalone type, not embedded in radio responses**: Preview resolution is context-agnostic — the same `enrich(forTrack(...), TRACK_PREVIEW)` call works for a radio result, a similar artist's top track, a similar album's track, or any other discovery surface. Embedding preview URLs inside radio responses would tie previews to one context and duplicate resolution logic. Standalone type, one feature serves every surface.

- **Auth gating per-capability, not per-provider**: ListenBrainz needs no key for existing endpoints (popularity, similar artists, discography). LB Radio requires a free user token. Only the radio capability is gated — same pattern as Last.fm/Fanart.tv/Discogs (key present → extra capabilities). No token → `ARTIST_RADIO_DISCOVERY` is silently absent, not an error.

- **`TRACK_PREVIEW` excluded from `DEFAULT_TRACK_TYPES`**: On-demand type only. Not every track lookup needs a preview; consumers request it explicitly when building discovery UIs.

- **`TRACK_PREVIEW` excluded from `RECOMMENDATION_TYPES`**: Previews are specifically for tracks the user doesn't have — catalog filtering (available-first/available-only) doesn't apply.

**Status**: Shipped

---

### 2026-03-24: v0.8.0 Production Readiness — four adoption blockers addressed

**Context**: External review identified four gaps blocking production adoption: no OkHttp adapter (every Android project has OkHttp already), no offline cache fallback, no bulk enrichment API, JitPack-only distribution. Flow-based progressive API was assessed and deliberately cut — identity resolution blocks all emission, so marginal benefit vs complexity.

**Decision**: Ship four targeted features as v0.8.0 without architectural rewrites.

**Key decisions**:
- **OkHttp 4.12.0, not 5.x**. OkHttp 5.x requires Kotlin 2.2.x stdlib as a transitive dependency, which would force consumers onto Kotlin 2.2.x even though musicmeta compiles with Kotlin 2.1.0. The upgrade belongs together with a future Kotlin 2.2 bump.
- **No `Accept-Encoding: gzip` in OkHttp adapter.** OkHttp adds this header automatically and handles transparent decompression. Setting it manually disables OkHttp's decompression and delivers raw gzip bytes to the JSON parser — a documented footgun in OkHttp's issue tracker.
- **`STALE_IF_ERROR` only, no `CACHE_FIRST`.** `CACHE_FIRST` (always serve cache, refresh in background) needs a background refresh coroutine scope, which is a different architecture. `STALE_IF_ERROR` covers the critical offline case with minimal complexity.
- **Stale only for `Error`/`RateLimited`, never `NotFound`.** `NotFound` means "the provider searched and found nothing" — serving stale data would be misleading. `Error` and `RateLimited` mean "the network failed" — stale is a reasonable fallback.
- **`enrichBatch()` is sequential, not pipelined.** Rate limiter naturally throttles at 1 req/sec for MusicBrainz. Cache hits return instantly. Real pipelining (concurrent identity resolution overlap) deferred until someone proves the simple version is a bottleneck.
- **OSSRH is dead (shut down June 2025).** The original plan targeted OSSRH URLs — research caught this before implementation. Switched to vanniktech `gradle-maven-publish-plugin` targeting `SonatypeHost.CENTRAL_PORTAL`.
- **vanniktech plugin 0.30.0, not 0.36.0.** v0.36.0 hardcodes `ANDROID_GRADLE_MIN = "8.13.0"` but the project uses AGP 8.7.3. v0.30.0 has the same `CENTRAL_PORTAL` DSL and works with AGP 8.0.0+.
- **Android javadoc jar disabled.** AGP 8.7.3 bundles Dokka 1.x (ASM8) which cannot parse Kotlin 2.1.0 sealed class metadata. Core and okhttp produce full javadoc jars. Android publishes `.aar` + `-sources.jar` + `.pom`. Re-enable with AGP 8.13.0+.

**Status**: Shipped

---

### 2026-03-24: Cache management API — closing the consumer dev map

**Context**: Audit of the v0.7.0 developer experience revealed that while build→get→search flows were clean, update/refresh operations required consumers to import `DefaultEnrichmentEngine` and hand-compute internal cache keys. This leaked an implementation detail: the entity key format (`"artist:Radiohead:GENRE"`) includes the type and uses MBID when available, with name-alias keys for cache convergence after disambiguation. No external consumer should need to know this.

**Decision**: Four new methods on `EnrichmentEngine` interface, plus `forceRefresh` on `enrich()`.

**Key design decisions**:
- **`invalidate(request, type?)` iterates all `EnrichmentType.entries` when type is null.** The cache interface's `invalidate(entityKey, null)` was designed for a different key format — entity keys embed the type, so "delete all for this entity" requires computing a key per type. 32 iterations is trivial cost for a cache operation.
- **`invalidateKeys()` private helper shared by `invalidate()` and `forceRefresh`.** Both need to clear the primary key AND the name-alias key (when MBID is present). Extracted to avoid duplication.
- **`forceRefresh` as a parameter on `enrich()`, not a separate method.** Keeps the full pipeline intact (identity resolution, provider fan-out, caching, catalog filtering). Implementation: invalidate requested types before the normal flow. Profile extensions inherit it for free via delegation.
- **Entity key functions extracted to `EntityKey.kt`.** `entityKeyFor()` and `entityKeyForName()` were `DefaultEnrichmentEngine` companion methods — internal details that tests referenced. Moved to package-internal top-level functions. Companion keeps thin delegation wrappers to avoid breaking existing test references.
- **`markManuallySelected`/`isManuallySelected` delegate directly.** These are simple key-computation wrappers — no aliasing complexity since manual selection is always on the primary key.

**Status**: Active

---

### 2026-03-24: Disambiguation signals — identity match score and "did you mean?"

**Context**: The engine's auto mode (`enrich()` with just a name) silently picks the best MusicBrainz match. Developers had no way to know if the match was confident ("Radiohead" → score 100) or ambiguous ("Bush" → score 80, could be British or Canadian band). The `search()` → pick → `enrich(mbid=...)` flow existed for manual disambiguation, but there was no signal telling developers *when* to use it. Worse, when no match met the threshold, all candidates were silently discarded — the developer got NotFound with zero context.

**Decision**: Two complementary signals on `EnrichmentResult`:
1. `Success.identityMatchScore: Int?` — stamps every result with the MusicBrainz search score (0-100) after identity resolution.
2. `NotFound.suggestions: List<SearchCandidate>?` — carries near-miss candidates when identity resolution finds results below the match threshold.

**Key design decisions**:
- **`IdentityMatch` enum** (`RESOLVED`, `BEST_EFFORT`, `SUGGESTIONS`) on both `Success` and `NotFound`. Single field to route on — no magic numbers, no checking multiple fields. `null` means identity resolution wasn't needed (MBID pre-provided or cached).
- **Short-circuit on `SUGGESTIONS`**. When identity fails with near-miss candidates, the engine returns `NotFound(SUGGESTIONS)` immediately for all types — no provider fan-out, no wasted API calls. The developer shows "did you mean?", user picks, re-enriches with the correct MBID. Saves ~14s vs the old behavior of querying all providers with a bad name.
- **`BEST_EFFORT` for unverified results**. When identity fails without candidates (truly nothing found), providers try fuzzy searches. Results are stamped `BEST_EFFORT` so the developer can show a warning.
- **`identityMatchScore` on 0-100 scale** (only when `RESOLVED`), matching `SearchCandidate.score`. Distinct from `confidence: Float` (0.0-1.0) which measures data quality, not identity quality.
- **Suggestions reuse `SearchCandidate`** — the same type returned by `engine.search()`. Contains title, artist, score, disambiguation text, and MBID.
- **Fuzzy fallback search**. MusicBrainz quoted search (`artist:"Radohead"`) returns zero results for typos. When empty, falls back to unquoted Lucene `~` fuzzy search to find near-miss candidates for suggestions. One extra API call, only in the typo case.
- **Max 3 suggestions** to keep the response lightweight. The developer can call `search()` for more.

**Status**: Active

---

### 2026-03-24: ARTIST_TOP_TRACKS — fetch everything, let devs filter

**Context**: Apps need an artist's most popular tracks for "Top Tracks" UI widgets. The data existed across three providers (Last.fm scrobble counts, ListenBrainz listen counts + duration + album, Deezer stream ranking) but wasn't surfaced as its own type — it was buried inside `ARTIST_POPULARITY.topTracks` from ListenBrainz only.

**Decision**: New `ARTIST_TOP_TRACKS` enrichment type with `TopTrackMerger`. Three providers merged: Last.fm (`artist.gettoptracks`, up to 1000), Deezer (`/artist/{id}/top`, up to 100), ListenBrainz (`/popularity/top-recordings-for-artist`, unlimited).

**Key design decisions**:
- **No artificial limit on output.** Early iterations had `topTracksLimit` in config, but this is opinionated — the developer should call `.take(n)` for their UI needs. Each provider fetches its API max; the merger returns everything deduplicated and ranked. Consistent with the "never discard data" principle.
- **New `TopTrack` data class** rather than reusing `PopularTrack`. `PopularTrack` was too thin (no album, duration, listenerCount, sources). `TopTrack` has everything an app needs for a rich track list.
- **ListenBrainz was discarding data.** The existing `getTopRecordingsForArtist` parser threw away `total_user_count`, `length`, and `release.name` — three fields the API already returned. Fixed to capture everything.
- **Merger deduplicates by normalized title + MBID.** MBID-based matching takes priority, so "Karma Police" and "Karma Police (Remastered)" merge when they share an MBID. Listen counts are summed across providers; highest listener count is kept.
- **Added to `RECOMMENDATION_TYPES`** so CatalogProvider filtering applies automatically. If a developer configures catalog awareness, top tracks are filtered by availability just like radio and similar artists.

**Status**: Active

---

### 2026-03-24: "Never discard data" — ArtworkMerger and multi-provider images

**Context**: Testing with niche artist "Ochre" revealed ARTIST_PHOTO returned NotFound despite Deezer having a profile photo. Investigation found Deezer's `searchArtist()` response includes `picture_small/medium/big/xl` but we discarded them. Same for Discogs — `getArtist()` returns `images[]` but we only parsed `members`. Broader problem: artwork types used short-circuit provider chains (first `Success` wins), so even when multiple providers had different images, only one was returned.

**Decision**: Establish "never discard data" as a core project principle. Make artwork types mergeable. Create `ArtworkMerger` following the `ResultMerger` pattern.

**Key design decisions**:
- **`ArtworkSource` model and `Artwork.alternatives` field** (backward compatible). The highest-confidence provider's image becomes the primary `url`/`thumbnailUrl`/`sizes`. Other providers' images are available via `alternatives: List<ArtworkSource>`. Consumers who only read `url` see no change; consumers who want all images check `alternatives`.
- **Not a flat `sizes` list.** Early design considered putting all provider images into one `sizes` list with provider labels. Rejected because `ArtworkSize` represents different resolutions of the *same* image — a Deezer press photo and a Wikidata editorial photo are *different images*, not size variants.
- **Deezer artist photos added** (priority 60, no API key needed). Covers niche artists that Wikidata/Fanart.tv miss.
- **Discogs artist photos added** (priority 40, requires token). Zero extra API calls — `getArtist()` was already called for BAND_MEMBERS, images were in the same response.
- **ARTIST_PHOTO now has 5 providers**: Wikidata (100), Fanart.tv (80), Deezer (60), Discogs (40), Wikipedia.
- **ALBUM_ART also made mergeable**: 5 providers (CAA, Deezer, iTunes, Fanart.tv, Wikipedia) now contribute with alternatives.
- **CD_ART added to Cover Art Archive**: The API already returned CD/Medium image types in metadata but the capability wasn't registered.

**Status**: Active

---

### 2026-03-24: Performance + quality fixes

**Context**: Artist enrichment for Radiohead took ~16 seconds. Band members showed "19 members: Colin Greenwood, Colin Greenwood, Colin Greenwood..." (duplicates from multiple MusicBrainz membership periods). Solo artist "Ochre" returned NotFound for band members despite being a known person.

**Fixes**:
- **MusicBrainz lookup caching** (`cachedArtistLookup` with Mutex). BAND_MEMBERS, ARTIST_LINKS, and GENRE all called `lookupArtist(mbid)` separately — 3 redundant API calls through the 1.1s rate limiter. Now uses `lookupArtistWithRels` (superset) cached by MBID. First call populates cache, subsequent calls return instantly. Saves ~2.2s per artist enrichment.
- **Concurrent `resolveAll`** in ProviderChain. Mergeable types (GENRE, SIMILAR_ARTISTS, ARTIST_PHOTO) previously queried providers sequentially via `forEachEligible`. Now launches each provider in its own `async` via `coroutineScope`. Combined with MB caching, reduced Radiohead from ~16s to ~10s.
- **Band member deduplication.** MusicBrainz returns multiple `member-of-band` relationships per person (different roles, time periods). Now deduplicates by member ID — merges roles (e.g., "guitar, keyboards" for Jonny Greenwood) and picks the widest date range.
- **Solo artist handling.** When MusicBrainz artist type is "Person" and no member relationships exist, returns the artist themselves as the sole member using `sort-name` for real name (e.g., "Christopher Leary (Ochre)").
- **ErrorKind.TIMEOUT.** When the engine timeout fires, timed-out types now get explicit `EnrichmentResult.Error(errorKind = ErrorKind.TIMEOUT)` instead of being silently missing from the result map.
- **SearchCandidate.disambiguation.** MusicBrainz returns disambiguation text (e.g., "British rock band" vs "Canadian band") — now included in search results so developers can show users meaningful choices for the pick-and-enrich flow.

**Status**: Active

---

### 2026-03-23: SIMILAR_TRACKS multi-provider merge via Deezer track radio

**Context**: SIMILAR_TRACKS only had Last.fm (`track.getSimilar`). The ROADMAP noted "Deezer radio is artist-seeded, different semantics" but that referred to ARTIST_RADIO (`/artist/{id}/radio`). Deezer also has `/track/{id}/radio` — a track-seeded endpoint returning ~25 similar tracks.

**Decision**: Add Deezer as a second SIMILAR_TRACKS provider with a `SimilarTrackMerger`, following the exact pattern established by `SimilarArtistMerger` in v0.6.0. The merger deduplicates by normalized title+artist, uses additive scoring capped at 1.0, and merges sources/identifiers.

**Key details**:
- `DeezerApi.searchTrack()` finds the Deezer track ID via `/search/track`, guarded by `ArtistMatcher.isMatch()`
- `DeezerApi.getTrackRadio()` calls `/track/{id}/radio`, reuses `DeezerRadioTrack` model (same response shape as artist radio)
- Position-based matchScores for Deezer results (same formula as `toSimilarArtists`)
- `SimilarTrack` gained a `sources: List<String>` field (matching `SimilarArtist` pattern)
- SIMILAR_TRACKS is now a mergeable type in the engine — all providers queried, results merged

**Status**: Active

---

### 2026-03-22: v0.6.0 Recommendations Engine — key architectural decisions

**Context**: Built four new recommendation enrichment types on top of the v0.5.0 engine. Several design decisions worth documenting.

**ResultMerger and CompositeSynthesizer extraction**: `DefaultEnrichmentEngine` was handling both mergeable-type dispatch (GENRE) and composite-type dispatch (ARTIST_TIMELINE) inline. With SIMILAR_ARTISTS becoming mergeable and GENRE_DISCOVERY becoming composite, these responsibilities were extracted into `ResultMerger` and `CompositeSynthesizer` interfaces. The engine now delegates to `ProviderRegistry` which holds lists of both. GenreMerger and TimelineSynthesizer were kept as objects (singletons) — stateless strategy pattern preserved. This allowed SimilarArtistMerger and GenreAffinityMatcher to plug in without modifying the engine.

**SimilarAlbumsProvider is standalone (not composite)**: The synthesizer interface requires pure functions with no I/O. `SIMILAR_ALBUMS` needs two Deezer API calls (related artists + their top albums). Placing this inside a synthesizer would break the no-I/O invariant. Decision: `SimilarAlbumsProvider` is a standalone `EnrichmentProvider` that does all its own I/O, identical to how every other provider works. No special treatment in the engine.

**CatalogProvider as fun interface (SAM)**: `CatalogProvider` exposes a single suspending method `checkAvailability`. Declaring it as `fun interface` allows consumers to use lambda syntax for simple implementations — `CatalogProvider { queries -> queries.map { CatalogMatch(available = true, source = "local") } }`. This is the same pattern used by Java's `Comparator` and Kotlin's `Comparator` functional interface.

**Catalog filtering extracted to CatalogFilter.kt**: The private helpers (`applyCatalogFiltering`, `toQueries`, `applyMode`, `reorderData`) were extracted to `CatalogFilter.kt` as top-level functions. The engine imports and calls them. This made the filtering logic independently testable.

**Deezer artist ID resolution pattern**: Providers that need a Deezer artist ID (SIMILAR_ARTISTS, ARTIST_RADIO, SIMILAR_ALBUMS) all follow the same pattern: check `identifiers.extra["deezerId"]` first (cache hit path), fall back to `DeezerApi.searchArtist()` + `ArtistMatcher.isMatch()` name guard, return `NotFound` if no confident match. The guard prevents false positives on artist name searches — Deezer's fuzzy search will return results for almost any query.

**Status**: Active

---

### 2026-03-21: Showcase demo and `runTest` discovery

**Context**: Built a comprehensive E2E showcase test to exercise every enrichment type across diverse queries (Radiohead, Kendrick Lamar, AC/DC, Bjork, instrumentals, obscure artists). The goal was both to demo capabilities and to find where the library needs improvement.

**Discovery — `runTest` breaks all E2E tests**: Every `engine.enrich()` call returned empty results under `runTest`. Root cause: `runTest` uses virtual time, and the engine's `withTimeout(30_000)` fires based on virtual time. Rate limiter `delay()` calls advance virtual time instantly, causing the timeout to fire before any real HTTP response arrives. Error message from kotlinx.coroutines confirms: *"Timed out after 30s of _virtual_ (kotlinx.coroutines.test) time."* All existing E2E tests (16 tests in `RealApiEndToEndTest`) were silently broken — they were passing only because the assertions happened to pass on empty results (they didn't). Switched all E2E tests to `runBlocking`.

**Discovery — identity resolution skipped wikidata/wikipedia for artists**: When the engine called MusicBrainz for identity resolution with type=GENRE, the provider's optimization (`type in RELATION_DEPENDENT_TYPES`) skipped the full lookup that gets wikidata/wikipedia URLs. This meant ARTIST_PHOTO (Wikidata) and ARTIST_BIO (Wikipedia) always failed for artist requests. Fixed by removing the type gate — always do the full lookup when the search result is missing wikidata/wikipedia.

**Discovery — `extractResolution` mismatch**: The existing E2E tests' `extractResolution` helper looked for `IdentifierResolution` data in results, but the engine stores `Metadata` data with `resolvedIdentifiers` attached to the Success wrapper. This was a stale pattern from before the engine was refactored. Fixed to reconstruct from current data model.

**Lesson**: E2E tests against real APIs should use `runBlocking`, not `runTest`. The virtual-time model in `runTest` is designed for unit tests with faked dependencies, not for integration tests with real I/O. The `RateLimiter` is particularly problematic because it uses `System.currentTimeMillis()` for the clock but `delay()` for waiting — a mismatch that causes artificial time accumulation under virtual time.

**Status**: Active

---

### 2026-03-21: Open-source readiness — remove opinionation

**Context**: musicmeta was extracted from Cascade (Android music player) as a standalone library. A review found several hardcoded values and Cascade-specific assumptions that would limit adoption by other apps.

**Changes**:
- Provider chain priorities made configurable via `EnrichmentConfig.priorityOverrides`. Apps can reorder which provider is tried first for each enrichment type without modifying provider code.
- Artwork sizes moved from engine-level config to per-provider constructor params. Each API has different size semantics (CAA uses pixel sizes in URLs, iTunes uses string replacement, Wikidata uses width params), so a single "preferred size" in config was misleading — it wasn't wired to anything.
- MusicBrainz `minMatchScore` (was hardcoded at 80) now a constructor param. Apps with obscure catalogs or non-Latin scripts can lower it.
- `ArtistMatcher.isMatch()` token overlap threshold now configurable (was hardcoded 0.5).
- Room database name extracted to a public constant so apps know what to change.
- All silent `catch (_: Exception)` blocks now log through `EnrichmentLogger`.
- Removed all Cascade references from test User-Agent strings.

**Rationale**: A library should let consumers tune behavior without forking. Constructor params with sensible defaults preserve backwards compatibility while opening up flexibility.

**Status**: Active

---

### 2026-03-21: Extraction from Cascade

**Context**: Cascade's metadata enrichment started as in-app API clients (MusicBrainz, Wikidata, Cover Art Archive). As the provider count grew to 11, the enrichment logic outgrew the app's data layer. The engine also has value as a standalone open-source library.

**Decision**: Extract into a two-module library:
- `musicmeta-core` (pure Kotlin/JVM) — engine, providers, HTTP, caching interface. Zero Android dependencies.
- `musicmeta-android` (optional) — Room cache, Hilt DI wiring, WorkManager base worker.

**Architecture**:
- `EnrichmentEngine` orchestrates the pipeline: cache check → identity resolution → fan-out to provider chains → confidence filtering → cache store.
- `ProviderRegistry` builds a `ProviderChain` per `EnrichmentType`, ordered by `ProviderCapability.priority`. The chain tries providers in order; `Success` short-circuits, `NotFound` falls through.
- Each provider is self-contained: own `*Api.kt` (HTTP), `*Models.kt` (parsing), `*Provider.kt` (enrichment logic). Adding a provider means implementing `EnrichmentProvider` — no engine changes needed.
- `CircuitBreaker` per provider (shared across chains) protects against cascading failures from a single degraded API.

**Trade-offs**:
- **Pro**: Reusable across apps, testable in isolation, pure Kotlin core runs on any JVM
- **Pro**: Provider architecture is open/closed — add new sources without modifying engine
- **Con**: Cascade's data layer now needs a mapping layer between engine types and domain models
- **Con**: JitPack dependency for consumers (vs composite build alternative)

**Key design choice — MusicBrainz as identity backbone**: MusicBrainz resolves MBIDs, Wikidata IDs, and Wikipedia titles. Downstream providers (Cover Art Archive, Wikidata, Wikipedia, Fanart.tv) use these IDs for precise lookups instead of fuzzy search. This dramatically improves accuracy but means MusicBrainz is a soft dependency. `enableIdentityResolution = false` provides an escape hatch.

**Status**: Active
