#!/usr/bin/env python3
"""Fail on house code conventions that no other gate enforces.

ktlint owns formatting and `apiCheck` owns the public ABI. These three rules are the ones that
were prose in CLAUDE.md with nothing to fail on them, which meant an agent had to read and obey
them rather than being told. Each message names the fix, not just the violation — the audience
is usually an agent, and an observation without a next move wastes a round trip.

Main sources only. Test sources are excluded deliberately and that exclusion is recorded in
ARCHITECTURE.md under "Not enforced" rather than left implicit here.

    python3 check_conventions.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# CLAUDE.md sets 200 as the target and 300 as the max. The four files already over it predate the
# rule; they are listed rather than reformatted so the gate can ship green, and printed on every
# run so they stay visible instead of becoming a permanent silent exemption.
MAX_FILE_LINES = 300
GRANDFATHERED_LONG_FILES = {
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt",
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProvider.kt",
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/GenreTaxonomy.kt",
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzEnricher.kt",
}

# @Serializable belongs on public API payload types only. On a provider model or an http/ type it
# silently widens the serialized surface consumers cache, so a later rename breaks their stored
# JSON rather than their compile.
SERIALIZABLE_BANNED_DIRS = ("/provider/", "/http/")

# ONE alternation matched in a single left-to-right pass. Running these as five sequential
# re.sub passes is wrong and silently disables the gate: `LINE_COMMENT` applied before
# `STRING_LITERAL` treats the `//` in "https://host/" as a comment and blanks the rest of the
# line, so `"https://host/" + u!!.trim()` reported clean. A single pass cannot do that, because
# whichever construct opens first consumes the ones nested inside it.
#
# Order within the alternation is the tie-break at a given position: `"""` must be tried before
# `"`, and both before the `//` that may sit inside them.
NOISE = re.compile(
    r"(?P<block>/\*.*?\*/)"
    r'|(?P<triple>""".*?""")'
    r"|(?P<line>//[^\n]*)"
    r"|(?P<char>'(?:\\.|[^'\\])*')"
    r'|(?P<string>"(?:\\.|[^"\\\n])*")',
    re.DOTALL,
)


def _blank_run(text: str) -> str:
    """Whitespace of the same length, keeping newlines so line numbers survive."""
    return re.sub(r"[^\n]", " ", text)


def _blank_literal(text: str) -> str:
    """Blank a string literal but keep `${...}` template expressions, which are code.

    `"name is ${u!!.trim()}"` contains a real not-null assertion. Blanking the whole literal
    hides it, and interpolation is where `!!` most often hides in Kotlin.
    """
    out: list[str] = []
    i = 0
    while i < len(text):
        start = text.find("${", i)
        if start < 0:
            out.append(_blank_run(text[i:]))
            break
        out.append(_blank_run(text[i:start]))

        # Walk to the brace that closes this one, so `${list.map { it }}` survives intact.
        depth = 0
        end = start + 1
        while end < len(text):
            if text[end] == "{":
                depth += 1
            elif text[end] == "}":
                depth -= 1
                if depth == 0:
                    end += 1
                    break
            end += 1
        else:
            # Unterminated — treat the remainder as literal rather than guessing.
            out.append(_blank_run(text[start:]))
            break

        # Blank the `${` and `}` delimiters, keep the expression. Lengths are preserved so the
        # reported column and every later line number still match the original file.
        out.append("  " + text[start + 2 : end - 1] + " ")
        i = end
    return "".join(out)


def strip_noise(source: str) -> str:
    """Blank comments and string literals so a `!!` inside one is not a violation.

    Replaces with same-length whitespace rather than deleting, so reported line numbers still
    match the original file.
    """

    def blank(match: re.Match[str]) -> str:
        if match.lastgroup in ("string", "triple"):
            return _blank_literal(match.group(0))
        return _blank_run(match.group(0))

    return NOISE.sub(blank, source)


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


def check_no_double_bang(path: Path, rel: str, source: str) -> list[str]:
    """Report every line whose code contains `!!`.

    Any `!!` counts. An earlier version skipped `!!=` to avoid the `!==` operator, but `!==`
    contains no `!!` at all, so that guard caught nothing it meant to and did skip `u!!==v`,
    which is a real violation. The remaining false positive is repeated negation (`!!flag`),
    which is rare and loud; a false negative here is silent, and silent is the worse failure
    for a gate.
    """
    findings = []
    for lineno, line in enumerate(strip_noise(source).splitlines(), start=1):
        if "!!" in line:
            findings.append(
                f"::error file={rel},line={lineno}::`!!` is banned in main sources. "
                f"Handle the null: use `?:` with a real fallback, an early return, or "
                f'`requireNotNull(x) {{ "why this cannot be null" }}` if it truly cannot be.'
            )
    return findings


def check_serializable_placement(path: Path, rel: str, source: str) -> list[str]:
    posix = path.as_posix()
    if not any(marker in posix for marker in SERIALIZABLE_BANNED_DIRS):
        return []
    findings = []
    for lineno, line in enumerate(strip_noise(source).splitlines(), start=1):
        if "@Serializable" in line:
            findings.append(
                f"::error file={rel},line={lineno}::@Serializable does not belong on a provider "
                f"or http type. Keep it on the public payload types (EnrichmentData subtypes, "
                f"EnrichmentIdentifiers) and map this type into one of those instead."
            )
    return findings


def check_file_length(path: Path, rel: str, source: str) -> list[str]:
    lines = len(source.splitlines())
    if lines <= MAX_FILE_LINES or rel in GRANDFATHERED_LONG_FILES:
        return []
    return [
        f"::error file={rel},line={MAX_FILE_LINES}::{rel} is {lines} lines (max "
        f"{MAX_FILE_LINES}). Split it — usually one responsibility has outgrown the file, so "
        f"move that out rather than shaving lines off the rest."
    ]


CHECKS = (check_no_double_bang, check_serializable_placement, check_file_length)


def run(root: Path) -> list[str]:
    findings = []
    for path in main_sources(root):
        rel = path.relative_to(root).as_posix()
        source = path.read_text(encoding="utf-8")
        for check in CHECKS:
            findings.extend(check(path, rel, source))
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

    scanned = len(main_sources(root))
    print(f"Conventions clean across {scanned} main sources.")
    if GRANDFATHERED_LONG_FILES:
        print(f"Grandfathered over {MAX_FILE_LINES} lines (not exemptions — shrink when touched):")
        for rel in sorted(GRANDFATHERED_LONG_FILES):
            print(f"  {rel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
