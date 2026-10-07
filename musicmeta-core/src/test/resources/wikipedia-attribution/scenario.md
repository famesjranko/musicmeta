# Wikipedia selected-file metadata capture

Captured before implementation on 2026-10-07 from the public Wikimedia endpoints. These are
upstream bytes, not merge evidence and not a live test.

| Request | File | SHA-256 |
|---|---|---|
| Action API `extracts|pageimages|pageprops`, `titles=Radiohead` | `radiohead-extract.json` | `1120c5fc6435525e0bdedb07d31acecd2cc2b5d0b521a763e40502bc37a88124` |
| REST `page/media-list/Radiohead` | `radiohead-media-list.json` | `eeca2cacf1f332267b763d2487f8ebdcbaeead9d3ba5954b730c56ca7ab8715e` |
| Action API `prop=imageinfo&iiprop=url|extmetadata`, selected `File:RadioheadO2211125_composite.jpg` | `selected-imageinfo.json` | `153f0688b34c1ca9b5c279990b509ef23c5575bcc3f7bc9a3331ee2336171a0b` |

The selected-file response identifies a Commons description page and has HTML in `Credit` and
`Artist`. Its page record also carries `missing: true` after title normalization while still
containing `imageinfo`; consumers must use the selected file's returned canonical title and
metadata, rather than treating article policy as a media licence.
