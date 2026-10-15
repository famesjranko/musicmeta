import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  escapeHtml,
  creditLineHtml,
  imageCreditBadgeHtml,
  plainText,
  safeHref,
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

test('a labelled line says what it credits, ahead of the providers', () => {
  const html = creditLineHtml([{ provider: 'deezer' }], 'Photo');
  assert.match(html, /<span class="credit-label">Photo<\/span>/);
  assert.ok(html.indexOf('Photo') < html.indexOf('Deezer'));
});

test('a label on nothing renders nothing — no orphan label', () => {
  assert.equal(creditLineHtml([], 'Genres'), '');
});

test('a label is escaped like any other untrusted text', () => {
  const html = creditLineHtml([{ provider: 'deezer' }], '<img src=x onerror=alert(1)>');
  assert.doesNotMatch(html, /<img/);
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

// --- Safe links ----------------------------------------------------------------------------

test('only a plain https link with no userinfo is safe to click', () => {
  assert.equal(safeHref('https://commons.wikimedia.org/wiki/File:A.jpg'), 'https://commons.wikimedia.org/wiki/File:A.jpg');
  for (const unsafe of [
    'http://commons.wikimedia.org/wiki/File:A.jpg',
    'javascript:alert(1)',
    'java\tscript:alert(1)',
    'data:text/html,hi',
    'https://user:pw@commons.wikimedia.org/',
    'https://user@commons.wikimedia.org/',
    'https://commons.wikimedia.org/\u202Eevil',
    'https://commons.wikimedia.org/\u0000',
    '//commons.wikimedia.org/wiki/File:A.jpg',
    '',
    null,
  ]) {
    assert.equal(safeHref(unsafe), null, String(unsafe));
  }
});

test('an unsafe text-credit link renders the provider as plain text and keeps the licence', () => {
  const html = creditLineHtml([{ provider: 'wikipedia', url: 'javascript:alert(1)' }]);
  assert.doesNotMatch(html, /javascript/);
  assert.match(html, /Text from Wikipedia/);
  assert.match(html, /CC BY-SA 4\.0/);
});

// --- Image credit --------------------------------------------------------------------------
// The "i" control describes the one file beside it. It renders what the response carried and
// decides nothing: every state of the metadata still gets a control, and the image is not its call.

const FILE = {
  title: 'File:Thom Yorke.jpg',
  sourceUrl: 'https://commons.wikimedia.org/wiki/File:Thom_Yorke.jpg',
  creator: '<a href="//commons.wikimedia.org/wiki/User:X">Jane Doe</a>',
  licence: 'CC BY-SA 4.0',
  licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
};

test('no image credit renders no control', () => {
  assert.equal(imageCreditBadgeHtml(null), '');
  assert.equal(imageCreditBadgeHtml(undefined), '');
});

test('a control is a button a keyboard reaches, with the popover beside it', () => {
  const html = imageCreditBadgeHtml({ provider: 'wikipedia', attribution: FILE });
  assert.match(html, /<button type="button" class="img-credit-btn" aria-label="Image credit" aria-expanded="false">i<\/button>/);
  assert.match(html, /class="img-credit-pop"/);
});

test('a Wikipedia photo with file facts shows its creator, description page and licence, not the text credit', () => {
  const html = imageCreditBadgeHtml({ provider: 'wikipedia', attribution: FILE });
  assert.match(html, /By Jane Doe/);
  assert.match(html, /href="https:\/\/commons\.wikimedia\.org\/wiki\/File:Thom_Yorke\.jpg"[^>]*>File:Thom Yorke\.jpg</);
  assert.match(html, /Licence: <a href="https:\/\/creativecommons\.org\/licenses\/by-sa\/4\.0\/"[^>]*>CC BY-SA 4\.0<\/a>/);
  assert.doesNotMatch(html, /Text from/);
});

test('a Wikipedia photo with no file facts names the provider and claims no licence', () => {
  const html = imageCreditBadgeHtml({ provider: 'wikipedia', url: 'https://en.wikipedia.org/wiki/Radiohead' });
  assert.match(html, /Image via <a href="https:\/\/en\.wikipedia\.org\/wiki\/Radiohead"[^>]*>Wikipedia<\/a>/);
  assert.doesNotMatch(html, /Text from|CC BY|[Ll]icen[cs]e/);
});

test('a Wikidata image with no file facts is credited to Wikidata, not to Wikipedia text', () => {
  const html = imageCreditBadgeHtml({ provider: 'wikidata' });
  assert.match(html, /Image via <a href="https:\/\/www\.wikidata\.org\/"[^>]*>Wikidata<\/a>/);
  assert.doesNotMatch(html, /Wikipedia|CC BY|[Ll]icen[cs]e/);
});

test('a partial attribution shows the facts it has and nothing it lacks', () => {
  const html = imageCreditBadgeHtml({ provider: 'wikipedia', attribution: { creator: 'Jane Doe' } });
  assert.match(html, /By Jane Doe/);
  assert.match(html, /Image via/);
  assert.doesNotMatch(html, /[Ll]icen[cs]e|Modified|Restrictions|Copyright/);
});

test('the upstream preferred credit text comes ahead of the creator and a differing credit line is kept', () => {
  const html = imageCreditBadgeHtml({
    provider: 'wikipedia',
    attribution: { creator: 'J. Doe', attributionText: 'Photo: Jane Doe / Agency', credit: 'Agency archive' },
  });
  assert.match(html, /By Photo: Jane Doe \/ Agency/);
  assert.doesNotMatch(html, /J\. Doe/);
  assert.match(html, /Credit: Agency archive/);
});

test('restrictive and contradictory facts are shown as the upstream gave them, none dropped', () => {
  const html = imageCreditBadgeHtml({
    provider: 'wikipedia',
    attribution: {
      licence: 'Public domain',
      otherLicences: ['CC BY-NC 4.0'],
      copyrightStatus: 'True',
      modification: 'Cropped',
      restrictions: ['No commercial use', 'Personality rights'],
    },
  });
  assert.match(html, /Licence: Public domain, CC BY-NC 4\.0/);
  assert.match(html, /Copyright: True/);
  assert.match(html, /Modified: Cropped/);
  assert.match(html, /Restrictions: No commercial use; Personality rights/);
});

test('unsafe links render as plain text and every other fact still shows', () => {
  const html = imageCreditBadgeHtml({
    provider: 'wikipedia',
    url: 'javascript:alert(1)',
    attribution: {
      title: 'File:A.jpg',
      sourceUrl: 'http://commons.wikimedia.org/wiki/File:A.jpg',
      creator: 'Jane Doe',
      licence: 'CC BY 4.0',
      licenceUrl: 'https://user:pw@creativecommons.org/licenses/by/4.0/',
    },
  });
  assert.doesNotMatch(html, /<a |href|javascript/);
  assert.match(html, /File:A\.jpg/);
  assert.match(html, /By Jane Doe/);
  assert.match(html, /Licence: CC BY 4\.0/);
  assert.match(html, /Image via Wikipedia/);
});

test('hostile attribution text is escaped and markup in a creator is reduced to its words', () => {
  const html = imageCreditBadgeHtml({
    provider: '<b>p</b>',
    attribution: { creator: '<img src=x onerror=alert(1)>Eve &amp; Co', licence: '&lt;script&gt;alert(1)&lt;/script&gt;' },
  });
  assert.doesNotMatch(html, /<img|<script|<b>/);
  assert.match(html, /By Eve &amp; Co/);
  assert.match(html, /&lt;script&gt;/);
});

test('plainText strips tags and decodes entities once', () => {
  assert.equal(plainText('<a href="x">Ann &amp; Bob</a>'), 'Ann & Bob');
  // A second decode would turn this escaped markup into the live tag `<b>`.
  assert.equal(plainText('&amp;lt;b&amp;gt;'), '&lt;b&gt;');
});
