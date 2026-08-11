#!/usr/bin/env bash
# Whether a hostile-but-reachable title/artist can make MusicBrainz reject a *search* with a 4xx.
#
# `bodyOrThrowTransient` maps HttpResult.ClientError to null, and every MusicBrainzApi.search*
# method turns that null into emptyList() -- so a 400 on a search currently reads as "no such
# release" and can trigger the symbol fallback and a NotFound-with-suggestions
# (.scratch/mb-4xx-as-empty-pool/spec.md). Whether that misreading is reachable turns entirely on
# whether MB's search endpoint can 4xx a query [buildQuery] actually emits -- not a hand-built
# hostile Lucene string, but the same escaping and framing the production code applies.
#
# Each case below rebuilds buildQuery's shape in bash: escape the same character class
# MusicBrainzApi.escapeLucene does, quote it the same way, URL-encode the whole query string, hit
# the same /release endpoint searchReleases calls. Cases:
#
#   1. baseline        -- a query that should succeed; the control every other row is read against.
#   2. over-long title  -- a title long enough to push the encoded URL past common length ceilings
#                          (a title this long cannot come from a real release, but nothing in the
#                          request path truncates or rejects it before it reaches buildQuery).
#   3. all-metachars    -- a title that is only Lucene special characters, so after escapeLucene it
#                          is a run of backslash-escaped punctuation with no alphanumeric content.
#   4. empty-after-trim -- a title that is blank once whitespace is stripped; buildQuery does not
#                          special-case this, so it becomes field:"" AND ...
#   5. raw-quote        -- a literal double-quote in the title. escapeLucene escapes '"' as part of
#                          LUCENE_SPECIAL_CHARS, so this tests whether the escaping actually closes
#                          the phrase MB sees, not whether an unescaped quote reaches MB (it can't).
#   6. control-chars    -- embedded control characters (NUL, a bare newline). Not in
#                          LUCENE_SPECIAL_CHARS, so escapeLucene passes them through unescaped --
#                          the one class the ticket's four inputs miss, found by reading the regex.
#
# NUL is the one byte bash's command substitution cannot carry (`ignored null byte in input`), so
# this script's control-chars row only exercises a bare newline. A real NUL was verified separately
# with `curl -G --data-urlencode "query@file"` reading a phrase containing one
# (`release:"abc\0def" AND artist:"radiohead"`) -- 200, measured 2026-08-12 -- so that class is
# covered too, just not reproducibly by this script.
#
# Respects MusicBrainz's 1 req/s limit (SPACING below). Not a gate: live third-party calls, so it
# cannot decide whether this repo's code is correct. Run it, read the status column, date it.
#
#   ./scripts/probes/mb-search-4xx-probe.sh
#
set -euo pipefail

UA="${PROBE_USER_AGENT:-musicmeta-probe/1.0 ( andrewmcdonald42@gmail.com )}"
BASE="https://musicbrainz.org/ws/2"
SPACING="${SPACING:-1.2}"
TIMEOUT="${TIMEOUT:-10}"

# Mirrors MusicBrainzApi.escapeLucene's LUCENE_SPECIAL_CHARS regex exactly:
# [+\-&|!(){}\[\]^"~*?:\\/]
escape_lucene() {
    local value="$1"
    local out="" ch
    local i
    for (( i = 0; i < ${#value}; i++ )); do
        ch="${value:$i:1}"
        case "$ch" in
            '+'|'-'|'&'|'|'|'!'|'('|')'|'{'|'}'|'['|']'|'^'|'"'|'~'|'*'|'?'|':'|"\\"|'/')
                out+="\\${ch}"
                ;;
            *)
                out+="$ch"
                ;;
        esac
    done
    printf '%s' "$out"
}

# Mirrors buildQuery: field:"escaped" AND field2:"escaped2", then URL-encode the whole thing --
# same call shape as searchReleases(title, artist).
build_query() {
    local title="$1" artist="$2"
    local raw
    raw="release:\"$(escape_lucene "$title")\" AND artistname:\"$(escape_lucene "$artist")\""
    python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$raw"
}

run_case() {
    local case_name="$1" title="$2" artist="$3"
    local query url code rc=0
    query="$(build_query "$title" "$artist")"
    url="$BASE/release?query=$query&fmt=json&limit=25"
    code="$(curl -s -o /dev/null -w '%{http_code}' -m "$TIMEOUT" -A "$UA" "$url")" || rc=$?
    printf '%-20s %5s  len=%-6d  %s\n' "$case_name" "${code:-ERR:$rc}" "${#url}" "$url"
    sleep "$SPACING"
}

echo "case                 status  url-length  url"
echo "----                 ------  ----------  ---"

run_case "baseline" "OK Computer" "Radiohead"

# Over-long title: 3000 chars pushes the encoded URL well past common 8KB/2KB ceilings.
over_long_title="$(python3 -c "print('a' * 3000)")"
run_case "over-long" "$over_long_title" "Radiohead"

# All Lucene metacharacters, nothing else -- after escaping, still non-alphanumeric.
run_case "all-metachars" '+-&|!(){}[]^"~*?:\/' "Radiohead"

# Blank after trim.
run_case "empty-after-trim" "   " "Radiohead"

# Raw quote in the title -- exercises whether escapeLucene actually closes the phrase.
run_case "raw-quote" 'Abbey "Road' "The Beatles"

# Control characters escapeLucene's regex does not cover: NUL and a bare newline.
run_case "control-chars" "$(printf 'OK\x00Computer\nSide2')" "Radiohead"

cat <<EOF

Read the status column: 4xx (400/414/etc) on any row other than a deliberately malformed control
input means a reachable title/artist can turn ClientError -> null -> an empty pool on a search.
A non-4xx (200, or MB's own error shape inside a 200) means that input is not the reachable-4xx
path. This is a sample, not a proof for every future MB release -- re-run before citing a stale
answer.

Measured $(date -u '+%Y-%m-%d %H:%MZ') from $(hostname -s 2>/dev/null || echo 'an unnamed host').
EOF
