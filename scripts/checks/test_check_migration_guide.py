#!/usr/bin/env python3
"""Self-tests for check_migration_guide.

Every finding this check can produce is proved to fire here, and the state at HEAD — the guide
heading `## Unreleased`, the changelog's `[Unreleased]` carrying `### Breaking Changes`, and every
other guide heading naming a real changelog section — is proved not to.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_migration_guide import run  # noqa: E402

CHANGELOG = """# Changelog

## [Unreleased]

### Breaking Changes
- Something narrowed

## [0.12.0] - 2026-08-18

### Fixed
- A thing
"""

CHANGELOG_NO_BREAK = CHANGELOG.replace("### Breaking Changes\n- Something narrowed", "### Fixed\n- Not a break")

GUIDE = """# Migration guide

## Unreleased

### Something narrowed

before/after here.

## 0.12.0

### An older break

before/after here.
"""

GUIDE_NO_UNRELEASED = "# Migration guide\n\n## 0.12.0\n\n### An older break\n\nbefore/after here.\n"

GUIDE_STALE_VERSION = GUIDE.replace("## 0.12.0", "## 0.11.9")


class MigrationGuideTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, guide: str, changelog: str) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "docs/guides/migration.md", guide)
            self.write(root, "CHANGELOG.md", changelog)
            return run(root)

    def test_consistent_tree_has_no_findings(self) -> None:
        self.assertEqual(self.findings_for(GUIDE, CHANGELOG), [])

    def test_guide_heading_naming_an_unknown_version_fails(self) -> None:
        # Given - a guide heading naming a version the changelog has no section for
        # When - the check runs
        findings = self.findings_for(GUIDE_STALE_VERSION, CHANGELOG)
        # Then - it fails, naming the file and the stale heading
        self.assertTrue(findings, "a stale guide heading must fail the gate")
        self.assertIn("docs/guides/migration.md", findings[0])
        self.assertIn("0.11.9", findings[0])

    def test_guide_unreleased_with_no_changelog_breaking_changes_fails(self) -> None:
        # Given - the guide has an Unreleased section but the changelog's Unreleased has no break
        # When - the check runs
        findings = self.findings_for(GUIDE, CHANGELOG_NO_BREAK)
        # Then - it fails, naming the guide
        self.assertTrue(findings, "an Unreleased guide section with nothing unreleased must fail")
        self.assertIn("docs/guides/migration.md", findings[0])

    def test_changelog_breaking_changes_with_no_guide_unreleased_fails(self) -> None:
        # Given - the changelog has a break under Unreleased but the guide has no Unreleased section
        # When - the check runs
        findings = self.findings_for(GUIDE_NO_UNRELEASED, CHANGELOG)
        # Then - it fails, naming the changelog
        self.assertTrue(findings, "an unrecorded break must fail the gate")
        self.assertIn("CHANGELOG.md", findings[0])

    def test_guide_missing_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "CHANGELOG.md", CHANGELOG)
            findings = run(root)
        self.assertTrue(findings)
        self.assertIn("docs/guides/migration.md", findings[0])

    def test_changelog_missing_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "docs/guides/migration.md", GUIDE)
            findings = run(root)
        self.assertTrue(findings)
        self.assertIn("CHANGELOG.md", findings[0])

    def test_head_of_repo_is_consistent(self) -> None:
        """The real files, not fixtures — proves the rule holds today, not just in a fixture."""
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(run(root), [], "docs/guides/migration.md and CHANGELOG.md have drifted")


if __name__ == "__main__":
    unittest.main()
