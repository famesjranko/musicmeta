#!/usr/bin/env python3
"""Self-tests for check_release_coordinates.

A gate nobody has watched fail is not a gate, so every finding this check can produce is proved to
fire here, and the versions that legitimately name an older release are proved not to.
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_release_coordinates import run  # noqa: E402

GRADLE = "group=io.github.famesjranko\nversion=0.12.0\n"

ROADMAP = """# musicmeta

## Where We Are (v0.12.0)

v0.12.0 is published to Maven Central and JitPack. Everything below the *Unreleased* block has
shipped; the version is declared once, in root `gradle.properties`.

### Unreleased — lands in the next release

Library code, including breaking changes: see the `[Unreleased]` block in `CHANGELOG.md`, which is
the list. The published 0.12.0 artifact carries none of it.

### Current Coverage

| GENRE_DISCOVERY | **v0.6.0** — static taxonomy |
| ARTIST_RADIO | **v0.6.0** — ordered playlist |
"""

README = """# musicmeta

    implementation("io.github.famesjranko:musicmeta-core:0.12.0")
    implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.12.0")
"""

GUIDE = 'implementation("io.github.famesjranko:musicmeta-okhttp:0.12.0")\n'

GUIDES = ("docs/guides/quick-start.md", "docs/guides/extension-points.md", "docs/guides/android.md")


class ReleaseCoordinatesTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, **overrides: str) -> list[str]:
        """Findings for an otherwise-consistent tree with the named files replaced."""
        files = {"gradle.properties": GRADLE, "ROADMAP.md": ROADMAP, "README.md": README}
        files.update(dict.fromkeys(GUIDES, GUIDE))
        files.update(overrides)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, body in files.items():
                if body is not None:
                    self.write(root, rel, body)
            return run(root)

    def test_consistent_tree_has_no_findings(self) -> None:
        self.assertEqual([], self.findings_for())

    def test_stale_roadmap_prose_under_a_pinned_heading_is_a_finding(self) -> None:
        stale = ROADMAP.replace("v0.12.0 is published to Maven Central", "v0.11.0 is published to Maven Central")
        findings = self.findings_for(**{"ROADMAP.md": stale})
        self.assertTrue(findings, "a stale published-version sentence must fail the gate")
        self.assertIn("ROADMAP.md", findings[0])
        self.assertIn("0.11.0", findings[0])

    def test_stale_unreleased_subsection_is_a_finding(self) -> None:
        stale = ROADMAP.replace("The published 0.12.0 artifact", "The published 0.10.1 artifact")
        findings = self.findings_for(**{"ROADMAP.md": stale})
        self.assertTrue(findings, "the Unreleased subsection names the published version too")
        self.assertIn("0.10.1", findings[0])

    def test_a_version_outside_the_guarded_regions_is_left_alone(self) -> None:
        """`### Current Coverage` names the release a capability landed in — permanently older."""
        self.assertEqual([], self.findings_for())
        self.assertIn("v0.6.0", ROADMAP)

    def test_stale_guide_coordinate_is_a_finding(self) -> None:
        stale = 'implementation("io.github.famesjranko:musicmeta-okhttp:0.11.0")\n'
        findings = self.findings_for(**{"docs/guides/quick-start.md": stale})
        self.assertTrue(findings, "the by-hand guide bump is what this gate exists to catch")
        self.assertIn("quick-start.md", findings[0])

    def test_stale_readme_jitpack_coordinate_is_a_finding(self) -> None:
        stale = README.replace("musicmeta-core:v0.12.0", "musicmeta-core:v0.11.0")
        findings = self.findings_for(**{"README.md": stale})
        self.assertTrue(findings, "the JitPack `v`-prefixed form counts as a coordinate")
        self.assertIn("README.md", findings[0])

    def test_missing_where_we_are_heading_is_a_finding(self) -> None:
        findings = self.findings_for(**{"ROADMAP.md": "# musicmeta\n\nno heading here\n"})
        self.assertTrue(findings, "a renamed heading must fail loudly, not silently pass")
        self.assertIn("Where We Are", findings[0])

    def test_missing_unreleased_subsection_is_a_finding(self) -> None:
        without = ROADMAP.replace("### Unreleased — lands in the next release", "### Something else")
        findings = self.findings_for(**{"ROADMAP.md": without})
        self.assertTrue(findings, "a renamed subsection must fail loudly, not silently pass")
        self.assertIn("Unreleased", findings[0])

    def test_unreadable_declared_version_is_a_finding(self) -> None:
        findings = self.findings_for(**{"gradle.properties": "group=io.github.famesjranko\n"})
        self.assertTrue(findings, "no source of truth means every comparison is vacuous")
        self.assertIn("gradle.properties", findings[0])


if __name__ == "__main__":
    unittest.main()
