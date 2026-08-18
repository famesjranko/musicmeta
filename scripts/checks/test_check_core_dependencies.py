#!/usr/bin/env python3
"""Self-check for check_core_dependencies.py.

The rule is worth nothing unless a new dependency actually fails, and worth less than nothing if a
test dependency does. Both directions are proved here, along with the parse holding up on the
shapes a real build script takes. Run with: python3 test_check_core_dependencies.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_core_dependencies import MODULE, run  # noqa: E402

REAL = """plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // JSON parsing
    implementation(libs.json)

    // Serialization (for cache layer consumers)
    api(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing)
    testFixturesImplementation(libs.bundles.testing)
    testFixturesImplementation(libs.json)
}
"""


class CoreDependenciesTest(unittest.TestCase):
    def findings_for(self, body: str) -> list[str]:
        """Findings for a tree whose core build script is this body."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / MODULE
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")
            return run(root)

    def test_the_real_dependency_set_passes(self):
        # Given core's dependencies as the build script declares them today
        # When the check runs
        # Then nothing is reported — the allowlist and the build script agree
        self.assertEqual(self.findings_for(REAL), [])

    def test_a_new_implementation_dependency_is_reported(self):
        # Given a fourth runtime dependency, the change this check exists to stop. It compiles,
        # every test passes, and `apiCheck` sees nothing — a transitive is not part of the ABI.
        body = REAL.replace(
            "    // Testing",
            "    implementation(libs.okhttp)\n\n    // Testing",
        )
        # When the check runs
        findings = self.findings_for(body)
        # Then it names the accessor and says where a dependency may legitimately go
        self.assertEqual(len(findings), 1)
        self.assertIn("libs.okhttp", findings[0])
        self.assertIn("adapter module", findings[0])

    def test_a_new_api_dependency_is_reported(self):
        # Given the worse form: `api` puts it on every consumer's compile classpath
        body = REAL.replace("    api(libs.kotlinx.serialization.json)", "    api(libs.room.runtime)")
        # When the check runs
        findings = self.findings_for(body)
        # Then it is reported
        self.assertEqual(len(findings), 1)
        self.assertIn("libs.room.runtime", findings[0])

    def test_a_new_test_dependency_is_not_reported(self):
        # Given a test-only dependency, which reaches no consumer. Policing it would make the rule
        # about our own convenience rather than about the published surface.
        body = REAL.replace(
            "    testImplementation(libs.bundles.testing)",
            "    testImplementation(libs.bundles.testing)\n    testImplementation(libs.robolectric)",
        )
        # When the check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for(body), [])

    def test_a_dependency_after_a_trailing_lambda_is_still_read(self):
        # Given an *allowed* declaration carrying a configuration block, and the offending one
        # after it. Anything that reads to the next `}` instead of counting braces ends the
        # `dependencies` block at the lambda and never sees the line that follows.
        body = REAL.replace(
            "    implementation(libs.kotlinx.coroutines.core)",
            "    implementation(libs.kotlinx.coroutines.core) {\n        isTransitive = false\n    }\n"
            "    implementation(libs.okhttp)",
        )
        # When the check runs
        findings = self.findings_for(body)
        # Then the dependency past the lambda is reported rather than swallowed with the block
        self.assertEqual(len(findings), 1)
        self.assertIn("libs.okhttp", findings[0])

    def test_a_raw_coordinate_is_reported(self):
        # Given a dependency written as a coordinate string rather than a catalog accessor — the
        # form a paste from a README takes, and the one a parse that only understands `libs.x`
        # skips rather than reports.
        body = REAL.replace(
            "    // Testing",
            '    implementation("com.squareup.okhttp3:okhttp:4.12.0")\n\n    // Testing',
        )
        # When the check runs
        findings = self.findings_for(body)
        # Then it is reported, and the fix names the catalog rather than the allowlist
        self.assertEqual(len(findings), 1)
        self.assertIn("okhttp", findings[0])
        self.assertIn("libs.versions.toml", findings[0])

    def test_a_named_argument_declaration_is_reported(self):
        # Given the other unparseable shape: group/name/version as named arguments
        body = REAL.replace(
            "    // Testing",
            '    implementation(group = "org.x", name = "y", version = "1.0")\n\n    // Testing',
        )
        # When the check runs
        findings = self.findings_for(body)
        # Then it is reported rather than skipped for being unrecognised
        self.assertEqual(len(findings), 1)
        self.assertIn("libs.versions.toml", findings[0])

    def test_a_nested_call_argument_is_read_whole(self):
        # Given a nested call, which a scan to the first `)` would truncate mid-argument
        body = REAL.replace(
            "    // Testing",
            "    implementation(platform(libs.some.bom))\n\n    // Testing",
        )
        # When the check runs
        findings = self.findings_for(body)
        # Then the whole declaration is quoted back, not the fragment before the inner `)`.
        # Asserted on the full `configuration(argument)` form: a truncated argument still reads as
        # a substring of the message, because the f-string supplies the closing paren itself.
        self.assertEqual(len(findings), 1)
        self.assertIn("`implementation(platform(libs.some.bom))`", findings[0])

    def test_a_project_dependency_is_reported_without_catalog_advice(self):
        # Given core depending on an adapter, which inverts the layering rather than adding a
        # library. It is still a finding, but "declare it in the version catalog" would be wrong.
        body = REAL.replace(
            "    // Testing",
            '    implementation(project(":musicmeta-okhttp"))\n\n    // Testing',
        )
        # When the check runs
        findings = self.findings_for(body)
        # Then it is reported, and the fix talks about layering rather than the catalog
        self.assertEqual(len(findings), 1)
        self.assertIn("depends on another module", findings[0])
        self.assertNotIn("libs.versions.toml", findings[0])

    def test_a_raw_coordinate_on_a_test_configuration_is_not_reported(self):
        # Given the same unparseable form on a configuration that reaches no consumer. Reporting
        # it would make the rule about our own convenience rather than the published surface.
        body = REAL.replace(
            "    // Testing",
            '    testImplementation("io.mockk:mockk:1.13.9")\n\n    // Testing',
        )
        # When the check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for(body), [])

    def test_a_missing_dependencies_block_is_reported(self):
        # Given a build script this check cannot parse — a refactor, or a move to a convention
        # plugin. Reporting clean here is the silence the rule is supposed to break.
        body = "plugins {\n    alias(libs.plugins.kotlin.jvm)\n}\n"
        # When the check runs
        findings = self.findings_for(body)
        # Then the parse failure itself is the finding
        self.assertEqual(len(findings), 1)
        self.assertIn("read nothing", findings[0])

    def test_a_missing_build_script_is_reported(self):
        # Given a tree where the module moved
        with tempfile.TemporaryDirectory() as tmp:
            # When the check runs
            findings = run(Path(tmp))
        # Then the absence is reported rather than passed over
        self.assertEqual(len(findings), 1)
        self.assertIn("checked nothing", findings[0])

    def test_the_finding_points_at_the_declaration_line(self):
        # Given an offending declaration at a known line of the file
        body = REAL.replace("    // Testing", "    implementation(libs.okhttp)\n\n    // Testing")
        expected = body.split("\n").index("    implementation(libs.okhttp)") + 1
        # When the check runs
        findings = self.findings_for(body)
        # Then the annotation points there, not at the block
        self.assertIn(f"line={expected}", findings[0])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
