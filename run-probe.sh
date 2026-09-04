#!/usr/bin/env bash
# Throwaway runner for the non-Latin coverage probe arm on this branch.
set -euo pipefail
cd "$(dirname "$0")"
export GRADLE_OPTS=-Dorg.gradle.caching=false
exec flock /tmp/claude-1000/-home-andy-dev-musicmeta/gradle.lock \
  ./gradlew :musicmeta-core:test --rerun-tasks -Dinclude.probe=true \
  --tests '*NonLatinCoverageProbe*'
