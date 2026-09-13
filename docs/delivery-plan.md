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

**Done.** Measured on 40 generated classes: 10.07s at one thread, 6.09s at two, 4.98s at four,
4.86s at twenty. Sublinear because the coverage phase is serial and the fixture has only 40
classes, so past a handful of workers each pays JVM startup for very little work.

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

**Done, and the size budget turned out not to be a constraint.**

Every mutant of a class is compiled in at once and selected by a field write, so a mutant costs a
volatile store rather than a class redefinition -- which makes the JVM discard the class,
re-verify it, and throw away its JIT-compiled code, once per mutant. Default engine;
`--engine=naive` still selects the reference implementation.

**Branch-free, which is the design decision that matters.** The obvious encoding guards each
mutant with an `if` inside the mutated method. That adds jumps, which adds stack map frames, which
means `COMPUTE_FRAMES`, which means loading classes to compute common supertypes -- and an analysis
that fails whenever the transformer cannot fully resolve the classpath. Instead each mutable
operation becomes a call to a static dispatch method that takes the operands and the mutant ids
and decides what to return. Control flow is untouched, existing frames stay valid, `COMPUTE_MAXS`
is enough, and no class is ever loaded to transform another.

**Spike A, answered by measurement rather than by design:** the budget is not a constraint with
this encoding. Each site grows by roughly a constant push and an invocation, so the 64KB method
limit needs thousands of mutation points in a single method before it matters. The class-splitting
strategy the plan called for was never needed.

`VOID_METHOD_CALLS` removes a call, which cannot be expressed without a branch, so those mutants
are routed to per-mutant redefinition. A test asserts every mutant is either compiled in or routed,
because silently losing one would look like a smaller inventory rather than a bug.

**Measured: 2.48x on the execution phase** (22.35s to 9.03s on the bench fixture), 1.97x on the
whole run. **The definition of done asked for 3x and this is 2.48x.** The remaining per-mutant cost
is not redefinition any more; it is starting a JUnit launcher execution per test. That is what M9's
warm daemon and a batched execution path would address, and it is why the target was missed rather
than met by adjusting the target.

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

**Done, narrower than planned, and the narrowing is the interesting part.**

**The daemon.** `jzap run --daemon` hands the invocation to a resident jzap, starting one if there
is none; `jzap daemon --status`/`--stop` manage it. One daemon per project model, keyed by the
model's absolute path, exiting on its own after thirty minutes idle so a forgotten daemon is a
temporary condition. It runs the very same command with `--daemon` dropped, so there is one
implementation and behaviour cannot drift between the two paths.

Measured on a warm-cache run: **0.35s to 0.17s**. That is the tool's own startup -- JVM, class
loading, JIT warmup -- which on a cached run is most of the wall clock, because the analysis itself
is a cache read.

**What it deliberately does not do** is keep analysis JVMs alive between invocations. That would
save more, and it would mean holding the code under test loaded in a process that outlives the
build which produced it. The plan asked for state reset to make that safe; the measurement changed
the shape of the problem, because the incremental cache had already removed most of the cost the
warm-JVM reuse was meant to remove.

**State reset, replaced by measurement.** Rather than building UniAPR-style static-state reset on
the assumption it is needed, jzap now *tests* whether reuse is sound: every fixture is analysed
with one JVM per mutant and with the default bounded reuse, and every verdict must match. There is
a fixture written to leak -- a running counter and a memoised field -- specifically so the test
would fail if bounded recycling were not enough. It passes. If a real project ever fails that gate,
the reset machinery is what to build, and there will be a case that demonstrates the need.

This also matters more since M8b than it did when the plan was written: schemata no longer
redefines a class per mutant, so an analysis JVM lives through more mutants than it used to.

Outstanding from the original definition of done: analysis-JVM reuse across invocations, the
escalation path for classes whose state cannot be reset, and concurrent invocations against one
daemon.

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

**Two of three done; the third measured and deliberately not built.** Kill-test-first ordering from
the cache, and hit-probe loop detection as the primary hang signal. Coverage is keyed by
**(class, method, line)** — finer than the (class, line) it started as, which M16 forced and which
Java lambdas needed anyway — but not by basic block.

**Why block granularity was not built.** Its purpose is to select fewer tests per mutant. Measured
across all four fixtures, the tests actually *run* per mutant are already at the floor:

| Fixture | covering tests per mutant | tests run per mutant |
|---|---|---|
| bench (1080 mutants) | 1.00 | 1.00 |
| Kotlin | 1.00 | 1.00 |
| multi-module | 1.00 | 1.00 |
| Java sample | 1.67 | 1.11 |

Kill-test-first ordering and early exit already reach what finer coverage would aim at: the killing
test runs first and nothing after it runs at all. Block granularity would still narrow the
*covering* set, and that matters in one specific case this repository has no fixture for — a
project with broad integration tests and many surviving mutants, where every covering test runs
because none of them kills. The honest statement is that the benefit is bounded by
(survivors x covering tests saved), and on everything measurable here that product is near zero.

Building it would mean a third cache-format change and a block-numbering scheme that two separate
bytecode passes have to agree on exactly — the same class of hazard as the schemata ordinals, for a
benefit that cannot currently be demonstrated. It is recorded here so the next person reaches for it
when they have a project where the numbers above look different, rather than on principle.

Loop detection counts back edges rather than watching the clock. The limit is ten times what the
unmutated code needed on the same tests, measured during the coverage run, with a floor so code
that barely loops still has room. The wall-clock timeout stays as a backstop for what counting
cannot see: a mutant that blocks rather than loops, or code that catches `Throwable`.

The payoff is determinism. A run with a one-millisecond wall-clock budget and one with thirty
seconds now produce byte-identical reports, which is the property the Gradle build cache needs
and which no amount of timing tolerance could have given.

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

**Done, with one DoD item outstanding.** Plain-text cache keyed on content hashes, the
invalidation rules below implemented and individually tested, toolchain mismatch refused,
timeouts and run errors never stored, twelve end-to-end tests comparing cached runs against
uncached ones rather than against recorded expectations.

Outstanding: exact-invalidation-set assertions by mutant key. The tests assert invalidation
*scope* (only the changed class's mutants are re-analysed) rather than enumerating the exact
expected set.

One thing this milestone taught: **caching verdicts alone is close to pointless.** A fully
cached run still executed the whole test suite once to rediscover coverage, which left the
warm run at 6.5% of a full run rather than the 5% target -- and on a real project with a slow
suite it would have been most of the cost. The coverage map is now cached too, keyed on every
scanned class and every test class, and reused only when the stored map covers every class the
current run needs. A map recorded during a narrow diff run must not be mistaken for a complete
one: reusing it would silently report every mutant in the missing classes as uncovered.

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

**Done.** Four techniques, all off by default, all measured together with what they cost:
arid-node suppression, one-mutant-per-line, TCE dedup, and extreme mutation. Measured with
`./gradlew :tools:bench:reduction`, which reports speed and detection loss side by side because
a reduction figure without its loss figure is an advertisement rather than a result.

Measured on the bench fixture (1080 mutants, 125 survivors):

| technique | mutants | time | speedup | survivors | lost |
|---|---|---|---|---|---|
| baseline | 1080 | 19.1s | 1.00x | 125 | 0 |
| dedup | 1080 | 19.1s | 1.00x | 125 | 0 |
| arid | 1080 | 19.1s | 1.00x | 125 | 0 |
| one-per-line | 520 | 9.9s | 1.92x | 82 | **43 (34%)** |
| extreme mutation | 240 | 4.9s | 3.87x | 0 | n/a |

Two findings worth more than the feature itself:

- **TCE finds nothing on javac output with this mutator set, by construction.** It compares
  compiled forms, so it can only catch a mutant whose bytecode is identical to the original's or
  to another's. javac folds almost nothing, and no two of the ten default mutators can produce the
  same instruction in the same place. The published "about 11% of Java mutants" figure was
  measured with a larger set containing operators that overlap, so it does not transfer. The
  filter is kept because it costs nothing when off and will matter where a compiler does fold --
  Kotlin's does considerably more -- or with a user-supplied set containing redundant operators.
- **One-per-line's real price is 34% of the findings.** It halves the mutant count and nearly
  doubles the speed, and it stops reporting 43 of 125 genuine gaps. Google adopted it anyway,
  which is defensible at their scale; stating the number is what lets anyone else decide.

Extreme mutation found no survivors at all on this fixture, which is the pseudo-tested-method
signal working: every method there is checked for doing *something*, and the finer mutators are
what find the 125 gaps in *what* it does.

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

**Done.** A Kotlin fixture, a junk taxonomy derived from reading the bytecode kotlinc actually
emits, and filters for each entry -- all gated on the class carrying `kotlin.Metadata`, so Java
classes and the PIT comparison are untouched. Switchable with `--mutate-kotlin-internals`.

The rules, each traced to a specific construct in the compiled output:

- **Generated property accessors.** `val id: String` has no getter body to get wrong.
- **Data class members.** `componentN`, `copy`, `copy$default`, and `equals`/`hashCode`/
  `toString` on a class that has `componentN` methods.
- **Null-check intrinsics.** Removing a `kotlin.jvm.internal.Intrinsics` call is not a mistake
  anyone can make.
- **For-each loop scaffolding.** `for (x in xs)` compiles to an `Iterator.hasNext` check.
  Negating it makes the loop skip everything or never end, which is the same uninformative
  outcome as mutating a loop counter in Java -- and the Java loop-counter filter cannot see it,
  because a Kotlin for-each loop has no increment.

Worth recording: the intrinsics and most data-class members were **already** being dropped, but
by accident -- they sit before the first line-number entry, and mutants with no line were being
filtered for having nowhere to point. A rule that states the intent stops that from regressing
silently the day something gives them a line.

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

**Done, and it needed a coverage change nobody had planned for.**

kotlinc emits a real method at the declaration *and* copies the body into every Kotlin call site,
under synthetic line numbers past the end of the file -- one distinct range per call site. So the
same source line exists in the compiled output three times over in the fixture, and none of the
three behaved correctly:

- Mutants in the inlined copies pointed at source lines that do not exist. Fixed by parsing the
  `SourceDebugExtension` attribute, the JSR-045 SMAP table, and translating each synthetic line
  back to the line it was copied from. Only the first stratum is read: the `KotlinDebug` stratum
  that follows maps the same output lines to the *call sites*, which is what a debugger wants and
  the opposite of what a report wants.
- After translation, an inlined copy and its declaration reported the same class and line, so
  they shared a coverage probe. A test exercising a call site made the unreachable declaration
  look covered. Fixed by keying coverage on **(class, method, line)** rather than (class, line) --
  which is a real improvement for Java too, where lambdas raise the same question more quietly.
- The declaration's own method now correctly reports NO_COVERAGE: Kotlin callers inline the body,
  so the emitted method never runs. That is the honest verdict, and it is what arcmutate documents
  as the reason bytecode mutation of inline functions is hard.

jzap does **not** deduplicate the copies across call sites, where arcmutate does. Two call sites
are genuinely two pieces of compiled code and a test may kill one and not the other; with the line
numbers now correct, reporting both is more information rather than noise. The description says
which are inlined copies so a reader knows why the same line appears twice.

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

## M16b · Kotest, and other JUnit Platform engines

**Done, and the failure it predicted was real.**

Kotest runs on the JUnit Platform, so discovery found its engine immediately and the first run
completed without error: 7 mutants, all reported as uncovered, mutation score 0.0%. Nothing
failed. That is precisely the silent degradation this milestone was written to catch.

The cause is that **Kotest builds its test tree when a spec runs, not when the platform asks what
the spec contains.** Discovery returns one container per spec and no leaf descriptors at all;
the leaves appear only during execution. jzap collected only `isTest()` descriptors, found none,
and concluded there were no tests.

The fix is to stop assuming the shape of what an engine exposes. The unit jzap runs is now a leaf
where the engine offers one and a childless container otherwise. For Kotest that means selection
works at **spec granularity**: coarser than a single test, and running one unit costs no more than
running that spec normally would.

Covered by fixtures in all four spec styles -- `StringSpec`, `FunSpec`, `DescribeSpec`,
`BehaviorSpec` -- with a deliberate gap the analysis has to find, and an assertion that unit ids
are stable across runs, since the cache keys killing tests by unique id.

Still outstanding from the original definition of done: the isolation-mode matrix
(`InstancePerTest`, `InstancePerLeaf`), coroutine and thread-hopping fixtures for the probe and
guard, and the measurement of project-level hook cost. Per-leaf selection would need a two-phase
discovery -- run once to learn the tree, then select leaves by unique id -- which is worth doing
only if spec granularity proves too coarse on a real project.

**Goal.** Analyse projects whose tests are written in Kotest, with per-test selection, coverage
and caching working exactly as they do for JUnit Jupiter.

Kotest runs on the JUnit Platform, so discovery finds it already. That is not the same as
supporting it. jzap executes one test at a time, addressed by unique id, and everything it does
rests on that: per-test coverage, test selection, early exit, kill-test-first ordering, and the
cache's notion of which test killed which mutant. A framework that cannot be driven a single test
at a time degrades all of it silently rather than loudly, which is the failure mode to design
against.

**Definition of done.**

- Kotest fixtures in the main spec styles — `StringSpec`, `FunSpec`, `DescribeSpec`,
  `BehaviorSpec` — each with hand-written expected verdicts, analysed end to end.
- **One leaf test runs, not the whole spec.** Asserted directly, by checking that a mutant covered
  by one leaf is not reported as covered by its siblings. If `selectUniqueId` silently falls back
  to running an entire spec, every mutant in the file appears covered by every test in it: the
  score stays plausible, the selection collapses, and nothing fails.
- All three isolation modes covered by fixtures — `SingleInstance` (the default),
  `InstancePerTest`, `InstancePerLeaf` — producing identical verdicts. Isolation mode changes how
  often a spec is instantiated, which is exactly the kind of per-test state the minion recycling
  policy exists to bound.
- **Unique ids are stable across runs.** Asserted by discovering twice and comparing. The cache
  keys killing tests by unique id, so an id that varies between runs would silently disable reuse,
  or worse, match the wrong test.
- Coroutines: coverage probes and the runaway-loop guard work across suspension points and across
  whatever threads Kotest dispatches onto. Both are static and global precisely so a test that
  hops threads still records; there is a fixture that hops threads to prove it.
- Project-level configuration (`AbstractProjectConfig`, `beforeProject`/`afterProject`) is
  measured, not assumed. jzap runs the launcher once per test, so a hook that Kotest intends to
  run once per engine execution may run once per test instead. Either it is cheap and harmless,
  or the coverage phase needs a batched mode — the milestone decides which, with numbers.
- Baseline duration and loop-iteration counts recorded per Kotest leaf, as for Jupiter.
- A Kotest project and an equivalent Jupiter project over the same production code produce the
  same verdicts. This is the real check: the framework should not be able to change what a mutant
  means.
- Supported Kotest versions documented, with a clear error rather than a crash on an unsupported
  one.
- `docs/status.md` states plainly which spec styles and isolation modes are covered.

**Tasks.**

- Kotest fixture module, one spec per style, plus an isolation-mode matrix.
- Verify `selectUniqueId` selection against Kotest's engine; if it does not support single-leaf
  selection, decide between a class-level fallback with documented coarser selection and a
  `jzap-testkit` adapter that drives Kotest directly.
- Thread-hopping and coroutine fixtures for the probe and guard.
- Measure project-level hook cost per launcher execution; batch the coverage phase if it is not
  negligible.
- Cross-framework verdict comparison against an equivalent Jupiter project.
- Version matrix in CI; version check with an actionable message.

**Why it is its own milestone.** TestNG and JUnit 4 sit behind the same `jzap-testkit` SPI and
raise the same question in a milder form. Kotest is the one that matters for a Kotlin-focused
tool, and it is the one most likely to break the one-test-at-a-time assumption, so it gets the
fixtures and the measurements rather than an assumption that the platform makes it work.

## M17 · Kotlin IR frontend (optional)

**Not built, deliberately.** The plan marked it optional and gated on Spike C, and the bytecode work
of M15 and M16 answered the question it was meant to answer: Kotlin mutants are clean, inline
function bodies are mutated through their call sites, and the reported source lines are correct
because the SMAP table gives them exactly.

What the IR frontend would add is mutant *quality* at the margins — better arid-node decisions, and
descriptions phrased in Kotlin rather than in bytecode terms. What it would cost is a hard coupling
to compiler internals: mutflow, the closest existing example, is pinned to a single Kotlin version.
That is a poor trade while the bytecode path produces clean results, and it is a good trade the day
someone finds a construct the bytecode filters cannot distinguish. Nothing in the current fixtures
is that construct.

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

**Mostly done.** Plugin id `io.github.huyz0.jzap`, tasks `mutationTest` and `mutationTestDiff`, model computed
from source sets and toolchains, engine forked with its version independent of the plugin's,
up-to-date checks working, configuration cache compatible, verified by seven TestKit tests
including a conformance check that the model the plugin writes drives the engine on its own.

Outstanding: the aggregate root task emitting one multi-module model (that is M20's work), the
build-cache relocatability check (waits on M11's content hashing, since the report embeds phase
timings), and the Android and Kotlin Multiplatform decision.

Three things this milestone taught, recorded because they are not obvious:

- Gradle's Groovy DSL turns `threshold = 80.0` into a `BigDecimal`, which will not assign to a
  `Property<Double>`, and Gradle forbids declaring a setter next to an abstract property getter.
  The obvious way to write the obvious thing fails with a type error unless the property is a
  plain scalar with a `Number` setter.
- An unresolvable engine cannot be reported with a better message than Gradle's own without
  giving up configuration-cache compatibility. `@Classpath` inputs are snapshotted before any
  action; an `@Internal` file collection still gains an implicit task dependency Gradle resolves
  first; and a non-file `Property` fed by a provider is finalised, and so queried, before the
  action too. Gradle's message already names the configuration and the coordinate.
- The conformance test worth writing is not "does the model look right" but "does the engine run
  from it unaided". That also gives users a way to reproduce a plugin problem with no Gradle in
  the loop.

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

**Done.** Goal `jzap:mutationCoverage`, bound to `verify` by default, computing the model from the
reactor's output directories, test classpath and compile source roots, and forking the engine
exactly as the Gradle plugin does. Verified by `./gradlew mavenSmokeTest`, which runs a real Maven
build against a generated project and checks a hand-derived expectation.

Built by Maven rather than by this repository's Gradle build, and deliberately not wired into
`build`: a Maven plugin needs a generated plugin descriptor and `maven-plugin-plugin` is what
generates it. Hand-writing that descriptor would work right up until it silently did not, and
making the ordinary build depend on Maven and a network to produce it is a worse trade than one
explicit task.

Outstanding: reactor-wide single invocation (the mojo is per-module; the engine supports the
multi-module case and the Gradle plugin exposes it, so this is wiring rather than capability),
the `mutators` parameter is accepted but not yet passed through, and the migration note for
`pitest-maven` users.

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

**Done.** The engine analyses every module in one pass rather than looping over them, so a test in
one module kills a mutant in another. The Gradle plugin gains `mutationTestAll`, which covers the
whole reactor in one invocation and is configuration-cache compatible.

This is the ordinary shape of a multi-module project and the case that module-at-a-time analysis
cannot see: a library module with no tests of its own reports every mutant as uncovered, and the
score is meaningless. The fixture is exactly that shape -- `multi-core` has no test sources at all,
and its mutants are killed by `multi-app`'s tests.

What it required beyond the loop:

- **Coverage runs once per test-bearing module**, with every in-scope class instrumented for every
  run. A class a module cannot see never loads and its probes never fire; a class it can see gets
  attributed correctly even though it was compiled next door.
- **A worker holds one analysis JVM per module**, started on demand, because a single mutant's
  covering tests can live in several modules and each needs its own classpath. Tests are run group
  by group, stopping at the first kill.
- **Modules that declare a test class path but have no compiled tests are skipped.** An aggregate
  run over a real reactor routinely includes one, and starting an analysis JVM on a classpath with
  no test framework loses the whole run to a module that had nothing to contribute.
- The plugin's aggregate task collects module descriptions as each project is configured, rather
  than reaching across projects. Iterating other projects from a task registration fails outright
  inside an included build, and reaching across at execution time is what breaks the configuration
  cache.

Outstanding: work-stealing across modules is not scheduled explicitly -- the mutant queue is shared
and partitioned by class, which keeps cores busy, but a single very slow module still gates the
end of the run. No large-reactor scale test yet.

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

**Done for PIT; arcmutate remains unmeasured.**

Published in [bench-report.txt](bench-report.txt) and [reduction-report.txt](reduction-report.txt),
both generated by the harnesses in `tools/` so anyone can rerun them. Medians of three runs with
ranges, never a best-of figure. Correctness published alongside: 1051 shared mutants, 100% verdict
agreement, one triaged difference in `parity-baseline.yaml`.

**arcmutate is not measured, and is not estimated.** Its line-level diff scoping and improved
incremental analysis are the closest comparison that exists for what jzap does, and they are
commercial. Guessing at them would be worse than the gap.

**Where jzap loses, stated as prominently as where it wins:**

- The plan's diff-run kill criterion cannot be evaluated as written. PIT's free tier has no
  line-level diff mode to compare against.
- M8b's target was 3x on the execution phase; the measurement is 2.40x.
- Reduction techniques are worth less than the literature suggests here, and one of them is worth
  nothing at all: TCE drops zero mutants on javac output with this mutator set.
- Thread scaling stops improving past four workers on a 40-class fixture.

**One process note worth more than any of the numbers.** An earlier run of the reduction harness,
taken while a build was running alongside it, reported one-per-line at 0.98x and extreme mutation at
0.72x. Those numbers invited a tidy conclusion -- that a cheap engine makes reduction pointless --
and they were entirely contention. The rerun on a quiet machine gives 1.40x and 1.63x. The harness
now says so in its own output, because the next person to see a surprising result will be tempted
the same way.

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

**Partly done.**

Done:

- **Diagnostics for the failures that produce a plausible-looking nothing**: no compiled classes, no
  mutants, no tests discovered. Each names what was looked at and what to check, and each is
  asserted by a test, because a message nobody tests is a message nobody maintains. These are the
  classpath problems that dominate a mutation tool's support load.
- [troubleshooting.md](troubleshooting.md), ordered by how often each failure comes up rather than
  by severity.
- [compatibility.md](compatibility.md), stating what jzap has been run against rather than what it
  might work with, and naming Android, Kotlin Multiplatform and the module path as untested.
- [versioning.md](versioning.md), covering the model schema, the cache format, mutator ids, mutant
  keys and the daemon protocol -- including the awkward row, that a fix which corrects a wrong
  verdict changes scores and will be released as a patch that says so.

Outstanding: release automation and signed artefacts, publication to Maven Central and the Gradle
Plugin Portal, public dashboards, and the thirty-day dogfood on an external project. None of those
can be done meaningfully before the artefacts are published somewhere.

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
                        M15 -> M16 -> M16b -> M17 (optional)
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
- M16b (Kotest) depends on M15 only for realistic fixtures, not technically. If Kotest turns out
  not to support single-leaf selection, it becomes a `jzap-testkit` change and can move earlier,
  since that decision affects the SPI every other framework goes through.
- M18 must not start before M11, because Gradle build-cache correctness depends on the
  determinism guarantees established in M10 and M11.
