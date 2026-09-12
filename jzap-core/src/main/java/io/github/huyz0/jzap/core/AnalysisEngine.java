package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.wire.Wire;
import io.github.huyz0.jzap.wire.WireException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The reference engine: one mutant at a time, in a forked JVM, with coverage-driven test
 * selection and early exit.
 *
 * <p>Deliberately the slow, obvious implementation. docs/delivery-plan.md keeps it forever as
 * the oracle that every optimisation is differentially tested against, because an optimisation
 * that changes a verdict is a bug and the only way to see that is to have something correct
 * to compare against.
 */
public final class AnalysisEngine {

    public static final String ENGINE_ID = "naive";

    /** Progress reporting. Implementations must tolerate being called from any thread. */
    public interface Listener {
        void phase(String name, String detail);

        void progress(int done, int total);

        void warning(String message);

        Listener SILENT = new Listener() {
            @Override
            public void phase(String name, String detail) {
            }

            @Override
            public void progress(int done, int total) {
            }

            @Override
            public void warning(String message) {
            }
        };
    }

    private final ProjectModel model;
    private final Listener listener;
    private final RuntimeJars.Jars jars;

    public AnalysisEngine(ProjectModel model, Listener listener) {
        this.model = model;
        this.listener = listener == null ? Listener.SILENT : listener;
        this.jars = RuntimeJars.discover();
    }

    /**
     * @param changed lines in scope, or null when the whole codebase is in scope
     */
    public AnalysisResult analyse(ChangedLines changed) {
        Map<String, Long> timings = new LinkedHashMap<>();
        List<Mutant> allResults = new ArrayList<>();
        List<String> failingBaseline = new ArrayList<>();
        int testsDiscovered = 0;

        for (ModuleModel module : model.modules()) {
            ModuleAnalysis analysis = new ModuleAnalysis(module, changed);
            long start = System.nanoTime();
            analysis.run();
            timings.merge("module:" + module.id(), millisSince(start), Long::sum);
            timings.merge("discovery", analysis.discoveryMillis, Long::sum);
            timings.merge("coverage", analysis.coverageMillis, Long::sum);
            timings.merge("execution", analysis.executionMillis, Long::sum);
            allResults.addAll(analysis.results);
            failingBaseline.addAll(analysis.failingBaselineTests);
            testsDiscovered += analysis.testCount;
        }

        return new AnalysisResult(
                Mutant.sorted(allResults),
                timings,
                testsDiscovered,
                describeScope(changed),
                ENGINE_ID,
                failingBaseline);
    }

    private String describeScope(ChangedLines changed) {
        Scope scope = model.scope();
        return switch (scope.kind()) {
            case ALL -> "all mutants in all target classes";
            case DIFF -> "changed " + scope.granularity() + "s between "
                    + (scope.from() == null ? "HEAD" : scope.from()) + " and "
                    + (scope.to() == null ? Scope.LOCAL : scope.to())
                    + " (" + (changed == null ? "?" : changed.lineCount()) + " lines in "
                    + (changed == null ? "?" : changed.paths().size()) + " files)"
                    + "; analysed against the current compiled code, nothing was checked out";
            case PATCH -> "changed " + scope.granularity() + "s from patch " + scope.patchFile()
                    + "; analysed against the current compiled code";
        };
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** One module's worth of work, kept together so its timings and warnings stay attributable. */
    private final class ModuleAnalysis {

        private final ModuleModel module;
        private final ChangedLines changed;
        private final MutationEngine mutation = new MutationEngine(
                Mutators.resolve(model.scope().mutators()),
                model.scope().isFilterEnabled(LoopCounterFilter.ID));

        final List<Mutant> results = new ArrayList<>();
        final List<String> failingBaselineTests = new ArrayList<>();
        long discoveryMillis;
        long coverageMillis;
        long executionMillis;
        int testCount;

        ModuleAnalysis(ModuleModel module, ChangedLines changed) {
            this.module = module;
            this.changed = changed;
        }

        void run() {
            long start = System.nanoTime();
            listener.phase("discovery", module.id());
            List<ClassBytes> classes = new ClassScanner(
                    model.scope().includeClasses(), model.scope().excludeClasses())
                    .scan(module.mutableCodePaths());
            Map<String, byte[]> bytesByClass = new LinkedHashMap<>();
            List<Mutant> inScope = new ArrayList<>();
            for (ClassBytes c : classes) {
                bytesByClass.put(c.binaryName(), c.bytes());
                for (Mutant m : mutation.discover(module.id(), c.bytes())) {
                    if (inScope(m)) {
                        inScope.add(m);
                    }
                }
            }
            inScope = Mutant.sorted(inScope);
            discoveryMillis = millisSince(start);

            if (inScope.isEmpty()) {
                // The fast path a diff-scoped run hits most of the time: no changed lines carry
                // mutants, so no test ever needs to be run.
                listener.phase("skipped", module.id() + ": no mutants in scope");
                return;
            }

            Set<String> mutatedClasses = new LinkedHashSet<>();
            for (Mutant m : inScope) {
                mutatedClasses.add(m.key().className());
            }

            Coverage coverage = gatherCoverage(mutatedClasses, bytesByClass);
            failingBaselineTests.addAll(coverage.failingTests);
            testCount = coverage.testIds.size();
            execute(inScope, bytesByClass, coverage);
        }

        private boolean inScope(Mutant m) {
            if (m.key().line() <= 0) {
                return false;   // no line information: nothing to report against
            }
            if (changed == null) {
                return true;
            }
            String sourcePath = sourcePathOf(m);
            return model.scope().isClassGranularity()
                    ? changed.containsPath(sourcePath)
                    : changed.containsLine(sourcePath, m.key().line());
        }

        /** Package directory plus the source file name recorded in the class file. */
        private String sourcePathOf(Mutant m) {
            String className = m.key().className();
            int lastDot = className.lastIndexOf('.');
            String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/') + "/";
            String file = m.sourceFile() != null ? m.sourceFile()
                    : simpleName(className) + ".java";
            return packagePath + file;
        }

        private String simpleName(String className) {
            int lastDot = className.lastIndexOf('.');
            String simple = lastDot < 0 ? className : className.substring(lastDot + 1);
            int dollar = simple.indexOf('$');
            return dollar < 0 ? simple : simple.substring(0, dollar);
        }

        private Coverage gatherCoverage(Set<String> mutatedClasses, Map<String, byte[]> bytesByClass) {
            long start = System.nanoTime();
            listener.phase("coverage", module.id());
            ProbeIndex index = new ProbeIndex();
            CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
            List<ClassBytes> instrumented = new ArrayList<>();
            // Only classes holding in-scope mutants are instrumented. On a diff-scoped run that
            // is a handful of classes rather than the whole module.
            for (String className : mutatedClasses) {
                byte[] original = bytesByClass.get(className);
                instrumented.add(new ClassBytes(className,
                        instrumenter.instrument(className, original), "instrumented"));
            }

            Map<Integer, Set<String>> testsByProbe = new LinkedHashMap<>();
            Map<String, Long> durations = new LinkedHashMap<>();
            List<String> failing = new ArrayList<>();
            List<String> testIds;

            try (MinionProcess minion = MinionProcess.start(module, jars, true)) {
                minion.initCoverage(index.size(), instrumented);
                testIds = minion.listTests(module.testClassPaths());
                int done = 0;
                for (String testId : testIds) {
                    MinionProcess.TestCoverage result;
                    try {
                        result = minion.runTestForCoverage(testId, 120_000);
                    } catch (MinionProcess.HungException e) {
                        listener.warning("test " + testId + " hung during the coverage run and was skipped");
                        minion.destroy();
                        throw new WireException("coverage run aborted: " + e.getMessage(), e);
                    }
                    durations.put(testId, result.durationMillis());
                    if (!result.passed()) {
                        failing.add(testId);
                        listener.warning("test already fails before any mutant is applied: " + testId
                                + (result.failureMessage() == null ? "" : " -- " + result.failureMessage()));
                    }
                    for (int probe : result.probeIds()) {
                        testsByProbe.computeIfAbsent(probe, k -> new LinkedHashSet<>()).add(testId);
                    }
                    listener.progress(++done, testIds.size());
                }
            }

            if (!failing.isEmpty()) {
                listener.warning(failing.size() + " test(s) already fail without any mutant applied. "
                        + "They are excluded from selection, because a mutant they cover would be "
                        + "reported as killed by a failure that has nothing to do with it.");
            }
            coverageMillis = millisSince(start);
            return new Coverage(index, testsByProbe, durations, failing, testIds);
        }

        private void execute(List<Mutant> mutants, Map<String, byte[]> bytesByClass, Coverage coverage) {
            long start = System.nanoTime();

            // Uncovered mutants are settled without starting anything: no test executes the
            // mutated line, so there is nothing to run them against.
            List<Mutant> covered = new ArrayList<>();
            for (Mutant mutant : mutants) {
                if (coverage.selectFor(mutant).isEmpty()) {
                    results.add(mutant.withOutcome(MutantStatus.NO_COVERAGE, null, 0, 0, 0));
                } else {
                    covered.add(mutant);
                }
            }
            if (covered.isEmpty()) {
                executionMillis = millisSince(start);
                return;
            }

            // Partitioned by class, not round robin. A worker that stays on one class keeps it
            // loaded and JIT-compiled; scattering classes across workers re-pays class loading
            // and warmup for every mutant.
            Map<String, List<Mutant>> byClass = new LinkedHashMap<>();
            for (Mutant mutant : covered) {
                byClass.computeIfAbsent(mutant.key().className(), k -> new ArrayList<>()).add(mutant);
            }
            Queue<List<Mutant>> queue = new ConcurrentLinkedQueue<>(byClass.values());

            int threads = Math.max(1, Math.min(model.threads(), byClass.size()));
            listener.phase("execution", module.id() + ": " + covered.size() + " mutants on "
                    + threads + " thread(s)");

            Collection<Mutant> analysed = new ConcurrentLinkedQueue<>();
            AtomicInteger done = new AtomicInteger();
            if (threads == 1) {
                new Worker(queue, bytesByClass, coverage, analysed, done, covered.size()).run();
            } else {
                runInParallel(threads, queue, bytesByClass, coverage, analysed, done, covered.size());
            }
            results.addAll(analysed);
            executionMillis = millisSince(start);
        }

        private void runInParallel(int threads, Queue<List<Mutant>> queue,
                                   Map<String, byte[]> bytesByClass, Coverage coverage,
                                   Collection<Mutant> analysed, AtomicInteger done, int total) {
            ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "jzap-worker");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<?>> futures = new ArrayList<>(threads);
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(
                            new Worker(queue, bytesByClass, coverage, analysed, done, total)));
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
         * Analyses whole classes taken from a shared queue, in one analysis JVM of its own.
         *
         * <p>Each worker owning its own JVM is what makes a hung mutant survivable: the process
         * is killed and only that worker pauses to start a replacement.
         */
        private final class Worker implements Runnable {

            private final Queue<List<Mutant>> queue;
            private final Map<String, byte[]> bytesByClass;
            private final Coverage coverage;
            private final Collection<Mutant> analysed;
            private final AtomicInteger done;
            private final int total;

            private MinionProcess minion;
            private int sinceRestart;

            Worker(Queue<List<Mutant>> queue, Map<String, byte[]> bytesByClass, Coverage coverage,
                   Collection<Mutant> analysed, AtomicInteger done, int total) {
                this.queue = queue;
                this.bytesByClass = bytesByClass;
                this.coverage = coverage;
                this.analysed = analysed;
                this.done = done;
                this.total = total;
            }

            @Override
            public void run() {
                try {
                    List<Mutant> batch;
                    while ((batch = queue.poll()) != null) {
                        for (Mutant mutant : batch) {
                            analysed.add(analyseOne(mutant));
                            listener.progress(done.incrementAndGet(), total);
                        }
                    }
                } finally {
                    if (minion != null) {
                        minion.close();
                    }
                }
            }

            private Mutant analyseOne(Mutant mutant) {
                List<String> selected = coverage.selectFor(mutant);
                long mutantStart = System.nanoTime();

                // A mutant in a static initialiser only takes effect if the class has not been
                // loaded yet, so it gets a JVM of its own. PIT reports these as surviving for
                // want of this, and documents it as a known limitation.
                boolean needsFreshJvm = "<clinit>".equals(mutant.key().methodName());
                if (minion == null || !minion.isAlive() || needsFreshJvm
                        || sinceRestart >= model.maxMutantsPerMinion()) {
                    recycle();
                }

                MutantStatus status;
                String killingTest = null;
                int testsRun = 0;
                try {
                    byte[] mutated = mutation.apply(bytesByClass.get(mutant.key().className()),
                            mutant.key());
                    minion.setOverride(mutant.key().className(), mutated);
                    MinionProcess.MutantOutcome outcome =
                            minion.runTests(selected, coverage.timeoutFor(selected, model));
                    testsRun = outcome.testsRun();
                    status = switch (outcome.code()) {
                        case Wire.OUTCOME_FAILED -> MutantStatus.KILLED;
                        case Wire.OUTCOME_ALL_PASSED -> MutantStatus.SURVIVED;
                        case Wire.OUTCOME_NON_VIABLE -> MutantStatus.NON_VIABLE;
                        default -> MutantStatus.RUN_ERROR;
                    };
                    if (status == MutantStatus.KILLED) {
                        killingTest = outcome.failingTest();
                    }
                    minion.clearOverrides();
                    sinceRestart++;
                } catch (MinionProcess.HungException e) {
                    status = MutantStatus.TIMED_OUT;
                    discard();
                } catch (WireException | IllegalStateException e) {
                    listener.warning("mutant " + mutant.key().asString() + " could not be analysed: "
                            + e.getMessage());
                    status = MutantStatus.RUN_ERROR;
                    discard();
                }
                return mutant.withOutcome(status, killingTest, selected.size(), testsRun,
                        millisSince(mutantStart));
            }

            private void recycle() {
                if (minion != null) {
                    minion.close();
                }
                minion = MinionProcess.start(module, jars, true);
                minion.listTests(module.testClassPaths());
                sinceRestart = 0;
            }

            /** Kills the JVM outright. The only reliable way to stop a mutant that hangs. */
            private void discard() {
                if (minion != null) {
                    minion.destroy();
                    minion = null;
                }
            }
        }
    }

    /** Per-test coverage plus the baselines that timeouts and ordering are derived from. */
    private record Coverage(
            ProbeIndex index,
            Map<Integer, Set<String>> testsByProbe,
            Map<String, Long> durations,
            List<String> failingTests,
            List<String> testIds) {

        /**
         * Tests that execute the mutated line, fastest first.
         *
         * <p>Cheapest test first is a crude ordering: kill-test-first from history is the real
         * answer and is scheduled for M10. Even so, ordering matters because early exit means
         * only the tests before the first failure are ever paid for.
         */
        List<String> selectFor(Mutant mutant) {
            int probe = index.lookup(mutant.key().className(), mutant.key().line());
            if (probe < 0) {
                return List.of();
            }
            Set<String> tests = testsByProbe.get(probe);
            if (tests == null) {
                return List.of();
            }
            return tests.stream()
                    .filter(t -> !failingTests.contains(t))
                    .sorted(Comparator.comparingLong(t -> durations.getOrDefault(t, 0L)))
                    .toList();
        }

        int timeoutFor(List<String> selected, ProjectModel model) {
            long baseline = selected.stream().mapToLong(t -> durations.getOrDefault(t, 0L)).sum();
            long timeout = (long) (baseline * model.timeoutFactor()) + model.timeoutConstMillis();
            return (int) Math.max(1_000L, Math.min(timeout, 600_000L));
        }
    }
}
