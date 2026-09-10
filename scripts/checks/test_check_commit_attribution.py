#!/usr/bin/env python3
"""Self-check for check_commit_attribution.py.

Each case is built by concatenation so this file carries no trailer of its own — the check reads
commit messages rather than the tree, but a literal here would still be a line in the repository
saying the thing the repository forbids.

The cases that matter are the two the real incident produced: the trailer Claude Code appends, and
the second copy GitHub's squash UI adds underneath a `---------` rule. Both shapes are proved to
fire, and the bot co-authors a dependency bump legitimately carries are proved not to.

Run with: python3 test_check_commit_attribution.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_commit_attribution import findings_for  # noqa: E402

CO_AUTHOR = "Co-a" + "uthored-by: "
ASSISTANT = "Claude Opus 5 <noreply@" + "anthropic.com>"
SESSION = "Claude-" + "Session: https://claude.ai/code/session_0123456789"
SHA = "0123456789abcdef0123456789abcdef01234567"


HUMAN = "famesjranko <andrewmcdonald42@gmail.com>"


def findings(body: str, *, author: str = HUMAN, committer: str = HUMAN) -> list[str]:
    """Findings for a commit whose subject is fixed and whose body is the case under test."""
    subject = "fix(core): a thing"
    return findings_for(SHA, subject, subject + "\n\n" + body, author, committer)


class CommitAttributionTest(unittest.TestCase):
    def test_assistant_co_author_is_reported(self):
        # Given the trailer Claude Code appends to a commit it wrote
        findings_found = findings(CO_AUTHOR + ASSISTANT + "\n")
        # Then it is reported, naming the commit and quoting the line
        self.assertEqual(len(findings_found), 1)
        self.assertIn(SHA[:7], findings_found[0])
        self.assertIn("co-author", findings_found[0])

    def test_session_trailer_is_reported_on_its_own(self):
        # Given a session link with no co-author line beside it
        findings_found = findings(SESSION + "\n")
        # Then the link alone is a finding — it names the tool as plainly as the co-author does
        self.assertEqual(len(findings_found), 1)
        self.assertIn("names the tool", findings_found[0])

    def test_every_copy_in_a_squash_body_is_reported(self):
        # Given the shape GitHub's squash UI produced: the branch's trailers, then its own copy
        body = CO_AUTHOR + ASSISTANT + "\n" + SESSION + "\n\n---------\n\n" + CO_AUTHOR + ASSISTANT + "\n"
        findings_found = findings(body)
        # Then all three lines are reported, so amending on the first does not hide the rest
        self.assertEqual(len(findings_found), 3)

    def test_bot_co_authors_are_not_reported(self):
        # Given the co-authors this repo's own automation writes
        body = (
            CO_AUTHOR
            + "dependabot[bot] <49699333+dependabot[bot]@users.noreply.github.com>\n"
            + CO_AUTHOR
            + "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>\n"
        )
        findings_found = findings(body)
        # Then nothing is reported — a dependency bump is attributed to its bot on purpose
        self.assertEqual(findings_found, [])

    def test_an_assistant_wearing_a_bot_suffix_is_reported(self):
        # Given a `[bot]` identity that is an assistant rather than repository automation
        findings_found = findings(CO_AUTHOR + "copilot-swe-agent[bot] <198982749+Copilot@users.noreply.github.com>\n")
        # Then it is reported: the check reads who the identity names, not whether it ends in [bot]
        self.assertEqual(len(findings_found), 1)

    def test_human_co_author_is_not_reported(self):
        # Given an ordinary co-authored commit
        findings_found = findings(CO_AUTHOR + "Andy <andrewmcdonald42@gmail.com>\n")
        # Then nothing is reported
        self.assertEqual(findings_found, [])

    def test_prose_naming_the_tool_is_not_reported(self):
        # Given a body that discusses the assistant rather than crediting it
        body = "The trailer arrives from Claude Code unless the setting is off.\n"
        findings_found = findings(body)
        # Then nothing is reported: the check reads trailers, not mentions
        self.assertEqual(findings_found, [])

    def test_assistant_as_the_author_is_reported(self):
        # Given the identity a hosted session's container arrives configured with
        findings_found = findings("", author="Claude <noreply@" + "anthropic.com>")
        # Then it is reported even though the message carries no trailer at all
        self.assertEqual(len(findings_found), 1)
        self.assertIn("authored", findings_found[0])

    def test_assistant_as_the_committer_is_reported(self):
        # Given a human author whose commit was written by an assistant's git identity
        findings_found = findings("", committer="Claude <noreply@" + "anthropic.com>")
        # Then the committer field is reported on its own
        self.assertEqual(len(findings_found), 1)
        self.assertIn("committed", findings_found[0])

    def test_generated_with_footer_is_reported(self):
        # Given the PR-description footer pasted into a commit body
        findings_found = findings("Generated with " + "[Claude Code](https://claude.com/claude-code)\n")
        # Then it is reported
        self.assertEqual(len(findings_found), 1)


if __name__ == "__main__":
    unittest.main()
