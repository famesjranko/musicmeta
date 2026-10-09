// The host-reachability notice. /api/providers carries, per provider, what one startup request to
// its host found. A refused or unreachable host explains a `not_found` that is a limitation of where
// this demo runs; this file only decides when to say so and what the sentence is. It reads no
// result data, so it can never change one.

import { escapeHtml as esc } from './attribution.js';

const WARNED = ['REFUSED', 'UNREACHABLE'];

const isWarned = (provider) => !!provider.reachability && WARNED.includes(provider.reachability.verdict);

// "HTTP 403" or "no answer (connection failed)": the clause both sentences share.
function outcome(reachability) {
  return reachability.verdict === 'REFUSED'
    ? `HTTP ${reachability.httpStatus}`
    : 'no answer (connection failed)';
}

// The probe's UTC clock time, read off the ISO string so the reader's timezone cannot move it.
function utcTime(checkedAt) {
  const match = /T(\d\d:\d\d)/.exec(checkedAt || '');
  return match ? `${match[1]} UTC` : null;
}

function list(items) {
  if (items.length < 2) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** The settings-table sentence for one /api/providers row, or null when its host raised no warning. */
export function providerWarning(provider) {
  if (!isWarned(provider)) return null;
  const r = provider.reachability;
  const at = utcTime(r.checkedAt);
  const where = r.host ? ` from ${r.host}` : '';
  return `May be unreachable from this host: a startup check got ${outcome(r)}${where}${at ? ` at ${at}` : ''}. ` +
    'A limitation of where this demo runs, not of the library.';
}

/**
 * The results-table sentence for a `not_found` of `type`, or null. It is said only when at least
 * one provider declares `type` and every one that does has a warned host: a provider that may
 * still answer means the miss may be genuine.
 */
export function typeWarning(type, providers) {
  const capable = (providers || []).filter((p) => (p.capabilities || []).includes(type));
  if (capable.length === 0 || !capable.every(isWarned)) return null;
  const names = list(capable.map((p) => p.displayName));
  const single = capable.length === 1;
  const clause = single
    ? outcome(capable[0].reachability)
    : list(capable.map((p) => `${outcome(p.reachability)} from ${p.displayName}`));
  return `${names}, the ${single ? 'provider' : 'providers'} for this type, may be unreachable from this host: ` +
    `a startup check got ${clause}. A limitation of where this demo runs.`;
}

/**
 * The amber symbol and its panel. The panel reuses `.img-credit-pop`, the image credit's popover,
 * so there is one popover look; `.host-warn-pop` only moves it below the symbol. The sentence is
 * both the panel text and the accessible name, and the symbol takes focus so a keyboard opens it.
 */
export function warningHtml(sentence) {
  if (!sentence) return '';
  return `<span class="host-warn" role="img" tabindex="0" aria-label="${esc(sentence)}">⚠` +
    `<span class="img-credit-pop host-warn-pop" aria-hidden="true">${esc(sentence)}</span></span>`;
}
