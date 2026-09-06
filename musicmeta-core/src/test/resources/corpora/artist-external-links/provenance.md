# Artist external-links corpus

`musicbrainz-changg-url-rels.json` — a live capture from MusicBrainz, taken **2026-09-07, before
the site-label derivation it is evidence for was written**:

    curl -H 'User-Agent: musicmeta-dev/0.1 (andrewmcdonald42@gmail.com)' \
      'https://musicbrainz.org/ws/2/artist/8b3a264b-aafc-46b9-84cc-6ede9ca17349?inc=url-rels&fmt=json'

The artist is `Changg`, the act the owner hit on the public demo: five URL relations whose types
name the *relationship* (`free streaming`, `social network` twice, `soundcloud`, `youtube`) and
whose hosts name the site (`open.spotify.com`, `www.facebook.com`, `www.instagram.com`,
`soundcloud.com`, `www.youtube.com`). Two rows sharing one type is the whole reason the type is not
a site name.

Trimmed, not edited: the artist's own biographical fields, `area`, `life-span`, `tags` and each
relation's empty `attribute*`/`*-credit` members were dropped; every field name and value kept here
is verbatim from the response, and the relations are in the order MusicBrainz returned them. The
fields the parser reads (`relations`, `target-type`, `type`, `url.resource`) are all present.

Live MusicBrainz field names are exercised by the daily non-gating `provider-drift.yml` job, the
same argument the pools under `../../pools/` make.
