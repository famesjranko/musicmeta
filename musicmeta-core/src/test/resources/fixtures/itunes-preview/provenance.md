Captured 2026-11-07 before the preview fallback was implemented from
https://itunes.apple.com/search?term=Radiohead+Karma+Police&entity=song&limit=5
with HTTP 200. JSON field values are unchanged; trailing whitespace and blank lines were removed. It includes the original track,
live and remastered versions, an EP release, and a Rockabye Baby! cover.
Tests select subsets or remove optional preview fields to exercise absence without
inventing a different upstream schema. No preview media is stored in this fixture.

Additional song searches captured 2026-11-07 before preview text normalization was implemented:

- `journey.json`: HTTP 200 from https://itunes.apple.com/search?media=music&entity=song&country=us&term=Journey+Don%27t+Stop+Believin%27&limit=25
- `umbrella.json`: HTTP 200 from https://itunes.apple.com/search?media=music&entity=song&country=us&term=Rihanna+Umbrella&limit=25
- `guns.json`: HTTP 200 from https://itunes.apple.com/search?media=music&entity=song&country=us&term=Guns+N%27+Roses+Sweet+Child+O%27+Mine&limit=25

These fixtures select complete, unchanged rows by track id from each captured pool;
`resultCount` states the retained row count. They include original recordings, covers,
live versions, remixes, featured credits, and a compilation album with an apostrophe.
The tests vary request text and select subsets; they do not rewrite catalogue titles.
No preview media was saved.

Full US song-search pools captured 2026-11-07 with HTTP 200, before the credit-ranking fix:

- `girls.json`: https://itunes.apple.com/search?media=music&entity=song&country=us&term=Maroon+5+Girls+Like+You&limit=25
- `girls-album.json`: https://itunes.apple.com/search?media=music&entity=song&country=us&term=Maroon+5+Girls+Like+You+Red+Pill+Blues&limit=25
- `levitating.json`: https://itunes.apple.com/search?media=music&entity=song&country=us&term=Dua+Lipa+Levitating&limit=25
- `cheap-thrills.json`: https://itunes.apple.com/search?media=music&entity=song&country=us&term=Sia+Cheap+Thrills&limit=25

These files preserve complete response bytes and upstream order. Each pool contains plain and
credited recordings. Plain-request tests reverse a two-row subset to prove that title preference
beats upstream order in both directions. Boundary and credit-selection tests explicitly replace a captured title or URL
to exercise malformed data and ordinary title words; these replacements are not upstream claims.
The schema-pin test removes `previewUrl` to simulate drift. No preview media was requested or saved.

Full Journey US song pools captured 2026-11-08 before the album-preference change:

- `journey-escape.json`: HTTP 200 at 06:39:09 UTC from https://itunes.apple.com/search?media=music&entity=song&country=us&term=Journey+Don%27t+Stop+Believin%27+Escape&limit=25 (25 rows).
- `journey-full.json`: HTTP 200 at 06:39:12 UTC from https://itunes.apple.com/search?media=music&entity=song&country=us&term=Journey+Don%27t+Stop+Believin%27&limit=25 (23 rows).

These files retain complete JSON responses and upstream order; only surrounding blank lines and
trailing whitespace were removed. Exactly two iTunes requests were sent, at least 3.2 seconds apart, with `MusicEnrichmentEngine/1.0` and the repository URL
in the User-Agent. No Deezer request or preview-media request was sent.
The Escape row (`1640102584`) has both a track-title and an album-title `2022 Remaster` qualifier;
a plain-title request must still refuse that track, even when the album preference matches.
Album-preference tests use unchanged captured rows and vary only the requested album hint,
including edition qualifiers from the shared vocabulary. Retry tests select only captured live
or other-artist rows for an unacceptable first pool, or an empty pool to model absence.
