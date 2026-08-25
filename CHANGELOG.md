# Changelog

All notable changes to musicmeta will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Each `## [x.y.z]` section below is the GitHub Release note, used verbatim** — read by someone
> deciding whether to upgrade. The rules for writing one live here, not elsewhere:
>
> - One **single-line dot point** per consumer-visible change: headline, the consequence a
>   consumer acts on, then its `(#issue)` where one exists. Two rendered lines is the ceiling,
>   and only when genuinely needed. Full reasoning stays in the issue or PR.
> - Consumer-visible means it changes the published artifacts, the public API, runtime behaviour,
>   documented usage, or the compatibility promise. CI, tooling, formatting and repo hygiene are
>   **not** — that record belongs in the PR and in git. An entry nobody can act on is noise.
> - A **behaviour** change counts only where the affected call was one we advertised. Changing what
>   a documented extension point does is an entry; changing what a provider returns for a type it
>   never declared as a capability is not. The test is not whether behaviour moved — it is whether
>   a consumer could legitimately have depended on it. Off-contract, the only action available is
>   "stop making a call that was never supported", which the line above rules out as noise.
> - For a payload change, ask the user about a cache-clear note.
> - `scripts/github-workflows/build_release_notes.py` caps any single line at 200 characters
>   (a paragraph is not a bullet) and a section at 48000; `./check` runs both against
>   `[Unreleased]` on every commit, and the
>   release fails if it does not fit. Sections from 0.10.0 down were written before the cap and run
>   longer — except 0.10.0 and 0.10.1 themselves, rewritten to match the notes GitHub actually
>   published, which is what a consumer read either way.

## [Unreleased]

### Breaking Changes
- A throwing `CatalogProvider.checkAvailability` no longer escapes `enrich()`: that type degrades to unfiltered results and the run caches, so catch your own timeout inside it
- `EnrichmentEngine` gains `enrichProgressive`, a defaulted method: a custom implementation built against an older `.jar` throws `AbstractMethodError` on first call until recompiled
- `EnrichmentEngine` gains `close()`, a defaulted method: a custom implementation built against an older `.jar` throws `AbstractMethodError` on first call until recompiled
- `EnrichmentEngine` gains `enrichBatchProgressive`, a defaulted method: a custom implementation built against an older `.jar` throws `AbstractMethodError` on first call until recompiled
- `CanonicalStatus` gains `RESOLVING`: an exhaustive `when` needs a branch; only a pre-terminal `enrichProgressive` emission can carry it, never `enrich()`'s return or a stream's terminal emission
- `ErrorKind` gains `ENGINE_CLOSED`: an exhaustive `when` needs a branch; only reachable when the engine was `close()`d before a requested type settled
- `EnrichmentResult.Success` gains `isCatalogDegraded` (appended last, defaulted): recompile; `true` when a recommendation type's `CatalogProvider` threw and the data reached you unranked instead
- `CompositeSynthesizer.synthesize`'s `resolved` deps now arrive finalized: `STALE_IF_ERROR` hands a failed-but-stale dependency as `Success`, not `Error` — can't tell genuine failure from stale
- `CatalogProvider.checkAvailability` is now called concurrently, from multiple coroutines at once, instead of once per call in sequence: a stateful implementation must be thread-safe
- Cancelling `enrich()`'s calling coroutine is now complete-and-cache, not abort-and-forfeit: the fan-out keeps running and still writes back, since it now shares `enrichProgressive`'s resolution path
- `EnrichmentEngine.Builder.build()` now throws `IllegalArgumentException` when registered `CompositeSynthesizer`s form a dependency cycle: the message names every type on the loop
- `Builder.build()` also throws when one type has both a `CompositeSynthesizer` and a `ResultMerger`: the merger could never run, so the registration was silently dead

### Added
- `EnrichmentEngine.enrichProgressive`: `enrich()`'s cumulative-snapshot streaming counterpart — each emission is everything settled so far; derive what's pending as `requestedTypes - raw.keys`
- `enrichProgressive`'s cancellation is complete-and-cache: a cancelled collector detaches, the fan-out keeps running to completion and still writes back, bounded to one run per distinct request key
- `EnrichmentEngine.close()` (defaulted no-op): releases the scope backing `enrichProgressive`'s detachment; call it once done with an engine to abandon a still-running detached fan-out
- A `close()`d engine stamps every unsettled requested type `Error(ErrorKind.ENGINE_CLOSED)`, including for a request key it had never seen before `close()`
- `EnrichmentEngine.enrichBatchProgressive`: `enrichBatch`'s cumulative-snapshot counterpart, composed from `enrichProgressive` per request in the same sequential order
- `engine.DEFAULT_SYNTHESIZER_DEPENDENCIES`: each composite type the engine synthesizes mapped to its source sub-types, for crediting a synthesized result without hand-copying the graph
- demo-web's enrich page now streams over `enrichProgressive` via server-sent events: the page paints each card as its type settles instead of waiting for the slowest provider
- demo-web credits every card with the upstream that supplied it, from response provenance, with each provider's required wording, link-back and licence notice rendered beside its data
- demo-web shows Deezer's private-use notice at the preview player, and states each provider's standing notices in the footer
- demo-web's Cloud Run artifacts are back (`Dockerfile`, `deploy.sh`); `-PdemoCoreVersion` builds the image against the released Maven Central core, unset builds from source as before
- demo-web reads `DEMO_PUBLIC=1` for a ToS-safe public posture (Last.fm off, personal tokens withheld, Discogs images off and 6h freshness ceiling); `DEMO_PUBLIC_ALLOW` lifts named restrictions
- demo-web bounds one client's share of upstream-bearing endpoints (20-burst, 30/min per client), and skips its transient-failure retry pass while the admission gate is saturated

### Changed
- demo-web no longer enriches its suggested queries at startup: the page is usable the moment it loads, `/api/health` reports ready as soon as the server binds, and the cache fills from real searches

### Fixed
- A fault that escapes the fan-out is no longer reported as `ErrorKind.ENGINE_CLOSED`: unsettled types carry `UNKNOWN` and the real cause, so a missing class no longer reads as a closed engine
- A `CompositeSynthesizer` dependency that is itself a composite is now synthesized instead of settling `NotFound("no_provider")`: nested composite graphs resolve to any depth
- A composite's dependency is resolved by its own registration, not the request: one with a `ResultMerger` is merged across every provider even when only the composite was asked for
- `CREDITS` now returns the songwriters, composers and lyricists MusicBrainz models on the work, silently dropped until now; caches 30 days, so clear yours or wait
- demo-web's card image now prefers a Deezer/iTunes CDN URL over Cover Art Archive's when both are available, cutting card paint latency from seconds to well under a second
- demo-web's artist summary card now uses fanart.tv's smaller preview image for its photo and background instead of the full-size original; the gallery still shows full-size
- Cached `LABEL`/`RELEASE_DATE`/`RELEASE_TYPE`/`COUNTRY` entries with unknown-curation genre tags re-fetched every call; now served from cache — tags are read only off `GENRE` and `ALBUM_METADATA`
- A full cache hit now reports `CanonicalStatus.NOT_ATTEMPTED_DISABLED` when `enableIdentityResolution` is false, matching the live path instead of always claiming `NOT_ATTEMPTED_CACHE_HIT`
- No `NotFound` a `CatalogFilterMode` produces by emptying a `Success` is negative-cached any more — covers a live answer, a `STALE_IF_ERROR` substitute, and a fresh cache hit re-filtered later
- A `CompositeSynthesizer`'s `NotFound` no longer negative-caches when synthesized over a `STALE_IF_ERROR`-substituted dependency — it describes a past call's stale snapshot, not this one
- An abandoned run (after `close()`, or a deadline firing before identity resolution starts) now reports `NOT_ATTEMPTED_DISABLED` when `enableIdentityResolution` is false, instead of always `FAILED`
- Track-scoped album art now reaches Deezer/iTunes/Discogs when the album title is known
- demo-web renders any 429 on `/api/*` — its own admission gate's JSON or a platform's plain-text refusal — as one "demo busy" state with a retry button, instead of a raw parse error
- A failing Cover Art Archive release lookup is attempted once per call, not once per artwork type: the four share one attempt budget, so an upstream recovering mid-call now reaches none of them
- The docs no longer promise a MusicBrainz `SearchCandidate` a `thumbnailUrl`: a release search response carries no cover-art flag, so that field was always null on a MusicBrainz candidate

## [0.12.0] - 2026-08-18

### Breaking Changes
- `ProviderInfo.isEnabled` is removed: `getProviders()` only reported `true`, so filtering on it was a no-op — use `isAvailable`; `copy`/constructor descriptors changed, `component6` gone: recompile
- `ARTIST_POPULARITY`/`TRACK_POPULARITY` results now report `provider = "popularity_merger"`, so a per-provider `confidenceOverrides` entry no longer affects those two types
- `Builder.addProvider` now throws on a duplicate provider id, or one the engine reserves (`engine`, `all_providers`, `no_provider`, `no_merger`, `no_composite_handler`, any `*_merger`): rename yours
- Duplicate ids previously shared one circuit breaker, so a healthy provider kept a failing twin in rotation; that configuration is now refused at registration rather than silently degrading
- `EnrichmentData.Popularity` gains `signals` (appended last, defaulted): source-compatible, binary-incompatible until recompile (`copy`/constructor descriptors changed), as with `GenreTag.curated`
- `IdentityResolution` gains `title`/`artist`, the canonical names it resolved (appended last, defaulted): recompile; older jars calling the constructor/`copy` throw `NoSuchMethodError`
- `GenreTag` gains `curated`, marking MusicBrainz's controlled vocabulary and ranking it first: source-compatible, binary-incompatible until recompile (`copy`/constructor descriptors changed)
- An identity provider throwing or returning `Error`/`RateLimited` now resolves to `CanonicalStatus.FAILED`, not `null`/unstamped confident values; `when`s need a branch
- A `CanonicalStatus.FAILED` result is excluded from the cache write-back, so a retry after a transient identity failure re-resolves rather than serving the failed guess for the TTL
- `CompositeSynthesizer.synthesize` now receives the identity provider's `Error` when identity resolution failed, where it previously received `null` (the "not attempted" value)
- Two distinct all-non-Latin artist names (e.g. two different CJK names) no longer match each other; both used to normalize to an empty string and compare equal
- A non-Latin artist request (e.g. 東京事変) against a romanizing provider (Deezer/iTunes/Discogs) now returns no match instead of the provider's unverified top hit; recovery is tracked separately
- `OkHttpEnrichmentClient` now retries a 429, a shed 502/503/504 and a transport failure on core's budgeted ladder; its constructor gains a defaulted `maxAttempts`, so recompile
- `DefaultHttpClient` no longer publishes `MAX_RETRY_AFTER_SEC`; the 120s standalone retry ceiling moved into the shared ladder
- `EnrichmentCache`'s `getIncludingExpired`/`getNegative`/`putNegative` are now abstract: a six-method cache fails to compile, and a pre-compiled one throws `NoSuchMethodError` at runtime
- `RoomEnrichmentCache` now takes a required `negativeDao: NegativeCacheDao` (schema v3, additive migration): recompile and wire `negativeCacheDao()`, or take `EnrichmentCacheDatabase.create()`
- Hard swap, no shim: `IdentityMatch` removed; `IdentityResolution.match` is now `.status: CanonicalStatus` (non-null); `Success`/`NotFound` drop `identityMatch(Score)`, `Success` gains `provenance`
- `EnrichmentResults.identity` is now non-null: it always carries a `CanonicalStatus`, including every reason resolution did not run, so `identity == null` no longer compiles
- `EnrichmentCache.put`/`putNegative` gain a required `canonicalStatus: CanonicalStatus` parameter (no default): a custom cache implementation must recompile and pass the call's status
- Android cache schema bumps to v4 (`MIGRATION_3_4`): `identity_match`/`_score` named a different fact and cannot be reinterpreted, so the migration clears both tables and the next call refetches
- `EnrichmentCacheEntity`/`NegativeCacheEntity` gain `canonicalStatus`/`isStale` fields: binary-incompatible until recompile for a caller constructing or `copy()`-ing them directly
- Old→new `IdentityMatch`/`identity == null` mapping — see `docs/how-it-works.md` "Step 7: Identity Model" for the full table
- `EnrichmentCache.get`/`getIncludingExpired`/`getNegative` now return `CacheEnvelope<...>?` instead of a bare result: recompile, and read `.result` where you read the old return value directly
- That return-type change is a suspend-fun descriptor erasure the `.api` diff cannot show; treat it as breaking regardless — `docs/pitfalls.md` "The published surface"
- `LookupProvenance.EXTERNAL_CATALOG_ID` distinguishes direct catalogue lookups such as iTunes UPC from provider-native ids; exhaustive `when`s need a branch
- `RoomEnrichmentCache`'s constructor now requires a third `SelectionDao` param (schema v5, `MIGRATION_4_5`): recompile and wire `selectionDao()`, or take `EnrichmentCacheDatabase.create()`
- `EnrichmentCacheDao` drops `isManual` (`Boolean?`)/`markManual`/`insertPreservingManual`; `SelectionDao.isSelected` replaces it, always non-null `Boolean` — invisible in the `.api` diff (erasure)
- `EnrichmentCacheEntity` drops `isManual`: recompile — direct construction/`copy()` breaks, and `component11`+ renumber, so destructuring silently rebinds

### Added
- `EnrichmentRequest.forTrackByMbid`/`forAlbumByMbid`/`forArtistByMbid`: request an entity by MBID alone; identity resolution fills the names the other providers search by
- `EnrichmentEngine.discoverMbidEntityType(mbid)`: what a bare MBID names (`MusicBrainzEntityType.RECORDING`/`RELEASE`/`ARTIST`, or null), at 1–3 requests probing in that order
- An MBID-only result is cached under MusicBrainz's canonical name as well as its id, so a later name-only lookup for that entity hits
- `TRACK_METADATA`/`EnrichmentData.TrackMetadata` (duration, album title, disambiguation), already fetched but dropped by MusicBrainz, Deezer, LRCLIB; in `DEFAULT_TRACK_TYPES`
- `PopularTrack` now carries `listenerCount`, `durationMs` and `album`, matching what `TopTrack` already exposes from the same ListenBrainz data
- `ALBUM_DESCRIPTION` (`EnrichmentData.Biography`), from Wikipedia and Last.fm's `wiki` block; in `DEFAULT_ALBUM_TYPES`, top source is keyless and long-cached
- `PopularitySignal`/`PopularitySignalKind`: each source's popularity claim in its own unit (scrobbles, listens, rank, a 1–5 rating), never summed — `Popularity.signals` is authoritative
- MusicBrainz now answers `ARTIST_POPULARITY`/`TRACK_POPULARITY` with its community rating as a signal, riding the lookups it already makes, so neither type costs an extra request
- `ProviderPolicies`: each provider's terms as data — commercial use, licence, notice to render
- `Builder.contact(urlOrEmail)` composes the User-Agent MusicBrainz and Wikimedia require (`MusicEnrichmentEngine/1.0 ( contact )`); it rejects a line break or paren, and a config `userAgent` wins
- `EnrichmentConfig.userAgentWithContact(contact)` exposes that composition for callers wiring `DefaultHttpClient` themselves
- `IdentifierNamespace` enum plus `EnrichmentIdentifiers.get(ns)`/`.with(ns, value)`: typed accessors over the existing untyped `extra` map, additive, no key or wire-format change
- MusicBrainz, Discogs, Spotify and Apple Music artist ids are now carried on Wikidata results' identifiers, parsed from claims already fetched; nothing consumes them for resolution yet
- Wikidata is a second `ARTIST_LINKS` source (priority 50), contributing the official website (P856) where MusicBrainz has no relations
- Deezer's `ALBUM_METADATA` now fills `barcode`/`label`/`releaseDate` from `GET /album/{id}`, one extra request shared with `ALBUM_TRACKS` per call; no genre (Deezer's is one coarse tag)
- `BudgetedTransientRetry`, `HttpResult.asAttempt`, and `withRetryBudgetForTest` behind a `@MusicmetaTestApi` opt-in: core's budgeted retry ladder is public for a client of your own
- `ProviderCatalog.entries`: the providers `withDefaultProviders()` would register, with id, display name, and `KeyRequirement` naming the gating field
- A confident `NotFound` is now cached for `EnrichmentConfig.negativeTtlMs` (default 1h), skipping the round-trip on a repeat

### Changed
- An artist identity pick whose name matched neither the entity's name nor any alias now reports `identityMatchScore` scaled by 0.7, not full strength — the pick itself is unchanged
- `ARTIST_POPULARITY`/`TRACK_POPULARITY` are now merged across providers instead of returning the first answer: a field the leading source lacks is filled from the next that has it
- Every popularity source is now queried rather than stopping at the first success
- Both types cache for 7 days, so a popularity entry written before this carries no `signals` until it is refetched: call `invalidate()` or enrich with `forceRefresh` to see them sooner
- A 429 now reaches you as `EnrichmentResult.RateLimited` with the upstream's `retryAfterMs`, not `Error`/`NETWORK`; both were documented as unreachable, so a `when` over results may need the branch
- A throttled provider now counts against its circuit breaker, so sustained 429s take it out of rotation for the cooldown instead of being asked on every call
- A request that names no entity (an MBID-only one whose MBID resolves to nothing) is no longer fanned out to name-search providers: those types are `NotFound`, not a live search for the empty string
- `invalidate()` on an MBID-only request now costs one identity lookup, to learn the canonical name its result was aliased under and drop that entry too
- `hip-hop` now folds into `hip hop`, MusicBrainz's own curated spelling, rather than the reverse: affected artists' genre strings change spelling
- `Metadata.genres` (the plain string list) is now the genre-tag names in tag order, so curated genres lead it as they lead `genreTags`
- Provider terms are now documented (docs/providers.md): keyless is not permission; Deezer and Last.fm restrict commercial use. README opening reworded to match.
- Deezer's `ALBUM_TRACKS` now uses the same artist-matched search as `ALBUM_METADATA` (was the unfiltered top hit), so a same-titled wrong-artist album no longer supplies the tracklist
- That same match can now reject all 5 candidates (alias, compilation, credited-as variant), so `ALBUM_TRACKS` can return `NotFound` where it previously returned the unfiltered top hit's tracks
- `ALBUM_TRACKS`'s confidence now reflects that same artist match (was always the no-match floor), matching what `ALBUM_METADATA` and `ALBUM_ART` already reported for the identical selection
- Wikipedia bios now come from the Action API extract, restoring parentheticals the old endpoint stripped (instrument credits, IPA); bio text changes, caches 30 days, so clear yours or wait
- Wikipedia `ARTIST_PHOTO` now carries the largest rendered thumbnail and every scale in `sizes`, not the original file: `height` is null, since the media route states none
- iTunes album resolution now does a `lookup?upc=` identity match when a barcode is known, replacing the fuzzy search — a barcode Apple doesn't carry is `NotFound`, not a search fallback
- `build()` warns from the User-Agent the wire will carry: the contactless default meeting MusicBrainz/Wikipedia/Wikidata, `contact()` after `withDefaultProviders()`, or `contact()` with your client
- Room writes are now a single unconditional insert (no read-then-write): dropping `insertPreservingManual` removes the only lock window an ordinary cache write held

### Fixed
- MusicBrainz track/album search now ranks by credited artist: a lookup that won on a wrong-artist hit and reported `RESOLVED`/`matchScore` 100 now returns the correctly-credited entity
- MusicBrainz now reports `QUALIFIER_FALLBACK_NAME` when a stripped candidate, not the literal title, resolved a track or album; an exact-title match still reports `EXACT_NAME`
- An all-cache-hit call now always reports `NOT_ATTEMPTED_CACHE_HIT`; it no longer replays a cached `NOT_ATTEMPTED_*` reason that a later config change could make false
- `RoomEnrichmentCache` and `InMemoryEnrichmentCache` now read back the `canonicalStatus` they persist on write, closing the write-only gap the previous audit found
- Deezer track search now accepts a candidate's title (exact or equivalent-qualifier match) before ranking it, rather than ranking any right-artist pool; a wholly unrelated title is no longer returned
- LRCLIB's search fallback now rejects a candidate whose artist or title it cannot identify, rather than taking the first search hit unconditionally
- Remove any retrying OkHttp interceptor: it cannot see the enrich deadline, so its retries are unbudgeted and now stack on the ladder
- A caller-supplied `httpClient()` only silences the contactless-User-Agent warning when it built the wire's only client; one set after `withDefaultProviders()` still warns
- An artist named by one of its MusicBrainz aliases now resolves: the search asked `artist:"…"` only, which does not reach the alias index, so a localised or former name found nothing
- `identityMatchScore` now distinguishes an artist matched on its own name from one matched on an alias, scaling the score by which of the two it was
- A cached payload whose genre tags never learned whether they were curated reads as a miss and refetches, rather than serving unmarked tags for the type's 90-day TTL
- Discogs limiter now has headroom, so jitter no longer tips it into a 429 (was `Error`/`NETWORK`)
- Wikipedia `ARTIST_PHOTO` now returns the article's lead photograph; its media schema had changed, so the parser saw no image or a diagram. Caches 30 days, so clear yours or wait
- A type whose every provider is circuit-broken is now `Error` (`ErrorKind.NETWORK`), not `NotFound`: an outage had read as "no such data" and carried no retry signal
- A merged type (`GENRE`, `ALBUM_ART`, `SIMILAR_ARTISTS`, …) whose providers all errored is now `Error` too; the merger sees only successes, dropping every failure before the consumer
- A track given no album is resolved from recordings MusicBrainz has already filtered to unmarked ones, so a heavily-covered title reaches the studio recording instead of a live or demo take
- A recording-MBID track request no longer skips identity resolution: `ALBUM_ART` and identity arrive as they do without one; the MBID used to return less
- A recording MBID on a track request is now looked up, not discarded, so a suggestions-list pick resolves to that recording, not whatever the name search ranked first
- A track whose title names its variant (`Song (Live at …)`) resolves to that recording; the canonical filter deleted it, leaving a full pool answering with the studio take
- A track naming an album MusicBrainz has no release titled resolves from the deep filtered pool as well as the shallow one, instead of only the 25-result search the filter exists to replace
- A track resolved by name is searched for once per `enrich()` rather than once per type, so the identity block and every type's payload name the same recording where they could disagree
- `NotFound` suggestions for a track come from the unfiltered pool, so a live or alternate take the consumer may have meant is offered instead of hidden by the filter that resolves the answer
- A recording MBID MusicBrainz does not hold no longer costs a track request every provider: it names no recording, so the request resolves by name as one carrying no MBID does
- An album or artist MBID MusicBrainz does not hold likewise resolves the request by name, where it previously returned nothing from MusicBrainz for every type
- A lookup body MusicBrainz answers with but that does not parse still resolves to nothing: it holds that entity, so no search hit may stand in for the one the caller named
- An MBID MusicBrainz does not hold is looked up once per call rather than once per type, so a stale third-party id costs one request, not one per type
- An artist name MusicBrainz holds nothing under is `NotFound` for `BAND_MEMBERS`/`ARTIST_DISCOGRAPHY`/`ARTIST_LINKS`; ranking the empty pool threw and reached consumers as a provider error
- A track request carrying a recording MBID now resolves every MusicBrainz type from one lookup, where it previously spent a search per type plus a separate credits lookup
- A read timeout or dropped connection now retries, once the deadline left covers another whole attempt and not just the wait; it was the most common MusicBrainz failure and was never retried
- A 502, 503 or 504 now retries on the same ladder as a 429 (bounded, `Retry-After`-honouring, deadline-aware); MusicBrainz sheds with 503, so a lookup one retry would answer no longer fails
- MusicBrainz album search took the first score-100 tie, so an album could resolve to a single, bootleg, promo or box set; identity, edition size and earliest date now pick the release
- MusicBrainz `searchCandidates` returned an empty list for tracks; tracks now get candidates and "did you mean?" suggestions (`CanonicalStatus.AMBIGUOUS`), matching album/artist behaviour
- Wikidata reported an artist from Latvia (Q211) as Czech Republic; Q211 is now Latvia, Czech Republic is keyed on Q213. `COUNTRY` caches 365 days, so clear yours or affected artists stay wrong
- Wikidata's artist lookup used a call Wikidata always rejected; birth/death date, country and occupation are now returned instead of nothing, every time
- ListenBrainz's SIMILAR_ARTISTS called a route that never existed and always returned nothing; capability dropped, Last.fm and Deezer already serve it (#18)
- Deezer's SIMILAR_TRACKS called `/track/{id}/radio`, which doesn't exist, and always returned nothing; now derived from the seed track's artist's related artists and their top tracks
- Deezer track search ignored the album hint and always took Deezer's first hit; previews and lookups could resolve to a remix or live take instead of the requested edition
- MusicBrainz track search took the first score-100 tie, which could be a demo or live take; identity, popularity, and other downstream track data now resolve to the studio recording
- MusicBrainz track ranking ignored a typed album and never penalized a music-video take; album matches are now preferred (and pass the score floor) and a video no longer beats a studio take
- MusicBrainz album/track search failed outright on a qualifier-suffixed title even though the release/recording exists; now falls back to a stripped title, tie-broken toward the matching edition
- An album MusicBrainz titles with symbols a caller cannot type (`F♯ A♯ ∞`) was NotFound from every ASCII spelling; the artist's release groups are now matched locally when the search finds nothing
- MusicBrainz's lookup memo outlived the call, so `forceRefresh`, `invalidate()` and `cache.clear()` were answered from the first call's payload; it now lives for one call, not the engine's
- MusicBrainz re-ran the album search ladder, and its suggestion search, once per album type; both now resolve once per call, so an album it cannot find costs 6 upstream requests, not 41
- Outside an engine, `MusicBrainzProvider` re-fetches the release and release-group per type; route multi-type requests through the engine to share one memo
- ListenBrainz's recording/artist popularity treated a JSON-null listen count as zero and kept it; a track or artist with no LB data now returns NotFound instead of a fake 0/0
- Cover Art Archive sent a track's recording MBID to its release endpoint, which always 404s; ALBUM_ART on tracks now resolves via the release-group id instead of failing every time
- MusicBrainz ALBUM_TRACKS flattened a bonus video disc into the tracklist; a release with a DVD/Blu-ray extra no longer duplicates every position
- Fanart.tv ignored each image's community likes and always took the first one; artist and album art now resolve to the most-liked image
- A transient MusicBrainz side-lookup could leave a type's identifier (e.g. `ALBUM_DESCRIPTION`'s Wikipedia title) unresolved and masquerade as NotFound; now Error, eligible for `STALE_IF_ERROR`
- A transient on MusicBrainz's full-artist lookup (URL relations for a search match) failed artist enrichment entirely; it now degrades to the search result without relations
- A transient on Cover Art Archive's thumbnail/front-image or Discogs's community-rating fetch discarded an already-resolved artwork/metadata answer; both now degrade the optional field instead
- MusicBrainz artist name search (GENRE/BAND_MEMBERS/ARTIST_DISCOGRAPHY/ARTIST_LINKS) is now memoized per `enrich()` call; an unknown artist cost 7 upstream searches, now 2
- A track's qualifier-fallback search (`(Remastered)`, `(Deluxe)`, …) now shares the per-call memo the raw search already had, instead of re-running once per type on a miss
- A canonical identity `NotFound` carrying suggestions no longer vetoes every other provider: an eligible one still runs and can return `BEST_EFFORT` data alongside the top-level suggestions
- MusicBrainz album/track lookup now recognizes a dash-form reissue suffix (`Starman - 2012 Remaster`) after an exact-title miss, matching the existing conservative bracketed-qualifier fallback
- Deezer album lookup now rejects a right-artist result whose title is unrelated and ranks accepted editions instead of trusting search order
- iTunes album search now accepts and ranks a candidate's title, not just its artist, and shares one selection across ALBUM_ART, ALBUM_METADATA and ALBUM_TRACKS
- Discogs album search now validates the returned album title as well as artist, parsing its combined `"Artist - Title"` field safely, before using release data
- Discogs combined-title parsing now finds the real artist/title boundary when the requested artist itself contains ` - `, instead of stopping at the first artist-plausible prefix
- `Success.provenance` is now observed from the winning provider's own route, not guessed from identifiers merely present on the request: an MBID no longer mislabels a name search `CANONICAL_ID`
- An all-cache-hit `Success` now reports `provenance = CACHE` instead of `null` when the cache that served it did not preserve the original lookup's route
- Deezer's artist-id, iTunes's collection-id/artist-id, and Discogs's `CREDITS`/`RELEASE_EDITIONS` branches now self-report `provenance = PROVIDER_NATIVE_ID`; iTunes UPC uses `EXTERNAL_CATALOG_ID`
- A merged or synthesized `Success` (e.g. `GENRE`, `ARTIST_TIMELINE`) now reports its weakest contributor's `provenance` instead of one fabricated from canonical status alone
- Cache keys now encode the complete request tuple: scope/type, names, selectors, all explicit identifiers, and sorted extras; only identical tuples replay
- Exact-bearing calls never read name aliases; canonical aliases require names supplied by identity resolution. Custom caches must treat keys as opaque
- The cache-key format change intentionally causes a one-time miss for existing entries; no cross-tuple entity equivalence is inferred
- Cache backends now agree on manual selections: invalidation removes them, an ordinary write preserves one, and marking a key before anything is cached still survives (Room schema v5)
- `TitleMatcher` no longer strips an identity-bearing internal quote, or accepts mismatched terminal brackets (`Song (Live]` no longer equals `Song (Live)`)
- LRCLIB's album/duration ranking no longer scores a candidate missing that evidence as though it agreed with the request; only an explicit match may outrank one silent on the same field
- demo-web's "Clear cached result & reload" now invalidates the identifier-bearing preview tuple, not only the name tuple, so the entry named by the following reload is actually cleared
- CoverArtArchive, Fanart.tv, Wikipedia, ListenBrainz and Wikidata now memoize their per-release/per-artist upstream fetch for one `enrich()` call instead of once per type
- MusicBrainz's plain recording search is now memoized per `enrich()` call, so a track miss no longer requests the same unfiltered recording search twice
- A timed-out enrich now stamps `provenance` on a `Success` identity resolution already wrote, instead of leaving it `null` against the published guarantee every `Success` sets it
- MusicBrainz artist identity now reports `FUZZY_NAME`, not `EXACT_NAME`, for an alias match — a label fix only; the matched artist and `matchScore` are unchanged
- `GenreMerger` now reads legacy `genres` too, so a genres-only contributor's names aren't dropped; a lone such contributor now reports `provider = "genre_merger"`
- A mixed GENRE set can now report weaker `provenance` too: the legacy-only contributor's route now counts, since its names are genuinely merged in — evidence catching up, not weaker data

## [0.11.0] - 2026-07-28

This release makes `engine/` internal, hardens providers and the engine, and corrects documentation errors.

### Breaking Changes
- A `CatalogProvider`'s own `withTimeout` expiry now propagates out of `enrich()` instead of reported as ours (#55)
- **`HttpClient` is now six abstract `HttpResult` methods** — nullable `fetchJson`, `fetchJsonArray`, `fetchBody`, `fetchRedirectUrl`, `postJson`, `postJsonArray` deleted; implement the `*Result` ones
- `fetchJsonResult(url, headers)` and `fetchRedirectUrlResult` are no longer defaulted — implement both, or `Authorization` headers go unsent and a 429 on the artwork path reads as "no artwork"
- `engine/` internals (`DefaultEnrichmentEngine`, `ProviderRegistry`, `ProviderChain`, `ArtistMatcher`, `ConfidenceCalculator`) are now `internal`; build engines with `EnrichmentEngine.Builder`
- `ResultMerger` and `CompositeSynthesizer` stay public, as the documented extension points

### Fixed
- A `Success` whose payload answers none of the type asked for is now `NotFound` — a tagless MusicBrainz recording no longer returns `GENRE` at confidence 1.0 with every field null
- Discogs sends its token in an `Authorization` header, not the URL query
- Fanart.tv `ALBUM_ART`/`CD_ART` need a release group id; the wrong-album fallback is gone
- A 401/403 on a call carrying a key (Last.fm, Discogs, Fanart.tv, ListenBrainz radio) is now `Error(ErrorKind.AUTH)`, not `NotFound`; five in a row open the breaker, one retries a minute later
- A MusicBrainz 429/5xx/network failure is now `Error(ErrorKind.NETWORK)`, not an empty result; identity stops refusing a resolvable query with a "did you mean" list whose top entry scores 100%
- A 429/5xx/network failure from Deezer, iTunes, Last.fm, Discogs, Fanart.tv or ListenBrainz is now `Error(ErrorKind.NETWORK)`, not an empty result; the breaker sees it and `STALE_IF_ERROR` engages
- A 429/5xx/network failure from Cover Art Archive, LRCLIB, Wikidata or Wikipedia is now `Error(ErrorKind.NETWORK)`, not an empty or partial result; the breaker sees it and `STALE_IF_ERROR` engages
- A Deezer quota body (HTTP 200, `error.code` 4) and an iTunes 403 are now `Error(ErrorKind.NETWORK)`, not an empty result; the breaker sees it and `STALE_IF_ERROR` engages
- A 429/5xx/network failure on Cover Art Archive's `ALBUM_ART` redirect is now `Error(ErrorKind.NETWORK)`, not `NotFound`; `HttpClient` gains a defaulted `fetchRedirectUrlResult` carrying the status
- An `ARTIST_TOP_TRACKS` merger registered via `Builder.addMerger` now overrides the built-in `TopTrackMerger`; merged output changes for anyone who registered one (#49)
- iTunes search candidates now carry `itunesCollectionId`; a follow-up `ALBUM_TRACKS` request resolves by direct lookup instead of re-searching
- A consumer `EnrichmentLogger` that throws no longer fails `enrich()`; the log line is lost (#71)
- MusicBrainz caches artist and release lookups (LRU, 500); album `GENRE` fills empty genres by lookup
- Discogs verifies the artist name on both searches (`NotFound` over a wrong-artist hit); a verified album result reports 0.8, not 0.6
- Cover Art Archive back covers, booklets and CD art read the canonical `"250"` thumbnail key, not the deprecated `"small"` alias
- A cancelled or timed-out `enrich()` no longer opens circuit breakers against healthy providers (#53)
- A consumer cache or merge strategy with its own `withTimeout` no longer surfaces as the engine's deadline (#61)
- A throwing `HttpClient` on Wikipedia's Wikidata sitelink lookup now yields `Error` with a classified `errorKind` instead of escaping as `UNKNOWN`
- Docs: `SIMILAR_ALBUMS` is documented as artist-derived — Deezer has no album-similarity endpoint, so two albums by one artist return near-identical lists (#107)
- Docs: `withDefaultProviders()` must be called last; `ArtworkMerger` covers only `ALBUM_ART`/`ARTIST_PHOTO`; the genre taxonomy is 189 relationships in 12 families, not `~70`
- Docs: four wrong `README.md` claims removed; eleven drifted API references in `docs/providers/` replaced by one `docs/providers.md`
- Docs: the guides and `ErrorKind` KDoc no longer claim four providers collapse a transient into an empty result — all eleven classify it; the custom-provider sample uses `fetchJsonResult`
- Docs: the `GENRE` roster is corrected — two providers, MusicBrainz and Last.fm, not four
- Docs: `ROADMAP.md` drops the shipped-milestone status tables its own pointer sends to `CHANGELOG.md`; the deferred items they listed keep their home under ROADMAP's Remaining Gaps
- Docs: `ROADMAP.md` drops the priority scorecard, version-evolution table and shipped inventories; what `1.0.0` waits on sits under Planned Milestones, the deferred Flow API under Remaining Gaps
- Each host gets its own `RateLimiter` — Cover Art Archive's is now actually used; ten providers shared one, serialising every fan-out (#50)
- A timed-out `enrich()` now caches nothing; no half-filtered result persists (#56)
- A 429 on the typed `HttpResult` calls now retries within the enrich deadline; standalone, an `HttpClient` may block up to 120s rather than return at once
- `EnrichmentIdentifiers.wikipediaTitle` is null unless MusicBrainz has an `en.wikipedia.org` relation; artists like Portishead get an English `ARTIST_BIO` via the Wikidata `enwiki` sitelink (#106)
- Deezer artist search picks the best name match, popularity only as tiebreak: Radiohead resolves to id 399, not the empty same-name ghost at hit 0; a supplied `deezerId` wins, so clear stale caches
- iTunes and Discogs artist search rank a candidate pool by name match instead of taking hit 0 of a 1-result search; a stored `itunesArtistId` still wins, so clear stale caches

## [0.10.1] - 2026-07-22

Patch release — first since 0.10.0. A consumer-facing reliability fix plus release-safety and CI hardening. No public-API change (`api/*.api` baselines unchanged).

### Fixed
- **A throwing `ResultMerger`/`CompositeSynthesizer` no longer escapes `enrich()`** (#28) — a throwing consumer merge strategy now yields `EnrichmentResult.Error` for its own type instead of propagating and discarding the whole call. Closes the last of the three consumer extension points to be guarded.

### Added
- **Publish-tag guard** (#13) — `publish.yml` refuses a tag that doesn't match the declared module versions before publishing anything.
- **`release-readiness` gate** (#35) — the `dev → main` release PR fails if the three module versions disagree with each other or with the pinned `CHANGELOG` heading. Now a required check on `main`.

### Changed
- **Single-source version** (#37) — declared once in root `gradle.properties` and inherited by all three modules; cross-module drift is now unrepresentable. Published coordinates unchanged.
- **API-drift watch keys on a label**, not brittle title text (#14).
- **All workflows off the deprecated Node 20 runtime** (#10).

## [0.10.0] - 2026-07-22

Public API compatibility enforcement — the public surface is now baselined, gated in CI, and narrowed to what was always meant to be public. Paired with a watch in the opposite direction, for third-party provider APIs changing underneath us.

> **Minor, not patch, by rule.** This release removes ~80 types from the published ABI. Under the 0.x carve-out (see `CLAUDE.md`), a `0.x.0` minor may break as long as the break is documented and visible in the reviewed `api/*.api` diff; a `0.x.y` patch may not.

### Added
- **Public ABI baselines** — `binary-compatibility-validator` dumps each module's API to `api/*.api`; `apiCheck` fails the build (and publish) on any divergence from the committed baseline. Regenerate with `apiDump`.
- **Scheduled API-drift watch** — weekly `apiDump` vs the baselines; files/auto-closes a single `[api-drift-bot]` tracking issue on drift or a demo-canary break.
- **Scheduled provider-API drift watch** (#16) — daily gated E2E run against the live third-party APIs; files/auto-closes a tracker when their JSON or endpoints change underneath us. Never gating.
- **`demo/` composite-build canary** — `build.yml` compiles the demo consumer, catching breaks `./gradlew build` never sees (it broke silently in 0.9.2).

### Fixed
- **A throwing `EnrichmentCache` no longer escapes `enrich()`** (#22) — every cache op on the `enrich()` path is now guarded: a failed read degrades to a miss, a failed write to not-cached. `CancellationException` still propagates.
- **`demo/` compiles again** — the mid-list `identifiers` parameter added in 0.9.2 shifted positional args; the demo's call sites now pass them by name.

### Breaking Changes
> Permitted under the 0.x carve-out — an ABI removal, visible in the reviewed `.api` diff (~1350 lines smaller). These types were public by omission, never by design.
- **Provider internals are now `internal`** — the `*Api` (11), `*Mapper` (11) and `*Models` (49) behind each provider, plus `MusicBrainzParser` (e.g. `DeezerApi`, `DiscogsMapper`, `MusicBrainzArtist`). Construct and register the public `*Provider` classes instead — unchanged.
- **`http/` infrastructure is now `internal`** — `CircuitBreaker` (and its `State`) is hidden. `HttpClient`, `HttpResult`, `HttpResponse`, `DefaultHttpClient`, `RateLimiter` stay public.
- **`engine/` mergers/synthesizers are now `internal`** — `ArtworkMerger`, `GenreMerger`, `SimilarArtistMerger`, `SimilarTrackMerger`, `TopTrackMerger`, `TimelineSynthesizer`, `GenreAffinityMatcher`. The `ResultMerger`/`CompositeSynthesizer` **interfaces** stay public (the `addMerger`/`addSynthesizer` extension points).
- **`SimilarAlbumsProvider(DeezerApi)` constructor replaced** — now `SimilarAlbumsProvider(httpClient: HttpClient, rateLimiter: RateLimiter = RateLimiter(100))`, matching the other providers.
- **`ProviderChain` constructor is now `internal`** — the class stays public and is reachable via `ProviderRegistry.chainFor(type)`; only direct construction is withdrawn.

## [0.9.2] - 2026-03-27

Track preview fast path — 4-5x faster preview resolution when deezerId is known.

### Added
- **`identifiers` parameter on `forTrack()`, `forArtist()`, `forAlbum()`** — pass pre-resolved identifiers (e.g., from top tracks) so providers can skip search/identity resolution. Appended at the end of each factory's parameter list with a default of `null`; source-compatible for existing callers.
- **`identifiers` parameter on `trackProfile()`, `artistProfile()`, `albumProfile()`** — same intent, flows through to the request factory. See Breaking Changes: on the profile extensions this parameter was inserted mid-list, not appended.
- **`resolveTrackPreviews()` batch extension** — resolves preview URLs for multiple tracks concurrently. Accepts `List<TrackPreviewRequest>`, returns `List<TrackPreviewResult>`. Fans out via coroutines internally.
- **`DeezerApi.getTrack(trackId)`** — fetches a single track by Deezer ID, including preview URL. Used by the fast path.

### Breaking Changes
> Documented after the fact (backfilled 2026-07-21). This shipped in a **patch** release, which the current versioning stance (see `CLAUDE.md`) says should never break the API — recorded here rather than corrected, because the committed `api/*.api` baseline already encodes this shape as the source of truth, and moving the parameter again would break the callers who have since adopted v0.9.2 positionally. See `STORIES.md` (2026-07-21).
- **`identifiers` inserted mid-list on the profile extensions `artistProfile(name, mbid, …)`, `albumProfile(title, artist, mbid, …)`, `trackProfile(title, artist, album, mbid, …)`** — `identifiers: EnrichmentIdentifiers? = null` was placed between `mbid` and `types` rather than appended at the end. Every positional argument after `mbid` shifted one slot. Named callers (e.g. `artistProfile("Radiohead", types = …)`) are unaffected; positional callers written against v0.9.1 (e.g. `artistProfile("Radiohead", null, myTypes)`) now bind their `Set` argument to the `EnrichmentIdentifiers?` slot and fail to compile. The in-repo `demo/` broke this way and was fixed by switching to named arguments. The `forTrack()`/`forArtist()`/`forAlbum()` factories in the same release appended `identifiers` correctly and are not affected.

### Changed
- **Deezer `TRACK_PREVIEW` fast path** — when `deezerId` is present in request identifiers, the provider calls `getTrack(id)` directly instead of searching by title/artist. Skips MusicBrainz identity resolution entirely. Cold lookup drops from ~2-3s to ~540ms per track; batch of 10 tracks drops from ~20-30s to ~5.5s.

## [0.9.1] - 2026-03-26

### Fixed
- **Empty top track titles from ListenBrainz** — API parser read `track_name` but the API returns `recording_name`, causing all TopTrack titles to be empty strings
- **Missing album names from ListenBrainz top tracks** — parser looked for a nested `release` object but the API returns `release_name` as a top-level field

### Migration note
If you use `EnrichmentCache`, clear your cache after upgrading from 0.9.0 or earlier. Cached entries from previous versions may contain empty titles or missing fields that are now correctly populated.

## [0.9.0] - 2026-03-26

LB Radio & Track Preview — 34 enrichment types.

### Added
- **`TRACK_PREVIEW` enrichment type** — 30-second MP3 preview URL via Deezer track search. On-demand type (not in `DEFAULT_TRACK_TYPES`). Access via `EnrichmentResults.trackPreview()` or `TrackProfile.preview`. 24-hour TTL (CDN URLs may rotate). No API key required.
- **`ARTIST_RADIO_DISCOVERY` enrichment type** — community-driven radio playlist via ListenBrainz LB Radio. Returns `RadioPlaylist` with recording, artist, and release MBIDs. Access via `EnrichmentResults.radioDiscovery()` or `ArtistProfile.radioDiscovery`. 7-day TTL. Included in `DEFAULT_ARTIST_TYPES`. Requires `ApiKeyConfig.listenBrainzToken` (free ListenBrainz account).
- **`RadioDiscoveryMode` enum** on `EnrichmentConfig` — controls LB Radio discovery depth: `EASY` (familiar-adjacent, default), `MEDIUM`, `HARD` (adventurous, deeper cuts).
- **`ApiKeyConfig.listenBrainzToken`** — optional user token for LB Radio. When absent, `ARTIST_RADIO_DISCOVERY` is silently unavailable; all other ListenBrainz endpoints continue working without any token.
- **`DeezerTrackSearchResult`** now carries `previewUrl: String?`, `durationSec: Int?`, and `albumTitle: String?` — extracted from the already-available Deezer track search response, no extra API calls.
- **HttpClient header support** — `fetchJsonResult(url, headers)` overload added to `HttpClient` interface (default no-op impl for backward compatibility); used by LB Radio to send `Authorization: Token` headers.

### Breaking Changes
> Documented after the fact (backfilled 2026-07-21).
- **`EnrichmentConfig.radioDiscoveryMode` inserted mid-list** — `radioDiscoveryMode: RadioDiscoveryMode = RadioDiscoveryMode.EASY` was placed between `radioLimit` and the previously-last `cacheMode` parameter rather than appended. Positional callers constructing `EnrichmentConfig(...)` with `cacheMode` in the final position rebind that argument to `radioDiscoveryMode` (a type mismatch → compile error). Named-argument callers are unaffected. This is a minor release, so a documented break is within the versioning stance (see `CLAUDE.md`); it is recorded here only because the `### Breaking Changes` heading was never used at the time.

## [0.8.2] - 2026-03-25

### Changed
- **Android `minSdk` lowered from 26 to 21** — no API-level-specific code exists in the android module; 21 (Android 5.0) is the practical floor
- **README** added Kotlin badge

## [0.8.1] - 2026-03-25

### Fixed
- **Search command** falls back to fuzzy matching when exact search returns empty — typos like "radohead" now find "Radiohead"
- **`TrackingCache`** delegates `getIncludingExpired()` — stale cache mode now works in demo

### Added
- **Demo**: `config http default|okhttp` — switch HTTP backend for live testing
- **Demo**: `config stale on|off` — toggle `STALE_IF_ERROR` cache mode
- **Demo**: `batch artist|album|track a; b; c` — bulk enrichment with streaming output
- **Demo**: `[stale]` indicator on results served from expired cache

### Changed
- **Maven group ID** changed from `com.landofoz` to `io.github.famesjranko` for Central Portal publishing
- **README** license set to Apache-2.0

## [0.8.0] - 2026-03-24

Production Readiness — 32 enrichment types.

### Added
- **`musicmeta-okhttp` module** — `OkHttpEnrichmentClient` implementing all 10 `HttpClient` methods via OkHttp 4.12.0 `Call` API. Transparent gzip decompression (no manual `Accept-Encoding` header). No built-in retry — delegates to OkHttp interceptors. Timeouts inherited from caller's `OkHttpClient` instance.
- **`CacheMode.STALE_IF_ERROR`** — when provider returns `Error` or `RateLimited` and an expired cache entry exists, serves the expired entry as `Success` with `isStale = true`. Does not serve stale for `NotFound` (provider found nothing). Stale results are not re-cached with fresh TTL.
- **`CacheMode` enum** on `EnrichmentConfig` — `NETWORK_FIRST` (default, existing behavior) and `STALE_IF_ERROR`
- **`isStale: Boolean`** on `EnrichmentResult.Success` — `false` by default, `true` when result is from expired cache via stale fallback
- **`getIncludingExpired()`** on `EnrichmentCache` — returns cached entry regardless of expiry. Default implementation returns `null` (backward compatible for custom caches). Implemented by `InMemoryEnrichmentCache` and `RoomEnrichmentCache`.
- **`enrichBatch()`** on `EnrichmentEngine` — returns `Flow<Pair<EnrichmentRequest, EnrichmentResults>>` for bulk enrichment. Sequential iteration with cooperative cancellation. Cache hits return immediately. Default method on interface with explicit override in `DefaultEnrichmentEngine`.
- **Maven Central publishing** via vanniktech `gradle-maven-publish-plugin` targeting `SonatypeHost.CENTRAL_PORTAL` — all 3 modules (`musicmeta-core`, `musicmeta-okhttp`, `musicmeta-android`) with POM metadata (Apache 2.0, developer, SCM), conditional GPG signing, sources + javadoc jars

### Changed
- **`InMemoryEnrichmentCache`** no longer eagerly deletes expired entries on `get()` — expired entries remain in the LRU map for stale serving via `getIncludingExpired()`
- **`EnrichmentEngine` interface** gains `enrichBatch()` default method — custom implementations inherit it automatically
- **Version bumped** from 0.1.0 to 0.8.0 across all modules
- **README** updated with Maven Central as primary installation method; JitPack preserved for existing consumers

## [0.7.0] - 2026-03-24

Developer Experience — profiles, named accessors, cache management, identity signals.

### Added
- **`EnrichmentResults` wrapper** — `enrich()` now returns `EnrichmentResults` (data class) instead of raw `Map`. Includes `raw` map access, `requestedTypes` set, and top-level `IdentityResolution`
- **`IdentityResolution` data class** — engine-level identity outcome (identifiers, match status, score, suggestions) accessible without scanning individual results
- **19 named accessors** on `EnrichmentResults` — `albumArt()`, `artistPhoto()`, `biography()`, `lyrics()`, `credits()`, `genres()`, `genreTags()`, `label()`, `releaseDate()`, `releaseType()`, `country()`, `similarArtists()`, `similarTracks()`, `topTracks()`, `radio()`, `discography()`, `similarAlbums()`, plus `artistPopularity()` and `trackPopularity()`
- **Generic typed accessor** `EnrichmentResults.get<T>(type)` — type-safe data extraction for any `EnrichmentData` subclass
- **`wasRequested(type)` and `result(type)`** on `EnrichmentResults` — distinguish "not requested" from "not found"; access raw result for error diagnostics
- **Metadata field fallback** — `genres()`, `label()`, `releaseDate()`, etc. try the dedicated type first, then fall back to `ALBUM_METADATA`
- **Default type sets** — `EnrichmentRequest.DEFAULT_ARTIST_TYPES`, `DEFAULT_ALBUM_TYPES`, `DEFAULT_TRACK_TYPES`; composable via set algebra
- **`defaultTypesFor(request)`** — returns the appropriate default set for any request kind
- **Profile extension functions** — `engine.artistProfile("Radiohead")`, `engine.albumProfile("OK Computer", "Radiohead")`, `engine.trackProfile("Creep", "Radiohead")` returning structured data classes with computed properties
- **`ArtistProfile`** — photo, bio, genres, members, discography, links, popularity, topTracks, similarArtists, radio, similarAlbums, timeline, genreDiscovery, identity, suggestions
- **`AlbumProfile`** — artwork (front/back/booklet/CD), genres, label, releaseDate, releaseType, country, tracks, editions, similarAlbums, genreDiscovery, identity
- **`TrackProfile`** — genres, lyrics, credits, artwork, popularity, similarTracks, genreDiscovery, identity
- **`SearchCandidate` profile overloads** — `engine.artistProfile(candidate)` for smooth "did you mean?" → re-enrich flow
- **Custom type sets on profiles** — `engine.artistProfile("Radiohead", types = setOf(GENRE, ARTIST_PHOTO))` to skip unnecessary API calls
- **`forceRefresh` parameter** on `enrich()` and all profile extensions — bypasses cache for the requested types, clears existing entries (including manual selections) before fetching
- **`engine.invalidate(request, type?)`** — invalidate cached data by request without knowing internal cache keys. Clears both MBID and name-alias keys. Pass `null` type to clear all types.
- **`engine.isManuallySelected(request, type)` / `engine.markManuallySelected(request, type)`** — manual selection support (e.g., user picks artwork) without cache key knowledge
- `SIMILAR_TRACKS` multi-provider merge — Deezer `/track/{id}/radio` added as second provider alongside Last.fm `track.getSimilar`
- `IdentityMatch` enum, `identityMatchScore`, `NotFound.suggestions`, short-circuit on suggestions, fuzzy fallback search
- **Demo CLI refactored** to showcase all three API tiers — enrichment commands use profile methods (Tier 1), profile summary card shows named accessors (Tier 2), per-type diagnostic output uses raw map (Tier 3). New `refresh` and `invalidate` commands demonstrate cache management. `pick` uses `SearchCandidate` overloads.
- **Developer guide split** into 7 focused pages under `docs/guides/` — quick-start, identity resolution, results & errors, cache management, configuration, extension points, Android integration

### Breaking Changes
> Documented after the fact (backfilled 2026-07-21). The return-type and interface-method breaks below were already flagged inline under **Changed** at release time; they are restated here so the `### Breaking Changes` heading `CLAUDE.md` requires is present. This is a minor release, so documented breaks are within the versioning stance.
- **`EnrichmentCacheEntity` gained three parameters mid-list** (`musicmeta-android`) — `identityMatch`, `identityMatchScore`, and `resolvedIdsJson` were inserted between `confidence` and the previously-following `isManual`/`cachedAt`/`expiresAt` parameters rather than appended. Positional constructors of this published data class rebind their trailing arguments. All three have defaults, so named-argument construction is unaffected. The Room schema change itself is handled by the `MIGRATION_1_2` migration (v1→v2); this note concerns the Kotlin constructor signature, not on-device data.
- **`EnrichmentEngine.enrich()` return type changed** from `Map<EnrichmentType, EnrichmentResult>` to `EnrichmentResults` — a hard break for any caller of the return value; access the raw map via `.raw` (also noted under Changed).
- **`EnrichmentEngine` interface gained `invalidate()`, `isManuallySelected()`, `markManuallySelected()` without default bodies** — every third-party implementation of the interface must add these methods (also noted under Changed).
- **`EnrichmentWorker.onItemEnriched()` parameter type changed** from `Map` to `EnrichmentResults` — overriding subclasses break (also noted under Changed).

### Fixed
- **`ProviderChain` preserves failure reasons** — when all providers fail with `RateLimited` or `Error`, the chain now returns the actual failure instead of collapsing to `NotFound`. Consumers can distinguish "data doesn't exist" from "all providers failed" for retry logic
- **Room cache persists identity fields** — `identityMatch`, `identityMatchScore`, and `resolvedIdentifiers` now round-trip through `RoomEnrichmentCache` (previously silently dropped on cache read). DB migration v1→v2
- **Cache key convergence after disambiguation** — when identity resolution resolves an MBID, results are also cached under the name-based key, so future name-only lookups find the MBID-resolved data
- iTunes `itunesArtistId` stored in `resolvedIdentifiers` after artist search

### Changed
- **Breaking:** `EnrichmentEngine.enrich()` returns `EnrichmentResults` instead of `Map<EnrichmentType, EnrichmentResult>`. Access the raw map via `.raw`. Signature also gains `forceRefresh: Boolean = false` (source compatible)
- **Breaking:** `EnrichmentEngine` interface gains `invalidate()`, `isManuallySelected()`, `markManuallySelected()` — custom implementations must add these methods
- **Breaking:** `EnrichmentWorker.onItemEnriched()` parameter changed from `Map` to `EnrichmentResults`
- Room database version 1 → 2 (automatic migration included via `MIGRATION_1_2`)

## [0.6.0] - 2026-03-23

Recommendations Engine — 31 enrichment types.

### Added
- `SIMILAR_ARTISTS` multi-provider merge — Last.fm, ListenBrainz, and Deezer results deduplicated and combined via `SimilarArtistMerger`; each `SimilarArtist` has a `sources` field listing contributing providers
- `ARTIST_RADIO` enrichment type — Deezer `/artist/{id}/radio` endpoint returns ordered `RadioPlaylist` with up to 25 `RadioTrack` items (title, artist, album, durationMs, identifiers); 7-day TTL
- `SIMILAR_ALBUMS` enrichment type — `SimilarAlbumsProvider` fetches Deezer related artists and their top albums, scored by artist similarity and era proximity (±5yr = 1.2x, ±10yr = 1.0x, beyond = 0.8x)
- `GENRE_DISCOVERY` enrichment type — `GenreAffinityMatcher` uses a static taxonomy of ~70 genre relationships (parent, child, sibling) to produce `GenreAffinity` results with affinity scores and source genres
- `CatalogProvider` interface — consumers implement `checkAvailability(List<CatalogQuery>): List<CatalogMatch>` to filter recommendation results by local library or streaming service availability
- `CatalogFilterMode` enum — `AVAILABLE_ONLY`, `AVAILABLE_FIRST`, `UNFILTERED` — applied post-resolution to all recommendation types
- `ResultMerger` interface — extracted from `DefaultEnrichmentEngine`; `GenreMerger` and `SimilarArtistMerger` implement it; engine delegates all mergeable-type dispatch to the registry
- `CompositeSynthesizer` interface — extracted from `DefaultEnrichmentEngine`; `TimelineSynthesizer` and `GenreAffinityMatcher` implement it; engine delegates all composite-type dispatch to the registry
- `SimilarArtist.sources` field — `List<String>` listing provider IDs that contributed each artist (backfilled for Last.fm and ListenBrainz)
- `GenreAffinity` data class — `name`, `affinity: Float`, `relationship: String`, `sourceGenres: List<String>`
- `SimilarAlbum` data class — `title`, `artist`, `year: Int?`, `artistMatchScore: Float`, `thumbnailUrl: String?`, `identifiers: EnrichmentIdentifiers`
- `RadioPlaylist` and `RadioTrack` data classes — playlist container and track with `durationMs: Long?`
- `CatalogQuery` and `CatalogMatch` data classes — input/output types for `CatalogProvider.checkAvailability()`
- `GenreTaxonomy.kt` — static genre affinity data extracted to its own file (pure constant, no logic)
- `CatalogFilter.kt` — catalog filtering helpers extracted from `DefaultEnrichmentEngine`
- Enrichment showcase test updated with v0.6.0 feature spotlight (SIMILAR_ARTISTS merge, ARTIST_RADIO, SIMILAR_ALBUMS, GENRE_DISCOVERY)

### Changed
- `DefaultEnrichmentEngine` delegates mergeable-type dispatch to `ProviderRegistry.mergers` (was inline); delegates composite-type dispatch to `ProviderRegistry.synthesizers` (was inline)
- `EnrichmentConfig` gains optional `catalogProvider: CatalogProvider?` and `catalogFilterMode: CatalogFilterMode` (default `UNFILTERED` — no behavior change for existing consumers)
- `EnrichmentEngine.Builder` gains `.catalog(provider, mode)` convenience method
- `SIMILAR_ARTISTS` promoted to mergeable type — all configured providers contribute rather than first-success short-circuit

## [0.5.0] - 2026-03-22

New Capabilities & Tech Debt Cleanup.

### Added
- `CREDITS` enrichment type — `EnrichmentData.Credits` with `CreditEntry` (name, role, roleCategory, instruments); MusicBrainz provides via recording artist-rels, Discogs via extraartists
- `RELEASE_EDITIONS` enrichment type — `EnrichmentData.ReleaseEditions` with `ReleaseEdition` (title, format, country, year, catalogNumber, barcode, label); MusicBrainz provides via release-group releases, Discogs via master versions
- `ARTIST_TIMELINE` composite enrichment type — `EnrichmentData.ArtistTimeline` with `TimelineEvent` (date, type, description, relatedEntity); synthesized from `ARTIST_DISCOGRAPHY` + `BAND_MEMBERS` + artist life-span data
- `GENRE` genre tags — `EnrichmentData.Metadata.genreTags: List<GenreTag>?` with per-tag confidence and sources, alongside the existing `genres` field (behaviourally additive; see Breaking Changes for the constructor-signature caveat)
- `GenreTag` data class — `name`, `confidence: Float`, `sources: List<String>`
- `GenreMerger` — additive confidence scoring across MusicBrainz and Last.fm genre data; deduplicates by name
- `TimelineSynthesizer` — composite synthesizer combining discography, members, and life-span into ordered timeline events
- `ResultMerger` / `CompositeSynthesizer` interfaces — engine extension points allowing new mergers and synthesizers without modifying `DefaultEnrichmentEngine`
- `ArtworkSize` extended — Cover Art Archive, Deezer, iTunes, Fanart.tv now all populate `sizes` field with multiple resolutions
- Discogs: `RELEASE_EDITIONS` via master versions endpoint (formats, countries, years, catalog numbers)
- MusicBrainz: `CREDITS` via recording artist-rels (performance, production, songwriting roles)
- `ConfidenceCalculator` — standardized confidence methods (`idBasedLookup`, `authoritative`, `searchScore`, `fuzzyMatch`)
- `ErrorKind` enum on `EnrichmentResult.Error` — `NETWORK`, `AUTH`, `PARSE`, `RATE_LIMIT`, `UNKNOWN`
- `HttpResult` sealed class — typed HTTP responses replacing nullable returns in all 11 providers
- `InMemoryEnrichmentCache` added as default in-process LRU cache
- Enrichment showcase test with v0.5.0 feature spotlight section

### Breaking Changes
> Documented after the fact (backfilled 2026-07-21).
- **`EnrichmentData.Metadata.genreTags` inserted mid-list** — the new `genreTags: List<GenreTag>? = null` parameter was placed between `genres` and `label` rather than appended. `Metadata` is a `@Serializable` public payload type. For positional construction and `copy()` this shifts every trailing argument; for kotlinx.serialization the element indices of `label`, `releaseDate`, and the rest shift by one (name-based JSON is unaffected, but index-based/binary formats and any hand-written `KSerializer` are). The field has a default, so named-argument construction and default JSON round-tripping keep working. Minor release, so a documented break is within the versioning stance.

### Changed
- All 11 providers migrated to `HttpResult`/`ErrorKind` uniform error handling
- `EnrichmentType.defaultTtlMs` — per-type TTL in enum; `EnrichmentConfig.ttlOverrides` for per-type override

## [0.4.0] - 2026-03-21

Provider Abstraction Overhaul — 25 enrichment types.

### Added
- 9 new enrichment types: `BAND_MEMBERS`, `ARTIST_DISCOGRAPHY`, `ALBUM_TRACKS`, `SIMILAR_TRACKS`, `ARTIST_BANNER`, `ARTIST_LINKS`, `ALBUM_ART_BACK`, `ALBUM_BOOKLET`, `ALBUM_METADATA`
- `ArtworkSize` data class and `Artwork.sizes` field — multi-size artwork support across Cover Art Archive, Deezer, iTunes, and Fanart.tv
- `IdentifierRequirement` enum replacing boolean `requiresIdentifier` — typed identifier checking per provider (MUSICBRAINZ_ID, WIKIDATA_ID, WIKIPEDIA_TITLE, etc.)
- `isIdentityProvider` flag and `resolveIdentity()` method on `EnrichmentProvider` interface — formalized identity resolution as a provider role
- 11 `*Mapper.kt` files — provider mapper pattern isolating DTO-to-EnrichmentData mapping from provider logic
- `ApiKeyConfig` data class and `EnrichmentEngine.Builder.apiKeys()` + `withDefaultProviders()` — one-line engine setup
- `EnrichmentIdentifiers.extra` map with `get()` and `withExtra()` — extensible identifier storage for provider-specific IDs (deezerId, discogsArtistId)
- `ErrorKind` enum on `EnrichmentResult.Error` — categorize errors as NETWORK, AUTH, PARSE, RATE_LIMIT, UNKNOWN
- `HttpResult` sealed class with `fetchJsonResult()` on `HttpClient` — typed HTTP responses (Ok, ClientError, ServerError, RateLimited, NetworkError)
- `ConfidenceCalculator` utility — standardized confidence scoring (idBasedLookup, authoritative, searchScore, fuzzyMatch) across all 11 providers
- `EnrichmentType.defaultTtlMs` — TTL moved into enum with `EnrichmentConfig.ttlOverrides` for per-type override
- MusicBrainz: band members via artist-rels, discography via release-group browse, tracklist from media array, artist links from all URL relation types
- Deezer: artist discography via `/artist/{id}/albums`, album tracks via `/album/{id}/tracks`, album metadata (trackCount, explicit, genres)
- Last.fm: `track.getSimilar` for SIMILAR_TRACKS, `track.getInfo` for track-level TRACK_POPULARITY (replacing artist-level data)
- Fanart.tv: ARTIST_BANNER capability via musicbanner images
- Cover Art Archive: JSON metadata endpoint for back cover and booklet art with image type filtering
- Discogs: band members via artist endpoint, album metadata (catalogNumber, communityRating)
- iTunes: album metadata (trackCount, primaryGenreName)
- ListenBrainz: batch POST endpoints for recording and artist popularity, top release groups for artist
- Wikidata: expanded properties — P569 (birth date), P570 (death date), P495 (country of origin), P106 (occupation) in a single API call
- Wikipedia: ARTIST_PHOTO via page media-list endpoint as supplemental source
- Enrichment showcase test updated to reflect v0.4.0 coverage (25 types)

### Breaking Changes
> Documented after the fact (backfilled 2026-07-21). These were shipped as clean removals/renames with no deprecation cycle — verified against `git diff v0.1.0..v0.4.0`. Most were noted under **Changed** at the time; they are consolidated here under the `### Breaking Changes` heading `CLAUDE.md` requires, and the `preferredArtworkSize` deletion (previously undocumented) is added. This is a minor release, so documented breaks are within the versioning stance.
- **`EnrichmentConfig.preferredArtworkSize: Int` and its `EnrichmentConfig.DEFAULT_ARTWORK_SIZE` default constant removed** — previously undocumented anywhere in this changelog. Consumers reading or setting `preferredArtworkSize` no longer compile; multi-size artwork is now expressed through `Artwork.sizes`. (A same-named `DEFAULT_ARTWORK_SIZE` still exists on individual provider companions — only the `EnrichmentConfig` binding was removed.)
- **`ProviderCapability.requiresIdentifier: Boolean` renamed and retyped to `identifierRequirement: IdentifierRequirement`** — breaks every third-party `EnrichmentProvider`/capability declaration, both the property name and its type.
- **`EnrichmentData.IdentifierResolution` sealed subclass removed** — consumers matching on it in a `when` over `EnrichmentData` no longer compile. Identity resolution now surfaces via `resolvedIdentifiers` on `EnrichmentResult.Success`.
- **`SimilarArtist.musicBrainzId: String?` and `PopularTrack.musicBrainzId: String?` replaced by `identifiers: EnrichmentIdentifiers`** — both types are `@Serializable`, so this breaks source (property access) *and* any persisted JSON: entries serialized by any pre-0.4.0 version carrying `musicBrainzId` no longer round-trip into the new `identifiers` shape.

### Changed
- `ProviderCapability.requiresIdentifier: Boolean` replaced by `identifierRequirement: IdentifierRequirement` enum
- `ProviderRegistry.identityProvider()` selects by `isIdentityProvider` flag instead of GENRE/LABEL heuristic
- `DefaultEnrichmentEngine.needsIdentityResolution()` is data-driven from provider capabilities, not hardcoded type list
- `DefaultEnrichmentEngine.ttlFor()` removed — TTL now on `EnrichmentType.defaultTtlMs` with config override
- `SimilarArtist.musicBrainzId: String?` replaced by `identifiers: EnrichmentIdentifiers`
- `PopularTrack.musicBrainzId: String?` replaced by `identifiers: EnrichmentIdentifiers`
- `EnrichmentData.IdentifierResolution` removed from public sealed class — identity resolution uses `resolvedIdentifiers` on `EnrichmentResult.Success`
- MusicBrainzProvider returns `Metadata` directly from identity resolution instead of `IdentifierResolution`
- All 11 providers delegate EnrichmentData construction to mapper objects (zero inline construction)
- All 11 providers use `ConfidenceCalculator` methods (zero hardcoded float confidence values)

### Fixed
- MusicBrainz: empty search results return `NotFound` instead of `RateLimited` (3 locations)
- Last.fm: API base URL uses HTTPS instead of HTTP
- Last.fm: `TRACK_POPULARITY` removed from capabilities (was returning artist-level data); properly restored with `track.getInfo`
- LRCLIB: duration parameter uses `Double` instead of `Int` — preserves fractional seconds (238500ms → 238.5s, not 238s)
- Wikidata: claim resolution filters for preferred-rank claims before falling back to first in array
- Wikidata: URL-encode pipe characters in multi-property query string (prevents `URISyntaxException`)

## [0.1.0] - 2026-03-21

### Added
- `EnrichmentEngine` with builder pattern, fan-out provider chains, confidence filtering, and configurable timeout
- Identity resolution pipeline — MusicBrainz resolves MBIDs, Wikidata IDs, and Wikipedia titles for downstream providers
- 11 providers: MusicBrainz, Cover Art Archive, Wikidata, Wikipedia, LRCLIB, Deezer, iTunes, Last.fm, ListenBrainz, Fanart.tv, Discogs
- `ProviderChain` with priority ordering and circuit breakers per provider
- `RateLimiter` for per-provider request throttling
- `InMemoryEnrichmentCache` with LRU eviction and TTL
- `EnrichmentConfig` with `minConfidence`, `confidenceOverrides`, `enableIdentityResolution`, `enrichTimeoutMs`
- `HttpClient` interface with `DefaultHttpClient` (java.net.HttpURLConnection)
- `ArtistMatcher` for music-aware fuzzy name matching across providers
- Search API (`engine.search()`) with candidate deduplication across providers
- Enrichment showcase test (`EnrichmentShowcaseTest`) — comprehensive E2E diagnostic
- API key forwarding in `build.gradle.kts` — Last.fm, Fanart.tv, and Discogs keys via system properties or env vars
- `musicmeta-android` module: `RoomEnrichmentCache`, `HiltEnrichmentModule`, `EnrichmentWorker`
- E2E test suite against real APIs (gated by `-Dinclude.e2e=true`)
- JitPack publishing support

### Fixed
- Artist identity resolution resolves wikidata/wikipedia URLs during all identity lookups
- E2E tests use `runBlocking` (not `runTest`) to avoid virtual-time timeout issues
- Silent exception swallowing in engine and cache now logged through `EnrichmentLogger`

### Changed
- Provider priorities configurable via `EnrichmentConfig.priorityOverrides`
- MusicBrainz minimum match score is a constructor param (`minMatchScore`, default 80)
- Artwork sizes are per-provider constructor params (not engine-level config)
