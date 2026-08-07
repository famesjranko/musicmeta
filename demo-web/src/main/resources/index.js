const kindEl = document.getElementById('kind');
const nameEl = document.getElementById('name');
const artistEl = document.getElementById('artist');
const albumEl = document.getElementById('album');
const statusEl = document.getElementById('status');
const resultEl = document.getElementById('result');
const submitBtn = document.getElementById('submit');

const NAME_PLACEHOLDERS = {
  artist: 'Artist name (e.g. Radiohead)',
  album: 'Album title (e.g. OK Computer)',
  track: 'Track title (e.g. Karma Police)',
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
};
const values = { artistName: '', albumTitle: '', trackTitle: '' };
let currentKind = kindEl.value;

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
  artistEl.style.display = kindEl.value === 'artist' ? 'none' : '';
  artistEl.placeholder = kindEl.value === 'album' ? 'Artist (required)' : 'Artist (for track)';
  albumEl.style.display = kindEl.value === 'track' ? '' : 'none';
  nameEl.placeholder = NAME_PLACEHOLDERS[kindEl.value] || NAME_PLACEHOLDERS.artist;
}

kindEl.addEventListener('change', () => {
  saveValues(currentKind);
  currentKind = kindEl.value;
  syncArtistField();
  loadValues(currentKind);
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
  kindEl.value = kind;
  currentKind = kind;
  nameEl.value = name || '';
  artistEl.value = artist || '';
  albumEl.value = album || '';
  saveValues(currentKind);
  syncArtistField();
  runQuery();
}

document.getElementById('examples').addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-kind]');
  if (!btn) return;
  fillAndRun(btn.dataset.kind, btn.dataset.name, btn.dataset.artist, btn.dataset.album);
});

document.getElementById('query-form').addEventListener('submit', (e) => {
  e.preventDefault();
  runQuery();
});

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

async function runQuery() {
  if (!backendReady) {
    statusEl.className = '';
    statusEl.textContent = 'The backend is still warming up. Search will unlock automatically when it is ready.';
    return;
  }

  const kind = kindEl.value;
  const name = nameEl.value.trim();
  const artist = artistEl.value.trim();
  const album = albumEl.value.trim();
  if (!name || (kind !== 'artist' && !artist)) {
    statusEl.textContent = kind === 'artist' ? 'Enter a name.' : 'Enter both a name and an artist.';
    statusEl.className = 'err';
    return;
  }

  stopPreview();
  submitBtn.disabled = true;
  submitBtn.innerHTML = '<span class="spinner"></span>Enriching…';
  statusEl.className = '';
  statusEl.textContent = '';
  resultEl.innerHTML = '<div class="loading-panel"><span class="spinner"></span>Calling providers — this can take a few seconds…</div>';

  const params = new URLSearchParams({ kind, name, artist, album });
  try {
    const res = await fetch('/api/enrich?' + params.toString());
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    render(data);
  } catch (err) {
    statusEl.className = 'err';
    statusEl.textContent = 'Failed: ' + err.message;
    resultEl.innerHTML = '<div id="empty">No result.</div>';
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Enrich';
  }
}

// --- Rendering ---

function render(data) {
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
        a different ${esc(kindEl.value)} entirely. This is usually temporary.</span>
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

  const providers = data.meta.providers.map((p) => `
    <tr>
      <td><span class="dot ${esc(p.status.split(':')[0])}"></span>${esc(p.type)}</td>
      <td>${esc(p.provider)}</td>
      <td>${esc(p.status)}</td>
      <td>${p.confidence != null ? p.confidence.toFixed(2) : '—'}</td>
    </tr>`).join('');

  resultEl.innerHTML = `
    <div class="card summary${unverified ? ' unverified' : ''}">
      ${backdrop}
      ${img}
      <div class="body">
        <div class="titlerow"><h2>${titleHtml}</h2>${bestEffortBadge}${summaryPreview}</div>
        ${subtitle}
        ${text}
      </div>
    </div>
    ${unverifiedBanner}
    ${gallery}
    <div class="sections${unverified ? ' unverified' : ''}">${sections}</div>
    <div class="card">
      <details class="meta-panel">
        <summary>${data.meta.elapsedMs}ms · ${totalItems} items across ${data.sections.length} sections${data.meta.identityMatch ? ' · identity: ' + esc(data.meta.identityMatch) : ''} — how we got this</summary>
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
