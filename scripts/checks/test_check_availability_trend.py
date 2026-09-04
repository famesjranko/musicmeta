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

from check_availability_trend import COUNTS_FILE, HEADER, run  # noqa: E402

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
    def findings_for(self, lines: list[str], *, header: str = HEADER) -> list[str]:
        """Findings for a counts file holding these data rows under a fresh temp root."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / COUNTS_FILE
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join([header, *lines]) + "\n", encoding="utf-8")
            return run(root)

    def errors_for(self, lines: list[str], *, header: str = HEADER) -> list[str]:
        """Only the findings that fail the run — a `::notice` is read, not acted on."""
        return [f for f in self.findings_for(lines, header=header) if f.startswith("::error")]

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


if __name__ == "__main__":
    unittest.main()
