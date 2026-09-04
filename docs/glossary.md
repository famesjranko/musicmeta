# Glossary

One word per concept, and the word each upstream uses for the same thing. Read it before naming a
public type or member, and before reading a provider's mapper — the mapper's job is precisely to
cross this table, and a name that crosses it in the wrong direction leaks an upstream's vocabulary
into the published surface.

Scope is vocabulary. What each provider *serves*, and what it deliberately leaves, is
[providers.md](providers.md); how the pipeline uses the resolved ids is
[how-it-works.md](how-it-works.md).

**Half of this is checked.** `scripts/checks/check_public_vocabulary.py` gates the one rule with a
mechanical form — an upstream's word may not appear unqualified in the published surface — and
nothing verifies the rest. Hand-verified against the packages on **2026-08-19**; treat anything
after that as a claim, not a fact.

## The three kinds

An `EnrichmentRequest` is an **artist**, an **album**, or a **track** — `forArtist`, `forAlbum`,
`forTrack` in `EnrichmentRequest.kt`. Everything below is one of those three seen through an
upstream's schema.

| This library | MusicBrainz | ListenBrainz | Discogs | iTunes | Deezer, Last.fm, LRCLIB |
|---|---|---|---|---|---|
| **artist** | artist | `artist_mbid` | artist | artist | artist |
| **album** | release-group | `release_name` | master | collection | album |
| **track** | recording | `recording_mbid` | track | track | track |
| **album edition** | release | — | release | — | — |

Where to see each claim in the code:

| Row | Evidence |
|---|---|
| album ↔ release-group | `EnrichmentIdentifiers.musicBrainzReleaseGroupId`; `MusicBrainzApi.browseReleaseGroupReleases` |
| album ↔ master | `DiscogsApi.getMasterVersions`, `MASTERS_URL` |
| album ↔ collection | `ITunesModels.ITunesAlbumResult.collectionId`/`collectionName`; a lookup filters `wrapperType == "collection"` |
| track ↔ recording | `MusicBrainzEntityType`'s KDoc states it outright: *"a recording is a track, a release an album"* |
| ListenBrainz speaks MusicBrainz | its payload fields are `recording_mbid`, `recording_name`, `release_name`, `artist_mbid` — `ListenBrainzApi.kt` |
| album edition ↔ release | `EnrichmentData.ReleaseEditions`, whose `ReleaseEdition` carries `format`, `country`, `catalogNumber` — the fields that distinguish pressings of one album |

The last column is the reason the table is short: most upstreams already say artist/album/track.
The ones that do not are the ones whose mappers do the most translation — and ListenBrainz is
MusicBrainz's vocabulary reached through a different API, not a fourth dialect.

## The traps that follow

**`musicBrainzId` is polymorphic by request kind.** It holds a release id on an album request, an
artist id on an artist request, and a *recording* id on a track request. A capability declaring
`MUSICBRAINZ_ID` therefore declares "an id of the request's own kind", not which kind it can serve.
The consequence that bites: a recording id cannot stand in for the release-group id Cover Art
Archive is keyed on, so a track request carrying a recording id still needs
`musicBrainzReleaseGroupId` for artwork. `MusicBrainzEntityType` is what answers "what does this id
name"; `how-it-works.md` has the worked path.

**`release` and `release-group` are different entities, and both are called "release" in prose.**
The release-group is the album; a release is one pressing of it. `ReleaseEditions` is the only
public type about the second, and every other public use of the word means the first.

**`CanonicalStatus.RESOLVING` means identity resolution is still running, not that it failed.**
It appears only on a pre-terminal `enrichProgressive` emission — a type already served from cache
can settle and be sent before the same call's canonical identity lookup returns. `enrich()`'s
return and every stream's terminal emission always carry a settled status instead; a consumer that
only calls `enrich()` never sees it.

**`ErrorKind.ENGINE_CLOSED` means the engine was `close()`d before an uncached, in-flight type
settled, not that the type failed or timed out.** A fully-cached call never reaches this: it
returns its cache hit and succeeds normally even after `close()`, without ever registering a
detached run. `ENGINE_CLOSED` appears only on a type that was genuinely uncached and in flight (or
would have been, for a request/types combination the engine had never seen before `close()`) — a
still-attached `enrichProgressive` collector or `enrich()`'s own return sees it there, and only
there — never on a run that reached its own deadline (that is `ErrorKind.TIMEOUT`) or on one still
running against an open engine.

**`EnrichmentResult.Success.isCatalogDegraded` means catalog filtering was attempted and could
not run, not that filtering was turned off.** A recommendation type still reaches the caller when
its `CatalogProvider` throws — the fetched data survives unranked rather than being lost — and this
flag is how that result says so. It is always `false` under `CatalogFilterMode.UNFILTERED`, which is
a deliberate configuration and not a degradation. See `EnrichmentResult.Success.isCatalogDegraded`'s
KDoc for why this is call-scoped, not a stored fact.

**alias pool** is every name identity resolution found for one entity — its canonical name plus
each alternative name the identity provider files under it — and it is what a provider's candidate
is verified against when the requested name matches nothing. MusicBrainz calls these `aliases` and
marks the ones an entity is published under; Discogs calls the same thing `namevariations` and
marks none of them. A *cache* alias is a different thing entirely: that is a second key one result
is stored under (`how-it-works.md`, Step 8).

**Discogs `master_id` is optional.** A Discogs release may have no master, so the album-level
identifier is absent rather than derivable — treat it as a miss, not as a release id to reuse.

## The rule on the published surface

An upstream's word for a concept this library already names may appear in a public identifier
**only when the provider's own name is attached** — in the identifier itself or in its enclosing
type. `getMusicBrainzReleaseGroupId`, `MUSICBRAINZ_RECORDING` and `MusicBrainzEntityType.RECORDING`
all qualify and are legal; a bare `recordingId` or `masterId` on a public type is not.

`scripts/checks/check_public_vocabulary.py` reads the committed `api/*.api` and gates this. It has
an allowlist, and an entry there needs a reason. What it cannot see is a name that is merely
*wrong* rather than borrowed — that stays review's job
([agents/review-checklist.md](agents/review-checklist.md)).

Two shapes carry the same rule, because a scale and a date type are as much a word for a concept as
a name is.

**One score scale.** Every score on the published surface is a `Float` in 0.0–1.0. `confidence`
scores a lookup or a claim (`EnrichmentResult.Success.confidence`, `GenreTag.confidence`,
`CatalogMatch.confidence`); `matchScore` ranks a match within one pool (`SearchCandidate`,
`IdentityResolution`, `SimilarArtist`, `SimilarTrack`). An upstream's own scale — MusicBrainz's
0–100 search score, a 1–5 community rating — is normalised at the mapper, or carried with its
source and kind in a `PopularitySignal`; it never reaches the surface raw. The one exception is
`SimilarAlbum.artistMatchScore`, a rank product that can reach 1.2 and says so in its KDoc.

**One date type.** A date is a `String` in ISO-8601, as precise as the upstream gave it (`YYYY`,
`YYYY-MM` or `YYYY-MM-DD`): `Metadata.releaseDate`, `Metadata.beginDate`, `Metadata.endDate`,
`TimelineEvent.date`, `ProviderPolicy.asReadOn`. A `year` is an `Int?`: `SimilarAlbum`,
`ReleaseEdition`, `DiscographyAlbum`, `EnrichmentRequest.ForAlbum`, `SearchCandidate`. A writer
holding a date truncates to the leading four digits, and a value that does not begin with a year
becomes null rather than a string the caller re-parses. No `java.time` on the surface, which is API
26+ on Android, and no `kotlinx-datetime`, which would be a core dependency every consumer
inherits.
