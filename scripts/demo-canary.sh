#!/usr/bin/env bash
# The demo canary: compile, lint and test demo-cli/ and demo-web/. `./check`, CI's `demo-canary`
# job and `make demo` all call this, so the three cannot disagree about what the canary runs.
#
# Both demos are separate composite builds that `./gradlew build` never compiles, consuming the
# library the way an external app does. They have no wrapper of their own and borrow the root one.
#
# ktlintCheck before test: the demos are exempt from house *conventions* (see check_conventions.py)
# but not from formatting, so a formatting failure reports first. -Pmusicmeta.rerunTests forces the
# tests to execute rather than be served from the shared build cache.
#
#   scripts/demo-canary.sh [gradle flags...]   e.g. --quiet
set -euo pipefail
cd "$(dirname "$0")/.."

for demo in demo-cli demo-web; do
    (cd "$demo" && ../gradlew compileKotlin ktlintCheck test -Pmusicmeta.rerunTests "$@")
done
