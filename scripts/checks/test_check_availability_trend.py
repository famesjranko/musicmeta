#!/usr/bin/env python3
"""Self-check for check_availability_trend.py.

The four behaviours the trend rests on are proved here, because each of them fails silently: a
provider stepping up flags and names its kind, a network-wide bad run flags nobody, one bad run
followed by a clean one raises nothing, and a counts file this cannot parse fails loudly rather
than reporting zero failures. The ways the check can read nothing — no file, header only, an
unknown kind — are proved too, since all three would otherwise pass as a clean bill of health.
Run with: python3 test_check_availability_trend.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_availability_trend import (  # noqa: E402
    COUNTS_FILE,
    HEADER,
    LAPTOP_THRESHOLD,
    READY_AT_RUNS,
    Threshold,
    main,
    run,
)

PROVIDERS = ("deezer", "itunes", "wikidata")


def rows_for(run_id: str, failures: dict[str, tuple[str, int]], total: int = 20) -> list[str]:
    """One run's rows: every provider passes `total` requests but those named in `failures`."""
    lines = []
    for provider in PROVIDERS:
        kind, count = failures.get(provider, ("", 0))
        lines.append(f"{run_id},{provider},ok,{total - count},{total}")
        if count:
            lines.append(f"{run_id},{provider},{kind},{count},{total}")
    return lines


class AvailabilityTrendTest(unittest.TestCase):
    def findings_for(self, lines: list[str], *, header: str = HEADER, **options) -> list[str]:
        """Findings for a counts file holding these data rows under a fresh temp root."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / options.get("counts_file", COUNTS_FILE)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join([header, *lines]) + "\n", encoding="utf-8")
            return run(root, **options)

    def errors_for(self, lines: list[str], *, header: str = HEADER, **options) -> list[str]:
        """Only the findings that fail the run — a `::notice` is read, not acted on."""
        return [f for f in self.findings_for(lines, header=header, **options) if f.startswith("::error")]

    def notices_for(self, lines: list[str], **options) -> list[str]:
        """Only the findings a passing run reports for someone to read."""
        return [f for f in self.findings_for(lines, **options) if f.startswith("::notice")]

    # --- the alert, and the two rules that keep it quiet ---

    def test_a_provider_over_the_threshold_on_both_recent_runs_is_flagged_with_its_kind(self):
        # Given - lrclib's timeouts step from none to 6 in 20, twice running
        lines = (
            rows_for("2026-09-01T05:00Z", {})
            + rows_for("2026-09-08T05:00Z", {"deezer": ("transport timeout", 6)})
            + rows_for("2026-09-15T05:00Z", {"deezer": ("transport timeout", 6)})
        )

        # When - the trend is read
        errors = self.errors_for(lines)

        # Then - the provider and the kind are both named
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("deezer", errors[0])
        self.assertIn("transport timeout", errors[0])

    def test_every_provider_degrading_together_flags_no_provider(self):
        # Given - two runs in which all three providers time out well over the threshold
        degraded = dict.fromkeys(PROVIDERS, ("transport timeout", 8))
        lines = rows_for("2026-09-08T05:00Z", degraded) + rows_for("2026-09-15T05:00Z", degraded)

        # When - the trend is read
        findings = self.findings_for(lines)

        # Then - nothing fails, and the run itself is reported rather than a provider
        self.assertEqual([f for f in findings if f.startswith("::error")], [])
        self.assertTrue(any("every measured provider" in f for f in findings), findings)

    def test_one_bad_run_followed_by_a_clean_one_raises_nothing(self):
        # Given - a provider over the threshold in the older run and clean in the newest
        lines = rows_for("2026-09-08T05:00Z", {"deezer": ("http 503", 9)}) + rows_for("2026-09-15T05:00Z", {})

        # When - the trend is read
        errors = self.errors_for(lines)

        # Then - the two-consecutive-runs rule holds the alert
        self.assertEqual(errors, [])

    def test_a_provider_under_the_threshold_on_both_runs_raises_nothing(self):
        # Given - two failures in twenty, twice — the measured baseline, not a degradation
        lines = rows_for("2026-09-08T05:00Z", {"deezer": ("http 429", 2)}) + rows_for(
            "2026-09-15T05:00Z", {"deezer": ("http 429", 2)}
        )

        # When - the trend is read
        errors = self.errors_for(lines)

        # Then - the baseline does not alert
        self.assertEqual(errors, [])

    def test_a_provider_absent_from_the_older_run_is_not_flagged(self):
        # Given - a newcomer over the threshold in its only run, beside one over it in both
        lines = (
            rows_for("2026-09-08T05:00Z", {"deezer": ("http 503", 9)})
            + rows_for("2026-09-15T05:00Z", {"deezer": ("http 503", 9)})
            + ["2026-09-15T05:00Z,newcomer,ok,4,20", "2026-09-15T05:00Z,newcomer,http 503,16,20"]
        )

        # When - the trend is read
        errors = self.errors_for(lines)

        # Then - only the provider present in both runs is flagged
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("deezer", errors[0])
        self.assertNotIn("newcomer", errors[0])

    # --- a file this cannot parse must fail, never read as zero failures ---

    def test_a_missing_counts_file_fails(self):
        # Given - a root with no counts file at all
        with tempfile.TemporaryDirectory() as tmp:
            # When - the trend is read
            findings = run(Path(tmp))

        # Then - the absence is an error, not an empty clean report
        self.assertTrue(any(f.startswith("::error") for f in findings), findings)

    def test_a_counts_file_with_no_data_rows_fails(self):
        # Given - a counts file holding only its header
        errors = self.errors_for([])

        # Then - reading nothing is a failure
        self.assertTrue(errors)

    def test_a_wrong_header_fails(self):
        # Given - a counts file whose columns have been reordered
        errors = self.errors_for(rows_for("2026-09-15T05:00Z", {}), header="provider,run,kind,count,total")

        # Then - the parser refuses the file rather than guessing
        self.assertTrue(errors)

    def test_a_row_with_a_non_integer_count_fails(self):
        # Given - a count that is not a number
        errors = self.errors_for(["2026-09-15T05:00Z,deezer,ok,twenty,20"])

        # Then - the malformed row is reported
        self.assertTrue(any("twenty" in f for f in errors), errors)

    def test_a_kind_outside_the_pin_vocabulary_fails(self):
        # Given - a kind the schema pin would never write
        errors = self.errors_for(["2026-09-15T05:00Z,deezer,ok,18,20", "2026-09-15T05:00Z,deezer,flaky,2,20"])

        # Then - the vocabulary is enforced, so a renamed bucket cannot go quiet
        self.assertTrue(any("flaky" in f for f in errors), errors)

    def test_counts_that_do_not_sum_to_the_total_fail(self):
        # Given - a provider whose rows account for 19 of its 20 requests
        errors = self.errors_for(["2026-09-15T05:00Z,deezer,ok,17,20", "2026-09-15T05:00Z,deezer,http 429,2,20"])

        # Then - the dropped row is reported rather than read as one fewer failure
        self.assertTrue(any("19" in f and "20" in f for f in errors), errors)

    def test_runs_out_of_order_fail(self):
        # Given - a newer run appended above an older one
        lines = rows_for("2026-09-15T05:00Z", {}) + rows_for("2026-09-08T05:00Z", {})

        # When - the trend is read
        errors = self.errors_for(lines)

        # Then - the append order the two-consecutive-runs rule depends on is enforced
        self.assertTrue(errors)

    # --- one run is not yet a trend, and must say so ---

    def test_a_single_run_reports_that_the_rule_cannot_fire_yet(self):
        # Given - a freshly seeded counts file holding one run
        findings = self.findings_for(rows_for("2026-09-15T05:00Z", {}))

        # Then - it passes, but says out loud that no comparison was made
        self.assertEqual([f for f in findings if f.startswith("::error")], [])
        self.assertTrue(any("::notice" in f for f in findings), findings)

    # --- a second vantage point is a second series, with its own threshold ---

    def test_the_threshold_is_per_series_rather_than_a_constant(self):
        # Given - a provider shedding 5 in 20 on both runs, over the laptop threshold of 3 in 20
        lines = rows_for("2026-09-08T05:00Z", {"deezer": ("http 503", 5)}) + rows_for(
            "2026-09-15T05:00Z", {"deezer": ("http 503", 5)}
        )

        # When - the same history is read against the laptop threshold and against a looser one
        default_errors = self.errors_for(lines)
        looser_errors = self.errors_for(lines, threshold=Threshold(6, 20))

        # Then - the caller's threshold decides, so one series' number cannot judge another
        self.assertEqual(len(default_errors), 1, default_errors)
        self.assertIn("3 in 20", default_errors[0])
        self.assertEqual(looser_errors, [])

    def test_a_series_outside_the_default_path_is_read(self):
        # Given - a second vantage point's counts kept in its own file
        runner_file = "scripts/probes/provider-availability-counts-runner.csv"
        lines = rows_for("2026-09-08T05:00Z", {"deezer": ("http 503", 9)}) + rows_for(
            "2026-09-15T05:00Z", {"deezer": ("http 503", 9)}
        )

        # When - the trend is read against that path
        errors = self.errors_for(lines, counts_file=runner_file)

        # Then - the finding names the file it read, so two series cannot be confused
        self.assertEqual(len(errors), 1, errors)
        self.assertIn(runner_file, errors[0])

    # --- collect-only: a series with no derived baseline measures, and does not judge ---

    def test_collect_only_reports_a_breach_without_failing_on_it(self):
        # Given - a breach that the same data flags when the threshold is being applied
        lines = rows_for("2026-09-08T05:00Z", {"deezer": ("http 503", 9)}) + rows_for(
            "2026-09-15T05:00Z", {"deezer": ("http 503", 9)}
        )

        # When - the series is read in collect-only mode
        findings = self.findings_for(lines, collect_only=True)

        # Then - the provider is named for a reader, but no borrowed threshold fails the run
        self.assertEqual([f for f in findings if f.startswith("::error")], [])
        self.assertTrue(any("deezer" in f and f.startswith("::notice") for f in findings), findings)

    def test_collect_only_still_fails_a_file_it_cannot_parse(self):
        # Given - a counts file with a kind outside the pin's vocabulary
        lines = ["2026-09-15T05:00Z,deezer,ok,18,20", "2026-09-15T05:00Z,deezer,flaky,2,20"]

        # When - it is read in collect-only mode
        errors = self.errors_for(lines, collect_only=True)

        # Then - collecting without judging is not licence to read nothing and look healthy
        self.assertTrue(any("flaky" in f for f in errors), errors)

    def test_collect_only_is_quiet_about_a_baseline_until_the_series_is_long_enough(self):
        # Given - one run short of the count a baseline can be derived from
        lines = []
        for index in range(READY_AT_RUNS - 1):
            lines += rows_for(f"2026-09-{index + 1:02d}T05:00Z", {})

        # When - the series is read in collect-only mode
        notices = self.notices_for(lines, collect_only=True)

        # Then - it says how far along it is and does not yet ask for a baseline
        self.assertTrue(any(str(READY_AT_RUNS) in f for f in notices), notices)
        self.assertFalse(any("can now be derived" in f for f in notices), notices)

    def test_collect_only_asks_for_a_baseline_once_the_series_is_long_enough(self):
        # Given - exactly as many runs as a baseline needs
        lines = []
        for index in range(READY_AT_RUNS):
            lines += rows_for(f"2026-09-{index + 1:02d}T05:00Z", {})

        # When - the series is read in collect-only mode
        notices = self.notices_for(lines, collect_only=True)

        # Then - it names the flag to retire, so collect-only cannot quietly become permanent
        self.assertTrue(any("can now be derived" in f for f in notices), notices)
        self.assertTrue(any("--collect-only" in f for f in notices), notices)

    # --- the flags the scheduled job passes must exist on the command line ---

    def test_the_command_line_carries_the_series_and_its_mode(self):
        # Given - a runner series in collect-only mode, invoked exactly as the schedule does
        runner_file = "scripts/probes/provider-availability-counts-runner.csv"
        lines = rows_for("2026-09-08T05:00Z", {"deezer": ("http 503", 9)}) + rows_for(
            "2026-09-15T05:00Z", {"deezer": ("http 503", 9)}
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / runner_file
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join([HEADER, *lines]) + "\n", encoding="utf-8")

            # When - the check runs against it
            status = main(["--root", tmp, "--counts-file", runner_file, "--collect-only"])

        # Then - it exits green, because collect-only judges nothing
        self.assertEqual(status, 0)

    def test_the_laptop_threshold_stays_the_default(self):
        # Given - the threshold a caller gets when it names none

        # When - it is read
        default = LAPTOP_THRESHOLD

        # Then - the measured laptop numbers are it, and stay two integers rather than a share
        self.assertEqual((default.failures, default.total), (3, 20))


if __name__ == "__main__":
    unittest.main()
