#!/usr/bin/env bash
# What Wikipedia's two surfaces actually return for the fields WikipediaApi reads.
#
# Three questions, all of which decayed since the code was written and will decay again:
#
#   1. MEDIA. `page/media-list` items used to carry an `original{source,width,height}` object.
#      They now carry a `srcset` of rendered thumbnails and a `leadImage` flag, and nothing else --
#      no original URL, no height. A parser written against `original` selects nothing, or selects
#      whichever incidental diagram happens to still have the old shape, while the article's own
#      photograph is invisible to it. The MEDIA section counts, per page, how many items the old
#      rule can see and what the new rule picks, so the failure is a number and not an anecdote.
#
#   2. BIO. The extract moved from `rest_v1 page/summary` to the Action API
#      (`prop=extracts&exintro&explaintext`). The two are not the same text: the Action API keeps
#      the parentheticals the summary endpoint strips -- instrument credits, IPA pronunciations,
#      native-script names. That is a visible change to every biography this library returns, so
#      it is measured, not assumed. The BIO section prints the common prefix length as a share of
#      the summary's length (100% = the Action extract starts with the whole summary lead) and the
#      first divergence for each page, which is where the parenthetical sits.
#
#   3. DEPRECATION. `rest_v1` is the legacy surface. Live responses are the only place a
#      `Deprecation` or `Sunset` header would appear before removal, and the documentation is the
#      only place a date would. The HEADERS section checks the former; the latter is a reading
#      task, recorded in docs/providers.md.
#
#   ./scripts/probes/wikipedia-surface-probe.sh
#   PAGES='Radiohead|Bj%C3%B6rk' ./scripts/probes/wikipedia-surface-probe.sh
#
# Wikimedia asks every client to identify itself; UA below carries a contact, and SPACING paces the
# run well under the conventions. Not a gate -- live third-party calls cannot decide whether this
# repo's code is correct. Run it, read the columns, date the answer.
set -euo pipefail

UA="${PROBE_USER_AGENT:-musicmeta-probe/1.0 ( https://github.com/famesjranko/musicmeta; andrewmcdonald42@gmail.com )}"
PAGES="${PAGES:-Radiohead|Metallica|Portishead_(band)|Sigur_R%C3%B3s|Kikagaku_Moyo|The_Books}"
SPACING="${SPACING:-0.5}"
TIMEOUT="${TIMEOUT:-15}"

REST="https://en.wikipedia.org/api/rest_v1/page"
ACTION="https://en.wikipedia.org/w/api.php"

fetch() {
    curl -sS --max-time "$TIMEOUT" -A "$UA" "$1"
}

# Per page: how many items the OLD rule (type=image, non-svg/icon/logo title, original.source
# present, original.width >= 100) can see, what it would pick, and what the NEW rule (same title
# rules, srcset[0].src, width read from the /NNNpx- segment, leadImage first) picks.
media_row() {
    local page="$1" body
    body="$(fetch "$REST/media-list/$page")"
    python3 - "$page" "$body" <<'PYEOF'
import json, re, sys

page = sys.argv[1]
try:
    items = json.loads(sys.argv[2]).get("items", [])
except json.JSONDecodeError:
    print(f"{page:<22} PARSE-ERROR")
    sys.exit(0)

def titled(item):
    title = item.get("title", "")
    if item.get("type") != "image":
        return False
    low = title.lower()
    return not (low.endswith(".svg") or "icon" in low or "logo" in low)

old = []
for item in items:
    if not titled(item):
        continue
    original = item.get("original")
    if not isinstance(original, dict) or not original.get("source"):
        continue
    if (original.get("width") or 0) and original["width"] < 100:
        continue
    old.append(item.get("title", ""))

new = []
for item in items:
    if not titled(item):
        continue
    srcset = item.get("srcset") or []
    if not srcset or not srcset[0].get("src"):
        continue
    match = re.search(r"/(\d+)px-", srcset[0]["src"])
    if match and int(match.group(1)) < 100:
        continue
    new.append((bool(item.get("leadImage")), item.get("title", "")))
new.sort(key=lambda pair: not pair[0])

leads = [t for lead, t in new if lead]
print(
    f"{page:<22} items={len(items):<3} old-visible={len(old):<3} "
    f"new-visible={len(new):<3} lead={leads[0] if leads else '-'}"
)
print(f"{'':<22} old-pick={old[0] if old else '(none)'}")
print(f"{'':<22} new-pick={new[0][1] if new else '(none)'}")
PYEOF
}

# Per page: the summary lead against the Action API extract -- prefix overlap and first divergence.
bio_row() {
    local page="$1"
    local summary extract
    summary="$(fetch "$REST/summary/$page")"
    sleep "$SPACING"
    extract="$(fetch "$ACTION?action=query&format=json&formatversion=2&redirects=1&prop=extracts&exintro=1&explaintext=1&titles=$page")"
    python3 - "$page" "$summary" "$extract" <<'PYEOF'
import json, sys

page = sys.argv[1]
try:
    summary = json.loads(sys.argv[2]).get("extract", "")
    pages = json.loads(sys.argv[3]).get("query", {}).get("pages", [{}])
    action = pages[0].get("extract", "")
except (json.JSONDecodeError, IndexError):
    print(f"{page:<22} PARSE-ERROR")
    sys.exit(0)

common = 0
for a, b in zip(summary, action):
    if a != b:
        break
    common += 1
share = round(100 * common / len(summary)) if summary else 0
print(f"{page:<22} summary={len(summary):<5} action={len(action):<5} prefix={share}%")
if common < len(summary):
    print(f"{'':<22} diverges: ...{action[common:common + 60]!r}")
PYEOF
}

echo "=== MEDIA  (page/media-list: what the pre-srcset rule sees vs what leadImage picks)"
printf '%s\n' "$PAGES" | tr '|' '\n' | while IFS= read -r page; do
    [ -n "$page" ] || continue
    media_row "$page"
    sleep "$SPACING"
done

echo
echo "=== BIO  (rest_v1 page/summary vs Action API prop=extracts -- the visible copy change)"
printf '%s\n' "$PAGES" | tr '|' '\n' | while IFS= read -r page; do
    [ -n "$page" ] || continue
    bio_row "$page"
    sleep "$SPACING"
done

echo
echo "=== HEADERS  (a Deprecation or Sunset header is the only live wind-down signal)"
for url in \
    "$REST/summary/Radiohead" \
    "$REST/media-list/Radiohead" \
    "$ACTION?action=query&format=json&titles=Radiohead"
do
    printf '%s\n' "$url"
    curl -sS -D - -o /dev/null --max-time "$TIMEOUT" -A "$UA" "$url" \
        | grep -iE '^HTTP/|^deprecation:|^sunset:|^warning:' | sed 's/^/    /'
    sleep "$SPACING"
done

cat <<EOF

MEDIA: old-pick is what the pre-srcset parser would return today; new-pick is what leadImage
selects. A page where they disagree is a wrong artist photo shipped to a consumer.
BIO: prefix=100% means the Action extract opens with the whole summary lead and only adds to it;
anything less is text the summary endpoint stripped, and the diverges line shows what.
HEADERS: no Deprecation/Sunset line means no live wind-down signal -- it is not a promise.

Measured $(date -u '+%Y-%m-%d %H:%MZ').
EOF
