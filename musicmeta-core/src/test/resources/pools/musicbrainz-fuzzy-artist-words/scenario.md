# Supplied-artist fuzzy artist-word captures

This is a selected subset of 47 files from the 119 captured responses in the full experiment. Captured live on 2026-11-08 before the artist-clause change; title terms follow #424 at 8cbce82a0f128ff60d1a4a3468bf361d55b47b0d. Requests used MusicBrainz WS/2, limit 3, sequentially, at least 1.1 seconds after every response, with User-Agent `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`.

The full experiment had 138 attempts: 119 HTTP 200 captures and 19 HTTP 503 responses retried. Errors were not zero-hit results. `current`, `words`, `phrase`, and `without-feat` name the fuzzy artist arms; `strict` and `canonical` name the quoted strict query and recording query with `AND -comment:*`. All 39 strict/canonical responses were empty; this subset retains nine. The featured words and diagnostic without-feat requests both missed; the cause is not isolated.

Only three returned rows were inspected. These captures do not establish live engine behavior, album-hint behavior, general recall, latency, or identity. Michael Radiohead is a constructed negative control. AND rows do not demonstrate Boolean harm; artist OR/NOT were not measured. The two leading-The requests are separate controls.

The test coverage is in `MusicBrainzFuzzyArtistWordsTest.kt` and `MusicBrainzFuzzyArtistWordsPathsTest.kt`.

| File | Decoded query | Hits | Top three rows in returned order |
|---|---|---:|---|
| `recording-alone-words.json` | `recording:you AND recording:are AND recording:not AND recording:aloen~ AND artistname:michael~ AND artistname:jackson~` | 50 | You Are Not Alone — Michael Jackson; You Are Not Alone — Michael Jackson; You Are Not Alone — Michael Jackson |
| `release-alone-words.json` | `release:you AND release:are AND release:not AND release:aloen~ AND artistname:michael~ AND artistname:jackson~` | 10 | You Are Not Alone — Michael Jackson; You Are Not Alone — Michael Jackson; You Are Not Alone — Michael Jackson |
| `recording-billie-words.json` | `recording:bilie~ AND recording:jean~ AND artistname:michael~ AND artistname:jackson~` | 155 | Billie Jean — Michael Jackson; Billie Jean — Michael Jackson; Billie Jean — Michael Jackson |
| `release-billie-words.json` | `release:bilie~ AND release:jean~ AND artistname:michael~ AND artistname:jackson~` | 12 | Billie Jean — Michael Jackson; Billie Jean — Michael Jackson; Billie Jean — Michael Jackson |
| `recording-stairway-words.json` | `recording:stairway~ AND recording:to AND recording:heavne~ AND artistname:led AND artistname:zeppelin~` | 853 | Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin |
| `release-stairway-words.json` | `release:stairway~ AND release:to AND release:heavne~ AND artistname:led AND artistname:zeppelin~` | 10 | Stairway to Heaven — Led Zeppelin; “Stairway to Heaven” (Vol. 3) — Led Zeppelin; Stairway to Heaven, Volume 4 — Led Zeppelin |
| `recording-simon-words.json` | `recording:the AND recording:sound~ AND recording:of AND recording:silnce~ AND artistname:simon~ AND artistname:and AND artistname:garfunkel~` | 116 | The Sound of Silence — Simon & Garfunkel; The Sound of Silence — Simon & Garfunkel; The Sound of Silence — Simon & Garfunkel |
| `release-simon-words.json` | `release:the AND release:sound~ AND release:of AND release:silnce~ AND artistname:simon~ AND artistname:and AND artistname:garfunkel~` | 5 | The Sounds of Silence, Part 1 — Simon & Garfunkel; 1969-11-11: The Sound of Silence: OH, USA — Simon & Garfunkel; The Sounds of Silence / I Am a Rock — Simon & Garfunkel |
| `recording-earth-words.json` | `recording:septmeber~ AND artistname:earth~ AND artistname:wind~ AND artistname:and AND artistname:fire~` | 172 | September — Earth Wind and Fire; September — Earth, Wind & Fire; September — Earth, Wind & Fire |
| `release-earth-words.json` | `release:septmeber~ AND artistname:earth~ AND artistname:wind~ AND artistname:and AND artistname:fire~` | 14 | September — Earth, Wind & Fire; September — Earth, Wind & Fire; September — Earth, Wind & Fire |
| `recording-micheal-strict.json` | `recording:"Bilie Jean" AND artistname:"Micheal Jackson"` | 0 | none |
| `recording-micheal-current.json` | `recording:bilie~ AND recording:jean~ AND artistname:Micheal Jackson~` | 0 | none |
| `recording-micheal-words.json` | `recording:bilie~ AND recording:jean~ AND artistname:micheal~ AND artistname:jackson~` | 155 | Billie Jean — Michael Jackson; Billie Jean — Michael Jackson; Billie Jean — Michael Jackson |
| `recording-micheal-phrase.json` | `recording:bilie~ AND recording:jean~ AND artistname:"micheal jackson"` | 0 | none |
| `recording-micheal-canonical.json` | `recording:"Bilie Jean" AND artistname:"Micheal Jackson" AND -comment:*` | 0 | none |
| `release-micheal-strict.json` | `release:"Bilie Jean" AND artistname:"Micheal Jackson"` | 0 | none |
| `release-micheal-current.json` | `release:bilie~ AND release:jean~ AND artistname:Micheal Jackson~` | 0 | none |
| `release-micheal-words.json` | `release:bilie~ AND release:jean~ AND artistname:micheal~ AND artistname:jackson~` | 12 | Billie Jean — Michael Jackson; Billie Jean — Michael Jackson; Billie Jean — Michael Jackson |
| `release-micheal-phrase.json` | `release:bilie~ AND release:jean~ AND artistname:"micheal jackson"` | 0 | none |
| `recording-zepplin-current.json` | `recording:stairway~ AND recording:to AND recording:heavne~ AND artistname:Led Zepplin~` | 856 | Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin |
| `recording-zepplin-words.json` | `recording:stairway~ AND recording:to AND recording:heavne~ AND artistname:led AND artistname:zepplin~` | 853 | Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin |
| `recording-zepplin-phrase.json` | `recording:stairway~ AND recording:to AND recording:heavne~ AND artistname:"led zepplin"` | 0 | none |
| `release-zepplin-current.json` | `release:stairway~ AND release:to AND release:heavne~ AND artistname:Led Zepplin~` | 10 | Rock Report Led Zeppelin Stairway to Heaven — Led Zeppelin; Stairway to Heaven — Led Zeppelin; “Stairway to Heaven” (Vol. 3) — Led Zeppelin |
| `release-zepplin-words.json` | `release:stairway~ AND release:to AND release:heavne~ AND artistname:led AND artistname:zepplin~` | 10 | Stairway to Heaven — Led Zeppelin; “Stairway to Heaven” (Vol. 3) — Led Zeppelin; Stairway to Heaven, Volume 4 — Led Zeppelin |
| `release-zepplin-phrase.json` | `release:stairway~ AND release:to AND release:heavne~ AND artistname:"led zepplin"` | 0 | none |
| `recording-the-beatles-words.json` | `recording:yestreday~ AND artistname:the AND artistname:beatles~` | 228 | Yesterday — The Beatles; Yesterday — The Beatles; Yesterday — The Beatles |
| `release-the-beatles-words.json` | `release:yestreday~ AND artistname:the AND artistname:beatles~` | 39 | Yesterday — The Beatles; Yesterday — The Beatles; Yesterday — The Beatles |
| `recording-beatles-words.json` | `recording:yestreday~ AND artistname:beatles~` | 234 | Yesterday — The Beatles; Yesterday — The Beatles; Yesterday — The Beatles |
| `release-beatles-words.json` | `release:yestreday~ AND artistname:beatles~` | 39 | Yesterday — The Beatles; Yesterday — The Beatles; Yesterday — The Beatles |
| `recording-featured-strict.json` | `recording:"Love the Way You Liee" AND artistname:"Eminem feat. Rihanna"` | 0 | none |
| `recording-featured-words.json` | `recording:love~ AND recording:the AND recording:way AND recording:you AND recording:liee~ AND artistname:eminem~ AND artistname:feat~ AND artistname:rihanna~` | 0 | none |
| `recording-featured-canonical.json` | `recording:"Love the Way You Liee" AND artistname:"Eminem feat. Rihanna" AND -comment:*` | 0 | none |
| `release-featured-strict.json` | `release:"Love the Way You Liee" AND artistname:"Eminem feat. Rihanna"` | 0 | none |
| `release-featured-words.json` | `release:love~ AND release:the AND release:way AND release:you AND release:liee~ AND artistname:eminem~ AND artistname:feat~ AND artistname:rihanna~` | 0 | none |
| `recording-radiohead-words.json` | `recording:krama~ AND recording:police~ AND artistname:radiohead~` | 281 | Karma Police — Radiohead; Karma Police — Radiohead; Karma Police — Radiohead |
| `release-radiohead-words.json` | `release:krama~ AND release:police~ AND artistname:radiohead~` | 9 | Karma Police — Radiohead; Karma Police — Radiohead; Karma Police — Radiohead |
| `recording-eagles-words.json` | `recording:hotle~ AND recording:california~ AND artistname:eagles~` | 113 | Hotel California — Eagles; Hotel California — Eagles; Hotel California — Eagles |
| `release-eagles-words.json` | `release:hotle~ AND release:california~ AND artistname:eagles~` | 84 | Hotel California — Eagles; Hotel California — Eagles; Hotel California — Eagles |
| `recording-negative-strict.json` | `recording:"Bilie Jean" AND artistname:"Michael Radiohead"` | 0 | none |
| `recording-negative-current.json` | `recording:bilie~ AND recording:jean~ AND artistname:Michael Radiohead~` | 156 | Billie Jean — Michael Jackson; Billie Jean — Michael Jackson; Billie Jean — Michael Jackson |
| `recording-negative-words.json` | `recording:bilie~ AND recording:jean~ AND artistname:michael~ AND artistname:radiohead~` | 0 | none |
| `recording-negative-canonical.json` | `recording:"Bilie Jean" AND artistname:"Michael Radiohead" AND -comment:*` | 0 | none |
| `release-negative-strict.json` | `release:"Bilie Jean" AND artistname:"Michael Radiohead"` | 0 | none |
| `release-negative-current.json` | `release:bilie~ AND release:jean~ AND artistname:Michael Radiohead~` | 13 | Billie Jean — Michael Jackson; Billie Jean — Michael Jackson; Billie Jean — Michael Jackson |
| `release-negative-words.json` | `release:bilie~ AND release:jean~ AND artistname:michael~ AND artistname:radiohead~` | 0 | none |
| `recording-featured-without-feat.json` | `recording:love~ AND recording:the AND recording:way AND recording:you AND recording:liee~ AND artistname:eminem~ AND artistname:rihanna~` | 0 | none |
| `release-featured-without-feat.json` | `release:love~ AND release:the AND release:way AND release:you AND release:liee~ AND artistname:eminem~ AND artistname:rihanna~` | 0 | none |
