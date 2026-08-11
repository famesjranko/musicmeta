#!/usr/bin/env bash
# Whether a hostile-but-reachable title/artist can make MusicBrainz reject a *search* with a 4xx.
#
# `bodyOrThrowTransient` maps HttpResult.ClientError to null, and every MusicBrainzApi.search*
# method turns that null into emptyList() -- so a 400 on a search currently reads as "no such
# release" and can trigger the symbol fallback and a NotFound-with-suggestions
# (.scratch/mb-4xx-as-empty-pool/spec.md). Whether that misreading is reachable turns entirely on
# whether MB's search endpoint can 4xx a query the production code actually emits -- not a
# hand-built hostile Lucene string, but the same escaping, quoting/framing, and byte-for-byte
# encoding the two query shapes in MusicBrainzApi.kt apply.
#
# Two shapes, because they differ in framing, not just field names:
#
#   STRICT (buildQuery, e.g. searchReleases): field:"escaped" AND field2:"escaped2" -- quoted.
#   FUZZY  (searchReleasesFuzzy/searchArtistsFuzzy/searchRecordingsFuzzy, MusicBrainzApi.kt:35,
#           59,198): field:escaped~ AND field2:escaped2~ -- UNQUOTED, with a trailing Lucene `~`
#           (fuzzy-match operator) on each term. escapeLucene runs the same either way; only the
#           quoting differs. An empty-after-trim title here becomes `release:~ AND ...` with the
#           fuzzy operator sitting directly on nothing, which the strict shape's quoted `""` can't
#           produce -- exactly the case this section exists to check MB doesn't choke on.
#
# Both shapes run the same hostile inputs:
#
#   1. baseline        -- a query that should succeed; the control every other row is read against.
#   2. over-long title  -- a title long enough to push the encoded URL past common length ceilings
#                          (a title this long cannot come from a real release, but nothing in the
#                          request path truncates or rejects it before it reaches the query builder).
#   3. all-metachars    -- a title that is only Lucene special characters, so after escapeLucene it
#                          is a run of backslash-escaped punctuation with no alphanumeric content.
#   4. empty-after-trim -- a title that is blank once whitespace is stripped; neither builder
#                          special-cases this.
#   5. raw-quote        -- a literal double-quote in the title. escapeLucene escapes '"' as part of
#                          LUCENE_SPECIAL_CHARS, so this tests whether the escaping actually closes
#                          the phrase MB sees (strict) or leaves a stray escaped quote sitting in an
#                          unquoted term (fuzzy) -- not whether an unescaped quote reaches MB, which
#                          it can't either way.
#   6. control-chars    -- a real NUL and a bare newline, embedded in the title. Not in
#                          LUCENE_SPECIAL_CHARS, so escapeLucene passes them through unescaped --
#                          the one class the ticket's four inputs miss, found by reading the regex.
#
# Encoding fidelity: production calls java.net.URLEncoder.encode(value, "UTF-8") --
# application/x-www-form-urlencoded, which leaves only [A-Za-z0-9.\-*_] and space (as `+`) unescaped
# and percent-encodes everything else, INCLUDING '~'. That is not what Python's
# urllib.parse.quote/quote_plus do out of the box -- both hardcode '~' (and '_.-') as "always safe"
# regardless of the `safe=` argument, so a naive quote_plus call would send an unescaped `~` where
# Java sends `%7E`, and the empty-after-trim/all-metachars rows are exactly where those bytes
# diverge. `encode_like_java` below reimplements URLEncoder's rule directly instead of trusting
# quote_plus to match it, so the URL sent here is the URL production sends, byte for byte.
#
# The control-chars row sends a real NUL. Bash command substitution silently drops NUL
# (`ignored null byte in input`), so the phrase is built and encoded once in Python, written to a
# temp file, and read from there with `curl -G --data-urlencode query@file` -- the file is the only
# channel between here and curl that can carry the byte at all.
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

# Reimplements java.net.URLEncoder.encode(value, "UTF-8") byte for byte: unreserved is
# [A-Za-z0-9.\-*_], space becomes '+', everything else is percent-encoded UTF-8. Python's
# urllib.parse.quote/quote_plus cannot be handed this rule via `safe=` alone -- both always leave
# '~' (and '_.-') unescaped regardless of `safe=`, which disagrees with URLEncoder on '~', the
# character every fuzzy-shape term ends with.
encode_like_java() {
    python3 - "$1" <<'PYEOF'
import sys
value = sys.argv[1]
unreserved = set(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-*_"
)
out = []
for byte in value.encode("utf-8"):
    ch = chr(byte)
    if byte < 128 and ch in unreserved:
        out.append(ch)
    elif ch == " ":
        out.append("+")
    else:
        out.append("%{:02X}".format(byte))
print("".join(out), end="")
PYEOF
}

# Mirrors buildQuery: field:"escaped" AND field2:"escaped2", quoted -- same shape as
# searchReleases(title, artist).
build_query_strict() {
    local title="$1" artist="$2"
    local raw
    raw="release:\"$(escape_lucene "$title")\" AND artistname:\"$(escape_lucene "$artist")\""
    encode_like_java "$raw"
}

# Mirrors the fuzzy builders: field:escaped~ AND field2:escaped2~, UNQUOTED -- same shape as
# searchReleasesFuzzy(title, artist).
build_query_fuzzy() {
    local title="$1" artist="$2"
    local raw
    raw="release:$(escape_lucene "$title")~ AND artistname:$(escape_lucene "$artist")~"
    encode_like_java "$raw"
}

run_case() {
    local case_name="$1" shape="$2" title="$3" artist="$4"
    local query url code rc=0
    if [[ "$shape" == "fuzzy" ]]; then
        query="$(build_query_fuzzy "$title" "$artist")"
    else
        query="$(build_query_strict "$title" "$artist")"
    fi
    url="$BASE/release?query=$query&fmt=json&limit=25"
    code="$(curl -s -o /dev/null -w '%{http_code}' -m "$TIMEOUT" -A "$UA" "$url")" || rc=$?
    printf '%-9s %-20s %5s  len=%-6d  %s\n' "$shape" "$case_name" "${code:-ERR:$rc}" "${#url}" "$url"
    sleep "$SPACING"
}

# The control-chars row for each shape: builds and encodes the phrase (with a real NUL) in Python,
# writes it to a temp file, and sends it with --data-urlencode query@file -- the one path that
# carries a NUL from here to curl at all.
run_control_chars_case() {
    local shape="$1"
    local tmp
    tmp="$(mktemp)"
    trap 'rm -f "$tmp"' RETURN
    if [[ "$shape" == "fuzzy" ]]; then
        python3 -c '
import sys
title = "OK\x00Computer\nSide2"
artist = "Radiohead"
sys.stdout.write("release:" + title + "~ AND artistname:" + artist + "~")
' >"$tmp"
    else
        python3 -c '
import sys
title = "OK\x00Computer\nSide2"
artist = "Radiohead"
sys.stdout.write("release:\"" + title + "\" AND artistname:\"" + artist + "\"")
' >"$tmp"
    fi
    local code rc=0
    code="$(curl -s -o /dev/null -w '%{http_code}' -m "$TIMEOUT" -A "$UA" -G \
        --data-urlencode "query@$tmp" --data-urlencode "fmt=json" --data-urlencode "limit=25" \
        "$BASE/release")" || rc=$?
    printf '%-9s %-20s %5s  (query@%s, real NUL, --data-urlencode)\n' "$shape" "control-chars" "${code:-ERR:$rc}" "$(basename "$tmp")"
    sleep "$SPACING"
}

echo "shape     case                 status  url-length  url"
echo "-----     ----                 ------  ----------  ---"

for shape in strict fuzzy; do
    run_case "baseline" "$shape" "OK Computer" "Radiohead"

    # Over-long title: 3000 chars pushes the encoded URL well past common 8KB/2KB ceilings.
    over_long_title="$(python3 -c "print('a' * 3000)")"
    run_case "over-long" "$shape" "$over_long_title" "Radiohead"

    # All Lucene metacharacters, nothing else -- after escaping, still non-alphanumeric.
    run_case "all-metachars" "$shape" '+-&|!(){}[]^"~*?:\/' "Radiohead"

    # Blank after trim.
    run_case "empty-after-trim" "$shape" "   " "Radiohead"

    # Raw quote in the title -- exercises whether escapeLucene actually closes the phrase (strict)
    # or leaves a stray escaped quote inside an unquoted term (fuzzy).
    run_case "raw-quote" "$shape" 'Abbey "Road' "The Beatles"

    # Control characters escapeLucene's regex does not cover: a real NUL and a bare newline.
    run_control_chars_case "$shape"
done

cat <<EOF

Read the status column: 4xx (400/414/etc) on any row means a reachable title/artist can turn
ClientError -> null -> an empty pool on that shape's search. A non-4xx (200, or MB's own error
shape inside a 200) means that input is not the reachable-4xx path for that shape. This is a
sample, not a proof for every future MB release -- re-run before citing a stale answer.

Measured $(date -u '+%Y-%m-%d %H:%MZ') from $(hostname -s 2>/dev/null || echo 'an unnamed host').
EOF
