import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { providerWarning, typeWarning, warningHtml } from '../../main/resources/reachability.js';
import { loadPage } from './page-harness.js';

const REFUSED_DEEZER = {
  verdict: 'REFUSED', httpStatus: 403, host: 'api.deezer.com', checkedAt: '2026-10-09T15:24:07Z',
};
const UNREACHABLE_ITUNES = {
  verdict: 'UNREACHABLE', httpStatus: null, host: 'itunes.apple.com', checkedAt: '2026-10-09T15:24:08Z',
};
const REACHABLE = { verdict: 'REACHABLE', httpStatus: 200, host: 'musicbrainz.org', checkedAt: '2026-10-09T15:24:06Z' };
const UNCHECKED = { verdict: 'UNCHECKED', httpStatus: null, host: null, checkedAt: null };

const row = (id, displayName, reachability, capabilities = ['ARTIST_RADIO']) => ({
  id, displayName, available: true, requiresApiKey: false, capabilities, policy: null, keyStatus: null, reachability,
});

const DEEZER_SENTENCE = 'May be unreachable from this host: a startup check got HTTP 403 from api.deezer.com at 15:24 UTC. ' +
  'A limitation of where this demo runs, not of the library.';

// --- Settings table ------------------------------------------------------------------------

test('a refused provider gets the warning with the exact sentence in the settings table', async () => {
  // Given - the page with a provider list where Deezer was refused at startup
  const { settingsHtml } = await loadPage([row('deezer', 'Deezer', REFUSED_DEEZER)]);

  // When - the settings table has painted
  const html = settingsHtml();

  // Then - Deezer's row carries a focusable symbol whose accessible name and panel are the sentence
  assert.ok(html.includes(`aria-label="${DEEZER_SENTENCE}"`), html);
  assert.match(html, /<span class="host-warn" role="img" tabindex="0"/);
  assert.ok(html.includes(`>${DEEZER_SENTENCE}</span>`), html);
});

test('an unreachable provider gets the no-answer variant of the sentence', async () => {
  // Given - the page with a provider list where iTunes never answered
  const { settingsHtml } = await loadPage([row('itunes', 'iTunes', UNREACHABLE_ITUNES)]);

  // When - the settings table has painted
  const html = settingsHtml();

  // Then - the sentence says no answer (connection failed) from the host, at the probe's UTC time
  assert.ok(html.includes(
    'May be unreachable from this host: a startup check got no answer (connection failed) from itunes.apple.com at 15:24 UTC. ' +
    'A limitation of where this demo runs, not of the library.',
  ), html);
});

test('a reachable, unchecked or throttled provider gets no warning in the settings table', async () => {
  // Given - the page with providers that are reachable, unchecked, and one with no reachability object at all
  const { settingsHtml } = await loadPage([
    row('musicbrainz', 'MusicBrainz', REACHABLE),
    row('lastfm', 'Last.fm', UNCHECKED),
    { ...row('lrclib', 'LRCLIB', REACHABLE), reachability: undefined },
  ]);

  // When - the settings table has painted
  const html = settingsHtml();

  // Then - all three rows are there and none has the symbol
  for (const name of ['MusicBrainz', 'Last.fm', 'LRCLIB']) assert.ok(html.includes(name), name);
  assert.doesNotMatch(html, /host-warn/);
});

// --- Results status table ------------------------------------------------------------------

function response(providers) {
  return {
    kind: 'artist',
    name: 'Radiohead',
    summary: { title: 'Radiohead', genres: [], pendingSlots: [] },
    gallery: [],
    sections: [],
    meta: { elapsedMs: 10, providers, identifiers: [] },
  };
}

const RADIO_RESULT = [{ type: 'ARTIST_RADIO', provider: 'all_providers', status: 'not_found', confidence: null }];
const RADIO_SENTENCE = 'Deezer, the provider for this type, may be unreachable from this host: ' +
  'a startup check got HTTP 403. A limitation of where this demo runs.';

test('a not_found whose only capable provider was refused carries the results sentence', async () => {
  // Given - Deezer is the one provider for ARTIST_RADIO and was refused
  const { render, resultHtml } = await loadPage([
    row('deezer', 'Deezer', REFUSED_DEEZER, ['ARTIST_RADIO']),
    row('musicbrainz', 'MusicBrainz', REACHABLE, ['GENRE']),
  ]);

  // When - a result with an ARTIST_RADIO not_found is rendered
  render(response(RADIO_RESULT), false);

  // Then - the status cell carries the symbol with that sentence
  const html = resultHtml();
  assert.ok(html.includes(`aria-label="${RADIO_SENTENCE}"`), html);
});

test('a not_found with a capable provider that may still answer carries no symbol', async () => {
  // Given - two providers declare ARTIST_RADIO and only one was refused
  const { render, resultHtml } = await loadPage([
    row('deezer', 'Deezer', REFUSED_DEEZER, ['ARTIST_RADIO']),
    row('listenbrainz', 'ListenBrainz', REACHABLE, ['ARTIST_RADIO']),
  ]);

  // When - a result with an ARTIST_RADIO not_found is rendered
  render(response(RADIO_RESULT), false);

  // Then - the miss is left unexplained, because the other provider could have answered
  assert.doesNotMatch(resultHtml(), /host-warn/);
});

test('an ok row never carries the symbol, even when every capable provider was refused', async () => {
  // Given - Deezer is the only ARTIST_RADIO provider and was refused, yet the type came back ok
  const { render, resultHtml } = await loadPage([row('deezer', 'Deezer', REFUSED_DEEZER, ['ARTIST_RADIO'])]);

  // When - an ok ARTIST_RADIO result is rendered
  render(response([{ type: 'ARTIST_RADIO', provider: 'deezer', status: 'ok', confidence: 0.9 }]), false);

  // Then - the row is there with no symbol
  const html = resultHtml();
  assert.ok(html.includes('ARTIST_RADIO'));
  assert.doesNotMatch(html, /host-warn/);
});

test('with several capable providers all warned, the sentence names each with its own outcome', () => {
  // Given - Deezer was refused and iTunes never answered, and both declare ARTIST_RADIO
  const providers = [
    row('deezer', 'Deezer', REFUSED_DEEZER, ['ARTIST_RADIO']),
    row('itunes', 'iTunes', UNREACHABLE_ITUNES, ['ARTIST_RADIO']),
  ];

  // When - asking for the sentence for that type
  const sentence = typeWarning('ARTIST_RADIO', providers);

  // Then - both names and both outcomes appear, and the wording is plural
  assert.equal(
    sentence,
    'Deezer and iTunes, the providers for this type, may be unreachable from this host: ' +
    'a startup check got HTTP 403 from Deezer and no answer (connection failed) from iTunes. ' +
    'A limitation of where this demo runs.',
  );
});

test('a type no provider declares gets no sentence', () => {
  // Given - providers that declare other types
  const providers = [row('deezer', 'Deezer', REFUSED_DEEZER, ['GENRE'])];

  // When - asking for a type none declares
  const sentence = typeWarning('ARTIST_RADIO', providers);

  // Then - there is nothing to say
  assert.equal(sentence, null);
});

// --- The panel -----------------------------------------------------------------------------

test('the panel is the image credit popover, so it carries no look of its own', () => {
  // Given - a warning rendered for a sentence
  const html = warningHtml(providerWarning(row('deezer', 'Deezer', REFUSED_DEEZER)));

  // When - reading the stylesheet's rules for the warning
  const css = readFileSync(new URL('../../main/resources/index.css', import.meta.url), 'utf8');
  const block = (selector) => new RegExp(`${selector.replace('.', '\\.')}\\s*\\{([^}]*)\\}`).exec(css)[1];

  // Then - the panel markup uses img-credit-pop, and the warning's own rules set no colour but var(--warn)
  assert.match(html, /<span class="img-credit-pop host-warn-pop"/);
  for (const selector of ['.host-warn', '.host-warn-pop']) {
    const colours = block(selector).match(/#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)|hsla?\([^)]*\)/g) || [];
    assert.deepEqual(colours, [], selector);
  }
  assert.doesNotMatch(block('.host-warn-pop'), /background|border|padding|font-size|box-shadow/);
});

test('the symbol is a keyboard-focusable image named by the sentence', () => {
  // Given - a sentence with markup characters in it
  const sentence = 'Deezer <b> & "friends"';

  // When - rendering the warning
  const html = warningHtml(sentence);

  // Then - it is focusable, has the image role, escapes the sentence, and is empty for no sentence
  assert.match(html, /role="img" tabindex="0" aria-label="Deezer &lt;b&gt; &amp; &quot;friends&quot;"/);
  assert.equal(warningHtml(null), '');
});
