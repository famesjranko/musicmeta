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

from check_test_shape import main, run  # noqa: E402

WELL_FORMED = """package a

class ATest {
    @Test
    fun `does the thing`() {
        // Given - a fixture
        val x = 1
        // When - the call happens
        val y = x + 1
        // Then - the result is correct
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
        # Given a test with two acts, each with its own `// When -` line
        body = """class ATest {
    @Test
    fun f() {
        // Given - a fixture
        val x = 1
        // When - the first call happens
        val y = x + 1
        // When - the second call happens
        val z = y + 1
        // Then - the result is correct
        assertEquals(3, z)
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — CLAUDE.md allows one `// When` per act
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    # --- missing Given ---

    def test_test_with_no_given_label_is_reported(self):
        # Given a `@Test` body with no `// Given -` line at all
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
        self.assertIn("no `// Given -` line", findings[0])

    # --- bare labels ---

    def test_bare_given_label_is_reported(self):
        # Given a `// Given` with no hyphen and no clause
        body = """class ATest {
    @Test
    fun f() {
        // Given
        val x = 1
        // When - the call happens
        // Then - it holds
        assertEquals(1, x)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then the bare label is reported on its own line, and — since a malformed Given doesn't
        # count as present — the window is also reported as missing a real `// Given -`
        self.assertEqual(len(findings), 2)
        self.assertIn("line=4", findings[0])
        self.assertIn("hyphen", findings[0])

    def test_given_with_dash_but_no_clause_is_reported(self):
        # Given a `// Given -` with the dash but nothing after it
        body = """class ATest {
    @Test
    fun f() {
        // Given -
        val x = 1
        // When - the call happens
        // Then - it holds
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
        // Given / When - construct without explicit kind
        val x = 1
        // Then - it holds
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

    def test_label_naming_a_second_step_in_its_clause_is_not_combined(self):
        # Given a well-formed `// Then -` whose clause happens to name another label word
        body = """class ATest {
    @Test
    fun f() {
        // Given - a fixture
        // When - the call happens
        // Then - the When branch is the one taken
        assertEquals(1, 1)
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — only label position counts, so a clause may say "When"
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    def test_prose_comment_mentioning_a_label_word_is_not_a_label(self):
        # Given a plain comment inside a `@Test` body that mentions Given without being a label
        body = """class ATest {
    @Test
    fun f() {
        // Given - a fixture
        // the When branch is the expensive one, which is why this is warmed up first
        // When - the call happens
        // Then - it holds
        assertEquals(1, 1)
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — the word is not in label position, so the line is prose. An
        # unanchored trigger would report this correct code with no way to opt out. A comment that
        # *opens* with a label word is still read as a label: that case is genuinely ambiguous, and
        # rewording it is the opt-out.
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    def test_double_hyphen_label_is_reported(self):
        # Given a `// Given --`, a form neither CLAUDE.md nor anyone else chose
        body = """class ATest {
    @Test
    fun f() {
        // Given -- a fixture
        // When - the call happens
        // Then - it holds
        assertEquals(1, 1)
    }
}
"""
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then it is reported as malformed, plus the missing-Given finding
        self.assertEqual(len(findings), 2)
        self.assertIn("hyphen", findings[0])

    def test_arrow_and_run_on_separators_are_reported(self):
        # Given labels using near misses of the ` - ` separator: an arrow, and a hyphen with no
        # space before the clause
        for separator in ("->", "-"):
            body = f"""class ATest {{
    @Test
    fun f() {{
        // Given {separator}a fixture
        // When - the call happens
        // Then - it holds
        assertEquals(1, 1)
    }}
}}
"""
            # When the test-shape check runs
            findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
            # Then each is reported as malformed — one separator, not a family of them
            self.assertEqual(len(findings), 2, separator)
            self.assertIn("hyphen", findings[0])

    # --- window boundary ---

    def test_second_test_in_same_class_is_checked_independently(self):
        # Given two `@Test` functions in one class, the first well-formed and the second missing
        # its Given — proving the window stops at the next `@Test` line rather than bleeding
        # the first function's labels into the second
        body = """class ATest {
    @Test
    fun first() {
        // Given - a fixture
        // When - the call happens
        // Then - it holds
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

    def test_window_stops_at_the_next_class_declaration(self):
        # Given a helper class *after* the last `@Test` in the file, with a comment naming a label
        # word — the direction the "next `@Test` or end of file" bound got wrong, since the file
        # end is not the end of the enclosing class
        body = """class ATest {
    @Test
    fun f() {
        // Given - a fixture
        // When - the call happens
        // Then - it holds
        assertEquals(1, 1)
    }
}

private class Helper {
    fun build() {
        // Then write the file out
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — the trailing class closes the last `@Test`'s window
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    def test_comment_inside_a_raw_string_is_not_graded_as_a_label(self):
        # Given a raw string holding fixture text with a bare `// Given` in it — content, not a
        # label. The real labels around it are well formed.
        body = '''class ATest {
    @Test
    fun f() {
        // Given - a fixture source file
        val source = """
    // Given
    fun g() = 1
"""
        // When - the call happens
        // Then - it holds
        assertEquals(1, source.length)
    }
}
'''
        # When the test-shape check runs
        # Then nothing is reported — grading raw string content would fail a test for the shape of
        # a string it merely holds
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    def test_comment_mentioning_a_raw_string_delimiter_does_not_blank_the_file(self):
        # Given a prose comment naming the `\"\"\"` delimiter an odd number of times, above a
        # `@Test` with no labels at all
        body = '''class ATest {
    // Kotlin raw strings open with """ and this line only mentions it
    @Test
    fun f() {
        assertEquals(1, 1)
    }
}
'''
        # When the test-shape check runs
        findings = self.findings_for("m/src/test/kotlin/ATest.kt", body)
        # Then the missing labels are still reported — treating that comment as a string opener
        # would blank every line below it, `@Test` included, and report the file clean without
        # ever reading it. A silent false negative is worse than the misparse it guards against.
        self.assertEqual(len(findings), 1)
        self.assertIn("no `// Given -` line", findings[0])

    def test_declaration_inside_a_raw_string_does_not_close_the_window(self):
        # Given a raw string whose fixture text opens with `class` at column 0, placed *before*
        # this test's `// Given -` line
        body = '''class ATest {
    @Test
    fun f() {
        val source = """
class Embedded {
    fun g() = 1
}
"""
        // Given - the fixture above
        // When - the call happens
        // Then - it holds
        assertEquals(1, source.length)
    }
}
'''
        # When the test-shape check runs
        # Then nothing is reported — were the embedded declaration read as source it would close
        # the window early, and the real `// Given -` after it would count as missing
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

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
        // Given - a fixture
        // When - the call happens
        // Then - it holds
        assertEquals(1, 1)
    }
}
"""
        # When the test-shape check runs
        # Then nothing is reported — the helper's comment sits outside any `@Test` window
        self.assertEqual(self.findings_for("m/src/test/kotlin/ATest.kt", body), [])

    # --- the two entry points agree ---

    def exit_code_for_file(self, rel: str, body: str) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, rel, body)
            return main(["--root", str(root), "--file", str(root / rel)])

    def test_file_mode_reports_a_violation_in_a_test_source(self):
        # Given a test source with no `// Given -` line
        body = """class ATest {
    @Test
    fun f() {
        assertEquals(1, 1)
    }
}
"""
        # When it is checked through `--file`, the path format-on-write.sh uses
        # Then it exits 2 — the hook blocks only on 2, so anything else is a silent gate
        self.assertEqual(self.exit_code_for_file("m/src/test/kotlin/ATest.kt", body), 2)

    def test_file_mode_ignores_a_test_source_the_gate_never_walks(self):
        # Given a test source nested two directories below the root — the shape an agent worktree
        # under `.claude/` has, which the gate's `*/src/test/**` glob does not reach
        body = """class ATest {
    @Test
    fun f() {
        assertEquals(1, 1)
    }
}
"""
        # When it is checked through `--file`
        # Then it passes — a substring test for `/src/test/` would block a write at a depth the
        # gate never enforces, so the hook would fail what CI does not
        self.assertEqual(self.exit_code_for_file(".claude/worktrees/w/m/src/test/kotlin/ATest.kt", body), 0)

    def test_file_mode_ignores_a_path_outside_the_gate_scope(self):
        # Given the same violation in a main source, which the full-repo gate never scans
        body = """class ATest {
    @Test
    fun f() {
        assertEquals(1, 1)
    }
}
"""
        # When it is checked through `--file`
        # Then it passes — a file the gate ignores must not fail at write-time
        self.assertEqual(self.exit_code_for_file("m/src/main/kotlin/ATest.kt", body), 0)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
