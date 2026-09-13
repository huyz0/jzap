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

    # --- S1a: engine comparison ----------------------------------------------------------
    # Schemata against the reference engine on identical work. The verdicts are asserted equal by
    # EngineDifferentialTest; what is measured here is only what the encoding saves.
    engines = {}
    for engine in ("naive", "schemata"):
        model = os.path.join(args.out, f"model-{engine}.json")
        reports = os.path.join(args.out, f"jzap-{engine}")
        write_model(model, props, {"kind": "ALL", "granularity": "line"}, reports)
        times = []
        summary = None
        for run in range(args.runs):
            shutil.rmtree(reports, ignore_errors=True)
            elapsed, result = timed([args.jzap, "run", "-m", model, "-o", reports,
                                     "-q", "--engine", engine])
            if result.returncode not in (0, 1):
                print(result.stdout[-4000:])
                sys.exit(f"jzap failed with engine {engine}")
            times.append(elapsed)
            summary = summarise_jzap(reports)
        engines[engine] = (times, summary)
        print(f"  engine {engine}: {statistics.median(times):.2f}s", file=sys.stderr)

    # --- S1b: thread scaling ------------------------------------------------------------
    # Reported as a curve rather than a single "parallel is faster" claim, because the shape is
    # the interesting part: where it stops scaling says whether the bottleneck is still the
    # analysis JVMs or has moved to the machine.
    cores = os.cpu_count() or 1
    thread_counts = sorted({1, 2, 4, cores} & set(range(1, cores + 1)))
    scaling = {}
    for threads in thread_counts:
        model = os.path.join(args.out, f"model-t{threads}.json")
        reports = os.path.join(args.out, f"jzap-t{threads}")
        write_model(model, props, {"kind": "ALL", "granularity": "line"}, reports)
        times = []
        for run in range(args.runs):
            shutil.rmtree(reports, ignore_errors=True)
            elapsed, result = timed([args.jzap, "run", "-m", model, "-o", reports,
                                     "-q", "-t", str(threads)])
            if result.returncode not in (0, 1):
                print(result.stdout[-4000:])
                sys.exit(f"jzap failed at {threads} threads")
            times.append(elapsed)
        scaling[threads] = times
        print(f"  jzap at {threads} thread(s): {statistics.median(times):.2f}s", file=sys.stderr)

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

    # --- S5 and S6: the incremental cache ------------------------------------------------
    cache_dir = os.path.join(args.out, "cache")
    shutil.rmtree(cache_dir, ignore_errors=True)
    cold_model = os.path.join(args.out, "model-cache.json")
    cold_reports = os.path.join(args.out, "jzap-cache")
    write_model(cold_model, props, {"kind": "ALL", "granularity": "line"}, cold_reports)

    def cached_run():
        shutil.rmtree(cold_reports, ignore_errors=True)
        elapsed, result = timed([args.jzap, "run", "-m", cold_model, "-o", cold_reports,
                                 "-q", "--cache-dir", cache_dir])
        if result.returncode not in (0, 1):
            print(result.stdout[-4000:])
            sys.exit("jzap failed on a cached run")
        return elapsed, summarise_jzap(cold_reports)

    cold_time, _ = cached_run()
    print(f"  cache cold: {cold_time:.2f}s", file=sys.stderr)

    warm_times = []
    for run in range(args.runs):
        elapsed, warm_summary = cached_run()
        warm_times.append(elapsed)
        print(f"  cache warm {run + 1}/{args.runs}: {elapsed:.2f}s", file=sys.stderr)

    # S6 recompiles one class for real rather than faking a change, because the whole question
    # is whether invalidation is precise and a simulated change cannot answer it.
    source_root = props["sourceRoot"]
    changed_source = os.path.join(source_root, "bench", "Unit0.java")
    original_source = open(changed_source, encoding="utf-8").read()
    # `untested` is covered by no test, so the edit changes the class's bytecode without
    # changing any verdict: what is being measured is invalidation scope, not a new result.
    patched = original_source.replace("return value / 2 + 1;", "return value / 2 + 2;")
    if patched == original_source:
        sys.exit("could not patch the bench fixture for the incremental scenario")
    # Each repetition must start from the pre-change cache. Without restoring it, only the
    # first run does the invalidation work and the rest are no-change runs, so the median
    # reports the wrong thing entirely -- which is what the first version of this did.
    cache_file = os.path.join(cache_dir, "jzap-cache.txt")
    pristine_cache = open(cache_file, encoding="utf-8").read()

    incremental_times, incremental_summary = [], None
    try:
        open(changed_source, "w", encoding="utf-8").write(patched)
        classes = props["mainClasses"].split(os.pathsep)[0]
        compile_result = subprocess.run(
            [args.java.replace("bin/java", "bin/javac"), "-g", "-d", classes,
             "-cp", props["testRuntimeClasspath"], changed_source],
            capture_output=True, text=True)
        if compile_result.returncode != 0:
            sys.exit("could not recompile the patched fixture: " + compile_result.stderr)
        for run in range(args.runs):
            open(cache_file, "w", encoding="utf-8").write(pristine_cache)
            elapsed, incremental_summary = cached_run()
            incremental_times.append(elapsed)
            print(f"  cache after one changed class {run + 1}/{args.runs}: {elapsed:.2f}s",
                  file=sys.stderr)
    finally:
        open(changed_source, "w", encoding="utf-8").write(original_source)
        subprocess.run(
            [args.java.replace("bin/java", "bin/javac"), "-g", "-d",
             props["mainClasses"].split(os.pathsep)[0],
             "-cp", props["testRuntimeClasspath"], changed_source],
            capture_output=True, text=True)

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
    lines.append("  " + report("jzap (default engine)", jzap_times))
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
    lines.append("S1a engine")
    for engine in ("naive", "schemata"):
        times, summary = engines[engine]
        lines.append("  " + report(f"jzap, {engine}", times)
                     + f"   {statistics.median(engines['naive'][0]) / statistics.median(times):5.2f}x"
                     + " vs naive")
    # The execution phase is the part schemata changes; the whole-run figure is diluted by the
    # serial coverage phase, so both are reported rather than only the flattering one.
    naive_execution = engines["naive"][1][2].get("execution", 0)
    schemata_execution = engines["schemata"][1][2].get("execution", 0)
    if naive_execution and schemata_execution:
        lines.append(f"  execution phase only: {naive_execution / 1000:.2f}s -> "
                     f"{schemata_execution / 1000:.2f}s, "
                     f"{naive_execution / schemata_execution:.2f}x")

    naive_counts = engines["naive"][1][1]
    schemata_counts = engines["schemata"][1][1]
    lines.append(f"  verdicts identical: {dict(sorted(naive_counts.items())) == dict(sorted(schemata_counts.items()))}"
                 f"  (naive {dict(sorted(naive_counts.items()))})")
    lines.append("  Schemata compiles every mutant of a class in at once, so selecting one is a")
    lines.append("  field write rather than a class redefinition -- which makes the JVM re-verify")
    lines.append("  the class and discard its compiled code, once per mutant.")

    lines.append("")
    lines.append(f"S1b thread scaling ({cores} cores available)")
    single = statistics.median(scaling[1])
    for threads in thread_counts:
        median = statistics.median(scaling[threads])
        lines.append("  " + report(f"jzap, {threads} thread(s)", scaling[threads])
                     + f"   {single / median:5.2f}x vs 1 thread")
    best = min(thread_counts, key=lambda t: statistics.median(scaling[t]))
    lines.append(f"  fastest at {best} thread(s), "
                 f"{statistics.median(pit_times) / statistics.median(scaling[best]):.2f}x PIT")
    lines.append("  Scaling is sublinear because the coverage phase is serial and this fixture")
    lines.append("  has only 40 classes: work is partitioned by class, so beyond a handful of")
    lines.append("  workers each one pays JVM startup for very little work.")

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
    lines.append("S5  re-run with no changes, cache warm")
    lines.append("  " + report("jzap", warm_times))
    lines.append(f"  {100.0 * statistics.median(warm_times) / statistics.median(jzap_times):.1f}%"
                 f" of a full run; {statistics.median(jzap_times) / statistics.median(warm_times):.1f}x faster")
    lines.append("")
    lines.append("S6  re-run after one class was recompiled")
    lines.append("  " + report("jzap", incremental_times))
    lines.append(f"  {statistics.median(incremental_times) / statistics.median(warm_times):.1f}x"
                 f" the no-change run, and still"
                 f" {statistics.median(jzap_times) / statistics.median(incremental_times):.1f}x"
                 f" faster than a full run")
    lines.append("  The cache is restored to its pre-change state before each repetition, so")
    lines.append("  every measured run is genuinely the first one after the change.")
    lines.append("  The change is to a method no test covers, so no verdict moves: what this")
    lines.append("  measures is how much the cache invalidates, not what it recomputes.")
    lines.append("")
    lines.append("Mutants and verdicts")
    lines.append(f"  jzap full: {jzap_summary[0]:4d} mutants  {dict(sorted(jzap_summary[1].items()))}")
    lines.append(f"  PIT  full: {pit_summary[0]:4d} mutants  {dict(sorted(pit_summary[1].items()))}")
    lines.append(f"  jzap diff: {diff_summary[0]:4d} mutants  {dict(sorted(diff_summary[1].items()))}")
    lines.append("")
    lines.append("Caveats, stated because a timing without them is not usable:")
    lines.append("  - Run on a developer machine, not an isolated bench host. Treat the ratio as")
    lines.append("    indicative and the absolute numbers as machine-specific.")
    lines.append("  - S1, S3, S5 and S6 use jzap's defaults: the schemata engine, one thread,")
    lines.append("    and no cache except where the scenario says otherwise. S1a compares the")
    lines.append("    engines directly.")
    lines.append("  - Not measured here: the resident daemon, which exists but is not one of")
    lines.append("    these scenarios, and block-granularity coverage, which is not built.")
    lines.append("  - PIT runs multi-process by default; both were pinned to one thread except")
    lines.append("    where S1b says otherwise.")
    lines.append("  - Run-to-run variance on this machine is wide when anything else is running.")
    lines.append("    Compare medians within one report, not across reports.")
    lines.append("  - Mutant counts differ slightly by design; see tools/parity for the exact,")
    lines.append("    triaged inventory difference.")

    text = "\n".join(lines)
    print("\n" + text)
    with open(os.path.join(args.out, "bench-report.txt"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
