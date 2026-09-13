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
| execution phase | 6344 ms | **1690 ms** |
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

## Leads measured and closed on value

### Parallelising the coverage phase: measured, and not worth it

I estimated this at ~550 ms and was wrong by 4x. Instrumenting the coverage phase gives:

| coverage phase, 810 ms total | |
|---|---|
| running the 200 tests, one at a time | 463 ms |
| discovering the suite | 240 ms |
| starting the analysis JVM | 57 ms |
| instrumenting classes with probes | 16 ms |

Only the 463 ms is parallelisable, and each extra JVM costs about 57 ms to start:

| workers | test time | startup cost | total | saved |
|---|---|---|---|---|
| 1 | 463 ms | 57 ms | 520 ms | — |
| 2 | 232 ms | 114 ms | 346 ms | 174 ms |
| 3 | 154 ms | 171 ms | 325 ms | 195 ms |
| 6 | 77 ms | 342 ms | 419 ms | 101 ms |

So the best case is around **190 ms on a 2910 ms run: under 7%** — and it buys that by changing
what the baseline measurement means. The same trap as the thread-scaling regression: once fixed
costs dominate, dividing the variable part barely helps.

**The risks, for the record, since the value would have to be much higher to be worth any of them:**

- **Test isolation changes, and with it the verdicts.** Tests currently share one JVM in the
  coverage phase, exactly as they do under `gradle test`. Split across processes, an order-dependent
  test — one relying on state an earlier test left, a shared static cache, a lazily initialised
  singleton — can pass or fail differently. That changes `failingBaselineTests`, which changes which
  tests are excluded from selection, which changes mutant verdicts. A baseline that disagrees with
  the project's own test run is worse than a slower one.
- **Resource contention inside the tests.** Fixed ports, fixed temp paths, a shared database. This
  is precisely why Gradle's `maxParallelForks` is not the default.
- **Measured durations inflate under CPU contention**, and those durations feed the wall-clock
  timeout backstop, so it gets looser. The deterministic loop guard is unaffected.
- **It must stay sequential *within* each JVM.** `CoverageRecorder` is a global array drained
  between tests and `LoopGuard` a global counter; two tests at once in one JVM would cross-attribute
  coverage and corrupt the iteration baselines. So the design is "one test at a time per JVM, several
  JVMs" — which is what makes the startup cost unavoidable.
- **More exposure to flaky tests**, from different timing and GC in more processes.

What would change this: a project where the tests are slow. At 2.3 ms per test this fixture is the
worst possible case for the idea. A suite where tests take 100 ms each would put nearly all of the
coverage phase in the parallelisable part, and the arithmetic would invert. The condition to check
before revisiting is `coverageRunTests` being a large majority of `coverage` in the report's
timings.

### Reducing the launcher's mandatory work

Each covered mutant costs one `launcher.execute` call, and that call re-discovers. Batching already
amortises it when a mutant has several covering tests — but on these fixtures each mutant has
exactly one, so there is nothing to batch. A project with broad tests would benefit from the
batching that already exists.

## What it added up to

On the same fixture, same mutators, one thread:

| | before profiling | after |
|---|---|---|
| jzap, full run | 8.89 s | **2.91 s** |
| PIT, full run | 29.58 s | 28.09 s |
| ratio | 3.33x | **9.64x** |
| execution phase, schemata vs reference engine | 2.40x | **10.37x** |

Thread scaling went from **negative** to flat-positive: 20 threads measured 4.06 s before the
worker cap and 2.55 s after, against 2.90 s at one thread. The remaining scaling is modest because
the run is now only 2.9 s, of which 0.8 s is a serial coverage phase.

One number moved in a direction that looks like a regression and is not: a fully cached re-run is
unchanged at 0.42 s, but that is now **14.3%** of a full run rather than 4.8%, because the full run
got three times faster. The cache did not get worse; what it was being compared against got better.

The coverage phase is now instrumented too — `coverageStartup`, `coverageDiscovery`,
`coverageInstrument` and `coverageRunTests` — which is what turned a plausible 550 ms estimate into
a measured 190 ms ceiling.

An earlier run of this harness reported the one-changed-class scenario at 6.23 s, worse than a full
run, which would have been a genuine bug in the cache. It was contention: reproduced by hand it was
1.2 s, and the clean rerun gives 1.58 s. Third time in this project that a surprising benchmark
number has turned out to be the machine rather than the code.

## How to reproduce

```bash
./gradlew :tools:bench:bench          # the scenario table
jzap run -m model.json -o out         # then read out/jzap-result.json "timings"
```

The `timings` object carries the execution-phase breakdown above. It is part of the report rather
than a debug flag precisely so the next person choosing an optimisation can start from the numbers.
