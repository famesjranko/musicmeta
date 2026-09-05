#!/usr/bin/env python3
"""Roll the raw enumerator output up into the probe's five metrics."""

import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).parent
PROVIDERS = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider"


def main() -> int:
    out = subprocess.run(
        [sys.executable, str(HERE / "enumerate.py"), PROVIDERS, "--json"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    (HERE / "raw-base.json").write_text(out)
    data = json.loads(out)

    header = (
        f"{'provider':16}{'pins':>5}{'A':>4}{'Afp':>5}{'Aattr':>6}"
        f"{'Ap':>4}{'Apattr':>7}{'Bunits':>7}{'Broutes':>8}{'noBuilder':>10}"
    )
    print(header)
    totals = dict.fromkeys(["pins", "a", "afp", "aattr", "ap", "apattr", "b", "bcov", "bpin", "inline"], 0)
    for name, result in data.items():
        public = [route for route in result["arm_a"] if not route["private"]]
        private = [route for route in result["arm_a"] if route["private"]]
        prime = result["arm_a_prime"]
        builders = result["arm_b"]
        covered = len({caller for unit in builders for caller in unit["callers"]})
        print(
            f"{name:16}{len(result['pins']):>5}{len(public):>4}{len(private):>5}"
            f"{sum(route['attributable'] for route in public):>6}"
            f"{len(prime):>4}{sum(route['attributable'] for route in prime):>7}"
            f"{len(builders):>7}{covered:>8}{len(result['suspend_without_builder']):>10}"
        )
        totals["pins"] += len(result["pins"])
        totals["a"] += len(public)
        totals["afp"] += len(private)
        totals["aattr"] += sum(route["attributable"] for route in public)
        totals["ap"] += len(prime)
        totals["apattr"] += sum(route["attributable"] for route in prime)
        totals["b"] += len(builders)
        totals["bcov"] += covered
        totals["bpin"] += sum(unit["attributable"] for unit in builders)
        totals["inline"] += len(result["suspend_without_builder"])
    print(totals)
    return 0


if __name__ == "__main__":
    sys.exit(main())
