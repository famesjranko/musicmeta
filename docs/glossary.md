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
| album ↔ release-group | `EnrichmentIdentifiers.musicBrainzReleaseGroupId`; `MusicBrainzApi.lookupReleaseGroup` |
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

**`EnrichmentResult.Success.isCatalogDegraded` means catalog filtering was attempted and could
not run, not that filtering was turned off.** A recommendation type still reaches the caller when
its `CatalogProvider` throws — the fetched data survives unranked rather than being lost — and this
flag is how that result says so. It is always `false` under `CatalogFilterMode.UNFILTERED`, which is
a deliberate configuration and not a degradation. It is call-scoped: every serve, live or a cache
hit, normalizes it to `false` before this call's own catalog check runs or is skipped, so it
reports whether *this* call could rank the result, never whether the value was ranked when it
was written — no shipped cache persists it.

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
