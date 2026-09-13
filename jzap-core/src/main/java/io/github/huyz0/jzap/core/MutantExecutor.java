package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.MutantSwitch;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.wire.Wire;
import io.github.huyz0.jzap.wire.WireException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides every in-scope mutant's verdict, in forked analysis JVMs.
 *
 * <p>Work is partitioned by class rather than round robin. A worker that stays on one class keeps
 * it loaded and JIT-compiled, and under the schemata engine keeps its one installed schemata
 * class; scattering classes across workers re-pays class loading and warmup for every mutant.
 */
final class MutantExecutor {

    /** Rough cost of running one mutant's tests once a JVM is warm, measured on the bench fixture. */
    private static final long PER_MUTANT_OVERHEAD_MILLIS = 2;

    /** Rough cost of starting an analysis JVM and getting it warm. */
    private static final long JVM_STARTUP_MILLIS = 250;

    /** Work that justifies one more worker: about twice what starting one costs. */
    private static final long WORK_PER_WORKER_MILLIS = 2 * JVM_STARTUP_MILLIS;

    private final ProjectModel model;
    private final AnalysisEngine.Listener listener;
    private final RuntimeJars.Jars jars;
    private final MutationEngine mutation;
    private final PhaseTimings timings;
    private final Map<String, ModuleModel> modulesById;
    private final AtomicInteger reusedFromCache = new AtomicInteger();

    MutantExecutor(ProjectModel model, AnalysisEngine.Listener listener, RuntimeJars.Jars jars,
                   MutationEngine mutation, PhaseTimings timings) {
        this.model = model;
        this.listener = listener;
        this.jars = jars;
        this.mutation = mutation;
        this.timings = timings;
        Map<String, ModuleModel> byId = new LinkedHashMap<>();
        model.modules().forEach(m -> byId.putIfAbsent(m.id(), m));
        this.modulesById = Map.copyOf(byId);
    }

    /** Mutants whose verdict came from a previous run rather than from executing anything. */
    int reusedFromCache() {
        return reusedFromCache.get();
    }

    List<Mutant> execute(List<Mutant> mutants, Map<String, ClassUnderTest> classes,
                         Coverage coverage, MutantCache cache) {
        // Selection is needed three times per mutant -- to consult the cache, to size the worker
        // pool, and to run the tests -- and each call sorts. Computed once, so the three cannot
        // disagree about which tests a mutant has either.
        Map<MutantKey, List<String>> selections = new LinkedHashMap<>();
        for (Mutant mutant : mutants) {
            selections.put(mutant.key(), coverage.selectFor(mutant));
        }

        List<Mutant> results = new ArrayList<>();
        List<Mutant> covered = new ArrayList<>();
        for (Mutant mutant : mutants) {
            settleWithoutRunning(mutant, selections.get(mutant.key()), classes, coverage, cache)
                    .ifPresentOrElse(results::add, () -> covered.add(mutant));
        }
        if (covered.isEmpty()) {
            return results;
        }

        Map<String, List<Mutant>> byClass = new LinkedHashMap<>();
        for (Mutant mutant : covered) {
            byClass.computeIfAbsent(mutant.key().className(), k -> new ArrayList<>()).add(mutant);
        }
        Queue<List<Mutant>> queue = new ConcurrentLinkedQueue<>(byClass.values());

        int threads = workersFor(covered, selections, coverage, byClass.size());
        listener.phase("execution", covered.size() + " mutants on " + threads + " thread(s)");

        Batch batch = new Batch(classes, coverage, selections, cache, new ConcurrentLinkedQueue<>(),
                new AtomicInteger(), covered.size());
        if (threads == 1) {
            new Worker(queue, batch).run();
        } else {
            runInParallel(threads, queue, batch);
        }
        results.addAll(batch.analysed());
        results.addAll(neverAnalysed(covered, batch));
        return results;
    }

    /**
     * Covered mutants no worker returned a verdict for, reported as errors rather than dropped.
     *
     * <p>A worker that fails outright takes the class it was holding with it, and those mutants
     * would otherwise simply not appear in the result. That is worse than an error: the mutation
     * score is a ratio over the mutants present, so a run that lost work would report a *higher*
     * score than the same run intact, and nothing in the output would say so.
     *
     * <p>Not written to the cache. There is no verdict here to reuse -- only the fact that this
     * run failed to reach one.
     */
    private List<Mutant> neverAnalysed(List<Mutant> covered, Batch batch) {
        if (batch.analysed().size() == covered.size()) {
            return List.of();
        }
        Set<MutantKey> produced = new LinkedHashSet<>();
        batch.analysed().forEach(mutant -> produced.add(mutant.key()));
        List<Mutant> missing = covered.stream()
                .filter(mutant -> !produced.contains(mutant.key()))
                .map(mutant -> mutant.withOutcome(MutantStatus.RUN_ERROR, null,
                        batch.selections().get(mutant.key()).size(), 0, 0))
                .toList();
        if (!missing.isEmpty()) {
            listener.warning(missing.size() + " mutant(s) were never analysed because an analysis "
                    + "worker failed. They are reported as errors rather than left out, so the "
                    + "score is not inflated by the work that was lost.");
        }
        return missing;
    }

    /**
     * The verdict for a mutant nothing has to be run for, if there is one.
     *
     * <p>The cache is consulted before anything is executed, including for uncovered mutants:
     * "no test covers this" is a verdict like any other, and it becomes wrong the moment a test
     * appears that does.
     */
    private Optional<Mutant> settleWithoutRunning(Mutant mutant, List<String> selected,
                                                  Map<String, ClassUnderTest> classes,
                                                  Coverage coverage, MutantCache cache) {
        String classHash = classes.get(mutant.key().className()).hash();
        Optional<Mutant> reused =
                cache.reuse(mutant, classHash, selected, coverage.testClassHashes());
        if (reused.isPresent()) {
            cache.carryForward(mutant.key());
            reusedFromCache.incrementAndGet();
            return reused;
        }
        if (selected.isEmpty()) {
            Mutant uncovered = mutant.withOutcome(MutantStatus.NO_COVERAGE, null, 0, 0, 0);
            cache.record(uncovered, classHash, selected, coverage.testClassHashes());
            return Optional.of(uncovered);
        }
        return Optional.empty();
    }

    /**
     * How many analysis JVMs are worth starting for this much work.
     *
     * <p>Not simply the requested thread count. Starting one costs around a quarter of a second,
     * and it starts cold -- the first mutants it runs pay for JIT warmup the previous JVM had
     * already paid for. Once the per-mutant cost fell to about 1.5 ms, twenty workers on a
     * two-second job measured *slower* than one: 4.06s against 3.20s, because the run was mostly
     * twenty JVM startups.
     */
    private int workersFor(List<Mutant> covered, Map<MutantKey, List<String>> selections,
                           Coverage coverage, int classCount) {
        long estimatedMillis = 0;
        for (Mutant mutant : covered) {
            estimatedMillis += estimatedCostMillis(selections.get(mutant.key()), coverage);
        }
        int requested = Math.max(1, Math.min(model.threads(), classCount));
        int workers = workerCountFor(requested, estimatedMillis);
        if (workers < requested) {
            listener.phase("execution", "using " + workers + " of " + requested
                    + " requested thread(s): about " + estimatedMillis + "ms of work does not "
                    + "justify more analysis JVMs, which cost roughly "
                    + JVM_STARTUP_MILLIS + "ms each to start cold");
        }
        return workers;
    }

    /**
     * The worker count this much work justifies, capped at what was asked for.
     *
     * <p>One extra worker per {@code WORK_PER_WORKER_MILLIS} of estimated work, which is roughly
     * twice what starting one costs. Separated from the estimating so the rule itself can be
     * checked at scales no fixture reaches: the sample fixture is a few tens of milliseconds of
     * work, so it never justifies a second worker, and a test that asked for eight threads there
     * would be asserting on a single-threaded run.
     *
     * @param requested       threads asked for, already capped at the number of classes -- work is
     *                        partitioned by class, so a worker with no class to take is a JVM
     *                        started for nothing
     * @param estimatedMillis what the covered mutants are expected to cost in total
     */
    static int workerCountFor(int requested, long estimatedMillis) {
        if (requested <= 1) {
            return 1;
        }
        // Compared as longs and only then narrowed. A duration large enough to overflow the
        // division is not a real workload, but it is reachable from a hand-edited or corrupted
        // cache, and the cast used to wrap negative -- which newFixedThreadPool rejects outright
        // rather than falling back to something sensible.
        long justified = Math.max(1, estimatedMillis / WORK_PER_WORKER_MILLIS);
        return (int) Math.min(requested, justified);
    }

    /** What one mutant costs: its first test, since early exit means that usually decides it. */
    private static long estimatedCostMillis(List<String> selected, Coverage coverage) {
        long testMillis = selected.isEmpty()
                ? 0
                : coverage.durations().getOrDefault(selected.get(0), 0L);
        return testMillis + PER_MUTANT_OVERHEAD_MILLIS;
    }

    private void runInParallel(int threads, Queue<List<Mutant>> queue, Batch batch) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "jzap-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(new Worker(queue, batch)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    // One worker failing must not discard what the others produced, so the
                    // failure is reported and the remaining futures are still awaited.
                    listener.warning("an analysis worker failed: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new WireException("analysis interrupted", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Everything the workers of one execution phase share.
     *
     * @param analysed results so far, written by every worker
     * @param done     mutants finished so far, for progress reporting
     */
    private record Batch(
            Map<String, ClassUnderTest> classes,
            Coverage coverage,
            Map<MutantKey, List<String>> selections,
            MutantCache cache,
            Collection<Mutant> analysed,
            AtomicInteger done,
            int total) {
    }

    /** The schemata class currently built for a batch, and where each mutant sits in it. */
    private record InstalledSchemata(String className, byte[] bytes, Map<MutantKey, Integer> indices) {
    }

    /**
     * Analyses whole classes taken from a shared queue, in analysis JVMs of its own.
     *
     * <p>One JVM per test-bearing module, started on demand: a mutant's covering tests can live in
     * several modules, and each needs its own classpath. Each worker owning its own JVMs is what
     * makes a hung mutant survivable — the process is killed and only that worker pauses.
     */
    private final class Worker implements Runnable {

        private final Queue<List<Mutant>> queue;
        private final Batch batch;

        private final Map<String, MinionProcess> minions = new LinkedHashMap<>();
        private int sinceRestart;

        private InstalledSchemata schemata;
        private final Set<String> minionsHoldingSchemata = new LinkedHashSet<>();

        Worker(Queue<List<Mutant>> queue, Batch batch) {
            this.queue = queue;
            this.batch = batch;
        }

        @Override
        public void run() {
            try {
                List<Mutant> classBatch;
                while ((classBatch = queue.poll()) != null) {
                    uninstallSchemata();
                    schemata = buildSchemata(classBatch);
                    for (Mutant mutant : classBatch) {
                        batch.analysed().add(analyseOne(mutant));
                        listener.progress(batch.done().incrementAndGet(), batch.total());
                    }
                }
            } finally {
                minions.values().forEach(MinionProcess::close);
                minions.clear();
            }
        }

        /**
         * Builds the schemata class for a batch, which is always one class's worth of mutants.
         *
         * <p>Built once per class rather than once per mutant, which is the entire saving: the
         * class is installed once and each mutant then costs a field write.
         *
         * @return null when this batch will be analysed by redefinition instead
         */
        private InstalledSchemata buildSchemata(List<Mutant> classBatch) {
            if (!model.usesSchemata() || classBatch.isEmpty()) {
                return null;
            }
            String className = classBatch.get(0).key().className();
            ClassUnderTest target = batch.classes().get(className);
            if (target == null) {
                return null;
            }
            long start = System.nanoTime();
            try {
                SchemataTransformer.Result result =
                        SchemataTransformer.transform(target.bytes(), classBatch);
                timings.addSince("executionSchemataBuild", start);
                return result.indices().isEmpty()
                        ? null
                        : new InstalledSchemata(className, result.schemata(), result.indices());
            } catch (RuntimeException e) {
                // A class that cannot be transformed is analysed the slow way rather than not at
                // all; the verdicts are the same either way.
                listener.warning("schemata could not be built for " + className + ", falling back "
                        + "to per-mutant redefinition: " + e.getMessage());
                return null;
            }
        }

        private Mutant analyseOne(Mutant mutant) {
            List<String> selected = batch.selections().get(mutant.key());
            long mutantStart = System.nanoTime();

            // A mutant in a static initialiser only takes effect if the class has not been loaded
            // yet, so it gets fresh JVMs. PIT reports these as surviving for want of this, and
            // documents it as a known limitation.
            boolean needsFreshJvm = "<clinit>".equals(mutant.key().methodName());
            if (needsFreshJvm || sinceRestart >= model.maxMutantsPerMinion()) {
                recycleAll();
            }

            Integer schemataIndex = schemata != null
                    && schemata.className().equals(mutant.key().className())
                    ? schemata.indices().get(mutant.key())
                    : null;

            byte[] mutated = null;
            if (schemataIndex == null) {
                try {
                    mutated = mutation.apply(
                            batch.classes().get(mutant.key().className()).bytes(), mutant.key());
                } catch (RuntimeException e) {
                    listener.warning("mutant " + mutant.key().asString() + " could not be generated: "
                            + e.getMessage());
                    return finish(mutant, MutantStatus.RUN_ERROR, null, selected, 0, mutantStart);
                }
            }

            Verdict verdict = new Verdict();
            // Grouped by module and run group by group, stopping at the first kill. Ordering
            // within a group already puts the previously-killing test first.
            for (Map.Entry<String, List<String>> group
                    : batch.coverage().groupByModule(selected).entrySet()) {
                ModuleModel module = modulesById.get(group.getKey());
                if (module == null) {
                    continue;
                }
                if (!runGroup(mutant, module, group.getValue(), schemataIndex, mutated, verdict)) {
                    break;
                }
            }
            return finish(mutant, verdict.status, verdict.killingTest, selected, verdict.testsRun,
                    mutantStart);
        }

        /** How one module's group of tests came out, accumulated across the groups. */
        private static final class Verdict {
            MutantStatus status = MutantStatus.SURVIVED;
            String killingTest;
            int testsRun;
        }

        /**
         * Runs one module's share of a mutant's tests.
         *
         * @return whether the remaining groups are still worth running, which they are only while
         *         the mutant keeps surviving
         */
        private boolean runGroup(Mutant mutant, ModuleModel module, List<String> tests,
                                 Integer schemataIndex, byte[] mutated, Verdict verdict) {
            MinionProcess minion;
            try {
                minion = minionFor(module);
            } catch (MinionProcess.HungException | WireException | IllegalStateException e) {
                // HungException is not a WireException. Before it was named here, a control round
                // trip that timed out -- starting a JVM, preparing its harness, installing a class
                // -- unwound out of the worker instead of failing one mutant, which lost every
                // remaining mutant of the class it was holding.
                return failGroup(mutant, module.id(), verdict, e.getMessage());
            }
            try {
                if (schemataIndex != null) {
                    installSchemataIfNeeded(minion, module.id());
                } else {
                    minion.installMutant(mutant.key().className(), mutated);
                }
            } catch (MinionProcess.NonViableException e) {
                // The JVM would not verify or link the mutated class. That is what NON_VIABLE
                // means, and it is a fact about the mutant rather than a failure of the analysis:
                // it costs one verdict, no warning, and -- provided the JVM can be put back to a
                // known state -- not the JVM either.
                verdict.status = MutantStatus.NON_VIABLE;
                if (!resetOverrides(minion, module.id())) {
                    discard(module.id());
                }
                return false;
            } catch (MinionProcess.HungException | WireException | IllegalStateException e) {
                return failGroup(mutant, module.id(), verdict, e.getMessage());
            }
            try {
                long runStart = System.nanoTime();
                MinionProcess.MutantOutcome outcome = minion.runTests(tests,
                        batch.coverage().iterationLimitFor(tests),
                        batch.coverage().timeoutFor(tests, model),
                        schemataIndex != null ? schemataIndex : MutantSwitch.NONE);
                timings.addSince("executionRunTests", runStart);
                verdict.testsRun += outcome.testsRun();
                sinceRestart++;
                if (schemataIndex == null) {
                    // The schemata class stays installed between mutants; a redefined one has
                    // to be put back.
                    minion.clearOverrides();
                    minionsHoldingSchemata.remove(module.id());
                }
                MutantStatus fromGroup = statusOf(outcome.code());
                if (fromGroup == MutantStatus.SURVIVED) {
                    return true;
                }
                verdict.status = fromGroup;
                verdict.killingTest =
                        fromGroup == MutantStatus.KILLED ? outcome.failingTest() : null;
                return false;
            } catch (MinionProcess.HungException e) {
                verdict.status = MutantStatus.TIMED_OUT;
                discard(module.id());
                return false;
            } catch (WireException | IllegalStateException e) {
                return failGroup(mutant, module.id(), verdict, e.getMessage());
            }
        }

        /**
         * Puts a JVM back to holding no overrides at all.
         *
         * <p>Needed after a refused install, because the minion records the bytes before it
         * retransforms: a refusal leaves an override registered that was never applied, and the
         * next mutant would run against a JVM in a state nobody asked for.
         *
         * @return whether the JVM can be trusted to serve another mutant
         */
        private boolean resetOverrides(MinionProcess minion, String moduleId) {
            try {
                minion.clearOverrides();
                minionsHoldingSchemata.remove(moduleId);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean failGroup(Mutant mutant, String moduleId, Verdict verdict, String reason) {
            listener.warning("mutant " + mutant.key().asString() + " could not be analysed: "
                    + reason);
            verdict.status = MutantStatus.RUN_ERROR;
            discard(moduleId);
            return false;
        }

        private static MutantStatus statusOf(byte outcomeCode) {
            return switch (outcomeCode) {
                case Wire.OUTCOME_FAILED -> MutantStatus.KILLED;
                case Wire.OUTCOME_ALL_PASSED -> MutantStatus.SURVIVED;
                case Wire.OUTCOME_NON_VIABLE -> MutantStatus.NON_VIABLE;
                case Wire.OUTCOME_RUNAWAY -> MutantStatus.TIMED_OUT;
                default -> MutantStatus.RUN_ERROR;
            };
        }

        private Mutant finish(Mutant mutant, MutantStatus status, String killingTest,
                              List<String> selected, int testsRun, long mutantStart) {
            Mutant result = mutant.withOutcome(status, killingTest, selected.size(), testsRun,
                    millisSince(mutantStart));
            batch.cache().record(result, batch.classes().get(mutant.key().className()).hash(),
                    selected, batch.coverage().testClassHashes());
            return result;
        }

        private MinionProcess minionFor(ModuleModel module) {
            MinionProcess existing = minions.get(module.id());
            if (existing != null && existing.isAlive()) {
                return existing;
            }
            long start = System.nanoTime();
            MinionProcess started = MinionProcess.start(module, jars);
            started.prepareTests(module.testClassPaths());
            timings.addSince("executionMinionStartup", start);
            timings.count("executionMinionsStarted", 1);
            minions.put(module.id(), started);
            return started;
        }

        private void installSchemataIfNeeded(MinionProcess minion, String moduleId) {
            if (minionsHoldingSchemata.add(moduleId)) {
                long start = System.nanoTime();
                minion.installSchemata(schemata.className(), schemata.bytes());
                timings.addSince("executionSchemataInstall", start);
            }
        }

        /**
         * Takes the previous class's schemata class back out of every JVM holding it.
         *
         * <p>Required for a verdict to mean anything. Schemata indices are numbered from zero
         * within each class and selected by one global switch, so a schemata class still installed
         * from an earlier class answers to the index chosen for the current one -- two mutants run
         * at once, and a mutant its own tests never detected is reported killed because a mutation
         * in some other class broke an unrelated assertion. Costs one round trip per class, against
         * one per mutant saved.
         */
        private void uninstallSchemata() {
            for (String moduleId : List.copyOf(minionsHoldingSchemata)) {
                MinionProcess minion = minions.get(moduleId);
                if (minion == null) {
                    continue;
                }
                try {
                    minion.clearOverrides();
                } catch (RuntimeException e) {
                    // A JVM that will not say it cleared the last mutant cannot be trusted to be
                    // running only the next one, so it does not get to serve another.
                    discard(moduleId);
                }
            }
            minionsHoldingSchemata.clear();
        }

        private void recycleAll() {
            minions.values().forEach(MinionProcess::close);
            minions.clear();
            minionsHoldingSchemata.clear();
            sinceRestart = 0;
        }

        /** Kills a JVM outright. The only reliable way to stop a mutant that hangs. */
        private void discard(String moduleId) {
            minionsHoldingSchemata.remove(moduleId);
            MinionProcess minion = minions.remove(moduleId);
            if (minion != null) {
                minion.destroy();
            }
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
