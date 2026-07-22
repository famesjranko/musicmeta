# shellcheck shell=bash
# Sourced by release.yml: does every published module resolve on Maven Central at this version?
#
#   source scripts/github-workflows/central_poms.sh
#   all_poms_resolve 0.11.0 && echo "already published"
#
# All three, not just core — a partial publish is immutable, so core resolving proves nothing about
# the other two.

all_poms_resolve() {
  local version="$1" module path
  for module in musicmeta-core musicmeta-okhttp musicmeta-android; do
    path="io/github/famesjranko/${module}/${version}/${module}-${version}.pom"
    curl -fsSI --max-time 20 "https://repo1.maven.org/maven2/${path}" >/dev/null 2>&1 || return 1
  done
}
