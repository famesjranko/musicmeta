#!/usr/bin/env python3
"""Self-check for check_schema_pin_coverage.py.

A gate nobody has watched fail is not a gate, so every path a provider directory can take is proved
here: declared and registered passes, declared but unregistered is reported, undeclared is reported,
and an allowlisted provider passes without either. The two ways the check itself can scan nothing —
no provider directories, no registry file — are proved too, because both would otherwise pass
silently and read as a clean bill of health. Run with: python3 test_check_schema_pin_coverage.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_schema_pin_coverage import PROVIDER_ROOT, REGISTRY, run  # noqa: E402

REGISTERED = "internal fun allSchemaPinTargets() = FooApi.SCHEMA_PIN_TARGETS\n"
REGISTERED_IMPORT = "import com.landofoz.musicmeta.provider.foo.FooApi\n"


class SchemaPinCoverageTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a source file at a repo-relative path."""
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(
        self,
        files: dict[str, str],
        *,
        allowlist: dict[str, str] | None = None,
    ) -> list[str]:
        """Findings for a tree holding just these files under a fresh temp root."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, body in files.items():
                self.write(root, rel, body)
            return run(root, allowlist=allowlist)

    # --- the four paths a provider directory can take ---

    def test_provider_declaring_and_registering_a_target_list_passes(self):
        # Given - a provider that declares a target list, named by the registry
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n",
                f"{PROVIDER_ROOT}/foo/FooApi.kt": "val SCHEMA_PIN_TARGETS = listOf<Any>()\n",
                REGISTRY: REGISTERED_IMPORT + REGISTERED,
            },
        )
        # Then - nothing is reported
        self.assertEqual(findings, [])

    def test_provider_declaring_a_keyed_target_function_passes(self):
        # Given - a keyed provider, whose routes take the credential as an argument
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n",
                f"{PROVIDER_ROOT}/foo/FooApi.kt": "fun schemaPinTargets(key: String) = listOf<Any>()\n",
                REGISTRY: REGISTERED_IMPORT + REGISTERED,
            },
        )
        # Then - the function spelling counts as a declaration
        self.assertEqual(findings, [])

    def test_provider_with_no_target_list_is_reported(self):
        # Given - a provider whose files never declare a target list
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n",
                REGISTRY: REGISTERED_IMPORT + REGISTERED,
            },
        )
        # Then - it is reported, naming the provider and the marker it needs
        self.assertEqual(len(findings), 1)
        self.assertIn(f"file={PROVIDER_ROOT}/foo/FooProvider.kt", findings[0])
        self.assertIn("provider/foo/", findings[0])
        self.assertIn("SCHEMA_PIN_TARGETS", findings[0])

    def test_a_registry_that_only_imports_the_provider_is_reported(self):
        # Given - a registry that imports the provider's Api class but never sums its target list
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n",
                f"{PROVIDER_ROOT}/foo/FooApi.kt": "val SCHEMA_PIN_TARGETS = listOf<Any>()\n",
                REGISTRY: REGISTERED_IMPORT + "internal fun allSchemaPinTargets() = emptyList<Any>()\n",
            },
        )
        # Then - it is reported: resolving the name is not making a request
        self.assertEqual(len(findings), 1)
        self.assertIn("never reads", findings[0])

    def test_provider_whose_target_list_the_registry_never_names_is_reported(self):
        # Given - a provider that declares a target list nothing registers
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n",
                f"{PROVIDER_ROOT}/foo/FooApi.kt": "val SCHEMA_PIN_TARGETS = listOf<Any>()\n",
                REGISTRY: "internal fun allSchemaPinTargets() = emptyList<Any>()\n",
            },
        )
        # Then - it is reported: a list nothing walks makes no request
        self.assertEqual(len(findings), 1)
        self.assertIn("never reads", findings[0])

    def test_allowlisted_provider_passes_without_a_target_list(self):
        # Given - a provider with no target list, named in the allowlist with a reason
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n",
                REGISTRY: REGISTERED_IMPORT + REGISTERED,
            },
            allowlist={"foo": "serves no route an answer depends on"},
        )
        # Then - nothing is reported
        self.assertEqual(findings, [])

    # --- the two ways this check can scan nothing ---

    def test_a_tree_with_no_provider_directories_fails(self):
        # Given - a tree with no provider directory at all
        findings = self.findings_for({REGISTRY: REGISTERED})
        # Then - the check fails rather than passing on having read nothing
        self.assertEqual(len(findings), 1)
        self.assertIn("scanned nothing", findings[0])

    def test_a_tree_with_no_registry_fails(self):
        # Given - provider directories but no registry file
        findings = self.findings_for(
            {f"{PROVIDER_ROOT}/foo/FooProvider.kt": "package a\n\nclass FooProvider\n"},
        )
        # Then - the check fails rather than reporting every provider as unregistered
        self.assertEqual(len(findings), 1)
        self.assertIn("is missing", findings[0])

    # --- the real tree ---

    def test_the_repository_itself_passes(self):
        # Given - this repository, at the root two levels above scripts/checks/
        root = Path(__file__).resolve().parents[2]
        # When - the check runs over it
        findings = run(root)
        # Then - every provider declares and registers a target list
        self.assertEqual(findings, [])


if __name__ == "__main__":
    unittest.main()
