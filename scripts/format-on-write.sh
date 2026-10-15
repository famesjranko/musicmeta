#!/usr/bin/env bash
# Format-on-write hook. Reads a Claude Code PostToolUse payload on stdin and formats the file that
# was just edited, so unformatted code never reaches a commit.
#
# Uses the ktlint and ruff CLIs rather than `make format`: Gradle costs seconds of daemon startup
# per edit, which is too slow to sit in the write path. When a CLI is absent this exits 0 without
# formatting — a fresh clone must not be broken by a missing optional tool, and `./check` is the
# authoritative gate either way. A missing or mismatched ktlint says so to the writer; ruff does
# not need to, because `./check` refuses to run without it.
#
# Install to enable:  https://github.com/pinterest/ktlint/releases  /  pipx install ruff
set -euo pipefail

FILE="$(python3 -c '
import json, sys
try:
    payload = json.load(sys.stdin)
except (json.JSONDecodeError, ValueError):
    sys.exit(0)
print(payload.get("tool_input", {}).get("file_path", ""))
' 2>/dev/null || true)"

[ -n "$FILE" ] && [ -f "$FILE" ] || exit 0

# Tells the writer, without blocking the edit. A PostToolUse hook's stderr reaches nobody at exit 0,
# so the note goes out as the hook's JSON `additionalContext`, which the writer does see.
note() {
    python3 -c 'import json, sys
print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": sys.argv[1]}}))' "$1"
}

# A CLI whose rule set differs from the gate's is worse than no CLI: it rewrites files to a style
# `./check` never asked for, so unrelated formatting rides along in every diff and nothing fails.
# Treat a mismatch exactly like an absent tool — do nothing, and say why once per edit.
ktlint_version_matches() {
    local want have
    want="$(sed -n 's/^ktlint-cli = "\(.*\)"$/\1/p' "$(dirname "${BASH_SOURCE[0]}")/../gradle/libs.versions.toml")"
    if ! command -v ktlint >/dev/null 2>&1; then
        note "format-on-write: ktlint is not on PATH, so $FILE was not formatted. Run \`make bootstrap\` to install ktlint ${want:-?}, or \`make format\` before committing."
        return 1
    fi
    have="$(ktlint --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
    [ -n "$want" ] && [ "$want" = "$have" ] && return 0
    note "format-on-write: ktlint ${have:-?} on PATH, gate pins ${want:-?}, so $FILE was not formatted. Run \`make bootstrap\` to install ${want:-?}, or \`make format\` before committing."
    return 1
}

# `demo-cli/` is included on purpose: it applies the same ktlint against the same `.editorconfig`, and
# `./check` gates it. It stays exempt from house *conventions*, which is a separate check.
#
# Rewrites in place. Failures are non-fatal: a file mid-edit may not parse, and blocking the write
# on that would be worse than leaving it for ./check.
case "$FILE" in
    *.kt|*.kts) ktlint_version_matches && ktlint --format --relative "$FILE" >/dev/null 2>&1 ;;
    *.py)       command -v ruff   >/dev/null 2>&1 && ruff format "$FILE" >/dev/null 2>&1 ;;
esac || true

# Given/when/then shape, immediate feedback while the file is still in the writer's context. Same
# script `check` runs, so hook and gate agree by construction. src/test/ only, matching the script's
# own scope — main sources have no test-shape rule to enforce.
#
# Exits 2 on a violation rather than swallowing it: a PostToolUse hook's stderr reaches the writer
# only at exit 2, so `|| true` here would leave the write-time half printing into a void while
# `CLAUDE.md` and `docs/verification.md` both claim it gates. Unlike the formatters above, this reports
# rather than rewrites, so failing loudly costs nothing mid-edit.
case "$FILE" in
    */src/test/*.kt) python3 "$(dirname "${BASH_SOURCE[0]}")/checks/check_test_shape.py" --file "$FILE" >&2 || exit 2 ;;
esac
