// Provider credits and the notices their terms oblige, as markup. DOM-free so node can test it;
// `index.js` places what this returns.
//
// Nothing here decides *which* provider supplied a datum — that arrives as the `Credit` the API
// built from the result the engine actually returned. This module only says how a named provider
// must be credited, and what its own terms require to appear beside its data.

/** The page's HTML escape, shared by both renderers. */
export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// Reserved by the engine for a result it merged from several upstreams. Such a result names no
// upstream, so it can never be a credit: the merged items carry the sources instead.
const MERGER_SUFFIX = '_merger';

const CC_BY_SA = 'https://creativecommons.org/licenses/by-sa/4.0/';

// `label` is the anchor text (defaults to `name`), `prefix`/`suffix` the plain text around it, and
// `extraHtml` whatever the terms require beyond a credit. `standingNotice` is owed by the page as
// a whole rather than by any one item it rendered.
const PROVIDER_CREDITS = {
  musicbrainz: { name: 'MusicBrainz', site: 'https://musicbrainz.org/' },
  coverartarchive: { name: 'Cover Art Archive', site: 'https://coverartarchive.org/' },
  wikipedia: {
    name: 'Wikipedia',
    site: 'https://en.wikipedia.org/',
    prefix: 'Text from ',
    extraHtml: `, <a href="${CC_BY_SA}" target="_blank" rel="noopener">CC BY-SA 4.0</a>`,
  },
  wikidata: { name: 'Wikidata', site: 'https://www.wikidata.org/' },
  fanarttv: { name: 'Fanart.tv', site: 'https://fanart.tv/' },
  listenbrainz: { name: 'ListenBrainz', site: 'https://listenbrainz.org/' },
  lrclib: { name: 'LRCLIB', site: 'https://lrclib.net/' },
  // Discogs' API terms give the wording and require the hyperlink to sit next to the data, so the
  // notice itself is the anchor text — and never carries rel="nofollow", which the same terms forbid.
  discogs: { name: 'Discogs', site: 'https://www.discogs.com/', label: 'Data provided by Discogs' },
  // Last.fm 2.7 requires the badge beside the data and a link back to the catalogue page.
  lastfm: { name: 'Last.fm', site: 'https://www.last.fm/', suffix: ' — powered by AudioScrobbler' },
  itunes: { name: 'iTunes', site: 'https://music.apple.com/' },
  deezer: {
    name: 'Deezer',
    site: 'https://www.deezer.com/',
    standingNotice: 'Deezer previews: streaming of the recordings is limited to a strictly private use within a family scope.',
  },
};

// Same upstream, same terms, second provider id.
PROVIDER_CREDITS['deezer-similar-albums'] = PROVIDER_CREDITS.deezer;

function linkHtml(href, text) {
  return `<a href="${escapeHtml(href)}" target="_blank" rel="noopener">${escapeHtml(text)}</a>`;
}

function creditHtml(credit) {
  const entry = PROVIDER_CREDITS[credit.provider];
  if (!entry) return `<span class="credit-item">${escapeHtml(credit.provider)}</span>`;
  const href = credit.url || entry.site;
  const body = linkHtml(href, entry.label || entry.name);
  return `<span class="credit-item">${escapeHtml(entry.prefix || '')}${body}` +
    `${escapeHtml(entry.suffix || '')}${entry.extraHtml || ''}</span>`;
}

/**
 * One line of credits for the data rendered beside it, in the order the response listed them and
 * one per provider. Empty when nothing was credited, so a card with no provenance grows no line.
 *
 * `label` names what the line credits. Pass one wherever the line does not sit against the thing
 * it is crediting — a bare provider name under a paragraph reads as that paragraph's source.
 */
export function creditLineHtml(credits, label) {
  const seen = new Set();
  const items = (credits || [])
    .filter((c) => c && c.provider && !c.provider.endsWith(MERGER_SUFFIX))
    .filter((c) => !seen.has(c.provider) && seen.add(c.provider))
    .map(creditHtml);
  if (items.length === 0) return '';
  const lead = label ? `<span class="credit-label">${escapeHtml(label)}</span>` : '';
  return `<p class="credit-line">${lead}${items.join('<span class="credit-sep"> · </span>')}</p>`;
}

/**
 * Notices the page owes for merely being able to reach a provider, rather than for a datum it
 * rendered — musicmeta's policy snapshot carries none of these, so they are listed here.
 */
export function standingNotices(providerIds) {
  const seen = new Set();
  return (providerIds || [])
    .map((id) => PROVIDER_CREDITS[id])
    .filter((entry) => entry && entry.standingNotice)
    .map((entry) => entry.standingNotice)
    .filter((notice) => !seen.has(notice) && seen.add(notice));
}
