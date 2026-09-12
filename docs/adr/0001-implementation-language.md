# ADR 0001: Implementation language — Java for the engine, not Rust

- **Status:** Accepted
- **Date:** 2026-09-12
- **Context docs:** [prior-art.md](../prior-art.md), [architecture.md](../architecture.md)

## Context

jzap's premise is an ultra-fast, diff-aware mutation testing tool for Java and Kotlin.
Rust is an obvious candidate for a tool that advertises speed, so the question is whether
the engine should be written in Rust with a minimal JVM helper, or on the JVM outright.

The answer turns on where the wall clock actually goes in a mutation run:

| Phase | Share of wall clock | Rust-addressable? |
|---|---|---|
| Baseline coverage run (full suite, instrumented) | seconds → minutes | No — the JVM runs user tests |
| Mutant execution (N mutants × selected tests) | **dominant, usually >90%** | No — the JVM runs user tests |
| Mutant generation (ASM over changed classes) | milliseconds → low seconds | Yes, irrelevantly |
| Diff parse, scope resolution, scheduling, cache I/O | milliseconds | Yes, irrelevantly |
| Report generation | milliseconds | Yes, irrelevantly |

Amdahl's law caps the upside: if all non-JVM work is around 3% of runtime, moving it to
Rust yields at most ~1.03×. The levers identified in [prior-art.md](../prior-art.md) are
5–50×, and every one of them is algorithmic rather than linguistic — mutant schemata,
a warm daemon with in-process mutant switching, block-level coverage-driven test
selection, and mutant-space reduction.

Two pieces of evidence settle it:

- **Runtime tracks mutants executed, nothing else.** Descartes on Apache Flink core:
  8.8× fewer mutants (43,619 → 4,935) produced 10.6× less time (2:29:45 → 14:04). The
  harness language never entered the equation.
- **The Rust tool in this space has the slowest architecture surveyed.** `cargo-mutants`
  is written in Rust and recompiles per mutant; Rust bought it nothing, because the cost
  lives in the compile-and-test loop. Conversely `mutmut` 3 is Python and is fast,
  because it moved to fork + trampoline.

Beyond the performance argument, every technique jzap depends on must execute *inside*
the JVM, in the same process as the code under test:

- mutant schemata — rewriting user classes, i.e. ASM
- `Instrumentation.redefineClasses` for mutant swapping — a Java agent
- per-test coverage probes — bytecode instrumentation
- UniAPR-style static-state reset between mutants — runtime bytecode transformation
- selecting and running individual tests — the JUnit Platform launcher
- Kotlin-faithful mutant selection — the Kotlin compiler's IR/PSI, JVM-only by construction

So a substantial JVM component exists no matter what language the orchestrator is in.

## Decision

1. **`jzap-core` and the agent are written in Java**, with minimal dependencies, shaded
   and relocated.
2. **Kotlin is permitted** for `jzap-cli`, `jzap-gradle`, `jzap-maven`, `jzap-report`,
   and `jzap-kotlin` — anything that does not get loaded into the user's JVM alongside
   their code.
3. **No Rust in the initial architecture.**

Java for the engine specifically because:

- Anything sharing a classloader with user code needs minimal, shaded dependencies.
  Putting kotlin-stdlib on the agent classpath invites precisely the version-clash class
  of bug PIT avoids by relocating its minion dependencies.
- ASM, the Instrumentation API, and JVMTI are Java-native, and the bytecode ecosystem is
  Java.
- The agent must load on whatever JDK the user's build chose, across a wide version range.

The controller process never loads code under test (PIT's doesn't either), so it could
technically be Kotlin. Keeping it Java is a friction choice, not a technical constraint.

## Consequences

**Accepted:**

- No sub-10ms process startup. Mitigated by the daemon: JVM startup is paid once per
  warm session, so a fast native launcher would save nothing measurable. See the daemon
  section of [architecture.md](../architecture.md).
- Java for the engine is more verbose than Kotlin would be, in the module where we will
  write the most code.
- Contributors working on the engine need Java plus ASM familiarity; contributors working
  on adapters and reporting can stay in Kotlin.

**Avoided:**

- A two-language build with an IPC protocol between orchestrator and JVM.
- Shipping per-platform native binaries alongside a jar.
- Reimplementing ASM and kotlinx-metadata equivalents against immature Rust bytecode
  crates, for no hot-path gain.

## Alternatives considered

**Rust orchestrator + JVM agent.** Rejected. The JVM component is required regardless,
so this adds a language boundary, a protocol, and a distribution problem while addressing
only the ~3% of runtime that was never the bottleneck.

**Rust end to end.** Not possible. Tests are JVM bytecode and must run on a JVM, and the
Kotlin frontend needs the Kotlin compiler.

**Kotlin for the engine and agent.** Rejected for the agent on dependency-hygiene
grounds (kotlin-stdlib on the user's classpath). Viable for the controller, but split
languages inside the engine buys little.

**GraalVM native-image for the controller.** Same ~3% ceiling as Rust, plus reflection
and agent-loading constraints that conflict with instrumenting a live JVM.

## Revisit if

- Profiling shows non-JVM work (scope resolution, scheduling, cache I/O, report
  generation) exceeding ~10% of wall clock on a real repository — most plausibly on huge
  monorepos where diff and cache handling dominate a small analysis.
- A watch-mode or IDE integration needs many short-lived invocations *and* the daemon
  cannot be kept warm, making launcher startup visible to users.

Either case is addressable incrementally: the versioned project-model JSON seam means a
native CLI client or a native diff/scope preprocessor can be dropped in without touching
the engine. That is the reason to defer, rather than pre-commit to, any native component.
