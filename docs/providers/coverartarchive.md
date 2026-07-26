# Cover Art Archive

What our code does with the Cover Art Archive. For the API itself — endpoints, request shapes, error
codes, rate limits — follow the upstream link; that is authoritative and this is not.

| | |
|---|---|
| **Package** | `provider/coverartarchive/` |
| **Provider ids** | `coverartarchive` |
| **Upstream API docs** | https://musicbrainz.org/doc/Cover_Art_Archive/API |
| **Auth** | None |
| **Deviations from the house pattern** | None — the four files, as `CLAUDE.md` describes them |

**Why this provider.** It is the only artwork source keyed on a MusicBrainz release rather than on a
name search, so its results are the ones we trust at `idBasedLookup()` confidence (1.0), and the only
source in the tree for the non-front images — back cover, booklet, disc.

## What We Extract

One row per entry in `CoverArtArchiveProvider.capabilities`.

| EnrichmentType | Identifier | Upstream call | What we keep |
|---|---|---|---|
| `ALBUM_ART` | `MUSICBRAINZ_ID` | `/release/{mbid}/front-{size}` redirect, twice, then `/release/{mbid}` | redirect URL, thumbnail URL, and every `thumbnails` entry of the first `front` image as `ArtworkSize` |
| `ALBUM_ART_BACK` | `MUSICBRAINZ_ID` | `/release/{mbid}` | first image whose `types` holds `"Back"`: `image`, `thumbnails["small"]`, all sizes |
| `ALBUM_BOOKLET` | `MUSICBRAINZ_ID` | `/release/{mbid}` | the same, for `"Booklet"` |
| `CD_ART` | `MUSICBRAINZ_ID` | `/release/{mbid}` | the same, for `"Medium"` |

Every result is `ConfidenceCalculator.idBasedLookup()`, 1.0 — correct, because nothing here is a
name match. `CD_ART` is priority 50, below Fanart.tv; the other three are 100 and uncontested.

`ALBUM_ART` costs up to **three** HTTP calls on a hit: a redirect check at `artworkSize` (1200), a
second at `thumbnailSize` (250), and the JSON metadata call that supplies `sizes`. The three
type-specific capabilities cost one, and read the full-size `image` URL straight from the JSON rather
than through a redirect.

There is a **release-group fallback** in `findArtwork` — `/release-group/{id}/front-{size}` when the
release has no art — that the engine cannot reach. All four capabilities declare
`MUSICBRAINZ_ID`, so `ProviderChain.hasRequiredIdentifiers()` skips the provider outright when only a
release-group id is present. It runs only for a consumer calling the provider directly, or when both
identifiers are set and the release genuinely has no art. `ANY_IDENTIFIER` is the declaration that
would match the code. Recorded, not changed — see `docs/pitfalls.md` §5.

## What We DON'T Extract

`/release/{mbid}` is already fetched for every capability above, so these cost nothing but code:

| Field | Useful for |
|---|---|
| `images[].comment` | Community note — which of several front covers this is |
| `images[].approved` | Whether the image passed review; we take the first match either way |
| `images[].id` | Stable per-image id, for `/release/{mbid}/{id}-{size}` |
| `images[].back` | We match on `types`, so this boolean is redundant but cheaper |
| Every `types` value we do not map | `"Obi"`, `"Spine"`, `"Track"`, `"Tray"`, `"Sticker"`, `"Poster"`, `"Liner"`, `"Watermark"`, `"Raw/Unedited"`, `"Matrix/Runout"`, `"Top"`, `"Bottom"` |
| Second and later images of a type | `firstOrNull` takes one; alternate pressings' art is discarded |

`/release-group/{id}` JSON is never called, only its `front-{size}` redirect, which is why the
release-group path returns no `sizes`.

## Gotchas

- `docs/pitfalls.md` §5 — see the release-group note above. This is the pitfall's own worked example,
  and the declaration is still narrower than the code.
- `docs/pitfalls.md` §3 — `parseImageList` reads through `optJSONArray`/`optBoolean`/`optString`, so a
  moved field yields an empty image list, which reads as "nobody uploaded art".
- `docs/pitfalls.md` §4 — a 404 (no art) arrives as a null redirect and becomes `NotFound`, correctly:
  no artwork is not a provider failure.

Two that are ours:

- **The provider is not rate limited.** The constructor takes a `RateLimiter` and marks it
  `@Suppress("UNUSED_PARAMETER")`; no call goes through it. Every other provider's calls are spaced.
- **`thumbnails["small"]` is a deprecated alias** for `"250"`. The three type-specific capabilities
  read it, so they return a null thumbnail against any response that has dropped the alias, while
  `sizes` still carries the numeric keys.
