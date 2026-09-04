#!/usr/bin/env python3
"""Fail on a provider whose upstream has been shedding over the threshold two runs running.

An UNAVAILABLE leaves no trace. The schema pin reports one on the day it happens and then forgets
it, so a provider degrading steadily looks exactly like one that is fine, and the only measurement
this repo has of that — 4 failures in 90 requests, three of them one provider and seven providers
with none — was taken by hand, once. This reads the time series `provider-transient-probe.sh`
writes and says which provider is getting worse.

**Per kind, per provider, never one rate.** A provider that times out and a provider that sheds a
503 have different upstream problems and different fixes; averaging them is how a read timeout
stayed invisible for as long as it did. The kinds are the schema pin's own vocabulary — `ok`,
`http <code>`, `transport <message>` — so a count here and a pin verdict can be read side by side,
and a bucket renamed on one side fails the parser rather than going quiet on the other.

**The threshold and why it is two runs.** Flag a provider at or above 3 failures in 20 requests
(15%), on the two most recent runs. Derived for `ROUNDS=20`, from a 4.4% baseline measured
2026-08-13: one run at that threshold alerts on 5.5% of healthy providers and catches 96.5% of a
provider degraded to 30%; requiring two consecutive runs takes the false alarm to ~0.3% per
provider per fortnight while detection stays ~93%. The baseline is one sample from one day —
re-run the probe and recompute before treating the number as tuned.

**A run in which every provider is over the threshold is about this machine, not an upstream.** The
probe round-robins across targets precisely so the healthy ones control for the network, and a
bad afternoon that hits all of them is what that control exists to absorb. Such a run is reported
and then excluded from attribution, the same judgement the pin makes when every route is
unavailable.

**Never a merge gate.** It reads live third-party availability, so it cannot decide whether this
repo's code is correct: `./check` runs only its self-test, and this is run by hand after a probe
run appends a row. What it *can* do wrong is read nothing and look healthy, so a missing file, a
header-only file, an unknown kind, counts that do not sum to their total and runs out of append
order are all failures rather than an empty clean report.

    python3 check_availability_trend.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

COUNTS_FILE = "scripts/probes/provider-availability-counts.csv"

HEADER = "run,provider,kind,count,total"

# The threshold as measured, kept as its two numbers rather than a float: the run count is what
# makes the false-alarm arithmetic above reproducible, and a share alone loses it.
THRESHOLD_FAILURES = 3
THRESHOLD_TOTAL = 20

# The schema pin's vocabulary, and nothing else. `PinVerdict.Unavailable` is built as
# `http <code>` or `transport <message>`; `ok` is the answered case the pin has no name for
# because a pinned route that answers is judged on its fields instead.
KIND = re.compile(r"^(ok|http [1-5][0-9][0-9]|transport [a-z][a-z ]*[a-z])$")

RUN_ID = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}Z$")


def error(message: str) -> str:
    return f"::error::{message}"


def notice(message: str) -> str:
    return f"::notice::{message}"


class Malformed(Exception):
    """A counts file that cannot be trusted to mean what it says."""


Run = tuple[str, dict[str, dict[str, int]], dict[str, int]]


def parse(text: str) -> list[Run]:
    """The runs in a counts file, oldest first, as (run id, per-provider kind counts, totals).

    Strict on every axis a silent misread could hide behind: the header, the field count, the kind
    vocabulary, integers that are counts, a provider's counts summing to its total, one row per
    provider and kind, and runs arriving in append order. Each raises `Malformed` — the trend must
    fail loudly rather than report the zero failures an unparsed file contains.
    """
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        raise Malformed("the file is empty")
    if lines[0] != HEADER:
        raise Malformed(f"the header is `{lines[0]}`, expected `{HEADER}`")
    if len(lines) == 1:
        raise Malformed("it holds a header and no rows")

    runs: list[Run] = []
    seen: set[str] = set()
    for lineno, line in enumerate(lines[1:], start=2):
        fields = line.split(",")
        if len(fields) != 5:
            raise Malformed(f"line {lineno} has {len(fields)} fields, expected 5: `{line}`")
        run_id, provider, kind, count_text, total_text = (field.strip() for field in fields)
        if not RUN_ID.match(run_id):
            raise Malformed(f"line {lineno} has run id `{run_id}`, expected YYYY-MM-DDTHH:MMZ")
        if not provider:
            raise Malformed(f"line {lineno} names no provider: `{line}`")
        if not KIND.match(kind):
            raise Malformed(
                f"line {lineno} has kind `{kind}`, which is outside the schema pin's vocabulary "
                "(`ok`, `http <code>`, `transport <message>`)",
            )
        try:
            count, total = int(count_text), int(total_text)
        except ValueError as invalid:
            raise Malformed(f"line {lineno} has a non-integer count or total: `{line}`") from invalid
        if count < 0 or total <= 0 or count > total:
            raise Malformed(f"line {lineno} has count {count} against total {total}: `{line}`")

        if not runs or runs[-1][0] != run_id:
            if run_id in seen:
                raise Malformed(f"line {lineno} reopens run `{run_id}`, whose rows are not contiguous")
            if runs and run_id < runs[-1][0]:
                raise Malformed(f"line {lineno} has run `{run_id}` after the later `{runs[-1][0]}`")
            seen.add(run_id)
            runs.append((run_id, {}, {}))
        _, counts, totals = runs[-1]
        if kind in counts.setdefault(provider, {}):
            raise Malformed(f"line {lineno} repeats kind `{kind}` for `{provider}` in run `{run_id}`")
        counts[provider][kind] = count
        if totals.setdefault(provider, total) != total:
            raise Malformed(
                f"line {lineno} gives `{provider}` total {total} in run `{run_id}`, "
                f"after {totals[provider]} on an earlier row",
            )

    for run_id, counts, totals in runs:
        for provider, kinds in counts.items():
            summed = sum(kinds.values())
            if summed != totals[provider]:
                raise Malformed(
                    f"run `{run_id}` accounts for {summed} of `{provider}`'s {totals[provider]} "
                    "requests — a row is missing, so its failures cannot be counted",
                )
    return runs


def over_threshold(kinds: dict[str, int], total: int) -> bool:
    """Whether this provider's failures in one run reach the measured threshold share."""
    failures = sum(count for kind, count in kinds.items() if kind != "ok")
    return failures * THRESHOLD_TOTAL >= THRESHOLD_FAILURES * total


def failure_summary(kinds: dict[str, int], total: int) -> str:
    """`6/20 transport timeout x6` — the share and the kinds behind it, worst kind first."""
    failures = {kind: count for kind, count in kinds.items() if kind != "ok"}
    by_size = sorted(failures.items(), key=lambda item: (-item[1], item[0]))
    return f"{sum(failures.values())}/{total} " + ", ".join(f"{kind} x{count}" for kind, count in by_size)


def findings_for(runs: list[Run]) -> list[str]:
    """Findings for a parsed history: the flags, and the notices that explain a quiet run."""
    if len(runs) < 2:
        return [
            notice(
                f"only one run in `{COUNTS_FILE}`, so no provider was compared against a previous "
                "one. The two-consecutive-runs rule needs a second run before it can flag anything.",
            ),
        ]

    findings = []
    (_, previous_counts, previous_totals) = runs[-2]
    (latest_id, latest_counts, latest_totals) = runs[-1]

    breached = [p for p, kinds in latest_counts.items() if over_threshold(kinds, latest_totals[p])]
    if breached and len(breached) == len(latest_counts):
        return [
            notice(
                f"run `{latest_id}`: every measured provider ({len(breached)}) was over the "
                "threshold, which is this machine's network rather than an upstream — the probe "
                "round-robins so the healthy targets control for exactly this. Not attributed to "
                "any provider.",
            ),
        ]

    for provider in sorted(breached):
        if provider not in previous_counts or not over_threshold(previous_counts[provider], previous_totals[provider]):
            continue
        findings.append(
            error(
                f"`{provider}` was over the availability threshold "
                f"({THRESHOLD_FAILURES} in {THRESHOLD_TOTAL}) on two consecutive runs: "
                f"{failure_summary(previous_counts[provider], previous_totals[provider])}, then "
                f"{failure_summary(latest_counts[provider], latest_totals[provider])} in "
                f"`{latest_id}`. Read the kinds — a timeout and a shed 503 are different upstream "
                "problems with different fixes.",
            ),
        )
    return findings


def run(root: Path) -> list[str]:
    """All findings for the counts file under `root`."""
    path = root / COUNTS_FILE
    if not path.is_file():
        return [
            error(
                f"`{COUNTS_FILE}` is missing, so this trend watch read nothing. Restore it, or the "
                "job reports no failures because it found no data — which looks identical to "
                "every provider being healthy.",
            ),
        ]
    try:
        runs = parse(path.read_text(encoding="utf-8"))
    except Malformed as malformed:
        return [
            error(
                f"`{COUNTS_FILE}` could not be read: {malformed}. Fix the file — an unparsed "
                "counts file reports zero failures, which reads as a clean bill of health.",
            ),
        ]
    return findings_for(runs)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail on a provider shedding over the threshold on two consecutive probe runs.",
    )
    parser.add_argument("--root", default=".", type=Path, help="repository root (default: .)")
    args = parser.parse_args(argv)

    findings = run(args.root)
    for finding in findings:
        print(finding)
    return 1 if any(finding.startswith("::error") for finding in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
