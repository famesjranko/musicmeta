#!/usr/bin/env python3
"""Fail when demo-web's edition-qualifier vocabulary diverges from core's.

`MusicBrainzQualifierFallback.KIND_PATTERNS` names the reissue/edition kinds core strips from a
title before searching MusicBrainz. `EditionQualifier.KIND_PATTERNS` in demo-web is a hand-copied
fork of that list: core's object is `internal`, demo-web is a separate module, and Kotlin's
`internal` does not cross a module boundary, so the list cannot be shared without publishing it.
Publishing it would put a display heuristic under this library's compatibility promise for good,
which is a permanent cost for a fourteen-line duplicate — hence a check instead.

**The rule:** the two `KIND_PATTERNS` lists must hold the same kind names, with the same regex
literals, in the same order. Order is part of the rule because both classifiers take the first
pattern that fullmatches, so reordering them changes which kind `"deluxe box set"` reports.

Only the vocabulary is compared, because only the vocabulary is duplicated. Core's `KIND_KEYWORDS`
has no demo-web counterpart and needs none: it feeds `tagEvidence`, which scores a MusicBrainz
release's disambiguation text against a stripped tag. demo-web strips a title and never ranks a
candidate, so it has nothing to score.

**What this cannot prove.** It compares literals, not behaviour. The two classifiers apply the same
patterns differently on purpose — demo-web lets a sub-phrase be a whitespace-separated *sequence*
of kinds, so `"(Remastered Deluxe Box Set)"` classifies there and not in core — and this check is
blind to that difference by design, so the asymmetry stays free to move. It also cannot see a kind
added to either file outside its `KIND_PATTERNS` list.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CORE_FILE = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzQualifierFallback.kt"
DEMO_FILE = "demo-web/src/main/kotlin/com/landofoz/musicmeta/demoweb/DiscographyGrouping.kt"

DECLARATION = "KIND_PATTERNS: List<KindPattern> = listOf("

# `KindPattern("kind", Regex("""<literal>""", RegexOption.IGNORE_CASE))`, across line breaks: core
# wraps its longest entry over four lines to stay inside the line-length limit.
ENTRY = re.compile(r'KindPattern\(\s*"([^"]*)"\s*,\s*Regex\("""(.*?)"""', re.DOTALL)

NEVER_COMPARED = "so the edition vocabulary was never compared"


class NotFound(Exception):
    """A file did not hold a parsable `KIND_PATTERNS` list, so nothing could be compared."""


def declaration_body(text: str, rel: str) -> str:
    """The text between `listOf(` and its matching `)`, for the `KIND_PATTERNS` declaration.

    Raises `NotFound` rather than returning empty: a parse that quietly yields nothing would let
    this check pass on a file it no longer understands, which is the silent divergence it exists
    to catch.
    """
    anchor = text.find(DECLARATION)
    if anchor < 0:
        raise NotFound(f"{rel} holds no `{DECLARATION}` declaration")
    start = anchor + len(DECLARATION)
    depth = 1
    for index in range(start, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[start:index]
    raise NotFound(f"{rel}'s `{DECLARATION}` is never closed")


def kind_patterns(text: str, rel: str) -> list[tuple[str, str]]:
    """The `(kind, regex literal)` pairs of a file's `KIND_PATTERNS`, in declaration order."""
    entries = ENTRY.findall(declaration_body(text, rel))
    if not entries:
        raise NotFound(f"{rel}'s `KIND_PATTERNS` parsed to zero entries")
    return entries


def line_of(text: str, kind: str) -> int:
    """The 1-based line where `kind` is declared, or the declaration's own line if it is absent."""
    match = re.search(rf'KindPattern\(\s*"{re.escape(kind)}"', text)
    offset = match.start() if match else text.find(DECLARATION)
    return 1 if offset < 0 else text.count("\n", 0, offset) + 1


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def missing_from_demo(kind: str) -> str:
    return (
        f"core strips the edition kind `{kind}` and demo-web does not. Copy its `KindPattern` "
        f"from {CORE_FILE} into `EditionQualifier.KIND_PATTERNS`, at core's position in the list."
    )


def missing_from_core(kind: str) -> str:
    return (
        f"demo-web strips the edition kind `{kind}` and core does not. Either add it to "
        "`MusicBrainzQualifierFallback.KIND_PATTERNS` or drop it here — a kind demo-web collapses "
        "that core will not search for groups the discography under a title core cannot re-enrich."
    )


def pattern_drift(kind: str, core: str, demo: str) -> str:
    return (
        f"the `{kind}` pattern has drifted: core matches `{core}`, demo-web matches `{demo}`. "
        "Copy core's literal verbatim."
    )


def order_drift(core: list[str], demo: list[str]) -> str:
    return (
        f"the kinds match but their order does not: core declares {core}, demo-web declares "
        f"{demo}. Both classifiers take the first pattern that fullmatches, so the order decides "
        "which kind a phrase reports — reorder demo-web to match core."
    )


def findings(root: Path) -> list[str]:
    """All findings for the tree at `root`."""
    for rel in (CORE_FILE, DEMO_FILE):
        if not (root / rel).is_file():
            message = (
                f"{rel} is missing, {NEVER_COMPARED}. Fix the path in "
                "check_edition_vocabulary.py, or the check is silently passing on nothing."
            )
            return [error(rel, 1, message)]

    core_text = (root / CORE_FILE).read_text(encoding="utf-8")
    demo_text = (root / DEMO_FILE).read_text(encoding="utf-8")
    try:
        core = kind_patterns(core_text, CORE_FILE)
        demo = kind_patterns(demo_text, DEMO_FILE)
    except NotFound as failure:
        message = f"{failure}, {NEVER_COMPARED}. Update check_edition_vocabulary.py's parser to match the new shape."
        return [error(DEMO_FILE, 1, message)]

    core_kinds = [kind for kind, _ in core]
    demo_kinds = [kind for kind, _ in demo]
    core_by_kind, demo_by_kind = dict(core), dict(demo)

    out: list[str] = []
    for kind in core_kinds:
        if kind not in demo_by_kind:
            out.append(error(DEMO_FILE, line_of(demo_text, kind), missing_from_demo(kind)))
    for kind in demo_kinds:
        if kind not in core_by_kind:
            out.append(error(DEMO_FILE, line_of(demo_text, kind), missing_from_core(kind)))
    for kind in core_kinds:
        if kind in demo_by_kind and core_by_kind[kind] != demo_by_kind[kind]:
            drift = pattern_drift(kind, core_by_kind[kind], demo_by_kind[kind])
            out.append(error(DEMO_FILE, line_of(demo_text, kind), drift))
    if not out and core_kinds != demo_kinds:
        out.append(error(DEMO_FILE, line_of(demo_text, DECLARATION), order_drift(core_kinds, demo_kinds)))
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fail when demo-web's edition vocabulary diverges from core's.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)
    root = Path(args.root) if args.root else Path(__file__).resolve().parents[2]

    found = findings(root)
    if found:
        for finding in found:
            print(finding, file=sys.stderr)
        print(f"\n{len(found)} edition-vocabulary divergence(s) between core and demo-web.", file=sys.stderr)
        return 2

    count = len(kind_patterns((root / CORE_FILE).read_text(encoding="utf-8"), CORE_FILE))
    print(f"Edition qualifier vocabulary in sync across {count} kinds.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
