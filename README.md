# jzap

Fast, diff-aware mutation testing for Java and Kotlin.

Mutation testing measures whether your tests would actually notice a bug. jzap changes your
compiled code in small, realistic ways — flips a comparison, swaps an operator, drops a call —
and reports every change your test suite fails to catch. Each survivor is a concrete gap.

**Status: working, measured, and faster than PIT.** On the benchmark fixture, single-threaded and
with the same mutators: **2.9s against PIT's 28.1s** on a developer machine, and 8.5x the same
comparison on a four-core CI runner, while analysing 40 *more* mutants. Verdicts
agree with PIT on all 1051 mutants the two tools share. A warm cache takes a re-run to 0.42s, and a
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
    threshold = 80
    threads = 4                  // optional; the default is 1, raise it for a slow suite
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
    <!-- Required in 0.1.0: the plugin forks the engine and does not resolve it itself yet.
         See docs/usage.md for how to put the engine's jars there. -->
    <engineClasspath>${project.build.directory}/jzap-engine/*</engineClasspath>
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

`jzap run` exits `0` when the run met its bar, `1` when it did not (`--threshold` or
`--fail-on-survivors`), `2` on a usage error and `3` when the analysis itself failed. A
mutant the JVM refuses to load (`NON_VIABLE`) or one whose analysis breaks (`RUN_ERROR`) is
counted and reported but left out of both percentages, since the tests were never given the
chance to detect it; a `RUN_ERROR` exits `3` rather than scoring the rest and passing quietly.

## With a coding agent

```bash
npx skills add huyz0/jzap      # teaches an agent to run jzap and act on the findings
```

Then ask it to check whether a change is actually tested. The skill covers scoping to the diff,
reading the findings, and what assertion each kind of survivor is missing —
[skills/jzap/](skills/jzap/) is one short file, worth reading before installing.

For agent-shaped output from any invocation, `-r agent` prints the survivors and uncovered
mutants and nothing else: on the benchmark fixture 16 KB against the JSON report's 509 KB, about
4,000 tokens instead of 130,000.

```
jzap: 2 survived, 3 uncovered of 12 mutants (score 58.3%, strength 77.8%)

survived:
sample/Discount.java
  10 CONDITIONALS_BOUNDARY changed conditional boundary: <= became <
  17 TRUE_RETURNS replaced boolean return with true
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
jzap-report   console, JSON, mutation-testing-elements, HTML, PR annotations, agent
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

**[huyz0.github.io/jzap](https://huyz0.github.io/jzap/)** — the same documents as below, with
navigation and search. The site is built from `docs/` rather than from a copy, so it cannot
drift from what the repository maintains.

- [docs/usage.md](docs/usage.md) — install, run, scope to a diff, read the report, wire up CI
- [docs/mutators.md](docs/mutators.md) — the ten mutators, and every rule that suppresses a mutant
- [docs/performance.md](docs/performance.md) — every published number, and how it was taken
- [docs/status.md](docs/status.md) — what works today, what does not
- [docs/prior-art.md](docs/prior-art.md) — the research this is built on
- [docs/architecture.md](docs/architecture.md) — the build-tool-agnostic core
- [docs/delivery-plan.md](docs/delivery-plan.md) — the phased delivery plan, with kill criteria
- [docs/parity-and-benchmarks.md](docs/parity-and-benchmarks.md) — the PIT comparison harness
- [docs/profiling.md](docs/profiling.md) — where the time goes, and which optimisations were not worth doing
- [docs/troubleshooting.md](docs/troubleshooting.md) — the failures people actually hit
- [docs/compatibility.md](docs/compatibility.md) — JDK, build tool, framework and language versions
- [docs/versioning.md](docs/versioning.md) — what counts as a breaking change
- [docs/adr/](docs/adr/) — decision records

To build it locally:

```bash
pip install -r mkdocs-requirements.txt
mkdocs serve
```

## Releasing

Not published yet: the automation is in place and the signed Central bundle is verified, but both
destinations need accounts and signing keys. [docs/releasing.md](docs/releasing.md) lists exactly
what to create, and a `v*` tag does the rest.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). Contributions are accepted under the same terms.
