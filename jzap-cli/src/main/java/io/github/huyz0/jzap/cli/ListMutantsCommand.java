package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.core.ClassBytes;
import io.github.huyz0.jzap.core.ClassScanner;
import io.github.huyz0.jzap.core.MutationEngine;
import io.github.huyz0.jzap.core.Mutators;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.ProjectModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Prints the mutant inventory without running a single test.
 *
 * <p>This is what the parity harness compares against PIT's, so it has to be cheap and exact:
 * inventory differences are diagnosed before anyone spends time on verdict differences.
 */
@Command(name = "list-mutants", description = "List the mutants in scope without running tests.")
final class ListMutantsCommand implements Callable<Integer> {

    @Mixin
    ModelOptions modelOptions;

    @Option(names = "--format", paramLabel = "keys|table",
            description = "keys prints one stable mutant key per line (default); table adds detail.")
    String format = "keys";

    @Override
    public Integer call() {
        ProjectModel model;
        ChangedLines changed;
        try {
            model = modelOptions.read();
            changed = ScopeResolution.resolve(model, Path.of("").toAbsolutePath());
        } catch (IllegalArgumentException e) {
            System.err.println("jzap: " + e.getMessage());
            return RunCommand.EXIT_USAGE;
        }

        MutationEngine engine = new MutationEngine(
                Mutators.resolve(model.scope().mutators()),
                model.scope().isFilterEnabled(io.github.huyz0.jzap.core.LoopCounterFilter.ID),
                model.scope().isOptionalFilterEnabled(io.github.huyz0.jzap.core.EquivalenceFilter.ID),
                model.scope().isOptionalFilterEnabled(io.github.huyz0.jzap.core.AridFilter.ID),
                model.scope().isOptionalFilterEnabled(io.github.huyz0.jzap.core.AnalysisEngine.ONE_PER_LINE));
        List<Mutant> mutants = new ArrayList<>();
        for (ModuleModel module : model.modules()) {
            List<ClassBytes> classes = new ClassScanner(
                    model.scope().includeClasses(), model.scope().excludeClasses())
                    .scan(module.mutableCodePaths());
            for (ClassBytes c : classes) {
                for (Mutant m : engine.discover(module.id(), c.bytes())) {
                    if (m.key().line() > 0 && inScope(model, changed, m)) {
                        mutants.add(m);
                    }
                }
            }
        }

        for (Mutant m : Mutant.sorted(mutants)) {
            if ("table".equals(format)) {
                System.out.printf("%-70s %-22s %s%n", m.key().asString(), m.key().mutator(),
                        m.description());
            } else {
                System.out.println(m.key().asString());
            }
        }
        System.err.println("jzap: " + mutants.size() + " mutants in scope");
        int dropped = engine.equivalentDropped() + engine.duplicateDropped()
                + engine.aridDropped() + engine.onePerLineDropped();
        if (dropped > 0 || !model.scope().enabledFilters().isEmpty()) {
            System.err.println("jzap: filters dropped " + dropped + " mutant(s): "
                    + engine.equivalentDropped() + " equivalent to the original, "
                    + engine.duplicateDropped() + " duplicates, "
                    + engine.aridDropped() + " in code that only reports, "
                    + engine.onePerLineDropped() + " beyond one per line");
        }
        return RunCommand.EXIT_OK;
    }

    private boolean inScope(ProjectModel model, ChangedLines changed, Mutant m) {
        if (changed == null) {
            return true;
        }
        String className = m.key().className();
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/') + "/";
        String path = packagePath + (m.sourceFile() == null ? "" : m.sourceFile());
        return model.scope().isClassGranularity()
                ? changed.containsPath(path)
                : changed.containsLine(path, m.key().line());
    }
}
