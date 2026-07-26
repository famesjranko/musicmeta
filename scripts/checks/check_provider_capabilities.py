#!/usr/bin/env python3
"""Fail when a provider's feature doc and its declared capabilities disagree.

`docs/providers/<name>.md` has a `## What We Extract` table whose first column is an
`EnrichmentType` name. `provider/<name>/` declares the same names in `override val capabilities`.
Two lists of enum constants, one set comparison — a doc claiming a type the code dropped is as wrong
as a doc missing one the code gained, so both directions fail.

This is the only checkable part of a feature doc. The prose around the table is not checked and
should not grow a checker.

**Why not the compiler, `apiCheck`, or detekt.** None of them read Markdown. `apiCheck` records
`public fun getCapabilities ()Ljava/util/List;` — the accessor, never the list's contents — so every
capability could be deleted without moving the baseline. `check_conventions.py` does absolute
substring bans, which cannot express a set comparison.

**Why here and not a Kotlin test.** A test can enumerate the packages (`BuilderDefaultProvidersTest`
already constructs every provider) and would read the real runtime list instead of parsing Kotlin.
The reason it lives here is that it also runs under `./check --fast`, and that the JVM route needs
every provider reachable from `withDefaultProviders()` with a key supplied. That is true today; if it
stops being true, or if the parser below costs more than it saves, moving this to a Kotlin test is
the smaller design and this docstring is where to record that it was chosen against.

**What the parser reads.** `capabilities` must be `listOf(...)` or `buildList { ... }` containing
only `ProviderCapability(...)` calls, `add`, and `if`/`else` scaffolding — each capability naming
exactly one `EnrichmentType`. Conditionals are fine, and their conditions are not inspected:
ListenBrainz gates `ARTIST_RADIO_DISCOVERY` on an auth token, and a doc describes the whole package,
so a capability the code can decline to register is still one it declares. Anything else in the
initializer — a bare identifier, a helper call, an `addAll`, a `map` over the enum — is rejected
**with a message naming the file rather than a skip**, because it could contribute a capability the
parser cannot see, and a check that passes on a file it did not understand is worse than no check
(`ARCHITECTURE.md`).

A provider package with no doc yet is skipped, so feature docs can land one at a time. A doc with no
matching package fails, as does a missing `docs/providers/` — that is the dangling-file failure a
directory of docs invites, and the self-disabling failure a directory that moves invites.

    python3 check_provider_capabilities.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PROVIDER_ROOT = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider"
ENUM_SOURCE = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentType.kt"
DOC_ROOT = "docs/providers"

EXTRACT_HEADING = "## What We Extract"
# The doc's side of the contract: a table cell holding exactly one backticked SCREAMING_CASE name.
DOC_NAME = re.compile(r"^`([A-Z][A-Z0-9_]*)`$")
ENUM_CONSTANT = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(", re.MULTILINE)
CAPABILITIES_DECL = re.compile(r"override\s+val\s+capabilities\b[^=]*=\s*")
LIST_OF = re.compile(r"listOf\s*\(")
BUILD_LIST = re.compile(r"buildList\s*\{")
CAPABILITY_CALL = re.compile(r"ProviderCapability\s*\(")
CONDITION = re.compile(r"\b(?:if|while)\s*\(")
TYPE_REF = re.compile(r"\bEnrichmentType\.([A-Z][A-Z0-9_]*)\b")
WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
# What may remain once capability calls and `if (...)` conditions are excised. Everything else —
# a bare `SHARED_CAPABILITY`, an `addAll(...)`, a `map` — is a way to contribute a capability
# invisibly, so the remainder is checked by allow-list rather than by banning known-bad shapes.
SCAFFOLDING_ALLOWED = {"add", "if", "else"}

SHAPE_FIX = (
    "the check reads `listOf(...)` or `buildList { ... }` holding only `ProviderCapability(...)` "
    "calls, `add`, and `if`/`else`. Write the list that way, or teach "
    "scripts/checks/check_provider_capabilities.py the new shape"
)


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


class Unreadable(Exception):
    """The parser met a shape it does not understand. Always fatal, never a skip."""

    def __init__(self, lineno: int, message: str) -> None:
        super().__init__(message)
        self.lineno = lineno
        self.message = message


def blank_comments(text: str) -> str:
    """Replace every comment with spaces, preserving length and newlines.

    Length-preserving so offsets and reported line numbers stay true, and applied *before* any
    bracket matching — a stray `)` in a comment would otherwise end the capabilities list early and
    hide every capability below it. A `//` inside a string literal elsewhere in the file is blanked
    too; harmless, because only the capabilities region is ever read and no offset moves.
    """

    def blank(match: re.Match[str]) -> str:
        return "".join(c if c == "\n" else " " for c in match.group(0))

    return re.sub(r"/\*.*?\*/|//[^\n]*", blank, text, flags=re.DOTALL)


def balanced(text: str, open_at: int) -> int:
    """Index just past the bracket matching the one at `open_at`."""
    closing = {"(": ")", "{": "}"}[text[open_at]]
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == text[open_at]:
            depth += 1
        elif text[i] == closing:
            depth -= 1
            if depth == 0:
                return i + 1
    raise Unreadable(text.count("\n", 0, open_at) + 1, f"unclosed `{text[open_at]}` in `capabilities`")


def enum_constants(root: Path) -> set[str]:
    """Every `EnrichmentType` name, so a typo in a doc is caught as a typo."""
    source = root / ENUM_SOURCE
    if not source.is_file():
        raise Unreadable(1, f"{ENUM_SOURCE} is missing — this check cannot validate any doc without it")
    body = source.read_text(encoding="utf-8").split("enum class EnrichmentType", 1)[-1]
    names = set(ENUM_CONSTANT.findall(body))
    if not names:
        raise Unreadable(1, f"{ENUM_SOURCE}: no enum constants found — the parser is out of date")
    return names


def initializers(text: str, rel: str) -> list[tuple[str, int]]:
    """Every `override val capabilities` body in a file, with its line.

    Every, not the first: two provider classes can share a file, and reading only one of them would
    under-report the package's capabilities and pass a stale doc.
    """
    found = []
    for decl in CAPABILITIES_DECL.finditer(text):
        start = decl.end()
        lineno = text.count("\n", 0, start) + 1

        opener = LIST_OF.match(text, start) or BUILD_LIST.match(text, start)
        if opener is None:
            raise Unreadable(lineno, f"{rel}: `capabilities` is neither `listOf(` nor `buildList {{` — {SHAPE_FIX}")
        open_at = opener.end() - 1
        close_at = balanced(text, open_at)

        # `listOf(...) + sharedCapabilities()` puts capabilities *outside* the brackets, where
        # nothing below would see them. Anything left on the closing line, or a continuation
        # operator opening the next, means the initializer did not end where the bracket did.
        tail = text[close_at:].split("\n")
        trailing = tail[0].strip()
        continuation = next((line.strip() for line in tail[1:] if line.strip()), "")
        if continuation.startswith(("+", ".", "?:", "?.")):
            trailing = continuation
        if trailing:
            raise Unreadable(lineno, f"{rel}: `capabilities` continues past its list with `{trailing}` — {SHAPE_FIX}")

        found.append((text[open_at + 1 : close_at - 1], lineno))
    return found


def capability_names(body: str, lineno: int, rel: str) -> list[str]:
    """The `EnrichmentType` names in one initializer body, or `Unreadable`."""
    names: list[str] = []
    remainder: list[str] = []
    pos = 0
    while (call := CAPABILITY_CALL.search(body, pos)) is not None:
        end = balanced(body, call.end() - 1)
        found = TYPE_REF.findall(body[call.start() : end])
        if len(found) != 1:
            raise Unreadable(
                lineno,
                f"{rel}: a `ProviderCapability(...)` names {len(found)} "
                f"`EnrichmentType` constants, expected exactly 1 — {SHAPE_FIX}",
            )
        names.append(found[0])
        remainder.append(body[pos : call.start()])
        pos = end
    remainder.append(body[pos:])

    # Everything the capability calls did not account for. Conditions are excised whole — what
    # `if (...)` tests is none of this check's business, and inspecting it only rejects idiomatic
    # Kotlin like `if (apiKeyProvider().isNotBlank())`.
    rest = "".join(remainder)
    while (condition := CONDITION.search(rest)) is not None:
        rest = rest[: condition.start()] + " if " + rest[balanced(rest, condition.end() - 1) :]
    for word in WORD.findall(rest):
        if word not in SCAFFOLDING_ALLOWED:
            raise Unreadable(
                lineno, f"{rel}: `capabilities` holds `{word}`, which the check cannot account for — {SHAPE_FIX}"
            )
    return names


def declared_capabilities(path: Path, rel: str) -> set[str]:
    """Everything one Kotlin file declares, or `Unreadable`. Empty when it declares none."""
    text = blank_comments(path.read_text(encoding="utf-8"))
    names: list[str] = []
    for body, lineno in initializers(text, rel):
        found = capability_names(body, lineno, rel)
        if not found:
            raise Unreadable(lineno, f"{rel}: `capabilities` declares nothing")
        names += found
    if duplicates := {n for n in names if names.count(n) > 1}:
        raise Unreadable(1, f"{rel}: declares {', '.join(sorted(duplicates))} twice")
    return set(names)


def documented_capabilities(doc: Path, rel: str) -> set[str]:
    """The `EnrichmentType` names in a doc's `## What We Extract` table, or `Unreadable`."""
    lines = doc.read_text(encoding="utf-8").split("\n")
    headings = [i for i, line in enumerate(lines) if line.strip() == EXTRACT_HEADING]
    if not headings:
        raise Unreadable(1, f"{rel}: no `{EXTRACT_HEADING}` section")
    if len(headings) > 1:
        raise Unreadable(
            headings[1] + 1,
            f"{rel}: a second `{EXTRACT_HEADING}` section — only the first is read, so the rest would go unchecked",
        )

    names: list[str] = []
    fenced = False
    for lineno, line in enumerate(lines[headings[0] + 1 :], start=headings[0] + 2):
        stripped = line.strip()
        if stripped.startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue  # a fenced block may show the row template; those are not claims
        if stripped.startswith("#"):
            break  # any heading ends the section, including a `###` subsection
        if not stripped.startswith("|"):
            continue
        cell = stripped.split("|")[1].strip()
        if cell == "EnrichmentType" or (cell and set(cell) <= set("-: ")):
            continue  # header row, separator row
        found = DOC_NAME.match(cell)
        if found is None:
            raise Unreadable(
                lineno,
                f"{rel}: first cell {cell!r} is not a backticked `EnrichmentType` "
                "name. Every row of this table is a capability claim",
            )
        names.append(found.group(1))

    if not names:
        raise Unreadable(headings[0] + 1, f"{rel}: `{EXTRACT_HEADING}` lists no capabilities")
    if duplicates := {n for n in names if names.count(n) > 1}:
        raise Unreadable(headings[0] + 1, f"{rel}: {', '.join(sorted(duplicates))} listed twice")
    return set(names)


def run(root: Path) -> list[str]:
    findings: list[str] = []
    provider_root = root / PROVIDER_ROOT
    doc_root = root / DOC_ROOT
    if not provider_root.is_dir():
        return [error(PROVIDER_ROOT, 1, "provider package root is missing")]
    if not doc_root.is_dir():
        # Without this the check reports green having compared nothing, which is the exact
        # silent-skip failure `ARCHITECTURE.md` names.
        return [error(DOC_ROOT, 1, "provider doc root is missing — this check would compare nothing")]
    packages = sorted(p.name for p in provider_root.iterdir() if p.is_dir())

    try:
        known = enum_constants(root)
    except Unreadable as problem:
        return [error(ENUM_SOURCE, problem.lineno, problem.message)]

    for doc in sorted(doc_root.glob("*.md")):
        if doc.stem != "README" and doc.stem not in packages:
            findings.append(
                error(f"{DOC_ROOT}/{doc.name}", 1, f"no `provider/{doc.stem}/` package — rename or delete this doc")
            )

    for name in packages:
        doc = doc_root / f"{name}.md"
        if not doc.is_file():
            continue  # not documented yet; feature docs land one at a time
        doc_rel = f"{DOC_ROOT}/{name}.md"

        declared: set[str] = set()
        readable = True
        # Every Kotlin file in the package, at any depth — `capabilities` in a helper or a
        # subdirectory counts just as much as one in `*Provider.kt`.
        for source in sorted((provider_root / name).rglob("*.kt")):
            rel = f"{PROVIDER_ROOT}/{name}/{source.relative_to(provider_root / name).as_posix()}"
            try:
                declared |= declared_capabilities(source, rel)
            except Unreadable as problem:
                findings.append(error(rel, problem.lineno, problem.message))
                readable = False
        if not readable:
            continue  # a partial `declared` would add a bogus difference on top of a real failure
        if not declared:
            findings.append(error(f"{PROVIDER_ROOT}/{name}", 1, "no `override val capabilities` in the package"))
            continue

        try:
            documented = documented_capabilities(doc, doc_rel)
        except Unreadable as problem:
            findings.append(error(doc_rel, problem.lineno, problem.message))
            continue

        if unknown := sorted(documented - known):
            findings.append(error(doc_rel, 1, f"not `EnrichmentType` constants: {', '.join(unknown)}"))
            continue
        if missing := sorted(declared - documented):
            findings.append(
                error(
                    doc_rel,
                    1,
                    f"declared by `provider/{name}/` but missing from `{EXTRACT_HEADING}`: {', '.join(missing)}",
                )
            )
        if extra := sorted(documented - declared):
            findings.append(
                error(
                    doc_rel,
                    1,
                    f"claimed in `{EXTRACT_HEADING}` but not declared by `provider/{name}/`: {', '.join(extra)}",
                )
            )

    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compare provider feature docs to declared capabilities.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} provider doc/capability disagreement(s).", file=sys.stderr)
        return 2

    print("Provider feature docs match the capabilities their packages declare.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
