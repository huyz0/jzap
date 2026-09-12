# jzap docs

An ultra-fast, diff-aware mutation testing tool for Java and Kotlin.

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
- [adr/](adr/) — decision records.
  - [ADR 0001](adr/0001-implementation-language.md) — Java for the engine, not Rust.
