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
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "github-workflows"))

from build_release_notes import released_versions  # noqa: E402
from check_migration_guide import run  # noqa: E402
from pin_release import pin_changelog, pin_migration_guide  # noqa: E402

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

GUIDE_WITH_PROSE_HEADING = GUIDE + "\n## Some prose heading\n\nnot a version.\n"


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

    def test_non_version_guide_heading_is_reported_with_its_own_message(self) -> None:
        # Given - a guide heading that is neither Unreleased nor a bare version
        # When - the check runs
        findings = self.findings_for(GUIDE_WITH_PROSE_HEADING, CHANGELOG)
        # Then - it fails with the shape message, not the stale-version message
        self.assertTrue(findings, "a non-version heading must fail the gate")
        self.assertTrue(
            any("guide headings are Unreleased or a bare version" in f for f in findings),
            f"expected the shape message, got: {findings}",
        )

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

    def test_pinning_the_live_files_together_leaves_no_findings(self) -> None:
        """pin_changelog and pin_migration_guide, applied to the real files, must still agree.

        Each is tested against the live repo in its own suite; this is the only place both run
        together, which is what a real release actually does.
        """
        root = Path(__file__).resolve().parents[2]
        changelog_text = (root / "CHANGELOG.md").read_text(encoding="utf-8")
        guide_text = (root / "docs" / "guides" / "migration.md").read_text(encoding="utf-8")

        last = released_versions(changelog_text)[0]
        major, minor, _ = (int(p) for p in last.split("."))
        next_minor = f"{major}.{minor + 1}.0"

        pinned_changelog = pin_changelog(changelog_text, next_minor, "2026-07-23")
        pinned_guide = pin_migration_guide(guide_text, next_minor)

        with tempfile.TemporaryDirectory() as tmp:
            tmp_root = Path(tmp)
            self.write(tmp_root, "CHANGELOG.md", pinned_changelog)
            self.write(tmp_root, "docs/guides/migration.md", pinned_guide)
            self.assertEqual(run(tmp_root), [])


if __name__ == "__main__":
    unittest.main()
