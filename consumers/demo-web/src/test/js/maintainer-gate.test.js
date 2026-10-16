import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  maintainerSecretFromSearch,
  cacheModeControlsLocked,
} from '../../main/resources/stream-protocol.js';

// --- Reading the maintainer secret off the page's own URL -----------------------------------
// A maintainer's own bookmark (`?maintainer=<secret>`), never solicited from a visitor and never
// stored — this is the only way index.js ever learns one.

test('a URL with no maintainer param carries no secret', () => {
  assert.equal(maintainerSecretFromSearch(''), null);
  assert.equal(maintainerSecretFromSearch('?kind=track'), null);
});

test('a URL with a maintainer param carries its value', () => {
  assert.equal(maintainerSecretFromSearch('?maintainer=s3cr3t'), 's3cr3t');
});

test('an empty maintainer param is treated as absent, not an empty-string secret', () => {
  assert.equal(maintainerSecretFromSearch('?maintainer='), null);
});

// --- Whether the cache-mode controls must render locked --------------------------------------
// This is the frontend half of D2: a public instance's /api/config POST is gated behind the
// maintainer secret (Server.kt `handleConfig`), so a visitor who cannot supply one must never be
// shown a control that would 401 the moment they touch it.

test('a server that requires the secret locks the controls when the visitor has none', () => {
  assert.equal(cacheModeControlsLocked(true, null), true);
});

test('a server that requires the secret leaves the controls usable once the visitor has it', () => {
  assert.equal(cacheModeControlsLocked(true, 's3cr3t'), false);
});

test('a server that does not require the secret never locks the controls', () => {
  // This is the local/dev case (posture disabled): GET /api/config reports
  // requiresMaintainerSecret: false, and the controls must behave exactly as they did before this
  // gate existed — usable with no secret at all.
  assert.equal(cacheModeControlsLocked(false, null), false);
  assert.equal(cacheModeControlsLocked(false, 'anything'), false);
});
