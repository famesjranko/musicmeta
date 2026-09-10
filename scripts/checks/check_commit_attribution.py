#!/usr/bin/env python3
"""Fail on AI or tool attribution in a commit.

Reads the commits a branch adds to its base, and the commit a push lands, for an assistant named as
the author, the committer, or in a trailer. Why prose alone could not hold this rule, and what it
cost: `docs/pitfalls.md` §41.

Two refusals shape the rest of this file:

- **A shallow clone is a hard failure, not an empty range.** `git clone --depth 1` still creates
  `origin/main`, so a base-resolves check passes, the range is empty, and the gate reports green
  having read nothing. Resolvability was the wrong thing to guard.
- **A push build reads the pushed commit, not nothing.** On `push: main` the range against
  `origin/main` is empty by construction — which is where the squash body GitHub composes at merge
  is readable, and the only surface the original incident's doubled trailer ever existed on.

    python3 check_commit_attribution.py [--base REV] [--head REV]
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

# Anchored whole-line and matched against the raw line, never a stripped one: a git trailer sits at
# column 0, so an indented or quoted copy is prose about the rule rather than an instance of it.
# Without that, this repo could not write a commit message quoting the line it bans — and
# `Generated with Claude Code, then rewritten by hand` would be a finding.
#
# The `[^,]` runs are what separate a footer from a sentence about one: `Generated with Claude Code`
# is a footer, `Generated with Claude Code, then rewritten by hand` is prose. A comma-free sentence
# still reads as a footer here; that residual is in VERIFICATION.md rather than chased with a
# grammar.
STANDALONE = re.compile(
    r"^(?:\N{ROBOT FACE}\s*)?(?:"
    r"Claude-Session:\s*\S+"
    r"|(?=.*\b(?:Claude|Anthropic|Copilot|Codex|Cursor|Devin|ChatGPT|OpenAI|Gemini|Aider)\b)"
    r"(?:Generated|Created|Written|Authored)[ -](?:with|by):?\s+[^,]{0,160}"
    r")$",
    re.IGNORECASE,
)

# Splits `Name <email>`; a trailer without an address is all name.
IDENTITY = re.compile(r"^(?P<name>.*?)\s*(?:<(?P<email>[^>]*)>)?\s*$")

CO_AUTHOR = re.compile(r"^Co-authored-by:\s*(?P<identity>.+?)\s*$", re.IGNORECASE)

# An assistant's *name*. Not sufficient on its own — see MACHINE_ADDRESS.
ASSISTANTS = re.compile(
    r"\b(?:claude|anthropic|copilot|codex|cursor|devin|chatgpt|openai|gemini|aider)\b",
    re.IGNORECASE,
)

# An address no person types: a vendor domain, a `[bot]` account, or a noreply mailbox. An identity
# is a finding only when an assistant's name appears *and* the address is one of these, so a
# contributor called Claude Dubois <claude.dubois@example.fr> is not locked out of the repository by
# their own name — there is no allowlist to add them to, and a commit that trips this cannot be
# amended by anyone but its author. Everything that actually emits these trailers qualifies:
# `noreply@anthropic.com`, `copilot-swe-agent[bot]@users.noreply.github.com`.
#
# The residual is stated rather than closed: `Claude <claude@example.com>` passes. VERIFICATION.md
# carries it under "Known gaps".
MACHINE_ADDRESS = re.compile(
    r"@(?:anthropic|openai|cursor|cognition-labs)\.\w+|\[bot\]|\bno-?reply@|users\.noreply\.github\.com",
    re.IGNORECASE,
)

# There is deliberately no bot allowlist. `dependabot[bot]` and `github-actions[bot]` carry a machine
# address but no assistant name, so exempting them by name would change no outcome — and it would
# cost one: `copilot-swe-agent[bot]` carries both, and is exactly the finding this check is for.

REMEDY = (
    "CLAUDE.md forbids AI/tool attribution on anything that leaves this machine. "
    "Amend the commit (`git rebase -i` for an older one), and set `attribution` in "
    ".claude/settings.json to empty `commit` and `pr` text with `sessionUrl` false, so no "
    "trailer is re-added."
)


def is_assistant_identity(identity: str) -> bool:
    """Whether a `Name <email>` names an assistant at an address a person would not own.

    The two halves are read separately on purpose. Matching an assistant's name against the whole
    string makes a vendor's own domain satisfy it, so a human with an `@anthropic.com` address would
    be reported for their employer's name rather than for anything they wrote.
    """
    parts = IDENTITY.match(identity)
    if not parts:
        return False
    email = parts["email"] or ""
    return bool(ASSISTANTS.search(parts["name"] or "") and MACHINE_ADDRESS.search(email))


def findings_for(sha: str, subject: str, message: str, author: str = "", committer: str = "") -> list[str]:
    """Every attribution in one commit — its author, its committer and its message trailers.

    Identities are read as well as trailers because a hosted session's container arrives with
    `user.email` already set to an assistant's — pitfalls §41.
    """
    findings = [
        f"::error::{sha[:7]} ({subject}) is {role} by an assistant: {identity!r}. {REMEDY}"
        for role, identity in (("authored", author), ("committed", committer))
        if identity and is_assistant_identity(identity)
    ]
    for line in message.split("\n"):
        if STANDALONE.match(line):
            reason = "names the tool that wrote it"
        elif (match := CO_AUTHOR.match(line)) and is_assistant_identity(match["identity"]):
            reason = "credits an assistant as a co-author"
        else:
            continue
        findings.append(f"::error::{sha[:7]} ({subject}) {reason}: {line!r}. {REMEDY}")
    return findings


def git(*args: str) -> str:
    """Run a git command that is expected to succeed, and return its stdout."""
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def commits(base: str, head: str) -> list[tuple[str, ...]]:
    """Every commit head adds to base, as (sha, subject, message, author, committer).

    Records are NUL-terminated (`-z`) rather than separated by an in-band sentinel. A commit message
    can contain any byte a sentinel might use — a message holding an RS silently truncated there and
    every trailer after it went unread — but it cannot contain NUL, which is what git splits on.
    """
    return parse_log(git("log", "-z", "--format=%H%x00%s%x00%an <%ae>%x00%cn <%ce>%x00%B", f"{base}..{head}"))


FIELDS_PER_COMMIT = 5


def parse_log(log: str) -> list[tuple[str, ...]]:
    """Split `git log -z` output into commits, refusing output that does not divide into records.

    `-z` *terminates* each record with NUL rather than separating records from each other, so the
    stream is one flat run of NUL-delimited fields and there is no record boundary to split on
    first — looking for a doubled NUL finds one only when a commit's body is empty, which parsed an
    entire log as a single commit and attributed every trailer in it to the newest SHA.

    Refusing rather than skipping a short tail: a dropped record is a commit this check silently did
    not read, the same green-while-reading-nothing failure the shallow guard exists to prevent.
    """
    fields = log.split("\0")
    if fields and not fields[-1].strip("\n"):
        fields.pop()
    if len(fields) % FIELDS_PER_COMMIT:
        raise SystemExit(
            f"::error::cannot parse `git log` output: {len(fields)} fields is not a whole number of "
            f"{FIELDS_PER_COMMIT}-field commits."
        )
    parsed: list[tuple[str, ...]] = []
    for i in range(0, len(fields), FIELDS_PER_COMMIT):
        sha, subject, author, committer, message = fields[i : i + FIELDS_PER_COMMIT]
        parsed.append((sha.strip("\n"), subject, message, author, committer))
    return parsed


def is_shallow() -> bool:
    """Whether this is a shallow clone, whose history is not what it appears to be."""
    return git("rev-parse", "--is-shallow-repository").strip() == "true"


def ref_exists(ref: str) -> bool:
    """Whether a revision resolves in this repository."""
    return subprocess.run(["git", "rev-parse", "--verify", "-q", ref], capture_output=True).returncode == 0


def resolve_base(requested: str | None) -> str:
    """The revision to compare against.

    Order: an explicit `--base`; the pull request's own base branch, so a stacked PR is not measured
    against commits its author cannot amend; the commit a push replaced, so a push build reads what
    it landed instead of an empty range; then `origin/main` and `main` for a local run.
    """
    if requested:
        if not ref_exists(requested):
            raise SystemExit(f"::error::--base {requested} does not resolve in this repository.")
        return requested
    candidates = []
    if base_ref := os.environ.get("GITHUB_BASE_REF"):
        candidates.append(f"origin/{base_ref}")
    # All-zeros is git's "no such commit" — a branch's first push has nothing before it.
    if (before := os.environ.get("MUSICMETA_PUSH_BEFORE", "")) and set(before) != {"0"}:
        candidates.append(before)
    candidates += ["origin/main", "main"]
    for candidate in candidates:
        if ref_exists(candidate):
            return candidate
    raise SystemExit(f"::error::cannot resolve a base revision (tried: {', '.join(candidates)}).")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="revision the branch left; see resolve_base for the default order")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    if is_shallow():
        raise SystemExit(
            "::error::this is a shallow clone, so the range this check reads is not the branch's "
            "history — it would report green having read nothing. Fetch full history "
            "(`fetch-depth: 0`) before running it."
        )
    findings = [finding for commit in commits(resolve_base(args.base), args.head) for finding in findings_for(*commit)]
    for finding in findings:
        print(finding)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
