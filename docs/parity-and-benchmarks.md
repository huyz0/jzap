# PIT parity and benchmark harness

PIT is jzap's correctness oracle and its performance baseline. This harness is not a
release-time validation step — it is built early (M4/M7) and every subsequent milestone
is gated on it. Nothing about the engine is believed until this harness says so.

Referenced by [delivery-plan.md](delivery-plan.md).

## 1. Why PIT is the oracle

PIT is mature, widely used, and produces a machine-readable per-mutant verdict. If jzap
and PIT disagree about whether a given mutant is killed, one of them is wrong, and the
burden of proof is on jzap. The harness exists to make every disagreement visible and
force it to be explained.

The comparison is only meaningful if mutant sets can be aligned, which is why the mutator
catalog ships with an explicit PIT mapping (M3) before the harness is built.

## 2. Corpus

Three tiers, all pinned to exact commits and vendored as a lockfile, never floating refs.

**Tier A — microfixtures (hand-written, ~40 projects).** One tiny project per semantic
hazard, each with a hand-written expected outcome independent of both tools:

- one per mutator: conditionals boundary, negate conditionals, math, increments,
  invert negatives, return values, void method calls, constructor calls, remove
  conditionals, empty returns, false/true returns, primitive returns
- hazards: static initialisers, `finally` blocks, try-with-resources, exceptions thrown
  from mutated code, infinite loops, `System.exit`, threads spawned by tests, reflection,
  lambdas and method refs, `switch`/tableswitch, string concat (indy), records, sealed
  types, enums, generics bridge methods, synthetic accessors, `assert` statements,
  JPMS module, JDK 8 vs 21+ bytecode shapes
- test-side hazards: parameterised tests, `@RepeatedTest`, nested tests, dynamic tests,
  test ordering dependence, tests that mutate static state, JUnit 4 via vintage, TestNG
- Kotlin: data classes, `when` exhaustiveness, null checks (`Intrinsics`), default
  arguments, inline functions (incl. `reified`, crossinline), `suspend`/coroutines,
  sealed hierarchies, extension functions, object/companion, delegated properties,
  operator overloads, `lateinit`, value classes

Tier A is where correctness is actually pinned down, because expected outcomes are
independent of PIT. **A hand-written expectation always outranks PIT.**

**Tier B — real Java projects (~8).** Mid-size, fast test suites, permissive licences:
commons-lang3, commons-math, commons-collections, jackson-databind (module subset),
guava (module subset), joda-time, jgit (subset), spring-boot sample app.

**Tier C — real Kotlin projects (~5).** okhttp, ktor (module subset), kotlinx-serialization
(subset), exposed (subset), a Spring Boot + Kotlin sample. Tier C is where jzap must beat
PIT on *mutant quality*, not just speed.

## 3. Mutant identity and normalisation

Comparison requires a tool-independent mutant key:

    (binary class name, method name + descriptor, source line,
     mutator equivalence class, ordinal within (line, equivalence class))

Notes and deliberate choices:

- **Source line, not bytecode index.** Bytecode offsets shift under different mutation
  strategies (schemata inserts branches), so offsets are not comparable between jzap and
  PIT, nor between jzap's naive and schemata engines.
- **Mutator equivalence class, not mutator name.** A checked-in mapping table maps each
  jzap mutator to zero or more PIT mutators and vice versa. Entries are one of
  `equivalent`, `broader`, `narrower`, or `no-equivalent`, and every `no-equivalent`
  entry carries a written justification. Mutants whose class maps to `no-equivalent` are
  excluded from verdict comparison and reported separately as inventory-only differences.
- **Ordinal** disambiguates several same-kind mutants on one line. It is assigned in
  bytecode order, which both tools can produce deterministically.
- PIT's mutant data comes from its XML report; jzap's from its own JSON. Both are parsed
  into the same normalised record type by the harness, so neither format is privileged.

## 4. Correctness comparison

Run with a mutator set restricted to the mapped intersection, identical timeout config,
identical thread count, identical JDK.

Three comparisons, in increasing strictness:

1. **Inventory.** Sets of mutant keys: `jzap-only`, `pit-only`, `shared`. Inventory
   differences are expected (different mutators, different junk filtering) but every
   *category* of difference must be named in the baseline file.
2. **Verdict.** For `shared` mutants, an agreement matrix over
   `KILLED / SURVIVED / NO_COVERAGE / TIMED_OUT / NON_VIABLE / RUN_ERROR`. Off-diagonal
   cells are the interesting output. `KILLED` vs `SURVIVED` disagreement is the most
   serious class and is a release blocker until explained.
3. **Killing test.** With PIT run under `--fullMutationMatrix`, compare the set of tests
   that kill each mutant. Disagreement here doesn't necessarily mean a wrong verdict, but
   it reliably surfaces coverage-attribution and test-selection bugs — which is exactly
   the machinery jzap is changing.

### Triage discipline

Every disagreement must be classified as exactly one of:

- **A — jzap bug.** Fix before the milestone closes.
- **B — PIT limitation.** Documented with a reference (e.g. static initialiser coverage
  attribution, inline-function mutant loss). A Tier A fixture with a hand-written
  expectation is added proving jzap is right.
- **C — intentional semantic difference.** Written justification in the baseline file.
- **D — nondeterminism in the project under test.** See flakiness control below; the
  mutant is quarantined and reported as a test-quality signal, not a parity failure.

Unclassified disagreements block the milestone. The baseline file
(`parity-baseline.yaml`) records every accepted B/C/D with its justification; CI fails on
any disagreement not present in the baseline, and also fails when a baselined
disagreement *disappears* without the baseline being updated (that means behaviour moved
and nobody noticed).

### Flakiness control

Each corpus project runs N=3 times per tool. Any mutant whose verdict varies across runs
in *either* tool is quarantined into a `flaky` bucket, excluded from the agreement matrix,
and reported. A rising flaky count is itself a regression.

### Self-differential testing

The naive engine from M6 is kept forever as a reference implementation, behind a flag.
Every optimisation milestone (M8–M12) must produce identical verdicts to the naive engine
on Tier A and Tier B, with the optimisation enabled and disabled. This catches bugs PIT
comparison cannot — e.g. schemata state leakage between mutants, or a cache returning a
stale verdict — because it holds the mutant set fixed and varies only our own machinery.

Determinism check: the same model plus the same bytecode must produce a byte-identical
report, excluding a designated timing block. This is a hard requirement, not a nicety —
Gradle's build cache lies if it doesn't hold (see [architecture.md](architecture.md)).

## 5. Performance comparison

### Controls

Published numbers come from a dedicated machine, never shared CI: fixed CPU governor,
turbo disabled, no other load, same JDK build for both tools, same thread count, same
mutator set (the mapped intersection), same timeout settings, PIT's
`--fullMutationMatrix` off, both tools' reports written to a tmpfs. Build/compile time is
excluded from all figures and reported separately.

5 runs per scenario. Report **median with min–max range**, never the best run. A
single-number claim with no range is not a result.

### Scenarios

| # | Scenario | What it isolates |
|---|---|---|
| S1 | Full run, single mid-size module, cold | headline throughput |
| S2 | Full run, same module, warm daemon | daemon benefit |
| S3 | Diff run: synthetic 10-line PR, cold | PR-path latency, the product's core claim |
| S4 | Diff run: same PR, warm daemon + warm cache | best-case developer loop |
| S5 | Re-run with zero changes | cache effectiveness |
| S6 | Re-run after a 1-line change | incremental invalidation precision |
| S7 | Full run, multi-module reactor | cross-module selection and daemon amortisation |
| S8 | Kotlin module, full run | Kotlin path, and mutant-quality comparison |

### Baselines measured against

- PIT, default config
- PIT with history file (its incremental analysis)
- PIT `scmMutationCoverage` (Maven only) for S3/S4 — the free diff-based comparator
- arcmutate `+GIT` and `+arcmutate_history` **if a licence is available**; if not, this
  is recorded as *unmeasured*, never estimated

### Metrics

Per scenario, per tool: wall clock (total and by phase: coverage / generation /
execution / reporting), mutants generated, mutants executed, **test executions per
mutant**, mutants/second, peak RSS, total CPU-seconds (the number that maps to CI cost),
and JVM process count.

`test executions per mutant` is the metric to watch during M10 — it measures selection
quality directly and, unlike wall clock, is machine-independent and therefore safe to
assert in CI.

### Fairness rules

These exist because it is easy to publish a flattering and meaningless number:

- Never compare jzap's reduced mutant set (M12) against PIT's full set on a time axis.
  Reduction changes what is measured, so those comparisons report time **and** the
  quantified detection loss, side by side.
- Any scenario where jzap loses is published with the same prominence as one it wins.
- Configuration for both tools is checked into the harness so anyone can rerun it.

### CI regression gates

Machine-independent metrics only (wall clock on shared CI is too noisy to gate on):
test executions per mutant, mutants generated, mutants executed, CPU-seconds within a
generous band. Per-scenario budgets checked in; a >10% regression fails the build. Wall
clock is tracked and charted, but only advisory.

## 6. Deliverables

    tools/parity/
      corpus.lock                 pinned corpus, exact commits
      mutator-mapping.yaml        jzap <-> PIT mutator equivalence classes
      parity-baseline.yaml        accepted B/C/D disagreements + justifications
      run.sh                      run both tools over a corpus tier
      normalise/                  PIT XML and jzap JSON -> normalised records
      compare/                    inventory diff, agreement matrix, triage report
    tools/bench/
      scenarios.yaml              S1-S8 definitions
      budgets.yaml                CI regression budgets
      report/                     markdown + chart output
