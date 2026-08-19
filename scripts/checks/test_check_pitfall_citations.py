#!/usr/bin/env python3
"""Self-check for check_pitfall_citations.py.

A gate nobody has watched fail is not a gate, so the orphaned-citation case is proved to fire and
the resolvable one proved not to. Fixture citations are built by concatenation so this file does
not cite anything itself. Run with: python3 test_check_pitfall_citations.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_pitfall_citations import run  # noqa: E402

SECTION = "§"
DOC = "docs/pitfalls" + ".md"
HEADINGS = "# Pitfalls\n\n## 1. First\n\nbody\n\n## 4. Fourth\n\nbody\n"


class PitfallCitationsTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a file at a repo-relative path."""
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, files: dict[str, str], *, with_doc: bool = True) -> list[str]:
        """Findings for a tree holding these files, plus the pitfalls doc unless withheld."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            if with_doc:
                self.write(root, DOC, HEADINGS)
            for rel, body in files.items():
                self.write(root, rel, body)
            return run(root)

    def test_orphaned_citation_in_a_source_is_reported(self):
        # Given a source comment citing a section number with no heading
        body = "// see " + DOC + " " + SECTION + "9\n"
        # When the check runs
        findings = self.findings_for({"musicmeta-core/src/main/kotlin/A.kt": body})
        # Then it is reported on the line that carries it, naming the missing number
        self.assertEqual(len(findings), 1)
        self.assertIn("line=1", findings[0])
        self.assertIn("9", findings[0])

    def test_resolvable_citation_is_not_reported(self):
        # Given a source comment citing a section that exists
        body = "// see " + DOC + " " + SECTION + "4\n"
        # When the check runs
        findings = self.findings_for({"musicmeta-core/src/main/kotlin/A.kt": body})
        # Then nothing is reported
        self.assertEqual(findings, [])

    def test_bare_reference_inside_the_pitfalls_doc_is_checked(self):
        # Given the doc cross-referencing a section number it does not define
        body = HEADINGS + "\nsame shape as " + SECTION + "7 one layer down\n"
        # When the check runs
        findings = self.findings_for({DOC: body}, with_doc=False)
        # Then the dangling cross-reference is reported
        self.assertEqual(len(findings), 1)
        self.assertIn("7", findings[0])

    def test_bare_reference_outside_the_reference_files_is_ignored(self):
        # Given a section mark with a number in a doc that never mentions the pitfalls file
        body = "the spec's " + SECTION + "9 covers this\n"
        # When the check runs
        findings = self.findings_for({"docs/other.md": body})
        # Then it is not treated as a pitfall citation
        self.assertEqual(findings, [])

    def test_missing_headings_refuse_to_report_clean(self):
        # Given a tree whose pitfalls doc has no numbered headings
        # When the check runs
        findings = self.findings_for({DOC: "# Pitfalls\n"}, with_doc=False)
        # Then the empty scan is itself the finding
        self.assertEqual(len(findings), 1)
        self.assertIn("no `## N.` headings", findings[0])

    def test_citation_in_an_agent_worktree_is_not_reported(self):
        # Given an orphaned citation inside `.claude/worktrees/`, a full checkout of this repo on
        # another branch — where the pitfalls file may legitimately be numbered differently
        body = "// see " + DOC + " " + SECTION + "9\n"
        # When the check runs
        findings = self.findings_for({".claude/worktrees/agent-a1/src/A.kt": body})
        # Then it is not reported — that branch's citations resolve against that branch's doc
        self.assertEqual(findings, [])

    def test_citation_in_a_tracked_dot_claude_file_is_reported(self):
        # Given the same orphan in `.claude/commands/`, which is committed wiring. The exclusion
        # was `/.claude/` wholesale, so this file's citations were never checked at all.
        body = "See " + DOC + " " + SECTION + "9\n"
        # When the check runs
        findings = self.findings_for({".claude/commands/review-checklist.md": body})
        # Then it is reported, because that file ships with the repo
        self.assertEqual(len(findings), 1)
        self.assertIn(".claude/commands/review-checklist.md", findings[0])

    def test_a_symlink_to_a_scanned_file_is_not_read_twice(self):
        # Given `AGENTS.md` symlinked to a file carrying an orphaned citation
        body = "See " + DOC + " " + SECTION + "9\n"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, DOC, HEADINGS)
            self.write(root, "CLAUDE.md", body)
            (root / "AGENTS.md").symlink_to("CLAUDE.md")
            # When the check runs
            findings = run(root)
        # Then the orphan is reported once, under the real file's name
        self.assertEqual(len(findings), 1)
        self.assertIn("CLAUDE.md", findings[0])
        self.assertNotIn("AGENTS.md", findings[0])

    def test_a_worktree_shaped_root_still_scans_its_own_tree(self):
        # Given `root` itself sitting at `.claude/worktrees/agent-a1/` — an agent's own checkout,
        # which is what `run()` is actually invoked against from inside one. Its absolute path
        # carries the exclusion substring too, so a naive absolute-path test excludes everything.
        orphan = "// see " + DOC + " " + SECTION + "9\n"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / ".claude" / "worktrees" / "agent-a1"
            self.write(root, DOC, HEADINGS)
            self.write(root, "musicmeta-core/src/main/kotlin/A.kt", orphan)
            self.write(
                root,
                ".claude/worktrees/agent-nested/musicmeta-core/src/main/kotlin/B.kt",
                orphan,
            )
            # When the check runs
            findings = run(root)
        # Then the orphan at the scanned root is still reported, and only the doubly-nested
        # worktree — another agent's checkout inside this one — is excluded
        self.assertEqual(len(findings), 1)
        self.assertIn("A.kt", findings[0])


if __name__ == "__main__":
    unittest.main()
