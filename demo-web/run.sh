#!/usr/bin/env bash
# Convenience wrapper for the musicmeta web demo.
# Usage:
#   ./run.sh          # starts the server on http://localhost:8099
#   PORT=9000 ./run.sh # starts on a different port
cd "$(dirname "$0")" || exit 1
exec ../gradlew run --console=plain -q
