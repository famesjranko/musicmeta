import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  escapeHtml,
  creditLineHtml,
  previewNoticeHtml,
  previewNoticeText,
  standingNotices,
} from '../../main/resources/attribution.js';

// --- Credit lines --------------------------------------------------------------------------
// Every chip is built from the provenance the response carried, so a provider that answered
// nothing can never be credited and one that answered cannot be missed.

test('no credits render nothing at all, not an empty container', () => {
  assert.equal(creditLineHtml([]), '');
  assert.equal(creditLineHtml(undefined), '');
});

test("Discogs renders its required wording, hyperlinked to the page the data came from", () => {
  const html = creditLineHtml([{ provider: 'discogs', url: 'https://www.discogs.com/artist/18839' }]);
  assert.match(html, /Data provided by Discogs/);
  assert.match(html, /href="https:\/\/www\.discogs\.com\/artist\/18839"/);
});

test('a Discogs link is never nofollowed — their terms forbid it', () => {
  const html = creditLineHtml([{ provider: 'discogs', url: 'https://www.discogs.com/artist/18839' }]);
  assert.doesNotMatch(html, /nofollow/);
});

test('a credit with no link-back still credits the provider, linking its own site', () => {
  const html = creditLineHtml([{ provider: 'discogs' }]);
  assert.match(html, /Data provided by Discogs/);
  assert.match(html, /href="https:\/\/www\.discogs\.com\/"/);
});

test('Wikipedia text carries its licence and a link to the article it came from', () => {
  const html = creditLineHtml([{ provider: 'wikipedia', url: 'https://en.wikipedia.org/wiki/Radiohead' }]);
  assert.match(html, /Wikipedia/);
  assert.match(html, /CC BY-SA 4\.0/);
  assert.match(html, /href="https:\/\/en\.wikipedia\.org\/wiki\/Radiohead"/);
  assert.match(html, /creativecommons\.org\/licenses\/by-sa\/4\.0/);
});

test('Last.fm carries the AudioScrobbler badge and a link back to its catalogue page', () => {
  const html = creditLineHtml([{ provider: 'lastfm', url: 'https://www.last.fm/music/Radiohead' }]);
  assert.match(html, /powered by AudioScrobbler/i);
  assert.match(html, /href="https:\/\/www\.last\.fm\/music\/Radiohead"/);
});

test('each shipped provider is credited by name rather than by its bare id', () => {
  const ids = ['musicbrainz', 'coverartarchive', 'wikidata', 'fanarttv', 'listenbrainz', 'lrclib', 'itunes', 'deezer'];
  const html = creditLineHtml(ids.map((provider) => ({ provider })));
  for (const named of ['MusicBrainz', 'Cover Art Archive', 'Wikidata', 'Fanart.tv', 'ListenBrainz', 'LRCLIB', 'Deezer']) {
    assert.match(html, new RegExp(named.replace('.', '\\.')));
  }
  // iTunes is named by its badge's alt text — the artwork is the credit, per Apple's guidelines.
  assert.match(html, /alt="Listen on Apple Music"/);
});

test('an iTunes credit renders the official badge, linked to the store page it was given', () => {
  const html = creditLineHtml([{ provider: 'itunes', url: 'https://music.apple.com/us/album/ok-computer/1097861387' }]);
  assert.match(html, /<img[^>]*src="\/apple-music-badge\.svg"/);
  assert.match(html, /alt="Listen on Apple Music"/);
  assert.match(html, /href="https:\/\/music\.apple\.com\/us\/album\/ok-computer\/1097861387"/);
});

test('an iTunes credit with no store link still shows the badge, linking the storefront', () => {
  const html = creditLineHtml([{ provider: 'itunes' }]);
  assert.match(html, /<img[^>]*src="\/apple-music-badge\.svg"/);
  assert.match(html, /href="https:\/\/music\.apple\.com\/"/);
});

test('the same provider credited twice is credited once', () => {
  const html = creditLineHtml([
    { provider: 'musicbrainz', url: 'https://musicbrainz.org/artist/a74b1b7f' },
    { provider: 'musicbrainz' },
  ]);
  assert.equal(html.match(/MusicBrainz/g).length, 1);
});

test("a provider the table doesn't know is still credited, by id, without an invented link", () => {
  const html = creditLineHtml([{ provider: 'somebodys-own-provider' }]);
  assert.match(html, /somebodys-own-provider/);
  assert.doesNotMatch(html, /href/);
});

test('a merger id is not a credit — it names no upstream', () => {
  assert.equal(creditLineHtml([{ provider: 'genre_merger' }]), '');
});

test('a hostile provider id and url cannot inject markup', () => {
  const html = creditLineHtml([{ provider: '<img src=x onerror=alert(1)>', url: '"><script>alert(1)</script>' }]);
  assert.doesNotMatch(html, /<img|<script/);
});

test('escapeHtml escapes every character that can break out of markup', () => {
  assert.equal(escapeHtml(`<&">'`), '&lt;&amp;&quot;&gt;&#39;');
});

// --- Preview notices -----------------------------------------------------------------------
// Deezer's terms of use IV obliges the developer to inform anyone reaching the content through
// the page that streaming is private-family-scope. It is owed at the player, where the recording
// is actually playable, and is driven by the source the preview resolved from.

test('a Deezer preview carries the private-use notice in its own words', () => {
  const html = previewNoticeHtml('deezer');
  assert.match(html, /strictly private use within a family scope/);
});

test("the Deezer notice names Deezer as the preview's source", () => {
  assert.match(previewNoticeHtml('deezer'), /Deezer/);
});

test('the notice is also available as plain text, for the player button tooltip', () => {
  assert.match(previewNoticeText('deezer'), /strictly private use within a family scope/);
  assert.doesNotMatch(previewNoticeText('deezer'), /</);
});

test('an Apple-sourced preview carries the courtesy attribution Apple requires', () => {
  assert.match(previewNoticeHtml('itunes'), /provided courtesy of iTunes/);
});

test('a preview from a provider owing no notice renders none', () => {
  assert.equal(previewNoticeHtml('somebodys-own-provider'), '');
  assert.equal(previewNoticeHtml(null), '');
  assert.equal(previewNoticeText(null), '');
});

// --- Standing notices ----------------------------------------------------------------------
// Some notices are owed by the page as a whole rather than by one rendered item, and musicmeta's
// own policy snapshot does not carry them.

test('a page that can reach Deezer states the private-use notice standing, not only at the player', () => {
  assert.ok(standingNotices(['deezer', 'musicbrainz']).some((n) => /strictly private use within a family scope/.test(n)));
});

test('a page with no Deezer provider owes no Deezer notice', () => {
  assert.deepEqual(standingNotices(['musicbrainz', 'wikipedia']), []);
});
