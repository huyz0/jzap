package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.CacheConfig;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an analysis: find the mutants in scope, learn which tests reach them, decide each one.
 *
 * <p>This class owns the order of those phases and the diagnostics for when one of them comes
 * back empty. The phases themselves are {@link CoverageCollector} and {@link MutantExecutor}.
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
    private final MutantFilters filters;
    private final MutationEngine mutation;

    public AnalysisEngine(ProjectModel model, Listener listener) {
        this.model = model;
        this.listener = listener == null ? Listener.SILENT : listener;
        this.jars = RuntimeJars.discover();
        this.filters = MutantFilters.from(model.scope());
        this.mutation = new MutationEngine(Mutators.resolve(model.scope().mutators()), filters);
    }

    /**
     * @param changed lines in scope, or null when the whole codebase is in scope
     */
    public AnalysisResult analyse(ChangedLines changed) {
        PhaseTimings timings = new PhaseTimings();
        MutantCache cache = openCache();
        cache.discardReason().ifPresent(reason ->
                listener.warning("not reusing the previous cache: " + reason));

        long discoveryStart = System.nanoTime();
        listener.phase("discovery", describeModules());
        Map<String, ClassUnderTest> classes = scanClasses();
        warnIfNothingToMutate(classes);
        List<Mutant> inScope = discover(classes, changed);
        warnIfNoMutants(classes, inScope, changed);
        timings.addSince("discovery", discoveryStart);

        if (inScope.isEmpty()) {
            // The fast path a diff-scoped run hits most of the time: no changed line carries a
            // mutant, so no test ever needs to be run.
            listener.phase("skipped", "no mutants in scope");
            cache.write();
            return new AnalysisResult(List.of(), timings.toMap(), 0, describeScope(changed),
                    model.engine(), List.of(), 0);
        }

        long coverageStart = System.nanoTime();
        Coverage coverage = new CoverageCollector(model, listener, jars, timings)
                .gather(inScope, classes, cache);
        timings.addSince("coverage", coverageStart);

        MutantExecutor executor = new MutantExecutor(model, listener, jars, mutation, timings);
        long executionStart = System.nanoTime();
        List<Mutant> results = executor.execute(inScope, classes, coverage, cache);
        timings.addSince("execution", executionStart);

        cache.write();
        return new AnalysisResult(
                Mutant.sorted(results),
                timings.toMap(),
                coverage.testIds().size(),
                describeScope(changed),
                model.engine(),
                new ArrayList<>(coverage.failingTests()),
                executor.reusedFromCache());
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
        String sourcePath = m.sourcePath();
        return model.scope().isClassGranularity()
                ? changed.containsPath(sourcePath)
                : changed.containsLine(sourcePath, m.key().line());
    }

    // ---------------------------------------------------------------- diagnostics

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
                if (!Files.exists(Path.of(path))) {
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
                filters.cacheKey(),
                config.toolchain() != null ? config.toolchain() : Hashes.toolchain()));
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
}
