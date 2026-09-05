#!/usr/bin/env python3
"""Fail when an upstream route is neither tied to a schema pin nor named in the allowlist below.

`check_schema_pin_coverage.py` asks whether a provider is pinned at all, and one pinned route
satisfies it while nine others move unwatched. This asks the route-level question: for every upstream
call the providers make, is there either a pin the route can be tied to, or a line here saying why
not. The rule is deliberately *not* "every route has a pin" — attribution to a pin is not
mechanisable for most routes — so what a new route is required to bring is a decision, recorded in
one of the two places, rather than silence.

**A route** is a non-private `suspend fun` in a `*Api.kt` that reaches an `httpClient.fetch*`/`post*`
call through a chain of same-file calls. The transitive part is what makes the check see the
providers that funnel every call through one private fetch helper; keying on the call site alone
reports the helper and hides its callers, which is silent green in four of the eleven providers here.

**Tied to a pin** means a `SchemaTarget(` in the same provider directory whose `route =` is the
function's own name, or whose `url =` calls a URL builder the function itself calls. Both keys are
direct on purpose: following the builder call transitively would let one pin claim every route
sharing a parameterised builder, so a pin on `artist.getinfo` would read as coverage for
`artist.gettoptracks`.

**Two shapes this cannot see, and what it does about them.**

  - Attribution beyond those two keys. A route whose URL is built inline, or reached through one
    level of builder indirection, has no mechanical key even when it *is* pinned. Those go in
    `ALLOWLIST` with a reason, which is why the list starts long: it is the backlog, written down.
  - Reachability is same-file, and enumeration reads only `*Api.kt`. A fetch helper moved to another
    file, or a request made straight from a provider, takes routes out of the enumeration entirely.
    `UNENUMERABLE_CALL_SITES` is the tripwire: an `httpClient` call that no route can be seen to make
    is reported, so the shape announces itself instead of quietly shrinking the route list.

Both lists are checked for staleness — an entry naming a route that no longer exists, or one that is
now pinned, is reported. Without that a burnt-down entry stays forever and the list stops reading as
the backlog it is.

Plain regex over the provider sources, no Kotlin front end — matching `check_provider_call_scope.py`.

    python3 check_route_pin_coverage.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PROVIDER_ROOT = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider"

FUN_RE = re.compile(
    r"^\s*(?:(?:private|internal|public|override|open|final|abstract)\s+)*"
    r"(?:(suspend)\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]"
)
PRIVATE_RE = re.compile(r"^\s*private\s")
HTTP_CALL_RE = re.compile(r"httpClient\s*\.\s*(?:fetch\w*|post\w*)")
URL_BUILDER_RE = re.compile(r"^\s*(?:(?:private|internal|public)\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*Url)\s*\(")

# Most entries below say one of three things, so each is written once. An entry carrying a sentence
# of its own is a route with something particular to say.
INLINE_URL = "unpinned: builds its URL inline, so there is no builder a pin's `url =` could name"
SHARED_BUILDER = "unpinned: no target for this Last.fm method, and the shared `buildUrl` cannot key one"
REDIRECT = "not pin-shaped: the response is a redirect to an image, not a document with paths to pin"

# Routes with no mechanical key to a pin, each with the reason a reviewer can disagree with. Most are
# simply unpinned, and the list is the drift watch's backlog written down: burning one down means
# adding the pin *and* deleting the line, and the check reports a line it no longer needs.
ALLOWLIST: dict[str, str] = {
    "coverartarchive/getArtworkUrl": REDIRECT,
    "coverartarchive/getGroupArtworkUrl": REDIRECT,
    "deezer/getAlbum": INLINE_URL,
    "deezer/getAlbumTracks": INLINE_URL,
    "deezer/getArtistAlbums": INLINE_URL,
    "deezer/getArtistRadio": INLINE_URL,
    "deezer/getArtistTop": INLINE_URL,
    "deezer/getRelatedArtists": INLINE_URL,
    "deezer/getTrack": INLINE_URL,
    "deezer/searchArtist": INLINE_URL,
    "deezer/searchTrack": INLINE_URL,
    "discogs/getArtist": INLINE_URL,
    "discogs/getMasterVersions": INLINE_URL,
    "discogs/getReleaseDetails": INLINE_URL,
    "discogs/searchArtist": INLINE_URL,
    "itunes/lookupAlbumTracks": INLINE_URL,
    "itunes/lookupArtistAlbums": INLINE_URL,
    "itunes/lookupByUpc": INLINE_URL,
    "itunes/searchArtist": INLINE_URL,
    "lastfm/getAlbumInfo": SHARED_BUILDER,
    "lastfm/getArtistInfo": (
        "pinned as `artist.getinfo`, but the route reaches `artistMethodUrl` through `buildUrl`, so "
        "one level of indirection leaves route and pin with no key in common"
    ),
    "lastfm/getArtistTopTracks": SHARED_BUILDER,
    "lastfm/getSimilarArtists": SHARED_BUILDER,
    "lastfm/getSimilarTracks": SHARED_BUILDER,
    "lastfm/getTrackInfo": SHARED_BUILDER,
    "listenbrainz/getArtistPopularity": INLINE_URL,
    "listenbrainz/getRadio": INLINE_URL,
    "listenbrainz/getRecordingPopularity": INLINE_URL,
    "lrclib/getLyrics": INLINE_URL,
    "musicbrainz/lookupArtistWithRels": INLINE_URL,
    "musicbrainz/lookupRecording": INLINE_URL,
    "musicbrainz/lookupRelease": INLINE_URL,
    "musicbrainz/lookupReleaseGroupWikiLinks": INLINE_URL,
    "musicbrainz/searchArtistsFuzzy": INLINE_URL,
    "musicbrainz/searchCanonicalRecordings": INLINE_URL,
    "musicbrainz/searchRecordings": INLINE_URL,
    "musicbrainz/searchRecordingsFuzzy": INLINE_URL,
    "musicbrainz/searchReleasesFuzzy": INLINE_URL,
    "wikipedia/getPageMediaList": INLINE_URL,
}

# `httpClient` calls that belong to no route this check can enumerate: a request made outside a
# `*Api.kt`, or one no route in its own file reaches. Each is unpinnable where it stands, because a
# pin names a URL its api client builds.
UNENUMERABLE_CALL_SITES: dict[str, str] = {
    "wikipedia/WikipediaProvider.kt#fetchWikidataTitle": (
        "resolves a Wikipedia title from a Wikidata sitelink, and asks Wikidata from the provider "
        "rather than through an api client, so no route names it and no pin can reach it"
    ),
}

NO_PROVIDERS_FINDING = (
    f"::error::no `*Api.kt` found under `{PROVIDER_ROOT}`, so this check scanned nothing. Fix the "
    "path above, or the check is silently passing on an empty tree."
)


def strip_comments(text: str) -> str:
    """Blank out block and line comments, keeping line numbering intact."""
    out: list[str] = []
    in_block = False
    for raw in text.split("\n"):
        line = raw
        if in_block:
            if "*/" in line:
                line = line.split("*/", 1)[1]
                in_block = False
            else:
                out.append("")
                continue
        while "/*" in line:
            before, rest = line.split("/*", 1)
            if "*/" in rest:
                line = before + " " + rest.split("*/", 1)[1]
            else:
                line = before
                in_block = True
                break
        out.append(re.sub(r"//.*$", "", line))
    return "\n".join(out)


def enclosing_body(lines: list[str], start: int) -> str:
    """The text of the function whose header is at `start`, header included.

    Brace-matched from the first `{` at or after the header; an expression body (`= ...`) runs to the
    first later line whose indent returns to the header's.
    """
    header_indent = len(lines[start]) - len(lines[start].lstrip())
    depth = 0
    seen_brace = False
    collected: list[str] = []
    for index in range(start, len(lines)):
        line = lines[index]
        collected.append(line)
        for char in line:
            if char == "{":
                depth += 1
                seen_brace = True
            elif char == "}":
                depth -= 1
        if seen_brace and depth <= 0:
            return "\n".join(collected)
        if not seen_brace and index > start and line.strip() and (len(line) - len(line.lstrip())) <= header_indent:
            return "\n".join(collected[:-1])
    return "\n".join(collected)


def after_signature(body: str, name: str) -> str:
    """`body` with the function's own signature removed.

    An expression body (`fun f() = g(h())`) carries its whole call on the header line, so dropping
    the header wholesale loses the calls it makes. Cut at the end of the parameter list instead.
    """
    match = re.search(rf"fun\s+{re.escape(name)}\s*(?:<[^>]*>)?\s*\(", body)
    if not match:
        return body
    depth = 0
    for index in range(match.end() - 1, len(body)):
        if body[index] == "(":
            depth += 1
        elif body[index] == ")":
            depth -= 1
            if depth == 0:
                return body[index + 1 :]
    return body


class Function:
    """One `fun` declaration: where it is, how it is declared, and the body it owns."""

    def __init__(self, name: str, line: int, private: bool, suspend: bool, body: str) -> None:
        self.name = name
        self.line = line
        self.private = private
        self.suspend = suspend
        self.body = body


def functions(text: str) -> list[Function]:
    """Every `fun` declaration in `text`, each with the body it owns and its signature removed."""
    lines = text.split("\n")
    found: list[Function] = []
    for index, line in enumerate(lines):
        match = FUN_RE.match(line)
        if not match:
            continue
        name = match.group(2)
        found.append(
            Function(
                name=name,
                line=index + 1,
                private=bool(PRIVATE_RE.match(line)),
                suspend=match.group(1) == "suspend",
                body=after_signature(enclosing_body(lines, index), name),
            )
        )
    return found


def calls(body: str, name: str) -> bool:
    """Whether `body` calls `name` as a bare function, rather than as somebody else's member."""
    return re.search(rf"(?<![A-Za-z0-9_.]){re.escape(name)}\s*\(", body) is not None


def first_by_name(fns: list[Function]) -> dict[str, Function]:
    """The functions by name, first declaration winning, since a name is the only key a call gives."""
    by_name: dict[str, Function] = {}
    for fn in fns:
        by_name.setdefault(fn.name, fn)
    return by_name


def reaching_http(fns: list[Function]) -> dict[str, bool]:
    """Which functions reach an `httpClient` call, directly or through a chain of same-file calls."""
    by_name = first_by_name(fns)
    reaches = {name: bool(HTTP_CALL_RE.search(fn.body)) for name, fn in by_name.items()}
    callees = {
        name: {other for other in by_name if other != name and calls(fn.body, other)} for name, fn in by_name.items()
    }
    changed = True
    while changed:
        changed = False
        for name in by_name:
            if not reaches[name] and any(reaches[callee] for callee in callees[name]):
                reaches[name] = True
                changed = True
    return reaches


def reachable_from(routes: list[Function], fns: list[Function]) -> set[str]:
    """Names of the functions the file's routes reach, the routes themselves included."""
    by_name = first_by_name(fns)
    frontier = [route.name for route in routes]
    seen = set(frontier)
    while frontier:
        body = by_name[frontier.pop()].body
        for other in by_name:
            if other not in seen and calls(body, other):
                seen.add(other)
                frontier.append(other)
    return seen


def pins_in(text: str) -> list[tuple[str | None, str | None]]:
    """Every `SchemaTarget(` literal in `text`, as its `route =` string and its `url =` expression."""
    pins: list[tuple[str | None, str | None]] = []
    for match in re.finditer(r"SchemaTarget\s*\(", text):
        # Both keys sit in the literal's first few lines, well inside 2000 characters; a window that
        # overran into the next literal could only repeat a pin that is already in this list, so the
        # bound trades a Kotlin parser for a key that cannot be invented, only missed.
        tail = text[match.end() : match.end() + 2000]
        route = re.search(r"route\s*=\s*\"([^\"]*)\"", tail)
        url = re.search(r"url\s*=\s*([^\n]*)", tail)
        pins.append(
            (
                route.group(1) if route else None,
                url.group(1).strip().rstrip(",") if url else None,
            )
        )
    return pins


def url_builders(text: str) -> set[str]:
    """The named `*Url` builders `text` declares — the key a pin's `url =` can be read by."""
    return {match.group(1) for line in text.split("\n") if (match := URL_BUILDER_RE.match(line))}


def is_pinned(route: Function, pins: list[tuple[str | None, str | None]], builders: set[str]) -> bool:
    """Whether `route` can be tied to one of `pins` by either mechanical key."""
    for pin_route, url_expr in pins:
        if pin_route and pin_route == route.name:
            return True
        if url_expr and any(builder in url_expr and calls(route.body, builder) for builder in builders):
            return True
    return False


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def unpinned_message(key: str) -> str:
    return (
        f"upstream route `{key}` is neither tied to a schema pin nor allowlisted, so the daily drift "
        f"watch never asks for it and stays green while its fields move. Give it a `SchemaTarget` — "
        f"`route =` matching this function's name, or `url =` calling a URL builder this function "
        f'itself calls — or add `"{key}": "<one-line reason>"` to ALLOWLIST in '
        f"check_route_pin_coverage.py saying why it is not worth pinning or cannot be tied to its pin."
    )


def stale_allowlist_message(key: str) -> str:
    return (
        f'`"{key}"` is in ALLOWLIST in check_route_pin_coverage.py but is not an unpinned route: '
        f"either it is tied to a pin now, or the function is gone. Delete the line — a list that "
        f"outlives its entries stops reading as the backlog it is."
    )


def outside_api_message(key: str) -> str:
    return (
        f"`{key}` calls `httpClient` from outside an `*Api.kt`, so no route enumeration reaches it "
        f"and no pin can name the URL it builds. Move the call into the provider's api client beside "
        f'the routes it belongs with, or add `"{key}": "<one-line reason>"` to '
        f"UNENUMERABLE_CALL_SITES in check_route_pin_coverage.py."
    )


def unreached_message(key: str) -> str:
    return (
        f"`{key}` calls `httpClient` but no route in its own file reaches it, so the request belongs "
        f"to no route this check can enumerate. Reachability here is same-file: a fetch helper split "
        f"away from its callers hides them. Keep the helper in the file whose routes call it, or add "
        f'`"{key}": "<one-line reason>"` to UNENUMERABLE_CALL_SITES in check_route_pin_coverage.py.'
    )


def stale_call_site_message(key: str) -> str:
    return (
        f'`"{key}"` is in UNENUMERABLE_CALL_SITES in check_route_pin_coverage.py but no such '
        f"unreachable `httpClient` call exists. Delete the line."
    )


def api_files(root: Path) -> list[Path]:
    """Every provider api client, sorted, so findings come out in a stable order."""
    base = root / PROVIDER_ROOT
    return sorted(base.glob("**/[A-Z]*Api.kt")) if base.is_dir() else []


def other_files(root: Path) -> list[Path]:
    """Every provider source that is not an api client."""
    base = root / PROVIDER_ROOT
    if not base.is_dir():
        return []
    apis = set(api_files(root))
    return sorted(path for path in base.glob("**/*.kt") if path not in apis)


def pins_and_builders(directory: Path) -> tuple[list[tuple[str | None, str | None]], set[str]]:
    """Every pin and every URL builder the provider directory declares, wherever in it they sit."""
    pins: list[tuple[str | None, str | None]] = []
    builders: set[str] = set()
    for path in sorted(directory.glob("*.kt")):
        text = strip_comments(path.read_text(encoding="utf-8"))
        pins.extend(pins_in(text))
        builders |= url_builders(text)
    return pins, builders


def run(
    root: Path,
    *,
    allowlist: dict[str, str] | None = None,
    call_sites: dict[str, str] | None = None,
) -> list[str]:
    """All findings for the tree at `root`.

    `allowlist` and `call_sites` default to the module-level lists; a test passes its own so the
    exemption paths are proved by something other than the real tree's contents.
    """
    allow = ALLOWLIST if allowlist is None else allowlist
    allow_sites = UNENUMERABLE_CALL_SITES if call_sites is None else call_sites
    apis = api_files(root)
    if not apis:
        return [NO_PROVIDERS_FINDING]

    findings: list[str] = []
    unpinned: set[str] = set()
    sites: set[str] = set()

    for path in apis:
        provider = path.parent.name
        rel = path.relative_to(root).as_posix()
        text = strip_comments(path.read_text(encoding="utf-8"))
        fns = functions(text)
        reaches = reaching_http(fns)
        routes = [fn for fn in fns if fn.suspend and not fn.private and reaches[fn.name]]
        pins, builders = pins_and_builders(path.parent)

        for route in routes:
            if is_pinned(route, pins, builders):
                continue
            key = f"{provider}/{route.name}"
            unpinned.add(key)
            if key not in allow:
                findings.append(error(rel, route.line, unpinned_message(key)))

        covered = reachable_from(routes, fns)
        for fn in fns:
            if not HTTP_CALL_RE.search(fn.body) or fn.name in covered:
                continue
            key = f"{provider}/{path.name}#{fn.name}"
            sites.add(key)
            if key not in allow_sites:
                findings.append(error(rel, fn.line, unreached_message(key)))

    for path in other_files(root):
        text = strip_comments(path.read_text(encoding="utf-8"))
        if not HTTP_CALL_RE.search(text):
            continue
        rel = path.relative_to(root).as_posix()
        for fn in functions(text):
            if not HTTP_CALL_RE.search(fn.body):
                continue
            key = f"{path.parent.name}/{path.name}#{fn.name}"
            sites.add(key)
            if key not in allow_sites:
                findings.append(error(rel, fn.line, outside_api_message(key)))

    findings.extend(f"::error::{stale_allowlist_message(key)}" for key in sorted(allow) if key not in unpinned)
    findings.extend(f"::error::{stale_call_site_message(key)}" for key in sorted(allow_sites) if key not in sites)
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail on an upstream route that is neither tied to a schema pin nor allowlisted.",
    )
    parser.add_argument("--root", default=".", type=Path, help="repository root (default: .)")
    args = parser.parse_args(argv)

    findings = run(args.root)
    for finding in findings:
        print(finding)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
