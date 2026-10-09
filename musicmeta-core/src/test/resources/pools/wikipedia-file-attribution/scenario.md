# wikipedia-file-attribution

Live captures of the one request `WikipediaProvider` makes after it has chosen an `ARTIST_PHOTO`: the
Action API `imageinfo` answer for the chosen file, plus the article's `media-list` the file is
chosen from. They are the evidence for what `extmetadata` really holds, so the mapping in
`WikipediaFileAttribution.kt` and the tests over it are written against these bytes and not against
a shape guessed from the documentation.

## Provenance

Captured 2026-10-08, before the code that reads them. Each `imageinfo-*.json` is the URL
`WikipediaApi.fileInfoUrl` builds, parameter for parameter, with the file title in `titles`:

    curl -sS -A 'musicmeta-dev/0.x (<contact>)' \
      'https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1&prop=imageinfo&iiprop=url%7Cextmetadata&iiextmetadatalanguage=en&iiextmetadatafilter=Artist%7CCredit%7CAttribution%7CLicenseShortName%7CLicenseUrl%7CUsageTerms%7CLicense%7CCopyrighted%7CRestrictions%7CNonFree&titles=<File%3A...>'

`radiohead-media-list.json` is `curl -sS -A '…' https://en.wikipedia.org/api/rest_v1/page/media-list/Radiohead`.
Every file is untrimmed and unedited, plus a trailing newline. The `iiextmetadatafilter` is what keeps
the answers small: it leaves out `Categories`, `ImageDescription` and the rest.

| File | Title requested | What it shows |
|---|---|---|
| `imageinfo-radiohead-lead.json` | `File:RadioheadO2211125_composite.jpg` | The Radiohead article's lead image: `Artist` Raph_PH, `CC BY 4.0`, a `Credit` that is a whole HTML gallery. A Commons file (`imagerepository: shared`) read through English Wikipedia |
| `imageinfo-public-domain.json` | `File:Albert Einstein Head.jpg` | `Public domain`, `Copyrighted` `False`, no `LicenseUrl`; the `Artist` is HTML and says the image was modified |
| `imageinfo-custom-attribution.json` | `File:Arena AufSchalke Innen bei Konzert.jpg` | The only capture with an `Attribution` field (`© … / CC BY-SA 4.0 (via Wikimedia Commons)`), beside `Artist` and `Credit` |
| `imageinfo-multi-licensed.json` | `File:16-03-30-Ben Gurion International Airport-RalfR-DSCF7550.jpg` | A file Commons lists under two licences (see below). The answer states one, `GFDL 1.2`, and its `LicenseUrl` is `http://`, not `https://` |
| `imageinfo-personality-restricted.json` | `File:Blind accordion player.jpg` | `CC BY-SA 3.0` with `Restrictions` `personality` |
| `imageinfo-non-free.json` | `File:Radiohead - OKNOTOK - Physical Album Cover Art.jpg` | A local English Wikipedia file (`imagerepository: local`): `Fair use`, `NonFree` `true`, no `LicenseUrl`, `UsageTerms` as HTML |
| `imageinfo-missing-file.json` | `File:OK Computer OKCOMPUTER.png` | A title no wiki holds: a page marked `missing` with no `imageinfo` at all |
| `radiohead-media-list.json` | `Radiohead` | The article's media list, lead image first |

## What the captures say about multi-licensed files

`imageinfo-multi-licensed.json` carries no marker that the file has a second licence. Its
`Categories` field, left out of the capture by the filter, does: asked for on its own on the same day it begins
`Taken with Fujifilm X-M1|GFDL-1.2|FAL`. Wikimedia documents the licence fields as unreliable for
multi-licensed files (mediawiki.org, Extension:CommonsMetadata), and a scan of 350 Commons files chosen
for carrying several licence templates found no `LicenseShortName`, `UsageTerms` or `License` that named
more than one licence. So the mapping reports the one licence the answer states, keeps its text as written,
and builds no `otherLicences`.

## Derived cases

The captures cannot show an answer that lacks fields, contradicts itself or carries an unsafe link, and no
live file does so on demand. The tests build these by editing a copy of `imageinfo-radiohead-lead.json` in
the test, and each is marked `(derived)` in its `Given`: extmetadata removed, only an `Artist`, an
unfamiliar licence, a licence statement naming two licences, contradictory flags, licence fallback to
`UsageTerms` and `License`, nested markup with entities, `javascript:` and protocol-relative links, and a
JSON `null` value. `WikipediaPhotoAttributionTest` also derives a copy with `extmetadata` removed and
sends non-JSON, `404`, `429`, `500`, an `IOException` and an Action API `maxlag` body as the `imageinfo`
answer. No derived case is evidence of what Wikimedia sends; each is evidence of what the library does
with it.
