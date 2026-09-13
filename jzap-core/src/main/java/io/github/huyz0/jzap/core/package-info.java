/**
 * The mutation engine: turn compiled classes and a test suite into a verdict per mutant.
 *
 * <h2>What this module exposes</h2>
 *
 * Nine types, and that is the whole surface. {@link io.github.huyz0.jzap.core.AnalysisEngine} is the entry
 * point; {@link io.github.huyz0.jzap.core.MutationEngine}, {@link io.github.huyz0.jzap.core.Mutators},
 * {@link io.github.huyz0.jzap.core.Mutator}, {@link io.github.huyz0.jzap.core.MutantFilters} and
 * {@link io.github.huyz0.jzap.core.Reduction} let a caller list an inventory without running anything, which
 * is what {@code jzap list-mutants} and the parity harness do;
 * {@link io.github.huyz0.jzap.core.ClassScanner} and {@link io.github.huyz0.jzap.core.ClassBytes} find the classes; and
 * {@link io.github.huyz0.jzap.core.MutationContext} is the contract with {@link io.github.huyz0.jzap.core.mutator}.
 *
 * <p>Everything else is package-private, including the cache, the minion protocol's controller
 * side, the schemata transformer and the coverage instrumenter. A caller has no business reaching
 * any of it, and the compiler now says so.
 *
 * <h2>Why one package and not several</h2>
 *
 * Thirty-six classes is a lot for one package, and the grouping below would make obvious
 * subpackages. They are deliberately not subpackages, because in Java that trade goes the wrong
 * way: package-private is the only real encapsulation available, so splitting
 * {@code cache}, {@code bytecode}, {@code engine} and {@code filter} apart would force the cache,
 * the schemata transformer, the hasher, the glob matcher and the probe index to become public --
 * six more types exposed to every consumer of this module, to gain four directories.
 *
 * <p>The {@code mutator} subpackage exists because it is the one boundary with a real contract: a
 * mutator sees {@link io.github.huyz0.jzap.core.MutationContext} and nothing else, and that contract is worth
 * the two types it costs to state. Where a boundary is only a filing decision, this package uses
 * the grouping below instead.
 *
 * <h2>The grouping</h2>
 *
 * <dl>
 *   <dt>Phases</dt>
 *   <dd>{@code AnalysisEngine} orders them and owns the diagnostics for a run that finds nothing.
 *       {@code CoverageCollector} learns which tests reach which lines; {@code MutantExecutor}
 *       decides each mutant, in forked JVMs, and owns the worker lifecycle. {@code Coverage},
 *       {@code ClassUnderTest} and {@code PhaseTimings} are what they pass between them.</dd>
 *
 *   <dt>The analysis JVM</dt>
 *   <dd>{@code MinionProcess} is the controller's side of the protocol in {@code jzap-wire};
 *       {@code RuntimeJars} locates the jars such a JVM has to be started with.</dd>
 *
 *   <dt>Bytecode</dt>
 *   <dd>{@code MutationEngine} discovers mutants and seeds one; {@code SchemataTransformer}
 *       compiles every mutant of a class in at once. {@code CoverageInstrumenter} and
 *       {@code ProbeIndex} place and name the coverage probes, {@code BackEdgeInstrumenter} the
 *       runaway-loop guard. {@code MutationContext} carries where we are and whether to act, and
 *       {@code LineNumberTracker} keeps it current. {@code Bytecode}, {@code Opcodes2},
 *       {@code MathNames}, {@code Conditionals} and {@code ReturnRules} are the emission helpers
 *       shared with the mutators; {@code MethodFilter} says which methods are worth visiting.</dd>
 *
 *   <dt>Which mutants to keep</dt>
 *   <dd>{@code MutantFilters} names the set and is the only type a caller needs;
 *       {@code LoopCounterFilter}, {@code KotlinFilter}, {@code AridFilter} and
 *       {@code EquivalenceFilter} implement it, with {@code Globs} and {@code Reduction} in
 *       support.</dd>
 *
 *   <dt>Identity and reuse</dt>
 *   <dd>{@code MutantCache} reuses verdicts when it can be shown nothing relevant changed;
 *       {@code Hashes} and {@code EngineVersion} are what it keys on. {@code SourceMap} translates
 *       the synthetic line numbers Kotlin's inlining produces back to real ones.</dd>
 * </dl>
 */
package io.github.huyz0.jzap.core;
