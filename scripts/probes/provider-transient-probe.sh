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
        case "$rc" in
            0)
                case "$code" in
                    # 3xx counts as answered: Cover Art Archive's normal reply is a redirect to
                    # where the image lives, which fetchRedirectUrlOnce is built to read. Following
                    # it here would measure archive.org's availability instead of the provider's.
                    2*|3*) ok["$key"]=$(( ok["$key"] + 1 )) ;;
                    429) rate_limited["$key"]=$(( rate_limited["$key"] + 1 )) ;;
                    4*) fourxx["$key"]=$(( fourxx["$key"] + 1 )) ;;
                    # The closed retryable set DefaultHttpClient uses. Split from the other 5xx
                    # because only these are recovered by a retry; a 500 or 501 counted alongside
                    # them would make the ladder look more effective than it is.
                    502|503|504) retryable_5xx["$key"]=$(( retryable_5xx["$key"] + 1 )) ;;
                    5*) other_5xx["$key"]=$(( other_5xx["$key"] + 1 )) ;;
                    *) conn_failed["$key"]=$(( conn_failed["$key"] + 1 )) ;;
                esac
                ;;
            # 28 is curl's timeout. Counted apart from every other transport failure because it is
            # the one the retry ladder does not cover: it arrives as HttpResult.NetworkError, which
            # returns unretried, after spending the full timeout of the enclosing EnrichDeadline.
            28) timed_out["$key"]=$(( timed_out["$key"] + 1 )) ;;
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
