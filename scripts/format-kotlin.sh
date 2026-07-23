#!/usr/bin/env bash
# Format-on-write hook. Reads a Claude Code PostToolUse payload on stdin and formats the Kotlin
# file that was just edited, so unformatted code never reaches a commit.
#
# Uses the ktlint CLI rather than `./gradlew ktlintFormat`: Gradle costs seconds of daemon startup
# per edit, which is too slow to sit in the write path. When the CLI is absent this exits 0 and
# does nothing — a fresh clone must not be broken by a missing optional tool, and `./check` is the
# authoritative gate either way.
#
# Install the CLI to enable it:  https://github.com/pinterest/ktlint/releases
set -euo pipefail

command -v ktlint >/dev/null 2>&1 || exit 0

FILE="$(python3 -c '
import json, sys
try:
    payload = json.load(sys.stdin)
except (json.JSONDecodeError, ValueError):
    sys.exit(0)
print(payload.get("tool_input", {}).get("file_path", ""))
' 2>/dev/null || true)"

case "$FILE" in
    *.kt|*.kts) ;;
    *) exit 0 ;;
esac

[ -f "$FILE" ] || exit 0

# --format rewrites in place. Failures are non-fatal: a file mid-edit may not parse, and blocking
# the write on that would be worse than leaving it for ./check.
ktlint --format --relative "$FILE" >/dev/null 2>&1 || true
