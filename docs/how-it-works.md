# How musicmeta Works

> A complete guide to the enrichment pipeline — from `enrich()` call to results.

## What It Does

musicmeta is a drop-in library that gives any music app **everything** about an artist, album, or track from a single call. You pass a title and artist name, and get back artwork, genres, bios, lyrics, credits, similar artists, popularity stats, and more — aggregated from 11 public APIs behind the scenes.

```kotlin
val artist = engine.artistProfile("Radiohead")

println(artist.bio?.text)          // "Radiohead are an English rock band..."
println(artist.genres.map { it.name }) // [alternative rock, art rock, electronic]
println(artist.photo?.url)         // https://upload.wikimedia.org/...
println(artist.topTracks)          // Top tracks with listen counts
println(artist.similarArtists)     // Similar artists from multiple sources
```

The consumer never needs to know which APIs exist, how they authenticate, or how to correlate identifiers across services.

This document traces the pipeline. How to *call* it — the three API tiers, error handling,
disambiguation, caching, batching and the OkHttp adapter — is in [guides/](guides/README.md).

---

## The Pipeline

```
enrich(request, types, forceRefresh)
      │
      ▼
┌────────────────────────────┐
│ 1. Force Invalidate        │── forceRefresh=false ──→ skip
└─────────────┬──────────────┘
              │ forceRefresh=true → invalidate request-tuple key + eligible canonical alias
              ▼
┌────────────────────────────┐
│ 2. Cache Check             │── all hit ──→ return cached
└─────────────┬──────────────┘
              │ miss (uncached types only proceed)
              ▼
┌──────────────────────────────────────────┐
│ 3. Identity Resolve (MusicBrainz)        │
│    searches title/artist                 │
│    → MBID, Wikidata ID, Wikipedia title  │
└─────────────┬────────────────────────────┘
              │ outcomes:
              │   success → merge IDs into request, continue
              │   suggestions → kept at the top level; fan-out still runs (step 4)
              │   not needed → skip (MBID already provided)
              ▼
┌────────────────────────────────────────────────────┐
│ 4. Concurrent Type Resolution (fan-out)            │
│                                                    │
│  Standard  ──→ chain.resolve()   (first wins)      │
│  Mergeable ──→ chain.resolveAll() (all win)        │
│  Composite ──→ resolve deps → synthesize           │
└─────────────┬──────────────────────────────────────┘
              │
              ▼
┌────────────────────────────┐
│ 5. Confidence Filter       │── drop below threshold (default 0.5)
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 6. Catalog Filter          │── reorder/filter recommendations
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 7. Stale Fallback          │── STALE_IF_ERROR: serve expired cache on Error/RateLimited
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 8. Provenance Stamp        │── mark provider results with LookupProvenance
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ 9. Cache Store + Alias     │── save with TTL + eligible canonical alias (skip stale results)
└─────────────┬──────────────┘
              │
              ▼
  return EnrichmentResults(raw, requestedTypes, identity)
```

### Step 1: Force Refresh Invalidation

When `forceRefresh=true`, the engine invalidates the complete request-tuple cache entry for every
requested type before proceeding. Any eligible canonical alias established by identity resolution
is invalidated as well, so stale data cannot survive under either key. The same tuple policy applies
to direct requests and transitive composite dependencies.

### Step 2: Cache Check

For each requested type, the engine checks the cache using a versioned, collision-free encoding of
the complete request tuple: scope and type, names, album/track selector inputs, every explicit
identifier, and sorted extra fields. Composite types use the same complete tuple for their
transitive dependency routes. An exact-bearing request never falls back to a bare-name key, and a
different tuple is never treated as equivalent merely because it contains the same MBID or provider
id. Cache hits go directly into the result map; only misses proceed to resolution. If every type is
a hit, the engine returns without identity resolution or provider calls.

### Step 3: Identity Resolution

The key insight: most providers need identifiers (MusicBrainz ID, Wikidata ID) for precise lookups, but the consumer only has a title and artist name.

**MusicBrainz acts as the identity backbone.** The engine first checks whether identity resolution is needed — it scans all uncached types and their provider chains' identifier requirements. If no provider needs an identifier the request lacks, identity resolution is skipped entirely.

A track request is the one exception, and it is a consequence of what `musicBrainzId` means. The field is polymorphic — a release id on an album request, an artist id on an artist request, a *recording* id on a track request — so a capability declaring `MUSICBRAINZ_ID` is declaring "an id of the request's own kind", not which kind it can serve. A recording id cannot stand in for the release-group id cover art is keyed on, so a track request carrying no `musicBrainzReleaseGroupId` still resolves identity even when it carries a recording id.

When needed, MusicBrainz searches by title/artist and returns:
- `musicBrainzId` (MBID) — the universal music identifier
- `musicBrainzReleaseGroupId` — for album editions
- `wikidataId` — extracted from MusicBrainz URL relations
- `wikipediaTitle` — extracted from MusicBrainz URL relations, English only: a relation pointing at
  another language wiki is ignored, leaving this null so `WikipediaProvider` falls back to the
  Wikidata `enwiki` sitelink
- Provider-specific IDs (Discogs, etc.) — stored in the `extra` map

These identifiers are merged into the request via `request.withIdentifiers(mergedIds)`. All downstream providers then use these IDs for precise lookups instead of fuzzy search.

**Name backfill, into a blank field only.** A request from `EnrichmentRequest.forTrackByMbid` (or its album/artist siblings) names an identifier and nothing else, and every provider but MusicBrainz searches by name. After the merge, the engine fills each blank name field from the entity identity resolution settled on — the recording or release title, and the `artist-credit` joined with MusicBrainz's own join phrases ("Queen & David Bowie"). A name the caller supplied is never overwritten: MusicBrainz keeps a variant like "Comfortably Numb (Live at Earls Court)" in the disambiguation rather than the title, so replacing the caller's string would send Deezer and LRCLIB after the studio take. An identifier MusicBrainz holds nothing under leaves the names blank — roughly seven in ten real third-party recording MBIDs, so this is the common path and not a corner. A request still naming no entity after the backfill is not fanned out to the providers that search by name: for each type, only providers whose capability is keyed on an identifier are asked, and a type none of them can serve is an honest `NotFound` from the chain. Nothing is asked to search for the empty string, on any provider — the engine enforces that, not MusicBrainz alone (`NamelessRequestFanOutTest`).

**An identifier the caller supplied is checked wherever it is used.** Every type that can answer
from one reaches its entity by its own route — genres, band members, artist links, popularity,
discography, album tracks, credits and release editions each look something different up — and each
route compares what came back against what the request described. The comparison is on the
*artist*, never the title, because a remaster, an edition or a localised title differs from what a
caller typed while still being the entity they meant. It reports only confident disagreement: a
supplied name matching any name MusicBrainz holds for that entity is no contradiction, and neither
is a pair of names written in scripts that cannot be compared. For an album there is a second,
structured check on the same terms — an album cannot predate its own first release, so a request
`year` two or more years earlier than the release group's is evidence of a different album.

When a check fires, the request falls back to the name it carries and `identity.status` reports
`CONTRADICTED`, which outranks every other status: the results beside it may be complete and
correct, and the bad identifier is the one thing this call can tell the caller that nothing else
will. `identity.identifiers` may still carry the identifier just disowned — resolving by name
supplies an entity, not an identifier — so read the status before trusting that field, and never
pass it to the next call. `CREDITS` and `RELEASE_EDITIONS` have no name route to recover by, so they
answer `NotFound` instead of falling back.

**A provider's candidates are verified against the names identity resolution found, not only the
one you asked with.** Deezer, iTunes and Discogs search by name, and a catalogue that carries an
artist only under a romanization answers a request written in another script with candidates whose
names cannot be compared to it — a request for 東京事変 is answered with "Tokyo Jihen". Every
name-search provider first matches on the name the request carries, unchanged; only when that
accepts nothing does it consult the alias pool MusicBrainz holds for the resolved entity, and a
candidate is accepted there only as the *same* name as one of those aliases, never by containment or
partial overlap. A candidate no known name form matches is still rejected. The query sent upstream is
always the caller's own string: rewriting it to a romanization picks whatever that spelling ranks
first, which is a different artist often enough to matter.

Confidence records which of the two verified it. A hit under the requested name reports exactly the
score it always has; one verified through the pool is scaled by the alias's tier — 0.95 for a name
the entity is published under, 0.85 for any other alias — so a cross-script match is never reported
as confidently as a direct one. Names are all the pool has: two acts that genuinely share an alias
string cannot be told apart by it, so 0.85 is also what the wrong act would score if a provider
returned it under that name. The pool costs a MusicBrainz lookup only for an album or track
request whose provider matching has already failed on the requested name; on an artist request it
rides on the search identity resolution has already made, and a request whose providers match
directly never pays for it at all.

### Type discovery: what a bare MBID names

`EnrichmentEngine.discoverMbidEntityType(mbid)` answers `RECORDING`, `RELEASE`, `ARTIST` or nothing, so a consumer holding an identifier can build the right request. MusicBrainz has no endpoint that takes an id without its type — a wrong-type lookup answers 404, exactly as the right type does for an id it no longer holds — so the answer is a probe of the three types in order: **recording, then release, then artist**. That costs 1 request for a recording, 2 for a release, 3 for an artist and 3 for a dead id, on a 1 req/s limiter; the counts are asserted in `MusicBrainzEntityTypeDiscoveryTest`. Recording leads because that is where third-party identifiers come from. Each probe shares the enricher's per-call memo, so discovery inside an `enrich()` that already looked the entity up is free, and a miss is paid for once per call. It is an interface method whose default throws — an engine with no MusicBrainz identity provider has nothing to probe — so an engine that wraps another must forward it, or the call reaches that default instead of the engine underneath.

**ListenBrainz was measured against this and lost** (2026-08-12). `GET /1/metadata/recording/` is keyless, on a 2.5/s limiter, and answers a recording MBID with its name in one request — but a *non*-recording MBID and a dead one both come back `{}`, so it cannot tell "not a recording" from "not held". A miss therefore still pays the MusicBrainz release and artist lookups, making LB-first 1 request cheaper only when the id is a recording and 1 request dearer on every other outcome — including the dead case, which was seven in ten of a real third-party population (1212 of 1710, 2026-08-12). It would also add a failure mode this has none of: an LB 5xx is an outage and must never read as absence. Recorded so it is not re-proposed.

**Cache alias:** when canonical resolution supplies names for the request, the result may also be
written under the corresponding identifier-free name/selector key so a later name-only lookup can
reuse the canonically selected entity. An exact-bearing request never reads through that alias, and
a provider-native exact-id result is not implicitly aliased to a bare name. The tuple key itself is
versioned; a format change intentionally causes a one-time miss. Custom cache implementations must
treat keys as opaque.

**Suggestions do not veto the fan-out:** If MusicBrainz can't find an exact match but has near-miss candidates, that is a statement about MusicBrainz's own lookup, not a global "nothing can be fetched" decision. Every uncached type still resolves through step 4 exactly as it would under a plain unresolved identity — each provider's own `ProviderChain` eligibility (availability, identifier requirements, circuit breaker) decides whether it runs. A `NONE`-identifier provider like Deezer's track preview search still answers; an MBID-only provider without an MBID still doesn't. Surviving `Success` results carry a `LookupProvenance` reflecting the fuzzy search that produced them (step 8), while the call's `CanonicalStatus` stays `AMBIGUOUS`. The suggestion list itself is attached once, to `EnrichmentResults.identity`, never copied onto a per-type result — the consumer can present it as "Did you mean?" and re-enrich with the selected candidate.

### Step 4: Concurrent Type Resolution

All requested types resolve **concurrently** via `coroutineScope { async {} }`. Three resolution modes:

#### Standard (short-circuit)
Most types. A `ProviderChain` tries providers in priority order:
- Priority 100 (primary) → 50 (fallback) → 30 (tertiary)
- First `Success` wins, remaining providers skipped
- `NotFound` and `Error` both fall through to the next provider
- If no provider succeeds, the last failure (`Error` or `RateLimited`) is returned; only if there was none is the outcome `NotFound`
- Circuit-broken or unavailable providers skipped
- Providers whose identifier requirements aren't met are skipped

#### Mergeable (collect-all)
Types where multiple providers contribute complementary data. The chain calls **every** eligible provider concurrently and collects all `Success` results. A type-specific `ResultMerger` then combines them:

| Merger | Type(s) | Strategy |
|--------|---------|----------|
| `GenreMerger` | GENRE | Normalizes tags, deduplicates, sums confidence (capped 1.0), merges sources; curated genres rank ahead of community tags |
| `ArtworkMerger` | All 8 artwork types | Highest-confidence as primary, others as `alternatives` |
| `SimilarArtistMerger` | SIMILAR_ARTISTS | Deduplicates by name, sums matchScores then rescales them against the merged maximum, merges sources |
| `SimilarTrackMerger` | SIMILAR_TRACKS | Deduplicates by name, sums matchScores, merges sources |
| `TopTrackMerger` | ARTIST_TOP_TRACKS | Deduplicates by MBID or title, sums listen counts |

Example merged genre result: `alternative rock (0.70, [musicbrainz, lastfm])` — higher confidence when multiple providers agree.

#### Composite (synthesize from sub-types)
Types that are synthesized from other resolved types rather than fetched from a provider:

| Synthesizer | Type | Dependencies | Strategy |
|-------------|------|-------------|----------|
| `TimelineSynthesizer` | ARTIST_TIMELINE | ARTIST_DISCOGRAPHY + BAND_MEMBERS | Extracts chronological events (formed, albums, member changes) from identity metadata + sub-type results |
| `GenreAffinityMatcher` | GENRE_DISCOVERY | GENRE | Looks up each input genre tag in a static taxonomy (189 relationships across 12 genre families), scores neighbors by `inputConfidence * relationshipWeight` |

The engine resolves dependencies first (standard rules), then passes results + identity metadata to the synthesizer. Sub-types are excluded from returned results unless the caller explicitly requested them.

Dependencies are part of the same fan-out, with no barrier in front of it: each composite is driven
by one coroutine that waits on its own dependencies and settles as soon as they land, so a composite
whose dependencies are fast never queues behind an unrelated slow type. A dependency that is itself
a composite pulls in its own dependencies, to any depth.

**A dependency is resolved by its own registration, not by the request.** A type's role — composite,
mergeable or standard — comes from whether a synthesizer or a merger is registered for it, never
from whether the caller named it. A dependency with a `ResultMerger` is therefore collected from
every eligible provider and merged exactly as it would be had it been requested directly, even when
only the composite was asked for.

`DEFAULT_SYNTHESIZER_DEPENDENCIES` maps each built-in composite type to the sub-types it is derived
from, for a caller building its own attribution: a synthesized result names a synthesizer, which is
nobody a reader can be sent to and nothing an upstream's terms cover, so credit the providers that
answered its sources instead. It covers the built-ins only — a synthesizer registered through
`Builder.addSynthesizer` is not in it.

`Builder.build()` refuses two graphs outright, with `IllegalArgumentException`: synthesizers whose
dependencies form a cycle (one depending on its own type included — the message names every type on
the cycle), and a type registered with both a `CompositeSynthesizer` and a `ResultMerger`, whose
merger could then never run.

### Progressive delivery

`enrichProgressive()` is not a parallel counterpart to `enrich()` — it is the path `enrich()` runs
through. `enrich()` is `enrichProgressive(...).last()`, so both drive the identical fan-out and
pipeline (Steps 4–8 below apply per type, not once at the end), and the stream's terminal emission
and `enrich()`'s return are always the same value, timeout included.

What the stream adds is cadence. It emits a cumulative snapshot each time a type settles, so a
caller sees fast types — a cache hit, a single-provider lookup — before slow ones finish, plus one
extra snapshot the moment live identity resolution settles, before any type has. That emission
carries the verdict and, where MusicBrainz offered candidates, `identity.suggestions`, so a
"did you mean?" prompt need not wait for the first provider to answer. Until it arrives, a
pre-terminal snapshot's `identity.status` reads `RESOLVING`.

**Cancellation is complete-and-cache, not abort-and-forfeit.** Cancelling the collecting coroutine —
`take(1)`, leaving composition, or cancelling whoever called `enrich()` — detaches that collector
from the fan-out already under way. The fan-out is not cancelled with it: it keeps running until it
settles or `enrichTimeoutMs` expires, and its cache write-back still happens, so a subsequent
equivalent call is typically a cache hit rather than a re-fetch. The continuing work is bounded to
the one run for this exact request/types/`forceRefresh` combination, so cancelling and re-calling
the same thing N times never multiplies upstream traffic by N. See
[guides/streaming.md](guides/streaming.md) for the API and its contract, and §Engine lifecycle for
what `close()` does to a run still in flight.

### Step 5: Confidence Filtering

Each provider returns a confidence score (0.0–1.0):

| Score | Meaning | Example |
|-------|---------|---------|
| 1.0 | Exact ID lookup | MusicBrainz by MBID, CAA by MBID |
| 0.95 | Authoritative source | Wikipedia bio, Wikidata properties |
| 0.80 | Good fuzzy match | Deezer search with artist confirmation |
| 0.60 | Weak fuzzy match | iTunes search |
| < 0.50 | Filtered out by default | — |

Results below `config.minConfidence` (default 0.5) are converted to NotFound. Per-provider confidence overrides let you tune thresholds at runtime without code changes.

### Step 6: Catalog Filtering

For recommendation types only (SIMILAR_ARTISTS, SIMILAR_ALBUMS, ARTIST_RADIO, ARTIST_RADIO_DISCOVERY, SIMILAR_TRACKS, ARTIST_TOP_TRACKS). When a `CatalogProvider` is configured, the engine checks which recommended items are available in the consumer's music catalog:

- **AVAILABLE_ONLY** — remove unavailable items; NotFound if none remain
- **AVAILABLE_FIRST** — reorder: available items first, then unavailable
- **UNFILTERED** (default) — no filtering

This lets a music player show only recommendations the user can actually play.

Filtering runs per type, as that type settles, and settlements do not queue behind one another — so
`CatalogProvider.checkAvailability` is called concurrently by as many coroutines as there are
recommendation types settling in one call. **A stateful implementation must be thread-safe.**

A `checkAvailability` that throws costs that type its filtering, never the call. The type degrades
to its unfiltered results and the `EnrichmentResult.Success` comes back with
`isCatalogDegraded = true`, rather than the exception escaping `enrich()`: filtering ranks and trims
data the providers already fetched, so keeping that data — and the cache write that follows — beats
losing both. Consumers can read the flag to show an "unranked" indicator, or to hide
availability-dependent UI for that result.

`isCatalogDegraded` is call-scoped, not a stored fact. Every serve, live or from cache, is
normalized to `false` before this call's own check runs, and only this call's own throw sets it —
so a `CatalogProvider` that has since recovered cannot haunt a later cache hit, and one that started
failing after a healthy write is not masked by it. It is also not a "still settling" signal: it can
be `true` on the terminal emission, meaning settled but unranked. `UNFILTERED` mode and a
`CatalogProvider` that simply is not configured are deliberate configuration rather than
degradation, and never set it.

### Step 7: Identity Model

Two independent facts describe an `enrich()` call, never one merged value:

- `EnrichmentResults.identity.status: CanonicalStatus` — the MusicBrainz canonical resolution
  outcome for this call, settled exactly once and never backdated. Never `null`: every reason
  resolution did not run has its own explicit state, so a consumer can never mistake "not attempted"
  for "confident". A pre-terminal `enrichProgressive()` emission can read `RESOLVING` until it
  settles.
- `EnrichmentResult.Success.provenance: LookupProvenance` — how that specific provider selected
  the entity behind its own result. This describes that provider's own lookup, not whether
  MusicBrainz agreed.

| `CanonicalStatus` | Meaning |
|---|---|
| `RESOLVED` | MusicBrainz resolution completed. `identity.matchScore` (0.0–1.0) scores *how the lookup went*, never that the entity is the one described — see below. |
| `AMBIGUOUS` | MusicBrainz found no confident match, but offered candidates. See `identity.suggestions`. |
| `UNRESOLVED` | MusicBrainz searched and found neither a match nor candidates. |
| `FAILED` | The identity provider errored (usually transient); a retry may resolve. |
| `RESOLVING` | Identity resolution is still running for this call. Only ever on a pre-terminal `enrichProgressive()` emission — never on `enrich()`'s return, and never on the stream's terminal emission, both of which wait for a real status. `CONTRADICTED` outranks it, so a provider that reached its entity from a supplied identifier can report a bad one while resolution is still in flight. |
| `NOT_ATTEMPTED_DISABLED` | `EnrichmentConfig.enableIdentityResolution` is `false`. |
| `CONTRADICTED` | An identifier on the request disagreed with what the caller supplied beside it: it named a confidently different artist, or an album a `year` two or more years earlier cannot belong to. Outranks every other status, including `RESOLVED`: a request carrying a usable name recovers by searching it, and reporting that success would hide the bad identifier. |
| `NOT_ATTEMPTED_IDENTIFIER_TRUSTED` | The request carried a MusicBrainz identifier and every requested type was content with it. Trusted, not verified — nothing checked that it names the entity described. |
| `NOT_ATTEMPTED_CACHE_HIT` | Every requested type was served from cache; no live attempt ran this call. Reported only when identity resolution is enabled — `NOT_ATTEMPTED_DISABLED` outranks it. |
| `NOT_ATTEMPTED_NO_PROVIDER` | Resolution was needed, but no identity provider is registered. |

An all-cache-hit call (every requested type served from cache) reports
`NOT_ATTEMPTED_CACHE_HIT` — or `NOT_ATTEMPTED_DISABLED`, if `enableIdentityResolution` is `false`,
since a config that was never going to attempt resolution outranks the cache as the reason it did
not happen. That is the same precedence a live call applies, so a warm cache and a cold one answer
"why wasn't identity attempted" identically. Either way the status comes from this call's own
config, not from what any cached entry was written under. Each
`CacheEnvelope.canonicalStatus` is retained as historical evidence for that entry alone — the
status the live call that wrote it carried — but it is never surfaced as this call's status: a
config change between the write and this read (e.g. identity resolution toggled) would make a
replayed status false for the call actually reporting it.

| `LookupProvenance` | Meaning |
|---|---|
| `CANONICAL_ID` | Looked up directly by a MusicBrainz canonical id. |
| `PROVIDER_NATIVE_ID` | Looked up directly by a provider-native id supplied on the request. |
| `EXTERNAL_CATALOG_ID` | Looked up directly by an external catalogue id (e.g. a UPC barcode) supplied on the request. |
| `EXACT_NAME` | Selected by a name search MusicBrainz confirmed this call: its own identity search returned the title that was asked for, or the request named no entity at all and MusicBrainz supplied the name every other provider then searched. |
| `QUALIFIER_FALLBACK_NAME` | Selected after normalization or qualifier-fallback stripping. |
| `FUZZY_NAME` | Selected by a name search nothing confirmed. This is also where a call whose identity resolved *by identifier* lands: looking an id up compares no name to anything, so it vouches for no provider's own name search. |
| `CACHE` | Served from cache by an implementation that could not recover the original provenance. |

A merged type (e.g. `GENRE`) or a synthesized composite type (e.g. `ARTIST_TIMELINE`) has no single
provider's route of its own: its `provenance` is the weakest of its contributing results', so it
never reads more confident than its least-confident contributor.

A consumer deciding how much to trust results reads both — and reads `matchScore` for neither. That
score says how well the lookup went, not that the entity found is the entity described: a request
carrying an identifier resolves by looking it up, and a live but wrong MBID scores 100. `RESOLVED`
beside a `provenance` of `CANONICAL_ID` therefore means "the identifier you supplied named
something", never "it named your entity". `CONTRADICTED` is the status that reports a supplied
identifier proved wrong, and it outranks `RESOLVED` for exactly that reason — but it reports only
what it can prove, so its absence is not agreement. Any `AMBIGUOUS`/`UNRESOLVED`/`FAILED` status
means every result this call produced is a fuzzy or ambiguous guess, whatever `provenance` an
individual result carries.

Migrating from the removed `IdentityMatch`:

| Old | New |
|---|---|
| `IdentityMatch.RESOLVED` (call-level) | `CanonicalStatus.RESOLVED` |
| `IdentityMatch.SUGGESTIONS` | `CanonicalStatus.AMBIGUOUS` |
| `IdentityMatch.BEST_EFFORT` | `CanonicalStatus.UNRESOLVED` |
| `IdentityMatch.UNVERIFIED` (never released) | `CanonicalStatus.FAILED` |
| `identity == null` (disabled) | `CanonicalStatus.NOT_ATTEMPTED_DISABLED` |
| `identity == null` (identifier supplied, unchecked) | `CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED` |
| supplied identifier disagrees with the name or year beside it | `CanonicalStatus.CONTRADICTED` |
| `identity == null` (all cached, resolution enabled) | `CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT` |
| `identity == null` (no provider) | `CanonicalStatus.NOT_ATTEMPTED_NO_PROVIDER` |
| `Success.identityMatch` (per-result) | `Success.provenance: LookupProvenance` |

### Step 8: Cache Store

No fresh result — success or `NotFound` — is cached for a call whose canonical status is
`AMBIGUOUS`, `UNRESOLVED`, or `FAILED`: the entry would read back as a cache hit indistinguishable
from a confident one, losing the ambiguity or outage.

A `NotFound` is negative-cached only when it is a provider's own answer, because only then is it
evidence the entity is absent upstream. It is never negative-cached when:

- its own chain skipped a provider for a missing identifier, resolved identity or not — a provider
  that was never asked cannot speak for "nothing found";
- catalog filtering (`catalogFilterMode`) emptied a `Success` down to nothing — that emptiness
  describes your local catalog, not the upstream;
- the value it was derived from is a `STALE_IF_ERROR` substitute — a past call's snapshot standing
  in for a failed live call is not this call's own answer;
- it is a composite synthesized over a dependency in either of the last two states. One such
  dependency among fresh ones is enough: the composite's answer still rests on it.

Without these exclusions a transient upstream error or a local catalog outage would persist as
"known absent" for `negativeTtlMs`.

An `Error` or `RateLimited` result is cached neither way, positive or negative: it is not a
provider's answer to remember, only a call that did not get one. The next request for that type
pays the full lookup again — a transient failure can never harden into a sticky negative.

Successful results reached under
`RESOLVED` (or any `NOT_ATTEMPTED_*` status) are cached with per-type TTLs, alongside the call's own
`canonicalStatus`, as a `CacheEnvelope`. `EnrichmentCache.get`/`getNegative` return that whole
envelope, not just the stored result, so a cache hit's `Success.provenance` replays the original
live lookup's value rather than a generic `CACHE`. The stored status remains historical evidence
for that entry; the current all-cache-hit call status comes from this call's config instead
(`NOT_ATTEMPTED_CACHE_HIT`, or `NOT_ATTEMPTED_DISABLED` when resolution is off).
- Artwork: 30–90 days (photos 30d, album art 90d)
- Genres/labels/metadata: 90–365 days
- Popularity/stats: 7 days
- Recommendations: 7–30 days
- Credits/members: 30 days
- Editions/tracks: 365 days (rarely change)

**Cache aliasing:** When identity resolution discovered a new MBID (not pre-provided in the request), the result is also cached under the name-only key. This means future name-only lookups find the MBID-resolved data without re-running identity resolution.

---

## The 11 Providers

| Provider | Auth | What it provides | Role |
|----------|------|-----------------|------|
| **MusicBrainz** | None (1 req/sec) | Identity, genres, labels, dates, members, discography, tracks, links, credits, editions | Identity backbone + primary for most metadata |
| **Cover Art Archive** | None | Album front/back/booklet art, CD art (multiple sizes) | Primary artwork |
| **Wikidata** | None | Artist photo, country, official website | Structured data supplement |
| **Wikipedia** | None | Artist bio, supplemental photos | Text content |
| **LRCLIB** | None | Synced + plain lyrics | Only lyrics source |
| **Deezer** | None | Album art, artist photos, discography, tracklists, album metadata, similar artists/tracks, artist radio, top tracks, similar albums, track previews | Fallback metadata + primary radio/similar albums |
| **iTunes** | None (rate sensitive) | Album art, album metadata, tracklists, discography | Tertiary fallback |
| **ListenBrainz** | None | Artist/track popularity, discography, top tracks, radio discovery | Listening-based stats + community radio (token for radio) |
| **Last.fm** | API key | Similar artists/tracks, genres, bios, popularity, album metadata, top tracks | Social/scrobble data |
| **Fanart.tv** | API key | Artist photos/backgrounds/logos/banners, CD art, album art | High-quality fan artwork |
| **Discogs** | Token | Artist photos, album art, labels, release types, band members, album metadata, credits, editions | Physical release metadata |

8 of 11 providers work without any API keys. The 3 key-requiring providers (Last.fm, Fanart.tv, Discogs) gracefully degrade — their types are served by other providers at lower priority.

---

## The 36 Enrichment Types

### Artwork (8 types — all mergeable)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ALBUM_ART | CAA(100), Deezer(50), iTunes(40), Fanart.tv(30), Discogs(20) | Multi-size + alternatives from all sources |
| ARTIST_PHOTO | Wikidata(100), Fanart.tv(80), Deezer(60), Discogs(40), Wikipedia(30) | 5 sources, merged via ArtworkMerger |
| ARTIST_BACKGROUND | Fanart.tv(100) | Requires key + MBID |
| ARTIST_LOGO | Fanart.tv(100) | Requires key + MBID |
| CD_ART | Fanart.tv(100), CAA(50) | 2 sources |
| ARTIST_BANNER | Fanart.tv(100) | Requires key + MBID |
| ALBUM_ART_BACK | CAA(100) | Via JSON metadata endpoint |
| ALBUM_BOOKLET | CAA(100) | Via JSON metadata endpoint |

`ArtworkMerger` is registered for **ALBUM_ART and ARTIST_PHOTO only** — the two types with more than
two sources. For those, the highest-confidence image becomes primary and the rest become
`alternatives`, so consumers get every available image from every provider in one result. The other
six artwork types resolve first-wins down the priority chain, including CD_ART: Fanart.tv answers it
when a key is present, and CAA is a fallback, not a second entry in the same result.

### Metadata (6 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| GENRE | MusicBrainz(100), Last.fm(100) | **Mergeable** — GenreTag with confidence, sources and `curated` (MusicBrainz's controlled vocabulary vs free-text tags; `null` where nobody could tell — an entry cached before the marking, or tags read off a search hit — which reads as a cache miss and refetches). Only these two serve the type; `genres()`/`genreTags()` additionally fall back to genre strings carried inside ALBUM_METADATA, which Discogs and iTunes also populate |
| LABEL | MusicBrainz(100), Discogs(50) | |
| RELEASE_DATE | MusicBrainz(100) | |
| RELEASE_TYPE | MusicBrainz(100), Discogs(50) | |
| COUNTRY | MusicBrainz(100), Wikidata(50) | |
| ALBUM_METADATA | Deezer(50), Discogs(40), Last.fm(40), iTunes(30) | Community ratings, barcode, etc. |

### Text (4 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_BIO | Wikipedia(100), Last.fm(50) | |
| LYRICS_SYNCED | LRCLIB(100) | Timestamped lines |
| LYRICS_PLAIN | LRCLIB(100) | Plain text |
| ALBUM_DESCRIPTION | Wikipedia(100), Last.fm(50) | Wikipedia extract, Last.fm `wiki` block |

### Relationships (5 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| SIMILAR_ARTISTS | Last.fm(100), ListenBrainz(50), Deezer(30) | **Mergeable** — deduplicates, sums matchScores, rescales to a 1.0 top |
| SIMILAR_TRACKS | Last.fm(100), Deezer(50) | **Mergeable** — deduplicates, sums matchScores |
| BAND_MEMBERS | MusicBrainz(100), Discogs(50) | From artist-rels |
| ARTIST_LINKS | MusicBrainz(100), Wikidata(50) | All URL relation types; Wikidata contributes P856 only |
| CREDITS | MusicBrainz(100), Discogs(50) | Recording rels + extraartists, roleCategory grouping |

### Additional Data (4 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_DISCOGRAPHY | MusicBrainz(100), Deezer(50), ListenBrainz(50), iTunes(30) | 4 providers |
| ALBUM_TRACKS | MusicBrainz(100), Deezer(50), iTunes(30) | 3 providers |
| RELEASE_EDITIONS | MusicBrainz(100), Discogs(50) | Release-group releases + master versions |
| TRACK_METADATA | MusicBrainz(100), Deezer(70), LRCLIB(40) | Duration, album title, disambiguation — all off responses already fetched |

### Statistics (2 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_POPULARITY | Last.fm(100), ListenBrainz(100), MusicBrainz(20) | **Mergeable** — each source's claim kept in its own unit as a `PopularitySignal`, never summed; MusicBrainz contributes its community rating |
| TRACK_POPULARITY | Last.fm(100), ListenBrainz(50), MusicBrainz(20) | **Mergeable** — same shape |

### Recommendations (5 types)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| ARTIST_RADIO | Deezer(100) | Tracks for a "radio station" seeded by artist |
| ARTIST_RADIO_DISCOVERY | ListenBrainz(100) | Community-driven radio via LB Radio; requires `ApiKey.LISTENBRAINZ_USER_TOKEN`. Its only route has been withdrawn upstream before — see [providers.md](providers.md) § Routes ListenBrainz has withdrawn before |
| ARTIST_TOP_TRACKS | Last.fm(100), ListenBrainz(100), Deezer(50) | **Mergeable** — deduplicates, sums listen counts |
| SIMILAR_ALBUMS | Deezer(100) | **Artist-derived** — Deezer has no album-similarity endpoint, so this is albums by artists related to the seed *artist*, era-weighted (re-ranked, nothing dropped). Two albums by one artist give near-identical lists |
| GENRE_DISCOVERY | GenreAffinityMatcher | **Composite** — taxonomy lookup from resolved GENRE tags |

### Preview (1 type)
| Type | Providers (by priority) | Notes |
|------|------------------------|-------|
| TRACK_PREVIEW | Deezer(100) | 30-second MP3 preview URL; on-demand (not in default types) |

### Composite (1 type)
| Type | Dependencies | Notes |
|------|-------------|-------|
| ARTIST_TIMELINE | ARTIST_DISCOGRAPHY + BAND_MEMBERS | Chronological events: formed, albums, member changes |

---

## Resilience

### Rate Limiting
Each host has its own `RateLimiter`, which delays requests to stay within bounds. The intervals and the basis for each are in [providers.md](providers.md) §Rate limiting.

### Circuit Breaker
Per-provider. Tracks consecutive failures:
- **CLOSED** — normal, requests pass through
- **OPEN** — 5+ consecutive failures, rejects instantly for 60 seconds
- **HALF_OPEN** — after cooldown, one test request allowed

Prevents hammering a down provider and slowing the entire pipeline.

### Engine lifecycle

`close()` releases the scope behind the complete-and-cache detachment described under §Progressive
delivery. It is a hard shutdown, not a drain: every detached run still in flight is abandoned at its
next suspension point and writes nothing back, and a collector still attached receives one final
snapshot rather than hanging on a fan-out that will never reach its terminal.

Every requested type still unsettled is stamped an `EnrichmentResult.Error` with
`ErrorKind.ENGINE_CLOSED` — the same per-type completeness a timeout gets, so a consumer never has
to tell "absent from the map" from "never answered". A call arriving *after* `close()` under a
request/types/`forceRefresh` key that was not already in flight never starts a fan-out at all: it is
answered directly, with every requested type stamped the same way. `ENGINE_CLOSED` is never
substituted from expired cache under `STALE_IF_ERROR`, since it describes this engine rather than
the upstream.

`close()` is never called for you. Skipping it costs at most a shared dispatcher plus whatever
detached runs the dedupe has not yet coalesced away.

### Graceful Degradation
- Missing API key → provider returns `isAvailable = false` → skipped in chain
- Provider failure → chain tries next provider at lower priority
- Timeout → returns partial results (whatever settled within `enrichTimeoutMs`, plus slack: cancelling a coroutine cannot interrupt a thread already blocked in a socket read, so a transport leg in flight rides out the deadline's remaining budget before the call returns)
- Individual type failure → other types still resolve
- Identity resolution found no match → results continue under `CanonicalStatus.UNRESOLVED`/`AMBIGUOUS`
- Identity provider errored → results continue under `CanonicalStatus.FAILED`, uncached so a retry can heal
- Catalog provider threw → that type's recommendations returned unfiltered, marked `Success.isCatalogDegraded = true`; nothing else in the call is affected
- Engine `close()`d mid-flight → every unsettled type stamped `Error(ErrorKind.ENGINE_CLOSED)`, see §Engine lifecycle
