#!/usr/bin/env bash
# Assert every published module declares the expected version.
#
#   check_versions.sh            # expect the top pinned `## [x.y.z]` heading in CHANGELOG.md
#   check_versions.sh 0.11.0     # expect this exact version (the release workflow passes one)
#
# Versions are read from Gradle, not grepped from build files, so this sees what would actually
# publish. An empty parse is a failure: "" compares equal to "" and the guard would be green forever.
set -eo pipefail

cd "$(dirname "$0")/../.."

MODULES=(musicmeta-core musicmeta-android musicmeta-okhttp)

expected="${1:-}"
if [ -z "${expected}" ]; then
  # First `## [x.y.z]` heading, then quit; the digit pattern skips `## [Unreleased]`.
  expected="$(sed -nE '/^## \[[0-9]/ { s/^## \[([0-9]+\.[0-9]+\.[0-9]+)\].*/\1/p; q; }' CHANGELOG.md)"
  if [ -z "${expected}" ]; then
    echo "::error::could not read a pinned '## [x.y.z]' version heading from CHANGELOG.md"
    exit 1
  fi
fi
expected="${expected#v}"

failed=0
for module in "${MODULES[@]}"; do
  declared="$(./gradlew -q --console=plain ":${module}:properties" | awk -F': ' '/^version: / {print $2; exit}')"
  if [ -z "${declared}" ]; then
    echo "::error::could not read a declared version from :${module}"
    failed=1
  elif [ "${declared}" != "${expected}" ]; then
    echo "::error::module :${module} declares ${declared} but ${expected} was expected"
    failed=1
  else
    echo ":${module} declares ${declared} — matches ${expected}."
  fi
done

exit "${failed}"
