#!/usr/bin/env python3
"""Two throwaway route enumerators for probe 13. Not a shipped check.

Arm A: a route is a function whose body calls `httpClient.fetch*`/`post*`.
Arm B: a route is a named URL-builder function that a suspend fun calls.

Both read `provider/*/[A-Z]*Api.kt` and report, per provider, the routes found
and whether each can be matched mechanically to a `SchemaTarget`.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

FUN_RE = re.compile(r"^(\s*)(?:(private|internal|public)\s+)?(?:(suspend)\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]")
HTTP_CALL_RE = re.compile(r"httpClient\s*\.\s*(fetch\w*|post\w*)")
URL_BUILDER_RE = re.compile(r"^\s*(?:(private|internal|public)\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*Url)\s*\(")


def strip_comments(text: str) -> str:
    """Blank out block and line comments, keeping line numbering intact."""
    out = []
    in_block = False
    for line in text.split("\n"):
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
        line = re.sub(r"//.*$", "", line)
        out.append(line)
    return "\n".join(out)


def functions(text: str):
    """Every `fun` declaration with its body text, by brace matching from the header."""
    lines = text.split("\n")
    found = []
    for index, line in enumerate(lines):
        match = FUN_RE.match(line)
        if not match:
            continue
        name = match.group(4)
        private = match.group(2) == "private"
        is_suspend = match.group(3) == "suspend"
        body, end = body_of(lines, index)
        found.append(
            {
                "name": name,
                "line": index + 1,
                "private": private,
                "suspend": is_suspend,
                "body": body,
                "searchable": after_signature(body, name),
                "end": end + 1,
            }
        )
    return found


def after_signature(body: str, name: str) -> str:
    """The function's body without its own signature.

    An expression body (`fun f() = g(h())`) puts the whole call on the header
    line, so dropping the header wholesale loses the calls it makes. Cut instead
    at the end of the parameter list.
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


def body_of(lines, start):
    """Body text of the function whose header starts at `start`.

    Brace-matched from the first `{` at or after the header; an expression body
    (`= ...`) runs to the first line whose indent returns to the header's.
    """
    header_indent = len(lines[start]) - len(lines[start].lstrip())
    depth = 0
    seen_brace = False
    collected = []
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
            return "\n".join(collected), index
        if not seen_brace and index > start:
            stripped = line.strip()
            if stripped and (len(line) - len(line.lstrip())) <= header_indent:
                return "\n".join(collected[:-1]), index - 1
    return "\n".join(collected), len(lines) - 1


def pin_blocks(text: str):
    """The `SchemaTarget(...)` literals in a file, as (route, url-expression) pairs."""
    pins = []
    for match in re.finditer(r"SchemaTarget\s*\(", text):
        tail = text[match.end() : match.end() + 2000]
        route = re.search(r"route\s*=\s*\"([^\"]*)\"", tail)
        url = re.search(r"url\s*=\s*([^\n]*)", tail)
        pins.append(
            {
                "route": route.group(1) if route else None,
                "url_expr": url.group(1).strip().rstrip(",") if url else None,
            }
        )
    return pins


def url_builders(text: str):
    return {
        match.group(2): index + 1
        for index, line in enumerate(text.split("\n"))
        if (match := URL_BUILDER_RE.match(line))
    }


def arm_a(text: str):
    """Functions that call httpClient.fetch*/post* directly."""
    return [
        {"name": fn["name"], "line": fn["line"], "private": fn["private"]}
        for fn in functions(text)
        if HTTP_CALL_RE.search(fn["searchable"])
    ]


def arm_b(text: str):
    """URL builders that a suspend fun in the file calls."""
    builders = url_builders(text)
    callers = {}
    for fn in functions(text):
        if not fn["suspend"]:
            continue
        for builder in builders:
            if re.search(rf"(?<![A-Za-z0-9_]){re.escape(builder)}\s*\(", fn["searchable"]):
                callers.setdefault(builder, []).append(fn["name"])
    return [
        {"name": builder, "line": builders[builder], "callers": sorted(set(callers.get(builder, [])))}
        for builder in sorted(builders)
        if builder in callers
    ]


def arm_a_prime(text: str):
    """Emergent variant, not in the frozen plan.

    A route is a public suspend fun that reaches an `httpClient.fetch*`/`post*`
    call through any chain of same-file calls, so a private fetch helper stops
    hiding its callers.
    """
    fns = functions(text)
    by_name = {}
    for fn in fns:
        by_name.setdefault(fn["name"], fn)
    reaches = {name: bool(HTTP_CALL_RE.search(fn["searchable"])) for name, fn in by_name.items()}
    calls = {
        name: {
            other
            for other in by_name
            if other != name and re.search(rf"(?<![A-Za-z0-9_.]){re.escape(other)}\s*\(", fn["searchable"])
        }
        for name, fn in by_name.items()
    }
    changed = True
    while changed:
        changed = False
        for name in by_name:
            if not reaches[name] and any(reaches[callee] for callee in calls[name]):
                reaches[name] = True
                changed = True
    return [
        {"name": fn["name"], "line": fn["line"], "private": fn["private"]}
        for fn in fns
        if fn["suspend"] and not fn["private"] and reaches[fn["name"]]
    ]


def attributable(route_name: str, body: str, pins, builders) -> bool:
    """Can this route be tied to a pin without a human reading it?

    Two mechanical keys: the pin's `url =` calls a builder this route calls, or
    the pin's `route` string equals the route's own name.
    """
    for pin in pins:
        if pin["route"] and pin["route"] == route_name:
            return True
        if pin["url_expr"]:
            for builder in builders:
                if builder in pin["url_expr"] and re.search(rf"(?<![A-Za-z0-9_]){re.escape(builder)}\s*\(", body):
                    return True
    return False


def analyse(path: Path):
    raw = path.read_text()
    text = strip_comments(raw)
    pins = pin_blocks(text)
    builders = url_builders(text)
    fns = {fn["name"]: fn for fn in functions(text)}

    a_routes = []
    for route in arm_a(text):
        fn = fns[route["name"]]
        a_routes.append(
            {
                **route,
                "attributable": attributable(route["name"], fn["searchable"], pins, builders),
            }
        )

    ap_routes = []
    for route in arm_a_prime(text):
        fn = fns[route["name"]]
        ap_routes.append({**route, "attributable": attributable(route["name"], fn["searchable"], pins, builders)})

    b_routes = []
    for route in arm_b(text):
        pinned = any(route["name"] in (pin["url_expr"] or "") for pin in pins)
        b_routes.append({**route, "attributable": pinned})

    inline_suspend = [
        fn["name"]
        for fn in functions(text)
        if fn["suspend"]
        and not fn["private"]
        and not any(
            re.search(rf"(?<![A-Za-z0-9_]){re.escape(builder)}\s*\(", fn["searchable"])
            for builder in builders
        )
    ]

    return {
        "file": str(path),
        "pins": pins,
        "builders": sorted(builders),
        "arm_a": a_routes,
        "arm_a_prime": ap_routes,
        "arm_b": b_routes,
        "suspend_without_builder": inline_suspend,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    files = sorted(args.root.glob("*/[A-Z]*Api.kt"))
    results = {path.parent.name: analyse(path) for path in files}

    if args.json:
        print(json.dumps(results, indent=2, sort_keys=True))
        return 0

    for name, result in results.items():
        print(f"=== {name}  ({len(result['pins'])} pins, {len(result['builders'])} url builders)")
        print(f"  builders: {', '.join(result['builders']) or '-'}")
        print("  ARM A:")
        for route in result["arm_a"]:
            flag = "private" if route["private"] else "public "
            print(f"    {flag} {route['name']}:{route['line']}  attributable={route['attributable']}")
        print("  ARM A-prime (emergent):")
        for route in result["arm_a_prime"]:
            print(f"    {route['name']}:{route['line']}  attributable={route['attributable']}")
        print("  ARM B:")
        for route in result["arm_b"]:
            print(
                f"    {route['name']}:{route['line']}  pinned={route['attributable']}"
                f"  callers={','.join(route['callers'])}"
            )
        print(f"  suspend fun with no builder call: {', '.join(result['suspend_without_builder']) or '-'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
