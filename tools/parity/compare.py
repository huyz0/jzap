#!/usr/bin/env python3
"""Compare jzap and PIT over the same classes.

Normalises both tools' output into one record type keyed by

    (class, method+descriptor, source line, mutator equivalence class, ordinal)

and reports the inventory difference, the verdict agreement matrix, and any difference not
accounted for in the baseline file. Exits non-zero when an unexplained difference exists.

Source line rather than bytecode offset is deliberate: offsets are not comparable between
tools, nor between jzap engines, because inserting a mutant shifts them. Ordinal breaks ties
among mutants sharing a line and mutator, assigned in bytecode order by both sides.
"""

import argparse
import fnmatch
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict

# PIT verdict vocabulary -> jzap's.
PIT_STATUS = {
    "KILLED": "KILLED",
    "SURVIVED": "SURVIVED",
    "NO_COVERAGE": "NO_COVERAGE",
    "TIMED_OUT": "TIMED_OUT",
    "NON_VIABLE": "NON_VIABLE",
    "MEMORY_ERROR": "RUN_ERROR",
    "RUN_ERROR": "RUN_ERROR",
    "STARTED": "RUN_ERROR",
}


def load_mapping(path):
    """Minimal reader for the small, fixed shape of mutator-mapping.yaml.

    Deliberately not a YAML library: the harness must run from a bare checkout with no
    Python packages installed, and this file's shape is fixed by us.
    """
    pit_to_jzap, relations, unmapped = {}, {}, set()
    section, current = None, None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if re.match(r"^mappings:", line):
            section = "mappings"
            continue
        if re.match(r"^unmapped_pit:", line):
            section = "unmapped"
            continue
        item = re.match(r"^\s*-\s*(\w+):\s*(.*)$", line)
        if item:
            key, value = item.group(1), item.group(2).strip()
            current = {key: value}
            if section == "mappings":
                current["_jzap"] = value
            continue
        field = re.match(r"^\s+(\w+):\s*(.*)$", line)
        if field and current is not None:
            key, value = field.group(1), field.group(2).strip()
            if key == "pit":
                names = [n.strip() for n in value.strip("[]").split(",") if n.strip()]
                if section == "mappings":
                    for n in names:
                        pit_to_jzap[n] = current["_jzap"]
                else:
                    unmapped.update(names)
                if section == "unmapped" and value and not value.startswith("["):
                    unmapped.add(value)
            elif key == "relation" and section == "mappings":
                relations[current["_jzap"]] = value
    return pit_to_jzap, relations, unmapped


def load_baseline(path):
    """Accepted differences, keyed by mutant-key glob.

    Key-based rather than count-based on purpose: a baseline that only counts accepted
    differences would silently absorb a *different* difference of the same size, which is
    exactly the regression this file exists to catch.
    """
    accepted = {"inventory": {}, "verdicts": {}}
    section, current_key = None, None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if re.match(r"^inventory:", line):
            section = "inventory"
            continue
        if re.match(r"^verdicts:", line):
            section = "verdicts"
            continue
        item = re.match(r"^\s*-\s*key:\s*(\S.*)$", line)
        if item and section:
            current_key = item.group(1).strip().strip('"')
            accepted[section][current_key] = {"classification": "?", "note": ""}
            continue
        field = re.match(r"^\s+(classification|note):\s*(.*)$", line)
        if field and section and current_key:
            accepted[section][current_key][field.group(1)] = field.group(2).strip()
    return accepted


def matching_baseline(baseline_section, key_string):
    """The baseline entry covering this difference, if any. Globs are allowed in keys."""
    for pattern, entry in baseline_section.items():
        if fnmatch.fnmatch(key_string, pattern):
            return pattern, entry
    return None, None


def read_pit(path, pit_to_jzap, unmapped):
    """PIT's XML report -> normalised records, plus what had to be dropped."""
    records, skipped = {}, Counter()
    raw = []
    for mutation in ET.parse(path).getroot():
        pit_mutator = mutation.findtext("mutator")
        if pit_mutator in unmapped:
            skipped["no-equivalent mutator"] += 1
            continue
        jzap_mutator = pit_to_jzap.get(pit_mutator)
        if jzap_mutator is None:
            skipped[f"unmapped mutator {pit_mutator}"] += 1
            continue
        indexes = [int(i.text) for i in mutation.find("indexes")] if mutation.find("indexes") is not None else [0]
        raw.append({
            "class": mutation.findtext("mutatedClass"),
            "method": mutation.findtext("mutatedMethod"),
            "desc": mutation.findtext("methodDescription"),
            "line": int(mutation.findtext("lineNumber")),
            "mutator": jzap_mutator,
            "index": min(indexes) if indexes else 0,
            "status": PIT_STATUS.get(mutation.get("status"), "RUN_ERROR"),
            "killing": mutation.findtext("killingTest") or None,
            "description": mutation.findtext("description"),
        })

    # Ordinal = position among same (class, method, desc, line, mutator), in bytecode order.
    buckets = defaultdict(list)
    for r in raw:
        buckets[(r["class"], r["method"], r["desc"], r["line"], r["mutator"])].append(r)
    for bucket, items in buckets.items():
        for ordinal, r in enumerate(sorted(items, key=lambda x: x["index"])):
            records[bucket + (ordinal,)] = r
    return records, skipped


def read_jzap(path):
    records = {}
    data = json.load(open(path, encoding="utf-8"))
    for m in data["mutants"]:
        method = m["method"]
        split = method.index("(")
        key = (m["class"], method[:split], method[split:], m["line"], m["mutator"], m["ordinal"])
        records[key] = {
            "status": m["status"],
            "killing": m.get("killingTest"),
            "description": m.get("description"),
        }
    return records, data


def render_key(key):
    cls, method, desc, line, mutator, ordinal = key
    return f"{cls}::{method}{desc}::{line}::{mutator}#{ordinal}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pit", required=True)
    ap.add_argument("--jzap", required=True)
    ap.add_argument("--mapping", required=True)
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--fixture", default="(unnamed)")
    args = ap.parse_args()

    pit_to_jzap, relations, unmapped = load_mapping(args.mapping)
    baseline = load_baseline(args.baseline)
    pit, skipped = read_pit(args.pit, pit_to_jzap, unmapped)
    jzap, jzap_data = read_jzap(args.jzap)

    shared = sorted(set(pit) & set(jzap), key=render_key)
    pit_only = sorted(set(pit) - set(jzap), key=render_key)
    jzap_only = sorted(set(jzap) - set(pit), key=render_key)

    print("=" * 78)
    print(f"jzap <-> PIT parity report: {args.fixture}")
    print("=" * 78)
    print(f"PIT mutants (mapped):  {len(pit)}")
    print(f"jzap mutants:          {len(jzap)}")
    print(f"shared:                {len(shared)}")
    print(f"PIT only:              {len(pit_only)}")
    print(f"jzap only:             {len(jzap_only)}")
    for reason, count in skipped.items():
        print(f"excluded from PIT:     {count} ({reason})")

    if pit_only:
        print("\nINVENTORY: in PIT but not jzap")
        for key in pit_only:
            print(f"  {render_key(key)}  [{pit[key]['status']}]  {pit[key]['description']}")
    if jzap_only:
        print("\nINVENTORY: in jzap but not PIT")
        for key in jzap_only:
            print(f"  {render_key(key)}  [{jzap[key]['status']}]  {jzap[key]['description']}")

    matrix = Counter()
    disagreements = []
    for key in shared:
        p, j = pit[key]["status"], jzap[key]["status"]
        matrix[(p, j)] += 1
        if p != j:
            disagreements.append((key, p, j))

    print("\nVERDICT AGREEMENT")
    statuses = sorted({s for pair in matrix for s in pair})
    width = max((len(s) for s in statuses), default=10) + 2
    print(" " * width + "".join(s.rjust(width) for s in statuses) + "   (rows PIT, cols jzap)")
    for p in statuses:
        row = "".join(str(matrix.get((p, j), 0)).rjust(width) for j in statuses)
        print(p.rjust(width) + row)

    agreed = sum(c for (p, j), c in matrix.items() if p == j)
    rate = 100.0 * agreed / len(shared) if shared else 100.0
    print(f"\nagreement on shared mutants: {agreed}/{len(shared)} ({rate:.1f}%)")

    if disagreements:
        print("\nVERDICT DISAGREEMENTS -- each must be triaged A/B/C/D before this can pass")
        for key, p, j in disagreements:
            print(f"  {render_key(key)}\n    PIT={p}  jzap={j}")

    killing_mismatch = [
        key for key in shared
        if pit[key]["status"] == "KILLED" == jzap[key]["status"]
        and pit[key]["killing"] and jzap[key]["killing"]
        and pit[key]["killing"].split("(")[0].split(".")[-1]
        not in (jzap[key]["killing"] or "")
    ]
    if killing_mismatch:
        print(f"\nNOTE: {len(killing_mismatch)} mutant(s) killed by a different test in each tool.")
        print("      Not a wrong verdict, but it is how coverage-attribution bugs show up.")
        for key in killing_mismatch[:10]:
            print(f"  {render_key(key)}\n    PIT={pit[key]['killing']}\n    jzap={jzap[key]['killing']}")

    # Triage: every difference must be covered by a baseline entry, and every baseline entry
    # must still describe something that is actually happening.
    unexplained, matched_patterns = [], set()

    for key in pit_only + jzap_only:
        pattern, entry = matching_baseline(baseline["inventory"], render_key(key))
        if entry is None:
            unexplained.append(("inventory", render_key(key), None))
        else:
            matched_patterns.add(("inventory", pattern))

    for key, p, j in disagreements:
        pattern, entry = matching_baseline(baseline["verdicts"], render_key(key))
        if entry is None:
            unexplained.append(("verdict", render_key(key), f"PIT={p} jzap={j}"))
        else:
            matched_patterns.add(("verdicts", pattern))

    stale = [(section, pattern)
             for section in ("inventory", "verdicts")
             for pattern in baseline[section]
             if (section, pattern) not in matched_patterns]

    if baseline["inventory"] or baseline["verdicts"]:
        print("\nACCEPTED DIFFERENCES (from the baseline)")
        for section in ("inventory", "verdicts"):
            for pattern, entry in baseline[section].items():
                state = "stale" if (section, pattern) in [(s, p) for s, p in stale] else "matched"
                print(f"  [{section}] {pattern}")
                print(f"    classification {entry['classification']}, {state}")

    print("\n" + "=" * 78)
    failed = False
    if unexplained:
        failed = True
        print("FAIL: differences not accounted for in the baseline:")
        for kind, key, detail in unexplained:
            print(f"  {kind}: {key}" + (f"  ({detail})" if detail else ""))
        print("Triage each one as a jzap bug, a PIT limitation, an intentional difference or")
        print("test nondeterminism, then record it in the baseline with its justification.")
    if stale:
        failed = True
        print("FAIL: baselined differences that no longer occur:")
        for section, pattern in stale:
            print(f"  [{section}] {pattern}")
        print("Behaviour moved and the baseline was not updated. Re-triage and update it.")
    if failed:
        return 1
    print("PASS: jzap and PIT agree, modulo the differences justified in the baseline.")
    print(f"  {agreed}/{len(shared)} shared mutants agree; "
          f"{len(baseline['inventory']) + len(baseline['verdicts'])} accepted difference(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
