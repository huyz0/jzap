# Measured results

Every number here came from `./gradlew :tools:bench:bench`, which runs both tools over the same
prebuilt classes with the same ten mutators and the same JDK. The raw output is checked in
verbatim as [bench-report.txt](bench-report.txt); this page is that report with the reasoning
around it.

!!! warning "Read the caveats before quoting any of this"
    These were taken on a developer machine, not an isolated bench host. Treat the **ratios** as
    indicative and the **absolute numbers** as machine-specific. Compare medians within one
    report, never across reports — three times in this project a surprising benchmark number has
    turned out to be machine contention rather than code. An earlier run taken while a build was
    running alongside it reported reduction techniques at 0.98x and 0.72x, numbers that invited a
    conclusion about reduction no longer paying and that were entirely contention.

The fixture is `fixtures/bench-java`: 40 classes, 200 tests, 1080 mutants, 960 of them covered.
Compilation is excluded from every timing; both tools analyse the same compiled classes.

## Against PIT

| Scenario | jzap | PIT |
|---|---|---|
| Full run, one thread | **2.91s** | 28.09s |
| Full run, 20 threads | 2.55s | not comparable — PIT pinned to one thread |
| Diff run, one changed line, 6 of 1080 mutants in scope | 1.44s | not comparable |
| Re-run with no changes, cache warm | 0.42s | no equivalent |
| Re-run after one class recompiled | 1.58s | no equivalent |
| Re-run with no changes, warm daemon | 0.17s | no equivalent |

At one thread that is **9.64x faster than PIT**, and jzap analysed **40 more mutants** than PIT
did — 1080 against 1040, 3.8% more work — so the ratio is conservative rather than flattered.
At twenty threads it is 11.04x, against a PIT pinned to one.

### Why three rows say "not comparable"

Stating a ratio there would be dishonest, so they are left blank:

- **The diff run.** PIT's free scoping works at changed-*file* granularity and needs a git
  repository, so it would be doing a different amount of work. Line-level scoping in the PIT
  ecosystem is arcmutate's, which is commercial and unmeasured here.
- **The cache rows.** PIT has a history file, but it does not cache the coverage map, so the
  comparison would not be like for like.
- **Multi-threaded.** PIT runs multi-process by default and was pinned to one thread for a fair
  single-threaded comparison; leaving it pinned while scaling jzap up would be comparing against
  a handicap.

## Where the speed comes from

### Mutant schemata: 6.41x

The default engine compiles every mutant of a class in at once and selects one with a field write,
instead of redefining the class once per mutant. Redefinition makes the JVM re-verify the class
and discard its compiled code, every single time.

| Engine | Full run | Execution phase |
|---|---|---|
| `naive` — the reference implementation | 18.65s | 17.48s |
| `schemata` — the default | **2.91s** | **1.69s** |
| | 6.41x | 10.37x |

Verdicts are asserted identical between the two on every fixture — 835 killed, 125 survived, 120
uncovered. The reference engine is kept permanently, and is selectable with `--engine=naive`,
precisely so that comparison can keep being made.

The encoding is branch-free: each mutable operation becomes a call to a static dispatch method, so
no stack map frames are added and no class has to be loaded in order to transform another.

### Profiling: three quarters of the rest

Almost all of the remaining speed came from measurement rather than from features. The execution
phase went from 6344 ms to 1690 ms:

| | before | after |
|---|---|---|
| execution phase | 6344 ms | **1690 ms** |
| — analysis JVM startup | 2787 ms (10 JVMs) | 66 ms (1 JVM) |
| — running tests | 4457 ms | 1445 ms |
| — activating mutants | 386 ms | 0 ms |

Three causes, none of them a missing feature:

1. **Every analysis JVM discovered the whole test suite and ignored it.** 230 ms per JVM for a
   result the execution phase never used, because it selects tests by the unique ids the coverage
   phase already found.
2. **Recycling the analysis JVM every 100 mutants threw away JIT warmup.** Running a test cost
   4.6 ms in a fresh JVM against 0.97 ms in a warm one — for a test whose actual work is 0.022 ms.
   The hedge was costing 2.9x on the execution phase. What makes raising the limit to 1000
   defensible rather than optimistic is the soundness gate: every fixture is analysed both with
   one JVM per mutant and with the default, and every verdict must match, including a fixture
   written to leak static state on purpose.
3. **Three protocol round trips per mutant where one would do.** At 1.5 ms per mutant, the two
   extra trips were a fifth of the execution phase.

What it added up to, same fixture and one thread:

| | before profiling | after |
|---|---|---|
| jzap, full run | 8.89s | **2.91s** |
| PIT, full run | 29.58s | 28.09s |
| ratio | 3.33x | **9.64x** |

[Profiling notes](profiling.md) has the full account, including — at greater length — the leads
that measurement **closed**. That half is the more useful one.

## A second machine, from CI

The same harness runs on a GitHub Actions runner — `./gradlew :tools:bench:bench` in
`.github/workflows/bench.yml`, weekly and on demand. Absolute times there are not comparable with
the numbers above and are not meant to be; both tools run in the same job on the same host, so the
**ratio** is what carries across.

Measured on a 4-core, 15 GB Azure runner, Temurin 17.0.20.1, median of 3:

| Scenario | CI runner (4 cores) | Developer machine (20 cores) |
|---|---|---|
| jzap, full run, one thread | 5.54s | 2.91s |
| PIT, full run, one thread | 47.23s | 28.09s |
| **ratio** | **8.52x** | **9.64x** |
| Schemata against the reference engine | 3.42x | 6.41x |
| Diff run, one changed line | 2.42s (2.3x its own full run) | 1.44s (2.0x) |
| Re-run, cache warm | 0.81s (14.7% of a full run) | 0.42s (14.3%) |

Two things are worth more than the timings.

**The verdicts are byte-identical across machines**: 835 killed, 125 survived, 120 uncovered on
both, and the same 1080-against-1040 mutant counts against PIT. Determinism is a hard requirement
here, because Gradle's build cache will lie if a result depends on the machine. This is the first
evidence for it from hardware nobody developed on.

**The engine ratio halves on four cores** — 3.42x rather than 6.41x, and 4.61x rather than 10.37x
on the execution phase alone. Schemata's saving is JVM work that the reference engine spends on
re-verifying and re-JITing a class per mutant, so it scales with how much CPU there is to save it
on. The 6.41x figure is a 20-core figure, and quoting it alone would overstate what a CI runner
sees.

## Thread scaling

| Threads | Time | vs 1 thread |
|---|---|---|
| 1 | 2.90s | 1.00x |
| 2 | 2.55s | 1.14x |
| 4 | 2.60s | 1.11x |
| 20 | 2.55s | 1.14x |

Flat-positive, and modest on purpose. Two things cap it: the coverage phase is serial and accounts
for 0.8s of a 2.9s run, and work is partitioned by class across workers, so on a 40-class fixture
each additional worker pays JVM startup for very little work.

Scaling was **negative** at twenty threads until the worker count was capped by estimated work —
4.06s before the cap, 2.55s after. Once a mutant costs 1.5 ms and a JVM start costs 250 ms, more
workers is simply worse. The general shape of both this and the profiling findings is the same:
**once fixed costs dominate, dividing the variable part barely helps.**

### On four cores it is still negative

The CI run measured the same curve on a 4-core runner and it goes the other way:

| Threads | CI runner (4 cores) | vs 1 thread |
|---|---|---|
| 1 | 5.66s | 1.00x |
| 2 | 6.88s | **0.82x** |
| 4 | 8.21s | **0.69x** |

Fastest at one thread, and the ranges do not overlap (5.52–5.94s against 7.99–8.43s, n=3), so this
is not noise.

### What that changed

**jzap now defaults to one analysis JVM.** It used to default to `availableProcessors()`, so on
this runner the out-of-the-box configuration was the 8.21s column — **31% slower than `-t 1`** on
the same host in the same job.

The arithmetic is asymmetric and jzap cannot predict which side it lands on. Two threads gained
1.14x on twenty cores and lost 18% on four; four threads lost 31%. So the upside of guessing is
about 14% and the downside about 45%. What decides it is whether the tests are CPU-bound or
waiting on something, and nothing in jzap measures that — `fixtures/parallel-java`, whose tests
sleep, gains from every worker it can get, while the bench fixture's 2 ms CPU-bound tests lose.
Both come to roughly 800–1100 ms of estimated work per worker, so no threshold can separate them.

A default that can silently cost half a run's time to chase 14% is a bad trade, especially when
the wins jzap is actually built on — diff scoping, the schemata engine, the cache, the daemon —
are multiples rather than percentages. **Raise it yourself when your suite is slow or I/O-bound**,
with `--threads N` or `jzap { threads = N }`; that is the case where an extra JVM gains almost
linearly.

The worker cap also now trims a request to the cores available, one less than the machine has.
It previously modelled only the work, so `--threads 32` on a four-core machine started
thirty-two JVMs to timeslice four cores. That bound is a physical limit rather than a heuristic:
every worker is a JVM running a real test suite, so workers past the core count do not run
concurrently, they timeslice, and each has still paid a cold start.

## Caching

| Scenario | Time | Note |
|---|---|---|
| Re-run, no changes | 0.42s | 14.3% of a full run; 7.0x faster |
| Re-run, one class recompiled | 1.58s | 3.8x the no-change run, still 1.8x faster than a full run |
| Re-run, no changes, warm daemon | 0.17s | the tool's own JVM startup, removed |

One number moved in a direction that looks like a regression and is not. A fully cached re-run is
unchanged at 0.42s, but that is now 14.3% of a full run rather than 4.8% — because the full run
got three times faster. The cache did not get worse; what it is compared against got better.

Re-running after one recompiled class costs 3.8x a no-change run because any change to any class
invalidates the whole coverage map. That is deliberate and not a gap: a changed production class
can alter which lines its callers reach, so invalidating only that class's coverage would be
unsound.

The benchmark restores the cache to its pre-change state before each repetition, so every measured
run is genuinely the first one after the change. The recompiled method is one no test covers, so no
verdict moves: what that row measures is how much the cache *invalidates*, not how much it
recomputes.

## What a diff run is actually for

The diff run is the figure the product is about. On this fixture one changed line puts 6 mutants
of 1080 in scope, and the run takes 1.44s against its own 2.91s full run.

That 2.0x is unimpressive, and it is unimpressive for a good reason: the fixture is small enough
that a full run is already under three seconds, so the fixed costs — starting a JVM, the serial
coverage phase — dominate what is left. The ratio grows with the size of the repository, which is
exactly the case a fixture cannot demonstrate. Against a real repository where a full run takes
minutes, a six-mutant diff run still costs about what it costs here.

## Mutant reduction, and what it costs

Four reduction techniques exist and all four are off by default, because PIT does none of them and
every dropped mutant would become a difference against the correctness oracle.
`./gradlew :tools:bench:reduction` reports speed and **detection loss** together, which is the
only honest way to report them:

- `--one-per-line` halves the mutant count for a **1.4x** speedup, and stops reporting **43 of 125
  genuine gaps**.
- `--dedup` (trivial compiler equivalence) drops nothing at all on javac output.

That 1.4x was 1.9x before the schemata engine landed. A reduction technique pays in proportion to
how much of a run is per-mutant cost, and schemata made a mutant cheap — so trading a third of the
findings for a shrinking speedup keeps getting worse. This is the clearest example in the project
of an optimisation whose value depends on the rest of the system, and of why the measurement has
to be repeated rather than cited.

## Honest gaps

- **No corpus outside these fixtures.** Every number here is from code written in this repository.
  A mutation testing tool that has only ever run on code written to exercise it knows less about
  itself than it appears to. A thirty-day dogfood on a real external project is planned and not
  done.
- **Block-granularity coverage is not built**, and measurement is why: tests actually *run* per
  mutant are already 1.00–1.11 across all four fixtures, because kill-test-first ordering and
  early exit reach the floor first. It would still help a project with broad integration tests and
  many survivors, and that is the condition to check before building it.
- **Parallelising the coverage phase** was estimated at 550 ms and measured at a **190 ms ceiling
  on a 2910 ms run — under 7%** — which it would buy by changing what the baseline test run means.
  [Profiling notes](profiling.md) records the five risks that go with it.

## Reproducing any of this

```bash
./gradlew :tools:bench:bench      # the scenario table above
./gradlew :tools:bench:reduction  # speed and detection loss together
./gradlew :tools:parity:parity    # verdict agreement against PIT
gh workflow run bench.yml         # the same harness on a CI runner
```

Every report states the machine it was taken on — cores, memory, platform — because a table of
timings with no statement of what produced them is the thing this page exists to avoid. The
benchmark fails if the schemata engine and the reference engine disagree on verdicts: a speedup
measured against different work is not a speedup, and a harness that reports that contradiction
and exits 0 has stopped being a check.

Then read the `timings` object in `jzap-result.json`, which carries the execution-phase breakdown.
It is part of the report rather than a debug flag precisely so that the next person choosing an
optimisation starts from numbers.
