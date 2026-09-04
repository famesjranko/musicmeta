#!/usr/bin/env python3
"""Fail when `docs/guides/migration.md` names a version, or an `Unreleased` state, the CHANGELOG disagrees with.

The guide groups its breaks by the version each shipped in, newest first, and heads the newest
group `## Unreleased` to match `CHANGELOG.md`'s own section until a release renames it. Nothing
enforced that the two files agreed, so a guide heading could survive naming a version the CHANGELOG
has no section for, or the guide could carry `## Unreleased` (or lack it) independently of whether
there is anything unreleased to describe.

Two rules, both read from the same two files on every commit:

1. Every `## <x>` heading in the guide is either `Unreleased` or a version that appears as
   `## [<x>]` in `CHANGELOG.md`. A guide heading naming a version the changelog has no section for
   is stale — usually a release renamed the changelog heading without renaming this one.
2. `## Unreleased` may appear in the guide only when `CHANGELOG.md`'s `## [Unreleased]` section
   itself contains a `### Breaking Changes` heading, and the converse: a `### Breaking Changes`
   entry under `[Unreleased]` requires an `## Unreleased` section in the guide. This is the
   existence half of "does every break have a migration note" — it cannot see whether an
   individual `### Breaking Changes` *line* has its own guide section; that half stays a gap.

    python3 check_migration_guide.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

MIGRATION_GUIDE = "docs/guides/migration.md"
CHANGELOG = "CHANGELOG.md"

GUIDE_HEADING = re.compile(r"^## (.+?)[ \t]*$", re.MULTILINE)
CHANGELOG_HEADING = re.compile(r"^## \[([^\]]+)\]", re.MULTILINE)
CHANGELOG_UNRELEASED = re.compile(r"^## \[Unreleased\][ \t]*$", re.MULTILINE)
BREAKING = re.compile(r"^### Breaking Changes[ \t]*$", re.MULTILINE)


def _section_body(text: str, match: re.Match[str]) -> str:
    """Everything after `match` up to the next `## ` heading, or the end of the text."""
    rest = text[match.end() :]
    next_heading = rest.find("\n## ")
    return rest if next_heading == -1 else rest[:next_heading]


def run(root: Path) -> list[str]:
    """All findings for the tree at root, formatted for GitHub annotations."""
    guide_path = root / MIGRATION_GUIDE
    changelog_path = root / CHANGELOG
    if not guide_path.exists():
        return [f"::error file={MIGRATION_GUIDE}::file is missing, so its headings cannot be checked."]
    if not changelog_path.exists():
        return [f"::error file={CHANGELOG}::file is missing, so the guide's headings cannot be checked against it."]

    guide_text = guide_path.read_text(encoding="utf-8")
    changelog_text = changelog_path.read_text(encoding="utf-8")

    changelog_versions = set(CHANGELOG_HEADING.findall(changelog_text))
    guide_headings = GUIDE_HEADING.findall(guide_text)

    findings = []
    for heading in guide_headings:
        if heading == "Unreleased":
            continue
        if heading not in changelog_versions:
            findings.append(
                f"::error file={MIGRATION_GUIDE}::heading `## {heading}` names a version "
                f"{CHANGELOG} has no `## [{heading}]` section for. A release likely renamed the "
                f"changelog heading without renaming this one."
            )

    unreleased_match = CHANGELOG_UNRELEASED.search(changelog_text)
    breaking_unreleased = bool(unreleased_match and BREAKING.search(_section_body(changelog_text, unreleased_match)))
    guide_has_unreleased = "Unreleased" in guide_headings

    if guide_has_unreleased and not breaking_unreleased:
        findings.append(
            f"::error file={MIGRATION_GUIDE}::has an `## Unreleased` section, but {CHANGELOG}'s "
            "`## [Unreleased]` has no `### Breaking Changes` heading — there is nothing unreleased "
            "to migrate for."
        )
    if breaking_unreleased and not guide_has_unreleased:
        findings.append(
            f"::error file={CHANGELOG}::`## [Unreleased]` has a `### Breaking Changes` heading, but "
            f"{MIGRATION_GUIDE} has no `## Unreleased` section to describe it in."
        )

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    findings = run(parser.parse_args().root.resolve())
    for finding in findings:
        print(finding)
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
