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

/**
 * The URL if it is safe to make clickable, else null. Upstreams pass link fields through as text,
 * so the page checks them here: https only, no control or format characters (which a parser
 * silently strips, turning `java\tscript:` into a real scheme), and no userinfo. A null never
 * hides the content it sat beside; the caller renders that as plain text.
 */
export function safeHref(url) {
  if (typeof url !== 'string' || url === '') return null;
  if (/[\p{Cc}\p{Cf}\s]/u.test(url)) return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'https:' || parsed.username !== '' || parsed.password !== '') return null;
  return parsed.href;
}

function linkHtml(href, text) {
  return `<a href="${escapeHtml(href)}" target="_blank" rel="noopener">${escapeHtml(text)}</a>`;
}

// A link when the target is safe, otherwise the same words as plain text.
function maybeLinkHtml(url, text) {
  const href = safeHref(url);
  return href ? linkHtml(href, text) : escapeHtml(text);
}

function creditHtml(credit) {
  const entry = PROVIDER_CREDITS[credit.provider];
  if (!entry) return `<span class="credit-item">${escapeHtml(credit.provider)}</span>`;
  const text = entry.label || entry.name;
  const body = credit.url ? maybeLinkHtml(credit.url, text) : linkHtml(entry.site, text);
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

// --- Image credit --------------------------------------------------------------------------
// A small "i" in the image corner. Hover or focus shows the credit; a click pins it open (index.js
// owns the pinning). It only describes the file: whatever the upstream said is shown as said, and
// nothing here decides whether the image is shown.

/** Upstream credit text may carry markup (Commons wraps names in anchors); show only its words. */
export function plainText(markup) {
  return String(markup ?? '')
    .replace(/<[^>]*>/g, '')
    .replace(/&(amp|lt|gt|quot|#39);/g, (m, e) => ({ amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" }[e]))
    .trim();
}

const present = (v) => typeof v === 'string' && plainText(v) !== '';

function imageCreditRows(attribution) {
  const a = attribution || {};
  const rows = [];
  const byline = present(a.attributionText) ? a.attributionText : a.creator;
  if (present(byline)) rows.push(`By ${escapeHtml(plainText(byline))}`);
  if (present(a.credit) && plainText(a.credit) !== plainText(byline)) {
    rows.push(`Credit: ${escapeHtml(plainText(a.credit))}`);
  }
  if (present(a.sourceUrl)) {
    rows.push(maybeLinkHtml(a.sourceUrl, present(a.title) ? plainText(a.title) : 'File description page'));
  }
  const licences = [a.licence, ...(a.otherLicences || [])].filter(present).map(plainText);
  if (licences.length > 0) {
    const first = maybeLinkHtml(a.licenceUrl, licences[0]);
    rows.push(`Licence: ${[first, ...licences.slice(1).map(escapeHtml)].join(', ')}`);
  } else if (present(a.licenceUrl)) {
    rows.push(maybeLinkHtml(a.licenceUrl, 'Licence page'));
  }
  if (present(a.copyrightStatus)) rows.push(`Copyright: ${escapeHtml(plainText(a.copyrightStatus))}`);
  if (present(a.modification)) rows.push(`Modified: ${escapeHtml(plainText(a.modification))}`);
  const restrictions = (a.restrictions || []).filter(present).map(plainText);
  if (restrictions.length > 0) rows.push(`Restrictions: ${escapeHtml(restrictions.join('; '))}`);
  return rows;
}

/**
 * The "i" control and its popover for one image, or '' when the response named no credit. With no
 * file facts the popover names the provider and says nothing about a licence. `credit` is a
 * `SourceCredit`: `{provider, url?, attribution?}`.
 */
export function imageCreditBadgeHtml(credit) {
  if (!credit || !credit.provider) return '';
  const entry = PROVIDER_CREDITS[credit.provider];
  const name = entry ? entry.name : credit.provider;
  const site = credit.url ? credit.url : entry && entry.site;
  const via = `Image via ${maybeLinkHtml(site, name)}`;
  const rows = [...imageCreditRows(credit.attribution), via];
  return '<span class="img-credit">' +
    '<button type="button" class="img-credit-btn" aria-label="Image credit" aria-expanded="false">i</button>' +
    `<span class="img-credit-pop" role="note">${rows.map((r) => `<span class="img-credit-row">${r}</span>`).join('')}</span>` +
    '</span>';
}
