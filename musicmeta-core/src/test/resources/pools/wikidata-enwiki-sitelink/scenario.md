# wikidata-enwiki-sitelink

Three answers from one route: `wbgetentities&props=sitelinks&sitefilter=enwiki`, the single request
`WikipediaProvider` makes of Wikidata when an artist arrives with a `wikidataId` and no
`wikipediaTitle`. `ARTIST_BIO`, `ARTIST_PHOTO` and `ALBUM_DESCRIPTION` all resolve their title
through it.

**Why this pool exists.** `WikidataApi.getEnwikiSitelink` reports four outcomes where the code it
replaced reported one null, and three of the four are claims about what this route really sends. A
hand-written fixture cannot be evidence for any of them, because each was written by reading the
same parse it would be checking:

- an entity **with** an English article carries `sitelinks.enwiki.title`;
- an entity **without** one carries `sitelinks` as `{}` — present and empty, not absent. That is the
  fact the whole distinction rests on: it is what makes an *absent* `sitelinks` object the route
  having moved rather than an artist with no page, and it is what the schema pin's
  `entities.Q44190.sitelinks` path can watch;
- an id Wikidata does not hold carries `entities.<id>` with a `missing` marker, `type` absent and no
  `sitelinks` at all — so "unknown id" has to be read off `missing`, not off the absent `sitelinks`
  it shares with a shape change.

## Provenance

Captured live on **2026-09-06** from the URL `WikidataApi.enwikiSitelinkUrl` builds, parameter for
parameter and in its order —
`https://www.wikidata.org/w/api.php?action=wbgetentities&ids=<id>&props=sitelinks&sitefilter=enwiki&format=json`
— one request per file, three ids:

| File | id | What it is |
|---|---|---|
| `wikidata-sitelink-radiohead.json` | `Q44190` | Radiohead, the entity the schema pin is aimed at |
| `wikidata-sitelink-no-english-article.json` | `Q43779` | Nick Woodland, a musician with a German article and no English one |
| `wikidata-sitelink-missing-entity.json` | `Q999999999` | an id no entity has |

**Untrimmed and unedited** — the whole response, byte for byte as Wikidata sent it, plus a trailing
newline. `sitefilter=enwiki` is what makes that affordable: the largest of the three is 150 bytes,
so there is nothing to cut and no line of a `scenario.md` standing between the reader and the bytes.

This route is also watched live by the daily `provider-drift.yml` job, which pins
`entities.Q44190.sitelinks` and `entities.Q44190.sitelinks.enwiki.title`. What that pin cannot
watch is the other two rows: a pin reads an empty `sitelinks` as absent and a `missing` marker as an
absent path, so a healthy answer for either would report as drift and neither can be aimed at. Only
these captures are evidence for how Wikidata says "no English article" and "no such entity".
