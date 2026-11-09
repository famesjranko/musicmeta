# Punctuation-only supplied artist captures

Captured live on 2026-11-09 against MusicBrainz WS/2 before implementation, baseline 03bcae9ac6019b71b54a639a94f4b2701b7d201c. Limit 3; one request at a time; exclusive fcntl.flock on /tmp/musicmeta-mb-live.lock around each request and at least 1.1 seconds after its response. User-Agent: `MusicEnrichmentEngine/1.0 ( https://github.com/famesjranko/musicmeta )`.

33 attempts: 26 HTTP 200 response bodies, seven HTTP 503 responses retried. No failed response counted as zero hits. requests.json records attempts; captures.json records decoded queries, URLs, receipt times, SHA-256, full counts and returned rows. Response bodies are unmodified. Row artist is the displayed credit; parsedArtist is the expected canonical-name credit exposed by the parser. Discovery selected real +/- titles; correct confirms real title/artist pairs; strict is the typo miss; current has no artist clause; term is the escaped fuzzy term; phrase is the escaped exact phrase; words is the per-word control.

Choose the exact phrase: both constrained forms retain intended leading rows in all four punctuation cases. The phrase follows strict search syntax and avoids an approximate punctuation term. The recording !!! term has 12 hits versus 11 for the phrase; only the first three were inspected, so the extra row is not classified. The +/- recording pool includes a collaboration. The title-only !!! release already leads with the intended artist; narrowing its pool is not evidence of an ordering repair for that case. Radiohead keeps its word clause (281 recordings, nine releases).

Not measured: rows beyond three; complete recall, aliases or punctuation variants; prevalence; live end-to-end enrichment; latency; album hints; ranking changes; fallback behavior; featured credits. Captures demonstrate suggestion pools, not identity or artist exclusivity. Strict typo misses were measured at limit 3; replaying an empty body in a larger-limit offline strict request does not measure that larger live request.

| File | Decoded query | Hits | Top three rows in returned order |
|---|---|---:|---|
| `recording-plus-minus-discovery-discovery.json` | `artistname:"\+\/\-"` | 164 | Banging the Drum — +/-; Surprise! — +/-; Making the Horse Drink — +/- |
| `release-plus-minus-discovery-discovery.json` | `artistname:"\+\/\-"` | 24 | You Are Here — +/-; As Seen on Television — +/-; Holding Patterns — +/- |
| `recording-bang-correct.json` | `recording:"Heart of Hearts" AND artistname:"\!\!\!"` | 11 | Heart of Hearts — !!!; Heart of Hearts — !!!; Heart of Hearts — !!! |
| `recording-bang-strict.json` | `recording:"Heart of Hearst" AND artistname:"\!\!\!"` | 0 | none |
| `recording-bang-current.json` | `recording:heart~ AND recording:of AND recording:hearst~` | 32259 | Heart of Heart of Hearts — Deep Color; Heart of Hearts — Dan Siegel; Heart of Hearts — Kevin Coyne |
| `recording-bang-term.json` | `recording:heart~ AND recording:of AND recording:hearst~ AND artistname:\!\!\!~` | 12 | Heart of Hearts — !!!; Heart of Hearts — !!!; Heart of Hearts — !!! |
| `recording-bang-phrase.json` | `recording:heart~ AND recording:of AND recording:hearst~ AND artistname:"\!\!\!"` | 11 | Heart of Hearts — !!!; Heart of Hearts — !!!; Heart of Hearts — !!! |
| `release-bang-correct.json` | `release:"Myth Takes" AND artistname:"\!\!\!"` | 6 | Myth Takes — !!!; Myth Takes — !!!; Myth Takes — !!! |
| `release-bang-strict.json` | `release:"Myht Takes" AND artistname:"\!\!\!"` | 0 | none |
| `release-bang-current.json` | `release:myht~ AND release:takes~` | 10 | Myth Takes — !!!; Myth Takes — !!!; Myth Takes — !!! |
| `release-bang-term.json` | `release:myht~ AND release:takes~ AND artistname:\!\!\!~` | 6 | Myth Takes — !!!; Myth Takes — !!!; Myth Takes — !!! |
| `release-bang-phrase.json` | `release:myht~ AND release:takes~ AND artistname:"\!\!\!"` | 6 | Myth Takes — !!!; Myth Takes — !!!; Myth Takes — !!! |
| `recording-plus-minus-correct.json` | `recording:"Banging the Drum" AND artistname:"\+\/\-"` | 2 | Banging the Drum — +/-; Banging the Drum — +/- {PLUS/MINUS} vs. Bloodthirsty Butchers |
| `recording-plus-minus-strict.json` | `recording:"Bangign the Drum" AND artistname:"\+\/\-"` | 0 | none |
| `recording-plus-minus-current.json` | `recording:bangign~ AND recording:the AND recording:drum~` | 23 | Banging the Drum' — The Tribe; Banging the Drum — +/-; Banging the Drum — Scream |
| `recording-plus-minus-term.json` | `recording:bangign~ AND recording:the AND recording:drum~ AND artistname:\+\/\-~` | 2 | Banging the Drum — +/-; Banging the Drum — +/- {PLUS/MINUS} vs. Bloodthirsty Butchers |
| `recording-plus-minus-phrase.json` | `recording:bangign~ AND recording:the AND recording:drum~ AND artistname:"\+\/\-"` | 2 | Banging the Drum — +/-; Banging the Drum — +/- {PLUS/MINUS} vs. Bloodthirsty Butchers |
| `release-plus-minus-correct.json` | `release:"Holding Patterns" AND artistname:"\+\/\-"` | 2 | Holding Patterns — +/-; Holding Patterns — +/- |
| `release-plus-minus-strict.json` | `release:"Holdign Patterns" AND artistname:"\+\/\-"` | 0 | none |
| `release-plus-minus-current.json` | `release:holdign~ AND release:patterns~` | 33 | Holding Patterns — Amanda Rheaume; Holding Patterns — Tangled Memories; Holding Patterns — Parallel Lions |
| `release-plus-minus-term.json` | `release:holdign~ AND release:patterns~ AND artistname:\+\/\-~` | 2 | Holding Patterns — +/-; Holding Patterns — +/- |
| `release-plus-minus-phrase.json` | `release:holdign~ AND release:patterns~ AND artistname:"\+\/\-"` | 2 | Holding Patterns — +/-; Holding Patterns — +/- |
| `recording-radiohead-strict.json` | `recording:"Krama Police" AND artistname:"Radiohead"` | 0 | none |
| `recording-radiohead-words.json` | `recording:krama~ AND recording:police~ AND artistname:radiohead~` | 281 | Karma Police — Radiohead; Karma Police — Radiohead; Karma Police — Radiohead |
| `release-radiohead-strict.json` | `release:"Krama Police" AND artistname:"Radiohead"` | 0 | none |
| `release-radiohead-words.json` | `release:krama~ AND release:police~ AND artistname:radiohead~` | 9 | Karma Police — Radiohead; Karma Police — Radiohead; Karma Police — Radiohead |
