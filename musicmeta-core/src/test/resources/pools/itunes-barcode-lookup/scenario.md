# itunes-barcode-lookup

A request for the album `Discovery` / `Daft Punk` carries a UPC barcode. iTunes resolves a barcode
by a direct `/lookup?upc=` identifier call, never a search — `ALBUM_METADATA` is a genuine `Success`
reporting `LookupProvenance.EXTERNAL_CATALOG_ID`.

**Why this pool exists.** It is the ID-route half of the provenance route cross-check
(`ProviderProvenanceContract`'s route cross-check test): an ID route (`CANONICAL_ID`,
`PROVIDER_NATIVE_ID`, `EXTERNAL_CATALOG_ID`) must imply an identifier endpoint was requested, and
`lrclib-qualifier-match` only exercises the name-route half. `EXTERNAL_CATALOG_ID` is self-reported
by `ITunesProvider` on the barcode branch (`ITunesProvider.kt`), not derived by the engine, so this
is also a case `stampProvenance`/`stampContributorProvenance` must leave untouched.

## Provenance

`itunes-upc-lookup.json` is trimmed from `UPC_LOOKUP_DISCOVERY` in
`musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/provider/itunes/ITunesProviderTest.kt:996-1004`
— **that file's origin is unverified**, same caveat the LRCLIB pools record. `wrapperType` is kept:
`ITunesApi.lookupByUpc` filters on `wrapperType == "collection"` before parsing, so dropping it (an
earlier trim did) makes every barcode hit silently invisible. `collectionType` and `artistId` were
dropped — `ITunesApi.parseAlbumResult` never reads either — and `artworkUrl100` is an artwork/preview
URL the legal constraint forbids regardless of whether a test reads it. No surviving field name or
value was changed from the source fixture. Trimmed 2026-08-16.

iTunes' actual field names are exercised against the live API by the daily `provider-drift.yml` job
(non-gating), same as the LRCLIB pools record.
