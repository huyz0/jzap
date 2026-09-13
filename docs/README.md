# jzap docs

An ultra-fast, diff-aware mutation testing tool for Java and Kotlin.

- [status.md](status.md) — what works today, what is measured, what is not built yet.

- [prior-art.md](prior-art.md) — research notes: the existing JVM tool landscape, where
  PIT spends its time, how other ecosystems do delta/diff runs, and which speed
  techniques are worth building on.
- [architecture.md](architecture.md) — build-tool-agnostic core, the project-model seam,
  what genuinely leaks into build-tool adapters, daemon ownership, build order, risks.
- [delivery-plan.md](delivery-plan.md) — 23 milestones in 7 phases, each with a goal, a
  binary definition of done, and a task list. Includes kill criteria.
- [parity-and-benchmarks.md](parity-and-benchmarks.md) — the PIT differential-correctness
  and performance harness: corpus, mutant normalisation, agreement matrix, triage
  discipline, benchmark scenarios and fairness rules.
- [bench-report.txt](bench-report.txt) — the most recent benchmark output, verbatim.
- [profiling.md](profiling.md) — where the time goes, what was done about it, and which leads
  turned out to be closed.
- [troubleshooting.md](troubleshooting.md) — the failures people actually hit, and what each means.
- [compatibility.md](compatibility.md) — what jzap has been run against, rather than what it might
  work with.
- [versioning.md](versioning.md) — what counts as a breaking change to the model schema, the cache
  format, mutator ids and mutant keys.
- [adr/](adr/) — decision records.
  - [ADR 0001](adr/0001-implementation-language.md) — Java for the engine, not Rust.
