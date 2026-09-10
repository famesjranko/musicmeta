#!/usr/bin/env python3
"""Fail on an AI or tool attribution trailer in a commit message.

`CLAUDE.md` forbids attributing a commit to Claude, Anthropic or any other assistant. That rule
lived as prose for four months, was deleted with the rest of a `## Git rules` section during a
docs restructure, and nothing noticed for six weeks — by which time two commits carrying
`Co-authored-by: Claude …` had merged and GitHub was listing an assistant among the repo's
contributors. Removing them meant rewriting `main`, moving a release tag and dropping the commit
signatures on sixteen commits, all to undo four trailer lines.

The prose could not have caught it either way. Claude Code appends the trailer itself unless
`includeCoAuthoredBy` is false, and a web session is handed the same instruction at a level above
any repository file — so the rule was being overridden, not forgotten. `.claude/settings.json`
turns that off at the source; this check is what fails when it is on anyway, which is the case a
setting in one repository cannot cover: another machine, another tool, or a squash body GitHub
composes from branch commits.

Bot co-authors are not the target and are allowlisted by name: `dependabot`, `renovate` and
`github-actions` write real commits here, and a dependency bump legitimately carries one.

    python3 check_commit_attribution.py [--base REV] [--head REV]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

# A trailer whose own name is the attribution — no co-author line needed for these to be a finding.
STANDALONE = re.compile(
    r"^(?:Claude-Session:|(?:\N{ROBOT FACE}\s*)?Generated with\b.*\b(?:Claude|Copilot|Codex|Cursor|ChatGPT)\b)",
    re.IGNORECASE,
)

CO_AUTHOR = re.compile(r"^Co-authored-by:\s*(?P<identity>.+)$", re.IGNORECASE)

# Matched against the whole `Name <email>` identity, so either half can give a co-author away.
ASSISTANTS = re.compile(
    r"anthropic\.com|\b(?:claude|copilot|codex|cursor|devin|chatgpt|openai|gemini|aider)\b",
    re.IGNORECASE,
)

# There is deliberately no bot allowlist. `dependabot[bot]` and `github-actions[bot]` are attributed
# on purpose here and match nothing above, so exempting them by name would change no outcome — and
# it would cost one: a `[bot]` identity naming an assistant, `copilot-swe-agent[bot]` being the one
# that exists today, is exactly the finding this check is for. An allowlist written for the harmless
# bots would have swallowed it.

SEPARATOR = "\x1e"


REMEDY = (
    "CLAUDE.md forbids AI/tool attribution on anything that leaves this machine. "
    "Amend the commit (`git rebase -i` for an older one), and set includeCoAuthoredBy to false in "
    ".claude/settings.json so a trailer is not re-added."
)


def findings_for(sha: str, subject: str, message: str, author: str = "", committer: str = "") -> list[str]:
    """Every attribution in one commit — its author, its committer and its message trailers.

    The identities are checked because a hosted session's container arrives with `user.email` set to
    an assistant's address, which no trailer rule reaches: such a commit names the assistant in the
    field GitHub actually builds its contributor list from. Squash-merging has been hiding this —
    GitHub rewrites the author to the PR's — so it is a defect waiting on a merge method, not a
    theoretical one.
    """
    findings = [
        f"::error::{sha[:7]} ({subject}) is {role} by an assistant: {identity!r}. {REMEDY}"
        for role, identity in (("authored", author), ("committed", committer))
        if identity and ASSISTANTS.search(identity)
    ]
    for line in message.splitlines():
        stripped = line.strip()
        if STANDALONE.match(stripped):
            reason = "names the tool that wrote it"
        elif (match := CO_AUTHOR.match(stripped)) and ASSISTANTS.search(match["identity"]):
            reason = "credits an assistant as a co-author"
        else:
            continue
        findings.append(f"::error::{sha[:7]} ({subject}) {reason}: {stripped!r}. {REMEDY}")
    return findings


def commits(base: str, head: str) -> list[tuple[str, ...]]:
    """Every commit head adds to base, as (sha, subject, message, author, committer).

    `%B` is last because it is the only field that can contain the field separator itself — a
    message quoting a null byte is not a thing git will store, but one spanning lines is the norm.
    """
    log = subprocess.run(
        ["git", "log", f"--format=%H%x00%s%x00%an <%ae>%x00%cn <%ce>%x00%B{SEPARATOR}", f"{base}..{head}"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [
        (sha, subject, message, author, committer)
        for record in log.split(SEPARATOR)
        if len(fields := record.strip("\n").split("\0", 4)) == 5
        for sha, subject, author, committer, message in [fields]
    ]


def resolve_base(requested: str | None) -> str:
    """The revision to compare against, or a finding-shaped reason it could not be resolved.

    Fails rather than falling back to "scan nothing": a base that does not resolve means the range
    is empty, and an empty range is indistinguishable from a clean branch. A shallow CI checkout is
    exactly how that happens, so `build.yml` fetches full history for this.
    """
    candidates = [requested] if requested else ["origin/main", "main"]
    for candidate in candidates:
        if (
            candidate
            and subprocess.run(["git", "rev-parse", "--verify", "-q", candidate], capture_output=True).returncode == 0
        ):
            return candidate
    raise SystemExit(
        f"::error::cannot resolve a base revision (tried: {', '.join(c for c in candidates if c)}). "
        "Fetch the base branch — a shallow clone makes this check pass by having nothing to read."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="revision the branch left; defaults to origin/main, then main")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    findings = [finding for commit in commits(resolve_base(args.base), args.head) for finding in findings_for(*commit)]
    for finding in findings:
        print(finding)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
