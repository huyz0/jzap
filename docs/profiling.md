# Profiling notes

Where the time goes, what was done about it, and which leads turned out to be closed. Recorded
because every optimisation in jzap so far was chosen by measurement, and the ones that were *not*
worth doing are as useful to know as the ones that were.

Measured on `fixtures/bench-java`: 40 classes, 200 tests, 1080 mutants, 960 of them covered.

## Where the execution phase went

The engine reports its own breakdown in the result's `timings`, which is how each of these was
found:

| | before | after | 
|---|---|---|
| execution phase | 6344 ms | **1669 ms** |
| — analysis JVM startup | 2787 ms (10 JVMs) | 66 ms (1 JVM) |
| — running tests | 4457 ms | 1445 ms |
| — activating mutants | 386 ms | 0 ms |
| — building schemata classes | 17 ms | 17 ms |
| — installing schemata classes | 43 ms | 43 ms |

Verdicts identical throughout: 835 killed, 125 survived, 120 uncovered.

## What was wrong

### Every analysis JVM discovered the whole test suite and ignored it

`230 ms` to discover 200 tests, paid by every analysis JVM at startup — and the execution phase
never used the result. It selects tests by the unique ids the coverage phase already found.
Discovery is now a separate command from preparing the harness, and only the coverage phase asks
for it. **2787 ms → 674 ms.**

### Recycling the analysis JVM threw away JIT warmup

This was the big one, and it was hiding behind a reasonable-sounding default.

The JVM was recycled every 100 mutants to bound static-state drift. Each fresh JVM starts cold, so
the per-mutant cost of *running a test* was 4.6 ms — against 0.97 ms for the same operation in a
warmed-up JVM, and 0.022 ms for the test's actual work. The hedge was costing **2.9x on the
execution phase**.

| mutants per JVM | execution | JVMs | running tests |
|---|---|---|---|
| 100 | 5807 ms | 10 | 4482 ms |
| 500 | 2428 ms | 2 | 1821 ms |
| 1000 | 1913 ms | 1 | 1406 ms |
| 2000 | 1982 ms | 1 | 1452 ms |

The default is now 1000. What makes that defensible rather than optimistic is the soundness gate:
every fixture is analysed with one JVM per mutant and with the default, and every verdict must
match — including a fixture written to leak static state on purpose. Peak memory for the
single-JVM run was 338 MB.

### Three round trips per mutant where one would do

Activating a mutant, running its tests, and resetting the switch were three separate commands. At
1.5 ms per mutant, the two extra round trips were a fifth of the execution phase. The mutant index
now travels with the run command. **386 ms → 0 ms.**

## Leads that are closed

### Caching JUnit test plans: not possible

`0.56 ms` of the `0.97 ms` per-test launcher cost is re-discovery inside `execute()`. Discovering
each test's plan once and re-executing it would remove that — except the JUnit Platform forbids it
outright:

```
PreconditionViolationException: TestPlan must only be executed once
```

So ~1 ms per test is the Platform's floor through its public API, and the remaining per-mutant cost
is within 0.5 ms of it. Anything further would mean going below the Launcher API, which trades
compatibility with every engine for half a millisecond.

### Block-granularity coverage: no headroom

Measured separately, in [delivery-plan.md](delivery-plan.md) under M10. Tests actually run per
mutant are already 1.00–1.11 across all fixtures, because kill-test-first ordering and early exit
get there first.

## Leads still open

### Parallelising the coverage phase

Coverage is now the largest remaining serial block: `810 ms` against an execution phase of
`1669 ms`. It runs each test individually because per-test attribution requires it, and it runs
them all in one JVM. Splitting the tests across several JVMs would parallelise it — worth perhaps
`550 ms`, a fifth of a full run.

**Not done, because it changes what the baseline run means.** Tests currently share a JVM during
the coverage phase, exactly as they do under `gradle test`. Splitting them across processes gives
*more* isolation than the project's own build does, which can change which tests pass — and a
baseline that disagrees with the project's own test run is worse than a slower one. Worth doing
behind a flag, with the semantic change stated.

### Reducing the launcher's mandatory work

Each covered mutant costs one `launcher.execute` call, and that call re-discovers. Batching already
amortises it when a mutant has several covering tests — but on these fixtures each mutant has
exactly one, so there is nothing to batch. A project with broad tests would benefit from the
batching that already exists.

## How to reproduce

```bash
./gradlew :tools:bench:bench          # the scenario table
jzap run -m model.json -o out         # then read out/jzap-result.json "timings"
```

The `timings` object carries the execution-phase breakdown above. It is part of the report rather
than a debug flag precisely so the next person choosing an optimisation can start from the numbers.
