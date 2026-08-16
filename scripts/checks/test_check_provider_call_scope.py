#!/usr/bin/env python3
"""Self-check for check_provider_call_scope.py.

A gate nobody has watched fail is not a gate, so all three paths a provider directory can take are
proved here: a directory that mentions `ProviderCallScope` passes, one that does not is reported,
and one that does not but is allowlisted passes anyway. Run with: python3
test_check_provider_call_scope.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_provider_call_scope import PROVIDER_ROOT, run  # noqa: E402


class ProviderCallScopeTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a source file at a repo-relative path."""
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, files: dict[str, str], *, allowlist: dict[str, str] | None = None) -> list[str]:
        """Findings for a tree holding just these files under a fresh temp root."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, body in files.items():
                self.write(root, rel, body)
            return run(root, allowlist=allowlist)

    # --- the three paths ---

    def test_provider_directory_mentioning_the_scope_passes(self):
        # Given a provider directory whose Provider.kt mentions ProviderCallScope
        body = "package a\n\nimport com.landofoz.musicmeta.engine.ProviderCallScope\n\nclass FooProvider\n"
        # When the check runs
        findings = self.findings_for({f"{PROVIDER_ROOT}/foo/FooProvider.kt": body})
        # Then nothing is reported
        self.assertEqual(findings, [])

    def test_provider_directory_missing_the_scope_is_reported(self):
        # Given a provider directory whose files never mention ProviderCallScope
        body = "package a\n\nclass FooProvider\n"
        # When the check runs
        findings = self.findings_for({f"{PROVIDER_ROOT}/foo/FooProvider.kt": body})
        # Then it is reported, naming the provider and pointing at the KDoc and the pitfall
        self.assertEqual(len(findings), 1)
        self.assertIn(f"file={PROVIDER_ROOT}/foo/FooProvider.kt", findings[0])
        self.assertIn("provider/foo/", findings[0])
        self.assertIn("ProviderCallScope.kt", findings[0])
        self.assertIn("docs/pitfalls.md", findings[0])
        self.assertIn("§12", findings[0])
        self.assertIn("ALLOWLIST", findings[0])

    def test_allowlisted_provider_directory_passes_without_mentioning_the_scope(self):
        # Given a provider directory that does not mention ProviderCallScope, but is allowlisted
        body = "package a\n\nclass FooProvider\n"
        # When the check runs with foo allowlisted, carrying a reason string
        findings = self.findings_for(
            {f"{PROVIDER_ROOT}/foo/FooProvider.kt": body},
            allowlist={"foo": "hits a single disjoint endpoint per type, nothing to memoize"},
        )
        # Then nothing is reported
        self.assertEqual(findings, [])

    # --- supporting behaviour ---

    def test_mention_anywhere_in_the_directory_counts_not_only_in_provider_kt(self):
        # Given a Provider.kt with no mention, but a sibling file in the same directory that has one
        provider_body = "package a\n\nclass FooProvider\n"
        sibling_body = "package a\n\nimport com.landofoz.musicmeta.engine.ProviderCallScope\n"
        # When the check runs
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": provider_body,
                f"{PROVIDER_ROOT}/foo/FooMemos.kt": sibling_body,
            }
        )
        # Then it passes: the rule is "somewhere in the directory", not "in Provider.kt itself"
        self.assertEqual(findings, [])

    def test_directory_with_no_provider_kt_is_not_checked(self):
        # Given a provider-shaped directory that declares no *Provider.kt, alongside a real one
        api_only_body = "package a\n\nclass FooApi\n"
        real_provider_body = (
            "package b\n\nimport com.landofoz.musicmeta.engine.ProviderCallScope\n\nclass BarProvider\n"
        )
        # When the check runs
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooApi.kt": api_only_body,
                f"{PROVIDER_ROOT}/bar/BarProvider.kt": real_provider_body,
            }
        )
        # Then nothing is reported: the rule only binds a directory that declares a Provider
        self.assertEqual(findings, [])

    def test_empty_tree_reports_the_scan_found_nothing(self):
        # Given a tree with no provider directory at all
        # When the check runs
        with tempfile.TemporaryDirectory() as tmp:
            findings = run(Path(tmp))
        # Then the empty scan is itself the finding, not a silent clean pass
        self.assertEqual(len(findings), 1)
        self.assertIn("scanned nothing", findings[0])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
