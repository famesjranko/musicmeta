#!/usr/bin/env bash
# Measures how often each provider endpoint fails, and *how* it fails.
#
# The distinction is the whole point. A 503, a 429 and a read timeout all reach the engine as
# ErrorKind.NETWORK, so a failing test cannot tell them apart — but they are different upstream
# problems with different fixes, and only some of them are on DefaultHttpClient's retry ladder.
# Counting them separately is what stops "the provider is flaky" from standing in for a measurement.
#
# Not a gate, and it must never become one: it makes live third-party calls, so it cannot decide
# whether this repo's code is correct. It is a measuring instrument. Run it, paste the block it
# prints into whatever claim needs backing, and date it.
#
# **One run is a sample, not a rate.** Ten requests that all pass do not mean an endpoint is
# healthy; they mean ten passed. Raise ROUNDS before quoting a percentage anywhere.
#
# Only the eight providers that need no credentials. The keyed three — Last.fm, Fanart.tv,
# Discogs — are out of scope deliberately: putting a key in a URL puts it in this script's output,
# and an availability number is not worth that.
#
#   ROUNDS=10 SPACING=1.2 ./scripts/probes/provider-transient-probe.sh
#
# `COUNTS_FILE` turns one run into a row in a time series instead of a number in a paste buffer.
# The table below is a sample; a provider degrading from that sample is only visible against the
# runs before it, and an UNAVAILABLE that nobody wrote down leaves no trace at all. Given a path,
# every target's per-kind counts are appended to it, one row per provider and kind:
#
#   COUNTS_FILE=scripts/probes/provider-availability-counts.csv \
#     ROUNDS=20 ./scripts/probes/provider-transient-probe.sh
#
# The kinds are the schema pin's vocabulary — `ok`, `http <code>`, `transport <message>` — so a
# count and a pin verdict can be read side by side. `scripts/checks/check_availability_trend.py`
# is what reads the file, and enforces that vocabulary: a bucket renamed here fails the parser
# rather than going quiet there.
#
# **One series, one vantage point.** Rows appended from a different network measure that network's
# egress as much as the upstream's availability, and a run compared against a row taken from
# somewhere else is comparing two things. Append from the machine the earlier rows came from, or
# start a separate file.
#
set -euo pipefail

ROUNDS="${ROUNDS:-10}"
# Under MusicBrainz's documented 1 req/s ceiling, so a shed here is the upstream's choice and not
# this script exceeding a published budget.
SPACING="${SPACING:-1.2}"
# Matches DefaultHttpClient's timeoutMs. A hang that would fail the library should fail here too,
# and a hang that would not should not — otherwise the numbers do not transfer. Approximate: the
# client applies 10s to connect and 10s to read separately, curl applies it to the whole request.
TIMEOUT="${TIMEOUT:-10}"
UA="${PROBE_USER_AGENT:-musicmeta-probe/1.0 ( andrewmcdonald42@gmail.com )}"
# Empty means print and forget, which is what a one-off measurement wants.
COUNTS_FILE="${COUNTS_FILE:-}"

# One line per target: <name> <url>. MusicBrainz appears twice on purpose — it reports different
# rate-limit zones with different limits per endpoint, so a host-level number would average two
# unrelated things.
TARGETS=(
    "musicbrainz-search|https://musicbrainz.org/ws/2/release-group?query=release:OK%20Computer%20AND%20artist:Radiohead&fmt=json&limit=5"
    "musicbrainz-artist|https://musicbrainz.org/ws/2/artist?query=Radiohead&fmt=json&limit=1"
    "listenbrainz|https://api.listenbrainz.org/1/stats/sitewide/artists?count=1"
    "wikidata|https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q44190&format=json"
    "wikipedia|https://en.wikipedia.org/api/rest_v1/page/summary/Radiohead"
    "coverartarchive|https://coverartarchive.org/release-group/b1392450-e666-3926-a536-22c65f834433/front"
    "deezer|https://api.deezer.com/search/album?q=OK%20Computer&limit=1"
    "itunes|https://itunes.apple.com/search?term=radiohead&entity=album&limit=1"
    "lrclib|https://lrclib.net/api/search?track_name=Karma%20Police&artist_name=Radiohead"
)

# Round-robin across every target rather than draining one at a time, so the other targets are the
# control. One endpoint timing out while the rest answer in the same second is the upstream's
# problem; everything failing at once is this machine's. Without that, a local network fault reads
# as provider drift — which is the mistake this whole effort exists to stop making.
declare -A ok fourxx retryable_5xx other_5xx rate_limited timed_out conn_failed
# The same requests counted a second time, keyed `<target>|<kind>` in the schema pin's vocabulary
# and holding the status code the buckets above collapse: a run that sheds 500s and a run that
# sheds 503s read alike in `5xx`, and the counts file is what a later run is compared against.
declare -A kinds

for name in "${TARGETS[@]}"; do
    key="${name%%|*}"
    ok["$key"]=0 fourxx["$key"]=0 retryable_5xx["$key"]=0 other_5xx["$key"]=0
    rate_limited["$key"]=0 timed_out["$key"]=0 conn_failed["$key"]=0
done

printf 'probing %d targets x %d rounds, %ss apart, %ss timeout\n\n' \
    "${#TARGETS[@]}" "$ROUNDS" "$SPACING" "$TIMEOUT"

for _ in $(seq 1 "$ROUNDS"); do
    for target in "${TARGETS[@]}"; do
        key="${target%%|*}"
        url="${target#*|}"
        rc=0
        code="$(curl -s -o /dev/null -w '%{http_code}' -m "$TIMEOUT" -A "$UA" "$url")" || rc=$?
        # Classified once, in the schema pin's vocabulary, with the buckets read off that single
        # verdict. Two classifications of one request drift apart the first time a status code
        # moves between them, and the table and the counts file would then disagree silently.
        if [ "$rc" -eq 0 ] && [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then
            case "$code" in
                # 3xx counts as answered: Cover Art Archive's normal reply is a redirect to
                # where the image lives, which fetchRedirectUrlOnce is built to read. Following
                # it here would measure archive.org's availability instead of the provider's.
                2*|3*) kind="ok" ;;
                *) kind="http $code" ;;
            esac
        elif [ "$rc" -eq 28 ]; then
            # 28 is curl's timeout. Counted apart from every other transport failure because it is
            # the one the retry ladder does not cover: it arrives as HttpResult.NetworkError, which
            # returns unretried, after spending the full timeout of the enclosing EnrichDeadline.
            kind="transport timeout"
        else
            kind="transport connect"
        fi
        kinds["$key|$kind"]=$(( ${kinds["$key|$kind"]:-0} + 1 ))
        case "$kind" in
            ok) ok["$key"]=$(( ok["$key"] + 1 )) ;;
            "http 429") rate_limited["$key"]=$(( rate_limited["$key"] + 1 )) ;;
            "http 4"*) fourxx["$key"]=$(( fourxx["$key"] + 1 )) ;;
            # The closed retryable set DefaultHttpClient uses. Split from the other 5xx because
            # only these are recovered by a retry; a 500 or 501 counted alongside them would make
            # the ladder look more effective than it is.
            "http 502"|"http 503"|"http 504") retryable_5xx["$key"]=$(( retryable_5xx["$key"] + 1 )) ;;
            "http 5"*) other_5xx["$key"]=$(( other_5xx["$key"] + 1 )) ;;
            "transport timeout") timed_out["$key"]=$(( timed_out["$key"] + 1 )) ;;
            *) conn_failed["$key"]=$(( conn_failed["$key"] + 1 )) ;;
        esac
        printf '.'
        sleep "$SPACING"
    done
done

printf '\n\n%-22s %5s %5s %5s %5s %5s %8s %6s\n' \
    target ok 4xx 429 "5xx*" 5xx timeout connfail
printf -- '%.0s-' {1..66}; printf '\n'
for target in "${TARGETS[@]}"; do
    key="${target%%|*}"
    printf '%-22s %5d %5d %5d %5d %5d %8d %6d\n' "$key" \
        "${ok[$key]}" "${fourxx[$key]}" "${rate_limited[$key]}" \
        "${retryable_5xx[$key]}" "${other_5xx[$key]}" \
        "${timed_out[$key]}" "${conn_failed[$key]}"
done

cat <<EOF

5xx* is 502/503/504 — the set DefaultHttpClient retries. Everything to its right is a transport
failure the ladder does not recover: a timeout or a dropped connection returns on the first attempt.

$ROUNDS rounds per target, measured $(date -u '+%Y-%m-%d %H:%MZ') from $(hostname -s 2>/dev/null || echo 'an unnamed host').
A single run is a sample. Quote the round count alongside any rate taken from it.
EOF

# One row per target and kind, appended. Written after the table so a failure here cannot cost the
# measurement itself — the numbers are on stdout before this line runs.
#
# The `ok` row is written even at zero, so a target that answered nothing is still present in the
# run: a provider missing from a run is not a provider that was fine, and the reader needs to tell
# those apart. Every row carries the run's total, which makes a row independently readable and lets
# the reader check that a target's kinds sum to it — a dropped row would otherwise read as one
# fewer failure.
if [ -n "$COUNTS_FILE" ]; then
    run_id="$(date -u '+%Y-%m-%dT%H:%MZ')"
    if [ ! -s "$COUNTS_FILE" ]; then
        printf 'run,provider,kind,count,total\n' > "$COUNTS_FILE"
    fi
    {
        for target in "${TARGETS[@]}"; do
            key="${target%%|*}"
            printf '%s,%s,ok,%d,%d\n' "$run_id" "$key" "${kinds["$key|ok"]:-0}" "$ROUNDS"
            while IFS= read -r entry; do
                [ -n "$entry" ] || continue
                printf '%s,%s,%s,%d,%d\n' \
                    "$run_id" "$key" "${entry#*|}" "${kinds["$entry"]}" "$ROUNDS"
            done < <(printf '%s\n' "${!kinds[@]}" | grep "^${key}|" | grep -v "^${key}|ok\$" | sort)
        done
    } >> "$COUNTS_FILE"
    printf '\nappended run %s to %s\n' "$run_id" "$COUNTS_FILE"
fi
