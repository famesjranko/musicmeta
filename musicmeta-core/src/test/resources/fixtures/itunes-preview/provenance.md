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
