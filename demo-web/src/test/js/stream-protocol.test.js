import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createSseParser,
  parseRetryAfter,
  classifyStreamOutcome,
  readJsonResponse,
  busyMessage,
} from '../../main/resources/stream-protocol.js';

// --- SSE framing ---------------------------------------------------------------------------
// The parser is incremental because a chunk boundary falls wherever the network puts it, never
// where an event ends. A parser that assumes whole events per chunk loses data silently: it reports
// a healthy stream that simply never yields anything.

test('one whole event in one chunk yields that event', () => {
  const parser = createSseParser();
  const events = parser.push('event: snapshot\ndata: {"sequence":1}\n\n');
  assert.deepEqual(events, [{ event: 'snapshot', data: '{"sequence":1}' }]);
});

test('an event split mid-line across chunks yields one event, not none', () => {
  const parser = createSseParser();
  assert.deepEqual(parser.push('event: snap'), []);
  assert.deepEqual(parser.push('shot\ndata: {"seq'), []);
  assert.deepEqual(parser.push('uence":1}\n\n'), [
    { event: 'snapshot', data: '{"sequence":1}' },
  ]);
});

test('two events in one chunk yield both, in order', () => {
  const parser = createSseParser();
  const events = parser.push('event: snapshot\ndata: 1\n\nevent: complete\ndata: 2\n\n');
  assert.deepEqual(events, [
    { event: 'snapshot', data: '1' },
    { event: 'complete', data: '2' },
  ]);
});

test('multiple data lines join with newlines, as the wire format means them', () => {
  const parser = createSseParser();
  const events = parser.push('event: error\ndata: line one\ndata: line two\n\n');
  assert.deepEqual(events, [{ event: 'error', data: 'line one\nline two' }]);
});

test('CRLF framing parses the same as LF', () => {
  const parser = createSseParser();
  const events = parser.push('event: snapshot\r\ndata: {"sequence":1}\r\n\r\n');
  assert.deepEqual(events, [{ event: 'snapshot', data: '{"sequence":1}' }]);
});

test('a comment line is a heartbeat and yields no event', () => {
  const parser = createSseParser();
  assert.deepEqual(parser.push(': keep-alive\n\n'), []);
});

test('a blank line before any field yields nothing rather than an empty event', () => {
  const parser = createSseParser();
  assert.deepEqual(parser.push('\n\n'), []);
});

// --- Retry-After ---------------------------------------------------------------------------

test('a numeric Retry-After is read as seconds', () => {
  assert.equal(parseRetryAfter('15'), 15);
});

test('an absent Retry-After has no value to offer', () => {
  assert.equal(parseRetryAfter(null), null);
});

test('an unparseable Retry-After is discarded rather than guessed at', () => {
  assert.equal(parseRetryAfter('soon'), null);
});

test('a negative Retry-After is clamped, never used as a delay', () => {
  assert.equal(parseRetryAfter('-5'), 0);
});

// --- What an ended stream means --------------------------------------------------------------
// This is the decision the defect lives in: the page must be able to tell "refused" from "dropped",
// because only one of them is worth a second request.

test('a 429 is busy, and must not be retried as a whole request', () => {
  const outcome = classifyStreamOutcome({ status: 429, painted: 0, retryAfter: '15' });
  assert.equal(outcome.kind, 'busy');
  assert.equal(outcome.retryAfterSeconds, 15);
  assert.equal(outcome.fallback, false);
});

test('a 429 after painting is still busy, not a partial result', () => {
  const outcome = classifyStreamOutcome({ status: 429, painted: 3, retryAfter: null });
  assert.equal(outcome.kind, 'busy');
  assert.equal(outcome.fallback, false);
});

test('a connection that drops before painting earns one whole-request retry', () => {
  const outcome = classifyStreamOutcome({ status: 200, painted: 0, dropped: true });
  assert.equal(outcome.kind, 'retry-whole');
  assert.equal(outcome.fallback, true);
});

test('a connection that drops after painting keeps what arrived', () => {
  const outcome = classifyStreamOutcome({ status: 200, painted: 4, dropped: true });
  assert.equal(outcome.kind, 'partial');
  assert.equal(outcome.fallback, false);
});

test('a transport failure with nothing painted earns the same second chance', () => {
  const outcome = classifyStreamOutcome({ status: null, painted: 0, dropped: true });
  assert.equal(outcome.kind, 'retry-whole');
  assert.equal(outcome.fallback, true);
});

test('a server error is a failure, not something to ask twice for', () => {
  const outcome = classifyStreamOutcome({ status: 500, painted: 0 });
  assert.equal(outcome.kind, 'failed');
  assert.equal(outcome.fallback, false);
});

test('a 404 is a failure the page reports rather than retries', () => {
  const outcome = classifyStreamOutcome({ status: 404, painted: 0 });
  assert.equal(outcome.kind, 'failed');
  assert.equal(outcome.fallback, false);
});

// --- Reading a response that promised JSON -----------------------------------------------------
// Anything between the browser and the demo can answer instead of the demo: a proxy, a gateway, a
// platform error page. Those answer in HTML, and a page that assumes JSON reports the parser's
// complaint about a '<' rather than what actually happened.

test('a successful JSON body is returned as data with no error', () => {
  const read = readJsonResponse({ status: 200, ok: true, body: '{"name":"Radiohead"}' });
  assert.deepEqual(read.data, { name: 'Radiohead' });
  assert.equal(read.error, null);
});

test("a failed JSON body reports the server's own error text", () => {
  const read = readJsonResponse({ status: 429, ok: false, body: '{"error":"The demo is busy."}' });
  assert.equal(read.error, 'The demo is busy.');
});

test('a failed JSON body with no error field falls back to the status', () => {
  const read = readJsonResponse({ status: 500, ok: false, body: '{}' });
  assert.equal(read.error, 'HTTP 500');
});

test('an HTML body is reported as an unexpected response, never as a parser complaint', () => {
  const read = readJsonResponse({
    status: 501, ok: false, contentType: 'text/html', body: '<!DOCTYPE html><html>nope</html>',
  });
  assert.equal(read.data, null);
  assert.match(read.error, /HTTP 501/);
  assert.doesNotMatch(read.error, /token|JSON|</);
});

test('an HTML body on a 200 is still not data', () => {
  const read = readJsonResponse({
    status: 200, ok: true, contentType: 'text/html', body: '<!DOCTYPE html>',
  });
  assert.equal(read.data, null);
  assert.match(read.error, /HTTP 200/);
});

test('an empty body reports the status rather than an empty message', () => {
  const read = readJsonResponse({ status: 502, ok: false, body: '' });
  assert.equal(read.data, null);
  assert.match(read.error, /HTTP 502/);
});

// --- One busy state, whoever refused ----------------------------------------------------------
// Two things can answer 429 on `/api/*` and they answer differently: the demo's own admission gate
// replies in JSON, and the platform in front of it (Cloud Run at one instance) replies with the
// plain text `Rate exceeded.`. A visitor has the same thing to do about either, so both must reach
// the page as the busy state and neither as a parser's complaint about the body.

test('a plain-text 429 from the platform reads as busy, not as an unreadable response', () => {
  const read = readJsonResponse({
    status: 429, ok: false, contentType: 'text/plain', body: 'Rate exceeded.',
  });
  assert.equal(read.busy, true);
  assert.equal(read.error, busyMessage(null));
  assert.doesNotMatch(read.error, /unreadable|token|JSON/);
});

test("a JSON 429 from the demo's own gate reads as busy and keeps the server's wording", () => {
  const read = readJsonResponse({
    status: 429, ok: false, contentType: 'application/json', body: '{"error":"at capacity"}',
  });
  assert.equal(read.busy, true);
  assert.equal(read.error, 'at capacity');
});

test('an empty 429 body is busy rather than a bare status', () => {
  const read = readJsonResponse({ status: 429, ok: false, body: '' });
  assert.equal(read.busy, true);
  assert.equal(read.error, busyMessage(null));
});

test('nothing but a 429 is busy, so an ordinary failure still reports itself', () => {
  const read = readJsonResponse({ status: 503, ok: false, contentType: 'text/plain', body: 'nope' });
  assert.equal(read.busy, false);
  assert.match(read.error, /HTTP 503/);
});

test('the busy message names the wait when the refusal named one', () => {
  assert.match(busyMessage(15), /15 seconds/);
  assert.doesNotMatch(busyMessage(null), /\d/);
});
