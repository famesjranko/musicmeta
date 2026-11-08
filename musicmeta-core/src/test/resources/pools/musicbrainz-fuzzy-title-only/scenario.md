# MusicBrainz title-only fuzzy search

## Provenance

Captured on 2026-11-07. Each JSON file is the complete,
unmodified HTTP 200 response (limit=3) from the URL below. No fields or rows were edited.
User-Agent: `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`,
the value the demo web client sends. Requests were sequential, with at least 1.1 seconds
between each response and the next request. HTTP 503 attempts were retried at most three times;
only successful responses are fixtures.

- `recording-karma-polise-empty-artist.json` — 2026-11-07T15:49:52.208675+00:00, count=7216213
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma+Polise~+AND+artistname%3A~&fmt=json&limit=3`
- `recording-karma-polise-unfielded-word.json` — 2026-11-07T15:48:25.482846+00:00, count=74629
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma+Polise~&fmt=json&limit=3`
- `recording-karma-polise-title-case.json` — 2026-11-07T15:48:29.707494+00:00, count=412
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolise~&fmt=json&limit=3`
- `recording-karma-polise-quoted.json` — 2026-11-07T15:49:57.818583+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Karma+Polise%22&fmt=json&limit=3`
- `recording-paranojd-title-case.json` — 2026-11-07T15:48:42.613642+00:00, count=8048
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AParanojd~&fmt=json&limit=3`
- `recording-karma-police-title-case.json` — 2026-11-07T15:48:52.634084+00:00, count=413
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolice~&fmt=json&limit=3`
- `release-karma-polise-empty-artist.json` — 2026-11-07T15:48:57.936838+00:00, count=552522
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma+Polise~+AND+artistname%3A~&fmt=json&limit=3`
- `release-karma-polise-unfielded-word.json` — 2026-11-07T15:49:00.635931+00:00, count=43933
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma+Polise~&fmt=json&limit=3`
- `release-karma-polise-title-case.json` — 2026-11-07T15:50:06.852298+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolise~&fmt=json&limit=3`
- `release-karma-polise-quoted.json` — 2026-11-07T15:49:05.837482+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22Karma+Polise%22&fmt=json&limit=3`
- `release-paranojd-title-case.json` — 2026-11-07T15:49:14.777665+00:00, count=1158
  - `https://musicbrainz.org/ws/2/release?query=release%3AParanojd~&fmt=json&limit=3`
- `release-karma-police-title-case.json` — 2026-11-07T15:49:24.926242+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolice~&fmt=json&limit=3`
- `recording-karma-polise-with-artist.json` — 2026-11-07T15:50:30.132567+00:00, count=386
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma+Polise~+AND+artistname%3ARadiohead~&fmt=json&limit=3`
- `release-karma-polise-with-artist.json` — 2026-11-07T15:50:38.293405+00:00, count=15
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma+Polise~+AND+artistname%3ARadiohead~&fmt=json&limit=3`

## Scope

The comparisons cover field scoping, empty artist terms, short words, punctuation,
exact-word constraints, case and combining marks.
These pools prove parsing and the query contract offline. No scheduled live check watches them;
index contents, total counts and ordering can change. They do not establish recording identity
without an artist, recall past the first three hits, or behaviour for every language or title.

## Query comparison

All four arms use the same six endpoint/title pairs, limit=3, User-Agent and pacing.
The captures measure total hit count and the first three titles, not all hits, identity,
latency or general multilingual recall. The empty-artist and unfielded-word queries return
unrelated titles. The AND-joined title terms return related titles on these six pairs.
Quoted phrases do not correct a typo and can match longer titles containing the phrase.

## Probe arm: with an empty artist term

- `recording:Karma Polise~ AND artistname:~`: count=7216213; top titles: Polise II; Polise III; Polise I
- `recording:Paranojd~ AND artistname:~`: count=7211021; top titles: Tux & Tobacco; Espresso & Echoes; Smoke & Silence
- `recording:Karma Police~ AND artistname:~`: count=7230198; top titles: Karma Police; Karma Police; Police Police Police Police Police
- `release:Karma Polise~ AND artistname:~`: count=552522; top titles: Polise; Hatar du polisen lite mindre nu när du är vuxen?; Polisector
- `release:Paranojd~ AND artistname:~`: count=551399; top titles: Black Cats & Deep Bass Groove Jazz Lounge; Smoky Black Cats & Deep Bass Groove Night; Black Cats & Deep Bass Purrfect Groove Night
- `release:Karma Police~ AND artistname:~`: count=554213; top titles: Karma Police; Karma Police; Karma Police

## Probe arm: without an artist term

- `recording:Karma Polise~`: count=74629; top titles: Kängpolisen; Skolpolisen; Necropolises
- `recording:Paranojd~`: count=8048; top titles: Paranoid, Paranoia; Paranoid, Paranoia; Paranoid (Prelude to Paranoia)
- `recording:Karma Police~`: count=50182; top titles: Chopper (thisisnotanantipolicesongthisisanantipolicehelicoptersong); Policenauts - Policerats; 1.5_2-hackthepolice.mp3
- `release:Karma Polise~`: count=43933; top titles: Boråspolisen Blues; Boråspolisen Blues; Dalapolisens spelmän
- `release:Paranojd~`: count=1158; top titles: Paranoid; Paranoid; Paranoid
- `release:Karma Police~`: count=10077; top titles: Mr.Policeman : いちばんいい時; Karma Police; Karma Police

## Probe arm: AND-joined title terms

- `recording:Karma~ AND recording:Polise~`: count=412; top titles: Karma Police; Karma Police; Karma Police
- `recording:Paranojd~`: count=8048; top titles: Paranoid, Paranoia; Paranoid, Paranoia; Paranoid (Prelude to Paranoia)
- `recording:Karma~ AND recording:Police~`: count=413; top titles: Karma Police; Karma Police; Karma Police
- `release:Karma~ AND release:Polise~`: count=22; top titles: Karma Police; Karma Police; Karma Police
- `release:Paranojd~`: count=1158; top titles: Paranoid; Paranoid; Paranoid
- `release:Karma~ AND release:Police~`: count=22; top titles: Karma Police; Karma Police; Karma Police

## Probe arm: quoted phrase

- `recording:"Karma Polise"`: count=0; top titles: 
- `recording:"Paranojd"`: count=0; top titles: 
- `recording:"Karma Police"`: count=411; top titles: Karma Police; Karma Police; Karma Police
- `release:"Karma Polise"`: count=0; top titles: 
- `release:"Paranojd"`: count=0; top titles: 
- `release:"Karma Police"`: count=22; top titles: Karma Police; Karma Police; Karma Police

## Short words and punctuation

Captured on 2026-11-07, with the same User-Agent,
limit=3, sequential pacing and retry bound as above. These are complete unmodified
HTTP 200 responses; every URL and timestamp is recorded below.

- `recording-karma-polise-title-case-exact-word.json` — 2026-11-07T17:04:30.362061+00:00, count=412
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolise~+AND+%28recording%3AKarma+OR+recording%3APolise%29&fmt=json&limit=3`
- `recording-karma-police-title-case-exact-word.json` — 2026-11-07T17:04:34.103293+00:00, count=413
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AKarma~+AND+recording%3APolice~+AND+%28recording%3AKarma+OR+recording%3APolice%29&fmt=json&limit=3`
- `recording-dont-stpo-me-now-title-case-exact-word.json` — 2026-11-07T17:04:55.646441+00:00, count=402
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ADon%27t~+AND+recording%3AStpo~+AND+recording%3AMe+AND+recording%3ANow+AND+%28recording%3ADon%27t+OR+recording%3AStpo%29&fmt=json&limit=3`
- `recording-love-hate-title-case-exact-word.json` — 2026-11-07T17:04:38.716542+00:00, count=4107
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ALove~+AND+recording%3AHate~+AND+%28recording%3ALove+OR+recording%3AHate%29&fmt=json&limit=3`
- `recording-rokc-roll-title-case-exact-word.json` — 2026-11-07T17:04:48.423572+00:00, count=23821
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ARokc~+AND+recording%3ARoll~+AND+%28recording%3ARokc+OR+recording%3ARoll%29&fmt=json&limit=3`
- `release-karma-polise-title-case-exact-word.json` — 2026-11-07T17:05:03.290519+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolise~+AND+%28release%3AKarma+OR+release%3APolise%29&fmt=json&limit=3`
- `release-karma-police-title-case-exact-word.json` — 2026-11-07T17:05:08.100717+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3AKarma~+AND+release%3APolice~+AND+%28release%3AKarma+OR+release%3APolice%29&fmt=json&limit=3`
- `release-dont-stpo-me-now-title-case-exact-word.json` — 2026-11-07T17:05:31.352150+00:00, count=36
  - `https://musicbrainz.org/ws/2/release?query=release%3ADon%27t~+AND+release%3AStpo~+AND+release%3AMe+AND+release%3ANow+AND+%28release%3ADon%27t+OR+release%3AStpo%29&fmt=json&limit=3`
- `release-love-hate-title-case-exact-word.json` — 2026-11-07T17:05:11.828802+00:00, count=852
  - `https://musicbrainz.org/ws/2/release?query=release%3ALove~+AND+release%3AHate~+AND+%28release%3ALove+OR+release%3AHate%29&fmt=json&limit=3`
- `release-rokc-roll-title-case-exact-word.json` — 2026-11-07T17:05:16.742071+00:00, count=5419
  - `https://musicbrainz.org/ws/2/release?query=release%3ARokc~+AND+release%3ARoll~+AND+%28release%3ARokc+OR+release%3ARoll%29&fmt=json&limit=3`
- `recording-satisfaction-title-case-exact-word.json` — 2026-11-07T17:05:39.420926+00:00, count=1123
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AI+AND+recording%3ACan%27t~+AND+recording%3AGet+AND+recording%3ANo+AND+recording%3ASatisfacton~+AND+%28recording%3ACan%27t+OR+recording%3ASatisfacton%29&fmt=json&limit=3`
- `recording-satisfaction-punctuated.json` — 2026-11-07T17:06:02.628775+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%5C%28I~+AND+recording%3ACan%27t~+AND+recording%3AGet~+AND+recording%3ANo%5C%29~+AND+recording%3ASatisfacton~&fmt=json&limit=3`
- `release-rumours-title-case-exact-word.json` — 2026-11-07T17:05:59.787886+00:00, count=5
  - `https://musicbrainz.org/ws/2/release?query=release%3ARumours~+AND+release%3ADeluxe~+AND+%28release%3ARumours+OR+release%3ADeluxe%29&fmt=json&limit=3`
- `release-rumours-punctuated.json` — 2026-11-07T17:03:39.382561+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3ARumours~+AND+release%3A%5C-~+AND+release%3ADeluxe~&fmt=json&limit=3`
- `recording-short-cjk-fuzzy.json` — 2026-11-07T16:55:27.785Z, count=5743
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%E6%9D%B1%E5%87%89~&fmt=json&limit=3`
- `recording-short-cjk-strict.json` — 2026-11-07T17:00:17.139146+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22%E6%9D%B1%E5%87%89%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `recording-symbols-fuzzy.json` — 2026-11-07T17:00:19.595211+00:00, count=498
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AF%E2%99%AF~+AND+recording%3AB%E2%99%AF~+AND+recording%3A%E2%88%9E~&fmt=json&limit=3`
- `recording-symbols-strict.json` — 2026-11-07T17:00:22.495586+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22F%E2%99%AF+B%E2%99%AF+%E2%88%9E%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `recording-hte-fuzzy.json` — 2026-11-07T17:00:32.457494+00:00, count=56690
  - `https://musicbrainz.org/ws/2/recording?query=recording%3AHte~&fmt=json&limit=3`
- `release-short-cjk-fuzzy.json` — 2026-11-07T17:01:22.658501+00:00, count=809
  - `https://musicbrainz.org/ws/2/release?query=release%3A%E6%9D%B1%E5%87%89~&fmt=json&limit=3`
- `release-short-cjk-strict.json` — 2026-11-07T17:01:25.187489+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22%E6%9D%B1%E5%87%89%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `release-symbols-fuzzy.json` — 2026-11-07T16:55:39.008Z, count=172
  - `https://musicbrainz.org/ws/2/release?query=release%3AF%E2%99%AF~+AND+release%3AB%E2%99%AF~+AND+release%3A%E2%88%9E~&fmt=json&limit=3`
- `release-symbols-strict.json` — 2026-11-07T17:01:37.792662+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22F%E2%99%AF+B%E2%99%AF+%E2%88%9E%22+AND+artistname%3A%22%22&fmt=json&limit=3`
- `release-hte-fuzzy.json` — 2026-11-07T17:01:44.442672+00:00, count=8826
  - `https://musicbrainz.org/ws/2/release?query=release%3AHte~&fmt=json&limit=3`

### Probe results

Each row is one live query, not an inference from a locally tokenized input. Hyphens and
slashes map to the same word query, as do curly and straight apostrophes after normalization.
`~1` labels describe the submitted syntax; their counts do not establish that the server
honours a one-edit budget. Only the first three hits were inspected. No identity, complete
pool relevance, latency or general recall was measured.

| Arm | Query | Count | Top titles (score) |
|---|---|---:|---|
| 東凉 all terms fuzzy | `recording:東凉~` | 5743 | SPECIAL BONUS TRACK- 本気!! <OFF VOCAL VERSION> (0); Beleza ! Beleza !! Beleza !!! (0); かっとばせ男!!パット咲け男!! (0) |
| 東凉 strict | `recording:"東凉" AND artistname:""` | 0 | ∅ |
| F♯ B♯ ∞ all terms fuzzy | `recording:F♯~ AND recording:B♯~ AND recording:∞~` | 498 | B3 - the memory be like A1 C3 E2 F4 F5 F8 (AcHe MiX) (100); B - Rain Be Fo Rain Bo (83); Be Fo' I Bounce (79) |
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
| 東凉 all terms fuzzy | `release:東凉~` | 809 | Pin-Occhio vai !! (0); Vous êtes ici !! (0); Terrora !! (0) |
| 東凉 strict | `release:"東凉" AND artistname:""` | 0 | ∅ |
| F♯ B♯ ∞ all terms fuzzy | `release:F♯~ AND release:B♯~ AND release:∞~` | 172 | BV I Fk E (100); B.G. In Hi Fi, Part 3 (81); B.G. In HI-FI, Part 4 (81) |
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
| Rumours - Delxue all terms fuzzy | `release:Rumours~ AND release:\-~ AND release:Delxue~` | 0 | ∅ |
| Rumours - Deluxe words | `release:Rumours~ AND release:Deluxe~` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| Rumours - Deluxe all terms fuzzy | `release:Rumours~ AND release:\-~ AND release:Deluxe~` | 0 | ∅ |
| バトルロイヤルズ | `recording:バトルロイヤルズ~` | 15 | バトルロイヤル (100); バトルロイヤル (99); バトルロイヤル (99) |
| バトルロイヤル | `recording:バトルロイヤル~` | 16 | バトルロイヤル (100); バトルロイヤル (99); バトルロイヤル (99) |
| Поиск | `recording:Поиск~` | 1051 | поиск (100); Поиск (100); Поиск (100) |
| Поикс | `recording:Поикс~` | 1178 | Поиск (100); поиск (100); Поиск (100) |
| 千本桜凉 | `recording:千本桜凉~` | 0 | ∅ |
| 千本桜 | `recording:千本桜~` | 16460 | 1000本ノック (0); 桜モダン (0); 千の夜を越えて (0) |
| Karma Polise with an exact-word clause | `recording:Karma~ AND recording:Polise~ AND (recording:Karma OR recording:Polise)` | 412 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Karma Police with an exact-word clause | `recording:Karma~ AND recording:Police~ AND (recording:Karma OR recording:Police)` | 413 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Love-Hate with an exact-word clause | `recording:Love~ AND recording:Hate~ AND (recording:Love OR recording:Hate)` | 4107 | Love Hate Love Hate (100); Hate Love Hate (98); Hate Love Hate (98) |
| Love-Htae with an exact-word clause | `recording:Love~ AND recording:Htae~ AND (recording:Love OR recording:Htae)` | 3499 | Love Hate Love Hate (100); Love, Hate, Love (97); Love, Hate, Love (97) |
| Rokc Roll with an exact-word clause | `recording:Rokc~ AND recording:Roll~ AND (recording:Rokc OR recording:Roll)` | 23821 | Rock, Roll, Roll (100); Roll Rock 'n' Roll (91); Roll Rock 'n' Roll (91) |
| Rock Roll with an exact-word clause | `recording:Rock~ AND recording:Roll~ AND (recording:Rock OR recording:Roll)` | 24309 | Rock, Roll, Roll (100); Rock Rockn Roll (99); Rock/Roll (96) |
| Don't Stpo Me Now with an exact-word clause | `recording:Don't~ AND recording:Stpo~ AND recording:Me AND recording:Now AND (recording:Don't OR recording:Stpo)` | 402 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don't Stop Me Now (100) |
| Don't Stop Me Now with an exact-word clause | `recording:Don't~ AND recording:Stop~ AND recording:Me AND recording:Now AND (recording:Don't OR recording:Stop)` | 510 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |
| Karma Polise with an exact-word clause | `release:Karma~ AND release:Polise~ AND (release:Karma OR release:Polise)` | 22 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Karma Police with an exact-word clause | `release:Karma~ AND release:Police~ AND (release:Karma OR release:Police)` | 22 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Love-Hate with an exact-word clause | `release:Love~ AND release:Hate~ AND (release:Love OR release:Hate)` | 852 | Love Hate Love Hate (100); Love Hate Fate (99); LOVE/HATE (91) |
| Love-Htae with an exact-word clause | `release:Love~ AND release:Htae~ AND (release:Love OR release:Htae)` | 762 | Love Hate Love Hate (100); Love Hate Live (98); LOVE/HATE (91) |
| Rokc Roll with an exact-word clause | `release:Rokc~ AND release:Roll~ AND (release:Rokc OR release:Roll)` | 5419 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Rock Roll with an exact-word clause | `release:Rock~ AND release:Roll~ AND (release:Rock OR release:Roll)` | 5490 | Rock@Roll (100); Rock and Roll (87); Rock And Roll (87) |
| Don't Stpo Me Now with an exact-word clause | `release:Don't~ AND release:Stpo~ AND release:Me AND release:Now AND (release:Don't OR release:Stpo)` | 36 | Don't Stop Me Now (100); Don't stop me NOW (100); Don't Stop Me Now (100) |
| Don't Stop Me Now with an exact-word clause | `release:Don't~ AND release:Stop~ AND release:Me AND release:Now AND (release:Don't OR release:Stop)` | 46 | Don't Stop Me Now (100); Don't stop me NOW (100); Don't Stop Me Now (100) |
| (I Can't Get No) Satisfacton with an exact-word clause | `recording:I AND recording:Can't~ AND recording:Get AND recording:No AND recording:Satisfacton~ AND (recording:Can't OR recording:Satisfacton)` | 1123 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisafction (98); (I Can't Get No) Satisfaction (97) |
| (I Can't Get No) Satisfaction with an exact-word clause | `recording:I AND recording:Can't~ AND recording:Get AND recording:No AND recording:Satisfaction~ AND (recording:Can't OR recording:Satisfaction)` | 1130 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can't Get No) Satisfaction (91); (I Can’t Get No) Satisfaction (91) |
| Sweet Chlid o' Mine with an exact-word clause | `recording:Sweet~ AND recording:Chlid~ AND recording:o AND recording:Mine~ AND (recording:Sweet OR recording:Chlid OR recording:Mine)` | 514 | Sweet Child o’ Mine (100); Sweet Child o' Mine (100); Sweet Child o' Mine (100) |
| Sweet Child o' Mine with an exact-word clause | `recording:Sweet~ AND recording:Child~ AND recording:o AND recording:Mine~ AND (recording:Sweet OR recording:Child OR recording:Mine)` | 516 | Sweet Child o’ Mine (100); Sweet Child o' Mine (100); Sweet Child o' Mine (100) |
| Rumours - Delxue with an exact-word clause | `release:Rumours~ AND release:Delxue~ AND (release:Rumours OR release:Delxue)` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| Rumours - Deluxe with an exact-word clause | `release:Rumours~ AND release:Deluxe~ AND (release:Rumours OR release:Deluxe)` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |
| (I Can't Get No) Satisfacton all terms fuzzy | `recording:\(I~ AND recording:Can't~ AND recording:Get~ AND recording:No\)~ AND recording:Satisfacton~` | 0 | ∅ |
| Don't Stpo Me Now all terms fuzzy | `recording:Don't~ AND recording:Stpo~ AND recording:Me~ AND recording:Now~` | 532 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |

### Comparison observations

The fuzzy-only and exact-word-constrained queries expose a precision/recall trade-off.
The exact-word-constrained `Rokc Roll` query is led by `Rock, Roll, Roll`; the fuzzy-only
query can be led by `Rolc`. The `Hotle Califonia` comparisons include empty
exact-word-constrained pools alongside nonempty fuzzy-only pools.

Three-character fuzzy terms such as `Hte` and `千本桜` return unrelated rows. These
measurements cover only the submitted queries and their first three hits; they do not
establish semantic relevance for every title or every hit.

## Recall, case and combining marks

Captured on 2026-11-08.
Same User-Agent as above, limit=3, sequential requests with 1.1 seconds after each response.
Each fixture is the complete unmodified HTTP 200 response. Errors were retried at most
three times; all measurements below completed successfully.

- `recording-karma-polise.json` — 2026-11-08T00:32:20.903919+00:00, count=412
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Akarma~+AND+recording%3Apolise~&fmt=json&limit=3`
- `recording-karma-police.json` — 2026-11-08T00:32:25.946046+00:00, count=413
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Akarma~+AND+recording%3Apolice~&fmt=json&limit=3`
- `recording-paranojd.json` — 2026-11-08T00:32:29.768145+00:00, count=8049
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Aparanojd~&fmt=json&limit=3`
- `recording-dont-stpo-me-now.json` — 2026-11-08T00:32:35.340518+00:00, count=509
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Adon%27t~+AND+recording%3Astpo~+AND+recording%3Ame+AND+recording%3Anow&fmt=json&limit=3`
- `recording-love-hate.json` — 2026-11-08T00:32:37.957123+00:00, count=4440
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Alove~+AND+recording%3Ahate~&fmt=json&limit=3`
- `recording-double-typo.json` — 2026-11-08T00:30:17.189987+00:00, count=676
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Ahotle~+AND+recording%3Acalifonia~&fmt=json&limit=3`
- `recording-double-typo-exact-word.json` — 2026-11-08T00:30:20.773518+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Ahotle~+AND+recording%3Acalifonia~+AND+%28recording%3Ahotle+OR+recording%3Acalifonia%29&fmt=json&limit=3`
- `recording-thai.json` — 2026-11-08T00:32:01.103770+00:00, count=15
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%E0%B8%84%E0%B8%A7%E0%B8%B2%E0%B8%A1%E0%B8%A3%E0%B8%B1%E0%B8%81~&fmt=json&limit=3`
- `recording-thai-split.json` — 2026-11-08T00:31:58.620070+00:00, count=5
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%E0%B8%84%E0%B8%A7%E0%B8%B2%E0%B8%A1%E0%B8%A3~+AND+recording%3A%E0%B8%81&fmt=json&limit=3`
- `recording-not-afraid-lowercase.json` — 2026-11-08T00:31:52.783022+00:00, count=1491
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Anot+AND+recording%3Aafraid~&fmt=json&limit=3`
- `recording-and-lowercase.json` — 2026-11-08T00:31:06.156302+00:00, count=11572
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Arokc~+AND+recording%3Aand+AND+recording%3Aroll~&fmt=json&limit=3`
- `recording-and-uppercase.json` — 2026-11-08T00:30:56.901512+00:00, count=7241512
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ARokc~+AND+recording%3AAND+AND+recording%3ARoll~&fmt=json&limit=3`
- `recording-or-lowercase.json` — 2026-11-08T00:31:14.197731+00:00, count=2002
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Anow+AND+recording%3Aor+AND+recording%3Anevr~&fmt=json&limit=3`
- `recording-or-uppercase.json` — 2026-11-08T00:31:11.239090+00:00, count=7341859
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ANow+AND+recording%3AOR+AND+recording%3ANevr~&fmt=json&limit=3`
- `recording-not-lowercase.json` — 2026-11-08T00:31:31.951345+00:00, count=1493
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Anot+AND+recording%3Aafriad~&fmt=json&limit=3`
- `recording-not-uppercase.json` — 2026-11-08T00:31:25.915385+00:00, count=7325620
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ANOT+AND+recording%3AAfriad~&fmt=json&limit=3`
- `recording-and-uppercase-exact-word.json` — 2026-11-08T00:31:01.554695+00:00, count=7345687
  - `https://musicbrainz.org/ws/2/recording?query=recording%3ARokc~+AND+recording%3AAND+AND+recording%3ARoll~+AND+%28recording%3ARokc+OR+recording%3ARoll%29&fmt=json&limit=3`
- `release-karma-polise.json` — 2026-11-08T00:34:31.687489+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3Akarma~+AND+release%3Apolise~&fmt=json&limit=3`
- `release-karma-police.json` — 2026-11-08T00:34:35.283022+00:00, count=22
  - `https://musicbrainz.org/ws/2/release?query=release%3Akarma~+AND+release%3Apolice~&fmt=json&limit=3`
- `release-paranojd.json` — 2026-11-08T00:34:37.830036+00:00, count=1158
  - `https://musicbrainz.org/ws/2/release?query=release%3Aparanojd~&fmt=json&limit=3`
- `release-dont-stpo-me-now.json` — 2026-11-08T00:34:40.284521+00:00, count=46
  - `https://musicbrainz.org/ws/2/release?query=release%3Adon%27t~+AND+release%3Astpo~+AND+release%3Ame+AND+release%3Anow&fmt=json&limit=3`
- `release-love-hate.json` — 2026-11-08T00:34:42.861190+00:00, count=958
  - `https://musicbrainz.org/ws/2/release?query=release%3Alove~+AND+release%3Ahate~&fmt=json&limit=3`
- `release-double-typo.json` — 2026-11-08T00:32:42.843558+00:00, count=139
  - `https://musicbrainz.org/ws/2/release?query=release%3Ahotle~+AND+release%3Acalifonia~&fmt=json&limit=3`
- `release-double-typo-exact-word.json` — 2026-11-08T00:32:45.405589+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3Ahotle~+AND+release%3Acalifonia~+AND+%28release%3Ahotle+OR+release%3Acalifonia%29&fmt=json&limit=3`
- `release-thai.json` — 2026-11-08T00:34:24.202575+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%E0%B8%84%E0%B8%A7%E0%B8%B2%E0%B8%A1%E0%B8%A3%E0%B8%B1%E0%B8%81~&fmt=json&limit=3`
- `release-thai-split.json` — 2026-11-08T00:34:20.698191+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%E0%B8%84%E0%B8%A7%E0%B8%B2%E0%B8%A1%E0%B8%A3~+AND+release%3A%E0%B8%81&fmt=json&limit=3`
- `release-not-afraid-lowercase.json` — 2026-11-08T00:34:14.927722+00:00, count=181
  - `https://musicbrainz.org/ws/2/release?query=release%3Anot+AND+release%3Aafraid~&fmt=json&limit=3`
- `release-and-lowercase.json` — 2026-11-08T00:33:14.026981+00:00, count=2137
  - `https://musicbrainz.org/ws/2/release?query=release%3Arokc~+AND+release%3Aand+AND+release%3Aroll~&fmt=json&limit=3`
- `release-and-uppercase.json` — 2026-11-08T00:33:07.922949+00:00, count=556819
  - `https://musicbrainz.org/ws/2/release?query=release%3ARokc~+AND+release%3AAND+AND+release%3ARoll~&fmt=json&limit=3`
- `release-or-lowercase.json` — 2026-11-08T00:33:35.201212+00:00, count=283
  - `https://musicbrainz.org/ws/2/release?query=release%3Anow+AND+release%3Aor+AND+release%3Anevr~&fmt=json&limit=3`
- `release-or-uppercase.json` — 2026-11-08T00:33:28.873461+00:00, count=574251
  - `https://musicbrainz.org/ws/2/release?query=release%3ANow+AND+release%3AOR+AND+release%3ANevr~&fmt=json&limit=3`
- `release-not-lowercase.json` — 2026-11-08T00:33:51.285725+00:00, count=183
  - `https://musicbrainz.org/ws/2/release?query=release%3Anot+AND+release%3Aafriad~&fmt=json&limit=3`
- `release-not-uppercase.json` — 2026-11-08T00:33:40.975184+00:00, count=568441
  - `https://musicbrainz.org/ws/2/release?query=release%3ANOT+AND+release%3AAfriad~&fmt=json&limit=3`
- `release-and-uppercase-exact-word.json` — 2026-11-08T00:33:10.488671+00:00, count=567295
  - `https://musicbrainz.org/ws/2/release?query=release%3ARokc~+AND+release%3AAND+AND+release%3ARoll~+AND+%28release%3ARokc+OR+release%3ARoll%29&fmt=json&limit=3`
- `recording-satisfaction.json` — 2026-11-08T00:34:45.784113+00:00, count=1128
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Ai+AND+recording%3Acan%27t~+AND+recording%3Aget+AND+recording%3Ano+AND+recording%3Asatisfacton~&fmt=json&limit=3`
- `release-rumours.json` — 2026-11-08T00:34:48.365196+00:00, count=5
  - `https://musicbrainz.org/ws/2/release?query=release%3Arumours~+AND+release%3Adeluxe~&fmt=json&limit=3`

### Probe table

Counts cover the upstream pool; only the first three rows were inspected. Mixed-case and
uppercase fuzzy terms returned the same counts as lowercase terms in each paired probe.
The lowercase exact Boolean words keep their title meaning instead of becoming operators.
`Beyonce` plus U+0301 returned the same counts and top rows with and without the mark on
both endpoints. The release query for `ความรัก` returned zero with or without marks;
the recording query supplies the positive title match.

| Arm | Decoded query | Count | Top titles (score) |
|---|---|---:|---|
| Hotle Califonia fuzzy-only lowercase terms | `recording:hotle~ AND recording:califonia~` | 676 | Hotel California (Hotel California) (100); Hotel Caliifornia (93); Hotel Cacafonia (92) |
| Hotle Califonia exact-word clause | `recording:hotle~ AND recording:califonia~ AND (recording:hotle OR recording:califonia)` | 0 | ∅ |
| Bohemain Rapsodie fuzzy-only lowercase terms | `recording:bohemain~ AND recording:rapsodie~` | 20 | Bohemian Rhapsody (Rapsodia Bohemia) (100); Bohemian rhapsodie (91); Bohemian Rhapsodie (90) |
| Bohemain Rapsodie exact-word clause | `recording:bohemain~ AND recording:rapsodie~ AND (recording:bohemain OR recording:rapsodie)` | 0 | ∅ |
| Stiarway to Heavn fuzzy-only lowercase terms | `recording:stiarway~ AND recording:to AND recording:heavn~` | 1752 | Stairway to Heaven (100); Stairway to Heaven (100); Stairway to Heaven (100) |
| Stiarway to Heavn exact-word clause | `recording:stiarway~ AND recording:to AND recording:heavn~ AND (recording:stiarway OR recording:heavn)` | 0 | ∅ |
| Wonderwal Oaisis fuzzy-only lowercase terms | `recording:wonderwal~ AND recording:oaisis~` | 53 | Wonderwall (Oasis) (100); Wonderwall (Oasis) (99); Wonderwall (OASIS) (99) |
| Wonderwal Oaisis exact-word clause | `recording:wonderwal~ AND recording:oaisis~ AND (recording:wonderwal OR recording:oaisis)` | 0 | ∅ |
| Rokc AND Roll bare operators | `recording:Rokc~ AND recording:AND AND recording:Roll~` | 7241512 | AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND (100); Roll And Roll And Roll (99); Roll & Roll & Roll (99) |
| Rokc AND Roll uppercase operator with an exact-word clause | `recording:Rokc~ AND recording:AND AND recording:Roll~ AND (recording:Rokc OR recording:Roll)` | 7345687 | Roll And Roll And Roll (100); Roll & Roll & Roll (100); &=&=&=&=OR; immense (99) |
| Rokc AND Roll fuzzy-only lowercase terms | `recording:rokc~ AND recording:and AND recording:roll~` | 11572 | Rock and Roll, Rock and Roll (100); Mr Rock And Roll Role (99); ROCK AND ROLL / ROCK AND ROLL (93) |
| Rokc AND Roll uppercase fuzzy terms | `recording:ROKC~ AND recording:and AND recording:ROLL~` | 11572 | Mr Rock And Roll Role (100); Rock and Roll, Rock and Roll (99); ROCK AND ROLL / ROCK AND ROLL (93) |
| Now OR Nevr bare operators | `recording:Now AND recording:OR AND recording:Nevr~` | 7341859 | &=&=&=&=OR; immense (100); AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND AND (94); And And And And And And And And And And (93) |
| Now OR Nevr fuzzy-only lowercase terms | `recording:now AND recording:or AND recording:nevr~` | 2002 | Now or Never Now (100); Now or Never Now (100); Now or Never Now (100) |
| Now OR Nevr uppercase fuzzy terms | `recording:NOW AND recording:or AND recording:NEVR~` | 2002 | Now or Never Now (100); Now or Never Now (100); Now or Never Now (100) |
| NOT Afriad bare operators | `recording:NOT AND recording:Afriad~` | 7325620 | I'm Not Afriad (100); Dialective - Not Afriad (90); Afriad To Fly (68) |
| NOT Afriad fuzzy-only lowercase terms | `recording:not AND recording:afriad~` | 1493 | I'm Not Afriad (100); Not Afraid (98); Not Afraid (98) |
| NOT Afriad uppercase fuzzy terms | `recording:not AND recording:AFRIAD~` | 1493 | I'm Not Afriad (100); Not Afraid (97); Not Afraid (97) |
| Rock and Roll correct mixed input | `recording:rock~ AND recording:and AND recording:roll~` | 11602 | Rock and Roll, Rock and Roll (100); Mr Rock And Roll Role (97); Rock & Roll Rock (94) |
| Rock and Roll correct uppercase fuzzy terms | `recording:ROCK~ AND recording:and AND recording:ROLL~` | 11602 | Rock and Roll, Rock and Roll (100); Mr Rock And Roll Role (97); Rock & Roll Rock (94) |
| Now or Never correct mixed input | `recording:now AND recording:or AND recording:never~` | 1993 | Now or Never Now (100); Now or Never Now (100); Now or Never Now (99) |
| Now or Never correct uppercase fuzzy terms | `recording:NOW AND recording:or AND recording:NEVER~` | 1993 | Now or Never Now (100); Now or Never Now (100); Now or Never Now (99) |
| Not Afraid correct mixed input | `recording:not AND recording:afraid~` | 1491 | Not Afraid (100); Not Afraid (100); Not Afraid (100) |
| Not Afraid correct uppercase fuzzy terms | `recording:not AND recording:AFRAID~` | 1491 | Not Afraid (100); Not Afraid (100); Not Afraid (100) |
| ความรัก without marks | `recording:ความร~ AND recording:ก` | 5 | ต้นเหตุ คือ...ความรัก (100); มีหัวใจ (แต่ไร้ความรู้สึก) (91); มีหัวใจ (แต่ไร้ความรู้สึก) (91) |
| ความรัก with marks | `recording:ความรัก~` | 15 | ความรัก (100); ความรัก (100); ความรัก (99) |
| Beyoncé without marks | `recording:beyonce~` | 30502 | Beyond Beyoncé (100); Renonce…Dénonce… (85); Beyond the Beyonda (79) |
| Beyoncé with marks | `recording:beyoncé~` | 30502 | Beyond Beyoncé (100); Renonce…Dénonce… (85); Beyond the Beyonda (79) |
| Karma Polise lowercase word query | `recording:karma~ AND recording:polise~` | 412 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Karma Police lowercase word query | `recording:karma~ AND recording:police~` | 413 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Paranojd lowercase word query | `recording:paranojd~` | 8049 | Paranoid, Paranoia (100); Paranoid, Paranoia (99); Paranoid (Prelude to Paranoia) (78) |
| Don't Stpo Me Now lowercase word query | `recording:don't~ AND recording:stpo~ AND recording:me AND recording:now` | 509 | Don't Stop Me Now (100); Don't Stop Me Now (100); Don’t Stop Me Now (100) |
| Love-Hate lowercase word query | `recording:love~ AND recording:hate~` | 4440 | Love Hater (Hate for Love) (100); Love Hate Fear Fate (99); Love Hate Love Hate (93) |
| Hotle Califonia fuzzy-only lowercase terms | `release:hotle~ AND release:califonia~` | 139 | Hotel California (100); Hotel California (100); Hotel California (100) |
| Hotle Califonia exact-word clause | `release:hotle~ AND release:califonia~ AND (release:hotle OR release:califonia)` | 0 | ∅ |
| Bohemain Rapsodie fuzzy-only lowercase terms | `release:bohemain~ AND release:rapsodie~` | 4 | Bohemian Rhapsodies (100); Bohemian Rhapsodies (100); Bohemian Rhapsodies (100) |
| Bohemain Rapsodie exact-word clause | `release:bohemain~ AND release:rapsodie~ AND (release:bohemain OR release:rapsodie)` | 0 | ∅ |
| Stiarway to Heavn fuzzy-only lowercase terms | `release:stiarway~ AND release:to AND release:heavn~` | 70 | Stairway to Heaven (100); Stairway to Heaven (100); Stairway to Heaven (100) |
| Stiarway to Heavn exact-word clause | `release:stiarway~ AND release:to AND release:heavn~ AND (release:stiarway OR release:heavn)` | 0 | ∅ |
| Wonderwal Oaisis fuzzy-only lowercase terms | `release:wonderwal~ AND release:oaisis~` | 4 | Wonderwall (OASIS) (100); A Wonderful Crisis (74); Cheap Wonderwall (Sia vs. Oasis) (70) |
| Wonderwal Oaisis exact-word clause | `release:wonderwal~ AND release:oaisis~ AND (release:wonderwal OR release:oaisis)` | 0 | ∅ |
| Rokc AND Roll bare operators | `release:Rokc~ AND release:AND AND release:Roll~` | 556819 | And And And And And And And And And And (100); %&%&%&%&%&%& (98); One and, and, and, and One and, and Three and, and (92) |
| Rokc AND Roll uppercase operator with an exact-word clause | `release:Rokc~ AND release:AND AND release:Roll~ AND (release:Rokc OR release:Roll)` | 567295 | and/or (100); And/Or (100); Rock & Roll and Alcohol (89) |
| Rokc AND Roll fuzzy-only lowercase terms | `release:rokc~ AND release:and AND release:roll~` | 2137 | Rock and Roll (100); Rock And Roll (100); Rock & Roll (100) |
| Rokc AND Roll uppercase fuzzy terms | `release:ROKC~ AND release:and AND release:ROLL~` | 2137 | Rock and Roll (100); Rock And Roll (100); Rock & Roll (100) |
| Now OR Nevr bare operators | `release:Now AND release:OR AND release:Nevr~` | 574251 | and/or (100); And/Or (100); And Now! (96) |
| Now OR Nevr fuzzy-only lowercase terms | `release:now AND release:or AND release:nevr~` | 283 | Now or Never Now (100); Now or Never (98); Now or Never (98) |
| Now OR Nevr uppercase fuzzy terms | `release:NOW AND release:or AND release:NEVR~` | 283 | Now or Never Now (100); Now or Never (98); Now or Never (98) |
| NOT Afriad bare operators | `release:NOT AND release:Afriad~` | 568441 | I'm Not Afriad (100); And Not Or (57); And Why Not? (57) |
| NOT Afriad fuzzy-only lowercase terms | `release:not AND release:afriad~` | 183 | Not Afraid, Not Afraid (100); Not Afraid (91); Not Afraid (91) |
| NOT Afriad uppercase fuzzy terms | `release:not AND release:AFRIAD~` | 183 | Not Afraid, Not Afraid (100); Not Afraid (91); Not Afraid (91) |
| Rock and Roll correct mixed input | `release:rock~ AND release:and AND release:roll~` | 2152 | Rock and Roll (100); Rock And Roll (100); Rock & Roll (100) |
| Rock and Roll correct uppercase fuzzy terms | `release:ROCK~ AND release:and AND release:ROLL~` | 2152 | Rock and Roll (100); Rock And Roll (100); Rock & Roll (100) |
| Now or Never correct mixed input | `release:now AND release:or AND release:never~` | 282 | Ever, Never or Now? (100); Now or Never Now (90); Now or Never (90) |
| Now or Never correct uppercase fuzzy terms | `release:NOW AND release:or AND release:NEVER~` | 282 | Ever, Never or Now? (100); Now or Never Now (90); Now or Never (90) |
| Not Afraid correct mixed input | `release:not AND release:afraid~` | 181 | Not Afraid, Not Afraid (100); Not Afraid (91); Not Afraid (91) |
| Not Afraid correct uppercase fuzzy terms | `release:not AND release:AFRAID~` | 181 | Not Afraid, Not Afraid (100); Not Afraid (91); Not Afraid (91) |
| ความรัก without marks | `release:ความร~ AND release:ก` | 0 | ∅ |
| ความรัก with marks | `release:ความรัก~` | 0 | ∅ |
| Beyoncé without marks | `release:beyonce~` | 7638 | BEYONCÉ (100); BEYONCÉ (100); BEYONCÉ (100) |
| Beyoncé with marks | `release:beyoncé~` | 7638 | BEYONCÉ (100); BEYONCÉ (100); BEYONCÉ (100) |
| Karma Polise lowercase word query | `release:karma~ AND release:polise~` | 22 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Karma Police lowercase word query | `release:karma~ AND release:police~` | 22 | Karma Police (100); Karma Police (100); Karma Police (100) |
| Paranojd lowercase word query | `release:paranojd~` | 1158 | Paranoid (100); Paranoid (100); Paranoid (100) |
| Don't Stpo Me Now lowercase word query | `release:don't~ AND release:stpo~ AND release:me AND release:now` | 46 | Don't Stop Me Now (100); Don't stop me NOW (100); Don't Stop Me Now (100) |
| Love-Hate lowercase word query | `release:love~ AND release:hate~` | 958 | Love Hate Fate (100); Love Hate Fear Fate (88); Love Hate Live (86) |
| Satisfaction punctuation | `recording:i AND recording:can't~ AND recording:get AND recording:no AND recording:satisfacton~` | 1128 | (I Can’t Get No Satisfaction) Satisfaction (100); (I Can’t Get No) Satisfaction (Can’t Get No Satisfaction Dub) (99); (I Can’t Get No) Satisafction (96) |
| Rumours punctuation | `release:rumours~ AND release:deluxe~` | 5 | Rumours (deluxe) (100); Rumours (super deluxe) (87); Rumours (super deluxe) (87) |

### Query policy and limits

Title-only queries use a four-code-point threshold. Their words contain letters, numbers
and combining marks, with internal apostrophes retained, curly apostrophes normalized,
and lowercase conversion using `Locale.ROOT`. Every word is required; only long words take
`~`, with no additional exact-word constraint. A title whose long words are all misspelled
can produce candidates. One approximate word can satisfy several fuzzy clauses, as `Rolc`
can for `Rokc Roll`. Responses are not filtered by title. Short-only titles receive no fuzzy
request. These captures do not prove relevance of the complete pool, general multilingual
recall, latency or live enrichment end to end.

## Numeric terms and Unicode word lengths

Captured live on 2026-11-08, with the same
User-Agent and limit=3 as above. Requests were sequential with at least 1.1 seconds after
each response, including unsuccessful attempts. HTTP 503 responses were retried at most
three times; only complete unmodified HTTP 200 bodies are fixtures. Each entry records
the response's `created` time, local receipt time, count, URL and SHA-256.

- `recording-strman-2012-remaster-strict.json` — 2026-11-08T04:17:56.761Z, received=2026-11-08T04:17:56.892081+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Strman+%5C-+2012+Remaster%22+AND+artistname%3A%22%22&fmt=json&limit=3`
  - SHA-256: `7c5dfbe3451d133eaccedf93aa3fde71d8694c2a8c22e91bcf49b9a0c808f030`
- `recording-strman-2012-remaster-fuzzy-number.json` — 2026-11-08T04:17:59.312Z, received=2026-11-08T04:17:59.452523+00:00, count=5
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Astrman~+AND+recording%3A2012~+AND+recording%3Aremaster~&fmt=json&limit=3`
  - SHA-256: `d4cbb022f1b0f166e4cb315e93ae9c12487555aad86975a11a40e35fd93ee62f`
- `recording-strman-2012-remaster-exact-number.json` — 2026-11-08T04:18:05.567Z, received=2026-11-08T04:18:05.697051+00:00, count=2
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Astrman~+AND+recording%3A2012+AND+recording%3Aremaster~&fmt=json&limit=3`
  - SHA-256: `8ddb6474697e0fcbcd72d96869dbdd70d4cc0a592c521e955b0e2f2626157194`
- `release-live-at-wembly-1986-strict.json` — 2026-11-08T04:18:11.410Z, received=2026-11-08T04:18:11.537651+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22Live+at+Wembly+1986%22+AND+artistname%3A%22%22&fmt=json&limit=3`
  - SHA-256: `4a9de2f46d88c2e357608f6c01cfa19396be5f2502f7f421e997b4858f14b8ec`
- `release-live-at-wembly-1986-fuzzy-number.json` — 2026-11-08T04:18:13.872Z, received=2026-11-08T04:18:14.003203+00:00, count=14
  - `https://musicbrainz.org/ws/2/release?query=release%3Alive~+AND+release%3Aat+AND+release%3Awembly~+AND+release%3A1986~&fmt=json&limit=3`
  - SHA-256: `eeccd52f662ad8e20009174a92531a46e0887714e422ea57f16d9da35f7a3c89`
- `release-live-at-wembly-1986-exact-number.json` — 2026-11-08T04:18:17.900Z, received=2026-11-08T04:18:18.027359+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3Alive~+AND+release%3Aat+AND+release%3Awembly~+AND+release%3A1986&fmt=json&limit=3`
  - SHA-256: `bd0cb7978f9a37495700b69fea99bb9ca112b9a4390d2961284942e75c5546bc`
- `recording-karma-polise-quoted-empty-artist.json` — 2026-11-08T04:18:20.461Z, received=2026-11-08T04:18:20.592010+00:00, count=0
  - `https://musicbrainz.org/ws/2/recording?query=recording%3A%22Karma+Polise%22+AND+artistname%3A%22%22&fmt=json&limit=3`
  - SHA-256: `c754fd4ef33865f880df7c0bcc9782cb05598fbf2c4ad3e73f98a77be31e281d`
- `recording-sum41.json` — 2026-11-08T04:18:23.027Z, received=2026-11-08T04:18:23.155309+00:00, count=1353
  - `https://musicbrainz.org/ws/2/recording?query=recording%3Asum41~&fmt=json&limit=3`
  - SHA-256: `48145ba63797c4887d154990508419b0dd071d3c10c8883d034911dd1819081c`
- `release-karma-polise-quoted-empty-artist.json` — 2026-11-08T04:18:25.435Z, received=2026-11-08T04:18:25.562775+00:00, count=0
  - `https://musicbrainz.org/ws/2/release?query=release%3A%22Karma+Polise%22+AND+artistname%3A%22%22&fmt=json&limit=3`
  - SHA-256: `2b54c77a2518f09b650f19695c0978696caf3944071a2a7909a2f9e7a775c29a`
- `release-sum41.json` — 2026-11-08T04:18:27.878Z, received=2026-11-08T04:18:28.351371+00:00, count=340
  - `https://musicbrainz.org/ws/2/release?query=release%3Asum41~&fmt=json&limit=3`
  - SHA-256: `7d1a576c0704339d0b2744da568ac47e9da772d6de6b797494dc9b4d56c797eb`

The numeric comparisons change only the fuzzy suffix on the year. Both strict title queries
miss. The exact-number recording query retains the two 2012 editions; the exact-number release
query returns no rows. The `sum41~` captures document the mixed letter-and-number query shape,
not a guarantee of related results. The quoted empty-artist captures are the exact strict queries
sent by candidate search, including `AND artistname:""`.
