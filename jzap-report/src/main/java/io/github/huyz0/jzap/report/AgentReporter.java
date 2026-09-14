package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The findings, and nothing else, for a coding agent reading this as command output.
 *
 * <p>An agent pays for every token it reads, and the reporter a machine would otherwise consume
 * is {@code json}, which carries every mutant including the ones that were killed. Measured on
 * the bench fixture -- 1080 mutants, 125 survivors, 120 uncovered -- this reporter emits 16 KB
 * against the JSON report's 509 KB: roughly four thousand tokens against a hundred and thirty
 * thousand, for the same findings an agent can act on.
 *
 * <p>It is not smaller than {@code console}; on that fixture the two are within 5% of each other,
 * because this one drops the source snippets and then spends the saving listing uncovered mutants
 * that {@code console} omits. The difference is shape rather than size: no prose, no snippets,
 * one finding per line, and the headline first.
 *
 * <p>What is dropped and why:
 *
 * <ul>
 *   <li><b>Killed mutants.</b> There is nothing to do about one. It is the good outcome, and it
 *       is the overwhelming majority of any healthy run.
 *   <li><b>Source snippets.</b> A {@code path:line} is enough for an agent to open the file, and
 *       it can read more context than one line if it needs to.
 *   <li><b>Timings.</b> Not actionable.
 * </ul>
 *
 * <p>The summary line comes first, and survivors come before uncovered mutants, so that a read
 * truncated at any point keeps the most useful part. Survivors are the findings a test suite is
 * wrong about; an uncovered mutant only says no test runs the line, which a coverage tool already
 * says more cheaply.
 *
 * <p>Written to stdout rather than to a file, because an agent reads what the command printed.
 */
final class AgentReporter implements Reporter {

    private final PrintStream out;

    AgentReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public String id() {
        return "agent";
    }

    @Override
    public void write(AnalysisResult result, ReportContext context) {
        out.println(summary(result));

        if (!result.failingBaselineTests().isEmpty()) {
            // First, and not abbreviated: every verdict below is suspect until these are fixed,
            // so an agent that acts on the findings without seeing this does the wrong work.
            out.println();
            out.println("baseline-failures: " + result.failingBaselineTests().size()
                    + " test(s) already fail with no mutant applied; verdicts below are unreliable"
                    + " until they are fixed");
            for (String test : result.failingBaselineTests()) {
                out.println("  " + test);
            }
        }

        section("survived", result, MutantStatus.SURVIVED);
        section("uncovered", result, MutantStatus.NO_COVERAGE);
        section("timed-out", result, MutantStatus.TIMED_OUT);
    }

    /**
     * One line, so a truncated read still gets the headline.
     *
     * <p>Counts before percentages: "2 survived" tells an agent how much work it is looking at,
     * where a score does not.
     */
    private static String summary(AnalysisResult result) {
        StringBuilder line = new StringBuilder("jzap: ");
        line.append(result.count(MutantStatus.SURVIVED)).append(" survived, ")
                .append(result.count(MutantStatus.NO_COVERAGE)).append(" uncovered");
        if (result.count(MutantStatus.TIMED_OUT) > 0) {
            line.append(", ").append(result.count(MutantStatus.TIMED_OUT)).append(" timed out");
        }
        if (result.unscored() > 0) {
            // Named rather than folded into the total: these are outside both percentages, so a
            // score that looks fine can be a score over very little.
            line.append(", ").append(result.unscored()).append(" not scored");
        }
        line.append(" of ").append(result.mutants().size()).append(" mutants");
        line.append(String.format(Locale.ROOT, " (score %.1f%%, strength %.1f%%)",
                result.mutationScore(), result.testStrength()));
        return line.toString();
    }

    /**
     * Findings of one status, grouped by file so the path is written once rather than per line.
     *
     * <p>The status is the section heading rather than a column, for the same reason.
     */
    private void section(String heading, AnalysisResult result, MutantStatus status) {
        Map<String, List<Mutant>> byFile = new LinkedHashMap<>();
        for (Mutant m : Mutant.sorted(result.mutants())) {
            if (m.status() == status) {
                byFile.computeIfAbsent(SourceLocator.relativePath(m), k -> new ArrayList<>()).add(m);
            }
        }
        if (byFile.isEmpty()) {
            return;
        }
        out.println();
        out.println(heading + ":");
        byFile.forEach((file, mutants) -> {
            // By line, not by mutant key. The key orders by method first, so two findings in one
            // file came out as 11 then 7 -- which reads as a mistake to anyone following along in
            // the source, and costs an agent a re-sort it should not have to do.
            mutants.sort((a, b) -> {
                int byLine = Integer.compare(a.key().line(), b.key().line());
                return byLine != 0 ? byLine : a.key().compareTo(b.key());
            });
            out.println(file);
            for (Mutant m : mutants) {
                out.println("  " + m.key().line() + " " + m.key().mutator() + " " + m.description());
            }
        });
    }
}
