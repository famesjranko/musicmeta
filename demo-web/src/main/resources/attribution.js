// Provider credits and the notices their terms oblige, as markup. DOM-free so node can test it;
// `index.js` places what this returns.
//
// Nothing here decides *which* provider supplied a datum — that arrives as the `Credit` the API
// built from the result the engine actually returned. This module only says how a named provider
// must be credited, and what its own terms require to appear beside its data.

/** The page's HTML escape, shared by both renderers. */
export function escapeHtml(s) {
  return String(s ?? '').replace(/[\u0000-\u001f\u007f-\u009f]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

const ENCODED_BYTES = /(?:%[0-9a-f]{2})+/ig;

function hasEncodedControl(value) {
  const decodedPercent = value.replace(/%25/ig, '%');
  for (const run of decodedPercent.match(ENCODED_BYTES) || []) {
    const bytes = Uint8Array.from(run.match(/[0-9a-f]{2}/ig), (hex) => Number.parseInt(hex, 16));
    if (bytes.some((byte) => byte <= 0x1f || byte === 0x7f)) return true;
    if (bytes.some((byte) => byte >= 0x80 && byte <= 0x9f)) {
      try {
        if ([...new TextDecoder('utf-8', { fatal: true }).decode(bytes)].some((char) => /[\u0000-\u001f\u007f-\u009f]/.test(char))) return true;
      } catch (_) { return true; }
    }
  }
  return false;
}

function safeUrl(value) {
  if (typeof value !== 'string' || /[\u0000-\u0020\u007f-\u009f]/.test(value) || hasEncodedControl(value)) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null;
  } catch (_) { return null; }
}

/** Renders payload-specific file/article attribution; all external text and URLs are untrusted. */
export function contentCreditHtml(credit) {
  if (!credit) return '';
  const text = credit.attributionText || [credit.creator, credit.credit].filter(Boolean).join(' · ');
  const source = safeUrl(credit.sourceUrl);
  const sourceHtml = source ? linkHtml(source, 'Source') : '';
  const licences = (credit.licenses || []).map((license) => {
    const href = safeUrl(license.url);
    return href ? linkHtml(href, license.identifier) : `<span>${escapeHtml(license.identifier)}</span>`;
  }).filter(Boolean);
  const relation = licences.length > 1 ? ({ ALL_OF: 'All licences apply', ANY_OF: 'Choose one licence' }[credit.licenseRelation] || 'Licence relationship unknown') : '';
  const details = [credit.usageTerms, ...(credit.restrictions || [])].filter(Boolean).map((value) => `<span>${escapeHtml(value)}</span>`);
  const modifications = credit.modificationNote || (credit.isModified === true ? 'Modified; details not supplied' : credit.isModified === false ? 'No modifications reported' : '');
  const parts = [text ? `<span>${escapeHtml(text)}</span>` : '', sourceHtml, ...licences,
    relation ? `<span>${relation}</span>` : '', ...details,
    modifications ? `<span>${escapeHtml(modifications)}</span>` : ''].filter(Boolean);
  return parts.length ? `<span class="content-credit">${parts.join('<span class="credit-sep"> · </span>')}</span>` : '';
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
  const href = safeUrl(credit.url) || entry.site;
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
