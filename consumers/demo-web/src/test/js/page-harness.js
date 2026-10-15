import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Loads the page's real index.js with a stub DOM, as render.test.js does, and answers
// /api/providers with `providers` so the settings table paints through the page's own
// renderProviders(). Every other fetch never answers. Not a *.test.js file, so the runner skips it.

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

export async function loadPage(providers) {
  const elements = new Map();
  const byId = (id) => {
    if (!elements.has(id)) elements.set(id, stubElement());
    return elements.get(id);
  };
  const realSetTimeout = globalThis.setTimeout;
  const providersResponse = {
    status: 200,
    ok: true,
    headers: { get: () => 'application/json' },
    text: async () => JSON.stringify({ providers }),
  };
  Object.assign(globalThis, {
    document: { getElementById: byId, querySelectorAll: () => [], addEventListener() {} },
    window: { addEventListener() {}, location: { search: '' } },
    localStorage: { getItem: () => null, setItem() {} },
    getComputedStyle: () => ({ lineHeight: '20px' }),
    Audio: class { addEventListener() {} },
    fetch: (url) => (String(url).startsWith('/api/providers') ? Promise.resolve(providersResponse) : new Promise(() => {})),
    setTimeout: (fn, ms, ...args) => realSetTimeout(fn, ms, ...args).unref(),
  });
  const source = readFileSync(new URL('index.js', resources), 'utf8')
    .replace(/from '\/([\w-]+\.js)'/g, (_, file) => `from '${new URL(file, resources).href}'`);
  const file = join(mkdtempSync(join(tmpdir(), 'page-test-')), 'index.mjs');
  writeFileSync(file, source);
  const { render } = await import(pathToFileURL(file).href);
  // The providers fetch settles over a few promise turns; one macrotask turn outlasts them all.
  await new Promise((resolve) => setImmediate(resolve));
  return { render, settingsHtml: () => byId('provider-panel').innerHTML, resultHtml: () => byId('result').innerHTML };
}
