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
 * @param threads       analysis threads; 0 means one per available processor
 * @param timeoutFactor multiplier applied to a test's baseline duration before declaring a hang
 * @param timeoutConstMillis constant added to the timeout, to absorb scheduling noise
 * @param maxMutantsPerMinion mutants analysed in one forked JVM before it is recycled, which
 *                      bounds state drift between mutants
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
        int maxMutantsPerMinion) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

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
        threads = threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
        timeoutFactor = timeoutFactor <= 0 ? 1.5 : timeoutFactor;
        timeoutConstMillis = timeoutConstMillis <= 0 ? 4000L : timeoutConstMillis;
        maxMutantsPerMinion = maxMutantsPerMinion <= 0 ? 100 : maxMutantsPerMinion;
    }

    public ModuleModel module(String id) {
        return modules.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such module: " + id));
    }

    public ProjectModel withScope(Scope newScope) {
        return new ProjectModel(schemaVersion, modules, newScope, cache, reporters, threads,
                timeoutFactor, timeoutConstMillis, maxMutantsPerMinion);
    }
}
