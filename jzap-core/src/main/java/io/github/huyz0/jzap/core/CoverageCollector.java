package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.wire.WireException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs every test-bearing module's suite once, recording which lines each test reaches.
 *
 * <p>Every in-scope class is instrumented for every module's run, not just that module's own
 * classes. A class the module cannot see simply never loads and its probes never fire; a class
 * it can see gets attributed correctly even though it was compiled next door. That is the
 * whole of cross-module test selection.
 *
 * <h2>Sequential, and measured to be right</h2>
 *
 * One test at a time in one JVM per module, which is what {@code gradle test} does and therefore
 * what the project's own baseline means. Splitting the suite across JVMs was measured on the
 * bench fixture and rejected: of an 810ms phase only the 463ms of per-test work parallelises,
 * each extra JVM costs about 57ms to start, and the best case saved 190ms of a 2910ms run. It
 * would also change test isolation, and a test that passes or fails differently changes which
 * tests are excluded from selection and therefore changes verdicts. See docs/profiling.md.
 *
 * <p>It must stay sequential within a JVM regardless of that: {@link io.github.huyz0.jzap.agent.CoverageRecorder}
 * is a global array drained between tests and {@link io.github.huyz0.jzap.agent.LoopGuard} a global counter,
 * so two tests at once would cross-attribute coverage.
 */
final class CoverageCollector {

    private static final int TEST_TIMEOUT_MILLIS = 120_000;

    private final ProjectModel model;
    private final AnalysisEngine.Listener listener;
    private final RuntimeJars.Jars jars;
    private final PhaseTimings timings;

    CoverageCollector(ProjectModel model, AnalysisEngine.Listener listener, RuntimeJars.Jars jars,
                      PhaseTimings timings) {
        this.model = model;
        this.listener = listener;
        this.jars = jars;
        this.timings = timings;
    }

    Coverage gather(List<Mutant> inScope, Map<String, ClassUnderTest> classes, MutantCache cache) {
        Set<String> mutatedClasses = new LinkedHashSet<>();
        for (Mutant m : inScope) {
            mutatedClasses.add(m.key().className());
        }
        Map<String, String> testClassHashes = hashTestClasses();
        String coverageKey = coverageKey(classes, testClassHashes);

        Optional<MutantCache.CachedCoverage> cached = cache.reuseCoverage(coverageKey, mutatedClasses);
        if (cached.isPresent()) {
            return fromCache(cached.get(), testClassHashes, cache);
        }

        ProbeIndex index = new ProbeIndex();
        List<ClassBytes> instrumented = instrument(mutatedClasses, classes, index);
        Tally tally = new Tally();
        for (ModuleModel module : testBearingModules()) {
            listener.phase("coverage", module.id());
            runSuite(module, index, instrumented, tally);
        }
        warnAboutFailingTests(tally.failing);

        cache.recordCoverage(new MutantCache.CachedCoverage(coverageKey, mutatedClasses,
                tally.testsByLocation, tally.durations, tally.loopIterations, tally.testModules,
                new ArrayList<>(tally.failing)));
        return new Coverage(tally.testsByLocation, tally.durations, tally.loopIterations,
                tally.failing, tally.allTests, tally.testModules, testClassHashes,
                cache::previousKillingTest);
    }

    /** What one pass over the suites accumulates. */
    private static final class Tally {
        final Map<String, Set<String>> testsByLocation = new LinkedHashMap<>();
        final Map<String, Long> durations = new LinkedHashMap<>();
        final Map<String, Long> loopIterations = new LinkedHashMap<>();
        final Map<String, String> testModules = new LinkedHashMap<>();
        final Set<String> failing = new LinkedHashSet<>();
        final List<String> allTests = new ArrayList<>();
    }

    private Coverage fromCache(MutantCache.CachedCoverage reused, Map<String, String> testClassHashes,
                               MutantCache cache) {
        listener.phase("coverage", "reused from cache, " + reused.durations().size() + " test(s)");
        return new Coverage(
                new LinkedHashMap<>(reused.testsByLocation()),
                new LinkedHashMap<>(reused.durations()),
                new LinkedHashMap<>(reused.loopIterations()),
                new LinkedHashSet<>(reused.failingTests()),
                new ArrayList<>(reused.durations().keySet()),
                reused.testModules(),
                testClassHashes,
                cache::previousKillingTest);
    }

    private List<ClassBytes> instrument(Set<String> mutatedClasses,
                                        Map<String, ClassUnderTest> classes, ProbeIndex index) {
        long start = System.nanoTime();
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
        List<ClassBytes> instrumented = new ArrayList<>(mutatedClasses.size());
        for (String className : mutatedClasses) {
            ClassUnderTest c = classes.get(className);
            instrumented.add(new ClassBytes(className,
                    instrumenter.instrument(className, c.bytes()), "instrumented"));
        }
        timings.addSince("coverageInstrument", start);
        return instrumented;
    }

    private void runSuite(ModuleModel module, ProbeIndex index, List<ClassBytes> instrumented,
                          Tally tally) {
        long startupStart = System.nanoTime();
        try (MinionProcess minion = MinionProcess.start(module, jars)) {
            minion.initCoverage(index.size(), instrumented);
            timings.addSince("coverageStartup", startupStart);

            long discoveryStart = System.nanoTime();
            List<String> tests = minion.listTests(module.testClassPaths());
            timings.addSince("coverageDiscovery", discoveryStart);
            if (tests.isEmpty()) {
                warnAboutEmptySuite(module);
            }

            int done = 0;
            for (String testId : tests) {
                record(module, testId, runTest(minion, testId), index, tally);
                listener.progress(++done, tests.size());
            }
        }
    }

    private MinionProcess.TestCoverage runTest(MinionProcess minion, String testId) {
        long start = System.nanoTime();
        try {
            MinionProcess.TestCoverage result = minion.runTestForCoverage(testId, TEST_TIMEOUT_MILLIS);
            timings.addSince("coverageRunTests", start);
            return result;
        } catch (MinionProcess.HungException e) {
            // Nothing useful survives a suite that will not finish unmutated: every verdict
            // downstream would be derived from a baseline that does not exist.
            minion.destroy();
            throw new WireException("coverage run aborted: " + e.getMessage(), e);
        }
    }

    private void record(ModuleModel module, String testId, MinionProcess.TestCoverage result,
                        ProbeIndex index, Tally tally) {
        tally.allTests.add(testId);
        // Keyed by test id alone. Two modules declaring the same test class would collide; that
        // is already a broken build, and the alternative is a compound key that makes the cache
        // file unreadable.
        tally.testModules.put(testId, module.id());
        tally.durations.put(testId, result.durationMillis());
        tally.loopIterations.put(testId, result.loopIterations());
        if (!result.passed()) {
            tally.failing.add(testId);
            listener.warning("test already fails before any mutant is applied: " + testId
                    + (result.failureMessage() == null ? "" : " -- " + result.failureMessage()));
        }
        for (int probe : result.probeIds()) {
            String location = index.locationOf(probe);
            if (location != null) {
                tally.testsByLocation.computeIfAbsent(location, k -> new LinkedHashSet<>())
                        .add(testId);
            }
        }
    }

    private void warnAboutEmptySuite(ModuleModel module) {
        listener.warning("no tests were discovered in " + module.id() + ". Every mutant "
                + "it covers will be reported as uncovered. Check that "
                + "junit-platform-launcher is on the test runtime classpath and that "
                + "testClassPaths points at compiled test classes; 'jzap run --dry-run' "
                + "prints both.");
    }

    private void warnAboutFailingTests(Set<String> failing) {
        if (failing.isEmpty()) {
            return;
        }
        listener.warning(failing.size() + " test(s) already fail without any mutant applied. "
                + "They are excluded from selection, because a mutant they cover would be "
                + "reported as killed by a failure that has nothing to do with it.");
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

    /**
     * Identity of everything the coverage map depends on.
     *
     * <p>Every scanned class and every test class, because a changed production class can alter
     * which lines its callers reach: keying on only the changed class would be unsound.
     */
    private String coverageKey(Map<String, ClassUnderTest> classes,
                               Map<String, String> testClassHashes) {
        List<String> parts = new ArrayList<>();
        classes.values().forEach(c -> parts.add("class " + c.binaryName() + "=" + c.hash()));
        testClassHashes.forEach((name, hash) -> parts.add("test " + name + "=" + hash));
        parts.sort(String::compareTo);
        return Hashes.ofLines(parts);
    }
}
