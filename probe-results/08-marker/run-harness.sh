#!/usr/bin/env bash
# One arm of the features/01 title-side harness, live. $1 names the output copy.
set -euo pipefail
root=/home/andy/dev/musicmeta/.claude/worktrees/agent-a87e9639f637b1300
cd "$root"
token="$(grep '^discogs.token=' secrets.properties | cut -d= -f2-)"
./gradlew :musicmeta-core:test -Dinclude.probe=true -Ddiscogs.token="$token" \
  --tests '*NonLatinTitleSideProbe*' --rerun-tasks --console=plain -i 2>&1 |
  sed 's/^[[:space:]]*//' | grep -E '^PROBE\||FAILED|^BUILD|^e:'
cp musicmeta-core/build/probe-nonlatin-titleside.tsv \
  ".scratch/features/prototypes/08-discogs-asterisk/probe-$1.tsv"
