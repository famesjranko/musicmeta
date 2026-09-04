#!/usr/bin/env python3
"""Self-tests for check_pom_dependencies.

A gate nobody has watched fail is not a gate, so every finding this check can produce is proved to
fire here. The cases that matter most are the ones where reading nothing would otherwise look like
a clean run: an absent POM, a POM with no `<dependencies>` element, an empty one, and a baseline
holding no dependency lines.
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_pom_dependencies import BASELINE, POM, run, write_baseline  # noqa: E402

POM_XML = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.famesjranko</groupId>
  <artifactId>musicmeta-core</artifactId>
  <version>0.12.0</version>
  <dependencies>
    <dependency>
      <groupId>org.jetbrains.kotlinx</groupId>
      <artifactId>kotlinx-serialization-json-jvm</artifactId>
      <version>1.7.3</version>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>org.json</groupId>
      <artifactId>json</artifactId>
      <version>20231013</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</project>
"""

BASELINE_TEXT = """# The dependencies of musicmeta-core's published POM.
org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3 scope=compile
org.json:json:20231013 scope=runtime
"""


class PomDependenciesTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, **overrides: str | None) -> list[str]:
        """Findings for an otherwise-consistent tree; a None override deletes that file."""
        files: dict[str, str | None] = {POM: POM_XML, BASELINE: BASELINE_TEXT}
        files.update(overrides)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, body in files.items():
                if body is not None:
                    self.write(root, rel, body)
            return run(root)

    def test_matching_baseline_passes(self) -> None:
        # Given - a POM whose dependencies are exactly the ones the baseline records
        # When - the check runs
        findings = self.findings_for()
        # Then - it reports nothing
        self.assertEqual([], findings)

    def test_added_dependency_fails(self) -> None:
        # Given - a POM carrying a dependency the baseline does not
        added = POM_XML.replace(
            "  </dependencies>",
            "    <dependency>\n"
            "      <groupId>com.squareup.okhttp3</groupId>\n"
            "      <artifactId>okhttp</artifactId>\n"
            "      <version>4.12.0</version>\n"
            "      <scope>runtime</scope>\n"
            "    </dependency>\n"
            "  </dependencies>",
        )
        # When - the check runs
        findings = self.findings_for(**{POM: added})
        # Then - the new coordinate is named
        self.assertTrue(any("okhttp" in f for f in findings), findings)

    def test_removed_dependency_fails(self) -> None:
        # Given - a baseline naming a dependency the POM no longer carries
        # When - the check runs
        findings = self.findings_for(**{BASELINE: BASELINE_TEXT + "com.example:gone:1.0 scope=runtime\n"})
        # Then - the missing coordinate is named
        self.assertTrue(any("com.example:gone" in f for f in findings), findings)

    def test_version_change_fails(self) -> None:
        # Given - a POM that moves a dependency's version, which moves every consumer's floor
        # When - the check runs
        findings = self.findings_for(**{POM: POM_XML.replace("20231013", "20240303")})
        # Then - the check reports it
        self.assertTrue(any("20240303" in f for f in findings), findings)

    def test_scope_change_fails(self) -> None:
        # Given - a POM that promotes a runtime dependency to compile
        # When - the check runs
        findings = self.findings_for(**{POM: POM_XML.replace("<scope>runtime</scope>", "<scope>compile</scope>")})
        # Then - the check reports it
        self.assertTrue(findings, "a scope change alters what a consumer compiles against")

    def test_unmodelled_element_moves_the_line(self) -> None:
        # Given - a dependency carrying an element the baseline format does not name explicitly
        optional = POM_XML.replace(
            "      <scope>runtime</scope>\n",
            "      <scope>runtime</scope>\n      <optional>true</optional>\n",
        )
        # When - the check runs
        findings = self.findings_for(**{POM: optional})
        # Then - it fails rather than dropping the element it did not expect
        self.assertTrue(any("optional" in f for f in findings), findings)

    def test_absent_pom_fails(self) -> None:
        # Given - no generated POM at all
        # When - the check runs
        findings = self.findings_for(**{POM: None})
        # Then - it fails, because reading nothing is not a clean run
        self.assertTrue(findings, "an absent POM must fail, not silently pass")

    def test_empty_pom_fails(self) -> None:
        # Given - a POM file that exists but holds nothing
        # When - the check runs
        findings = self.findings_for(**{POM: ""})
        # Then - it fails rather than treating unparseable input as no dependencies
        self.assertTrue(findings, "an unreadable POM must fail, not silently pass")

    def test_pom_without_dependencies_element_fails(self) -> None:
        # Given - a POM whose `<dependencies>` element is absent, as a shape change would leave it
        without = POM_XML[: POM_XML.index("  <dependencies>")] + "</project>\n"
        # When - the check runs
        findings = self.findings_for(**{POM: without})
        # Then - it fails, because the element it reads is the whole gate
        self.assertTrue(findings, "a missing <dependencies> element must fail, not read as zero")

    def test_pom_with_empty_dependencies_element_fails(self) -> None:
        # Given - a POM whose `<dependencies>` element holds no dependency
        empty = POM_XML[: POM_XML.index("  <dependencies>")] + "  <dependencies>\n  </dependencies>\n</project>\n"
        # When - the check runs
        findings = self.findings_for(**{POM: empty})
        # Then - it fails, because core resolving to nothing means the wrong file was read
        self.assertTrue(findings, "zero extracted dependencies must fail, not pass vacuously")

    def test_dependency_missing_a_coordinate_fails(self) -> None:
        # Given - a dependency with no `<groupId>`, which no coordinate can be built from
        broken = POM_XML.replace("      <groupId>org.json</groupId>\n", "")
        # When - the check runs
        findings = self.findings_for(**{POM: broken})
        # Then - it fails rather than emitting a half-formed line
        self.assertTrue(any("groupId" in f for f in findings), findings)

    def test_absent_baseline_fails(self) -> None:
        # Given - no committed baseline to diff against
        # When - the check runs
        findings = self.findings_for(**{BASELINE: None})
        # Then - it fails, because there is nothing to compare with
        self.assertTrue(findings, "an absent baseline must fail, not silently pass")

    def test_baseline_with_no_dependency_lines_fails(self) -> None:
        # Given - a baseline emptied down to its header
        # When - the check runs
        findings = self.findings_for(**{BASELINE: "# header only\n"})
        # Then - it fails rather than comparing against nothing
        self.assertTrue(findings, "an empty baseline must fail, not silently pass")

    def test_write_baseline_makes_the_check_pass(self) -> None:
        # Given - a tree whose baseline disagrees with the POM
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, POM, POM_XML)
            self.write(root, BASELINE, "# header only\n")
            # When - the baseline is regenerated
            self.assertEqual([], write_baseline(root))
            # Then - the check reports nothing, and the file names how to regenerate it
            self.assertEqual([], run(root))
            self.assertIn("make pom-dump", (root / BASELINE).read_text(encoding="utf-8"))

    def test_write_baseline_refuses_an_unreadable_pom(self) -> None:
        # Given - a tree with no generated POM
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, BASELINE, BASELINE_TEXT)
            # When - the baseline is regenerated
            findings = write_baseline(root)
            # Then - it refuses, leaving the committed baseline intact
            self.assertTrue(findings, "regenerating from nothing would silently empty the baseline")
            self.assertEqual(BASELINE_TEXT, (root / BASELINE).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
