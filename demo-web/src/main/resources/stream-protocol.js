// Wire-protocol and decision logic for the enrichment stream, kept free of the DOM so it can be
// tested without a browser. `index.js` holds everything that touches the page.

/**
 * An incremental server-sent-events reader.
 *
 * Incremental because a chunk boundary falls wherever the network puts it, never where an event
 * ends: a reader that assumes whole events per chunk drops every event split across two, and does
 * it silently — the stream looks healthy and simply never yields anything.
 *
 * `push` returns the events completed by this chunk, in arrival order, and buffers the remainder.
 */
export function createSseParser() {
  let buffer = '';
  let event = null;
  let data = [];

  return {
    push(chunk) {
      buffer += chunk;
      const completed = [];
      let newline;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        // A CRLF stream leaves the carriage return on the front of the split.
        const line = buffer.slice(0, newline).replace(/\r$/, '');
        buffer = buffer.slice(newline + 1);

        if (line.startsWith(':')) continue; // a heartbeat comment carries no field
        if (line === '') {
          // A blank line with no field before it is framing, not an empty event.
          if (event !== null || data.length > 0) {
            completed.push({ event: event ?? 'message', data: data.join('\n') });
          }
          event = null;
          data = [];
          continue;
        }
        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        // One optional space after the colon belongs to the framing, not the value.
        const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '');
        if (field === 'event') event = value;
        else if (field === 'data') data.push(value);
      }
      return completed;
    },
  };
}

/**
 * `Retry-After` as a whole number of seconds, or null when the header says nothing usable.
 *
 * A value in the past is clamped rather than dropped: the server is still saying "wait then retry",
 * and a negative delay must never reach a timer.
 */
export function parseRetryAfter(header) {
  if (header === null || header === undefined || header === '') return null;
  const seconds = Number(header);
  if (Number.isFinite(seconds)) return Math.max(0, Math.round(seconds));
  const when = Date.parse(header);
  if (Number.isNaN(when)) return null;
  return Math.max(0, Math.round((when - Date.now()) / 1000));
}

/**
 * What a visitor is told when a `429` turns them away, whoever refused.
 *
 * There is one sentence because there is one thing to do about it. The demo's own admission gate
 * refuses in JSON and the platform in front of it refuses in plain text; which of them answered is
 * the operator's concern, never the visitor's.
 *
 * `seconds` is folded in only when the refusal named a wait — a guessed number would be worse than
 * none, because it is the number the visitor waits on.
 */
export function busyMessage(seconds) {
  return seconds
    ? `The demo is busy with another visitor — it runs a few lookups at a time. Try again in about ${seconds} seconds.`
    : 'The demo is busy with another visitor — it runs a few lookups at a time. Try again in a moment.';
}

/**
 * What an ended stream means, and in particular whether it is worth a second request.
 *
 * The distinction this exists for: a refusal and a dropped connection both end a stream, and only
 * the dropped one deserves a retry. Retrying a refusal turns one turned-away visitor into two
 * requests, and the second is refused as surely as the first.
 *
 * `fallback` is the single answer callers act on — no caller re-derives it from `kind`.
 */
export function classifyStreamOutcome({ status, painted = 0, retryAfter = null, dropped = false }) {
  if (status === 429) {
    return { kind: 'busy', fallback: false, retryAfterSeconds: parseRetryAfter(retryAfter) };
  }
  if (typeof status === 'number' && status >= 400) {
    return { kind: 'failed', fallback: false };
  }
  if (painted > 0) {
    return { kind: dropped ? 'partial' : 'complete', fallback: false };
  }
  return { kind: 'retry-whole', fallback: true };
}

/**
 * Reads a response that was supposed to carry JSON, and says what happened when it did not.
 *
 * The demo answers every endpoint in JSON, but the demo is not the only thing that can answer:
 * a proxy, a gateway or the platform itself replies in HTML, and `response.json()` then throws a
 * complaint about an unexpected `<` that tells a reader nothing about what went wrong. That
 * complaint is the parser's, not the server's, and it must never reach the page.
 *
 * Returns `{ data, error, busy }` — exactly one of `data` and `error` is set, `error` is always
 * something worth showing a reader, and `busy` marks the one failure the page answers with an
 * offer to try again rather than with a report.
 */
export function readJsonResponse({ status, ok, contentType = '', body = '' }) {
  let parsed = null;
  let parseable = true;
  try {
    parsed = JSON.parse(body);
  } catch (_) {
    parseable = false;
  }
  if (status === 429) {
    // Read before the body is judged, because a refusal need not have come from the demo: the
    // platform in front of it refuses in plain text, which is unparseable but not unexplained.
    return { data: null, error: parsed?.error || busyMessage(null), busy: true };
  }
  if (!parseable) {
    // The status is the only reliable fact left, so it carries the message.
    const kind = contentType.includes('html') ? 'a page instead of data' : 'an unreadable response';
    return { data: null, error: `The server returned ${kind} (HTTP ${status}).`, busy: false };
  }
  if (ok) return { data: parsed, error: null, busy: false };
  return { data: parsed, error: parsed?.error || `HTTP ${status}`, busy: false };
}
