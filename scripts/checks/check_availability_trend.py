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

**A threshold belongs to one series, and so does a file.** Availability measured from somewhere
else is that network's egress as much as the upstream's, so a number derived from one vantage
point judges only that vantage point's rows. `--counts-file` names the series and
`--threshold-failures`/`--threshold-total` the rule to judge it by; a series whose own baseline
has not been derived yet runs `--collect-only`, which reports the same providers as notices and
says how far it is from being able to derive one. Carrying another series' number in silently is
the failure this exists to prevent — the file the numbers came from would still be right, and the
alerts would be about the wrong network.

**A run in which every provider is over the threshold is about this machine, not an upstream.** The
probe round-robins across targets precisely so the healthy ones control for the network, and a
bad afternoon that hits all of them is what that control exists to absorb. Such a run is reported
and then excluded from attribution, the same judgement the pin makes when every route is
unavailable.

**Never a merge gate.** It reads live third-party availability, so it cannot decide whether this
repo's code is correct: `./check` runs only its self-test. The laptop series is read by hand after
a probe run appends a row; the runner series is read by a scheduled job that gates nothing. What
it *can* do wrong is read nothing and look healthy, so a missing file, a header-only file, an
unknown kind, counts that do not sum to their total and runs out of append order are all failures
rather than an empty clean report — in every mode, `--collect-only` included.

    python3 check_availability_trend.py [--root PATH] [--counts-file PATH]
        [--threshold-failures N --threshold-total N | --collect-only]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NamedTuple

COUNTS_FILE = "scripts/probes/provider-availability-counts.csv"

HEADER = "run,provider,kind,count,total"


class Threshold(NamedTuple):
    """The alert rule as its two measured numbers rather than a float.

    The run count is what makes the false-alarm arithmetic in this module's header reproducible,
    and a share alone loses it. It is a parameter and not a constant because it belongs to one
    series: a number derived from one vantage point judges only that vantage point's rows.
    """

    failures: int
    total: int

    def __str__(self) -> str:
        return f"{self.failures} in {self.total}"


# Derived from this laptop, at `ROUNDS=20`, against a 4.4% baseline measured 2026-08-13. It is the
# default because it is the only threshold anyone has derived; it is not a general number.
LAPTOP_THRESHOLD = Threshold(3, 20)

# How many runs a series needs before its own baseline can be derived from it. Eight weekly runs is
# two months of a fortnightly false-alarm figure — enough for a per-provider failure share to mean
# something, and short enough that a collect-only series is not collecting forever.
READY_AT_RUNS = 8

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


def over_threshold(kinds: dict[str, int], total: int, threshold: Threshold) -> bool:
    """Whether this provider's failures in one run reach the threshold share for its series."""
    failures = sum(count for kind, count in kinds.items() if kind != "ok")
    return failures * threshold.total >= threshold.failures * total


def failure_summary(kinds: dict[str, int], total: int) -> str:
    """`6/20 transport timeout x6` — the share and the kinds behind it, worst kind first."""
    failures = {kind: count for kind, count in kinds.items() if kind != "ok"}
    by_size = sorted(failures.items(), key=lambda item: (-item[1], item[0]))
    return f"{sum(failures.values())}/{total} " + ", ".join(f"{kind} x{count}" for kind, count in by_size)


def findings_for(
    runs: list[Run],
    *,
    counts_file: str = COUNTS_FILE,
    threshold: Threshold = LAPTOP_THRESHOLD,
    collect_only: bool = False,
    ready_at: int = READY_AT_RUNS,
) -> list[str]:
    """Findings for a parsed history: the flags, and the notices that explain a quiet run.

    Under `collect_only` the same comparison runs and reports the same providers, but as notices:
    a series whose baseline has not been derived has no threshold of its own, and judging it by
    another series' number is the mistake the mode exists to prevent.
    """
    findings = list(collection_progress(runs, counts_file, ready_at)) if collect_only else []

    if len(runs) < 2:
        return [
            *findings,
            notice(
                f"only one run in `{counts_file}`, so no provider was compared against a previous "
                "one. The two-consecutive-runs rule needs a second run before it can flag anything.",
            ),
        ]

    (_, previous_counts, previous_totals) = runs[-2]
    (latest_id, latest_counts, latest_totals) = runs[-1]

    breached = [p for p, kinds in latest_counts.items() if over_threshold(kinds, latest_totals[p], threshold)]
    if breached and len(breached) == len(latest_counts):
        return [
            *findings,
            notice(
                f"run `{latest_id}`: every measured provider ({len(breached)}) was over the "
                "threshold, which is this machine's network rather than an upstream — the probe "
                "round-robins so the healthy targets control for exactly this. Not attributed to "
                "any provider.",
            ),
        ]

    report = notice if collect_only else error
    unjudged = (
        " Reported, not judged: this series has no threshold derived from its own rows, and the "
        "one applied here was borrowed to find the candidate."
        if collect_only
        else ""
    )
    for provider in sorted(breached):
        if provider not in previous_counts:
            continue
        if not over_threshold(previous_counts[provider], previous_totals[provider], threshold):
            continue
        findings.append(
            report(
                f"`{provider}` was over the availability threshold ({threshold}) on two "
                f"consecutive runs of `{counts_file}`: "
                f"{failure_summary(previous_counts[provider], previous_totals[provider])}, then "
                f"{failure_summary(latest_counts[provider], latest_totals[provider])} in "
                f"`{latest_id}`. Read the kinds — a timeout and a shed 503 are different upstream "
                f"problems with different fixes.{unjudged}",
            ),
        )
    return findings


def collection_progress(runs: list[Run], counts_file: str, ready_at: int) -> list[str]:
    """How far a collect-only series is from carrying a threshold of its own.

    A mode that never asks to be retired becomes the permanent state, and a series that collects
    forever is the same silence as no series at all. So every run says where it is, and the run
    that reaches `ready_at` names the flag to drop and the numbers to replace it with.
    """
    if len(runs) < ready_at:
        return [
            notice(
                f"`{counts_file}` holds {len(runs)} of the {ready_at} runs a baseline needs. Collecting, not judging.",
            ),
        ]
    return [
        notice(
            f"`{counts_file}` holds {len(runs)} runs, so this series' own baseline can now be "
            "derived: take each provider's failure share across the runs, and pick the smallest "
            "`failures in total` whose per-run false-alarm rate against that share is acceptable "
            "twice running. Then drop `--collect-only` and pass the result as "
            "`--threshold-failures` and `--threshold-total`.",
        ),
    ]


def run(
    root: Path,
    *,
    counts_file: str = COUNTS_FILE,
    threshold: Threshold = LAPTOP_THRESHOLD,
    collect_only: bool = False,
    ready_at: int = READY_AT_RUNS,
) -> list[str]:
    """All findings for one series' counts file under `root`.

    A file this cannot read is an error in every mode. `collect_only` withholds the judgement, not
    the integrity checks: a series that reads nothing must never look healthy while it collects.
    """
    path = root / counts_file
    if not path.is_file():
        return [
            error(
                f"`{counts_file}` is missing, so this trend watch read nothing. Restore it, or the "
                "job reports no failures because it found no data — which looks identical to "
                "every provider being healthy.",
            ),
        ]
    try:
        runs = parse(path.read_text(encoding="utf-8"))
    except Malformed as malformed:
        return [
            error(
                f"`{counts_file}` could not be read: {malformed}. Fix the file — an unparsed "
                "counts file reports zero failures, which reads as a clean bill of health.",
            ),
        ]
    return findings_for(
        runs,
        counts_file=counts_file,
        threshold=threshold,
        collect_only=collect_only,
        ready_at=ready_at,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail on a provider shedding over the threshold on two consecutive probe runs.",
    )
    parser.add_argument("--root", default=".", type=Path, help="repository root (default: .)")
    parser.add_argument(
        "--counts-file",
        default=COUNTS_FILE,
        help=f"the series to read, relative to --root (default: {COUNTS_FILE})",
    )
    parser.add_argument(
        "--threshold-failures",
        type=int,
        default=LAPTOP_THRESHOLD.failures,
        help=f"failures that breach, for this series (default: {LAPTOP_THRESHOLD.failures})",
    )
    parser.add_argument(
        "--threshold-total",
        type=int,
        default=LAPTOP_THRESHOLD.total,
        help=f"requests the breach is measured over (default: {LAPTOP_THRESHOLD.total})",
    )
    parser.add_argument(
        "--collect-only",
        action="store_true",
        help="report breaches as notices, for a series whose own baseline is not derived yet",
    )
    parser.add_argument(
        "--ready-at",
        type=int,
        default=READY_AT_RUNS,
        help=f"runs a collect-only series needs before its baseline can be derived (default: {READY_AT_RUNS})",
    )
    args = parser.parse_args(argv)

    findings = run(
        args.root,
        counts_file=args.counts_file,
        threshold=Threshold(args.threshold_failures, args.threshold_total),
        collect_only=args.collect_only,
        ready_at=args.ready_at,
    )
    for finding in findings:
        print(finding)
    return 1 if any(finding.startswith("::error") for finding in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
