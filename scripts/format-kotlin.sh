#!/usr/bin/env bash
# Format-on-write hook. Two modes, because an agent writes files two ways.
#
#   PostToolUse on Edit|Write — a payload on stdin names the file in `tool_input.file_path`.
#                               Format that one, immediately.
#   Stop (--sweep)            — end of turn. Format every dirty Kotlin file in the tree.
#
# The sweep exists because Bash writes are invisible to the first mode: `sed -i`, heredocs and
# generator scripts name no file, and a Bash payload carries `tool_input.command` instead. That gap
# is how ktlint import-ordering failures reached ./check.
#
# It is a *sweep*, not a per-Bash-call hook, deliberately. Attributing files to the command that
# just ran needs a heuristic — an mtime window was tried and rejected in review — and every version
# of that heuristic is wrong in both directions: it misses a command that writes and then runs for
# a while, or a generator that preserves mtimes, and it grabs unrelated files another tool touched
# in the same moment. Sweeping what git calls dirty needs no such guess, and costs one ktlint run
# per turn rather than one per Bash call.
#
# Known limit, shared by both modes: a Bash command that writes *and commits* leaves a clean tree,
# so nothing here sees it. `ktlintCheck` inside ./check is what actually fails, and this never was.
#
# Uses the ktlint CLI rather than `./gradlew ktlintFormat`: Gradle costs seconds of daemon startup,
# which is too slow to sit in the write path. When the CLI is absent this exits 0 and does nothing —
# a fresh clone must not be broken by a missing optional tool.
#
# Install the CLI to enable it:  https://github.com/pinterest/ktlint/releases
set -euo pipefail

MODE=payload
PRINT_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --sweep) MODE=sweep ;;
        --print-targets) PRINT_ONLY=1 ;;
    esac
done

# Selection lives in Python and emits NUL-separated paths. `git status -z` is the only form that is
# safe against paths containing spaces, quotes or newlines, and unpicking its rename records in
# shell is worse than not doing it at all.
select_targets() {
    python3 -c '
import json, subprocess, sys

mode = sys.argv[1]
KOTLIN = (".kt", ".kts")

def emit(paths):
    sys.stdout.write("\0".join(paths))

if mode == "payload":
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        # An unreadable payload is not the same as "a payload with no file_path". Falling through
        # to a sweep here would format whatever happened to be dirty — behaviour arrived at by
        # accident rather than chosen.
        sys.exit(0)
    path = (payload.get("tool_input") or {}).get("file_path") or ""
    emit([path] if path.endswith(KOTLIN) else [])
    sys.exit(0)

try:
    raw = subprocess.run(
        ["git", "status", "-z", "--porcelain", "--untracked-files=all"],
        capture_output=True, check=True, text=True,
    ).stdout
except (subprocess.CalledProcessError, FileNotFoundError):
    sys.exit(0)

# A -z record is "XY <path>\0", except renames and copies, which append "\0<original>". That extra
# field is consumed and discarded — only the destination exists to format.
fields, targets = iter(raw.split("\0")), []
for record in fields:
    if not record:
        continue
    status, path = record[:2], record[3:]
    if status[0] in "RC":
        next(fields, None)
    if status != "D " and path.endswith(KOTLIN):
        targets.append(path)
emit(targets)
' "$1"
}

mapfile -d "" -t TARGETS < <(select_targets "$MODE")

# A path is only a target if it is still there: an Edit payload can name a file a later step moved,
# and a staged deletion is reported by git while the file is gone.
KEEP=()
for path in "${TARGETS[@]}"; do
    if [ -n "$path" ] && [ -f "$path" ]; then
        KEEP+=("$path")
    fi
done
[ "${#KEEP[@]}" -eq 0 ] && exit 0

if [ "$PRINT_ONLY" = 1 ]; then
    printf '%s\n' "${KEEP[@]}"
    exit 0
fi

command -v ktlint >/dev/null 2>&1 || exit 0

# One invocation for all of them — the JVM start dominates, so per-file calls would multiply the
# only cost that matters.
#
# --format rewrites in place. Failures are non-fatal: a file mid-edit may not parse, and blocking
# on that would be worse than leaving it for ./check. That is also why this is a convenience and
# not a gate; ktlintCheck is what fails.
ktlint --format --relative "${KEEP[@]}" >/dev/null 2>&1 || true
