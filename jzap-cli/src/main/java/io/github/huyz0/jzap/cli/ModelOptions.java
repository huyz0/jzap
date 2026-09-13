package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.core.MutantFilters;
import io.github.huyz0.jzap.model.ModelIo;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.model.ScopeKind;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** The options every command shares: which model to read, and how to narrow its scope. */
final class ModelOptions {

    @Option(names = {"-m", "--project-model"}, required = true, paramLabel = "FILE",
            description = "Project model JSON, as emitted by a build-tool adapter.")
    Path modelFile;

    @Option(names = "--from", paramLabel = "REF",
            description = "Base git ref for diff scoping. Default: HEAD.")
    String from;

    @Option(names = "--to", paramLabel = "REF",
            description = "Tip git ref for diff scoping. Use -Local- for uncommitted changes "
                    + "(the default) or -Empty- for the empty tree.")
    String to;

    @Option(names = "--patch", paramLabel = "FILE",
            description = "Unified diff to scope by, instead of a git range. Needs no repository.")
    Path patchFile;

    @Option(names = "--scope", paramLabel = "line|class",
            description = "Mutate only changed lines (default), or every mutant in a changed class.")
    String granularity;

    @Option(names = "--all", description = "Analyse everything, ignoring any diff scoping.")
    boolean all;

    @Option(names = "--mutators", split = ",", paramLabel = "ID",
            description = "Mutators to use. Default: the standard set.")
    List<String> mutators;

    @Option(names = "--include", split = ",", paramLabel = "GLOB",
            description = "Only mutate classes matching these globs, e.g. com.example.*")
    List<String> include;

    @Option(names = "--exclude", split = ",", paramLabel = "GLOB",
            description = "Never mutate classes matching these globs.")
    List<String> exclude;

    @Option(names = "--dedup",
            description = "Drop mutants whose compiled form matches the original's, which can "
                    + "never be killed, or another mutant's, which would share its verdict. "
                    + "Off by default: PIT does not do this, so every dropped mutant becomes a "
                    + "difference against the correctness oracle.")
    boolean dedup;

    @Option(names = "--arid",
            description = "Drop mutants in code that reports rather than decides: logging calls, "
                    + "and void methods whose calls are all logging. Off by default.")
    boolean arid;

    @Option(names = "--one-per-line",
            description = "Keep at most one mutant per source line. Off by default.")
    boolean onePerLine;

    @Option(names = "--mutate-kotlin-internals",
            description = "Also mutate constructs the Kotlin compiler generated: property "
                    + "accessors, data class members, null-check intrinsics and for-each loop "
                    + "scaffolding. Off by default, because no developer mistake produces them.")
    boolean mutateKotlinInternals;

    @Option(names = "--mutate-loop-counters",
            description = "Also mutate loop counters. Off by default: negating a loop counter "
                    + "either hangs the test or crashes it immediately, so the mutant dies for "
                    + "a reason unrelated to what the test checks.")
    boolean mutateLoopCounters;

    /** Default-on filters the user asked to switch off, plus whatever the model already listed. */
    private List<String> disabledFilters(Scope fromModel) {
        List<String> filters = new ArrayList<>(fromModel.disabledFilters());
        if (mutateLoopCounters) {
            filters.add(MutantFilters.LOOP_COUNTERS);
        }
        if (mutateKotlinInternals) {
            filters.add(MutantFilters.KOTLIN_JUNK);
        }
        return List.copyOf(new LinkedHashSet<>(filters));
    }

    /** Filters that are off unless asked for, plus whatever the model already requested. */
    private List<String> optionalFilters(Scope fromModel) {
        List<String> filters = new ArrayList<>(fromModel.enabledFilters());
        if (dedup) {
            filters.add(MutantFilters.EQUIVALENCE);
        }
        if (arid) {
            filters.add(MutantFilters.ARID);
        }
        if (onePerLine) {
            filters.add(MutantFilters.ONE_PER_LINE);
        }
        return List.copyOf(new LinkedHashSet<>(filters));
    }

    ProjectModel read() {
        if (!Files.isRegularFile(modelFile)) {
            throw new IllegalArgumentException("no project model at " + modelFile.toAbsolutePath()
                    + ". A build-tool adapter produces this file; see docs/architecture.md.");
        }
        ModelIo io = new ModelIo();
        ProjectModel model = io.readProjectModel(modelFile);
        io.warnings().forEach(w -> System.err.println("jzap: " + w));
        return model.withScope(effectiveScope(model.scope()));
    }

    private Scope effectiveScope(Scope fromModel) {
        ScopeKind kind = fromModel.kind();
        if (all) {
            kind = ScopeKind.ALL;
        } else if (patchFile != null) {
            kind = ScopeKind.PATCH;
        } else if (from != null || to != null) {
            kind = ScopeKind.DIFF;
        }
        return new Scope(
                kind,
                from != null ? from : fromModel.from(),
                to != null ? to : fromModel.to(),
                granularity != null ? granularity : fromModel.granularity(),
                patchFile != null ? patchFile.toString() : fromModel.patchFile(),
                include != null ? include : fromModel.includeClasses(),
                exclude != null ? exclude : fromModel.excludeClasses(),
                mutators != null ? mutators : fromModel.mutators(),
                disabledFilters(fromModel),
                optionalFilters(fromModel));
    }
}
