# Prior art: fast, diff-aware mutation testing for Java and Kotlin

Research notes for jzap. Captures the existing JVM tool landscape, where PIT spends
its time, how other language ecosystems do delta/diff runs, and which speed
techniques look worth building on. Dated 2026-09-12.

## 1. The JVM landscape

| Tool | Approach | Diff/delta support | Status |
|---|---|---|---|
| [PIT / pitest](https://pitest.org/) | Bytecode mutation, forked "minion" JVMs, per-test coverage | `scmMutationCoverage` Maven goal: changed **files**, whole class mutated; history file for incremental runs | Apache 2, de-facto standard, actively developed |
| [arcmutate](https://www.arcmutate.com/) (GroupCDG, by PIT's author) | Commercial PIT plugins | `+GIT` change-based (changed **lines** only), `+GIT_TEST`, `+GIT_MIXED`; `from`/`to` git refs plus `-Local-` (uncommitted) and `-Empty-`; `gitci` JSON output feeding GitHub/GitLab/Bitbucket/Azure PR comments; `+arcmutate_history` incremental analyser | [Commercial licence required](https://docs.arcmutate.com/licence.html) for commercial use |
| [pitest-kotlin](https://github.com/pitest/pitest-kotlin) / arcmutate Kotlin plugin | Filters junk mutants arising from Kotlin bytecode patterns; inline-function mutation | — | Free plugin is basic; inline-function support and aggressive null filtering (`+KOTLIN_NO_NULLS`) are arcmutate-only |
| [Descartes](https://github.com/STAMP-project/pitest-descartes) (STAMP) | Extreme mutation: strip method body, or replace it with a single return | — | Maintained; produces far fewer mutants |
| [mutflow](https://github.com/anschnapp/mutflow) | K2 Kotlin compiler plugin, mutant schemata, runs inside the normal JUnit suite | — | Young: ~28 stars, pinned to Kotlin 2.4.x |
| [Mutant-Kraken](https://conf.researchr.org/details/icst-2024/mutation-2024-papers/2/Mutant-Kraken-A-Mutation-Testing-Tool-for-Kotlin) | tree-sitter AST, 7 Kotlin-specific operators | — | Research tool (ICST 2024) |
| Jumble, Javalanche, µJava, Jester | Historical; Jumble used mutant schemata | — | Dead (Jumble's last release was 2009) |

Two gaps stand out:

- `gradle-pitest-plugin` still has no scm/diff task
  ([issue #165](https://github.com/szpak/gradle-pitest-plugin/issues/165)). Since
  nearly all Kotlin lives on Gradle, free line-level diff mutation testing does not
  exist for Kotlin projects today.
- Line-level diff scoping, PR annotations, and the better incremental analyser are all
  behind arcmutate's commercial licence. The free path stops at changed *files*.

## 2. Where PIT spends its time

From Henry Coles' own design notes,
[so_you_want_to_build_mutation_testing_system.md](https://github.com/hcoles/pitest/blob/master/so_you_want_to_build_mutation_testing_system.md):

- One controlling process spawns **minion** JVMs. Each minion analyses many mutants, so
  process startup is amortised. The controller never loads the code under test, and
  minion dependencies are shaded into new packages to avoid clashing with the user's.
- Mutants are generated as bytecode **in memory** and inserted with the Instrumentation
  API. Nothing is written to disk.
- A **coverage phase** runs first, recording per test which instructions it executes.
  Only tests that execute the mutated instruction are run against a mutant. Coles notes
  this beats both naming conventions and static analysis.
- **Early exit**: stop analysing a mutant the moment one test kills it. He measures this
  as roughly a 50% win over academic tools that record every killing test.
- **Timeouts** derive from baseline per-test timings recorded during coverage (factor ×
  normal time, plus a constant). A hung minion is killed at the process level, because
  you cannot reliably kill a Java thread. He suggests a better scheme: insert probes
  counting execution hits, and treat a wildly elevated count as an infinite loop, which
  terminates earlier.
- **Test splitting**: PIT subdivides test classes so early exit doesn't force a rerun of
  a whole class.
- Explicitly **rejected**: writing mutant class files to disk and forking a JVM per
  mutant. Coles attributes most abandoned mutation systems to this design.
- Explicitly **considered, not implemented**: mutant schemata. Cheap insertion and a
  single compilation, but risks oversized classes, and schemata mutants cannot fire
  during static initialisation.

Known weak spots:

- **Static initialisers.** Coverage attributes them to whichever test first loads the
  class, and the code isn't re-run, so mutants there look ineffective.
- **Junk mutants** from bytecode that doesn't map to source intent. Worst for non-Java
  JVM languages (Coles calls out Scala; Kotlin has the same problem).
- Practitioner reports cluster on integration tests with in-process databases (hours per
  run), PIT scanning the entire classpath and attempting to run tests that aren't
  normally run, and multi-module projects (cross-module tests only partially supported,
  and only with explicit configuration, since 1.17.1).

## 3. Delta/diff as done elsewhere

**Google** — Petrović & Ivanković,
[State of Mutation Testing at Google (ICSE-SEIP'18)](https://research.google.com/pubs/archive/46584.pdf)
and [Practical Mutation Testing at Scale (TSE'21)](https://arxiv.org/pdf/2102.11378):

- Mutants only on lines touched by the changelist diff.
- **One mutant per covered line**, a deliberate break with traditional mutagenesis.
- **Arid node** suppression: an AST-level heuristic marking "uninteresting" nodes
  (logging, boilerplate) as not worth mutating. Arid lines are those without statement
  coverage plus those judged uninteresting.
- Results surface as code-review comments; developer "please fix" / "not useful"
  feedback drives the suppression rules over time.
- Statement/block removal (SBR) alone accounts for roughly 72% of the raw mutant space.

**StrykerJS** — [incremental mode](https://stryker-mutator.io/docs/stryker-js/incremental/):

- Stores `reports/stryker-incremental.json` and does a git-like diff of source and test
  files against the previous report to match up mutants and tests.
- Reuses a result when either a killed mutant's culprit test still exists unchanged, or
  an unkilled mutant has no new covering test and no changed tests.
- Limits: changes outside mutated and test files go undetected; dependency and config
  changes aren't tracked; static mutants have no coverage so test-change detection can't
  work for them; test-file change detection depends on the runner plugin (full for Jest
  and Vitest).

**cargo-mutants** — [`--in-diff`](https://mutants.rs/pr-diff.html): takes a `git diff`
file and tests only changed code. Documentation is blunt about the tradeoff — much
faster PR feedback, but misses problems a whole-tree run would find.

**Mull** (LLVM/C++) —
[incremental mutation testing](https://mull.readthedocs.io/en/latest/IncrementalMutationTesting.html):
`gitDiffRef` plus `gitProjectRoot`, mutating only source lines in the changeset;
`gitDiff: true` to visualise included/excluded lines. Supports branch comparison
(`origin/main`), work-in-progress (`.` or `HEAD`), and single commits (`COMMIT^!`).
Caveat: it does **not** check out the target commit, so analysing an older commit whose
files have since changed gives misleading results. arcmutate has the same property — the
git range only *selects* what to mutate; analysis always runs against current code.

**go-mutesting** — `--git-diff-lines` limits mutation to lines changed since a given ref,
which keeps results aligned with what the PR shows even when the branch is behind base.

## 4. Speed techniques, ranked by likely payoff

1. **Mutant schemata / mutation switching.** Compile once with every mutant present as a
   guarded branch, and activate one at a time via a global switch. This is what makes
   [Stryker](https://stryker-mutator.io/docs/stryker-net/technical-reference/mutant-schemata/)
   fast across JS/.NET/Scala (a
   [reported 20–70%](https://angular.love/announcing-stryker-4-0-mutation-switching)
   improvement, and with bundlers like webpack the bundle is built once instead of per
   mutant), what [mutmut 3](https://github.com/boxed/mutmut) does in Python (libcst
   injects a trampoline around each function; the original is kept under a mangled name,
   mutants live in a dict, and `MUTANT_UNDER_TEST` selects one at runtime with no
   re-import), and what mutflow does for Kotlin. It is also the one big lever PIT
   deliberately left unused. Literature reports bytecode schemata around 5× faster than
   separate compilation, with ~99.78% less disk use.
2. **Warm/persistent JVM with in-process mutant switching.** `Instrumentation.redefineClasses`
   (HotSwap) or schemata flags swap mutants without a process restart.
   [UniAPR](https://arxiv.org/abs/2007.11449) reports over an order of magnitude speedup
   for Java patch validation this way. Its key contribution is the part that's easy to get
   wrong: **resetting global JVM state** (statics, singletons, caches) between runs via
   runtime bytecode transformation. Without that, results are unsound.
3. **Coverage-driven test selection at block granularity.** PIT works at line/instruction
   level; [PR #534](https://github.com/hcoles/pitest/pull/534) (jon-bell) moves
   mutant-test pairing to basic blocks with exception-correct coverage tracking. Pair it
   with **kill-test-first ordering** from history (arcmutate prioritises the previously
   killing test) and early exit.
4. **Mutant-space reduction before execution**, in cascade: diff scoping → arid-node
   suppression → one mutant per line or per block →
   [TCE](https://discovery.ucl.ac.uk/1499169/1/Jia_Trivial_Compiler_mutation-testing-papadakis-icse15.pdf)
   dedup (compile/normalise mutants and compare binaries; ~11% of Java mutants are
   equivalent or duplicated, 28% in C) → optionally an extreme-mutation fast tier.
   Descartes on Apache Flink core: 4,935 mutants in 14:04, versus PIT's default engine at
   43,619 mutants in 2:29:45 — and extreme mutants still reliably find pseudo-tested
   methods.
5. **Content-hash result caching.** arcmutate invalidates on class bytecode hash and
   writes plain-text history files, but warns that bytecode from different `javac`
   versions or platforms differs, so hashes are not portable across machines. Any ambition
   to share results through Gradle or Bazel remote caches needs the same key discipline
   plus reproducible bytecode.

## 5. Kotlin specifics

Bytecode mutation of Kotlin is where every existing tool hurts. Per
[arcmutate's Kotlin docs](https://docs.arcmutate.com/docs/kotlin.html) and
[pitest-kotlin](https://github.com/pitest/pitest-kotlin):

- **Inline functions.** The body is copied into each caller, and some instructions (such
  as returns) never appear in the inlined copy, so an inline function receives fewer and
  different mutants than its source suggests. The analysis needed to handle this is
  expensive enough that inline methods over 500 instructions are skipped by default.
  See also [pitest issue #764](https://github.com/hcoles/pitest/issues/764).
- **Compiler-generated constructs** produce mutants that "cannot be reproduced by
  mistakes in the source code": null checks (`Intrinsics.checkNotNull`), data-class
  `equals`/`hashCode`/`copy`, `when` tables, default-argument synthetics, coroutine state
  machines and `suspend` continuations. Both plugins handle these by filtering after
  generation, not by avoiding generation.

Mutating **Kotlin IR through a K2 compiler plugin** — mutflow's approach — avoids junk
generation entirely rather than filtering it, and yields source-faithful mutant
descriptions for free. The cost is coupling to unstable compiler internals (mutflow is
pinned to Kotlin 2.4.x) and no help for Java. The unoccupied position is a hybrid: IR/AST
for mutant *selection and description*, bytecode for *seeding*.

## 6. Implications for jzap

The unclaimed intersection is: **free, Gradle-first, line-level diff mode, schemata plus
a warm daemon, Kotlin-native mutant semantics.** A concrete shape:

- **Frontend.** Git diff → changed line ranges → map to methods and blocks via bytecode
  line tables for Java, and Kotlin IR/PSI for Kotlin (which also gives faithful mutant
  descriptions and a place to apply arid-node suppression).
- **Engine.** One compile; bytecode mutant schemata with a per-thread active-mutant
  switch; a resident daemon holding a warm JVM with classes already loaded (AppCDS or
  CRaC snapshot); UniAPR-style static-state reset between mutants; process-level
  isolation kept only as the fallback for hangs and state leaks.
- **Selection.** Cached per-test block coverage keyed by class hash, kill-test-first
  ordering from history, early exit, TCE dedup.
- **Output.** A `gitci`-style JSON for PR annotations (arcmutate's format is the proven
  model) plus the standard mutation-testing-elements report schema, so existing
  dashboards work unchanged.

Decisions worth settling early, because they shape the architecture:

- Whether sharing results through Gradle or Bazel remote caches is a goal. If so, every
  cache key must be a content hash and bytecode must be reproducible across machines.
- Whether to accept the schemata limitation that mutants cannot fire during static
  initialisation, or to keep a bytecode-redefinition path for that case.
- Whether the Kotlin frontend takes a compiler-plugin dependency (better mutants, version
  churn) or stays purely bytecode-based with filters (portable, junkier).

## 7. Further reading

- [What It Would Take to Use Mutation Testing in Industry — a study at Facebook](https://arxiv.org/pdf/2010.13464)
  — not yet read; text extraction failed.
- [How Do Java Mutation Tools Differ?](https://cacm.acm.org/research/how-do-java-mutation-tools-differ/) (CACM)
- [metamutator](https://github.com/SpoonLabs/metamutator) — Java mutant schemata via Spoon
- [Mutation Testing Optimisations using the Clang Front-end](https://arxiv.org/pdf/2210.17215)
- [Extreme mutation testing in practice](https://arxiv.org/pdf/2103.08480)
- [Faster Mutation Testing](https://blog.frankel.ch/faster-mutation-testing/) (Frankel)
