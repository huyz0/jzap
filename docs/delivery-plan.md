# jzap delivery plan

23 milestones in 7 phases. Each milestone states a goal, a binary definition of done, and
a task list. Every milestone from M6 onward is gated on the PIT parity harness described
in [parity-and-benchmarks.md](parity-and-benchmarks.md).

Architecture and module names come from [architecture.md](architecture.md); the technique
choices come from [prior-art.md](prior-art.md); the language choice from
[ADR 0001](adr/0001-implementation-language.md).

## Sequencing rationale

Three rules drive the order:

1. **Correctness before speed.** A slow-but-right engine (M6) ships before any
   optimisation, and is kept forever as the reference implementation that every
   optimisation is differentially tested against. Optimising an engine whose verdicts you
   cannot trust produces fast wrong answers.
2. **The oracle comes early.** The parity harness (M4) and benchmark harness (M7) are
   built before the work they measure, not after. Every later milestone has a
   pre-existing way to prove it.
3. **De-risk the unknowns first.** M1 is timeboxed spikes on the three bets the whole
   premise rests on. If they fail, the architecture changes while that is still cheap.

## Kill criteria

Checked at M9 and M12. If, at equal mutant inventory on scenarios S1–S4:

- jzap is not **≥2× faster than PIT** on a full module run (S1), **and**
- jzap is not **≥5× faster than PIT** on a PR-sized diff run warm (S4),

then the performance premise has failed and the project should be reconsidered rather
than continued. Record the measurement either way; a negative result documented is worth
more than the work continued on momentum.

---

# Phase 0 — Foundations

## M0 · Repo skeleton and CI

**Done.** Gradle build, nine modules, JDK 17 release target. CI matrix not set up (no CI yet); the module dependency-direction check is enforced by the agent's constant-pool test rather than ArchUnit.

**Goal.** A multi-module build that compiles, tests, and publishes snapshots on three
JDKs, with the module boundaries from [architecture.md](architecture.md) enforced.

**Definition of done.**
- `./gradlew build` green from a clean clone with no local setup beyond a JDK.
- Modules exist and compile: `jzap-model`, `jzap-core`, `jzap-cli`, `jzap-report`,
  `jzap-git`, `jzap-testkit`, `jzap-daemon`. Empty is fine; boundaries are not.
- Dependency rules enforced by a build check, failing the build on violation:
  `jzap-model` depends on nothing; `jzap-core` does not depend on `jzap-git`,
  `jzap-gradle`, `jzap-maven`, or any build tool API.
- CI matrix on JDK 17, 21, 25 (earliest supported target decided and recorded).
- `jzap --version` runs from a built distribution.
- Shading/relocation configured for `jzap-core` and the agent, verified by a test that
  asserts no unrelocated third-party package appears in the shaded jar.
- License, `CONTRIBUTING`, code format + static analysis in CI.

**Tasks.**
- Gradle build with version catalog; convention plugins for the shared config.
- Module dependency-direction check (ArchUnit or a custom Gradle verification task).
- Shadow/relocation setup + the "no leaked packages" test.
- CI workflow: build, test, JDK matrix, snapshot publish.
- Decide and document minimum supported JDK for *running* jzap versus *target bytecode*
  it can analyse — these are different numbers and conflating them causes support pain.

## M1 · Risk spikes (timeboxed, 1 week hard stop)

**Skipped, deliberately.** The spikes were folded into M3-M6: building the reference engine answered Spike B (redefinition works; state drift is bounded by recycling rather than reset) and part of Spike C. Spike A, the schemata size budget, is still unanswered and is now the first task of M8.

**Goal.** Establish empirically that the three load-bearing technical bets work, before
the architecture depends on them.

**Definition of done.** A written spike report per bet, with runnable throwaway code and
a go/no-go, plus any architecture doc updated to match what was learned.

- **Spike A — bytecode schemata viability.** Insert 1, 10, 100, 1000 guarded mutants into
  one method and one class. Record: does the 64KB per-method bytecode limit bite, and at
  how many mutants; does the constant-pool 64K limit bite; what is the JIT impact of the
  switch on the surrounding code; does verification pass. **Output: the per-method and
  per-class mutant budget, which the M8 design must respect.**
- **Spike B — in-JVM mutant swapping and state reset.** Run two mutants back to back in
  one JVM with `Instrumentation.redefineClasses`; then demonstrate a state-leak failure
  (a static counter, a memoising cache, an initialised singleton) and demonstrate
  resetting it via bytecode transformation. **Output: whether redefinition or schemata
  switching is the primary mechanism, and what classes of state we can and cannot reset.**
- **Spike C — Kotlin inline reality check.** Take a real Kotlin module; enumerate inline
  function bodies and their inlined copies in callers; confirm the discrepancy described
  in [prior-art.md](prior-art.md) and measure the analysis cost. **Output: whether M16 is
  a bytecode problem or needs the IR frontend (M17).**

**Tasks.** One throwaway prototype per spike; write the report; update
[architecture.md](architecture.md) and open ADRs for anything that contradicts current
assumptions.

## M2 · Project model and CLI contract

**Done.** Schema version 1, round-trip tested, unknown fields warn, unknown `schemaVersion` is fatal with advice. `--dry-run` prints the resolved scope and classpaths.

**Goal.** The seam is real and versioned before anything consumes it.

**Definition of done.**
- JSON schema for the project model published in `jzap-model`, version 1.
- Round-trip tests: parse → serialise → byte-identical.
- Validation with actionable messages: every rejection names the offending field, the
  expected shape, and the actual value.
- `jzap --project-model model.json --dry-run` prints the resolved module set, classpaths,
  scope, and per-module bytecode hashes, exiting 0, with no analysis performed.
- Forward-compatibility rule implemented and tested: unknown fields are ignored with a
  warning; an unknown `schemaVersion` major is a hard error.
- Hand-written model JSONs for three shapes committed as fixtures: single module,
  multi-module, Kotlin module.

**Tasks.**
- Model records + schema + validator.
- CLI argument parsing, `@argfile` support (Windows command-line length limits).
- `--dry-run` resolution report.
- Fixture models and round-trip tests.

---

# Phase 1 — A correct engine

## M3 · Mutant inventory

**Done.** Ten mutators matching PIT's DEFAULTS ids, stable keys, `jzap list-mutants`, `mutator-mapping.yaml`, hand-written Tier A expectations. Two filters were added that the plan had not anticipated: no-op return mutants and loop counters.

**Goal.** Enumerate mutation points from compiled classes with stable identities. No
execution.

**Definition of done.**
- ASM-based class scanner walks `mutableCodePaths`, respecting include/exclude filters.
- Mutator catalog implemented for the core Java set (conditionals boundary, negate
  conditionals, math, increments, invert negatives, return values, void method calls,
  constructor calls, remove conditionals, empty/false/true/primitive returns).
- Every mutant carries a **stable ID** that survives recompilation when the enclosing
  method is unchanged, and the ID scheme is documented with its invalidation rules.
- `mutator-mapping.yaml` committed: every jzap mutator mapped to PIT mutators as
  `equivalent` / `broader` / `narrower` / `no-equivalent`, with justification for every
  `no-equivalent`.
- `jzap list-mutants` emits normalised JSON for a model.
- Tier A microfixtures exist for every mutator, each asserting the exact expected mutant
  list, written by hand and not copied from any tool's output.
- Deterministic: same bytecode in, identical mutant list and ordering out, asserted.

**Tasks.**
- Class scanner + filters; method/descriptor/line extraction from line-number tables.
- Mutator SPI + the core catalog.
- Mutant ID scheme + documentation + stability tests across recompilation.
- `list-mutants` command; Tier A fixtures; PIT mapping table.

## M4 · PIT parity harness — inventory level

**Done, and extended.** Runs on the hand-written fixture and on a generated one 100x larger. Baseline matches by key glob rather than by count, and fails on stale entries.

**Goal.** Automated, reproducible inventory comparison against PIT, available to every
later milestone.

**Definition of done.**
- `tools/parity/` runs both tools over a corpus tier and emits an inventory diff:
  `jzap-only`, `pit-only`, `shared`, grouped by cause.
- PIT XML and jzap JSON both normalised into the same record type by separate adapters;
  neither format privileged.
- Corpus Tier A (~40 microfixtures) and Tier B (≥4 real Java projects) pinned in
  `corpus.lock` at exact commits.
- `parity-baseline.yaml` exists; CI fails on any inventory difference category not in the
  baseline, **and** on any baselined category that disappears silently.
- A triage report is generated per run, human-readable, listing every difference with its
  classification (A/B/C/D per [parity-and-benchmarks.md](parity-and-benchmarks.md)).
- Documented: how to add a corpus project, how to accept a baseline change.

**Tasks.**
- Corpus fetch/pin tooling; PIT runner at a pinned version.
- Normalisers; inventory comparator; triage report renderer.
- Baseline file format + CI gate (both directions).
- Triage the first real diff to zero unclassified entries.

## M5 · Test execution and per-test coverage

**Partial.** Per-test coverage works through the JUnit Platform launcher, at **line** granularity rather than block. JaCoCo cross-check not built. JUnit 4 and TestNG adapters not built.

**Goal.** Run individual tests and record, per test, which basic blocks executed.

**Definition of done.**
- JUnit Platform launcher integration: discover, select, and run tests individually,
  not via a build tool's test task. JUnit 4 via vintage and TestNG behind the
  `jzap-testkit` SPI.
- Coverage probes instrument at **basic-block** granularity, with correct attribution in
  the presence of exceptions (the gap PIT PR #534 addresses).
- Per-test block coverage map produced; per-test wall-clock baseline recorded for later
  timeout calibration.
- Line coverage derived from block coverage agrees with JaCoCo on Tier B projects within
  a documented tolerance, and every deviation is explained.
- Tier A hazard fixtures pass: static initialisers, exceptions, lambdas, parameterised
  and nested tests, `finally`, try-with-resources.
- Static-initialiser attribution behaviour explicitly documented — this is a known PIT
  weakness and jzap must state its own semantics rather than inherit them by accident.
- Coverage run is deterministic given fixed test order; test order is controlled and
  recorded.

**Tasks.**
- Agent + block-probe instrumentation; probe data collection with low per-test overhead.
- JUnit Platform adapter; vintage and TestNG adapters.
- Coverage model + serialisation; JaCoCo cross-check harness.
- Hazard fixtures; static-init semantics decision + doc.

## M6 · Naive kill loop — the correctness baseline

**Done.** 1051 shared mutants compared against PIT, 100% verdict agreement, one triaged inventory difference. `--engine=naive` is not yet a flag because there is only one engine; it becomes one in M8.

**Goal.** A complete, obviously-correct mutation run. Slow is acceptable and expected.

**Definition of done.**
- One mutant at a time, inserted as bytecode via the Instrumentation API in a forked
  minion JVM; nothing written to disk.
- Minions reused across mutants; a hung or crashed minion is killed and its mutant
  retried in a fresh one, with the retry recorded.
- Early exit on first killing test. Timeouts from M5 baselines, reported as a distinct
  `TIMED_OUT` status.
- Full status set produced: `KILLED`, `SURVIVED`, `NO_COVERAGE`, `TIMED_OUT`,
  `NON_VIABLE`, `RUN_ERROR`.
- **Verdict parity vs PIT on Tier A and Tier B: 100% agreement on shared mutants, modulo
  entries justified in `parity-baseline.yaml`.** Zero unclassified disagreements. Any
  `KILLED` vs `SURVIVED` disagreement is resolved before the milestone closes.
- Killing-test comparison against PIT `--fullMutationMatrix` run and triaged.
- This engine is retained permanently behind `--engine=naive` as the reference
  implementation for all later differential testing.

**Tasks.**
- Controller/minion protocol; minion lifecycle, reuse, kill, retry.
- Mutant insertion via instrumentation; status determination; early exit.
- Extend the parity harness to verdict-level and killing-test comparison.
- Triage every disagreement to A/B/C/D; add Tier A fixtures proving jzap right wherever
  the cause is a PIT limitation.

## M7 · Benchmark harness and honest baseline

**Done.** Scenarios S1 and S3 implemented with median-of-N reporting, work-parity reporting, and a refusal to print a diff figure when nothing is in scope. S2, S4-S8 need the daemon and cache that do not exist yet.

**Goal.** Know exactly how slow the naive engine is, and have the apparatus that will
measure every later claim.

**Definition of done.**
- `tools/bench/` implements scenarios S1–S8 from
  [parity-and-benchmarks.md](parity-and-benchmarks.md).
- Metrics collected per scenario: phase-split wall clock, mutants generated, mutants
  executed, **test executions per mutant**, mutants/second, peak RSS, CPU-seconds,
  process count.
- 5 runs per scenario; report emits median with min–max range. A single-number output is
  not accepted.
- Baselines recorded for PIT default, PIT with history file, and PIT
  `scmMutationCoverage`; arcmutate recorded as measured or explicitly *unmeasured*.
- Both tools' configuration checked in so any third party can rerun it.
- CI regression gates active on machine-independent metrics only, with budgets in
  `budgets.yaml`.
- A published baseline document stating, without spin, how far behind PIT the naive
  engine is.

**Tasks.**
- Scenario runner; metric collection; JFR or async-profiler hooks for phase attribution.
- Dedicated bench machine setup + documented procedure (governor, turbo, isolation).
- Report renderer; CI gate on stable metrics.
- Write and publish the baseline numbers.

---

# Phase 2 — Speed

Every milestone in this phase carries the same two standing gates, in addition to its own:

- **Self-differential:** identical verdicts to `--engine=naive` on Tier A and Tier B,
  with the optimisation on and off.
- **Determinism:** same model + same bytecode → byte-identical report, excluding the
  designated timing block.

## M8 · Parallel execution, then the schemata engine

**Not started.** Split into two deliverables after building the reference engine, because they
are independent and the first is far cheaper per unit of speed. The engine is currently
single-threaded, so a laptop with eight cores is doing an eighth of the work it could.

### M8a · Parallel execution

**Goal.** Analyse mutants on every available core.

**Definition of done.**
- A pool of analysis JVMs sized from `ProjectModel.threads`, each with its own coverage
  baseline-independent work queue.
- Mutants are partitioned **by class**, not round robin. A minion that keeps working on one
  class reuses its loaded, JIT-compiled state; scattering classes across minions throws that
  away and re-pays class loading per mutant.
- The coverage phase stays single-JVM: per-test coverage is only meaningful if tests run in
  isolation from each other, and running them concurrently would let one test's threads
  pollute another's probe readings.
- Standing gates pass: identical verdicts to the single-threaded engine on Tier A and Tier B,
  with 1, 2 and 8 threads.
- A report produced with 8 threads is **byte-identical** to one produced with 1, since results
  are sorted by mutant key before writing. Asserted, because non-determinism here would break
  the Gradle build cache before it is ever built.
- A hung mutant kills only its own minion; the other workers keep going. Tested with a fixture
  containing a deliberate infinite loop.
- Measured: near-linear speedup up to core count on the bench fixture, reported with the
  observed range.

**Tasks.**
- Work queue partitioned by class; minion pool with lifecycle and recycling per worker.
- Thread-safe result collection; sort before reporting.
- Infinite-loop fixture; per-worker kill and restart.
- Differential runs at 1, 2 and 8 threads; benchmark.

### M8b · Schemata engine

**Goal.** Compile once, with all mutants present as guarded branches and one active at a time.

**Definition of done.**
- **First task is Spike A, still unanswered:** insert 1, 10, 100 and 1000 guarded mutants into
  one method and one class, and record where the 64KB per-method bytecode limit and the 64K
  constant-pool limit actually bite. The budget that measurement produces constrains everything
  below, so it comes before any design.
- Schemata transformer honours that budget; a class that exceeds it is **split across schemata
  groups** rather than failing, and the split is invisible in results.
- The active-mutant switch is per-thread, not global, so tests that spawn threads behave. A
  Tier A fixture with a thread-spawning test proves it.
- Mutants that cannot fire under schemata are routed to the existing per-mutant redefinition
  path automatically. A test asserts none are silently dropped — which is the specific failure
  mode to fear here, because a dropped mutant looks like a smaller inventory rather than a bug.
- Static initialisers keep the fresh-JVM treatment they have now.
- Bytecode verification passes for every transformed class across both fixtures, asserted by
  running the verifier rather than by the absence of crashes.
- Standing gates pass, and `--engine=naive` still selects the reference engine.
- Measured: at least 3x on the mutant-execution phase against the reference engine on S1.

**Tasks.**
- Spike A and its written budget.
- Transformer, per-thread switch, group splitting, routing for ineligible mutants.
- Drop-detection test; verifier sweep; thread-safety fixture.
- Benchmark and publish.

## M9 · Warm daemon and state reset

**Not started.**

**Goal.** Amortise JVM startup, class loading, and JIT warmup across mutants and across
invocations — soundly.

**Definition of done.**
- Daemon owned by jzap, keyed on a hash of (module set, classpath, toolchain, engine
  version), on a local socket, with an idle timeout. Reachable identically from CLI and
  (later) both build plugins.
- Static-state reset between mutants via bytecode transformation, per Spike B. A
  dedicated **soundness suite** of fixtures that leak state (static counters, memoising
  caches, lazily initialised singletons, system properties, registered shutdown hooks)
  produces identical verdicts run in-daemon and in one-JVM-per-mutant mode.
- Classes whose state cannot be reset are detected and escalated to process isolation
  automatically; the escalation is logged and counted in the report.
- Staleness safety: daemon drops any module whose class bytecode hash moved, rather than
  patching in place. A fixture proves a changed class cannot yield a stale verdict.
- Concurrent invocations against one daemon are serialised or isolated, with a test
  proving no cross-talk.
- Standing gates pass, **and** the same verdicts are produced with the daemon cold and
  warm.
- **≥2× on S2 versus S1, and ≥5× on S4 versus S3.**
- **First kill-criteria checkpoint** evaluated and recorded.

**Tasks.**
- Daemon process, protocol, keying, idle shutdown, discovery/handshake with version
  check.
- State-reset transformer; reset-failure detection and escalation path.
- Soundness fixture suite; staleness and concurrency tests.
- Benchmark; evaluate kill criteria; publish.

## M10 · Coverage-driven selection, ordering, and hit-probe timeouts

**Not started.**

**Goal.** Run the fewest tests that can decide each mutant, and decide hangs
deterministically.

**Definition of done.**
- Test selection at block granularity from the M5 coverage map.
- Kill-test-first ordering from history: the test that previously killed a mutant runs
  first.
- Early exit retained and measured.
- **Hit-probe infinite-loop detection is the primary hang signal**, with wall-clock
  timeout only as a backstop. This is required for determinism and therefore for Gradle
  build-cache correctness (see [architecture.md](architecture.md)).
- `TIMED_OUT` is reported as a distinct status and is **excluded from cached results**.
- Standing gates pass.
- **Test executions per mutant reduced ≥50% versus selection disabled**, on Tier B and
  S1 — a machine-independent number, so it becomes a CI gate.

**Tasks.**
- Block-level selection; history-backed ordering.
- Hit-probe instrumentation and loop heuristic; calibrate against Tier A loop fixtures.
- Wire `TIMED_OUT` exclusion into the cache contract.
- Benchmark; add the selection-quality CI gate.

## M11 · Incremental cache

**Not started.**

**Goal.** Reuse prior verdicts safely across runs and machines.

**Definition of done.**
- Cache keyed on content hashes: class bytecode, test bytecode, mutator set, **the enabled
  filter set**, engine version, model-relevant config. Key composition documented. The filter
  set is load-bearing: `--mutate-loop-counters` changes the inventory, so a cache that ignored
  it would serve verdicts for a different set of mutants.
- Invalidation rules implemented and individually tested, in the spirit of StrykerJS's:
  a killed mutant's result survives only if its killing test still exists unchanged; an
  unkilled mutant's result survives only if no new covering test appeared and no covering
  test changed.
- Human-readable cache format (arcmutate's plain-text history is the precedent; PIT's
  opaque file is the anti-precedent).
- Cross-machine portability limits **documented and enforced**: bytecode differs across
  `javac` versions and platforms, so a cache records the toolchain that produced it and
  refuses to be used under a mismatched one rather than returning wrong answers.
- `TIMED_OUT` and `RUN_ERROR` never cached.
- Standing gates pass, **and** cold-cache and warm-cache runs produce identical verdicts
  on Tier A and Tier B.
- **S5 (no changes) ≤5% of S1 wall clock; S6 (one-line change) invalidates only the
  mutants provably affected**, asserted by an exact expected-invalidation-set test, not
  by a count.

**Tasks.**
- Cache format, key composition, toolchain fingerprinting and mismatch refusal.
- Invalidation engine + one test per rule.
- Exact-invalidation-set fixtures; cold/warm differential run in CI.
- Benchmark S5/S6.

## M12 · Mutant reduction

**Not started.**

**Goal.** Cut the mutant set without cutting usefulness — and quantify the tradeoff
honestly.

**Definition of done.**
- Arid-node suppression implemented, following Google's approach: an AST/IR-level
  heuristic marking uninteresting nodes (logging, boilerplate, generated members).
  Rules are data, not code, so they can be tuned without a release.
- One-mutant-per-line (and per-block) modes available.
- TCE-style dedup: normalise/compile mutants and compare bytecode to drop equivalent and
  duplicated mutants. Expect roughly ~11% for Java per the literature; **report the
  actual measured figure for our corpus.**
- Extreme-mutation fast tier available as an explicit mode (Descartes is the precedent:
  8.8× fewer mutants gave 10.6× less time on Flink core).
- **A quantified detection-loss report**, per reduction technique and cumulatively: how
  many mutants that survive under the full set are no longer generated, and therefore how
  many real test-suite gaps go unreported. Published alongside the speed figures, with
  equal prominence.
- Fairness rule enforced in the harness: reduced-set timings are never compared against
  PIT's full set on a bare time axis.
- Every reduction technique individually toggleable, with `--no-reduction` reproducing
  M10 behaviour exactly (asserted).
- **Second kill-criteria checkpoint** evaluated and recorded.

**Tasks.**
- Arid-node rule engine + rule set; per-line/per-block modes.
- TCE dedup pipeline; measure actual reduction on the corpus.
- Extreme-mutation mode.
- Detection-loss measurement harness; publish speed and loss together.
- Evaluate kill criteria.

---

# Phase 3 — Delta and reporting

## M13 · Git diff scoping

**Done ahead of schedule.** Git ranges, `-Local-`, `-Empty-`, patch files, line and class granularity, and the engine proven to work with JGit absent. The edge-case fixture repos cover renames, deletions and clean trees; submodules and shallow clones are not yet covered.

**Goal.** Line-level diff scoping — the product's core differentiator, since free
line-level diff mutation testing does not exist for Gradle projects today.

**Definition of done.**
- `jzap-git` (JGit) resolves `from`/`to` refs including `-Local-` (staged + unstaged) and
  `-Empty-`, matching arcmutate's semantics.
- Three modes: change-based (changed lines), test-based (classes exercised by changed
  tests), mixed.
- `scope` widening to whole changed classes available, as arcmutate's `scope[class]` is.
- **Core never depends on `jzap-git`**: it accepts line ranges only, so a CI system can
  hand over a patch file instead. Asserted by the M0 dependency check and by a test that
  runs a diff-scoped analysis from a patch file with JGit absent from the classpath.
- The "analysis runs against current code, the git range only selects scope" semantic is
  implemented and **stated in the CLI output**, because both Mull and arcmutate document
  this as a live source of misleading results.
- Fixture repositories covering: renames, file moves, whitespace-only changes, mode
  changes, merge commits, shallow clones, detached HEAD, submodules, and a changed file
  containing only an interface (the shape that breaks PIT's `scmMutationCoverage`,
  [issue #306](https://github.com/hcoles/pitest/issues/306)).
- Comparison against arcmutate `+GIT` scoping on shared fixtures where a licence permits;
  otherwise the semantics are pinned by our own hand-written expectations.
- **S3/S4 measured** against PIT `scmMutationCoverage` (file-granularity) with the
  granularity difference stated explicitly in the writeup.

**Tasks.**
- JGit ref resolution; diff → line range mapping; the three modes.
- Line-range → method/block mapping via line-number tables.
- Patch-file input path; no-JGit-on-classpath test.
- Fixture repos for every listed edge case; benchmark S3/S4.

## M14 · Reporters

**Done.** Console, native JSON, mutation-testing-elements, self-contained HTML, PR annotations. Elements output is not yet schema-validated in CI, and there is no CI to validate it in.

**Goal.** Output that existing tooling and code review already understand.

**Definition of done.**
- `mutation-testing-elements` JSON emitted and **validated against the published schema
  in CI**, then confirmed to render in the standard viewer.
- Self-contained HTML report with source view, per-mutant diff, and survivor navigation.
- `gitci`-style JSON for PR annotation, modelled on arcmutate's (the proven format), with
  a severity level.
- Native jzap JSON: the full detail record, the harness's input, versioned.
- JUnit-XML-ish CI summary and a non-zero exit on threshold breach, with thresholds
  configurable per scope (full run versus diff run need different numbers).
- Reports are deterministic byte-for-byte apart from a designated timing block, asserted.
- Mutant descriptions are source-faithful for Java; Kotlin descriptions are marked
  provisional until M15.

**Tasks.**
- Elements JSON writer + schema validation in CI + viewer smoke test.
- HTML report; gitci writer; native JSON; threshold logic and exit codes.
- Determinism assertions.

---

# Phase 4 — Kotlin

This is where jzap earns its existence rather than merely matching PIT. Tier C corpus and
the junk-mutant taxonomy are the measures.

## M15 · Kotlin junk-mutant handling

**Not started.**

**Goal.** Do not generate junk mutants from compiler-generated constructs, rather than
filtering them after the fact.

**Definition of done.**
- A written **junk taxonomy** with a Tier A fixture per entry: `Intrinsics` null checks,
  data-class `equals`/`hashCode`/`copy`/`componentN`, `when` tables, default-argument
  synthetics (`$default`), coroutine state machines and `suspend` continuations,
  delegated properties, `lateinit` checks, value-class boxing, sealed-hierarchy
  dispatch, `object`/companion accessors, extension-function receivers, operator
  overloads, bridge and synthetic members.
- Zero junk mutants on the taxonomy corpus, asserted per entry.
- kotlin-metadata read to drive decisions where bytecode alone is ambiguous, **without
  putting kotlin-stdlib on the agent classpath** (per ADR 0001) — verified by the M0
  leaked-package test.
- Aggressive null-check filtering available as an opt-in mode, as arcmutate's
  `+KOTLIN_NO_NULLS` is.
- **Mutant-quality comparison on Tier C against PIT bare and PIT + pitest-kotlin**: for
  each tool, the count of mutants a reviewer classifies as junk, from a blind sample of
  200 mutants scored against the taxonomy. jzap must be lower than both.
- Mutant descriptions on Tier C are source-faithful; no longer provisional.

**Tasks.**
- Junk taxonomy document + fixture per entry.
- Detection rules; kotlin-metadata reader with dependency hygiene.
- Opt-in null-filtering mode.
- Blind-sample scoring protocol and the three-way comparison.

## M16 · Kotlin inline functions

**Not started.**

**Goal.** Correct mutants for inline functions, whose bodies are copied into every caller
and whose bytecode omits some instructions entirely.

**Definition of done.**
- Inline function bodies mutated with mutants seeded into the **inlined copies** in
  callers, deduplicated back to one logical mutant at the declaration site so reports are
  not flooded with per-call-site duplicates.
- Reported source location points at the inline function's own source, not the caller's.
- `reified`, `crossinline`, `noinline`, and inline lambdas covered by Tier A fixtures.
- Analysis cost bounded with a documented default (arcmutate skips inline methods over
  500 instructions; ours states its own limit and the reason), and exceeding it degrades
  to "not mutated" with an explicit report entry rather than silently.
- Comparison against PIT bare (which loses these mutants) and arcmutate's Kotlin plugin
  where licensed: jzap generates mutants PIT does not, and Tier A fixtures with
  hand-written expectations prove they are legitimate and killable.
- Spike C's finding honoured: if bytecode-only analysis proved insufficient, M17 is a
  prerequisite and this milestone is resequenced accordingly.

**Tasks.**
- Inline-copy detection and correlation back to the declaration.
- Mutant dedup across call sites; source-location remapping.
- Fixtures for each inline flavour; cost bound + explicit degradation reporting.
- Three-way comparison.

## M17 · Kotlin IR frontend (optional)

**Not started.**

**Goal.** Source-faithful mutant selection and description via a K2 compiler plugin,
degrading cleanly when absent.

**Definition of done.**
- K2 compiler plugin contributes mutation-point metadata consumed by the core through a
  source-model SPI.
- **Absent plugin degrades to M15/M16 bytecode behaviour**, asserted by running the whole
  Kotlin corpus both ways; the plugin changes mutant *quality*, never *correctness*.
- Compiler-version coupling managed explicitly: a supported-Kotlin-version matrix in CI,
  and a clear error, never a crash, on an unsupported version. mutflow's pinning to a
  single Kotlin version is the failure mode to avoid.
- Arid-node suppression (M12) upgraded to use real IR rather than bytecode heuristics,
  with the improvement measured.
- Documented as optional, with a plain statement of what it buys and what it costs.

**Tasks.**
- Compiler plugin; source-model SPI and serialisation.
- Degradation differential test; Kotlin version matrix in CI.
- Rewire arid-node rules onto IR; measure.

---

# Phase 5 — Build tools and scale

## M18 · Gradle plugin

**Not started.**

**Goal.** First-class Gradle support, targeting the gap that free line-level diff mutation
testing does not exist there
([gradle-pitest-plugin #165](https://github.com/szpak/gradle-pitest-plugin/issues/165)).

**Definition of done.**
- Plugin computes the project model from source sets, configurations, and toolchains, and
  hands it to the daemon. **The plugin contains no analysis logic**, asserted by the M0
  dependency check.
- Per-project task **and** an aggregate root task emitting a single multi-module model.
- Full Gradle correctness: configuration cache compatible, up-to-date checks with
  declared inputs and outputs, build-cache relocatable, lazy task configuration, no
  cross-project access at execution time.
- Engine version overridable independently of the plugin version, as
  gradle-pitest-plugin's `pitestVersion` is.
- Classpath passed by file, not as arguments (Windows length limits), as
  gradle-pitest-plugin does by default.
- Android and Kotlin Multiplatform: either supported with tests, or **documented as
  unsupported with the reason**. No silent partial behaviour.
- Model-conformance suite: the same fixture project through the Gradle adapter and a
  hand-written model produces identical models modulo absolute paths.
- Build-cache correctness test: run, clean, re-run from cache, assert identical report —
  the test that catches any residual nondeterminism from M10.

**Tasks.**
- Plugin, tasks, model computation, toolchain resolution.
- Configuration-cache and build-cache compliance; TestKit tests across supported Gradle
  versions.
- Conformance suite; cache-correctness test; Android/KMP decision + docs.

## M19 · Maven plugin

**Not started.**

**Goal.** Maven parity, reusing the same seam.

**Definition of done.**
- Mojo computes the model from the reactor and dependency resolution, respecting scopes.
- Reactor-wide single invocation available, not only per-module.
- Model-conformance suite passes: Gradle adapter and Maven adapter produce identical
  models for equivalent fixture projects, modulo paths. This is the check that stops
  adapter drift from masquerading as engine bugs.
- Migration note for PIT users: config mapping from `pitest-maven` including
  `scmMutationCoverage`, with a worked example.
- Comparison run: the same Maven project under `pitest-maven scmMutationCoverage` and
  under jzap, with both correctness and performance reported.

**Tasks.**
- Mojo + model computation; reactor handling; ITs via maven-invoker.
- Cross-adapter conformance; migration doc; comparison run.

## M20 · Multi-module single run

**Not started.**

**Goal.** One invocation over a whole reactor, with cross-module test selection and the
daemon warmed once — the thing PIT supports only partially and only with explicit
configuration.

**Definition of done.**
- Cross-module test selection works: a mutant in module A is killed by a test in module
  B, with a fixture proving it and the same case demonstrated failing or requiring manual
  configuration under PIT.
- Module dependency graph respected for classpath construction and scheduling.
- Coverage cache shared across modules within a run.
- Scheduling keeps cores busy across modules; no serialisation on the slowest module.
- **S7 measured**; daemon warmed once for the whole reactor, asserted by process count.
- Scales to a documented reactor size (state the largest tested, with numbers).

**Tasks.**
- Multi-module model consumption; graph-aware classpath and scheduling.
- Shared cross-module coverage cache; work-stealing scheduler.
- Large-reactor fixture; benchmark S7.

---

# Phase 6 — Hardening and 1.0

## M21 · Published comparison against PIT and arcmutate

**Not started.**

**Goal.** The headline claims, measured, reproducible, and fair.

**Definition of done.**
- Full S1–S8 results published for jzap, PIT default, PIT + history, PIT
  `scmMutationCoverage`, and arcmutate where licensed (otherwise explicitly *unmeasured*,
  never estimated).
- Median with min–max range over 5 runs; dedicated machine; procedure documented well
  enough for a third party to reproduce.
- Correctness published alongside speed: the final agreement matrix, the full
  `parity-baseline.yaml` with justifications, and the Tier C mutant-quality comparison.
- **Reduction techniques' detection loss published with equal prominence to their speed
  gain.**
- **Every scenario where jzap loses is published with the same prominence as the wins.**
- Harness and all configuration public so the numbers can be independently rerun.

**Tasks.**
- Final bench runs; results writeup; reproduction instructions.
- Publish parity artefacts; third-party reproduction attempt before publication.

## M22 · 1.0 hardening

**Not started.**

**Goal.** A tool someone else can adopt without talking to us.

**Definition of done.**
- Error messages: every failure mode names what failed, why, and the next action. A
  reviewed catalog of the top 20 failure modes, each with a test asserting the message.
- The classpath problems that dominate PIT's support load are handled explicitly: no
  mutations found, tests on the classpath that are not meant to run, missing test
  dependencies, module-path versus classpath confusion — each with a diagnostic that
  names the cause.
- Docs: quickstart per build tool, mutator reference, diff-mode guide, CI recipes for
  GitHub/GitLab, migration from PIT, troubleshooting, performance-tuning guide.
- Compatibility matrix published: JDK, Gradle, Maven, Kotlin, JUnit, TestNG versions.
- Semantic-versioning policy covering the model schema, the cache format, the mutator
  SPI, and the daemon protocol — and what a breaking change to each means for users.
- Release automation: signed artefacts, Maven Central, Gradle Plugin Portal,
  reproducible builds.
- Public parity and benchmark dashboards updated per release.
- A 30-day dogfood period on a real external project, with findings triaged and
  blockers closed.

**Tasks.**
- Error-message catalog + tests; diagnostics for the known classpath failure modes.
- Documentation set; compatibility matrix; versioning policy.
- Release pipeline; dashboards; dogfood and triage.

---

## What building it changed about this plan

Recorded here rather than silently edited in, because the differences are the useful part.

- **M1's spikes were folded into the build.** Writing the reference engine answered more than a
  prototype would have, and left exactly one spike genuinely outstanding: the schemata size
  budget, which is now M8b's first task.
- **M13 and M14 arrived early.** Diff scoping and reporting turned out to be independent of the
  execution engine, so they were built alongside it. The plan already allowed this; it happened
  sooner than expected.
- **Two mutant filters appeared that the plan did not anticipate**, both found by comparing
  against PIT rather than by design: no-op return mutants, which can never be killed, and loop
  counters, which are killed 79 times out of 80 and cost a third of the run time. The second
  moved jzap from 1.64x slower than PIT to 1.5x faster. M12's reduction work now has a
  precedent to follow: measure the information a mutant class carries, not just its cost.
- **Parallelism was an afterthought in M8 and should not have been.** It is the cheapest large
  speedup available and it is now M8a, ahead of schemata.
- **Coverage shipped at line granularity, not block.** That was a deliberate scope cut, and the
  cost is that test selection is broader than necessary. M10 remains as written.
- **The benchmark harness needed a guard the plan did not specify.** Its first version reported
  a 158x diff-run speedup, which was entirely an artefact of the synthetic patch landing on a
  line carrying no mutants. It now derives the target line from the inventory and refuses to
  print a figure when nothing is in scope. Any future scenario needs the same treatment: a
  benchmark that cannot fail is not measuring anything.

## Milestone dependency summary

    M0 -> M1 -> M2 -> M3 -> M4 -> M5 -> M6 -> M7
                                            |
                          +-----------------+-----------------+
                          v                                   v
                    M8 -> M9 -> M10 -> M11 -> M12        M13 -> M14
                          |                                   |
                          +--------------+--------------------+
                                         v
                              M15 -> M16 -> M17 (optional)
                                         |
                                         v
                                   M18 -> M19 -> M20
                                         |
                                         v
                                   M21 -> M22

- M13/M14 can run in parallel with Phase 2 once M7 exists, since diff scoping and
  reporting touch the scope resolver and output layer rather than the execution engine.
- M17 is optional and gated on Spike C's finding; if bytecode analysis proved
  insufficient for inline functions, it moves ahead of M16.
- M18 must not start before M11, because Gradle build-cache correctness depends on the
  determinism guarantees established in M10 and M11.
