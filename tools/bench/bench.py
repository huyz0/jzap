#!/usr/bin/env python3
"""Time jzap against PIT on the same classes.

Both tools get the same mutator set, one thread, the same JDK and the same compiled classes.
Compilation is excluded: the fixture is built before either tool starts.

Reports the median of N runs with the observed range. A best-of number would flatter whichever
tool happened to get the quietest moment on the machine.
"""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import Counter

MUTATORS = ("CONDITIONALS_BOUNDARY,INCREMENTS,INVERT_NEGS,MATH,NEGATE_CONDITIONALS,"
            "VOID_METHOD_CALLS,EMPTY_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS")


def read_descriptor(path):
    props = {}
    for line in open(path, encoding="utf-8"):
        if "=" in line:
            key, value = line.rstrip("\n").split("=", 1)
            props[key] = value
    return props


def timed(command, cwd=None):
    start = time.monotonic()
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    elapsed = time.monotonic() - start
    return elapsed, result


def write_model(path, props, scope, report_dir, patch=None):
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
        "scope": scope,
        "reporters": ["json"],
        "threads": 1,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)
    return model


def summarise_jzap(report_dir):
    data = json.load(open(os.path.join(report_dir, "jzap-result.json"), encoding="utf-8"))
    counts = Counter(m["status"] for m in data["mutants"])
    return len(data["mutants"]), counts, data.get("timings", {})


def summarise_pit(report_dir):
    root = ET.parse(os.path.join(report_dir, "mutations.xml")).getroot()
    mutants = list(root)
    return len(mutants), Counter(m.get("status") for m in mutants)


def pick_mutant_bearing_line(jzap, model):
    """The line with the most mutants, so the diff run does real work.

    Uses `jzap list-mutants`, which is exactly what the parity harness reads, so the benchmark
    and the correctness comparison are scoped by the same inventory.
    """
    result = subprocess.run([jzap, "list-mutants", "-m", model], capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr[-2000:], file=sys.stderr)
        sys.exit("could not list mutants")

    by_line = Counter()
    for key in result.stdout.splitlines():
        if not key.strip():
            continue
        cls, _method, line, _mutator = key.split("::", 3)
        by_line[(cls, int(line))] += 1
    if not by_line:
        sys.exit("no mutants at all in the bench fixture")

    (cls, line), count = by_line.most_common(1)[0]
    package = cls.rsplit(".", 1)[0].replace(".", "/") if "." in cls else ""
    simple = cls.rsplit(".", 1)[-1].split("$")[0]
    path = f"{package}/{simple}.java" if package else f"{simple}.java"
    return {"path": path, "line": line, "count": count}


def report(name, times):
    median = statistics.median(times)
    return f"{name:<34} {median:7.2f}s   (range {min(times):.2f}-{max(times):.2f}s, n={len(times)})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--descriptor", required=True)
    ap.add_argument("--jzap", required=True)
    ap.add_argument("--pit-classpath", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--runs", type=int, default=3)
    args = ap.parse_args()

    props = read_descriptor(args.descriptor)
    os.makedirs(args.out, exist_ok=True)

    # --- S1: full run, both tools -------------------------------------------------------
    jzap_model = os.path.join(args.out, "model-all.json")
    jzap_reports = os.path.join(args.out, "jzap-all")
    write_model(jzap_model, props, {"kind": "ALL", "granularity": "line"}, jzap_reports)

    jzap_times, pit_times = [], []
    jzap_summary = pit_summary = None

    for run in range(args.runs):
        shutil.rmtree(jzap_reports, ignore_errors=True)
        elapsed, result = timed([args.jzap, "run", "-m", jzap_model, "-o", jzap_reports, "-q"])
        if result.returncode not in (0, 1):
            print(result.stdout[-4000:])
            print(result.stderr[-4000:], file=sys.stderr)
            sys.exit("jzap failed on the full run")
        jzap_times.append(elapsed)
        jzap_summary = summarise_jzap(jzap_reports)
        print(f"  jzap full run {run + 1}/{args.runs}: {elapsed:.2f}s", file=sys.stderr)

    pit_reports = os.path.join(args.out, "pit-all")
    for run in range(args.runs):
        shutil.rmtree(pit_reports, ignore_errors=True)
        os.makedirs(pit_reports, exist_ok=True)
        elapsed, result = timed([
            args.java, "-cp", args.pit_classpath + os.pathsep + props["testRuntimeClasspath"],
            "org.pitest.mutationtest.commandline.MutationCoverageReport",
            "--reportDir", pit_reports,
            "--targetClasses", "bench.*",
            "--excludedClasses", "bench.*Test",
            "--targetTests", "bench.*",
            "--sourceDirs", props["sourceRoot"],
            "--outputFormats", "XML",
            "--timestampedReports", "false",
            "--threads", "1",
            "--mutators", MUTATORS,
            "--verbose", "false",
        ])
        if result.returncode != 0:
            print(result.stdout[-4000:])
            sys.exit("PIT failed on the full run")
        pit_times.append(elapsed)
        pit_summary = summarise_pit(pit_reports)
        print(f"  PIT  full run {run + 1}/{args.runs}: {elapsed:.2f}s", file=sys.stderr)

    # --- S3: diff run, jzap only --------------------------------------------------------
    # PIT's free equivalent scopes by changed *file*, not changed line, and needs a git
    # repository to do it. Comparing the two here would compare different amounts of work, so
    # the diff figure is reported as jzap against its own full run.
    #
    # The changed line is derived from the inventory rather than guessed. A patch that happens
    # to land on a line carrying no mutants measures the "nothing in scope" fast path, which
    # would produce a spectacular and completely meaningless speedup.
    target = pick_mutant_bearing_line(args.jzap, jzap_model)
    print(f"  diff run targets {target['path']} line {target['line']} "
          f"({target['count']} mutants on that line)", file=sys.stderr)
    patch = os.path.join(args.out, "one-line.patch")
    with open(patch, "w", encoding="utf-8") as f:
        f.write(f"--- a/{target['path']}\n+++ b/{target['path']}\n"
                f"@@ -{target['line']} +{target['line']} @@\n-        old\n+        new\n")
    diff_model = os.path.join(args.out, "model-diff.json")
    diff_reports = os.path.join(args.out, "jzap-diff")
    write_model(diff_model, props,
                {"kind": "PATCH", "granularity": "line", "patchFile": patch}, diff_reports)

    diff_times = []
    diff_summary = None
    for run in range(args.runs):
        shutil.rmtree(diff_reports, ignore_errors=True)
        elapsed, result = timed([args.jzap, "run", "-m", diff_model, "-o", diff_reports, "-q"])
        if result.returncode not in (0, 1):
            print(result.stdout[-4000:])
            sys.exit("jzap failed on the diff run")
        diff_times.append(elapsed)
        diff_summary = summarise_jzap(diff_reports)
        print(f"  jzap diff run {run + 1}/{args.runs}: {elapsed:.2f}s", file=sys.stderr)

    # --- report -------------------------------------------------------------------------
    lines = []
    lines.append("=" * 78)
    lines.append("jzap vs PIT, same classes, same ten mutators, one thread, same JDK")
    lines.append("=" * 78)
    lines.append("")
    lines.append(f"Fixture: {len(props['mainClasses'].split(os.pathsep))} code path(s), "
                 f"generated bench classes")
    lines.append("Compilation is excluded; both tools analyse the same prebuilt classes.")
    lines.append("")
    lines.append("S1  full run")
    lines.append("  " + report("jzap (engine: naive)", jzap_times))
    lines.append("  " + report("PIT", pit_times))
    ratio = statistics.median(pit_times) / statistics.median(jzap_times)
    faster = "faster" if ratio > 1 else "slower"
    lines.append(f"  jzap is {abs(ratio if ratio > 1 else 1 / ratio):.2f}x {faster} than PIT here")

    # A speed ratio means nothing without knowing whether both tools did the same amount of
    # work. Mutant count is the closest available proxy, so it is stated next to the ratio
    # rather than left for the reader to find further down.
    delta = jzap_summary[0] - pit_summary[0]
    if delta:
        direction = "more" if delta > 0 else "fewer"
        percent = 100.0 * abs(delta) / pit_summary[0]
        lines.append(f"  Work parity: jzap analysed {abs(delta)} {direction} mutants than PIT "
                     f"({percent:.1f}%), so the ratio is "
                     + ("conservative" if delta > 0 and ratio > 1 else "flattered by that")
                     + ".")
    else:
        lines.append("  Work parity: both tools analysed the same number of mutants.")
    lines.append("")
    if diff_summary[0] == 0:
        sys.exit("the diff run found no mutants, so its timing would be meaningless. "
                 "Fix the target-line selection before reporting a number.")

    lines.append("S3  diff run, one changed line")
    lines.append(f"  target: {target['path']} line {target['line']}, "
                 f"{diff_summary[0]} mutants in scope of {jzap_summary[0]} total")
    lines.append("  " + report("jzap (patch-scoped)", diff_times))
    speedup = statistics.median(jzap_times) / statistics.median(diff_times)
    lines.append(f"  {speedup:.1f}x faster than its own full run")
    lines.append("  Not compared against PIT: PIT's free scoping works at changed-file")
    lines.append("  granularity and needs a git repository, so it would be doing a different")
    lines.append("  amount of work. Line-level scoping in the PIT ecosystem is arcmutate's,")
    lines.append("  which is commercial and unmeasured here.")
    lines.append("")
    lines.append("Mutants and verdicts")
    lines.append(f"  jzap full: {jzap_summary[0]:4d} mutants  {dict(sorted(jzap_summary[1].items()))}")
    lines.append(f"  PIT  full: {pit_summary[0]:4d} mutants  {dict(sorted(pit_summary[1].items()))}")
    lines.append(f"  jzap diff: {diff_summary[0]:4d} mutants  {dict(sorted(diff_summary[1].items()))}")
    lines.append("")
    lines.append("Caveats, stated because a timing without them is not usable:")
    lines.append("  - Run on a developer machine, not an isolated bench host. Treat the ratio as")
    lines.append("    indicative and the absolute numbers as machine-specific.")
    lines.append("  - jzap's engine here is the deliberately slow reference implementation: one")
    lines.append("    mutant at a time, one thread, no schemata, no warm daemon, no cache. Those")
    lines.append("    are M8-M11 in docs/delivery-plan.md.")
    lines.append("  - PIT runs multi-process by default; both were pinned to one thread.")
    lines.append("  - Mutant counts differ slightly by design; see tools/parity for the exact,")
    lines.append("    triaged inventory difference.")

    text = "\n".join(lines)
    print("\n" + text)
    with open(os.path.join(args.out, "bench-report.txt"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
