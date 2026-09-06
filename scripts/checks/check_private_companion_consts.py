#!/usr/bin/env python3
"""Fail on a bare `const val` inside a `private companion object` in main sources.

Kotlin honours the private companion; the JVM does not. `private companion object { const val TAG }`
compiles to `public static final field TAG` on the *enclosing* class, so `apiCheck` records it as
published surface and a Java consumer can read a constant no Kotlin caller could ever see. Deleting
one afterwards is a breaking change for a constant nobody meant to publish.

`private const val` inside the same companion does not leak, and the enclosing class still reads it
unqualified — so the fix costs nothing at any call site, which is why this is a ban rather than an
allowlist.

Nothing else catches it. `apiCheck` freezes the leaked line rather than objecting to it, detekt's
rule set has no rule keyed on companion visibility (`NestedClassesVisibility` is about a nested
*class*, not a member), and `explicitApi()` is satisfied — the declaration's Kotlin visibility is
already stated by the companion.

Main sources of the published modules only. A `const val` in a *public* companion is a deliberate
published constant and is not this; test sources publish nothing.

    python3 check_private_companion_consts.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Anchored at line start after leading indentation: `private companion object` is the whole
# declaration, and a `companion object` that is not private is out of scope.
COMPANION = re.compile(r"^(\s*)private companion object\b")
# `const val NAME`, with no modifier before `const`. Kotlin puts visibility first, so a private one
# reads `private const val` and does not match.
BARE_CONST = re.compile(r"^\s*const val (\w+)")

FIX = (
    "a bare `const val` in a private companion is a public static field on the enclosing class, so "
    "the api dump publishes it and removing it later is a break. Write `private const val` — the "
    "enclosing class still reads it unqualified."
)

# Agent worktrees are full checkouts of this repo on other branches; scanning them would judge
# another change's content and fail this run on a path outside the diff under test. Compared
# against the path relative to `root`, because `root` itself can be one of those worktrees.
AGENT_WORKTREES = ".claude/worktrees/"


def main_sources(root: Path) -> list[Path]:
    """Main sources of the published modules — `demo-cli/` and `demo-web/` publish nothing."""
    return sorted(
        path
        for path in root.glob("musicmeta-*/src/main/**/*.kt")
        if "/build/" not in path.as_posix() and AGENT_WORKTREES not in path.relative_to(root).as_posix()
    )


def leaked_consts(text: str) -> list[tuple[int, str]]:
    """The `(line number, name)` of every bare `const val` under a `private companion object`.

    The companion's extent is read from indentation rather than by counting braces: ktlint owns
    indentation in this repo, so the closing brace of a companion is always the first line indented
    no further than its declaration. Brace counting would need to tell a `{` in a regex literal or
    a string template from a real one, which is a Kotlin front end this gate has no reason to own.
    """
    findings: list[tuple[int, str]] = []
    indent: int | None = None
    for lineno, line in enumerate(text.split("\n"), start=1):
        if indent is None:
            match = COMPANION.match(line)
            if match:
                indent = len(match.group(1))
            continue
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            indent = None
            # The line that closed one companion cannot open another, so no re-test is needed.
            continue
        const = BARE_CONST.match(line)
        if const:
            findings.append((lineno, const.group(1)))
    return findings


def run(root: Path) -> list[str]:
    findings = []
    for path in main_sources(root):
        rel = path.relative_to(root).as_posix()
        for lineno, name in leaked_consts(path.read_text(encoding="utf-8")):
            findings.append(f"::error file={rel},line={lineno}::{rel}:{lineno}: {name}: {FIX}")
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ban a bare `const val` in a private companion.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    sources = main_sources(root)
    if not sources:
        print(
            "::error::no main sources found, so this rule checked nothing. Fix `main_sources()` if the modules moved.",
            file=sys.stderr,
        )
        return 2

    findings = run(root)
    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} leaked private-companion constant(s).", file=sys.stderr)
        return 2

    # The count is printed because a scan that read nothing otherwise reports the same "clean" as
    # one that read everything.
    print(f"No leaked private-companion constants across {len(sources)} main sources.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
