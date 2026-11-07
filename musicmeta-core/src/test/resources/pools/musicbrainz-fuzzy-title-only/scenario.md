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
AND-per-title-term captures retain typo tolerance while requiring every word in the title field.
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
