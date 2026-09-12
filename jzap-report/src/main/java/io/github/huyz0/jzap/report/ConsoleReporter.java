package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The terminal summary.
 *
 * <p>Leads with surviving mutants rather than with the score, because the survivors are the
 * actionable part: each one is a change to the code that the test suite does not notice.
 */
public final class ConsoleReporter implements Reporter {

    private final PrintStream out;

    public ConsoleReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public String id() {
        return "console";
    }

    @Override
    public void write(AnalysisResult result, ReportContext context) {
        SourceLocator sources = new SourceLocator(context.sourceRoots());

        if (!result.failingBaselineTests().isEmpty()) {
            out.println();
            out.println("WARNING: " + result.failingBaselineTests().size()
                    + " test(s) already fail without any mutant applied:");
            for (String test : result.failingBaselineTests()) {
                out.println("  " + test);
            }
            out.println("  These were excluded from test selection. Fix them before trusting any");
            out.println("  verdict below: a mutant they cover would look killed for the wrong reason.");
        }

        List<Mutant> survivors = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.SURVIVED)
                .toList();

        if (!survivors.isEmpty()) {
            out.println();
            out.println("Surviving mutants (" + survivors.size() + "):");
            Map<String, List<Mutant>> byFile = new LinkedHashMap<>();
            for (Mutant m : survivors) {
                byFile.computeIfAbsent(SourceLocator.relativePath(m), k -> new java.util.ArrayList<>()).add(m);
            }
            byFile.forEach((file, mutants) -> {
                out.println("  " + file);
                for (Mutant m : mutants) {
                    out.println("    line " + m.key().line() + ": " + m.description()
                            + " [" + m.key().mutator() + "]");
                    sources.line(m).ifPresent(code -> out.println("      " + code));
                }
            });
        }

        out.println();
        out.println("Scope: " + result.scopeSummary());
        out.println("Engine: " + result.engine() + ", tests discovered: " + result.testsDiscovered());
        out.println();
        out.printf("  killed       %5d%n", result.count(MutantStatus.KILLED));
        out.printf("  survived     %5d%n", result.count(MutantStatus.SURVIVED));
        out.printf("  no coverage  %5d%n", result.count(MutantStatus.NO_COVERAGE));
        if (result.count(MutantStatus.TIMED_OUT) > 0) {
            out.printf("  timed out    %5d%n", result.count(MutantStatus.TIMED_OUT));
        }
        if (result.count(MutantStatus.NON_VIABLE) > 0) {
            out.printf("  non viable   %5d%n", result.count(MutantStatus.NON_VIABLE));
        }
        if (result.count(MutantStatus.RUN_ERROR) > 0) {
            out.printf("  run errors   %5d%n", result.count(MutantStatus.RUN_ERROR));
        }
        out.println("  " + "-".repeat(18));
        out.printf("  total        %5d%n", result.mutants().size());
        out.println();
        out.printf("Mutation score %.1f%%  (test strength %.1f%%, ignoring uncovered mutants)%n",
                result.mutationScore(), result.testStrength());
        result.timings().forEach((phase, millis) -> {
            if (!phase.startsWith("module:")) {
                out.printf("  %-10s %6d ms%n", phase, millis);
            }
        });
    }
}
