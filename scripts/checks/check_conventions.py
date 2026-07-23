#!/usr/bin/env python3
"""Fail on the two house conventions that no other gate enforces, plus stray conflict markers.

ktlint owns formatting, type-resolved detekt owns bug patterns, `apiCheck` owns the public ABI.
What is left is two bans that are project decisions rather than general Kotlin advice, and both are
plain substring searches.

**They deliberately do not skip comments or string literals.** An earlier version carried a
hand-written Kotlin scanner — 155 lines classifying every character as code or text, plus a
118-line KotlinLexer oracle and a 337-line differential test to keep it honest — so that a `!!`
inside a comment would not be reported. That machinery existed to make the rules *weaker*. Both
bans are absolute: there are no literal `!!` and no `@Serializable` anywhere in the relevant
sources, comments and strings included, so the substring form passes today and forbids strictly
more. If a comment ever legitimately needs to write one, reword the comment; that is cheaper than
owning a Kotlin front end. (#60)

Main sources only, `demo/` excluded.

    python3 check_conventions.py [--root PATH]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# @Serializable belongs on public API payload types only. On a provider model or an http/ type it
# silently widens the serialized surface consumers cache, so a later rename breaks their stored
# JSON rather than their compile.
SERIALIZABLE_BANNED_DIRS = ("/provider/", "/http/")

DOUBLE_BANG_FIX = (
    "`!!` is banned in main sources. Handle the null: use `?:` with a real fallback, an early "
    'return, or `requireNotNull(x) { "why this cannot be null" }` if it truly cannot be.'
)
SERIALIZABLE_FIX = (
    "@Serializable does not belong on a provider or http type. Keep it on the public payload "
    "types (EnrichmentData subtypes, EnrichmentIdentifiers) and map this type into one of those "
    "instead."
)
# A resolve that was never finished passes every other layer of the gate: ktlint and detekt only
# read Kotlin, ruff and mypy only Python, and nothing at all reads Markdown or YAML. (#65)
#
# Only the two directional markers, and only at line start. `=======` alone is a Markdown setext
# heading underline, and matching it would fail on ordinary documents. These two forms carry no
# meaning anywhere else — including in this file, where they appear inside a tuple rather than at
# the start of a line, which is why no self-exclusion is needed.
CONFLICT_MARKERS = ("<<<<<<< ", ">>>>>>> ")
CONFLICT_FIX = "unresolved merge-conflict marker. Finish the merge before committing."


def main_sources(root: Path) -> list[Path]:
    """Main sources of the published modules.

    `demo/` is excluded for the same reason ktlint skips it: it is a separate composite build whose
    job is to compile against the published surface like an external consumer, not to match house
    style. Holding it to these rules would make the canary about us instead of about consumers.
    """
    return sorted(
        path
        for path in root.glob("*/src/main/**/*.kt")
        if "/build/" not in path.as_posix() and not path.as_posix().startswith(f"{root.as_posix()}/demo/")
    )


def tracked_text_files(root: Path) -> list[Path]:
    """Everything the conflict-marker scan reads: the docs and configs nothing else looks at."""
    suffixes = (".md", ".yml", ".yaml", ".kt", ".kts", ".py", ".sh", ".toml", ".json")
    return sorted(
        path
        for path in root.rglob("*")
        if path.suffix in suffixes
        and path.is_file()
        and "/build/" not in path.as_posix()
        and "/.git/" not in path.as_posix()
    )


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def run(root: Path) -> list[str]:
    findings = []

    for path in main_sources(root):
        rel = path.relative_to(root).as_posix()
        banned_here = any(marker in path.as_posix() for marker in SERIALIZABLE_BANNED_DIRS)
        for lineno, line in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
            if "!!" in line:
                findings.append(error(rel, lineno, DOUBLE_BANG_FIX))
            if banned_here and "@Serializable" in line:
                findings.append(error(rel, lineno, SERIALIZABLE_FIX))

    for path in tracked_text_files(root):
        rel = path.relative_to(root).as_posix()
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for lineno, line in enumerate(text.split("\n"), start=1):
            if line.startswith(CONFLICT_MARKERS):
                findings.append(error(rel, lineno, CONFLICT_FIX))

    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Enforce house code conventions.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    # Resolved, because the demo/ exclusion compares absolute path prefixes — with `--root .`
    # an unresolved root makes that comparison fail and silently starts scanning demo/.
    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} convention violation(s).", file=sys.stderr)
        return 2

    print(f"Conventions clean across {len(main_sources(root))} main sources.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
