# Featured-credit search captures

Captured live on 2026-11-09 at main `03bcae9ac6019b71b54a639a94f4b2701b7d201c`, before any implementation change. These are complete, unmodified HTTP 200 response bodies. MusicBrainz WS/2 recording and release searches used limit 3 and User-Agent `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`. Requests ran sequentially, with an exclusive `fcntl.flock` on `/tmp/musicmeta-mb-live.lock` through each request and its 1.1-second post-response gap. Other probes shared the lock.

191 HTTP attempts: 165 HTTP 200 captures and 26 HTTP 503 responses, retried. Errors are not zero-hit results. `captures.json` records each decoded query, URL, receipt time, SHA-256, count, returned row ids and full structured artist credits. This directory is a selected subset of the source captures. It does not claim a new live measurement.

The full experiment, including the protocol, limits and triage, is in [issue #441's triage comment](https://github.com/famesjranko/musicmeta/issues/441#issuecomment-6737700973). The selected bodies below are the complete route pool used by the offline tests. Only the returned top three were inspected. No failed response body is needed.

| File | Decoded query | Hits | Top three rows in returned order |
|---|---|---:|---|
| `recording-feat-strict-current.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem feat. Rihanna"` | 0 | none |
| `recording-feat-strict-primary.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem"` | 30 | Love The Way You Lie — Eminem; Love The Way You Lie — Eminem; Love the Way You Lie (instrumental) — Eminem |
| `recording-feat-strict-components.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem" AND artistname:"Rihanna"` | 20 | Love The Way You Lie — Eminem feat. Rihanna; Love the Way You Lie — Rihanna feat. Eminem; Love the Way You Lie — Eminem feat. Rihanna |
| `recording-ft-strict-current.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem Ft. Rihanna"` | 0 | none |
| `recording-featuring-strict-current.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem fEaTuRiNg Rihanna"` | 0 | none |
| `recording-empire-strict-current.json` | `recording:"Empire State of Mind" AND artistname:"JAY\-Z FEAT. Alicia Keys"` | 0 | none |
| `recording-empire-strict-components.json` | `recording:"Empire State of Mind" AND artistname:"JAY\-Z" AND artistname:"Alicia Keys"` | 14 | Empire State Of Mind — Alicia Keys feat. JAY‐Z [canonical: Alicia Keys feat. JAŸ-Z]; Empire State of Mind — Jay‐Z + Alicia Keys [canonical: JAŸ-Z + Alicia Keys]; Empire State of Mind — Jay-Z Alicia Keys [canonical: JAŸ-Z Alicia Keys] |
| `recording-simon-strict-current.json` | `recording:"The Sound of Silence" AND artistname:"Simon \& Garfunkel"` | 101 | The Sound of Silence — Simon & Garfunkel; The Sound of Silence — Simon & Garfunkel; The Sound of Silence — Simon & Garfunkel |
| `recording-simon-and-strict-current.json` | `recording:"The Sound of Silence" AND artistname:"SIMON AND GARFUNKEL"` | 101 | The Sound of Silence — Simon & Garfunkel; The Sound of Silence — Simon & Garfunkel; The Sound of Silence — Simon & Garfunkel |
| `recording-earth-strict-current.json` | `recording:"September" AND artistname:"Earth, Wind \& Fire"` | 172 | September — Earth, Wind & Fire; September — Earth, Wind & Fire; September — Earth, Wind & Fire |
| `recording-earth-and-strict-current.json` | `recording:"September" AND artistname:"EARTH, WIND AND FIRE"` | 172 | September — Earth, Wind & Fire; September — Earth, Wind & Fire; September — Earth, Wind & Fire |
| `recording-hall-strict-current.json` | `recording:"Maneater" AND artistname:"Daryl Hall \& John Oates"` | 74 | Maneater — Daryl Hall & John Oates; Maneater — Daryl Hall & John Oates; Maneater — Daryl Hall & John Oates |
| `recording-negative-strict-current.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem feat. Radiohead"` | 0 | none |
| `recording-negative-strict-components.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem" AND artistname:"Radiohead"` | 0 | none |
| `recording-empire-typo-fuzzy-current.json` | `recording:empire~ AND recording:state~ AND recording:of AND recording:midn~ AND artistname:jay AND artistname:z AND artistname:feat~ AND artistname:alicia~ AND artistname:keys~` | 0 | none |
| `recording-empire-typo-fuzzy-components.json` | `recording:empire~ AND recording:state~ AND recording:of AND recording:midn~ AND artistname:jay AND artistname:z AND artistname:alicia~ AND artistname:keys~` | 14 | Empire State of Mind — Jay‐Z feat. Alicia Keys [canonical: JAŸ-Z feat. Alicia Keys]; Empire State Of Mind — Alicia Keys feat. JAY‐Z [canonical: Alicia Keys feat. JAŸ-Z]; Empire State of Mind — Jay‐Z + Alicia Keys [canonical: JAŸ-Z + Alicia Keys] |
| `release-feat-strict-current.json` | `release:"Love the Way You Lie" AND artistname:"Eminem feat. Rihanna"` | 0 | none |
| `release-feat-strict-primary.json` | `release:"Love the Way You Lie" AND artistname:"Eminem"` | 1 | Love the Way You Lie — Eminem feat. Rihanna |
| `release-feat-strict-components.json` | `release:"Love the Way You Lie" AND artistname:"Eminem" AND artistname:"Rihanna"` | 1 | Love the Way You Lie — Eminem feat. Rihanna |
| `release-ft-strict-current.json` | `release:"Love the Way You Lie" AND artistname:"Eminem Ft. Rihanna"` | 0 | none |
| `release-featuring-strict-current.json` | `release:"Love the Way You Lie" AND artistname:"Eminem fEaTuRiNg Rihanna"` | 0 | none |
| `release-empire-strict-current.json` | `release:"Empire State of Mind" AND artistname:"JAY\-Z FEAT. Alicia Keys"` | 0 | none |
| `release-empire-strict-components.json` | `release:"Empire State of Mind" AND artistname:"JAY\-Z" AND artistname:"Alicia Keys"` | 5 | Empire State of Mind — Jay‐Z & Alicia Keys [canonical: JAŸ-Z & Alicia Keys]; Empire State of Mind — Jay‐Z & Alicia Keys [canonical: JAŸ-Z & Alicia Keys]; Empire State of Mind — Jay Z & Alicia Keys [canonical: JAŸ-Z & Alicia Keys] |
| `release-simon-strict-current.json` | `release:"The Sound of Silence" AND artistname:"Simon \& Garfunkel"` | 1 | 1969-11-11: The Sound of Silence: OH, USA — Simon & Garfunkel |
| `release-simon-and-strict-current.json` | `release:"The Sound of Silence" AND artistname:"SIMON AND GARFUNKEL"` | 1 | 1969-11-11: The Sound of Silence: OH, USA — Simon & Garfunkel |
| `release-earth-strict-current.json` | `release:"September" AND artistname:"Earth, Wind \& Fire"` | 14 | September — Earth, Wind & Fire; September — Earth, Wind & Fire; September — Earth, Wind & Fire |
| `release-earth-and-strict-current.json` | `release:"September" AND artistname:"EARTH, WIND AND FIRE"` | 14 | September — Earth, Wind & Fire; September — Earth, Wind & Fire; September — Earth, Wind & Fire |
| `release-hall-strict-current.json` | `release:"Maneater" AND artistname:"Daryl Hall \& John Oates"` | 6 | Maneater — Hall & Oates [canonical: Daryl Hall & John Oates]; Maneater — Daryl Hall + John Oates [canonical: Daryl Hall & John Oates]; Best - Maneater — Daryl Hall & John Oates |
| `release-negative-strict-current.json` | `release:"Love the Way You Lie" AND artistname:"Eminem feat. Radiohead"` | 0 | none |
| `release-negative-strict-components.json` | `release:"Love the Way You Lie" AND artistname:"Eminem" AND artistname:"Radiohead"` | 0 | none |
| `release-empire-typo-fuzzy-current.json` | `release:empire~ AND release:state~ AND release:of AND release:midn~ AND artistname:jay AND artistname:z AND artistname:feat~ AND artistname:alicia~ AND artistname:keys~` | 0 | none |
| `release-empire-typo-fuzzy-components.json` | `release:empire~ AND release:state~ AND release:of AND release:midn~ AND artistname:jay AND artistname:z AND artistname:alicia~ AND artistname:keys~` | 5 | Empire State of Mind — Jay Z & Alicia Keys [canonical: JAŸ-Z & Alicia Keys]; Empire State of Mind — Jay-Z + Alicia Keys [canonical: JAŸ-Z + Alicia Keys]; Empire State of Mind — JAY‐Z + Alicia Keys [canonical: JAŸ-Z + Alicia Keys] |
| `recording-lvoe-fuzzy-current.json` | `recording:lvoe~ AND recording:the AND recording:way AND recording:you AND recording:lie AND artistname:eminem~ AND artistname:feat~ AND artistname:rihanna~` | 0 | none |
| `recording-lvoe-fuzzy-primary.json` | `recording:lvoe~ AND recording:the AND recording:way AND recording:you AND recording:lie AND artistname:eminem~` | 30 | Love The Way You Lie — Eminem; Love The Way You Lie — Eminem; Love the Way You Lie (instrumental) — Eminem |
| `recording-lvoe-fuzzy-components.json` | `recording:lvoe~ AND recording:the AND recording:way AND recording:you AND recording:lie AND artistname:eminem~ AND artistname:rihanna~` | 20 | Love The Way You Lie — Eminem feat. Rihanna; Love the Way You Lie — Eminem feat. Rihanna; Love the Way You Lie — Rihanna feat. Eminem |
| `recording-lvoe-strict-current.json` | `recording:"Lvoe the Way You Lie" AND artistname:"Eminem feat. Rihanna"` | 0 | none |
| `recording-lvoe-negative-fuzzy-current.json` | `recording:lvoe~ AND recording:the AND recording:way AND recording:you AND recording:lie AND artistname:eminem~ AND artistname:feat~ AND artistname:radiohead~` | 0 | none |
| `recording-lvoe-negative-fuzzy-components.json` | `recording:lvoe~ AND recording:the AND recording:way AND recording:you AND recording:lie AND artistname:eminem~ AND artistname:radiohead~` | 0 | none |
| `release-lvoe-fuzzy-current.json` | `release:lvoe~ AND release:the AND release:way AND release:you AND release:lie AND artistname:eminem~ AND artistname:feat~ AND artistname:rihanna~` | 0 | none |
| `release-lvoe-fuzzy-primary.json` | `release:lvoe~ AND release:the AND release:way AND release:you AND release:lie AND artistname:eminem~` | 1 | Love the Way You Lie — Eminem feat. Rihanna |
| `release-lvoe-fuzzy-components.json` | `release:lvoe~ AND release:the AND release:way AND release:you AND release:lie AND artistname:eminem~ AND artistname:rihanna~` | 1 | Love the Way You Lie — Eminem feat. Rihanna |
| `release-lvoe-strict-current.json` | `release:"Lvoe the Way You Lie" AND artistname:"Eminem feat. Rihanna"` | 0 | none |
| `release-lvoe-negative-fuzzy-current.json` | `release:lvoe~ AND release:the AND release:way AND release:you AND release:lie AND artistname:eminem~ AND artistname:feat~ AND artistname:radiohead~` | 0 | none |
| `release-lvoe-negative-fuzzy-components.json` | `release:lvoe~ AND release:the AND release:way AND release:you AND release:lie AND artistname:eminem~ AND artistname:radiohead~` | 0 | none |
| `recording-feat-canonical-current.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem feat. Rihanna" AND -comment:*` | 0 | none |
| `recording-feat-canonical-components.json` | `recording:"Love the Way You Lie" AND artistname:"Eminem" AND artistname:"Rihanna" AND -comment:*` | 15 | Love The Way You Lie — Eminem feat. Rihanna; Love the Way You Lie — Rihanna feat. Eminem; Love the Way You Lie — Eminem feat. Rihanna |
| `recording-lvoe-strict-components.json` | `recording:"Lvoe the Way You Lie" AND artistname:"Eminem" AND artistname:"Rihanna"` | 0 | none |
| `recording-lvoe-canonical-components.json` | `recording:"Lvoe the Way You Lie" AND artistname:"Eminem" AND artistname:"Rihanna" AND -comment:*` | 0 | none |
| `release-lvoe-strict-components.json` | `release:"Lvoe the Way You Lie" AND artistname:"Eminem" AND artistname:"Rihanna"` | 0 | none |
