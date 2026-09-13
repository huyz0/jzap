package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.MutantSwitch;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.CacheConfig;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.wire.Wire;
import io.github.huyz0.jzap.wire.WireException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * The reference engine: one mutant at a time, in a forked JVM, with coverage-driven test
 * selection and early exit.
 *
 * <p>Deliberately the slow, obvious implementation. docs/delivery-plan.md keeps it forever as the
 * oracle that every optimisation is differentially tested against, because an optimisation that
 * changes a verdict is a bug and the only way to see that is to have something correct to compare
 * against.
 *
 * <p>Analysis spans every module in one pass rather than looping over them. That is what lets a
 * test in one module kill a mutant in another — the ordinary shape of a library module with its
 * tests next door, and the case PIT supports only partially and only with explicit configuration.
 * It also means the coverage phase runs once per test-bearing module rather than once per module
 * pair, and the mutant queue is shared, so every core stays busy even when the modules are
 * lopsided.
 */
public final class AnalysisEngine {

    /** The reference implementation's id, kept for comparison against every optimisation. */
    public static final String NAIVE_ENGINE = "naive";

    /** Filter id for keeping at most one mutant per source line. */
    public static final String ONE_PER_LINE = "ONE_PER_LINE";

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

    /** A compiled class in scope for mutation, and the module whose output it came from. */
    private record ClassUnderTest(String binaryName, byte[] bytes, String hash, ModuleModel module) {
    }

    private final ProjectModel model;
    private final Listener listener;
    private final RuntimeJars.Jars jars;
    private final MutationEngine mutation;
    private final AtomicInteger reusedFromCache = new AtomicInteger();

    /**
     * Where the execution phase's time went, beyond running tests.
     *
     * <p>Reported so the next optimisation is chosen by measurement. Every one so far was: the
     * schemata engine because redefinition dominated, batching because launcher startup did.
     */
    private final AtomicInteger minionsStarted = new AtomicInteger();
    private final AtomicLong minionStartupNanos = new AtomicLong();
    private final AtomicLong schemataNanos = new AtomicLong();
    private final AtomicLong installNanos = new AtomicLong();
    private final AtomicLong runTestsNanos = new AtomicLong();
    private final AtomicLong activateNanos = new AtomicLong();

    public AnalysisEngine(ProjectModel model, Listener listener) {
        this.model = model;
        this.listener = listener == null ? Listener.SILENT : listener;
        this.jars = RuntimeJars.discover();
        this.mutation = new MutationEngine(
                Mutators.resolve(model.scope().mutators()),
                model.scope().isFilterEnabled(LoopCounterFilter.ID),
                model.scope().isOptionalFilterEnabled(EquivalenceFilter.ID),
                model.scope().isOptionalFilterEnabled(AridFilter.ID),
                model.scope().isOptionalFilterEnabled(ONE_PER_LINE),
                model.scope().isFilterEnabled(KotlinFilter.ID));
    }

    /**
     * @param changed lines in scope, or null when the whole codebase is in scope
     */
    public AnalysisResult analyse(ChangedLines changed) {
        Map<String, Long> timings = new LinkedHashMap<>();
        MutantCache cache = openCache();
        cache.discardReason().ifPresent(reason ->
                listener.warning("not reusing the previous cache: " + reason));

        long discoveryStart = System.nanoTime();
        listener.phase("discovery", describeModules());
        Map<String, ClassUnderTest> classes = scanClasses();
        warnIfNothingToMutate(classes);
        List<Mutant> inScope = discover(classes, changed);
        warnIfNoMutants(classes, inScope, changed);
        timings.put("discovery", millisSince(discoveryStart));

        if (inScope.isEmpty()) {
            // The fast path a diff-scoped run hits most of the time: no changed line carries a
            // mutant, so no test ever needs to be run.
            listener.phase("skipped", "no mutants in scope");
            cache.write();
            return new AnalysisResult(List.of(), timings, 0, describeScope(changed),
                    model.engine(), List.of(), 0);
        }

        long coverageStart = System.nanoTime();
        Coverage coverage = gatherCoverage(inScope, classes, cache);
        timings.put("coverage", millisSince(coverageStart));

        long executionStart = System.nanoTime();
        List<Mutant> results = execute(inScope, classes, coverage, cache);
        timings.put("execution", millisSince(executionStart));
        timings.put("executionMinionStartup", minionStartupNanos.get() / 1_000_000L);
        timings.put("executionMinionsStarted", (long) minionsStarted.get());
        timings.put("executionSchemataBuild", schemataNanos.get() / 1_000_000L);
        timings.put("executionSchemataInstall", installNanos.get() / 1_000_000L);
        timings.put("executionRunTests", runTestsNanos.get() / 1_000_000L);
        timings.put("executionActivate", activateNanos.get() / 1_000_000L);

        cache.write();
        return new AnalysisResult(
                Mutant.sorted(results),
                timings,
                coverage.testIds().size(),
                describeScope(changed),
                model.engine(),
                coverage.failingTests(),
                reusedFromCache.get());
    }

    // ---------------------------------------------------------------- discovery

    private Map<String, ClassUnderTest> scanClasses() {
        ClassScanner scanner = new ClassScanner(
                model.scope().includeClasses(), model.scope().excludeClasses());
        Map<String, ClassUnderTest> classes = new LinkedHashMap<>();
        for (ModuleModel module : model.modules()) {
            for (ClassBytes c : scanner.scan(module.mutableCodePaths())) {
                // First module wins, which only matters if two modules emit the same class; that
                // is already a broken build, and guessing between them would hide it.
                classes.putIfAbsent(c.binaryName(),
                        new ClassUnderTest(c.binaryName(), c.bytes(), Hashes.of(c.bytes()), module));
            }
        }
        return classes;
    }

    private List<Mutant> discover(Map<String, ClassUnderTest> classes, ChangedLines changed) {
        List<Mutant> inScope = new ArrayList<>();
        for (ClassUnderTest c : classes.values()) {
            for (Mutant m : mutation.discover(c.module().id(), c.bytes())) {
                if (inScope(m, changed)) {
                    inScope.add(m);
                }
            }
        }
        return Mutant.sorted(inScope);
    }

    private boolean inScope(Mutant m, ChangedLines changed) {
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
    private static String sourcePathOf(Mutant m) {
        String className = m.key().className();
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/') + "/";
        String file = m.sourceFile();
        if (file == null || file.isBlank()) {
            String simple = lastDot < 0 ? className : className.substring(lastDot + 1);
            int dollar = simple.indexOf('$');
            file = (dollar < 0 ? simple : simple.substring(0, dollar)) + ".java";
        }
        return packagePath + file;
    }

    /**
     * Diagnostics for the failures that otherwise produce a plausible-looking nothing.
     *
     * <p>A run that finds no classes, no mutants or no tests still completes, prints a score and
     * exits zero. That is the shape of the problems that dominate a mutation tool's support load,
     * so each one says what was looked at and what to check.
     */
    private void warnIfNothingToMutate(Map<String, ClassUnderTest> classes) {
        if (!classes.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (ModuleModel module : model.modules()) {
            for (String path : module.mutableCodePaths()) {
                if (!java.nio.file.Files.exists(Path.of(path))) {
                    missing.add(path);
                }
            }
        }
        StringBuilder message = new StringBuilder("no compiled classes were found to mutate.");
        if (!missing.isEmpty()) {
            message.append(" These code paths do not exist: ").append(String.join(", ", missing))
                    .append(". Compile the project first.");
        } else {
            message.append(" The code paths exist but contain no class files");
            if (!model.scope().includeClasses().isEmpty()) {
                message.append(" that match ").append(String.join(", ", model.scope().includeClasses()));
            }
            message.append(". Run with --dry-run to see the resolved paths.");
        }
        listener.warning(message.toString());
    }

    private void warnIfNoMutants(Map<String, ClassUnderTest> classes, List<Mutant> inScope,
                                 ChangedLines changed) {
        if (!inScope.isEmpty() || classes.isEmpty()) {
            return;
        }
        if (changed != null) {
            // The healthy case for a diff-scoped run, and worth saying plainly so nobody reads it
            // as a failure.
            listener.phase("scope", "no changed line carries a mutant; nothing to analyse");
            return;
        }
        listener.warning(classes.size() + " class(es) were scanned but none yielded a mutant. "
                + "The usual cause is classes compiled without debug information: a mutant with no "
                + "line number cannot be pointed at any source and is not reported. Check that the "
                + "build compiles with -g, which is the default for Gradle and Maven. "
                + "Run 'jzap list-mutants' to see the inventory without running any test.");
    }

    // ---------------------------------------------------------------- coverage

    /**
     * Runs every test-bearing module's suite once, recording which lines each test reaches.
     *
     * <p>Every in-scope class is instrumented for every module's run, not just that module's own
     * classes. A class the module cannot see simply never loads and its probes never fire; a class
     * it can see gets attributed correctly even though it was compiled next door. That is the
     * whole of cross-module test selection.
     */
    private Coverage gatherCoverage(List<Mutant> inScope, Map<String, ClassUnderTest> classes,
                                    MutantCache cache) {
        Set<String> mutatedClasses = new LinkedHashSet<>();
        for (Mutant m : inScope) {
            mutatedClasses.add(m.key().className());
        }
        Map<String, String> testClassHashes = hashTestClasses();
        String coverageKey = coverageKey(classes, testClassHashes);

        Optional<MutantCache.CachedCoverage> cached = cache.reuseCoverage(coverageKey, mutatedClasses);
        if (cached.isPresent()) {
            MutantCache.CachedCoverage reused = cached.get();
            listener.phase("coverage", "reused from cache, " + reused.durations().size() + " test(s)");
            return new Coverage(
                    new LinkedHashMap<>(reused.testsByLocation()),
                    new LinkedHashMap<>(reused.durations()),
                    new LinkedHashMap<>(reused.loopIterations()),
                    new ArrayList<>(reused.failingTests()),
                    new ArrayList<>(reused.durations().keySet()),
                    reused.testModules(),
                    testClassHashes,
                    cache::previousKillingTest);
        }

        ProbeIndex index = new ProbeIndex();
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
        List<ClassBytes> instrumented = new ArrayList<>();
        for (String className : mutatedClasses) {
            ClassUnderTest c = classes.get(className);
            instrumented.add(new ClassBytes(className,
                    instrumenter.instrument(className, c.bytes()), "instrumented"));
        }

        Map<String, Set<String>> testsByLocation = new LinkedHashMap<>();
        Map<String, Long> durations = new LinkedHashMap<>();
        Map<String, Long> loopIterations = new LinkedHashMap<>();
        Map<String, String> testModules = new LinkedHashMap<>();
        List<String> failing = new ArrayList<>();
        List<String> allTests = new ArrayList<>();

        for (ModuleModel module : testBearingModules()) {
            listener.phase("coverage", module.id());
            try (MinionProcess minion = MinionProcess.start(module, jars, true)) {
                minion.initCoverage(index.size(), instrumented);
                List<String> tests = minion.listTests(module.testClassPaths());
                if (tests.isEmpty()) {
                    listener.warning("no tests were discovered in " + module.id() + ". Every mutant "
                            + "it covers will be reported as uncovered. Check that "
                            + "junit-platform-launcher is on the test runtime classpath and that "
                            + "testClassPaths points at compiled test classes; 'jzap run --dry-run' "
                            + "prints both.");
                }
                int done = 0;
                for (String testId : tests) {
                    MinionProcess.TestCoverage result;
                    try {
                        result = minion.runTestForCoverage(testId, 120_000);
                    } catch (MinionProcess.HungException e) {
                        minion.destroy();
                        throw new WireException("coverage run aborted: " + e.getMessage(), e);
                    }
                    allTests.add(testId);
                    // Keyed by test id alone. Two modules declaring the same test class would
                    // collide; that is already a broken build, and the alternative is a compound
                    // key that makes the cache file unreadable.
                    testModules.put(testId, module.id());
                    durations.put(testId, result.durationMillis());
                    loopIterations.put(testId, result.loopIterations());
                    if (!result.passed()) {
                        failing.add(testId);
                        listener.warning("test already fails before any mutant is applied: " + testId
                                + (result.failureMessage() == null ? "" : " -- " + result.failureMessage()));
                    }
                    for (int probe : result.probeIds()) {
                        String location = index.locationOf(probe);
                        if (location != null) {
                            testsByLocation.computeIfAbsent(location, k -> new LinkedHashSet<>())
                                    .add(testId);
                        }
                    }
                    listener.progress(++done, tests.size());
                }
            }
        }

        if (!failing.isEmpty()) {
            listener.warning(failing.size() + " test(s) already fail without any mutant applied. "
                    + "They are excluded from selection, because a mutant they cover would be "
                    + "reported as killed by a failure that has nothing to do with it.");
        }
        cache.recordCoverage(new MutantCache.CachedCoverage(coverageKey, mutatedClasses,
                testsByLocation, durations, loopIterations, testModules, failing));
        return new Coverage(testsByLocation, durations, loopIterations, failing, allTests,
                testModules, testClassHashes, cache::previousKillingTest);
    }

    /**
     * Modules that actually have compiled tests.
     *
     * <p>Declaring a test class path is not the same as having tests in it. An aggregate run over
     * a reactor routinely includes a module with an empty test source set, and starting an
     * analysis JVM for it fails on a classpath that has no test framework at all -- a confusing
     * way to lose a whole run to a module that had nothing to contribute.
     */
    private List<ModuleModel> testBearingModules() {
        ClassScanner scanner = new ClassScanner(List.of(), List.of());
        return model.modules().stream()
                .filter(m -> !m.testClassPaths().isEmpty())
                .filter(m -> !scanner.scan(m.testClassPaths()).isEmpty())
                .toList();
    }

    private Map<String, String> hashTestClasses() {
        Map<String, String> hashes = new LinkedHashMap<>();
        ClassScanner scanner = new ClassScanner(List.of(), List.of());
        for (ModuleModel module : model.modules()) {
            for (ClassBytes c : scanner.scan(module.testClassPaths())) {
                hashes.put(c.binaryName(), Hashes.of(c.bytes()));
            }
        }
        return hashes;
    }

    private String coverageKey(Map<String, ClassUnderTest> classes,
                               Map<String, String> testClassHashes) {
        List<String> parts = new ArrayList<>();
        classes.values().forEach(c -> parts.add("class " + c.binaryName() + "=" + c.hash()));
        testClassHashes.forEach((name, hash) -> parts.add("test " + name + "=" + hash));
        parts.sort(String::compareTo);
        return Hashes.ofLines(parts);
    }

    // ---------------------------------------------------------------- execution

    private List<Mutant> execute(List<Mutant> mutants, Map<String, ClassUnderTest> classes,
                                 Coverage coverage, MutantCache cache) {
        List<Mutant> results = new ArrayList<>();
        List<Mutant> covered = new ArrayList<>();

        for (Mutant mutant : mutants) {
            List<String> selected = coverage.selectFor(mutant);
            String classHash = classes.get(mutant.key().className()).hash();

            // The cache is consulted before anything is executed, including for uncovered
            // mutants: "no test covers this" is a verdict like any other, and it becomes wrong
            // the moment a test appears that does.
            Optional<Mutant> reused = cache.reuse(mutant, classHash, selected, coverage.testClassHashes());
            if (reused.isPresent()) {
                results.add(reused.get());
                cache.carryForward(mutant.key());
                reusedFromCache.incrementAndGet();
                continue;
            }
            if (selected.isEmpty()) {
                Mutant uncovered = mutant.withOutcome(MutantStatus.NO_COVERAGE, null, 0, 0, 0);
                results.add(uncovered);
                cache.record(uncovered, classHash, selected, coverage.testClassHashes());
                continue;
            }
            covered.add(mutant);
        }

        if (covered.isEmpty()) {
            return results;
        }

        // Partitioned by class, not round robin. A worker that stays on one class keeps it loaded
        // and JIT-compiled; scattering classes across workers re-pays class loading and warmup
        // for every mutant.
        Map<String, List<Mutant>> byClass = new LinkedHashMap<>();
        for (Mutant mutant : covered) {
            byClass.computeIfAbsent(mutant.key().className(), k -> new ArrayList<>()).add(mutant);
        }
        Queue<List<Mutant>> queue = new ConcurrentLinkedQueue<>(byClass.values());

        int threads = workersFor(covered, coverage, byClass.size());
        listener.phase("execution", covered.size() + " mutants on " + threads + " thread(s)");

        Collection<Mutant> analysed = new ConcurrentLinkedQueue<>();
        AtomicInteger done = new AtomicInteger();
        if (threads == 1) {
            new Worker(queue, classes, coverage, cache, analysed, done, covered.size()).run();
        } else {
            runInParallel(threads, queue, classes, coverage, cache, analysed, done, covered.size());
        }
        results.addAll(analysed);
        return results;
    }

    /**
     * How many analysis JVMs are worth starting for this much work.
     *
     * <p>Not simply the requested thread count. Starting one costs around a quarter of a second,
     * and it starts cold -- the first mutants it runs pay for JIT warmup the previous JVM had
     * already paid for. Once the per-mutant cost fell to about 1.5 ms, twenty workers on a
     * two-second job measured *slower* than one: 4.06s against 3.20s, because the run was mostly
     * twenty JVM startups.
     *
     * <p>So the cap is the work itself. One extra worker per {@code WORK_PER_WORKER_MILLIS} of
     * estimated work, which is roughly twice what starting one costs, and never more workers than
     * there are classes to give them.
     */
    private int workersFor(List<Mutant> covered, Coverage coverage, int classCount) {
        int requested = Math.max(1, Math.min(model.threads(), classCount));
        if (requested == 1) {
            return 1;
        }
        long estimatedMillis = 0;
        for (Mutant mutant : covered) {
            List<String> selected = coverage.selectFor(mutant);
            // The first test decides most mutants, so it is what a mutant usually costs.
            long testMillis = selected.isEmpty()
                    ? 0
                    : coverage.durations().getOrDefault(selected.get(0), 0L);
            estimatedMillis += testMillis + PER_MUTANT_OVERHEAD_MILLIS;
        }
        int justified = (int) Math.max(1, estimatedMillis / WORK_PER_WORKER_MILLIS);
        int workers = Math.min(requested, justified);
        if (workers < requested) {
            listener.phase("execution", "using " + workers + " of " + requested
                    + " requested thread(s): about " + estimatedMillis + "ms of work does not "
                    + "justify more analysis JVMs, which cost roughly "
                    + JVM_STARTUP_MILLIS + "ms each to start cold");
        }
        return workers;
    }

    /** Rough cost of running one mutant's tests once a JVM is warm, measured on the bench fixture. */
    private static final long PER_MUTANT_OVERHEAD_MILLIS = 2;

    /** Rough cost of starting an analysis JVM and getting it warm. */
    private static final long JVM_STARTUP_MILLIS = 250;

    /** Work that justifies one more worker: about twice what starting one costs. */
    private static final long WORK_PER_WORKER_MILLIS = 2 * JVM_STARTUP_MILLIS;

    private void runInParallel(int threads, Queue<List<Mutant>> queue,
                               Map<String, ClassUnderTest> classes, Coverage coverage,
                               MutantCache cache, Collection<Mutant> analysed,
                               AtomicInteger done, int total) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "jzap-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(
                        new Worker(queue, classes, coverage, cache, analysed, done, total)));
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
     * Analyses whole classes taken from a shared queue, in analysis JVMs of its own.
     *
     * <p>One JVM per test-bearing module, started on demand: a mutant's covering tests can live in
     * several modules, and each needs its own classpath. Each worker owning its own JVMs is what
     * makes a hung mutant survivable — the process is killed and only that worker pauses.
     */
    private final class Worker implements Runnable {

        private final Queue<List<Mutant>> queue;
        private final Map<String, ClassUnderTest> classes;
        private final Coverage coverage;
        private final MutantCache cache;
        private final Collection<Mutant> analysed;
        private final AtomicInteger done;
        private final int total;

        private final Map<String, MinionProcess> minions = new LinkedHashMap<>();
        private int sinceRestart;

        /** The schemata class currently installed in each minion, and its mutant indices. */
        private String installedClass;
        private byte[] installedSchemata;
        private Map<MutantKey, Integer> installedIndices = Map.of();
        private final Set<String> minionsHoldingSchemata = new LinkedHashSet<>();

        Worker(Queue<List<Mutant>> queue, Map<String, ClassUnderTest> classes, Coverage coverage,
               MutantCache cache, Collection<Mutant> analysed, AtomicInteger done, int total) {
            this.queue = queue;
            this.classes = classes;
            this.coverage = coverage;
            this.cache = cache;
            this.analysed = analysed;
            this.done = done;
            this.total = total;
        }

        @Override
        public void run() {
            try {
                List<Mutant> batch;
                while ((batch = queue.poll()) != null) {
                    prepareSchemata(batch);
                    for (Mutant mutant : batch) {
                        analysed.add(analyseOne(mutant));
                        listener.progress(done.incrementAndGet(), total);
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
         */
        private void prepareSchemata(List<Mutant> batch) {
            installedClass = null;
            installedSchemata = null;
            installedIndices = Map.of();
            minionsHoldingSchemata.clear();
            if (!model.usesSchemata() || batch.isEmpty()) {
                return;
            }
            String className = batch.get(0).key().className();
            ClassUnderTest target = classes.get(className);
            if (target == null) {
                return;
            }
            long start = System.nanoTime();
            try {
                SchemataTransformer.Result result =
                        SchemataTransformer.transform(target.bytes(), batch);
                schemataNanos.addAndGet(System.nanoTime() - start);
                if (result.indices().isEmpty()) {
                    return;
                }
                installedClass = className;
                installedSchemata = result.schemata();
                installedIndices = result.indices();
            } catch (RuntimeException e) {
                // A class that cannot be transformed is analysed the slow way rather than not at
                // all; the verdicts are the same either way.
                listener.warning("schemata could not be built for " + className + ", falling back "
                        + "to per-mutant redefinition: " + e.getMessage());
            }
        }

        private Mutant analyseOne(Mutant mutant) {
            List<String> selected = coverage.selectFor(mutant);
            long mutantStart = System.nanoTime();

            // A mutant in a static initialiser only takes effect if the class has not been loaded
            // yet, so it gets fresh JVMs. PIT reports these as surviving for want of this, and
            // documents it as a known limitation.
            boolean needsFreshJvm = "<clinit>".equals(mutant.key().methodName());
            if (needsFreshJvm || sinceRestart >= model.maxMutantsPerMinion()) {
                recycleAll();
            }

            Integer schemataIndex = mutant.key().className().equals(installedClass)
                    ? installedIndices.get(mutant.key())
                    : null;

            byte[] mutated = null;
            if (schemataIndex == null) {
                try {
                    mutated = mutation.apply(classes.get(mutant.key().className()).bytes(), mutant.key());
                } catch (RuntimeException e) {
                    listener.warning("mutant " + mutant.key().asString() + " could not be generated: "
                            + e.getMessage());
                    return mutant.withOutcome(MutantStatus.RUN_ERROR, null, selected.size(), 0,
                            millisSince(mutantStart));
                }
            }

            MutantStatus status = MutantStatus.SURVIVED;
            String killingTest = null;
            int testsRun = 0;

            // Grouped by module and run group by group, stopping at the first kill. Ordering
            // within a group already puts the previously-killing test first.
            for (Map.Entry<String, List<String>> group : coverage.groupByModule(selected).entrySet()) {
                ModuleModel module = moduleById(group.getKey());
                if (module == null) {
                    continue;
                }
                MinionProcess minion;
                try {
                    minion = minionFor(module);
                    if (schemataIndex != null) {
                        installSchemataIfNeeded(minion, module.id());
                    } else {
                        minion.setOverride(mutant.key().className(), mutated);
                    }
                } catch (WireException | IllegalStateException e) {
                    listener.warning("mutant " + mutant.key().asString() + " could not be analysed: "
                            + e.getMessage());
                    discard(module.id());
                    status = MutantStatus.RUN_ERROR;
                    break;
                }
                try {
                    long runStart = System.nanoTime();
                    MinionProcess.MutantOutcome outcome = minion.runTests(group.getValue(),
                            coverage.iterationLimitFor(group.getValue()),
                            coverage.timeoutFor(group.getValue(), model),
                            schemataIndex != null ? schemataIndex : MutantSwitch.NONE);
                    runTestsNanos.addAndGet(System.nanoTime() - runStart);
                    testsRun += outcome.testsRun();
                    sinceRestart++;
                    MutantStatus fromGroup = switch (outcome.code()) {
                        case Wire.OUTCOME_FAILED -> MutantStatus.KILLED;
                        case Wire.OUTCOME_ALL_PASSED -> MutantStatus.SURVIVED;
                        case Wire.OUTCOME_NON_VIABLE -> MutantStatus.NON_VIABLE;
                        case Wire.OUTCOME_RUNAWAY -> MutantStatus.TIMED_OUT;
                        default -> MutantStatus.RUN_ERROR;
                    };
                    if (schemataIndex == null) {
                        // The schemata class stays installed between mutants; a redefined one has
                        // to be put back.
                        minion.clearOverrides();
                        minionsHoldingSchemata.remove(module.id());
                    }
                    if (fromGroup != MutantStatus.SURVIVED) {
                        status = fromGroup;
                        killingTest = fromGroup == MutantStatus.KILLED ? outcome.failingTest() : null;
                        break;
                    }
                } catch (MinionProcess.HungException e) {
                    status = MutantStatus.TIMED_OUT;
                    discard(module.id());
                    break;
                } catch (WireException | IllegalStateException e) {
                    listener.warning("mutant " + mutant.key().asString() + " could not be analysed: "
                            + e.getMessage());
                    status = MutantStatus.RUN_ERROR;
                    discard(module.id());
                    break;
                }
            }

            Mutant result = mutant.withOutcome(status, killingTest, selected.size(), testsRun,
                    millisSince(mutantStart));
            cache.record(result, classes.get(mutant.key().className()).hash(), selected,
                    coverage.testClassHashes());
            return result;
        }

        private MinionProcess minionFor(ModuleModel module) {
            MinionProcess existing = minions.get(module.id());
            if (existing != null && existing.isAlive()) {
                return existing;
            }
            long start = System.nanoTime();
            MinionProcess started = MinionProcess.start(module, jars, true);
            started.prepareTests(module.testClassPaths());
            minionStartupNanos.addAndGet(System.nanoTime() - start);
            minionsStarted.incrementAndGet();
            minions.put(module.id(), started);
            return started;
        }

        private void installSchemataIfNeeded(MinionProcess minion, String moduleId) {
            if (minionsHoldingSchemata.add(moduleId)) {
                long start = System.nanoTime();
                minion.setOverride(installedClass, installedSchemata);
                installNanos.addAndGet(System.nanoTime() - start);
            }
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

    private ModuleModel moduleById(String id) {
        return model.modules().stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- support

    private MutantCache openCache() {
        CacheConfig config = model.cache();
        Path file = config.enabled() && config.dir() != null
                ? Path.of(config.dir()).resolve("jzap-cache.txt")
                : null;
        return MutantCache.open(file, new MutantCache.Header(
                model.engine(),
                EngineVersion.get(),
                String.join(",", Mutators.resolve(model.scope().mutators()).stream()
                        .map(Mutator::id).sorted().toList()),
                effectiveFilters(),
                config.toolchain() != null ? config.toolchain() : Hashes.toolchain()));
    }

    /**
     * The filters actually in force, for the cache header.
     *
     * <p>Load-bearing rather than decorative: every filter changes the inventory, so a cache that
     * ignored the set would serve verdicts for a different set of mutants.
     */
    private String effectiveFilters() {
        List<String> active = new ArrayList<>();
        if (model.scope().isFilterEnabled(LoopCounterFilter.ID)) {
            active.add(LoopCounterFilter.ID);
        }
        if (model.scope().isFilterEnabled(KotlinFilter.ID)) {
            active.add(KotlinFilter.ID);
        }
        if (model.scope().isOptionalFilterEnabled(EquivalenceFilter.ID)) {
            active.add(EquivalenceFilter.ID);
        }
        if (model.scope().isOptionalFilterEnabled(AridFilter.ID)) {
            active.add(AridFilter.ID);
        }
        if (model.scope().isOptionalFilterEnabled(ONE_PER_LINE)) {
            active.add(ONE_PER_LINE);
        }
        active.sort(String::compareTo);
        return String.join(",", active);
    }

    private String describeModules() {
        return model.modules().size() == 1
                ? model.modules().get(0).id()
                : model.modules().size() + " modules";
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

    /** Per-test coverage plus the baselines that timeouts and ordering are derived from. */
    private record Coverage(
            Map<String, Set<String>> testsByLocation,
            Map<String, Long> durations,
            Map<String, Long> loopIterations,
            List<String> failingTests,
            List<String> testIds,
            Map<String, String> testModules,
            Map<String, String> testClassHashes,
            java.util.function.Function<MutantKey, Optional<String>> previousKillingTest) {

        /**
         * Tests that execute the mutated line: the one that killed it last time first, then the
         * rest cheapest first.
         *
         * <p>Ordering matters because of early exit. Everything tried before the test that
         * actually kills a mutant is wasted, and the test that killed it in the previous run is
         * overwhelmingly likely to kill it again.
         */
        List<String> selectFor(Mutant mutant) {
            Set<String> tests = testsByLocation.get(ProbeIndex.key(
                    mutant.key().className(), mutant.key().methodName(),
                    mutant.key().descriptor(), mutant.key().line()));
            if (tests == null) {
                return List.of();
            }
            String killedItLastTime = previousKillingTest.apply(mutant.key()).orElse(null);
            return tests.stream()
                    .filter(t -> !failingTests.contains(t))
                    .sorted(Comparator
                            .comparing((String t) -> !t.equals(killedItLastTime))
                            .thenComparingLong(t -> durations.getOrDefault(t, 0L)))
                    .toList();
        }

        /** The selected tests grouped by the module that has to run them, order preserved. */
        Map<String, List<String>> groupByModule(List<String> selected) {
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (String test : selected) {
                String module = testModules.get(test);
                if (module != null) {
                    grouped.computeIfAbsent(module, k -> new ArrayList<>()).add(test);
                }
            }
            return grouped;
        }

        /**
         * Loop iterations past which a mutant is declared runaway.
         *
         * <p>Ten times what the unmutated code needed, with a floor so that code which loops
         * barely at all still has room. A mutant that does an order of magnitude more work than
         * the original is not doing the same job slowly; it is not stopping.
         */
        long iterationLimitFor(List<String> selected) {
            long baseline = selected.stream()
                    .mapToLong(t -> loopIterations.getOrDefault(t, 0L))
                    .max().orElse(0L);
            return Math.max(1_000_000L, baseline * 10);
        }

        int timeoutFor(List<String> selected, ProjectModel model) {
            long baseline = selected.stream().mapToLong(t -> durations.getOrDefault(t, 0L)).sum();
            long timeout = (long) (baseline * model.timeoutFactor()) + model.timeoutConstMillis();
            return (int) Math.max(1_000L, Math.min(timeout, 600_000L));
        }
    }
}
