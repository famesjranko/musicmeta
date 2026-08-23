import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  escapeHtml,
  creditLineHtml,
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
  for (const named of ['MusicBrainz', 'Cover Art Archive', 'Wikidata', 'Fanart.tv', 'ListenBrainz', 'LRCLIB', 'iTunes', 'Deezer']) {
    assert.match(html, new RegExp(named.replace('.', '\\.')));
  }
});

test('an iTunes credit is a text link like every other provider, to the store page it was given', () => {
  const html = creditLineHtml([{ provider: 'itunes', url: 'https://music.apple.com/us/album/ok-computer/1097861387' }]);
  assert.match(html, /iTunes/);
  assert.match(html, /href="https:\/\/music\.apple\.com\/us\/album\/ok-computer\/1097861387"/);
  assert.doesNotMatch(html, /<img/);
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

// --- Standing notices ----------------------------------------------------------------------
// Some notices are owed by the page as a whole rather than by one rendered item, and musicmeta's
// own policy snapshot does not carry them. Deezer's terms of use IV obliges the developer to
// inform anyone reaching the content through the page that streaming is private-family-scope, and
// this is the only place the page says it: playing a preview states nothing of its own.

test('a page that can reach Deezer states the private-use notice for as long as the page stands', () => {
  assert.ok(standingNotices(['deezer', 'musicbrainz']).some((n) => /strictly private use within a family scope/.test(n)));
});

test('a page with no Deezer provider owes no Deezer notice', () => {
  assert.deepEqual(standingNotices(['musicbrainz', 'wikipedia']), []);
});
