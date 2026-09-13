# jzap

Fast, diff-aware mutation testing for Java and Kotlin.

Mutation testing measures whether your tests would actually notice a bug. jzap changes your
compiled code in small, realistic ways — flips a comparison, swaps an operator, drops a call —
and reports every change your test suite fails to catch. Each survivor is a concrete gap.

**Status: working, measured, and faster than PIT.** On the benchmark fixture, single-threaded and
with the same mutators: **8.9s against PIT's 29.6s**, while analysing 40 *more* mutants. Verdicts
agree with PIT on all 1051 mutants the two tools share. A warm cache takes a re-run to 0.43s, and a
resident daemon takes it to 0.17s.

Java and Kotlin, JUnit 5 and Kotest, Gradle and Maven, single module and whole reactor.
See [docs/status.md](docs/status.md) for exactly what exists and what does not.

## Gradle

```groovy
plugins {
    id 'java'
    id 'io.github.huyz0.jzap'
}

jzap {
    threads = 4
    threshold = 80
    cacheDir = layout.buildDirectory.dir("jzap-cache")   // optional, off by default
}
```

```bash
./gradlew mutationTest        # the whole module
./gradlew mutationTestDiff    # only lines changed since HEAD, including uncommitted work
```

## Maven

```xml
<plugin>
  <groupId>io.github.huyz0</groupId>
  <artifactId>jzap-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <threshold>80</threshold>
  </configuration>
</plugin>
```

```bash
mvn verify                      # bound to the verify phase
mvn jzap:mutationCoverage       # or on its own
```

## Try it without a build tool

```bash
./gradlew :jzap-cli:installDist :fixtures:sample-java:writeProjectModel
```

Then point it at a project model, which a build-tool adapter normally produces — here the
fixture's own build writes one:

```bash
./jzap-cli/build/install/jzap/bin/jzap run -m build/fixture-model.json -o build/reports/jzap
```

```
Surviving mutants (2):
  sample/Discount.java
    line 10: changed conditional boundary: <= became < [CONDITIONALS_BOUNDARY]
      if (percent > 50) {
    line 17: replaced boolean return with true [TRUE_RETURNS]
      return price == 0;

  killed           7
  survived         2
  no coverage      3
  ------------------
  total           12

Mutation score 58.3%  (test strength 77.8%, ignoring uncovered mutants)
```

Analyse only what a pull request changed:

```bash
jzap run -m model.json --from origin/main --to -Local-
```

The git range only selects what to analyse. Analysis always runs against the currently
compiled code; nothing is checked out.

## Commands

```bash
jzap run           -m model.json [--from REF --to REF | --patch FILE] [--scope line|class]
                   [--threads N] [--cache-dir DIR] [--threshold PERCENT]
jzap list-mutants  -m model.json          # the inventory, without running a single test
jzap mutators                             # the available mutators
jzap run -m model.json --dry-run          # resolved scope and classpaths, then stop
```

## How it fits together

The engine knows nothing about Gradle, Maven, or git. A build tool computes a **project
model** — classpaths, source roots, toolchain — and hands it over; a CI system can supply a
unified diff instead of a checkout. That single seam is why build-tool support is additive
rather than invasive. See [docs/architecture.md](docs/architecture.md).

```
jzap-model    the versioned project model, mutant keys, results
jzap-core     discovery, mutation, coverage probes, the controller
jzap-agent    the Java agent            (dependency-free: shares a JVM with your code)
jzap-wire     the controller protocol   (dependency-free, same reason)
jzap-minion   the forked JVM that runs your tests
jzap-git      git ranges and unified diffs, resolved to line ranges
jzap-report   console, JSON, mutation-testing-elements, HTML, PR annotations
jzap-cli      the command line
jzap-gradle   the Gradle adapter: source sets and toolchains in, project model out
jzap-maven    the Maven adapter, built by Maven because plugin descriptors are
```

Kotlin is analysed the same way, with the compiler's own scaffolding filtered out and inline
function bodies mutated through their call sites — reported against the inline function's source,
not the synthetic line numbers kotlinc gives the copies.

## Verifying it against PIT

PIT is jzap's correctness oracle, and the comparison is wired into the build rather than done
by hand:

```bash
./gradlew :tools:parity:parity   # inventory diff, verdict agreement matrix, triage
./gradlew :tools:bench:bench     # timings for both tools over the same classes
```

Any difference that is not justified in `tools/parity/parity-baseline.yaml` fails the build —
and so does a baselined difference that stops occurring, since that means behaviour moved and
nobody noticed. See [docs/parity-and-benchmarks.md](docs/parity-and-benchmarks.md).

## Documentation

- [docs/status.md](docs/status.md) — what works today, what does not
- [docs/prior-art.md](docs/prior-art.md) — the research this is built on
- [docs/architecture.md](docs/architecture.md) — the build-tool-agnostic core
- [docs/delivery-plan.md](docs/delivery-plan.md) — 23 milestones, with kill criteria
- [docs/parity-and-benchmarks.md](docs/parity-and-benchmarks.md) — the PIT comparison harness
- [docs/troubleshooting.md](docs/troubleshooting.md) — the failures people actually hit
- [docs/compatibility.md](docs/compatibility.md) — JDK, build tool, framework and language versions
- [docs/versioning.md](docs/versioning.md) — what counts as a breaking change
- [docs/adr/](docs/adr/) — decision records
