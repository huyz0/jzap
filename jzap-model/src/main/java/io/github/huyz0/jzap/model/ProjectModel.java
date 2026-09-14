package io.github.huyz0.jzap.model;

import java.util.List;

/**
 * The single seam between build tools and the jzap engine.
 *
 * <p>Deliberately a versioned document rather than a flag soup: adapters become dumb and
 * testable, bug reports become reproducible by attaching the model, and the engine version
 * can move independently of the plugin version. See docs/architecture.md.
 *
 * @param schemaVersion major version of this document's shape
 * @param modules       the modules to analyse; a list, so one invocation can cover a whole
 *                      reactor and warm the daemon once
 * @param scope         what to analyse
 * @param cache         incremental cache configuration
 * @param reporters     reporter ids to run
 * @param threads       analysis JVMs; 0 means {@link #DEFAULT_THREADS}
 * @param timeoutFactor multiplier applied to a test's baseline duration before declaring a hang
 * @param timeoutConstMillis constant added to the timeout, to absorb scheduling noise
 * @param maxMutantsPerMinion mutants analysed in one forked JVM before it is recycled, which
 *                      bounds state drift between mutants. Defaults to 1000: recycling is a hedge
 *                      against state drift that has never been observed, including on a fixture
 *                      written to leak, and the hedge was measured costing 2.9x on the execution
 *                      phase at the previous default of 100 -- because a fresh JVM throws away the
 *                      JIT warmup the previous mutants paid for. Set it to 1 for a JVM per mutant,
 *                      which is the sound reference the soundness gate compares against.
 * @param engine        {@code schemata} compiles every mutant of a class in at once and selects
 *                      one with a field write; {@code naive} redefines the class per mutant and is
 *                      kept as the reference implementation every optimisation is compared against
 */
public record ProjectModel(
        int schemaVersion,
        List<ModuleModel> modules,
        Scope scope,
        CacheConfig cache,
        List<String> reporters,
        int threads,
        double timeoutFactor,
        long timeoutConstMillis,
        int maxMutantsPerMinion,
        String engine) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Analysis JVMs when none is asked for. One, deliberately.
     *
     * <p>It used to be {@code availableProcessors()}, and that was measured wrong. On a four-core
     * CI runner the benchmark fixture took 5.66s at one thread, 6.88s at two and 8.21s at four --
     * so the default configuration was 31% slower than {@code -t 1} on the commonest machine jzap
     * runs on, with non-overlapping ranges over three runs. On a twenty-core machine the same
     * fixture gained 1.14x from two threads. The upside of guessing is therefore about 14% and the
     * downside about 45%, and jzap cannot tell in advance which it will get: what decides it is
     * whether the tests are CPU-bound or waiting on something, and nothing here measures that.
     *
     * <p>So the default is the one setting that is never worse than asking for it, and a project
     * that knows its tests are slow or I/O-bound opts in with {@code --threads} or
     * {@code jzap { threads = N }}. A suite whose tests sleep gains almost linearly; see
     * fixtures/parallel-java, which exists to exercise that path.
     *
     * <p>The wins jzap is actually built on are much larger than the one being given up here --
     * diff scoping, the schemata engine, the cache and the daemon are each multiples rather than
     * percentages -- so a default that can silently cost half a run's time to chase 14% is a bad
     * trade even before the asymmetry.
     */
    public static final int DEFAULT_THREADS = 1;

    public ProjectModel {
        if (schemaVersion == 0) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion " + schemaVersion + "; this build understands "
                            + CURRENT_SCHEMA_VERSION);
        }
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("at least one module is required");
        }
        modules = List.copyOf(modules);
        scope = scope == null ? Scope.all() : scope;
        cache = cache == null ? CacheConfig.disabled() : cache;
        reporters = reporters == null || reporters.isEmpty() ? List.of("console", "json") : List.copyOf(reporters);
        threads = threads <= 0 ? DEFAULT_THREADS : threads;
        timeoutFactor = timeoutFactor <= 0 ? 1.5 : timeoutFactor;
        timeoutConstMillis = timeoutConstMillis <= 0 ? 4000L : timeoutConstMillis;
        maxMutantsPerMinion = maxMutantsPerMinion <= 0 ? 1000 : maxMutantsPerMinion;
        engine = engine == null || engine.isBlank() ? "schemata" : engine;
        if (!engine.equals("schemata") && !engine.equals("naive")) {
            throw new IllegalArgumentException(
                    "engine must be 'schemata' or 'naive', got: " + engine);
        }
    }

    public boolean usesSchemata() {
        return "schemata".equals(engine);
    }

    public ModuleModel module(String id) {
        return modules.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such module: " + id));
    }

    public ProjectModel withScope(Scope newScope) {
        return new ProjectModel(schemaVersion, modules, newScope, cache, reporters, threads,
                timeoutFactor, timeoutConstMillis, maxMutantsPerMinion, engine);
    }

    public ProjectModel withCache(CacheConfig newCache) {
        return new ProjectModel(schemaVersion, modules, scope, newCache, reporters, threads,
                timeoutFactor, timeoutConstMillis, maxMutantsPerMinion, engine);
    }

    public ProjectModel withThreads(int newThreads) {
        return new ProjectModel(schemaVersion, modules, scope, cache, reporters, newThreads,
                timeoutFactor, timeoutConstMillis, maxMutantsPerMinion, engine);
    }

    public ProjectModel withEngine(String newEngine) {
        return new ProjectModel(schemaVersion, modules, scope, cache, reporters, threads,
                timeoutFactor, timeoutConstMillis, maxMutantsPerMinion, newEngine);
    }
}
