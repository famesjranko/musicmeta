# MusicBrainz title-only fuzzy search

## Provenance

Captured before the query-builder change on 2026-11-07. Each JSON file is the complete,
unmodified HTTP 200 response (limit=3) from the URL below. No fields or rows were edited.
User-Agent: `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`,
the value the demo web client sends. Requests were sequential, with at least 1.1 seconds
between each response and the next request. HTTP 503 attempts were retried at most three times;
only successful responses are fixtures.

- `recording-karma-polise-original.json` — 2026-11-07T15:49:52.208675+00:00, count=7216213
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma+Polise~+AND+artistname%3A~&fmt=json&limit=3`
- `recording-karma-polise-drop-artist.json` — 2026-11-07T15:48:25.482846+00:00, count=74629
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma+Polise~&fmt=json&limit=3`
- `recording-karma-polise-and-terms.json` — 2026-11-07T15:48:29.707494+00:00, count=412
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolise~&fmt=json&limit=3`
- `recording-karma-polise-quoted.json` — 2026-11-07T15:49:57.818583+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Karma+Polise%22&fmt=json&limit=3`
- `recording-paranojd-and-terms.json` — 2026-11-07T15:48:42.613642+00:00, count=8048
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AParanojd~&fmt=json&limit=3`
- `recording-karma-police-and-terms.json` — 2026-11-07T15:48:52.634084+00:00, count=413
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolice~&fmt=json&limit=3`
- `release-karma-polise-original.json` — 2026-11-07T15:48:57.936838+00:00, count=552522
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma+Polise~+AND+artistname%3A~&fmt=json&limit=3`
- `release-karma-polise-drop-artist.json` — 2026-11-07T15:49:00.635931+00:00, count=43933
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma+Polise~&fmt=json&limit=3`
- `release-karma-polise-and-terms.json` — 2026-11-07T15:50:06.852298+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolise~&fmt=json&limit=3`
- `release-karma-polise-quoted.json` — 2026-11-07T15:49:05.837482+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22Karma+Polise%22&fmt=json&limit=3`
- `release-paranojd-and-terms.json` — 2026-11-07T15:49:14.777665+00:00, count=1158
  - `https://musicbrainz.org/ws/2/release?query=release%3AParanojd~&fmt=json&limit=3`
- `release-karma-police-and-terms.json` — 2026-11-07T15:49:24.926242+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolice~&fmt=json&limit=3`
- `recording-karma-polise-with-artist.json` — 2026-11-07T15:50:30.132567+00:00, count=386
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma+Polise~+AND+artistname%3ARadiohead~&fmt=json&limit=3`
- `release-karma-polise-with-artist.json` — 2026-11-07T15:50:38.293405+00:00, count=15
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma+Polise~+AND+artistname%3ARadiohead~&fmt=json&limit=3`

## Scope

The original and artist-term-only removal captures demonstrate unrelated results. The
initial AND-per-title-term captures demonstrate the first query change only; they do not
cover short or punctuated words. The revision below records that gap and its fix.
These pools prove parsing and the query contract offline. No scheduled live check watches them;
index contents, total counts and ordering can change. They do not establish recording identity
without an artist, recall past the first three hits, or behaviour for every language or title.

## Query comparison

## Probe arm: original

- `recording:Karma Polise~ AND artistname:~`: count=7216213; top titles: Polise II; Polise III; Polise I
- `recording:Paranojd~ AND artistname:~`: count=7211021; top titles: Tux & Tobacco; Espresso & Echoes; Smoke & Silence
- `recording:Karma Police~ AND artistname:~`: count=7230198; top titles: Karma Police; Karma Police; Police Police Police Police Police
- `release:Karma Polise~ AND artistname:~`: count=552522; top titles: Polise; Hatar du polisen lite mindre nu när du är vuxen?; Polisector
- `release:Paranojd~ AND artistname:~`: count=551399; top titles: Black Cats & Deep Bass Groove Jazz Lounge; Smoky Black Cats & Deep Bass Groove Night; Black Cats & Deep Bass Purrfect Groove Night
- `release:Karma Police~ AND artistname:~`: count=554213; top titles: Karma Police; Karma Police; Karma Police

Same six endpoint/title pairs, limit=3, same User-Agent and pacing as the captures.
Measured total hit count and the first three titles, not all hits, identity, latency or general
multilingual recall. The original and artist-removal arms return unrelated titles. The AND arm
returns related titles on all six pairs. Quoting returns no typo rescue; it can still match
longer titles containing the exact phrase.

## Probe arm: drop-artist

- `recording:Karma Polise~`: count=74629; top titles: Kängpolisen; Skolpolisen; Necropolises
- `recording:Paranojd~`: count=8048; top titles: Paranoid, Paranoia; Paranoid, Paranoia; Paranoid (Prelude to Paranoia)
- `recording:Karma Police~`: count=50182; top titles: Chopper (thisisnotanantipolicesongthisisanantipolicehelicoptersong); Policenauts - Policerats; 1.5_2-hackthepolice.mp3
- `release:Karma Polise~`: count=43933; top titles: Boråspolisen Blues; Boråspolisen Blues; Dalapolisens spelmän
- `release:Paranojd~`: count=1158; top titles: Paranoid; Paranoid; Paranoid
- `release:Karma Police~`: count=10077; top titles: Mr.Policeman : いちばんいい時; Karma Police; Karma Police

Same six endpoint/title pairs, limit=3, same User-Agent and pacing as the captures.
Measured total hit count and the first three titles, not all hits, identity, latency or general
multilingual recall. The original and artist-removal arms return unrelated titles. The AND arm
returns related titles on all six pairs. Quoting returns no typo rescue; it can still match
longer titles containing the exact phrase.

## Probe arm: and-terms

- `recording:Karma~ AND recording:Polise~`: count=412; top titles: Karma Police; Karma Police; Karma Police
- `recording:Paranojd~`: count=8048; top titles: Paranoid, Paranoia; Paranoid, Paranoia; Paranoid (Prelude to Paranoia)
- `recording:Karma~ AND recording:Police~`: count=413; top titles: Karma Police; Karma Police; Karma Police
- `release:Karma~ AND release:Polise~`: count=22; top titles: Karma Police; Karma Police; Karma Police
- `release:Paranojd~`: count=1158; top titles: Paranoid; Paranoid; Paranoid
- `release:Karma~ AND release:Police~`: count=22; top titles: Karma Police; Karma Police; Karma Police

Same six endpoint/title pairs, limit=3, same User-Agent and pacing as the captures.
Measured total hit count and the first three titles, not all hits, identity, latency or general
multilingual recall. The original and artist-removal arms return unrelated titles. The AND arm
returns related titles on all six pairs. Quoting returns no typo rescue; it can still match
longer titles containing the exact phrase.

## Probe arm: quoted

- `recording:"Karma Polise"`: count=0; top titles: 
- `recording:"Paranojd"`: count=0; top titles: 
- `recording:"Karma Police"`: count=411; top titles: Karma Police; Karma Police; Karma Police
- `release:"Karma Polise"`: count=0; top titles: 
- `release:"Paranojd"`: count=0; top titles: 
- `release:"Karma Police"`: count=22; top titles: Karma Police; Karma Police; Karma Police

Same six endpoint/title pairs, limit=3, same User-Agent and pacing as the captures.
Measured total hit count and the first three titles, not all hits, identity, latency or general
multilingual recall. The original and artist-removal arms return unrelated titles. The AND arm
returns related titles on all six pairs. Quoting returns no typo rescue; it can still match
longer titles containing the exact phrase.

## Revision: short words and punctuation

Captured before the follow-up code change on 2026-11-07, with the same User-Agent,
limit=3, sequential pacing and retry bound as above. These are complete unmodified
HTTP 200 responses; every URL and timestamp is recorded below.

- `recording-revision-karma-polise-anchored.json` — 2026-11-07T17:04:30.362061+00:00, count=412
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolise~+AND+%28recording%3AKarma+OR+recording%3APolise%29&fmt=json&limit=3`
- `recording-revision-karma-police-anchored.json` — 2026-11-07T17:04:34.103293+00:00, count=413
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolice~+AND+%28recording%3AKarma+OR+recording%3APolice%29&fmt=json&limit=3`
- `recording-revision-short-words.json` — 2026-11-07T17:04:55.646441+00:00, count=402
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ADon%27t~+AND+recording%3AStpo~+AND+recording%3AMe+AND+recording%3ANow+AND+%28recording%3ADon%27t+OR+recording%3AStpo%29&fmt=json&limit=3`
- `recording-revision-joined-words.json` — 2026-11-07T17:04:38.716542+00:00, count=4107
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ALove~+AND+recording%3AHate~+AND+%28recording%3ALove+OR+recording%3AHate%29&fmt=json&limit=3`
- `recording-revision-overlapping-words.json` — 2026-11-07T17:04:48.423572+00:00, count=23821
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ARokc~+AND+recording%3ARoll~+AND+%28recording%3ARokc+OR+recording%3ARoll%29&fmt=json&limit=3`
- `release-revision-karma-polise-anchored.json` — 2026-11-07T17:05:03.290519+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolise~+AND+%28release%3AKarma+OR+release%3APolise%29&fmt=json&limit=3`
- `release-revision-karma-police-anchored.json` — 2026-11-07T17:05:08.100717+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolice~+AND+%28release%3AKarma+OR+release%3APolice%29&fmt=json&limit=3`
- `release-revision-short-words.json` — 2026-11-07T17:05:31.352150+00:00, count=36
  - `https://musicbrainz.org/ws/2/release?query=release%3ADon%27t~+AND+release%3AStpo~+AND+release%3AMe+AND+release%3ANow+AND+%28release%3ADon%27t+OR+release%3AStpo%29&fmt=json&limit=3`
- `release-revision-joined-words.json` — 2026-11-07T17:05:11.828802+00:00, count=852
  - `https://musicbrainz.org/ws/2/release?query=release%3ALove~+AND+release%3AHate~+AND+%28release%3ALove+OR+release%3AHate%29&fmt=json&limit=3`
- `release-revision-overlapping-words.json` — 2026-11-07T17:05:16.742071+00:00, count=5419
  - `https://musicbrainz.org/ws/2/release?query=release%3ARokc~+AND+release%3ARoll~+AND+%28release%3ARokc+OR+release%3ARoll%29&fmt=json&limit=3`
- `recording-revision-satisfaction-words.json` — 2026-11-07T17:05:39.420926+00:00, count=1123
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AI+AND+recording%3ACan%27t~+AND+recording%3AGet+AND+recording%3ANo+AND+recording%3ASatisfacton~+AND+%28recording%3ACan%27t+OR+recording%3ASatisfacton%29&fmt=json&limit=3`
- `recording-revision-satisfaction-old.json` — 2026-11-07T17:06:02.628775+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%5C%28I~+AND+recording%3ACan%27t~+AND+recording%3AGet~+AND+recording%3ANo%5C%29~+AND+recording%3ASatisfacton~&fmt=json&limit=3`
- `release-revision-rumours-words.json` — 2026-11-07T17:05:59.787886+00:00, count=5
  - `https://musicbrainz.org/ws/2/release?query=release%3ARumours~+AND+release%3ADeluxe~+AND+%28release%3ARumours+OR+release%3ADeluxe%29&fmt=json&limit=3`
- `release-revision-rumours-old.json` — 2026-11-07T17:03:39.382561+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3ARumours~+AND+release%3A%5C-~+AND+release%3ADeluxe~&fmt=json&limit=3`
- `recording-revision-short-cjk-old.json` — 2026-11-07T17:00:13.724855+00:00, count=5743
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%E6%9D%B1%E5%87%89~&fmt=json&limit=3`
- `recording-revision-short-cjk-strict.json` — 2026-11-07T17:00:17.139146+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22%E6%9D%B1%E5%87%89%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `recording-revision-symbols-old.json` — 2026-11-07T17:00:19.595211+00:00, count=498
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AF%E2%99%AF~+AND+recording%3AB%E2%99%AF~+AND+recording%3A%E2%88%9E~&fmt=json&limit=3`
- `recording-revision-symbols-strict.json` — 2026-11-07T17:00:22.495586+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22F%E2%99%AF+B%E2%99%AF+%E2%88%9E%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `recording-revision-three-letter-old.json` — 2026-11-07T17:00:32.457494+00:00, count=56690
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AHte~&fmt=json&limit=3`
- `release-revision-short-cjk-old.json` — 2026-11-07T17:01:22.658501+00:00, count=809
  - `https://musicbrainz.org/ws/2/release?query=release%3A%E6%9D%B1%E5%87%89~&fmt=json&limit=3`
- `release-revision-short-cjk-strict.json` — 2026-11-07T17:01:25.187489+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22%E6%9D%B1%E5%87%89%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `release-revision-symbols-old.json` — 2026-11-07T17:01:31.806159+00:00, count=172
  - `https://musicbrainz.org/ws/2/release?query=release%3AF%E2%99%AF~+AND+release%3AB%E2%99%AF~+AND+release%3A%E2%88%9E~&fmt=json&limit=3`
- `release-revision-symbols-strict.json` — 2026-11-07T17:01:37.792662+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22F%E2%99%AF+B%E2%99%AF+%E2%88%9E%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `release-revision-three-letter-old.json` — 2026-11-07T17:01:44.442672+00:00, count=8826
  - `https://musicbrainz.org/ws/2/release?query=release%3AHte~&fmt=json&limit=3`

### Probe results

Each row is one live query, not an inference from a locally tokenized input. Hyphens and
slashes map to the same word query, as do curly and straight apostrophes after normalization.
`~1` labels describe the submitted syntax; their counts do not establish that the server
honours a one-edit budget. Only the first three hits were inspected. No identity, complete
pool relevance, latency or general recall was measured.

| Arm | Query | Count | Top titles (score) |
|---|---|---:|---|
| 東凉 old | `recording:東凉~` | 5743 | SPECIAL BONUS TRACK- 本気!! <OFF VOCAL VERSION> (0); Beleza ! Beleza !! Beleza !!! (0); かっとばせ男!!パット咲け男!! (0) |
| 東凉 strict | `recording:"東凉" AND artistname:""` | 0 | ∅ |
| F♯ B♯ ∞ old | `recording:F♯~ AND recording:B♯~ AND recording:∞~` | 498 | B3 - the memory be like A1 C3 E2 F4 F5 F8 (AcHe MiX) (100); B - Rain Be Fo Rain Bo (83); Be Fo' I Bounce (79) |
| F♯ B♯ ∞ strict | `recording:"F♯ B♯ ∞" AND artistname:""` | 0 | ∅ |
| short exact | `recording:東凉` | 8429 | 凉凉 (100); 凉凉 (100); 凉凉 (100) |
| Hte 3+ | `recording:Hte~` | 56690 | Hoe komt het (100); Hoe het is (100); Hoe het was (100) |
| Hte ~1 form | `recording:Hte~1` | 56690 | Hoe het ging (100); Het hete eten (100); Hoe Is Het? (100) |
| The 3+ | `recording:The~` | 4006073 | Khe Mhe Nhe (100); The 5the Life (98); The She (90) |
| The ~1 form | `recording:The~1` | 4006075 | The 5the Life (100); Khe Mhe Nhe (99); The She (92) |
| Love-Hate words | `recording:Love~ AND recording:Hate~` | 4440 | Love Hater (Hate for Love) (100); Love Hate Fear Fate (99); Love Hate Love Hate (93) |
| Love-Htae words | `recording:Love~ AND recording:Htae~` | 3797 | Love Hate Love Hate (100); Hate Love Hate (96); Hate Love Hate (96) |
| Rokc Roll bare ~ form | `recording:Rokc~ AND recording:Roll~` | 24073 | Rolc (100); Rock, Roll, Roll (84); Mr Rock And Roll Role (81) |
| Rokc Roll ~1 form | `recording:Rokc~1 AND recording:Roll~1` | 24071 | Rolc (100); Rock, Roll, Roll (83); Mr Rock And Roll Role (81) |
| Rokc Roll ~1 form anchor | `recording:Rokc~1 AND recording:Roll~1 AND (recording:Rokc OR recording:Roll)` | 23820 | Rock, Roll, Roll (100); Roll Rock 'n' Roll (91); Roll Rock 'n' Roll (91) |
| Rock Roll bare ~ form | `recording:Rock~ AND recording:Roll~` | 24377 | Rock Rockn Roll (100); Hula Rock (Rock, Rock'n' Roll) (92); Rocka-Rolla Rock (90) |
| Rock Roll ~1 form | `recording:Rock~1 AND recording:Roll~1` | 24377 | Rock Rockn Roll (100); Hula Rock (Rock, Rock'n' Roll) (92); Rocka-Rolla Rock (90) |
| Rock Roll ~1 form anchor | `recording:Rock~1 AND recording:Roll~1 AND (recording:Rock OR recording:Roll)` | 24309 | Rock, Roll, Roll (100); Rock Rockn Roll (99); Rock Roll (96) |
| 東凉 old | `release:東凉~` | 809 | Pin-Occhio vai !! (0); Vous êtes ici !! (0); Terrora !! (0) |
| 東凉 strict | `release:"東凉" AND artistname:""` | 0 | ∅ |
| F♯ B♯ ∞ old | `release:F♯~ AND release:B♯~ AND release:∞~` | 172 | BV I Fk E (100); B.G. In Hi Fi, Part 3 (81); B.G. In HI-FI, Part 4 (81) |
| F♯ B♯ ∞ strict | `release:"F♯ B♯ ∞" AND artistname:""` | 0 | ∅ |
| short exact | `release:東凉` | 2782 | 凉凉 (100); 涼 (90); 凉 (90) |
| Hte 3+ | `release:Hte~` | 8826 | Hoe het loopt (100); Hoe het is (100); Hoe het danst (100) |
| Hte ~1 form | `release:Hte~1` | 8826 | Hoe het loopt (100); Hoe het is (100); Hoe het danst (100) |
| The 3+ | `release:The~` | 786085 | TDE Presents: The Tae Beast Tape (100); She's the Woman (98); tae the clouds (98) |
| The ~1 form | `release:The~1` | 786085 | TDE Presents: The Tae Beast Tape (100); She's the Woman (97); tae the clouds (97) |
| Love-Hate words | `release:Love~ AND release:Hate~` | 958 | Love Hate Fate (100); Love Hate Fear Fate (89); Love Hate Live (86) |
| Love-Htae words | `release:Love~ AND release:Htae~` | 797 | Love Hate Live (100); Love Hate Love Hate (94); LOVE/HATE (86) |
| Rokc Roll bare ~ form | `release:Rokc~ AND release:Roll~` | 5460 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rokc Roll ~1 form | `release:Rokc~1 AND release:Roll~1` | 5460 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rokc Roll ~1 form anchor | `release:Rokc~1 AND release:Roll~1 AND (release:Rokc OR release:Roll)` | 5419 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rock Roll bare ~ form | `release:Rock~ AND release:Roll~` | 5534 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rock Roll ~1 form | `release:Rock~1 AND release:Roll~1` | 5534 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rock Roll ~1 form anchor | `release:Rock~1 AND release:Roll~1 AND (release:Rock OR release:Roll)` | 5490 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| (I Can't Get No) Satisfacton 3+ | `recording:I AND recording:Can't~ AND recording:Get~ AND recording:No AND recording:Satisfacton~` | 1128 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (99); (I Can’t Get No) Satisafction (97) |
| (I Can't Get No) Satisfacton 4+ | `recording:I AND recording:Can't~ AND recording:Get AND recording:No AND recording:Satisfacton~` | 1128 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (99); (I Can’t Get No) Satisafction (97) |
| (I Can't Get No) Satisfaction 3+ | `recording:I AND recording:Can't~ AND recording:Get~ AND recording:No AND recording:Satisfaction~` | 1130 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (98); (I Can’t Get No) Satisafction (96) |
| (I Can't Get No) Satisfaction 4+ | `recording:I AND recording:Can't~ AND recording:Get AND recording:No AND recording:Satisfaction~` | 1130 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (98); (I Can’t Get No) Satisafction (96) |
| (I Can’t Get No) Satisfacton 3+ | `recording:I AND recording:Can’t~ AND recording:Get~ AND recording:No AND recording:Satisfacton~` | 1128 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (99); (I Can’t Get No) Satisafction (96) |
| (I Can’t Get No) Satisfacton 4+ | `recording:I AND recording:Can’t~ AND recording:Get AND recording:No AND recording:Satisfacton~` | 1128 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (99); (I Can’t Get No) Satisafction (96) |
| (I Can’t Get No) Satisfaction 3+ | `recording:I AND recording:Can’t~ AND recording:Get~ AND recording:No AND recording:Satisfaction~` | 1130 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (98); (I Can’t Get No) Satisafction (96) |
| (I Can’t Get No) Satisfaction 4+ | `recording:I AND recording:Can’t~ AND recording:Get AND recording:No AND recording:Satisfaction~` | 1130 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (98); (I Can’t Get No) Satisafction (96) |
| Sweet Chlid o' Mine 3+ | `recording:Sweet~ AND recording:Chlid~ AND recording:o AND recording:Mine~` | 514 | Sweet Child o’ Mine (100); Sweet Child o' Mine (100); Sweet Child o' Mine (100) |
| Sweet Child o' Mine 3+ | `recording:Sweet~ AND recording:Child~ AND recording:o AND recording:Mine~` | 516 | Sweet Child o' Mine (100); Sweet Child O' Mine (100); Sweet Child o Mine (100) |
| Don't Stpo Me Now 3+ | `recording:Don't~ AND recording:Stpo~ AND recording:Me AND recording:Now~` | 520 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don't Stop Me Now (100) |
| Don't Stpo Me Now 4+ | `recording:Don't~ AND recording:Stpo~ AND recording:Me AND recording:Now` | 509 | Don’t Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |
| Don't Stop Me Now 3+ | `recording:Don't~ AND recording:Stop~ AND recording:Me AND recording:Now~` | 521 | Don’t Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |
| Don't Stop Me Now 4+ | `recording:Don't~ AND recording:Stop~ AND recording:Me AND recording:Now` | 510 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don't Stop Me Now (100) |
| Rumours - Delxue words | `release:Rumours~ AND release:Delxue~` | 6 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| Rumours - Delxue old | `release:Rumours~ AND release:\-~ AND release:Delxue~` | 0 | ∅ |
| Rumours - Deluxe words | `release:Rumours~ AND release:Deluxe~` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| Rumours - Deluxe old | `release:Rumours~ AND release:\-~ AND release:Deluxe~` | 0 | ∅ |
| バトルロイヤルズ | `recording:バトルロイヤルズ~` | 15 | バトルロイヤル (100); バトルロイヤル (99); バトルロイヤル (99) |
| バトルロイヤル | `recording:バトルロイヤル~` | 16 | バトルロイヤル (100); バトルロイヤル (99); バトルロイヤル (99) |
| Поиск | `recording:Поиск~` | 1051 | поиск (100); Поиск (100); Поиск (100) |
| Поикс | `recording:Поикс~` | 1178 | Поиск (100); поиск (100); Поиск (100) |
| 千本桜凉 | `recording:千本桜凉~` | 0 | ∅ |
| 千本桜 | `recording:千本桜~` | 16460 | 1000本ノック (0); 桜モダン (0); 千の夜を越えて (0) |
| Karma Polise chosen | `recording:Karma~ AND recording:Polise~ AND (recording:Karma OR recording:Polise)` | 412 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Karma Police chosen | `recording:Karma~ AND recording:Police~ AND (recording:Karma OR recording:Police)` | 413 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Love-Hate chosen | `recording:Love~ AND recording:Hate~ AND (recording:Love OR recording:Hate)` | 4107 | Love Hate Love Hate (100); Hate Love Hate (98); Hate Love Hate (98) |
| Love-Htae chosen | `recording:Love~ AND recording:Htae~ AND (recording:Love OR recording:Htae)` | 3499 | Love Hate Love Hate (100); Love, Hate, Love (97); Love, Hate, Love (97) |
| Rokc Roll chosen | `recording:Rokc~ AND recording:Roll~ AND (recording:Rokc OR recording:Roll)` | 23821 | Rock, Roll, Roll (100); Roll Rock 'n' Roll (91); Roll Rock 'n' Roll (91) |
| Rock Roll chosen | `recording:Rock~ AND recording:Roll~ AND (recording:Rock OR recording:Roll)` | 24309 | Rock, Roll, Roll (100); Rock Rockn Roll (99); Rock/Roll (96) |
| Don't Stpo Me Now chosen | `recording:Don't~ AND recording:Stpo~ AND recording:Me AND recording:Now AND (recording:Don't OR recording:Stpo)` | 402 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don't Stop Me Now (100) |
| Don't Stop Me Now chosen | `recording:Don't~ AND recording:Stop~ AND recording:Me AND recording:Now AND (recording:Don't OR recording:Stop)` | 510 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |
| Karma Polise chosen | `release:Karma~ AND release:Polise~ AND (release:Karma OR release:Polise)` | 22 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Karma Police chosen | `release:Karma~ AND release:Police~ AND (release:Karma OR release:Police)` | 22 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Love-Hate chosen | `release:Love~ AND release:Hate~ AND (release:Love OR release:Hate)` | 852 | Love Hate Love Hate (100); Love Hate Fate (99); LOVE/HATE (91) |
| Love-Htae chosen | `release:Love~ AND release:Htae~ AND (release:Love OR release:Htae)` | 762 | Love Hate Love Hate (100); Love Hate Live (98); LOVE/HATE (91) |
| Rokc Roll chosen | `release:Rokc~ AND release:Roll~ AND (release:Rokc OR release:Roll)` | 5419 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rock Roll chosen | `release:Rock~ AND release:Roll~ AND (release:Rock OR release:Roll)` | 5490 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Don't Stpo Me Now chosen | `release:Don't~ AND release:Stpo~ AND release:Me AND release:Now AND (release:Don't OR release:Stpo)` | 36 | Don't Stop Me Now (100); Don't stop me NOW (100); Don't Stop Me Now (100) |
| Don't Stop Me Now chosen | `release:Don't~ AND release:Stop~ AND release:Me AND release:Now AND (release:Don't OR release:Stop)` | 46 | Don't Stop Me Now (100); Don't stop me NOW (100); Don't Stop Me Now (100) |
| (I Can't Get No) Satisfacton chosen | `recording:I AND recording:Can't~ AND recording:Get AND recording:No AND recording:Satisfacton~ AND (recording:Can't OR recording:Satisfacton)` | 1123 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisafction (98); (I Can't Get No) Satisfaction (97) |
| (I Can't Get No) Satisfaction chosen | `recording:I AND recording:Can't~ AND recording:Get AND recording:No AND recording:Satisfaction~ AND (recording:Can't OR recording:Satisfaction)` | 1130 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can't Get No) Satisfaction (91); (I Can’t Get No) Satisfaction (91) |
| Sweet Chlid o' Mine chosen | `recording:Sweet~ AND recording:Chlid~ AND recording:o AND recording:Mine~ AND (recording:Sweet OR recording:Chlid OR recording:Mine)` | 514 | Sweet Child o’ Mine (100); Sweet Child o' Mine (100); Sweet Child o' Mine (100) |
| Sweet Child o' Mine chosen | `recording:Sweet~ AND recording:Child~ AND recording:o AND recording:Mine~ AND (recording:Sweet OR recording:Child OR recording:Mine)` | 516 | Sweet Child o’ Mine (100); Sweet Child o' Mine (100); Sweet Child o' Mine (100) |
| Rumours - Delxue chosen | `release:Rumours~ AND release:Delxue~ AND (release:Rumours OR release:Delxue)` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| Rumours - Deluxe chosen | `release:Rumours~ AND release:Deluxe~ AND (release:Rumours OR release:Deluxe)` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| (I Can't Get No) Satisfacton old | `recording:\(I~ AND recording:Can't~ AND recording:Get~ AND recording:No\)~ AND recording:Satisfacton~` | 0 | ∅ |
| Don't Stpo Me Now old | `recording:Don't~ AND recording:Stpo~ AND recording:Me~ AND recording:Now~` | 532 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |

### Decision and recall cost

Extract Unicode letter/digit runs, retaining internal apostrophes and normalizing `’` to
ASCII apostrophe. Words shorter than four Unicode code points are exact query terms.
Longer words take `~`. Require every word, and when several distinct long words exist,
require at least one of those long words exactly as an additional OR clause. This removes
`Rolc` from the displayed `Rokc Roll` pool without client-side filtering.

Three-character fuzzy terms were rejected: `Hte` and even correctly spelled `千本桜`
returned unrelated rows. Titles containing no four-character word, including `U2`, `AC/DC`,
`Hte` and `東凉`, receive no fuzzy request. When several long words are all misspelled,
the exact-word requirement can also lose the intended title. An empty list is preferable
to those demonstrated false suggestions. Search-index matching remains approximate; the
measurements do not promise semantic relevance for every title or every hit.
