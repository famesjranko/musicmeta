import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Loads the page's real index.js and calls its real render(). index.js is a browser module: it
// imports from site-absolute paths and touches `document` at load, so the test points the imports
// at the resource files and stands a stub DOM in. The stub knows nothing about the page; any
// element is a bag of properties, and `result.innerHTML` is what render() painted.

const resources = new URL('../../main/resources/', import.meta.url);

function stubElement() {
  const el = {
    style: {},
    dataset: {},
    classList: { toggle() {}, add() {}, remove() {}, contains: () => false },
    querySelectorAll: () => [],
    querySelector: () => null,
    addEventListener() {},
    setAttribute() {},
    closest: () => null,
    parentElement: null,
  };
  el.parentElement = el;
  return el;
}

async function loadRender() {
  const elements = new Map();
  const byId = (id) => {
    if (!elements.has(id)) elements.set(id, stubElement());
    return elements.get(id);
  };
  const realSetTimeout = globalThis.setTimeout;
  Object.assign(globalThis, {
    document: { getElementById: byId, querySelectorAll: () => [], addEventListener() {} },
    window: { addEventListener() {}, location: { search: '' } },
    localStorage: { getItem: () => null, setItem() {} },
    getComputedStyle: () => ({ lineHeight: '20px' }),
    Audio: class { addEventListener() {} },
    // Never answers: the load-time health and provider polls stay pending and paint nothing.
    fetch: () => new Promise(() => {}),
    // The polls arm timers; unref them so a finished test can exit.
    setTimeout: (fn, ms, ...args) => realSetTimeout(fn, ms, ...args).unref(),
  });
  const source = readFileSync(new URL('index.js', resources), 'utf8')
    .replace(/from '\/([\w-]+\.js)'/g, (_, file) => `from '${new URL(file, resources).href}'`);
  const file = join(mkdtempSync(join(tmpdir(), 'render-test-')), 'index.mjs');
  writeFileSync(file, source);
  const { render } = await import(pathToFileURL(file).href);
  return { render, painted: () => byId('result').innerHTML };
}

const WIKI_TEXT_CREDIT = { provider: 'wikipedia', url: 'https://en.wikipedia.org/wiki/Radiohead' };

function response(gallery) {
  return {
    name: 'Radiohead',
    summary: {
      title: 'Radiohead',
      text: 'Radiohead are an English rock band.',
      textCredit: WIKI_TEXT_CREDIT,
      imageUrl: 'https://upload.wikimedia.org/wikipedia/commons/a/a1/Thom_Yorke.jpg',
      // No attribution: the upstream said nothing about the file.
      imageCredit: { provider: 'wikipedia' },
      genres: [],
      pendingSlots: [],
    },
    gallery,
    sections: [],
    meta: { providers: [], identifiers: [] },
  };
}

const GALLERY = [
  { url: 'https://upload.wikimedia.org/wikipedia/commons/b/b2/Alt.jpg', label: 'wikipedia', credit: { provider: 'wikipedia' } },
  { url: 'https://commons.wikimedia.org/media/c/c3/Logo.svg', label: 'Logo', credit: { provider: 'wikidata' } },
  { url: 'https://upload.wikimedia.org/wikipedia/commons/e/e5/Banner.jpg', label: 'Banner', credit: { provider: 'wikipedia' } },
];

test('a Wikipedia summary shows its text credit, its unattributed image and every gallery entry', async () => {
  // Given - the page's render() and a response with Wikipedia text, a photo with no file facts and a Wikimedia gallery
  const { render, painted } = await loadRender();

  // When - the response is rendered
  render(response(GALLERY), false);

  // Then - the text credit, the summary image and each gallery image are all in the page
  const html = painted();
  assert.match(html, /Text from <a href="https:\/\/en\.wikipedia\.org\/wiki\/Radiohead"[^>]*>Wikipedia<\/a>/);
  assert.ok(html.includes('src="https://upload.wikimedia.org/wikipedia/commons/a/a1/Thom_Yorke.jpg"'));
  for (const g of GALLERY) assert.ok(html.includes(`src="${g.url}"`), g.url);
  assert.equal((html.match(/<figure>/g) || []).length, GALLERY.length);
});
