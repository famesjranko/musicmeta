# Wikipedia attribution regression evidence

All commands ran offline in this isolated worktree. Live APIs are not merge evidence.
The fixture hash and synthetic-variant limitations are in scenario.md.

## Commands

`W = ./gradlew :musicmeta-core:test --tests '*Wikipedia*' --tests '*ProviderTransientFailureTest' -Pmusicmeta.rerunTests --console=plain`

Every mutation below was applied to production source, tested with W, removed, and tested with W again.
The restored runs executed the test task; the rerunTests property prevents test-cache substitution.
Compilation failures are excluded from the evidence. The compiling original-parser check restores
WikipediaApi from 5edadf6 while leaving appended DTO fields at their null defaults.

## original-parser

Command: W

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > missing metadata is safe absence while malformed response is a protocol error FAILED
WikipediaAttributionPolicyTest > metadata transport and Action API errors stay bounded protocol failures FAILED
WikipediaAttributionPolicyTest > invalid boolean values remain unknown FAILED
WikipediaAttributionPolicyTest > missing and malformed metadata values never assert unrestricted reuse FAILED
WikipediaAttributionPolicyTest > public domain honors explicit false without requiring a creator FAILED
WikipediaAttributionPolicyTest > malformed HTML entities and controls become safe plain text FAILED
WikipediaAttributionPolicyTest > file metadata rejects empty multiple and control character titles before transport FAILED
WikipediaAttributionPolicyTest > local repository redirects and multilingual canonical titles retain selected identity FAILED
WikipediaAttributionPolicyTest > attribution requirement retains explicit true false and unknown values FAILED
WikipediaAttributionPolicyTest > unsafe credential control and non HTTPS links are rejected FAILED
98 tests completed, 10 failed, 5 skipped
BUILD FAILED in 14s
```

## original-policy

Command: `./gradlew :musicmeta-core:test --tests '*WikipediaAttributionPolicyTest' -Pmusicmeta.rerunTests --console=plain`

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > custom Attribution overrides artist credit without changing reported requirement FAILED
WikipediaAttributionPolicyTest > missing metadata is safe absence while malformed response is a protocol error FAILED
WikipediaAttributionPolicyTest > invalid boolean values remain unknown FAILED
WikipediaAttributionPolicyTest > malformed HTML entities and controls become safe plain text FAILED
WikipediaAttributionPolicyTest > ambiguous multiple and inconsistent licenses fail closed FAILED
WikipediaAttributionPolicyTest > restricted nonfree and unknown records cannot become reusable attribution FAILED
WikipediaAttributionPolicyTest > captured selected Commons file retains canonical identity and reported flags FAILED
WikipediaAttributionPolicyTest > unsafe credential control and non HTTPS links are rejected FAILED
11 tests completed, 8 failed
BUILD FAILED in 2s
```

## original-canonical

Command: `./gradlew :musicmeta-core:test --tests '*WikipediaAttributionPolicyTest' -Pmusicmeta.rerunTests --console=plain`

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > oversized metadata and invalid canonical identity cannot become credit FAILED
17 tests completed, 1 failed
BUILD FAILED in 2s
```

## original-usage

Command: `./gradlew :musicmeta-core:test --tests '*WikipediaAttributionPolicyTest' -Pmusicmeta.rerunTests --console=plain`

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > contradictory usage terms cannot turn a recognized license into reusable credit FAILED
18 tests completed, 1 failed
BUILD FAILED in 3s
```

## Named mutations

### policy-bypass

Remove every file reuse guard after the source URL and licence checks.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > custom Attribution overrides artist credit without changing reported requirement FAILED
WikipediaAttributionPolicyTest > missing and malformed metadata values never assert unrestricted reuse FAILED
WikipediaAttributionPolicyTest > ambiguous multiple and inconsistent licenses fail closed FAILED
WikipediaAttributionPolicyTest > restricted nonfree and unknown records cannot become reusable attribution FAILED
WikipediaAttributionPolicyTest > captured selected Commons file retains canonical identity and reported flags FAILED
98 tests completed, 5 failed, 5 skipped
BUILD FAILED in 4s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### incomplete-policy

Also replace absent description-page and licence fields with invented defaults.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > custom Attribution overrides artist credit without changing reported requirement FAILED
WikipediaAttributionPolicyTest > missing and malformed metadata values never assert unrestricted reuse FAILED
WikipediaAttributionPolicyTest > ambiguous multiple and inconsistent licenses fail closed FAILED
WikipediaAttributionPolicyTest > restricted nonfree and unknown records cannot become reusable attribution FAILED
WikipediaAttributionPolicyTest > captured selected Commons file retains canonical identity and reported flags FAILED
WikipediaFileMetadataTest > file attribution refuses incomplete file identity or licence FAILED
98 tests completed, 6 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### drop-file-attribution

Return null for all File records rather than preserving valid file credit.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > public domain honors explicit false without requiring a creator FAILED
WikipediaAttributionPolicyTest > local repository redirects and multilingual canonical titles retain selected identity FAILED
WikipediaFileMetadataTest > file attribution prefers source Attribution over artist and credit FAILED
98 tests completed, 3 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 3s
```

### default-unknown-false

Return false instead of null for all unrecognized or missing boolean values.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > invalid boolean values remain unknown FAILED
WikipediaAttributionPolicyTest > restricted nonfree and unknown records cannot become reusable attribution FAILED
WikipediaAttributionPolicyTest > attribution requirement retains explicit true false and unknown values FAILED
WikipediaAttributionPolicyTest > captured selected Commons file retains canonical identity and reported flags FAILED
WikipediaFileMetadataTest > file metadata drops non HTTPS links and keeps unknown flags unknown FAILED
98 tests completed, 5 failed, 5 skipped
BUILD FAILED in 2s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### raw-html

Return metadata HTML unchanged before normalization.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > custom Attribution overrides artist credit without changing reported requirement FAILED
WikipediaAttributionPolicyTest > malformed HTML entities and controls become safe plain text FAILED
WikipediaAttributionPolicyTest > captured selected Commons file retains canonical identity and reported flags FAILED
WikipediaFileMetadataTest > file metadata uses the selected canonical title and safe extmetadata FAILED
98 tests completed, 4 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### drop-article-attribution-and-restore-thumbnail

Remove article attribution and restore the page-image thumbnail.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > article links encode reserved delimiters and Unicode title data FAILED
WikipediaProviderTest > enrich suppresses article thumbnail URL without file attribution FAILED
WikipediaProviderTest > enrich returns biography from the article extract FAILED
WikipediaProviderTest > enrich attaches the article attribution and suppresses its uncredited thumbnail FAILED
WikipediaProviderTest > enrich suppresses tracked bio thumbnails without file attribution FAILED
98 tests completed, 5 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### swallow-cancellation

Catch CancellationException around the HTTP operation and return null.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > metadata cancellation cannot become a normal return FAILED
98 tests completed, 1 failed, 5 skipped
BUILD FAILED in 13s
```

Restored green:

```text
BUILD SUCCESSFUL in 3s
```

### unencoded-file-title

Interpolate fileTitle without encodePathSegment in the request URL builder.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaFileMetadataTest > file metadata URL encodes multilingual selected file titles FAILED
98 tests completed, 1 failed, 5 skipped
BUILD FAILED in 2s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### accept-invalid-file-input

Remove the selected-file input precondition.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > file metadata rejects empty multiple and control character titles before transport FAILED
WikipediaFileMetadataTest > file metadata rejects an unsupported non File title FAILED
98 tests completed, 2 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### corrupt-renderings

Alter the mapped URL, width and height and drop all rendered sizes.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaMediaListSelectionTest > media parser strips the utm tracking parameters from every image URL FAILED
WikipediaMediaListSelectionTest > media parser keeps a source whose URL states no rendered width FAILED
WikipediaMediaListSelectionTest > media parser passes through a source that is already absolute FAILED
WikipediaMediaListSelectionTest > media parser picks the lead image at its largest offered scale FAILED
WikipediaMediaListSelectionTest > media parser offers every scale the article renders in sizes FAILED
WikipediaMediaListSelectionTest > media parser reports no height because the media list does not carry one FAILED
98 tests completed, 6 failed, 5 skipped
BUILD FAILED in 2s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### reverse-media-and-accept-narrow

Reverse the selected media order and remove the minimum-width filter.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaMediaListSelectionTest > media parser falls back to the first image when the article flags no lead FAILED
WikipediaMediaListSelectionTest > media parser prefers the lead image over an earlier non-lead photograph FAILED
98 tests completed, 2 failed, 5 skipped
BUILD FAILED in 2s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### invent-photo-for-empty-list

Replace the parsed media list with a fabricated image.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaMediaListSelectionTest > media parser keeps a source whose URL states no rendered width FAILED
WikipediaMediaListSelectionTest > media parser passes through a source that is already absolute FAILED
WikipediaMediaListSelectionTest > media parser returns NotFound when every image is filtered out FAILED
WikipediaMediaListSelectionTest > media parser falls back to the first image when the article flags no lead FAILED
WikipediaMediaListSelectionTest > media parser skips an image rendered below the minimum width FAILED
WikipediaMediaListSelectionTest > media parser prefers the lead image over an earlier non-lead photograph FAILED
WikipediaMediaListSelectionTest > media parser returns NotFound when the media list is empty FAILED
WikipediaMediaListSelectionTest > media parser picks the lead image at its largest offered scale FAILED
WikipediaMediaListSelectionTest > media parser offers every scale the article renders in sizes FAILED
98 tests completed, 9 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### accept-narrow

Remove only the minimum-width filter.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaMediaListSelectionTest > media parser skips an image rendered below the minimum width FAILED
98 tests completed, 1 failed, 5 skipped
BUILD FAILED in 2s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### lift-quarantine

Return a fabricated artwork Success from the quarantined direct photo route.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaMediaListSelectionTest > provider withholds photos even when a media route has a selected file FAILED
98 tests completed, 1 failed, 5 skipped
BUILD FAILED in 4s
```

Restored green:

```text
BUILD SUCCESSFUL in 2s
```

### unbounded-metadata

Remove the metadata-length bound.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > oversized metadata and invalid canonical identity cannot become credit FAILED
100 tests completed, 1 failed, 5 skipped
BUILD FAILED in 3s
```

Restored green:

```text
BUILD SUCCESSFUL in 3s
```

### ignore-usage-terms

Remove the conflicting UsageTerms check.

Command: W. Red:

```text
> Task :musicmeta-core:test FAILED
WikipediaAttributionPolicyTest > contradictory usage terms cannot turn a recognized license into reusable credit FAILED
100 tests completed, 1 failed, 5 skipped
BUILD FAILED in 4s
```

Restored green:

```text
BUILD SUCCESSFUL in 3s
```

Each newly added or changed test appears in at least one red excerpt above. Original-parser
failures additionally cover shape classification, explicit source flags, malformed restrictions,
bounded diagnostics, unsupported titles, unsafe URLs and local/Commons canonical identity.


## Acceptance and final verification

A1: W -> restored article-attribution tests pass; drop-article-attribution-and-restore-thumbnail -> the article notice and safe-link regressions fail.
A2: W -> canonical selected-file identity and custom Attribution tests pass; drop-file-attribution -> both fail. Public photo Success remains disabled.
A3: W -> public-domain, custom, missing, restricted, non-free, conflicting and multi-licence cases pass; policy-bypass -> five policy regressions fail.
A4: W -> explicit true/false/unknown AttributionRequired values are preserved; default-unknown-false -> the flag regression fails. Source LicenseShortName/LicenseUrl supply the returned designation/link. Multi-licence uncertainty remains quarantined.
A5: W -> the capability and direct legacy photo result are withheld; lift-quarantine -> provider withholds photos even when a media route has a selected file FAILED. No new Wikipedia image reaches a demo via this provider.
A6: base64 -d musicmeta-core/src/test/resources/wikipedia-attribution/selected-imageinfo.json.gz.base64 | gzip -dc | sha256sum -> 153f0688b34c1ca9b5c279990b509ef23c5575bcc3f7bc9a3331ee2336171a0b. Only the independent complete stored response warrants capture-based parsing assurance; adversarial variants are synthetic.
A7: W -> safe absence, visible protocol errors, bounded diagnostics, cancellation, encoded titles, safe HTML/HTTPS links and local/Commons canonical identity pass. original-parser and swallow-cancellation excerpts above show the corresponding reds.
A8: W -> biography thumbnails remain null; drop-article-attribution-and-restore-thumbnail -> all four affected provider tests fail. No dependency was added.
A9: All 36 methods in WikipediaAttributionPolicyTest, WikipediaFileMetadataTest and WikipediaMediaListSelectionTest, plus the four changed WikipediaProviderTest methods, have observed named red excerpts and restored-green runs above. Original compiling API/parser behavior was also exercised; newly appended fields retained nullable defaults for that run.
D1: W -> the approved fail-closed model/case policy passes. HTML is safe plain text, resource identity is retained, unknown flags remain nullable, and ARTIST_PHOTO remains quarantined because extmetadata cannot reliably establish complete multi-licensing.

Required final command:
`./gradlew :musicmeta-core:test --tests '*Wikipedia*' --tests '*ProviderTransientFailureTest'`
-> BUILD SUCCESSFUL in 14s; test reports contained 100 tests, zero failures and five existing live-test skips.

`python3 scripts/checks/check_route_pin_coverage.py` -> exit 0, no output.
`python3 scripts/checks/check_schema_pin_coverage.py` -> exit 0, no output.
`python3 scripts/checks/check_test_shape.py` -> Test shape clean across 281 test sources.
`git diff --check` -> exit 0, no output.

`./gradlew :musicmeta-core:detektMain :musicmeta-core:detektTest :musicmeta-core:ktlintMainSourceSetFormat :musicmeta-core:ktlintTestSourceSetFormat`
-> BUILD SUCCESSFUL in 45s.

`make check` -> module build, API/dependency checks and demo canary passed;
the docs-samples stage failed outside this worker's owned files:
`quick_start_narrative.kt:92:13 Unresolved reference 'similarAlbums'.`
The documentation task must reconcile this sample with the accepted artist-property removal
before the final integrated full check can pass. No Room, dependency, cache, demo or documentation
source was changed by this worker.
