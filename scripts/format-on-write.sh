#!/usr/bin/env bash
# Format-on-write hook. Reads a Claude Code PostToolUse payload on stdin and formats the file that
# was just edited, so unformatted code never reaches a commit.
#
# Uses the ktlint and ruff CLIs rather than `make format`: Gradle costs seconds of daemon startup
# per edit, which is too slow to sit in the write path. When a CLI is absent this exits 0 and does
# nothing — a fresh clone must not be broken by a missing optional tool, and `./check` is the
# authoritative gate either way.
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

# Rewrites in place. Failures are non-fatal: a file mid-edit may not parse, and blocking the write
# on that would be worse than leaving it for ./check.
case "$FILE" in
    *.kt|*.kts) command -v ktlint >/dev/null 2>&1 && ktlint --format --relative "$FILE" >/dev/null 2>&1 ;;
    *.py)       command -v ruff   >/dev/null 2>&1 && ruff format "$FILE" >/dev/null 2>&1 ;;
esac || true
