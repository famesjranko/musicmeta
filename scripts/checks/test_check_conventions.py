#!/usr/bin/env python3
"""Self-check for check_conventions.py.

A gate nobody has watched fail is not a gate, so every rule here is proved to fire on the exact
violation it exists to catch, and proved not to fire on the thing most likely to be mistaken for
one. Run with: python3 test_check_conventions.py

This file used to be 385 lines, most of it exercising a hand-written Kotlin scanner through string
templates, nested block comments, raw-string quote runs and backtick identifiers. That scanner is
gone (#60) — the bans are absolute, so there is nothing to distinguish code from comment, and the
cases it needed no longer exist.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_conventions import run  # noqa: E402


class ConventionsTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a source file at a repo-relative path."""
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, rel: str, body: str) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, rel, body)
            return run(root)

    # --- no `!!` ---

    def test_double_bang_in_main_source_is_reported(self):
        # Given a main source dereferencing a nullable with the not-null assertion
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the conventions check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it is reported on the line that carries it, with the fix in the message
        self.assertEqual(len(findings), 1)
        self.assertIn("line=3", findings[0])
        self.assertIn("requireNotNull", findings[0])

    def test_double_bang_on_a_platform_type_is_reported(self):
        # Given a `!!` on a Java platform type — the case detekt's UnsafeCallOnNullableType and
        # UnnecessaryNotNullOperator both miss, since neither treats a flexible type as nullable.
        # This is the entire reason the rule survives alongside type-resolved detekt.
        body = 'package a\n\nfun f() = System.getProperty("x")!!.length\n'
        # When the conventions check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it is caught here, because nothing else catches it
        self.assertEqual(len(findings), 1)

    def test_double_bang_glued_to_an_equality_operator_is_reported(self):
        # Given `u!!==v`, which an earlier `!!=` exemption skipped while catching nothing it meant
        # to — `!==` contains no `!!` at all, so the exemption only ever hid real violations
        body = "package a\n\nfun f(u: Int?, v: Int) = u!!==v\n"
        # When the conventions check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it is still a violation
        self.assertEqual(len(findings), 1)

    def test_double_bang_in_a_comment_is_reported(self):
        # Given a comment that writes the operator it is discussing. The ban is deliberately
        # absolute: skipping comments is what cost 610 lines of scanner, oracle and differential
        # test, and there are no such comments in the tree. Reword the comment.
        body = "package a\n\n// never write x!! here\nfun f() = 1\n"
        # When the conventions check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it fires — loudly and wrongly, which is the accepted trade
        self.assertEqual(len(findings), 1)

    def test_clean_main_source_reports_nothing(self):
        # Given a main source handling the null properly
        body = 'package a\n\nfun f(x: String?) = x ?: ""\n'
        # When the conventions check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body), [])

    # --- scope ---

    def test_test_sources_are_not_scanned(self):
        # Given a `!!` in a test source, where asserting a fixture exists is legitimate
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the conventions check runs
        # Then it is not reported: a failed assertion there is a stack trace, not a consumer crash
        self.assertEqual(self.findings_for("musicmeta-core/src/test/kotlin/A.kt", body), [])

    def test_demo_is_not_scanned(self):
        # Given a `!!` in demo/, the external-consumer canary
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the conventions check runs
        # Then it is not reported — the canary's job is to compile like a consumer, not to match
        # house style
        self.assertEqual(self.findings_for("demo/src/main/kotlin/A.kt", body), [])

    def test_root_given_as_dot_still_excludes_demo(self):
        # Given the check invoked with a relative root. The demo/ exclusion compares absolute path
        # prefixes, so an unresolved root silently starts scanning demo/.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "demo/src/main/kotlin/A.kt", "package a\n\nfun f(x: String?) = x!!.length\n")
            import os

            cwd = os.getcwd()
            os.chdir(tmp)
            try:
                from check_conventions import main

                # When it runs with --root .
                # Then demo/ is still excluded and it exits clean
                self.assertEqual(main(["--root", "."]), 0)
            finally:
                os.chdir(cwd)

    # --- @Serializable placement ---

    def test_serializable_on_a_provider_model_is_reported(self):
        # Given @Serializable on a provider model, which widens the serialized surface consumers
        # cache — so a later rename breaks their stored JSON rather than their compile
        body = "package a\n\n@Serializable\ndata class M(val a: String)\n"
        # When the conventions check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/provider/deezer/M.kt", body)
        # Then it is reported with the alternative named
        self.assertEqual(len(findings), 1)
        self.assertIn("EnrichmentData", findings[0])

    def test_serializable_on_an_http_type_is_reported(self):
        # Given the same annotation under http/
        body = "package a\n\n@Serializable\ndata class R(val a: String)\n"
        # When the conventions check runs
        # Then it is reported there too
        self.assertEqual(len(self.findings_for("musicmeta-core/src/main/kotlin/http/R.kt", body)), 1)

    def test_serializable_on_a_public_payload_type_is_allowed(self):
        # Given @Serializable where it belongs — a root-package payload type
        body = "package a\n\n@Serializable\ndata class Metadata(val a: String)\n"
        # When the conventions check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for("musicmeta-core/src/main/kotlin/Metadata.kt", body), [])

    # --- merge-conflict markers ---

    def test_conflict_marker_in_markdown_is_reported(self):
        # Given an unfinished merge in a document. Nothing else in the gate reads Markdown, so
        # before this the whole run went green on a conflicted tree.
        body = "# doc\n<<<<<<< HEAD\na\n=======\nb\n>>>>>>> other\n"
        # When the conventions check runs
        findings = self.findings_for("ARCHITECTURE.md", body)
        # Then both markers are reported
        self.assertEqual(len(findings), 2)
        self.assertIn("Finish the merge", findings[0])

    def test_conflict_marker_in_a_workflow_is_reported(self):
        # Given the same in YAML
        body = "on:\n<<<<<<< HEAD\n  push:\n>>>>>>> other\n"
        # When the conventions check runs
        # Then it is caught there too
        self.assertEqual(len(self.findings_for(".github/workflows/build.yml", body)), 2)

    def test_equals_run_alone_is_not_a_conflict_marker(self):
        # Given a Markdown setext heading underline, which is a run of `=` like a conflict
        # separator. Only the `<<<<<<< ` and `>>>>>>> ` forms are matched, and only at line start,
        # because `=======` alone appears in ordinary documents.
        body = "Heading\n=======\n\ntext\n"
        # When the conventions check runs
        # Then it is not a finding
        self.assertEqual(self.findings_for("README.md", body), [])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
