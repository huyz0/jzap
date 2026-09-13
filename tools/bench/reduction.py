"""Measure what each mutant-reduction technique removes, and what it costs.

A reduction technique is only worth having if what it drops is worth less than the time it
saves, and that is not knowable without measuring both. This reports them side by side, per
docs/parity-and-benchmarks.md: never the speed gain alone.

The cost that matters is detection loss: mutants that SURVIVED the full run and are no longer
generated at all. Each one is a real gap in the test suite that the reduced run will not report.
"""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import time

CONFIGURATIONS = [
    ("baseline", []),
    ("dedup", ["--dedup"]),
    ("arid", ["--arid"]),
    ("one-per-line", ["--one-per-line"]),
    ("all three", ["--dedup", "--arid", "--one-per-line"]),
    ("extreme mutation", ["--mutators", "EXTREME"]),
]


def read_descriptor(path):
    props = {}
    for line in open(path, encoding="utf-8"):
        if "=" in line:
            key, value = line.rstrip("\n").split("=", 1)
            props[key] = value
    return props


def write_model(path, props, reports):
    sep = os.pathsep
    model = {
        "schemaVersion": 1,
        "modules": [{
            "id": ":fixtures:bench-java",
            "mutableCodePaths": props["mainClasses"].split(sep),
            "sourceRoots": props["sourceRoot"].split(sep),
            "testClassPaths": props["testClasses"].split(sep),
            "testClasspath": props["testRuntimeClasspath"].split(sep),
        }],
        "scope": {"kind": "ALL", "granularity": "line"},
        "reporters": ["json"],
        "threads": 1,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)


def run(jzap, model, reports, extra, runs):
    times = []
    result = None
    for _ in range(runs):
        shutil.rmtree(reports, ignore_errors=True)
        start = time.monotonic()
        completed = subprocess.run([jzap, "run", "-m", model, "-o", reports, "-q", *extra],
                                   capture_output=True, text=True)
        times.append(time.monotonic() - start)
        if completed.returncode not in (0, 1):
            print(completed.stdout[-3000:])
            sys.exit("jzap failed with " + " ".join(extra))
        result = json.load(open(os.path.join(reports, "jzap-result.json"), encoding="utf-8"))
    return statistics.median(times), result


def survivors(result):
    return {m["key"] for m in result["mutants"] if m["status"] == "SURVIVED"}


def keys(result):
    return {m["key"] for m in result["mutants"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--descriptor", required=True)
    ap.add_argument("--jzap", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--runs", type=int, default=2)
    args = ap.parse_args()

    props = read_descriptor(args.descriptor)
    os.makedirs(args.out, exist_ok=True)
    model = os.path.join(args.out, "model.json")
    reports = os.path.join(args.out, "reports")
    write_model(model, props, reports)

    lines = ["=" * 78,
             "Mutant reduction: what each technique removes, and what it costs",
             "=" * 78,
             ""]

    baseline_time, baseline = None, None
    rows = []
    for name, flags in CONFIGURATIONS:
        elapsed, result = run(args.jzap, model, reports, flags, args.runs)
        print(f"  {name}: {elapsed:.2f}s, {len(result['mutants'])} mutants", file=sys.stderr)
        if name == "baseline":
            baseline_time, baseline = elapsed, result
            rows.append((name, elapsed, result, 0, 0.0))
            continue
        lost = survivors(baseline) - keys(result)
        rows.append((name, elapsed, result, len(lost),
                     100.0 * len(lost) / max(1, len(survivors(baseline)))))

    header = (f"{'technique':<18}{'mutants':>9}{'dropped':>9}{'time':>8}"
              f"{'speedup':>9}{'survivors':>11}{'lost':>7}{'loss':>8}")
    lines.append(header)
    lines.append("-" * len(header))
    for name, elapsed, result, lost, loss_percent in rows:
        dropped = len(baseline["mutants"]) - len(result["mutants"])
        lines.append(
            f"{name:<18}{len(result['mutants']):>9}{dropped:>9}{elapsed:>7.2f}s"
            f"{baseline_time / elapsed:>8.2f}x{len(survivors(result)):>11}{lost:>7}"
            f"{loss_percent:>7.1f}%")

    lines.append("")
    lines.append("survivors: gaps in the test suite the run reports.")
    lines.append("lost:      mutants that survived the full run and are no longer generated at")
    lines.append("           all. Each one is a real gap the reduced run will not report, which")
    lines.append("           is the price of the speedup in the column beside it.")
    lines.append("")
    lines.append("A reduction technique pays in proportion to how much of the run is per-mutant")
    lines.append("cost. Under the schemata engine a mutant costs a field write rather than a class")
    lines.append("redefinition, so halving the mutant count is worth less than it used to be:")
    lines.append("one-per-line was 1.9x against the reference engine and is about 1.4x now, for the")
    lines.append("same 34% of findings given up. The cheaper the engine gets, the worse that trade")
    lines.append("looks.")
    lines.append("")
    lines.append("Run this on a quiet machine. An earlier run of this harness, taken while a build")
    lines.append("was running alongside it, reported one-per-line at 0.98x and extreme mutation at")
    lines.append("0.72x -- numbers that invited a conclusion about reduction no longer paying at all,")
    lines.append("and that were entirely contention.")
    lines.append("")
    lines.append("Extreme mutation is listed alongside these but is not the same kind of thing:")
    lines.append("it replaces the mutator set rather than filtering it, so its mutants are not a")
    lines.append("subset of the baseline's and its loss figure counts every baseline survivor.")
    lines.append("Read its mutant count and survivor count, not its loss.")
    lines.append("")
    lines.append("All reduction techniques are off by default. PIT does not do any of them, so")
    lines.append("enabling one turns every dropped mutant into a difference against the")
    lines.append("correctness oracle; see tools/parity.")

    text = "\n".join(lines)
    print("\n" + text)
    with open(os.path.join(args.out, "reduction-report.txt"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
