#!/usr/bin/env bash
# Convenience wrapper for the musicmeta demo CLI.
# Usage:
#   ./run.sh                          # interactive mode
#   ./run.sh artist Radiohead         # single command
#   ./run.sh album "OK Computer" Radiohead
cd "$(dirname "$0")" || exit 1

if [ $# -gt 0 ]; then
    exec ../gradlew run --console=plain -q --args="$*"
else
    exec ../gradlew run --console=plain -q
fi
