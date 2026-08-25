#!/usr/bin/env bash
# Whether MusicBrainz's own search analyzer already ignores every character
# MusicBrainzTitleFolding drops, so that an empty search pool rules out a fold match that would
# differ by nothing else.
#
# MusicBrainzTitleFolding.foldMatchPossible refuses the symbol-title fallback for a title whose
# fold differs from plain normalisation only by a dropped character. That refusal is only safe if
# the pool the caller's own title built could not have missed the stored title over that character
# alone -- a live property of MusicBrainz's index, not of this code, and one that moves whenever
# MusicBrainz changes its analyzer.
#
# Two directions, because a caller's text and a stored title can differ either way round:
#
#   QUERY SIDE  - the caller types the character, MusicBrainz stores the title without it. Built by
#                 decorating a plain stored title, so it runs for every character class.
#   STORED SIDE - MusicBrainz stores the character, the caller types the title plain. Needs a real
#                 stored title per class, so it runs only for the classes MusicBrainz actually
#                 holds an example of. Each seed is checked for existence first: a seed that has
#                 been retitled or merged away reports SEED STALE, never a false FAIL.
#
# Queries are built exactly as MusicBrainzApi builds them -- a quoted `release:` phrase over
# escapeLucene'd text -- because an unescaped bracket or brace is Lucene syntax and would answer a
# different question.
#
# Not a gate: live third-party calls, and the answer decays with MusicBrainz's catalogue and
# analyzer. Re-run it before citing it, and date what you paste.
#
#   ./scripts/probes/mb-dropped-char-analyzer-probe.sh
#
set -euo pipefail

UA="${PROBE_USER_AGENT:-musicmeta-probe/1.0 ( andrewmcdonald42@gmail.com )}"
BASE="https://musicbrainz.org/ws/2/release"
SPACING="${SPACING:-1.3}"

# MusicBrainzApi.escapeLucene, character for character.
escape_lucene() {
    python3 -c "
import re, sys
print(re.sub(r'([+\-&|!()\{\}\[\]^\"~*?:\\\\/])', r'\\\\\1', sys.argv[1]))
" "$1"
}

# Prints every distinct release title the strict search returns for this title/artist pair. A
# throttled or erroring call is retried and then aborts the run: read as an empty result it would
# be indistinguishable from the analyzer not finding the title, which is the answer being measured.
search_titles() {
    local title="$1" artist="$2" attempt body status query
    query="release:\"$(escape_lucene "$title")\" AND artistname:\"$(escape_lucene "$artist")\""
    for attempt in 1 2 3 4 5; do
        body="$(curl -s -w '\n%{http_code}' -H "User-Agent: $UA" --get \
            --data-urlencode "query=$query" --data-urlencode "limit=25" --data-urlencode "fmt=json" \
            "$BASE")"
        status="${body##*$'\n'}"
        sleep "$SPACING"
        if [ "$status" = "200" ]; then
            printf '%s' "${body%$'\n'*}" | python3 -c "
import json, sys
seen = set()
for release in json.load(sys.stdin).get('releases', []):
    if release['title'] not in seen:
        seen.add(release['title'])
        print(release['title'])
"
            return 0
        fi
        sleep "$attempt"
    done
    echo "probe aborted: MusicBrainz answered $status for $query" >&2
    exit 1
}

report() { printf '%-14s %-12s %-34s %s\n' "$1" "$2" "$3" "$4"; }

# Whether the strict search for this title/artist pair returns $3 verbatim. The result is captured
# before it is matched: piping straight into `grep -q` would SIGPIPE the producer, and `pipefail`
# would read that as the search having failed.
search_finds() {
    local titles
    titles="$(search_titles "$1" "$2")" || exit 1
    grep -qxF -- "$3" <<<"$titles"
}

echo "== QUERY SIDE: the caller types the character, MusicBrainz stores the title without it =="
report "class" "verdict" "query title" "expected stored title"

QUERY_SIDE_ARTIST="Radiohead"
QUERY_SIDE_STORED="OK Computer"
# Each entry is the class's opening and closing character, in that order.
for pair in '()' '[]' '{}' '""' "''" $'\u2018\u2019' $'\u201c\u201d' $'\u00ab\u00bb'; do
    open="${pair:0:1}"
    close="${pair:1}"
    typed="${open}${QUERY_SIDE_STORED}${close}"
    if search_finds "$typed" "$QUERY_SIDE_ARTIST" "$QUERY_SIDE_STORED"; then
        report "$pair" "PASS" "$typed" "$QUERY_SIDE_STORED"
    else
        report "$pair" "FAIL" "$typed" "$QUERY_SIDE_STORED"
    fi
done

echo
echo "== STORED SIDE: MusicBrainz stores the character, the caller types the title plain =="
report "class" "verdict" "plain query" "stored title sought"

# class | artist | stored title | the same title with the class's characters removed
#
# A seed must differ from its plain form by the character class and nothing else. Anything the fold
# leaves behind -- a `?`, a differently-curled apostrophe -- confounds the row, because a miss then
# has two candidate causes and this probe can only attribute one.
STORED_SIDE_SEEDS=(
    '()|Oasis|Morning Glory (unplugged)|Morning Glory unplugged'
    '[]|Deerhoof|[untitled]|untitled'
    '[]|Montrose|Montrose [Deluxe Edition]|Montrose Deluxe Edition'
    '{}|Villagers|{Awayland}|Awayland'
    "''|Thelonious Monk|'Round Midnight|Round Midnight"
    $'\u2019|Thelonious Monk|\u2019Round Midnight|Round Midnight'
    $'\u201c\u201d|David Bowie|\u201cHeroes\u201d|Heroes'
)
for seed in "${STORED_SIDE_SEEDS[@]}"; do
    IFS='|' read -r class artist stored plain <<<"$seed"
    if ! search_finds "$stored" "$artist" "$stored"; then
        report "$class" "SEED STALE" "$plain" "$stored"
        continue
    fi
    if search_finds "$plain" "$artist" "$stored"; then
        report "$class" "PASS" "$plain" "$stored"
    else
        report "$class" "FAIL" "$plain" "$stored"
    fi
done

cat <<EOF

No stored-side row exists for the straight double quote, the left single quote or the guillemets:
MusicBrainz's title style normalises quotes to the curly pair, and a browse of the release groups
of Kino, ARIA, Childish Gambino, Mylene Farmer, DDT and Nautilus Pompilius found no stored title
carrying one. Those three classes rest on their query-side row alone -- add a seed above the moment
a real stored example turns up.

A FAIL on any row means a title differing from a stored title only over that character can build an
empty pool, so foldMatchPossible must stop treating that character as invisible: drop it from
MusicBrainzTitleFolding.DROPPED_CHARS.

Measured $(date -u '+%Y-%m-%d %H:%MZ') from $(hostname -s 2>/dev/null || echo 'an unnamed host').
EOF
