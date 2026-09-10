# Security Policy

## Reporting a vulnerability

Report privately through GitHub's [security advisory
form](https://github.com/famesjranko/musicmeta/security/advisories/new) — not a public issue, and
not a pull request, either of which discloses the problem before there is a fix.

Useful in a report: the affected version and module, what an attacker can do, and the smallest
reproduction you have. If you are unsure whether something qualifies, report it anyway.

Expect an acknowledgement within a week. This is a single-maintainer project, so a fix timeline
depends on severity and on whether the cause is in this library or upstream. You will be told which.

## Supported versions

Pre-1.0, only the **latest released minor** gets fixes. A fix ships as the next patch or minor on
`main`; older `0.x` lines are not backported. Both channels carry the same artifacts —
[Maven Central](https://central.sonatype.com/artifact/io.github.famesjranko/musicmeta-core) and
[JitPack](https://jitpack.io/#famesjranko/musicmeta).

## In scope

- Credential handling: how a key a consumer supplies through `ApiKeyConfig` reaches a provider, and
  anything that could put one into a log, an exception message, a cache entry, or a URL that gets
  recorded. (`secrets.properties` is a convenience for this repo's own build and demos, not
  something the published library reads.)
- The cache: `EnrichmentCacheDatabase` and the `@Serializable` cache types, including anything that
  lets untrusted upstream content reach a consumer's storage in a form it does not expect.
- Parsing of upstream responses: a malformed or hostile response that crashes a consumer,
  exhausts memory, or escapes the type it was parsed into.
- The HTTP layer: URL construction, redirect handling, and TLS behaviour in `DefaultHttpClient` and
  the OkHttp adapter.

## Out of scope

- Vulnerabilities in the eleven upstream provider APIs themselves. Report those to the provider;
  [docs/providers.md](docs/providers.md) links each one. If musicmeta's *handling* of a bad response
  is the problem, that is in scope and belongs here.
- The demos (`demo-cli/`, `demo-web/`). They exist to exercise the library and are not published
  artifacts — but say so if you find something there, because it may mirror a real defect in
  `musicmeta-core`.
- Content a provider returns. This library reports what upstream says; it does not vouch for it.

## A note on API keys

Several providers put the key in the query string. Redact URLs, not just config values, before
pasting anything into a report, an issue or a log excerpt.
