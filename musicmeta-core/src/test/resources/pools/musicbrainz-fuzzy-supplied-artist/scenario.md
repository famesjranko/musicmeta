# Supplied-artist fuzzy title captures

Captured live on 2026-11-08 before the query implementation changed. Complete unmodified HTTP 200 bodies from `https://musicbrainz.org/ws/2/`, limit 3. Requests were sequential, with at least 1.1 seconds after every response, including HTTP 503 retries. User-Agent: `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`. `captures.json` records receipt times and SHA-256 hashes.

Only the first three rows are captured. These establish the named controls, not general recall, relevance, latency, or live engine behaviour. Artist matching and the fallback ladder are outside this experiment.

| File | Decoded query | Hits |
|---|---|---:|
| `recording-kiss-strict.json` | `recording:"Rokc AND Roll" AND artistname:"Kiss"` | 0 |
| `recording-kiss-old.json` | `recording:Rokc AND Roll~ AND artistname:Kiss~` | 0 |
| `recording-kiss-words.json` | `recording:rokc~ AND recording:and AND recording:roll~ AND artistname:Kiss~` | 353 |
| `release-kiss-strict.json` | `release:"Rokc AND Roll" AND artistname:"Kiss"` | 0 |
| `release-kiss-old.json` | `release:Rokc AND Roll~ AND artistname:Kiss~` | 0 |
| `release-kiss-words.json` | `release:rokc~ AND release:and AND release:roll~ AND artistname:Kiss~` | 58 |
| `recording-halsey-strict.json` | `recording:"Now OR Nevr" AND artistname:"Halsey"` | 0 |
| `recording-halsey-old.json` | `recording:Now OR Nevr~ AND artistname:Halsey~` | 236 |
| `recording-halsey-words.json` | `recording:now AND recording:or AND recording:nevr~ AND artistname:Halsey~` | 6 |
| `release-halsey-strict.json` | `release:"Now OR Nevr" AND artistname:"Halsey"` | 0 |
| `release-halsey-old.json` | `release:Now OR Nevr~ AND artistname:Halsey~` | 26 |
| `release-halsey-words.json` | `release:now AND release:or AND release:nevr~ AND artistname:Halsey~` | 3 |
| `recording-eminem-strict.json` | `recording:"NOT Afriad" AND artistname:"Eminem"` | 0 |
| `recording-eminem-old.json` | `recording:NOT Afriad~ AND artistname:Eminem~` | 7329417 |
| `recording-eminem-words.json` | `recording:not AND recording:afriad~ AND artistname:Eminem~` | 19 |
| `release-metallica-leading-strict.json` | `release:"AND Justcie for All" AND artistname:"Metallica"` | 0 |
| `release-metallica-leading-old.json` | `release:AND Justcie for All~ AND artistname:Metallica~` | 838682 |
| `release-metallica-leading-words.json` | `release:and AND release:justcie~ AND release:for AND release:all AND artistname:Metallica~` | 55 |
| `release-metallica-trailing-strict.json` | `release:"Justcie for All AND" AND artistname:"Metallica"` | 0 |
| `release-metallica-trailing-old.json` | `release:Justcie for All AND~ AND artistname:Metallica~` | 838682 |
| `release-metallica-trailing-words.json` | `release:justcie~ AND release:for AND release:all AND release:and AND artistname:Metallica~` | 55 |
| `recording-negative-strict.json` | `recording:"Karma Radiohead" AND artistname:"Radiohead"` | 0 |
| `recording-negative-old.json` | `recording:Karma Radiohead~ AND artistname:Radiohead~` | 12614 |
| `recording-negative-words.json` | `recording:karma~ AND recording:radiohead~ AND artistname:Radiohead~` | 0 |
| `recording-radiohead-strict.json` | `recording:"Krama Police" AND artistname:"Radiohead"` | 0 |
| `recording-radiohead-old.json` | `recording:Krama Police~ AND artistname:Radiohead~` | 357 |
| `recording-radiohead-words.json` | `recording:krama~ AND recording:police~ AND artistname:Radiohead~` | 281 |
| `release-eagles-strict.json` | `release:"Hotle California" AND artistname:"Eagles"` | 0 |
| `release-eagles-old.json` | `release:Hotle California~ AND artistname:Eagles~` | 88 |
| `release-eagles-words.json` | `release:hotle~ AND release:california~ AND artistname:Eagles~` | 84 |
| `recording-polise-strict.json` | `recording:"Karma Polise" AND artistname:"Radiohead"` | 0 |
| `recording-polise-old.json` | `recording:Karma Polise~ AND artistname:Radiohead~` | 386 |
| `recording-polise-words.json` | `recording:karma~ AND recording:polise~ AND artistname:Radiohead~` | 281 |
| `release-polise-strict.json` | `release:"Karma Polise" AND artistname:"Radiohead"` | 0 |
| `release-polise-old.json` | `release:Karma Polise~ AND artistname:Radiohead~` | 15 |
| `release-polise-words.json` | `release:karma~ AND release:polise~ AND artistname:Radiohead~` | 9 |

## Canonical strict misses

Captured under the same protocol before implementation. These provide the filtered strict pool in the track suggestion ladder.

| File | Decoded query | Hits |
|---|---|---:|
| `recording-kiss-canonical.json` | `recording:"Rokc AND Roll" AND artistname:"Kiss" AND -comment:*` | 0 |
| `recording-halsey-canonical.json` | `recording:"Now OR Nevr" AND artistname:"Halsey" AND -comment:*` | 0 |
| `recording-eminem-canonical.json` | `recording:"NOT Afriad" AND artistname:"Eminem" AND -comment:*` | 0 |
| `recording-negative-canonical.json` | `recording:"Karma Radiohead" AND artistname:"Radiohead" AND -comment:*` | 0 |
| `recording-radiohead-canonical.json` | `recording:"Krama Police" AND artistname:"Radiohead" AND -comment:*` | 0 |
| `recording-polise-canonical.json` | `recording:"Karma Polise" AND artistname:"Radiohead" AND -comment:*` | 0 |
