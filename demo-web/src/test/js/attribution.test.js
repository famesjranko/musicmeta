import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

import {
  escapeHtml,
  creditLineHtml,
  standingNotices,
} from '../../main/resources/attribution.js';
import { contentCreditHtml } from '../../main/resources/attribution.js';

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

test('file credit uses the selected file payload, escapes text, and rejects unsafe links', () => {
  // Given - file metadata containing external markup and an unsafe description link.
  const credit = {
    attributionText: '<b>Photo & credit</b>',
    sourceUrl: 'javascript:alert(1)',
    licenses: [{ identifier: 'CC BY-SA <4>', url: 'https://creativecommons.org/licenses/by-sa/4.0/' }],
    modificationNote: 'cropped & adjusted',
  };

  // When - rendering its required credit.
  const html = contentCreditHtml(credit);

  // Then - text is escaped and unsafe links are absent.
  assert.match(html, /Photo &amp; credit/);
  assert.match(html, /CC BY-SA &lt;4&gt;/);
  assert.match(html, /cropped &amp; adjusted/);
  assert.doesNotMatch(html, /javascript:|<b>/);
});

test('file credit rejects literal and encoded controls in its source URL', () => {
  // Given - untrusted source URLs containing literal C0/C1/DEL values or encoded control bytes.
  const unsafeUrls = [
    'https://example.test/%0A',
    'https://example.test/%0D',
    'https://example.test/%7F',
    'https://example.test/%250A',
    'https://example.test/%0a',
    'https://example.test/%80',
    'https://example.test/literal\u0001',
    'https://example.test/literal\u0080',
    'https://example.test/literal\u007f',
  ];

  // When - rendering each attribution source.
  const rendered = unsafeUrls.map((sourceUrl) => contentCreditHtml({ sourceUrl }));

  // Then - no control-bearing source becomes a link.
  for (const html of rendered) assert.doesNotMatch(html, /href=/);
});

test('file credit rejects literal and encoded controls in every licence URL', () => {
  // Given - untrusted licence URLs containing literal C0/C1/DEL values or encoded control bytes.
  const unsafeUrls = [
    'https://example.test/%0A',
    'https://example.test/%0D',
    'https://example.test/%7F',
    'https://example.test/%250A',
    'https://example.test/%0a',
    'https://example.test/%80',
    'https://example.test/literal\u0001',
    'https://example.test/literal\u0080',
    'https://example.test/literal\u007f',
  ];

  // When - rendering each licence URL.
  const rendered = unsafeUrls.map((url) => contentCreditHtml({ licenses: [{ identifier: 'Control', url }] }));

  // Then - every unsafe licence remains text rather than an active link.
  for (const html of rendered) {
    assert.match(html, /<span>Control<\/span>/);
    assert.doesNotMatch(html, /href=/);
  }
});

test('file credit retains ordinary HTTPS links and safe encoded paths', () => {
  // Given - an ordinary HTTPS source and a licence path with ordinary URL encoding.
  const credit = {
    sourceUrl: 'https://example.test/source',
    licenses: [{ identifier: 'Encoded licence', url: 'https://example.test/licence%20terms/path%2Fpart' }],
  };

  // When - rendering the attribution.
  const html = contentCreditHtml(credit);

  // Then - both safe HTTPS links render unchanged.
  assert.match(html, /href="https:\/\/example\.test\/source"/);
  assert.match(html, /href="https:\/\/example\.test\/licence%20terms\/path%2Fpart"/);
});

test('file credit retains public-domain, custom, restricted, and multiple licence details', () => {
  // Given - file metadata with public domain and custom restrictions.
  const credit = {
    credit: 'Museum collection', sourceUrl: 'https://example.test/file',
    licenses: [{ identifier: 'Public domain' }, { identifier: 'CC0', url: 'https://creativecommons.org/publicdomain/zero/1.0/' }],
    modificationNote: 'Restricted: editorial use only',
  };

  // When - rendering all file credit facts.
  const html = contentCreditHtml(credit);

  // Then - every designation and restriction remains visible.
  assert.match(html, /Museum collection/);
  assert.match(html, /Public domain/);
  assert.match(html, /CC0/);
  assert.match(html, /Restricted: editorial use only/);
});

test('missing content facts render no empty image credit', () => {
  // Given - metadata containing no usable facts or safe source link.
  const credit = { sourceUrl: 'ftp://example.test/file' };

  // When - rendering an incomplete credit.
  const html = contentCreditHtml(credit);

  // Then - it contributes no empty control or credit block.
  assert.equal(html, '');
});

test('public domain credit remains visible without a named creator', () => {
  // Given - a public domain file with no required creator credit.
  const credit = { sourceUrl: 'https://example.test/archive', licenses: [{ identifier: 'Public domain' }] };
  // When - rendering its file attribution.
  const html = contentCreditHtml(credit);
  // Then - the licence and file source remain available.
  assert.match(html, /Public domain/);
  assert.match(html, /href="https:\/\/example.test\/archive"/);
});

test('multiple licences retain every designation and their required relation', () => {
  // Given - two licences that must both be satisfied.
  const credit = { creator: 'Archive', licenses: [{ identifier: 'CC BY 4.0' }, { identifier: 'Custom grant' }], licenseRelation: 'ALL_OF', isModified: false };
  // When - rendering the required attribution.
  const html = contentCreditHtml(credit);
  // Then - both designations and the conjunction remain explicit.
  assert.match(html, /CC BY 4\.0/);
  assert.match(html, /Custom grant/);
  assert.match(html, /All licences apply/);
  assert.match(html, /No modifications reported/);
});

test('custom credit overrides constructed credit without truncation and rejects control-bearing links', () => {
  // Given - externally supplied credit text and unsafe URL input.
  const credit = { creator: 'Hidden creator', credit: 'Hidden credit', attributionText: '<Author>\u001b' + 'a'.repeat(5000),
    sourceUrl: 'https://example.test/\nunsafe', licenses: [{ identifier: 'Custom', url: 'data:text/html,x' }],
    usageTerms: 'Editorial use', restrictions: ['Permission required'] };
  // When - rendering file credit.
  const html = contentCreditHtml(credit);
  // Then - the complete escaped custom credit is retained and unsafe links are absent.
  assert.ok(html.includes('&lt;Author&gt;'), 'External markup is escaped');
  assert.ok(html.includes('a'.repeat(5000)));
  assert.ok(!/Hidden creator|Hidden credit|\u001b|href=/.test(html), 'No hidden credit, active control, or unsafe link');
  assert.match(html, /Editorial use/);
  assert.match(html, /Permission required/);
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

test('repository page preserves image credits across live, cached, refresh and alternative paths', {
  skip: process.env.PLAYWRIGHT_MODULE ? false : 'Set PLAYWRIGHT_MODULE to run the Chrome integration check',
}, async () => {
  // Given - the actual repository resources and local API responses with distinct file credits.
  const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE);
  const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="300"><rect width="300" height="300" fill="gray"/></svg>');
  const credit = (creator) => ({ creator, sourceUrl: 'https://example.test/' + creator,
    licenses: [{ identifier: 'CC0', url: 'https://creativecommons.org/publicdomain/zero/1.0/' }], modificationNote: 'Scaled for display' });
  const payload = { kind: 'artist', name: 'Fixture', summary: { title: 'Fixture', genres: [],
    imageUrl: image, imageAttribution: credit('Primary'), text: 'Local biography',
    textAttribution: credit('Contributors'), identityVerdict: 'RESOLVED' },
    gallery: ['Alternative', 'Portrait', 'Archive'].map((label) => ({ url: image, label, attribution: credit(label) })),
    sections: [], meta: { elapsedMs: 1, providers: [], identifiers: [], requestedTypes: [] } };
  const paths = [];
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) {
      paths.push(request.url);
      const data = structuredClone(payload);
      if (url.searchParams.get('name') === 'Text only') {
        delete data.summary.imageUrl;
        data.gallery = [];
      }
      if (url.searchParams.get('name') === 'Old text') {
        delete data.summary.textAttribution;
        data.summary.textCredit = { provider: 'wikipedia' };
      }
      if (url.searchParams.get('name') === 'Background') {
        data.summary.backgroundImageUrl = image;
        data.summary.backgroundAttribution = credit('Background');
      }
      if (url.searchParams.get('name') === 'Provider image') {
        delete data.summary.imageAttribution;
        data.summary.imageCredit = { provider: 'deezer' };
      }
      if (url.searchParams.get('name') === 'Long credit') data.summary.imageAttribution.attributionText = 'Long ' + 'a'.repeat(5000);
      if (url.pathname === '/api/enrich-stream') {
        response.setHeader('Content-Type', 'text/event-stream');
        response.end('event: snapshot\ndata: ' + JSON.stringify({ sequence: 1, response: data, pending: [], identityPending: false }) + '\n\nevent: complete\ndata: ' + JSON.stringify({ sequence: 2, response: data, pending: [], identityPending: false }) + '\n\n');
      } else {
        response.setHeader('Content-Type', 'application/json');
        response.end(JSON.stringify(url.pathname === '/api/enrich' ? data : {}));
      }
      return;
    }
    try {
      const resource = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      if (!/^[\w.-]+$/.test(resource)) throw new Error('Invalid resource');
      response.setHeader('Content-Type', resource.endsWith('.js') ? 'text/javascript' : resource.endsWith('.css') ? 'text/css' : 'text/html');
      response.end(await readFile(new URL('../../main/resources/' + resource, import.meta.url)));
    } catch (_) { response.writeHead(404).end(); }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  try {
    // When - querying through the normal form and interacting with each rendered watermark.
    for (const width of [1440, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 1000 }, hasTouch: width === 390 });
      const page = await context.newPage();
      page.setDefaultTimeout(5000);
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await page.route('**/*', (route) => route.request().url().startsWith(origin) || route.request().url().startsWith('data:') ? route.continue() : route.abort());
      await page.goto(origin);
      await page.locator('#name').fill('Fixture');
      await page.locator('#submit').click();
      const buttons = page.locator('.image-credit');
      await buttons.first().waitFor();
      assert.equal(await buttons.count(), 4);
      assert.equal(await page.locator('.gallery figcaption').count(), 0, 'Image credit has no persistent caption');
      assert.equal(await buttons.first().textContent(), 'i');
      assert.deepEqual(await buttons.first().evaluate((element) => {
        const style = getComputedStyle(element);
        return [style.width, style.height, style.right, style.bottom, style.borderRadius];
      }), ['22px', '22px', '7px', '7px', '50%']);
      assert.deepEqual(await buttons.first().evaluate((element) => {
        const control = element.getBoundingClientRect(), image = element.previousElementSibling.getBoundingClientRect();
        return [Math.round(image.right - control.right), Math.round(image.bottom - control.bottom)];
      }), [7, 7], 'Watermark is inset from the image itself');
      for (let i = 0; i < 4; i++) {
        const button = buttons.nth(i);
        const popover = page.locator('.image-credit-popover').nth(i);
        await button.scrollIntoViewIfNeeded();
        assert.equal(await button.textContent(), 'i');
        assert.equal(await button.evaluate((element) => getComputedStyle(element).opacity), '0.42');
        await button.hover();
        assert.equal(await popover.isVisible(), true);
        assert.ok((await popover.textContent()).includes(['Primary', 'Alternative', 'Portrait', 'Archive'][i]));
        assert.equal(await popover.locator('a').first().getAttribute('href'), 'https://example.test/' + ['Primary', 'Alternative', 'Portrait', 'Archive'][i]);
        assert.ok((await popover.textContent()).includes('CC0'));
        assert.ok((await popover.textContent()).includes('Scaled for display'));
        await page.mouse.move(0, 0);
        assert.equal(await popover.isVisible(), false, 'Hover leave closes preview');
        await button.focus();
        assert.equal(await popover.isVisible(), true, 'Focus previews credit');
        await button.click();
        await button.evaluate((element) => element.blur());
        await page.keyboard.press('Escape');
        assert.equal(await popover.isVisible(), false, 'Global Escape closes a pinned credit after focus leaves its wrapper');
        assert.equal(await button.evaluate((element) => element === document.activeElement), true, 'Global Escape returns focus to its watermark');
        assert.equal(await popover.isVisible(), false, 'Returned focus does not reopen the dismissed credit');
        await button.evaluate((element) => element.blur());
        await button.focus();
        await page.keyboard.press('Tab');
        assert.equal(await popover.locator('a').first().evaluate((element) => element === document.activeElement), true);
        await page.keyboard.press('Escape');
        assert.equal(await popover.isVisible(), false, 'Escape from credit link closes');
        assert.equal(await button.evaluate((element) => element === document.activeElement), true, 'Escape returns focus');
        await button.click();
        await button.evaluate((element) => element.blur());
        await page.mouse.move(0, 0);
        assert.equal(await popover.isVisible(), true, 'Click pins credit after focus and hover leave');
        const geometry = await popover.evaluate((element) => {
          const p = element.getBoundingClientRect(), b = element.previousElementSibling.getBoundingClientRect();
          return { inside: p.left >= 0 && p.right <= innerWidth && p.top >= 0 && p.bottom <= innerHeight,
            overlap: p.left < b.right && p.right > b.left && p.top < b.bottom && p.bottom > b.top };
        });
        assert.deepEqual(geometry, { inside: true, overlap: false });
        await popover.locator('.image-credit-close').click();
        assert.equal(await popover.isVisible(), false, 'Close remains closed after focus return');
        assert.equal(await button.evaluate((element) => element === document.activeElement), true);
        if (width === 390) await button.tap(); else await button.click();
        if (width === 390) await page.locator('h1').tap(); else await page.locator('h1').click();
        assert.equal(await popover.isVisible(), false, 'Outside pointer closes');
      }
      await page.locator('.gallery img').first().click();
      assert.equal(await page.locator('#lightbox').isHidden(), false, 'Image click opens the lightbox');
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#lightbox').isHidden(), true, 'Escape still closes the lightbox');
      // Then - credits stay image-specific and usable at both widths and after a cached refresh.
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      assert.equal(await page.locator('.summary .source a').first().getAttribute('href'), 'https://example.test/Contributors');
      await page.locator('#name').fill('Old text');
      await page.locator('#submit').click();
      await buttons.first().waitFor();
      assert.equal(await page.locator('.summary .source').count(), 0, 'Old text receives no invented provider-wide licence');
      await page.locator('#fetch-fresh-btn').click();
      await buttons.first().waitFor();
      assert.equal(await buttons.count(), 4);
      await page.evaluate(() => localStorage.setItem('musicmeta.demo.streaming', 'off'));
      await page.reload();
      await page.locator('#name').fill('Fixture');
      await page.locator('#submit').click();
      await buttons.first().waitFor();
      assert.equal(await buttons.count(), 4, 'Cached whole-response payload retains all credits');
      await page.locator('#name').fill('Background');
      await page.locator('#submit').click();
      await page.waitForFunction(() => document.querySelectorAll('.image-credit').length === 5);
      await page.locator('.background-credit .image-credit').click();
      assert.ok((await page.locator('.background-credit .content-credit').textContent()).includes('Background'));
      await page.keyboard.press('Escape');
      await page.locator('#name').fill('Provider image');
      await page.locator('#submit').click();
      await buttons.first().waitFor();
      await buttons.first().click();
      assert.ok((await page.locator('.image-credit-popover').first().textContent()).includes('Deezer'));
      await page.keyboard.press('Escape');
      await page.locator('#name').fill('Long credit');
      await page.locator('#submit').click();
      await buttons.first().waitFor();
      await buttons.first().click();
      const longPopover = page.locator('.image-credit-popover').first();
      assert.ok((await longPopover.textContent()).includes('a'.repeat(5000)), 'Long functional credit is complete');
      assert.equal(await longPopover.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return bounds.top >= 0 && bounds.bottom <= innerHeight;
      }), true, 'Long credit stays within the viewport');
      await page.keyboard.press('Escape');
      await page.locator('#name').fill('Text only');
      await page.locator('#submit').click();
      await page.waitForFunction(() => document.querySelector('.summary .text')?.textContent === 'Local biography');
      assert.equal(await buttons.count(), 0);
      assert.equal(await page.locator('.summary .source a').first().getAttribute('href'), 'https://example.test/Contributors');
      assert.deepEqual(errors, []);
      await context.close();
    }
    assert.ok(paths.some((path) => path.startsWith('/api/enrich-stream?')));
    assert.ok(paths.some((path) => path.includes('refresh=true')));
    assert.ok(paths.some((path) => path.startsWith('/api/enrich?')));
  } finally {
    await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
