#!/usr/bin/env python3
"""Self-check for check_test_shape.py.

A gate nobody has watched fail is not a gate, so every rule here is proved to fire on the exact
violation it exists to catch, and proved not to fire on the thing most likely to be mistaken for
one. Run with: python3 test_check_test_shape.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_test_shape import run  # noqa: E402

WELL_FORMED = """package a

class ATest {
    @Test
    fun `does the thing`() {
        // Given — a fixture
        val x = 1
        // When — the call happens
        val y = x + 1
        // Then — the result is correct
        assertEquals(2, y)
    }
}
"""


class TestShapeTest(unittest.TestCase):
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

    # --- well-formed passes ---

    def test_well_formed_labels_report_nothing(self):
        # Given a `@Test` with correctly formed Given/When/Then labels
        # When the test-shape check runs
        # Then nothing is reported
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", WELL_FORMED), [])

    def test_multiple_when_labels_naming_separate_acts_are_allowed(self):
        # Given a test with two acts, each with its own `// When —` line
        body = """class ATest {
    @Test
    fun f() {
        // Given — a fixture
        val x = 1
        // When — the first call happens
        val y = x + 1
        // When — the second call happens
        val z = y + 1
        // Then — the result is correct
        assertEquals(3, z)
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — CLAUDE.md allows one `// When` per act
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    # --- missing Given ---

    def test_test_with_no_given_label_is_reported(self):
        # Given a `@Test` body with no `// Given —` line at all
        body = """class ATest {
    @Test
    fun f() {
        val x = 1
        assertEquals(1, x)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then it is reported, anchored to the `@Test` line
        self.assertEqual(len(findings), 1)
        self.assertIn("line=2", findings[0])
        self.assertIn("no `// Given —` line", findings[0])

    # --- bare labels ---

    def test_bare_given_label_is_reported(self):
        # Given a `// Given` with no em dash and no clause
        body = """class ATest {
    @Test
    fun f() {
        // Given
        val x = 1
        // When — the call happens
        // Then — it holds
        assertEquals(1, x)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then the bare label is reported on its own line, and — since a malformed Given doesn't
        # count as present — the window is also reported as missing a real `// Given —`
        self.assertEqual(len(findings), 2)
        self.assertIn("line=4", findings[0])
        self.assertIn("em dash", findings[0])

    def test_given_with_dash_but_no_clause_is_reported(self):
        # Given a `// Given —` with the dash but nothing after it
        body = """class ATest {
    @Test
    fun f() {
        // Given —
        val x = 1
        // When — the call happens
        // Then — it holds
        assertEquals(1, x)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then it is still reported — the dash alone is not a clause — plus the missing-Given
        # finding, since a malformed label doesn't count as a present one
        self.assertEqual(len(findings), 2)

    # --- combined labels ---

    def test_combined_given_when_label_is_reported(self):
        # Given a single comment line naming both Given and When
        body = """class ATest {
    @Test
    fun f() {
        // Given / When — construct without explicit kind
        val x = 1
        // Then — it holds
        assertEquals(1, x)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then it is reported as a combined-label violation, and Given still doesn't count as
        # present since the line itself is malformed
        self.assertEqual(len(findings), 2)
        self.assertIn("more than one", findings[0])

    # --- window boundary ---

    def test_second_test_in_same_class_is_checked_independently(self):
        # Given two `@Test` functions in one class, the first well-formed and the second missing
        # its Given — proving the window stops at the next `@Test` line rather than bleeding
        # the first function's labels into the second
        body = """class ATest {
    @Test
    fun first() {
        // Given — a fixture
        // When — the call happens
        // Then — it holds
        assertEquals(1, 1)
    }

    @Test
    fun second() {
        assertEquals(2, 2)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then only the second function is reported
        self.assertEqual(len(findings), 1)
        self.assertIn("line=10", findings[0])

    # --- scope ---

    def test_non_test_helper_mentioning_given_is_not_scanned(self):
        # Given a main-source helper whose doc comment happens to say "given" in prose
        body = """class Helper {
    // Given a config, builds a client
    fun build() = 1
}
"""
        # When the test-shape check runs
        # Then it is not reported — main sources aren't test sources, and there is no `@Test`
        # to anchor a window to anyway
        self.assertEqual(self.findings_for("m/src/main/kotlin/Helper.kt", body), [])

    def test_non_test_function_in_a_test_file_is_not_scanned(self):
        # Given a helper function in a test file, with no `@Test` annotation of its own
        body = """class ATest {
    fun helper() {
        // Given a raw comment that isn't a real label context
    }

    @Test
    fun f() {
        // Given — a fixture
        // When — the call happens
        // Then — it holds
        assertEquals(1, 1)
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — the helper's comment sits outside any `@Test` window
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
