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

**Reporters**: console, jzap's own JSON, the mutation-testing-elements schema, a
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
| Full run | 20.0s (19.7-20.3) | 30.2s (30.1-30.5) |
| Diff run, one changed line, 6 mutants in scope | 1.6s | not comparable |

That is 1.51x faster than PIT while analysing 40 *more* mutants (3.8%), so the ratio is
conservative rather than flattered. Repeat runs of the whole harness landed between 1.51x and
1.57x, which is the honest width of this measurement on a machine that is also doing other
things. It is also not the interesting number: the engine here is the
deliberately slow reference implementation, with no schemata, no warm daemon, no cache and no
parallelism. The diff run is 12x faster than jzap's own full run, which is the figure the
product is actually about.

PIT is not timed on the diff scenario because its free scoping works at changed-*file*
granularity and needs a git repository, so it would be doing a different amount of work.
Line-level scoping in the PIT ecosystem is arcmutate's, which is commercial and unmeasured
here.

The absolute numbers are machine-specific: this was run on a developer machine, not an
isolated bench host, which is exactly the caveat docs/parity-and-benchmarks.md requires before
any figure is published.

## Not built yet

The performance work that motivates the project. The engine is deliberately the slow,
obvious one — `docs/delivery-plan.md` keeps it permanently as the oracle every optimisation is
differentially tested against.

| Missing | Milestone |
|---|---|
| Mutant schemata (compile once, all mutants as guarded branches) | M8b |
| Warm daemon with in-JVM mutant switching and static-state reset | M9 |
| Block-granularity coverage, kill-test-first ordering, hit-probe timeouts | M10 |
| Arid-node suppression, one-per-line, TCE dedup, extreme mutation | M12 |
| Kotlin junk-mutant handling and inline functions | M15, M16 |
| Maven plugin | M19 |
| Multi-module single run with cross-module test selection | M20 |

Also absent: parallelism (the engine is single-threaded), Tier B and Tier C corpora (parity
runs against the hand-written fixture only), and the JUnit 4 and TestNG adapters behind the
`jzap-testkit` SPI.

## Known limitations of what does exist

- **Line-granularity coverage** selects more tests than necessary. Block granularity with
  exception-correct attribution is M10.
- **Static state between mutants** is bounded by recycling the analysis JVM every
  `maxMutantsPerMinion` mutants, not by resetting it. Proper reset is M9.
- **Timeouts are wall-clock based**, which makes a `TIMED_OUT` verdict non-deterministic. It
  is therefore reported as a distinct status and will never be cached. Hit-probe detection is
  M10, and it is a prerequisite for the Gradle build cache being sound.
- **Renames are not followed** in git diffs. A renamed file's every line looks changed, which
  would flood a pull request with mutants for code nobody touched.
- **Test classes are never mutated**, because only a module's `mutableCodePaths` are scanned.
  This is structural, not a filter that can be switched off.

## Running the checks

```bash
./gradlew build                  # all unit and end-to-end tests
./gradlew :tools:parity:parity   # differential correctness against PIT
./gradlew :tools:bench:bench     # timings for both tools over the same classes
```
