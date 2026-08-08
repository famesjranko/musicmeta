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

# A provider is `provider/<name>/` as internal `*Api`, `*Models`, `*Mapper` plus a public
# `*Provider`. Keeping the first three internal is what lets them be renamed without an `apiDump`,
# so a missing `internal` costs the freedom the layout exists to buy.
#
# Read from the committed `api/*.api` rather than from the sources, because the dump is the
# consequence: it catches a public nested type, a typealias and a top-level `fun`'s `…Kt` facade
# just as well as a missing modifier, and it cannot disagree with what `apiCheck` gates. The
# trade-off is that a stale dump reports clean here — `apiCheck` is what fails in that case.
#
# A name check, deliberately, and that is its whole scope: it does not judge whether a `*Provider`
# deserves the name, nor whether an internal file follows the `*Api`/`*Models`/`*Mapper` layout.
# Those stay review's job (`docs/agents/review-checklist.md`). There is no allowlist because the
# rule has an escape hatch that a threshold rule does not: a type that legitimately needs to be
# public is either named `*Provider` or does not belong under `provider/`.
API_PROVIDER_SEGMENT = "/provider/"
API_PROVIDER_SUFFIX = "Provider"
NO_API_DUMPS_FINDING = (
    "::error::no `api/*.api` found, so the public-surface rule checked nothing. Run "
    "`./gradlew apiDump`, or fix `api_dumps()` if the baselines moved."
)
API_PROVIDER_FIX = (
    "only `*Provider` may be public under `provider/`. Mark this `internal` (its *Api, *Models and "
    "*Mapper siblings are) and re-run `./gradlew apiDump` — public here freezes the name under the "
    "compatibility promise."
)


def main_sources(root: Path) -> list[Path]:
    """Main sources of the published modules.

    `demo/` is excluded, and ktlint deliberately no longer follows suit. These rules govern how we
    build internals; the canary's job is to compile against the published surface the way an outside
    consumer does, and holding it to them would make the canary about us instead of about consumers.
    Formatting is the opposite case — it has no bearing on that job, and demo/ is the worked example
    people read — so `demo/build.gradle.kts` applies ktlint against this repo's `.editorconfig`.
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


def api_dumps(root: Path) -> list[Path]:
    """The committed public-ABI baselines, one per published module.

    `rglob`, not a fixed `*/api/*.api` depth: a module moved under a parent directory would
    otherwise stop being scanned, and the scan going quiet is invisible — see `run()`, which
    refuses to report clean on an empty result for exactly that reason.
    """
    return sorted(path for path in root.rglob("api/*.api") if "/build/" not in path.as_posix())


def public_provider_type(line: str) -> str | None:
    """The offending outer type name on an `api/*.api` declaration line, or None if it is allowed.

    A declaration sits at column 0 and names its type by binary name; members are indented, and
    only a member can be `protected`, so `public` alone is the whole set at column 0. Only
    the outer type is judged: `…Provider$Companion` is that provider's own nested type, and a
    nested type of anything else is already condemned by its outer name.
    """
    if not line.startswith("public "):
        return None
    binary_name = next((token for token in line.split() if "/" in token), None)
    if binary_name is None or API_PROVIDER_SEGMENT not in binary_name:
        return None
    outer = binary_name.rsplit("/", 1)[1].split("$", 1)[0]
    return None if outer.endswith(API_PROVIDER_SUFFIX) else outer


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

    dumps = api_dumps(root)
    # An empty result is indistinguishable from a clean one, and this scan is the only reader of
    # `api/*.api` — a rename or a module move would take the rule out with nothing to say so.
    # Unconditional: an earlier `and main_sources(root)` qualifier meant a tree where *both* globs
    # came up empty reported clean, which is the exact silence this guard exists to break. The
    # script only ever runs from the repo root, so there is no legitimate dump-less invocation.
    if not dumps:
        findings.append(NO_API_DUMPS_FINDING)

    for path in dumps:
        rel = path.relative_to(root).as_posix()
        for lineno, line in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
            offender = public_provider_type(line)
            if offender is not None:
                findings.append(error(rel, lineno, f"`{offender}`: {API_PROVIDER_FIX}"))

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

    # Every count is printed, not just the sources: a scan that found nothing to read reports the
    # same "clean" as one that read everything, and the count is the only thing that separates them.
    print(
        f"Conventions clean across {len(main_sources(root))} main sources, "
        f"{len(api_dumps(root))} api dumps, {len(tracked_text_files(root))} text files."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
