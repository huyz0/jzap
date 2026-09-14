# jzap

Fast, diff-aware mutation testing for Java and Kotlin.

Mutation testing measures whether your tests would actually notice a bug. jzap changes your
compiled code in small, realistic ways — flips a comparison, swaps an operator, drops a call —
and reports every change your test suite fails to catch. Each survivor is a concrete gap: a
line your tests execute without checking.

Coverage tells you a line ran. Mutation testing tells you whether anything was asserted about it.

## Where it stands

On the generated benchmark fixture — 40 classes, 200 tests, 1080 mutants — single-threaded, with
the same ten mutators, both tools analysing the same prebuilt classes:

| | jzap | PIT |
|---|---|---|
| Full run, one thread | **2.91s** | 28.09s |
| Mutants analysed | 1080 | 1040 |

That is **9.64x** while analysing 40 *more* mutants, so the ratio understates rather than
flatters. A warm cache takes a re-run to 0.42s and a resident daemon to 0.17s.

Correctness is not asserted, it is measured: verdicts agree with PIT on **all 1051 mutants the
two tools share**, across a hand-written fixture and a generated one two orders of magnitude
larger. See [Measured results](performance.md) for the full table with its caveats, and
[Benchmark harness](parity-and-benchmarks.md) for the rules the comparison runs under.

!!! warning "Not yet published"
    jzap is not on Maven Central or the Gradle Plugin Portal. Everything documented here works
    and is tested, but using it today means building from source. [Status](status.md) records
    exactly what exists, what is measured, and what is not built.

## Start here

<div class="grid cards" markdown>

-   :material-rocket-launch: **[Getting started](usage.md)**

    Gradle, Maven and the CLI. Diff-scoped runs, reporters, exit codes, CI wiring.

-   :material-dna: **[Mutators and filters](mutators.md)**

    The ten mutators, what each one changes, and every rule that decides a mutant is not
    worth seeding.

-   :material-speedometer: **[Measured results](performance.md)**

    Every published number, how it was taken, and which comparisons are deliberately absent.

-   :material-sitemap: **[Architecture](architecture.md)**

    The project-model seam that keeps the engine ignorant of Gradle, Maven and git.

</div>

## What makes it fast

Four things, each measured rather than assumed, and each written up where the numbers are:

- **Mutant schemata.** Every mutant of a class is compiled in at once and selected by a field
  write, instead of redefining the class once per mutant. **6.41x** on a full run, 10.37x on the
  execution phase alone, with verdicts asserted identical against the reference engine.
- **Diff scoping at line granularity.** A pull request analyses the lines it changed, not the
  repository. Free line-level diff scoping does not otherwise exist in the PIT ecosystem.
- **Per-test coverage with early exit**, and kill-test-first ordering from the cache, so a mutant
  runs the one test most likely to kill it and stops there.
- **Profiling, which supplied three quarters of the speed.** Not new features: an analysis JVM
  discovering a test suite it never read, JIT warmup thrown away every 100 mutants, and three
  protocol round trips per mutant where one would do. [Profiling notes](profiling.md) records what
  was fixed and, at greater length, what measurement ruled *out*.

## How correctness is established

PIT is the oracle, and the comparison is wired into the build rather than done by hand. An
unjustified difference fails the build — and so does a justified difference that stops occurring,
since that means behaviour moved and nobody noticed.

```bash
./gradlew :tools:parity:parity   # inventory diff, verdict agreement matrix, triage
./gradlew :tools:bench:bench     # timings for both tools over the same classes
```

Claims about PIT's behaviour in these documents were established by running it, not by reading
about it. Where a document says PIT does something, there is a probe or a baseline entry behind it.

## Every document here

**Using it**

- [Getting started](usage.md) — install, run, scope to a diff, read the report, wire up CI
- [Mutators and filters](mutators.md) — what gets changed, and what deliberately does not
- **Coding agents** — `npx skills add huyz0/jzap` installs a skill that teaches an agent to run
  jzap and act on what it finds; `-r agent` is the reporter shaped for reading as command output
- [Troubleshooting](troubleshooting.md) — the failures people actually hit, and what each means
- [Compatibility](compatibility.md) — what jzap has been run against, rather than what it might
  work with

**Performance**

- [Measured results](performance.md) — the benchmark table, the caveats, the honest gaps
- [Profiling notes](profiling.md) — where the time went, and which leads are closed
- [Benchmark harness](parity-and-benchmarks.md) — corpus, normalisation, agreement matrix, triage
  discipline, fairness rules
- [bench-report.txt](bench-report.txt) — the most recent benchmark output, verbatim

**Design**

- [Architecture](architecture.md) — the build-tool-agnostic core, the model seam, what leaks and
  where it is allowed to
- [Prior art](prior-art.md) — the JVM tool landscape, where PIT spends its time, how other
  ecosystems do delta runs
- [Versioning](versioning.md) — what counts as a breaking change to the schema, the cache, and
  mutator ids
- [ADR 0001](adr/0001-implementation-language.md) — Java for the engine, not Rust

**Project**

- [Status](status.md) — what works today, what is measured, what is not built
- [Coverage](coverage.md) — what the coverage figure measures and what it cannot
- [Releasing](releasing.md) — what a release publishes where, and the accounts and keys it needs
- [Delivery plan](delivery-plan.md) — the phased plan, each stage with a binary definition of
  done and kill criteria

## Licence

Apache License 2.0. Contributions are accepted under the same terms.
