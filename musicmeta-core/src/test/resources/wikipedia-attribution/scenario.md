# Wikipedia selected-file metadata capture

Captured before implementation on 2026-10-07 from the public Wikimedia endpoints. These are
upstream bytes, not merge evidence and not a live test.

| Request | File | SHA-256 |
|---|---|---|
| Action API `extracts|pageimages|pageprops`, `titles=Radiohead` | `radiohead-extract.json` | `1120c5fc6435525e0bdedb07d31acecd2cc2b5d0b521a763e40502bc37a88124` |
| REST `page/media-list/Radiohead` | `radiohead-media-list.json` | `eeca2cacf1f332267b763d2487f8ebdcbaeead9d3ba5954b730c56ca7ab8715e` |
| Action API `prop=imageinfo&iiprop=url|extmetadata`, selected `File:RadioheadO2211125_composite.jpg` | `selected-imageinfo.json` | `153f0688b34c1ca9b5c279990b509ef23c5575bcc3f7bc9a3331ee2336171a0b` |

`selected-imageinfo.json.gz.base64` is the complete selected-imageinfo response, gzip-compressed
then base64 encoded without transformation. Decode it before comparing the recorded SHA-256.

The selected-file response identifies a Commons description page and has HTML in `Credit` and
`Artist`. Its page record also carries `missing: true` after title normalization while still
containing `imageinfo`; consumers must use the selected file's returned canonical title and
metadata, rather than treating article policy as a media licence.

The extract and media-list hashes document the capture session; only the complete selected-file
response is stored in this directory. The media-selection tests retain their pre-existing 2026-08-12
captures and synthetic ordering/rendering variants. They exercise the internal API and mapper
while the public photo result remains quarantined.

The stored response reports CC BY 4.0 and AttributionRequired=true but omits NonFree. The parser
retains that omission as null; the reuse policy therefore withholds this record. Tests based on
the unmodified stored response establish the parser's agreement with those captured bytes only.
Public-domain, custom-attribution, restrictions, redirects, malformed HTML and unsafe-link cases
are explicitly synthetic variants of this capture, not assertions about a live file.

[CommonsMetadata's returned-data documentation](https://www.mediawiki.org/wiki/Extension:CommonsMetadata#Returned_data)
states that the licence fields are unreliable for multi-licensed images, and describes License as
a best guess. LicenseShortName and LicenseUrl supply the represented designation and link; a
conflicting, combined or absent License code prevents acceptance. This conservative check does
not establish that an apparently single licence is complete. ARTIST_PHOTO remains quarantined,
including files whose metadata passes the internal policy. No article-text licence is used to
establish image rights.
