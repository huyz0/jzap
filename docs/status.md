# Status

What exists today, what it has been verified against, and what is not built yet. Milestone
numbers refer to [delivery-plan.md](delivery-plan.md).

## Working

**The full pipeline, end to end.** Scan compiled classes, discover mutants, instrument for
per-test coverage, fork an analysis JVM, seed each mutant, run only the tests that cover it,
stop at the first failure, and report. Driven from the CLI against a project model.

**Ten mutators**, matching PIT's DEFAULTS set with the same ids so inventories can be compared
mutant for mutant: `CONDITIONALS_BOUNDARY`, `INCREMENTS`, `INVERT_NEGS`, `MATH`,
`NEGATE_CONDITIONALS`, `VOID_METHOD_CALLS`, `EMPTY_RETURNS`, `FALSE_RETURNS`, `TRUE_RETURNS`,
`PRIMITIVE_RETURNS`.

**Diff scoping at line granularity**, from a git range (`--from`/`--to`, including `-Local-`
for uncommitted work and `-Empty-` for the empty tree) or from a unified diff with no
repository present. Widening to whole changed classes with `--scope class`.

**Reporters**: console, jzap's own JSON, the mutation-testing-elements schema, an
`agent` reporter carrying only the findings for a coding agent to read, a
self-contained HTML page, and per-survivor pull-request annotations.

**A Gradle plugin.** `id 'io.github.huyz0.jzap'` adds `mutationTest` and `mutationTestDiff`:

```groovy
plugins { id 'java'; id 'io.github.huyz0.jzap' }

jzap {
    engineVersion = "0.1.0"      // or engineClasspath, to run a local build
    threads = 4
    threshold = 80
}
```

It computes the project model from source sets and the toolchain, forks the engine with its
version independent of the plugin's, declares its inputs and outputs so Gradle can skip it, and
is configuration-cache compatible. The conformance test asserts the model it writes drives the
engine unaided, which is also how a user reproduces a plugin problem without Gradle in the loop.

**Kotlin.** Analysed end to end, with the constructs kotlinc generates filtered out rather than
reported as junk: property accessors, data class members, null-check intrinsics, and for-each loop
scaffolding. Every rule is gated on `kotlin.Metadata`, so Java classes behave exactly as before.

**Kotlin inline functions.** Their bodies are mutated through the inlined copies at each call
site, reported against the inline function's own source line by parsing the `SourceDebugExtension`
SMAP table, and the emitted declaration correctly reports no coverage because Kotlin callers never
execute it. Coverage is keyed by (class, method, line) so a call site and the declaration cannot
share a probe.

**A Maven plugin**: `jzap:mutationCoverage`, bound to `verify`, configured with an
`<engineClasspath>` and the same options as the Gradle plugin. Built by Maven rather than by this
repository's Gradle build, and checked by `./gradlew mavenSmokeTest`, which runs a real Maven build
and asserts a hand-derived result.

**Multi-module projects in one pass**, so a test in one module kills a mutant in another. The
Gradle plugin's `mutationTestAll` covers a whole reactor in one invocation. Analysed module at a
time — which is what a per-module task does — a library module with no tests of its own reports
every mutant as uncovered and the score means nothing.

**Kotest**, at spec granularity. Kotest builds its test tree when a spec runs rather than when the
platform asks what it contains, so discovery returns containers and no leaves; jzap now takes its
runnable unit from whatever the engine exposes rather than assuming leaves exist. Fixtures cover
`StringSpec`, `FunSpec`, `DescribeSpec` and `BehaviorSpec`.

**Four mutant-reduction techniques**, all off by default and all measured with what they cost:
`--arid` (logging and report-only methods), `--one-per-line`, `--dedup` (trivial compiler
equivalence), and `--mutators EXTREME` (Descartes-style whole-body replacement). Off by default
because PIT does none of them, so each dropped mutant would become a difference against the
correctness oracle.

`./gradlew :tools:bench:reduction` reports speed and **detection loss** together. On the bench
fixture one-per-line halves the mutant count for a 1.4x speedup and stops reporting 43 of 125
genuine gaps; TCE drops nothing at all on javac output, for a reason worth reading in
[delivery-plan.md](delivery-plan.md).

That 1.4x was 1.9x before the schemata engine landed. A reduction technique pays in proportion to
how much of a run is per-mutant cost, and schemata made a mutant cheap — so the trade of a third of
the findings for a shrinking speedup keeps getting worse.

**A resident daemon.** `jzap run --daemon` hands the work to a jzap that is already warm, saving
the tool's own JVM startup — on a cached run, most of the wall clock. Measured at 0.35s to 0.17s.
It deliberately does not keep analysis JVMs alive between invocations: that would mean holding the
code under test loaded in a process outliving the build that produced it, for a saving the cache
has already largely taken.

**A soundness gate on JVM reuse.** Reusing an analysis JVM between mutants is an optimisation with
a correctness question attached, so every fixture is analysed both with one JVM per mutant and with
the default bounded reuse, and every verdict must match. One fixture is written to leak static
state on purpose so the gate would fail if bounded recycling were not enough.

**An incremental cache**, opt-in with `--cache-dir` or `jzap { cacheDir = ... }`. It caches both
verdicts and the per-test coverage map. A killed mutant's verdict is reused when its killing test
still covers it and that test's class is unchanged; an unkilled mutant's only when the covering
set is identical and every covering test's class is unchanged. Timeouts and run errors are never
stored, because a wall-clock timeout is not reproducible and reusing one would report a guess as
a result. The cache records the toolchain that wrote it and refuses to be read under a different
one, since bytecode differs between javac versions and platforms. The file is plain text in
sorted sections, so a diff of it shows what changed.

Opt-in rather than on by default: a cache whose whole question is whether reuse is sound should
not start reusing without being asked.

**Mutant schemata**, the default engine. Every mutant of a class is compiled in at once and
selected by a field write, rather than redefining the class per mutant. The encoding is branch-free
— each mutable operation becomes a call to a static dispatch method — so no stack map frames are
added and no class has to be loaded to transform another. Measured at 2.48x on the execution phase
against the reference engine, with identical verdicts asserted on every fixture.
`--engine=naive` selects the reference implementation, which is kept permanently for exactly that
comparison.

**Deterministic hang detection.** A mutant that never returns is found by counting loop
iterations, not by watching the clock: the limit is ten times what the unmutated code needed on
the same tests. A run with a one-millisecond wall-clock budget and one with thirty seconds produce
byte-identical reports. The wall-clock timeout remains as a backstop for mutants that block rather
than loop, and for code that catches `Throwable`.

**Kill-test-first ordering.** When the cache knows which test killed a mutant last time, that test
runs first. Early exit means everything tried before the killing test is wasted work.

**Parallel execution.** Mutants are partitioned by class across a pool of analysis JVMs, so a
worker keeps its classes loaded and JIT-warmed rather than re-paying startup per mutant. Verdicts
and report bytes are asserted identical at 1, 2 and 8 threads, because thread count leaking into
a result would break the Gradle build cache before it is built. A hung mutant kills only its own
worker; there is a fixture whose mutant genuinely never returns to prove the recovery path.

**Verified against PIT.** `./gradlew :tools:parity:parity` runs both tools over the same
compiled classes with the same mutator set and compares them, on a hand-written fixture and on
a generated one two orders of magnitude larger. Current result:

| Fixture | Shared mutants | Verdict agreement | Accepted differences |
|---|---|---|---|
| `fixtures/sample-java` | 11 | 11/11 (100%) | 1 |
| `fixtures/bench-java` | 1040 | 1040/1040 (100%) | 40 |

The accepted differences are one class of inventory difference, triaged and justified in
`tools/parity/parity-baseline.yaml`: jzap seeds `FALSE_RETURNS` where a boolean comes from a
comparison and PIT does not. On `sample.Discount::isFree` that mutant is killed by the existing
suite, so it is a real and distinct fault rather than noise.

### Correctness work worth naming

These were found by building the thing, and each is a trap the next implementation would fall
into too:

- **Probe placement and stack map frames.** ASM reports a label, then its line number, and
  only then the frame for that offset. Inserting a coverage probe when the line number is
  visited therefore puts instructions between a branch target and its frame. The result is
  code that silently computes the wrong answer before it fails verification. Probes are now
  deferred to just before the first real instruction of the line.
- **Static initialisers get their own JVM.** A mutant in `<clinit>` only takes effect if the
  class has not been loaded yet. PIT documents reporting these as surviving for want of this.
- **No-op mutants are suppressed.** Replacing a return value with the value already returned
  produces a mutant equivalent to the original, which can never be killed and appears as a
  permanent false alarm. PIT suppresses the same case; the exact rule was established by
  running PIT's mutators over a probe class rather than assumed.
- **Loop counters are not mutated by default.** Negating one either hangs the test or crashes
  it immediately, so the mutant dies for a reason unrelated to what the test checks. Seeding
  them on the bench fixture produced 80 extra mutants of which 79 were killed, with the slowest
  running to integer wraparound before dying. PIT filters the same case. The filter is
  switchable with `--mutate-loop-counters`, because "not worth seeding" is a judgement.
- **Mutants the tests never judged are outside the score.** A mutant the JVM refused to load
  (`NON_VIABLE`) or whose analysis broke (`RUN_ERROR`) was never actually run against a test.
  Counting it as undetected blames the suite for jzap's problem; counting it as detected, which
  is what PIT does, credits the suite for a fault it never saw. Both answers distort the figure,
  in opposite directions, so these mutants are in neither the numerator nor the denominator of
  the mutation score or of test strength. They are still counted and reported, and the console and HTML
  reports name them so the totals add up. This also makes jzap's own score agree with the
  mutation-testing-elements report it already writes, where the two are `CompileError` and
  `RuntimeError` and the score is defined over valid mutants only. A `RUN_ERROR` additionally
  exits 3 and fails the build: an honest score over whatever did work must not let a broken
  analysis pass a threshold quietly.
- **A red baseline is detected and reported.** If a test already fails before any mutant is
  applied, every mutant it covers would look killed for an unrelated reason. Those tests are
  excluded from selection and reported with their failure message.
- **The agent and the wire protocol have no dependencies.** They share a classloader with the
  code under test. A test walks the agent's constant pool and fails if it references anything
  but the JDK and itself.
- **Mutant bytecode is generated in the controller** and shipped to the analysis JVM as bytes,
  so no bytecode library ever reaches the classpath of the project being analysed.

**Measured against PIT.** `./gradlew :tools:bench:bench` times both tools over the same
prebuilt classes, same ten mutators, one thread each, median of three runs. On the generated
bench fixture (40 classes, 200 tests, ~1080 mutants) on a developer machine:

| Scenario | jzap | PIT |
|---|---|---|
| Full run, one thread | **2.9s** | 28.1s |
| Full run, 20 threads | 2.6s | not comparable (PIT pinned to one thread) |
| Diff run, one changed line, 6 mutants in scope | 1.4s | not comparable |
| Re-run with no changes, cache warm | 0.42s | no equivalent |
| Re-run after one class recompiled | 1.6s | no equivalent |
| Re-run with no changes, warm daemon | 0.17s | no equivalent |

At one thread that is **9.6x faster than PIT** while analysing 40 *more* mutants (3.8%), so the
ratio is conservative rather than flattered. 11x at twenty threads, against a PIT pinned to one.

The schemata engine accounts for 6.4x of that — 10.4x on the execution phase alone — against the
reference engine, with verdicts asserted identical.

Three quarters of that came from profiling rather than from new features, and the findings are in
[profiling.md](profiling.md): every analysis JVM was discovering a test suite it never read,
recycling the JVM every 100 mutants was throwing away JIT warmup, and each mutant took three
protocol round trips where one would do. The same document records what profiling ruled *out* —
including parallelising the coverage phase, whose ceiling measured under 7% of a run and which would
change what the baseline test run means.

Thread scaling is flat-positive *on this machine* — 1.00x, 1.14x, 1.11x, 1.14x at 1, 2, 4 and 20
threads. It was *negative* at twenty until the worker count was capped by estimated work: once a
mutant costs 1.5ms and a JVM start costs 250ms, more workers is worse. The remaining scaling is
modest because the run is now 2.9s, of which 0.8s is a serial coverage phase.

On four cores it is negative: the CI benchmark measures 1.00x, 0.82x, 0.69x at 1, 2 and 4 threads,
fastest at one. jzap used to default to one analysis JVM per processor, which made that 0.69x
column its out-of-the-box behaviour on a standard CI runner.

**The default is now one analysis JVM.** The upside of guessing measured about 14% and the downside
about 45%, and what decides which you get is whether the tests are CPU-bound or waiting -- which
nothing here measures. Raise it with `--threads` for a slow or I/O-bound suite, where an extra JVM
gains almost linearly. The worker cap additionally trims any request to one less than the core
count, which it never did before: `--threads 32` on four cores used to start thirty-two JVMs.
See [performance.md](performance.md).

**Also measured in CI.** `.github/workflows/bench.yml` runs the same harness weekly on a GitHub
runner. The absolute times are not comparable with the table above and are not meant to be; what
it establishes is that the verdicts are identical on hardware nobody developed on -- 835 killed,
125 survived, 120 uncovered on both -- that the jzap-to-PIT ratio holds at 7.9x-8.5x on four cores,
and that the schemata engine's advantage halves there, since what it saves is JVM work and there
is less CPU to save it on. The ratio is a range because two runs of the same commit disagreed on
PIT's time by 8% and on jzap's by under 1%: a single figure from a shared runner is not a
measurement.

**Run these on a quiet machine.** An earlier run taken while a build was running alongside it
reported reduction techniques at 0.98x and 0.72x — numbers that invited a conclusion about
reduction no longer paying, and that were entirely contention. Compare medians within one report,
never across reports.

Re-running after one recompiled class costs 3.8x a no-change run because any change to any class
invalidates the whole coverage map. That is deliberate: a changed production class can alter
which lines its callers reach, so invalidating only that class's coverage would be unsound. The
recompiled method is one no test covers, so no verdict moves: that scenario measures how much the
cache invalidates, not how much it recomputes.

PIT is not timed on the diff scenario because its free scoping works at changed-*file*
granularity and needs a git repository, so it would be doing a different amount of work.
Line-level scoping in the PIT ecosystem is arcmutate's, which is commercial and unmeasured
here. The same is true of the cache scenarios: PIT has a history file, but it does not cache the
coverage map, so the comparison would not be like for like.

**On the plan's kill criteria.** The full-run criterion (>=2x PIT) is met with room to spare:
9.6x at one thread and 11.0x at twenty. The diff-run criterion (>=5x PIT on a warm PR-sized run)
cannot be evaluated as written, because PIT's free tier has no line-level diff mode to compare
against and arcmutate's is unmeasured. Against jzap's own full run, a diff run on this fixture is
2.0x faster -- unimpressive, and for a reason worth stating: a full run here is already under
three seconds, so fixed costs dominate whatever the diff removes. That ratio grows with the size
of the repository, which is exactly what a fixture cannot demonstrate.

The absolute numbers are machine-specific: this was run on a developer machine, not an
isolated bench host, which is exactly the caveat docs/parity-and-benchmarks.md requires before
any figure is published.

## Not built yet

Every milestone in [delivery-plan.md](delivery-plan.md) is now either delivered or closed with the
measurement that decided against it. What remains is the work that cannot be done without
publishing artefacts or without projects other than these fixtures:

| Missing | Where it belongs |
|---|---|
| Publishing to Maven Central and the Gradle Plugin Portal — the automation exists and the signed bundle is verified locally, but nothing is published, because both destinations need accounts and keys. See [releasing.md](releasing.md) | M22 |
| A thirty-day dogfood on a real external project | M22 |
| Tier C corpora — parity runs against the fixtures here, not against real repositories | M4, M21 |
| JUnit 4 and TestNG adapters behind the `jzap-testkit` SPI | M5 |
| Kotest per-leaf selection, its isolation-mode matrix, coroutine fixtures | M16b |
| Reactor-wide single invocation for Maven; the Gradle plugin already has one | M19 |
| Analysis-JVM reuse across daemon invocations | M9 |
| Android and Kotlin Multiplatform | M18 |

Several of those are the same shape: they need a project that is not a fixture. A mutation testing
tool that has only ever been run on code written to exercise it knows less about itself than it
appears to.

## Measured and deliberately not built

Two milestones were closed by measurement rather than by code, and the numbers are in
[delivery-plan.md](delivery-plan.md):

- **Block-granularity coverage.** Its purpose is to select fewer tests per mutant. Across all
  four fixtures the tests actually *run* per mutant are already 1.0-1.11: kill-test-first ordering
  and early exit reach the floor first. It would still help a project with broad integration tests
  and many survivors, where every covering test runs because none of them kills — and that is the
  condition to check before building it.
- **The Kotlin IR frontend.** The bytecode path already produces clean Kotlin mutants with
  correct source lines. An IR frontend would improve them at the margins and couple jzap hard to
  compiler internals, which is what pins mutflow to a single Kotlin version.

## Known limitations of what does exist

- **Whether more analysis JVMs will help cannot be predicted.** The default is one, because
  guessing measured 14% upside and 45% downside. What decides it is whether the tests are
  CPU-bound or waiting, and jzap measures neither: a suite that sleeps gains almost linearly from
  every worker, while fast CPU-bound tests lose to contention and cold starts, and the two look
  identical in the duration data the scheduler has. Raising `threads` is therefore a measurement
  the user has to make on their own suite. Sampling CPU utilisation during the coverage phase
  would let jzap decide this itself and is not built.
- **The Maven plugin cannot resolve the engine itself.** `engineClasspath` is a required
  filesystem path, so a Maven user has to copy `io.github.huyz0:jzap-cli` and its transitive jars
  somewhere first, where the Gradle plugin just takes an `engineVersion` and resolves it. The
  parameter was made required when there was no published engine to fetch; there is one now, and
  making the mojo resolve it through Maven's own resolver is the first thing to fix after 0.1.0.
- **Line-granularity coverage** selects more tests than necessary. Block granularity with
  exception-correct attribution is not built; the section above has the measurement that
  decided against it for now.
- **Static state between mutants** is bounded by recycling the analysis JVM every
  `maxMutantsPerMinion` mutants, not by resetting it. Resetting statics in place is not built.
- **`TIMED_OUT` is still never cached.** Loop detection is deterministic, but the wall-clock
  backstop that catches blocking mutants is not, and the two are not distinguished at the point
  the cache is written. The verdict itself is reproducible; only its reuse is withheld.
- **A diff-scoped Gradle task can report a stale scope.** `JzapTask` declares the ref names as
  inputs but not the git state they resolve against, so committing the change that
  `HEAD..-Local-` was selecting leaves every declared input unchanged while the correct scope
  becomes empty -- and Gradle calls the task up to date. `--rerun-tasks` forces it. The task is
  marked not cacheable partly for this reason; closing it means declaring the resolved commits
  through a `ValueSource`, so that resolving them does not break the configuration cache.
- **Renames are not followed** in git diffs. A renamed file's every line looks changed, which
  would flood a pull request with mutants for code nobody touched.
- **Test classes are never mutated**, because only a module's `mutableCodePaths` are scanned.
  This is structural, not a filter that can be switched off.
- **An already-empty return value is recognised only when it is returned directly.** An
  `EMPTY_RETURNS` mutant is suppressed where the method already returns the value the mutant
  would return -- `return List.of()`, `return Optional.empty()`, `return Boolean.FALSE`,
  `return 0` from a method returning `Integer` -- because such a mutant is the original program
  and can never be killed. Assigning it to a local first is not recognised:

  ```java
  List<String> r = List.of();
  return r;                      // still mutated, and the mutant can never be killed
  ```

  jzap decides this from the instruction immediately before the return, which cannot see through
  a local. PIT's `EquivalentReturnMutationFilter` does handle it, by matching the store and the
  load as a sequence over the whole method, so this is a jzap-only false survivor rather than a
  shared limitation. Closing it means deciding the same question from the instruction list
  instead, in the shape the position-based filters already use.

## Running the checks

```bash
./gradlew build                  # all unit and end-to-end tests
./gradlew :tools:parity:parity   # differential correctness against PIT
./gradlew :tools:bench:bench     # timings for both tools over the same classes
```
