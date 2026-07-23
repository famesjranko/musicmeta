#!/usr/bin/env python3
"""Self-check for check_conventions.py.

A gate nobody has watched fail is not a gate, so every rule here is proved to fire on the exact
violation it exists to catch, and proved not to fire on the thing most likely to be mistaken for
one. Run with: python3 test_check_conventions.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_conventions import (  # noqa: E402
    GRANDFATHERED_LONG_FILES,
    MAX_FILE_LINES,
    run,
    strip_noise,
)


class ConventionsTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a Kotlin source at a module-relative path."""
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
        # Then it fails and the message tells the author what to do instead
        self.assertEqual(len(findings), 1)
        self.assertIn("`!!` is banned", findings[0])
        self.assertIn("requireNotNull", findings[0])
        self.assertIn("line=3", findings[0])

    def test_double_bang_inside_a_string_or_comment_is_not_reported(self):
        # Given `!!` appearing only in a string literal and a comment
        body = 'package a\n\n// wow!!\nval s = "loud!!"\nval t = """also!!"""\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported, because neither is a not-null assertion
        self.assertEqual(findings, [])

    def test_not_equals_is_not_a_double_bang(self):
        # Given the `!==` identity operator and a negated comparison
        body = "package a\n\nval b = x !== y\nval c = !!!flag\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then only the genuine double-negation-adjacent `!!` is caught, not `!==`
        self.assertTrue(all("line=3" not in f for f in findings))

    def test_test_sources_are_not_scanned(self):
        # Given a test source using `!!`, which is allowed there
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/test/kotlin/A.kt", body)
        # Then it is ignored, because the rule is scoped to main sources
        self.assertEqual(findings, [])

    def test_demo_is_not_scanned(self):
        # Given the demo consumer canary breaking a house rule
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the check runs
        findings = self.findings_for("demo/src/main/kotlin/A.kt", body)
        # Then it is ignored, because demo compiles as an external consumer would
        self.assertEqual(findings, [])

    # --- @Serializable placement ---

    def test_serializable_on_a_provider_model_is_reported(self):
        # Given @Serializable applied to a provider's internal model
        body = "package a\n\n@Serializable\ndata class DeezerAlbum(val id: Long)\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/M.kt", body)
        # Then it fails and points at the payload types that should carry it instead
        self.assertEqual(len(findings), 1)
        self.assertIn("@Serializable does not belong", findings[0])
        self.assertIn("EnrichmentData", findings[0])

    def test_serializable_on_an_http_type_is_reported(self):
        # Given @Serializable on an http/ infrastructure type
        body = "package a\n\n@Serializable\nclass HttpThing\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/T.kt", body)
        # Then it is reported for the same reason
        self.assertEqual(len(findings), 1)

    def test_serializable_on_a_public_payload_type_is_allowed(self):
        # Given @Serializable on a top-level public payload type, which is its intended home
        body = "package a\n\n@Serializable\ndata class EnrichmentIdentifiers(val mbid: String?)\n"
        # When the check runs
        findings = self.findings_for(
            "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentIdentifiers.kt", body
        )
        # Then nothing is reported
        self.assertEqual(findings, [])

    # --- file length ---

    def test_file_over_the_cap_is_reported(self):
        # Given a main source one line past the cap
        body = "package a\n" + "// filler\n" * MAX_FILE_LINES
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/Long.kt", body)
        # Then it fails and the message says to split rather than shave
        self.assertEqual(len(findings), 1)
        self.assertIn("Split it", findings[0])

    def test_file_exactly_at_the_cap_is_allowed(self):
        # Given a main source exactly at the cap — the boundary the rule must not misjudge
        body = "// filler\n" * MAX_FILE_LINES
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/Exact.kt", body)
        # Then it passes, because the cap is a maximum and not an exclusive bound
        self.assertEqual(findings, [])

    def test_grandfathered_file_is_not_reported(self):
        # Given one of the four files that predate the rule, still over the cap
        rel = sorted(GRANDFATHERED_LONG_FILES)[0]
        body = "// filler\n" * (MAX_FILE_LINES + 50)
        # When the check runs
        findings = self.findings_for(rel, body)
        # Then it passes, because the list exists to let the gate ship green
        self.assertEqual(findings, [])

    # --- noise stripping ---

    def test_strip_noise_preserves_line_numbers(self):
        # Given a multi-line comment and a multi-line string
        source = 'a\n/* two\nlines */\nb\n"""x\ny"""\nc\n'
        # When noise is blanked out
        stripped = strip_noise(source)
        # Then the line count is unchanged, so reported line numbers still match the file
        self.assertEqual(len(stripped.splitlines()), len(source.splitlines()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
