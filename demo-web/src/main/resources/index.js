const tabsEl = document.getElementById('kind-tabs');
const kindTabs = Array.from(tabsEl.querySelectorAll('button[data-kind]'));
const nameEl = document.getElementById('name');
const artistEl = document.getElementById('artist');
const albumEl = document.getElementById('album');
const statusEl = document.getElementById('status');
const resultEl = document.getElementById('result');
const submitBtn = document.getElementById('submit');
const findBtn = document.getElementById('find');
const searchResultsEl = document.getElementById('search-results');

const NAME_PLACEHOLDERS = {
  artist: 'Artist name (e.g. Radiohead)',
  album: 'Album title (e.g. OK Computer)',
  track: 'Track title (e.g. Karma Police)',
  mbid: 'MusicBrainz id — artist, release or recording',
};

// The #name box is reused for three different semantic roles across kinds (artist name / album
// title / track title), and #artist and #album drop in and out of view — so a kind switch can
// leave typed text sitting in a box whose meaning just changed underneath it. FIELD_ROLES maps
// each kind's boxes to a stable semantic key; values are saved out to `values` under the old
// kind's roles and restored into the new kind's roles, so e.g. an artist name typed under "Artist"
// reappears in the artist box after switching to "Track" instead of being stranded in the title box.
const FIELD_ROLES = {
  artist: { name: 'artistName', artist: null, album: null },
  album: { name: 'albumTitle', artist: 'artistName', album: null },
  track: { name: 'trackTitle', artist: 'artistName', album: 'albumTitle' },
  // One box, one identifier: the app resolves what it names and renders that page, so there is
  // nothing for the artist or album boxes to hold.
  mbid: { name: 'mbid', artist: null, album: null },
};
const values = { artistName: '', albumTitle: '', trackTitle: '', mbid: '' };
let currentKind = 'artist';

// What the primary box means per kind, for the screen-reader label and the ⌕ button's name.
const NAME_LABELS = {
  artist: 'Artist name',
  album: 'Album title',
  track: 'Track title',
  mbid: 'MusicBrainz id',
};
const FIND_LABELS = {
  artist: 'Find matching artists',
  album: 'Find matching albums',
  track: 'Find matching tracks',
};

// Reads the three boxes' current DOM values into `values`, keyed by the given kind's roles.
function saveValues(kind) {
  const roles = FIELD_ROLES[kind];
  values[roles.name] = nameEl.value;
  if (roles.artist) values[roles.artist] = artistEl.value;
  if (roles.album) values[roles.album] = albumEl.value;
}

// Writes `values` back into the three boxes per the given kind's roles.
function loadValues(kind) {
  const roles = FIELD_ROLES[kind];
  nameEl.value = values[roles.name] || '';
  artistEl.value = roles.artist ? values[roles.artist] || '' : '';
  albumEl.value = roles.album ? values[roles.album] || '' : '';
}

function syncArtistField() {
  const namesOnly = currentKind === 'artist' || currentKind === 'mbid';
  artistEl.style.display = namesOnly ? 'none' : '';
  artistEl.placeholder = currentKind === 'album' ? 'Artist (required)' : 'Artist (for track)';
  albumEl.style.display = currentKind === 'track' ? '' : 'none';
  nameEl.placeholder = NAME_PLACEHOLDERS[currentKind] || NAME_PLACEHOLDERS.artist;
  nameEl.setAttribute('aria-label', NAME_LABELS[currentKind] || NAME_LABELS.artist);
  // An identifier names its entity outright, so there is nothing to search for under `mbid`.
  findBtn.hidden = currentKind === 'mbid';
  findBtn.parentElement.classList.toggle('no-find', findBtn.hidden);
  findBtn.setAttribute('aria-label', FIND_LABELS[currentKind] || FIND_LABELS.artist);
  kindTabs.forEach((tab) => {
    const active = tab.dataset.kind === currentKind;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-pressed', String(active));
    tab.tabIndex = active ? 0 : -1;
  });
}

// Moves to `kind`, carrying each box's text across by semantic role (see FIELD_ROLES). Candidates
// found under the old kind no longer describe what the form now asks for, so they are dropped.
function selectKind(kind) {
  if (kind === currentKind) return;
  saveValues(currentKind);
  currentKind = kind;
  syncArtistField();
  loadValues(currentKind);
  clearCandidates();
}

tabsEl.addEventListener('click', (e) => {
  const tab = e.target.closest('button[data-kind]');
  if (tab) selectKind(tab.dataset.kind);
});

// Arrow keys move between the buttons and take focus with them, as a roving tabindex group is
// expected to. Home/End jump straight to the first/last button.
tabsEl.addEventListener('keydown', (e) => {
  if (e.key === 'Home' || e.key === 'End') {
    e.preventDefault();
    const target = kindTabs[e.key === 'Home' ? 0 : kindTabs.length - 1];
    selectKind(target.dataset.kind);
    target.focus();
    return;
  }
  const step = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
  if (!step) return;
  e.preventDefault();
  const index = kindTabs.findIndex((tab) => tab.dataset.kind === currentKind);
  const next = kindTabs[(index + step + kindTabs.length) % kindTabs.length];
  selectKind(next.dataset.kind);
  next.focus();
});

syncArtistField();

// --- Cold-start readiness ---
// The health endpoint reports when the server's startup warm-up has completed. Keep search
// available for typing, but prevent provider calls until ready so the first user request does not
// spend its timeout budget paying the backend's JIT/DNS/TLS setup cost.
let backendReady = false;
const healthPill = document.getElementById('health-pill');
const healthLabel = document.getElementById('health-label');
const exampleButtons = Array.from(document.querySelectorAll('#examples button[data-kind]'));

function setSearchReady(ready) {
  backendReady = ready;
  submitBtn.disabled = !ready;
  submitBtn.setAttribute('aria-disabled', String(!ready));
  findBtn.disabled = !ready;
  findBtn.setAttribute('aria-disabled', String(!ready));
  exampleButtons.forEach((button) => { button.disabled = !ready; });
}

function setHealthState(state, label) {
  healthPill.dataset.state = state;
  healthLabel.textContent = label;
}

async function pollHealth() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch('/api/health', {
      cache: 'no-store',
      signal: controller.signal,
    });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const data = await response.json();
    if (data.ready) {
      setHealthState('ready', 'Backend ready');
      setSearchReady(true);
      return;
    }
    setHealthState('warming', 'Warming backend…');
    setTimeout(pollHealth, 1000);
  } catch (_) {
    setHealthState('error', 'Backend unavailable');
    setTimeout(pollHealth, 1500);
  } finally {
    clearTimeout(timeout);
  }
}

setSearchReady(false);
pollHealth();

// Fills the form and runs a search — shared by the "Try:" buttons and by clicking an
// internal-navigation target (SectionItem.enrich / SummaryCard.subtitleEnrich) elsewhere on the page.
function fillAndRun(kind, name, artist, album) {
  currentKind = kind;
  nameEl.value = name || '';
  artistEl.value = artist || '';
  albumEl.value = album || '';
  saveValues(currentKind);
  syncArtistField();
  clearCandidates();
  runQuery();
}

document.getElementById('examples').addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-kind]');
  if (!btn) return;
  fillAndRun(btn.dataset.kind, btn.dataset.name, btn.dataset.artist, btn.dataset.album);
});

document.getElementById('query-form').addEventListener('submit', (e) => {
  e.preventDefault();
  clearCandidates();
  runQuery();
});

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// The exact request behind the page currently on screen, so the toolbar's "Fetch fresh data"
// button can re-run precisely that lookup rather than whatever the form fields hold by then.
let lastQuery = null;

// `refresh`, when true, re-issues `lastQuery` with `forceRefresh` instead of reading the form —
// the toolbar's "Fetch fresh data" button passes true. `replay`, when true, also re-issues
// `lastQuery` but without forcing a refresh — the invalidate button's re-run after a successful
// cache clear, which must look like an ordinary (post-invalidation, cache-miss) lookup rather than
// a user-triggered force-refresh. `pick`, when given, is a request built from a chosen search
// candidate rather than from the boxes — it enriches what the user pointed at even where the form
// still holds the words they searched with. The search form's submit passes none of the three.
async function runQuery(refresh, replay, pick) {
  if (!backendReady) {
    statusEl.className = '';
    statusEl.textContent = 'The backend is still warming up. Search will unlock automatically when it is ready.';
    return;
  }

  let kind, name, artist, album;
  if (refresh || replay) {
    if (!lastQuery) return;
    ({ kind, name, artist, album } = lastQuery);
  } else if (pick) {
    ({ kind, name, artist = '', album = '' } = pick);
  } else {
    kind = currentKind;
    name = nameEl.value.trim();
    artist = artistEl.value.trim();
    album = albumEl.value.trim();
    const needsArtist = kind !== 'artist' && kind !== 'mbid';
    if (!name || (needsArtist && !artist)) {
      statusEl.textContent = needsArtist ? 'Enter both a name and an artist.' : 'Enter a name.';
      statusEl.className = 'err';
      return;
    }
  }

  stopPreview();
  submitBtn.disabled = true;
  submitBtn.innerHTML = '<span class="spinner"></span>Enriching…';
  statusEl.className = '';
  statusEl.textContent = '';
  resultEl.innerHTML = '<div class="loading-panel"><span class="spinner"></span>Calling providers — this can take a few seconds…</div>';

  const params = new URLSearchParams({ kind, name, artist, album });
  if (refresh) params.set('refresh', 'true');
  try {
    const res = await fetch('/api/enrich?' + params.toString());
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    lastQuery = { kind, name, artist, album };
    render(data, !!refresh);
  } catch (err) {
    statusEl.className = 'err';
    statusEl.textContent = 'Failed: ' + err.message;
    resultEl.innerHTML = '<div id="empty">No result.</div>';
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Enrich';
  }
}

// --- Candidate search ---
// Manual only: every ⌕ Find is one call into rate-limited upstreams, so nothing here runs off
// typing. The candidates a run produced are held so a click can enrich the exact one picked.
let candidates = [];

function clearCandidates() {
  candidates = [];
  searchResultsEl.innerHTML = '';
  searchResultsEl.hidden = true;
}

function candidateSubtitle(hit) {
  return [hit.disambiguation, hit.artist, hit.releaseType, hit.year].filter(Boolean).join(' \u00b7 ');
}

function renderCandidates(query) {
  if (!candidates.length) {
    searchResultsEl.innerHTML = `<h4>No matches for &ldquo;${esc(query)}&rdquo;</h4>`;
    searchResultsEl.hidden = false;
    return;
  }
  const rows = candidates.map((hit, index) => {
    const thumb = hit.thumbnailUrl
      ? `<img src="${esc(hit.thumbnailUrl)}" alt="" onerror="this.remove()" />`
      : '';
    const subtitle = candidateSubtitle(hit);
    return `<li><button type="button" data-index="${index}">${thumb}<span class="primary">${esc(hit.title)}${
      subtitle ? ` <span class="secondary">${esc(subtitle)}</span>` : ''
    }</span><span class="score">score ${esc(hit.score)}</span></button></li>`;
  }).join('');
  searchResultsEl.innerHTML = `<h4>Matches for &ldquo;${esc(query)}&rdquo;</h4><ul>${rows}</ul>`;
  searchResultsEl.hidden = false;
}

async function runSearch() {
  if (!backendReady) {
    statusEl.className = '';
    statusEl.textContent = 'The backend is still warming up. Search will unlock automatically when it is ready.';
    return;
  }
  const query = nameEl.value.trim();
  if (!query) {
    statusEl.className = 'err';
    statusEl.textContent = 'Enter something to search for.';
    return;
  }

  const kind = currentKind;
  // A typed artist narrows an album or track search; it is never required to run one.
  const params = new URLSearchParams({ kind, q: query });
  const artist = artistEl.value.trim();
  if (kind !== 'artist' && artist) params.set('artist', artist);

  findBtn.disabled = true;
  statusEl.className = '';
  statusEl.textContent = '';
  searchResultsEl.innerHTML = '<h4>Searching\u2026</h4>';
  searchResultsEl.hidden = false;
  try {
    const res = await fetch('/api/search?' + params.toString());
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    candidates = data.candidates || [];
    renderCandidates(query);
  } catch (err) {
    clearCandidates();
    statusEl.className = 'err';
    statusEl.textContent = 'Search failed: ' + err.message;
  } finally {
    findBtn.disabled = !backendReady;
  }
}

// A pick fills what the candidate knows into the boxes and enriches it immediately — by MBID when
// the candidate carries one, which is the identifier path rather than another name lookup.
function pickCandidate(hit) {
  nameEl.value = hit.title;
  if (currentKind !== 'artist' && hit.artist) artistEl.value = hit.artist;
  saveValues(currentKind);

  if (hit.mbid) {
    clearCandidates();
    runQuery(false, false, { kind: 'mbid', name: hit.mbid });
    return;
  }
  // No identifier and no artist leaves an album or track request with nothing to match against.
  if (currentKind !== 'artist' && !artistEl.value.trim()) {
    statusEl.className = 'err';
    statusEl.textContent = 'That match carries no artist — type one, then hit Enrich.';
    return;
  }
  clearCandidates();
  runQuery(false, false, {
    kind: currentKind,
    name: nameEl.value.trim(),
    artist: artistEl.value.trim(),
    album: albumEl.value.trim(),
  });
}

findBtn.addEventListener('click', runSearch);

searchResultsEl.addEventListener('click', (e) => {
  const row = e.target.closest('button[data-index]');
  if (!row) return;
  const hit = candidates[Number(row.dataset.index)];
  if (hit) pickCandidate(hit);
});

// --- Rendering ---

// Genre chips. `title` alone is invisible to touch and keyboard users, so the same confidence and
// sources also go in a visually-hidden span. The legend appears only when a curated chip needs it.
function genreChipsHtml(genres) {
  if (!genres || !genres.length) return '';
  const chips = genres.map((g) => {
    const sources = (g.sources || []).join(', ');
    const detail = `confidence ${g.confidence.toFixed(2)}${sources ? ` \u00b7 ${sources}` : ''}`;
    const kind = g.curated === true ? 'MusicBrainz curated genre' : 'community tag';
    return `<span class="genre-chip${g.curated === true ? ' curated' : ''}" title="${esc(detail)}">${esc(g.name)}<span class="visually-hidden"> (${esc(kind)}, ${esc(detail)})</span></span>`;
  }).join('');
  const legend = genres.some((g) => g.curated === true)
    ? '<p class="genre-legend"><span aria-hidden="true">\u2726</span> MusicBrainz curated genre</p>'
    : '';
  return `<div class="genre-chips">${chips}</div>${legend}`;
}

function render(data, wasForceRefresh) {
  const summary = data.summary;
  const backdrop = summary.backgroundImageUrl
    ? `<div class="backdrop" style="background-image:url('${esc(summary.backgroundImageUrl)}')"></div>`
    : '';
  const img = summary.imageUrl
    ? `<img src="${esc(summary.imageUrl)}" alt="" onerror="this.remove()" />`
    : '';
  const text = summary.text
    ? `<div class="text">${esc(summary.text)}</div><button type="button" class="show-more text-toggle" style="display:none">Show all</button>${summary.textSource ? `<div class="source">source: ${esc(summary.textSource)}</div>` : ''}`
    : '';
  const summaryPreview = previewButtonHtml(summary.previewTitle, summary.previewArtist, summary.previewAlbum);
  const subtitle = summary.subtitle
    ? `<div class="subtitle${summary.subtitleEnrich ? ' enrich-row' : ''}"${enrichAttrs(summary.subtitleEnrich)}>${esc(summary.subtitle)}</div>`
    : '';
  const genres = genreChipsHtml(summary.genres);

  // An unresolved identity must not render the raw query as a confident title.
  const unresolvedTitle = summary.identityVerdict === 'SUGGESTIONS'
    ? `No exact match for &ldquo;${esc(data.name)}&rdquo;`
    : null;
  const unverified = summary.identityVerdict === 'UNVERIFIED';
  const bestEffortBadge = summary.identityVerdict === 'BEST_EFFORT'
    ? '<span class="badge badge-warn">Best-effort match</span>'
    : unverified
      ? '<span class="badge badge-warn">Unverified — lookup failed</span>'
      : '';
  const titleHtml = unresolvedTitle || esc(summary.title);

  // UNVERIFIED means the identity lookup errored (usually transiently) — distinct from
  // BEST_EFFORT's "searched, no match". Retry is manual only: an automatic re-run under
  // concurrent load compounds the very starvation that produced the failure.
  const unverifiedBanner = unverified
    ? `<div class="identity-banner">
        <span aria-hidden="true">&#9888;&#65039;</span>
        <span class="identity-banner-text">We couldn&rsquo;t verify this match &mdash; the identity
        lookup didn&rsquo;t respond. Everything below is an unverified fuzzy match and may describe
        a different ${esc(currentKind)} entirely. This is usually temporary.</span>
        <button type="button" id="identity-retry">Retry lookup</button>
      </div>`
    : '';

  const sections = data.sections.map((s) => sectionHtml(s, unverified)).join('');
  const totalItems = data.sections.reduce((n, s) => n + s.items.length, 0);

  const gallery = (data.gallery && data.gallery.length)
    ? `<div class="card gallery${unverified ? ' unverified' : ''}">${data.gallery.map((g) => `
      <figure>
        <img src="${esc(g.url)}" alt="${esc(g.label || '')}" onerror="this.closest('figure').remove()" />
        ${g.label ? `<figcaption>${esc(g.label)}</figcaption>` : ''}
      </figure>`).join('')}</div>`
    : '';

  // The identifiers identity resolution settled on. Rendered as a definition list rather than a
  // table row: they are values a reader copies, not per-provider outcomes to scan down.
  const identifiers = (data.meta.identifiers || []).length
    ? `<dl class="identifiers">${data.meta.identifiers.map((i) => `
        <div><dt>${esc(i.label)}</dt><dd><code>${esc(i.value)}</code></dd></div>`).join('')}</dl>`
    : '';

  const staleCount = data.meta.providers.filter((p) => p.status === 'ok_stale').length;

  const providers = data.meta.providers.map((p) => `
    <tr>
      <td><span class="dot ${esc(p.status.split(':')[0])}"></span>${esc(p.type)}</td>
      <td>${esc(p.provider)}</td>
      <td>${p.status === 'ok_stale' ? 'ok <span class="stale-badge">stale fallback</span>' : esc(p.status)}</td>
      <td>${p.confidence != null ? p.confidence.toFixed(2) : '—'}</td>
    </tr>`).join('');

  // The wire carries elapsed time only, never cache-hit provenance — Meta has no such field, and
  // the engine doesn't expose one. "Completed in" stays honest either way; a refresh the user just
  // triggered is the one case this page can honestly call out, because it *knows* that fetch was
  // forced fresh.
  const freshnessText = wasForceRefresh
    ? `Fresh fetch in <b>${data.meta.elapsedMs} ms</b>`
    : `Completed in <b>${data.meta.elapsedMs} ms</b>`;

  resultEl.innerHTML = `
    <div class="result-toolbar">
      <span class="freshness" role="status">${freshnessText}</span>
      <span class="spacer"></span>
      <button class="toolbar-btn" type="button" id="fetch-fresh-btn">⟳ Fetch fresh data</button>
      ${lastQuery && lastQuery.kind === 'mbid' ? '' : '<button class="toolbar-btn quiet" type="button" id="invalidate-btn">✕ Clear cached result &amp; reload</button>'}
    </div>
    <div class="card summary${unverified ? ' unverified' : ''}">
      ${backdrop}
      ${img}
      <div class="body">
        <div class="titlerow"><h2>${titleHtml}</h2>${bestEffortBadge}${summaryPreview}</div>
        ${subtitle}
        ${genres}
        ${text}
      </div>
    </div>
    ${unverifiedBanner}
    ${gallery}
    <div class="sections${unverified ? ' unverified' : ''}">${sections}</div>
    <div class="card">
      <details class="meta-panel">
        <summary>${totalItems} items across ${data.sections.length} sections${data.meta.identityMatch ? ' · identity: ' + esc(data.meta.identityMatch) : ''}${staleCount > 0 ? ' · <b class="stale-count">' + staleCount + ' stale provider response' + (staleCount === 1 ? '' : 's') + '</b>' : ''} — how we got this</summary>
        ${identifiers}
        <table class="providers">
          <thead><tr><th>Type</th><th>Provider</th><th>Status</th><th>Confidence</th></tr></thead>
          <tbody>${providers}</tbody>
        </table>
      </details>
    </div>
  `;

  setupTextToggle();
  setupSectionToggles();
}

// Shows the summary text's "Show all" toggle only when the collapsed block actually clips
// content, and — when it does — snaps the collapsed height down to a whole multiple of the
// text's line-height so the cut always lands between lines, never through the middle of one.
// Measured post-render (scrollHeight vs the CSS max-height in clientHeight) rather than a
// character-count heuristic, since how much text fits depends on panel width and font metrics.
function setupTextToggle() {
  const textEl = resultEl.querySelector('.summary .text');
  const toggle = resultEl.querySelector('.summary .text-toggle');
  if (!textEl || !toggle) return;
  textEl.style.maxHeight = ''; // re-baseline to the CSS cap before remeasuring
  if (textEl.scrollHeight > textEl.clientHeight + 1) {
    const lineHeight = parseFloat(getComputedStyle(textEl).lineHeight) || textEl.clientHeight;
    const lines = Math.max(1, Math.floor(textEl.clientHeight / lineHeight));
    const snapped = lines * lineHeight;
    textEl.dataset.collapsedHeight = String(snapped);
    textEl.style.maxHeight = snapped + 'px';
    toggle.style.display = '';
  } else {
    toggle.remove();
  }
}

// Data-attributes carrying an EnrichTarget (kind/name/artist/album) for the click-delegation
// handler below. Every value goes through esc() like any other interpolated field.
function enrichAttrs(enrich) {
  if (!enrich) return '';
  return ` data-enrich-kind="${esc(enrich.kind)}" data-enrich-name="${esc(enrich.name)}"` +
    ` data-enrich-artist="${esc(enrich.artist || '')}" data-enrich-album="${esc(enrich.album || '')}"`;
}

function sectionHtml(section, unverified) {
  const items = section.items.map((it) => {
    const rowClass = it.enrich ? ' enrich-row' : '';
    const primaryHtml = it.link
      ? `<a href="${esc(it.link)}" target="_blank" rel="noopener">${esc(it.primary)}</a>`
      : it.enrich
        ? `<span class="enrich-link">${esc(it.primary)}</span>`
        : esc(it.primary);
    const preview = previewButtonHtml(it.previewTitle, it.previewArtist, it.previewAlbum);
    return `
      <li class="${rowClass.trim()}"${enrichAttrs(it.enrich)}>
        ${it.imageUrl ? `<img src="${esc(it.imageUrl)}" alt="" onerror="this.remove()" />` : ''}
        <span class="primary">${primaryHtml}${it.secondary ? ` <span class="secondary">${esc(it.secondary)}</span>` : ''}</span>
        ${it.meta ? `<span class="meta">${esc(it.meta)}</span>` : ''}
        ${preview}
      </li>`;
  }).join('');
  // Rendered for every section, whatever its size — whether it's actually needed is a function of
  // room, not item count, and can only be answered post-render (see setupSectionToggles). Starts
  // hidden so a section that never clips never shows a flash of a button.
  const toggle = `<button type="button" class="show-more" style="display:none">Show all ${section.items.length}</button>`;
  const chip = unverified ? ' <span class="badge badge-warn section-chip">unverified</span>' : '';
  return `<div class="card section"><h3>${esc(section.label)} <span class="count">${section.items.length}</span>${chip}</h3><ul>${items}</ul>${toggle}</div>`;
}

// Per-card measure-after-render, mirroring setupTextToggle: a card's list is only "genuinely
// clipped" if its rendered content overflows the room the row actually gave it (ul.scrollHeight >
// ul.clientHeight), which depends on the tallest sibling in the row and can't be known from item
// count alone. When it is clipped, the cap is snapped down from that room to the bottom edge of
// the last <li> that fully fits — never a fixed pixel value — so the visible cut always lands on
// an item boundary instead of mid-row. Re-run after any toggle (a grown/shrunk row changes every
// sibling's room) and on resize. A card whose list is already expanded always keeps its "Show
// fewer" button — collapsing is always a valid action regardless of measurement.
function setupSectionToggles() {
  resultEl.querySelectorAll('.section').forEach((card) => {
    const ul = card.querySelector('ul');
    const toggle = card.querySelector('.show-more');
    if (!ul || !toggle) return;
    if (ul.classList.contains('expanded')) {
      toggle.style.display = '';
      return;
    }
    ul.style.maxHeight = ''; // re-baseline to the CSS cap (var(--section-cap)) before remeasuring
    if (ul.scrollHeight <= ul.clientHeight + 1) {
      toggle.style.display = 'none';
      return;
    }
    const room = ul.clientHeight;
    const ulTop = ul.getBoundingClientRect().top;
    const items = Array.from(ul.children);
    let snapped = 0;
    for (const item of items) {
      const bottom = item.getBoundingClientRect().bottom - ulTop;
      if (bottom > room) break;
      snapped = bottom;
    }
    // Guard: a single item taller than the available room (e.g. a very long primary/secondary on
    // a narrow panel) would otherwise snap to 0 and hide everything — show that one item in full.
    if (snapped <= 0 && items.length > 0) {
      snapped = items[0].getBoundingClientRect().bottom - ulTop;
    }
    ul.style.maxHeight = snapped + 'px';
    toggle.style.display = '';
  });
}

let resizeTimer = null;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(setupSectionToggles, 150);
});

function previewButtonHtml(title, artist, album) {
  if (!title || !artist) return '';
  return `<button type="button" class="preview-btn" data-title="${esc(title)}" data-artist="${esc(artist)}" data-album="${esc(album || '')}" title="Play 30s preview">&#9654;</button>`;
}

// --- Unverified-identity retry (manual only — see the banner comment in render()) ---

resultEl.addEventListener('click', (e) => {
  if (e.target.closest('#identity-retry')) runQuery();
});

// --- Result toolbar: force-refresh, and clear-cache-then-reload ---

resultEl.addEventListener('click', (e) => {
  if (e.target.closest('#fetch-fresh-btn')) runQuery(true);
});

// Invalidates the cached entry behind the page currently on screen, then re-runs the same
// lookup — the visible effect is the same slow full fetch a first-ever search pays. Absent on
// mbid pages: that kind is rejected by /api/invalidate (see Server.kt handleInvalidate), because
// a bare MBID's request isn't reconstructable without the discovery round-trip render() already
// paid to get here.
resultEl.addEventListener('click', async (e) => {
  const btn = e.target.closest('#invalidate-btn');
  if (!btn || !lastQuery) return;
  btn.disabled = true;
  try {
    const res = await fetch('/api/invalidate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(lastQuery),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    runQuery(false, true);
  } catch (err) {
    statusEl.className = 'err';
    statusEl.textContent = 'Failed: ' + err.message;
    btn.disabled = false;
  }
});

// --- Show more / show fewer (event delegation, no extra network calls) ---

resultEl.addEventListener('click', (e) => {
  const toggle = e.target.closest('.show-more');
  if (!toggle) return;

  if (toggle.classList.contains('text-toggle')) {
    const textEl = toggle.previousElementSibling;
    const expanding = !textEl.classList.contains('expanded');
    // Inline max-height (the line-snapped collapsed height) outranks the stylesheet's
    // `.expanded { max-height: none }` rule, so expanding must clear it; collapsing restores the
    // already-computed snapped height instead of remeasuring (width/content haven't changed).
    textEl.style.maxHeight = expanding ? '' : (textEl.dataset.collapsedHeight || '') + 'px';
    textEl.classList.toggle('expanded', expanding);
    toggle.textContent = expanding ? 'Show fewer' : 'Show all';
    return;
  }

  const card = toggle.closest('.section');
  const ul = card.querySelector('ul');
  const expanding = !ul.classList.contains('expanded');
  // Same inline-vs-class precedence issue as the text toggle above; setupSectionToggles()
  // recomputes and reapplies the item-snapped height on collapse.
  if (expanding) ul.style.maxHeight = '';
  ul.classList.toggle('expanded', expanding);
  if (expanding) {
    toggle.textContent = 'Show fewer';
  } else {
    toggle.textContent = 'Show all ' + card.querySelectorAll('li').length;
    card.scrollIntoView({ block: 'nearest' });
  }
  // A card growing or shrinking changes how much room its row gives every sibling card.
  setupSectionToggles();
});

// --- Internal cross-navigation (result items, artist name on track/album pages) ---
// Reuses the same fill-form-and-submit flow as the "Try:" buttons. Delegated on resultEl so it
// also covers items revealed by "Show all N"; excludes clicks that belong to another row
// affordance (external link, preview button, show-more) so those keep their own behaviour.

resultEl.addEventListener('click', (e) => {
  if (e.target.closest('a, .preview-btn, .show-more')) return;
  const target = e.target.closest('[data-enrich-kind]');
  if (!target) return;
  fillAndRun(target.dataset.enrichKind, target.dataset.enrichName, target.dataset.enrichArtist, target.dataset.enrichAlbum);
});

// --- Artwork lightbox ---
// Full-size overlay for the summary image or any gallery image. The summary card's *backdrop* is
// a plain div with a CSS background-image, not an <img>, so it's excluded by construction — the
// selector below only ever matches an actual <img> element. src is read straight off the live DOM
// node (already resolved/decoded by the browser), not re-parsed from markup, so no extra esc()
// call is needed here beyond the one already used to build the <img src="..."> in the first place.

const lightbox = document.getElementById('lightbox');
const lightboxImg = document.getElementById('lightbox-img');

function openLightbox(src) {
  lightboxImg.src = src;
  lightbox.hidden = false;
}

function closeLightbox() {
  lightbox.hidden = true;
  lightboxImg.src = '';
}

resultEl.addEventListener('click', (e) => {
  const img = e.target.closest('.summary img, .gallery img');
  if (!img) return;
  openLightbox(img.src);
});

lightbox.addEventListener('click', closeLightbox);

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !lightbox.hidden) closeLightbox();
});

// --- 30s preview playback ---

const audio = new Audio();
let activeBtn = null;
const PLAY_GLYPH = '&#9654;';
const PLAY_TITLE = 'Play 30s preview';

audio.addEventListener('ended', stopPreview);

// Clears loading/playing/error and any pending error-revert timer, restoring the glyph. Keyed on
// the button element itself (not the shared activeBtn/errorTimer bookkeeping) so resetting one
// button never disturbs a different button's independent error-revert countdown.
function resetBtn(btn) {
  clearTimeout(btn._errorTimer);
  btn._errorTimer = null;
  btn.classList.remove('playing', 'loading', 'error');
  btn.innerHTML = PLAY_GLYPH;
  btn.title = PLAY_TITLE;
}

function stopPreview() {
  audio.pause();
  if (activeBtn) resetBtn(activeBtn);
  activeBtn = null;
}

resultEl.addEventListener('click', async (e) => {
  const btn = e.target.closest('.preview-btn');
  if (!btn) return;

  if (btn === activeBtn) {
    stopPreview();
    return;
  }
  resetBtn(btn); // this button may still be mid error-revert from an earlier attempt
  stopPreview();
  activeBtn = btn;
  btn.classList.add('loading');
  btn.innerHTML = '<span class="spinner"></span>';

  const params = new URLSearchParams({
    title: btn.dataset.title,
    artist: btn.dataset.artist,
    album: btn.dataset.album || '',
  });
  try {
    const res = await fetch('/api/preview?' + params.toString());
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'No preview');
    if (btn !== activeBtn) return; // superseded by another click while loading
    audio.src = data.url;
    await audio.play();
    btn.classList.remove('loading');
    btn.innerHTML = PLAY_GLYPH;
    btn.classList.add('playing');
  } catch (err) {
    if (btn !== activeBtn) return; // superseded before the failure arrived; already reset
    activeBtn = null;
    btn.classList.remove('loading');
    btn.classList.add('error');
    btn.innerHTML = '!';
    btn.title = 'No preview available: ' + err.message;
    btn._errorTimer = setTimeout(() => {
      btn.classList.remove('error');
      btn.innerHTML = PLAY_GLYPH;
      btn.title = PLAY_TITLE;
      btn._errorTimer = null;
    }, 2000);
  }
});

// Provider panel + attribution. One fetch at load, deliberately never awaited by anything on the
// enrichment path: the panel and the credits line are app-level facts, not part of a result.
const settingsDialog = document.getElementById('settings-dialog');
const providerPanel = document.getElementById('provider-panel');
const creditsEl = document.getElementById('credits');
const creditsNotices = document.getElementById('credits-notices');

// Enum names arrive bare over the wire so the frontend owns presentation: CONDITIONAL -> Conditional.
function humanizeEnum(name) {
  const words = String(name).toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// A notice is owed outright, or owed for some of the provider's data and not others — the demo
// cannot know which item came from where before the request runs, so both states render.
const ATTRIBUTION_OWED = ['REQUIRED', 'DEPENDS_ON_DATA'];

// keyStatus arrives from the server merge of the live provider set with the default-provider
// catalog: KEY_MISSING is a catalog entry no instance registered (row never went live), and
// TOKEN_MISSING is a live-but-token-gated entry (ListenBrainz) whose surface is otherwise intact.
function keyStateHtml(provider) {
  if (provider.keyStatus === 'KEY_MISSING') return '<span class="keyreq">key missing</span>';
  if (provider.keyStatus === 'TOKEN_MISSING') {
    return '<span class="keyreq" title="Gates artist radio discovery only — everything else this provider answers still works.">token missing</span>';
  }
  if (!provider.requiresApiKey) return 'no key needed';
  return provider.available ? 'key configured' : '<span class="keyreq">key missing</span>';
}

function providerRowHtml(provider) {
  const skipped = provider.keyStatus === 'KEY_MISSING';
  const availability = skipped ? 'skipped' : provider.available ? 'available' : 'unavailable';
  const policy = provider.policy;
  return `<tr>
      <td><span class="pdot${provider.available ? '' : ' off'}" aria-hidden="true"></span>${esc(provider.displayName)}
        <span class="secondary">${availability}</span></td>
      <td>${keyStateHtml(provider)}</td>
      <td>${provider.capabilities.length}</td>
      <td>${policy ? esc(humanizeEnum(policy.commercialUse)) : 'Not recorded'}</td>
      <td>${policy && policy.dataLicence ? esc(policy.dataLicence) : 'Not recorded'}</td>
    </tr>`;
}

function renderProviders(providers) {
  providerPanel.innerHTML = `<table class="provider-table">
      <caption>Providers this instance was built with (plus any skipped for a missing key), and the terms musicmeta records for each.</caption>
      <thead><tr>
        <th scope="col">Provider</th><th scope="col">Key</th><th scope="col">Types</th>
        <th scope="col">Commercial use</th><th scope="col">Licence</th>
      </tr></thead>
      <tbody>${providers.map(providerRowHtml).join('')}</tbody>
    </table>`;

  const notices = providers
    .filter((p) => p.policy && ATTRIBUTION_OWED.includes(p.policy.attribution) && p.policy.attributionNotice)
    .map((p) => p.policy.attributionNotice);
  if (notices.length === 0) return;
  creditsNotices.innerHTML = notices.map((n) => `<span class="credit">${esc(n)}</span>`).join('');
  creditsEl.hidden = false;
}

fetch('/api/providers', { cache: 'no-store' })
  .then((res) => {
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res.json();
  })
  .then((data) => renderProviders(data.providers || []))
  .catch(() => { providerPanel.textContent = 'The provider list could not be loaded.'; });

document.getElementById('settings-open').addEventListener('click', () => settingsDialog.showModal());
document.getElementById('credits-link').addEventListener('click', () => settingsDialog.showModal());
document.getElementById('settings-close').addEventListener('click', () => settingsDialog.close());

// Cache mode. One GET at load to reflect what's actually running; each change POSTs and reflects
// the confirmed value back rather than the value clicked, so a rejected/failed change doesn't
// leave the radio lying about which mode is live.
const cacheModeInputs = document.querySelectorAll('#cache-mode-fieldset input[type="radio"]');
let confirmedCacheMode = null;

function setCacheModeRadio(cacheMode) {
  confirmedCacheMode = cacheMode;
  cacheModeInputs.forEach((input) => { input.checked = input.value === cacheMode; });
}

fetch('/api/config', { cache: 'no-store' })
  .then((res) => {
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res.json();
  })
  .then((data) => setCacheModeRadio(data.cacheMode))
  .catch(() => {});

cacheModeInputs.forEach((input) => {
  input.addEventListener('change', () => {
    const requested = input.value;
    const lastConfirmed = confirmedCacheMode;
    fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cacheMode: requested }),
    })
      .then((res) => {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then((data) => setCacheModeRadio(data.cacheMode))
      .catch(() => setCacheModeRadio(lastConfirmed));
  });
});
