package io.github.huyz0.jzap.model;

import java.util.List;
import java.util.Map;

/**
 * The complete outcome of a run. This record is the input to every reporter and to the
 * parity harness, so its shape is part of jzap's public contract.
 *
 * @param mutants        every mutant in scope, with its outcome, in stable key order
 * @param timings        phase durations in millis, keyed by phase name
 * @param testsDiscovered number of tests found during the coverage phase
 * @param scopeSummary   human-readable description of what was analysed
 * @param engine         engine id that produced this result, e.g. {@code naive}
 * @param failingBaselineTests tests that already failed before any mutant was applied. Every
 *                       mutant these cover would look killed for the wrong reason, so they
 *                       are excluded from selection and reported instead of silently trusted.
 * @param reusedFromCache mutants whose verdict came from a previous run rather than from
 *                       executing anything
 */
public record AnalysisResult(
        List<Mutant> mutants,
        Map<String, Long> timings,
        int testsDiscovered,
        String scopeSummary,
        String engine,
        List<String> failingBaselineTests,
        int reusedFromCache) {

    public AnalysisResult {
        mutants = mutants == null ? List.of() : List.copyOf(mutants);
        timings = timings == null ? Map.of() : Map.copyOf(timings);
        failingBaselineTests = failingBaselineTests == null ? List.of() : List.copyOf(failingBaselineTests);
    }

    public long count(MutantStatus status) {
        return mutants.stream().filter(m -> m.status() == status).count();
    }

    /** Mutants with coverage, i.e. those a score can meaningfully be computed over. */
    public long covered() {
        return mutants.stream().filter(m -> m.status() != null && m.status() != MutantStatus.NO_COVERAGE).count();
    }

    public long detected() {
        return mutants.stream().filter(m -> m.status() != null && m.status().isDetected()).count();
    }

    /** Mutation score over all mutants, including uncovered ones. */
    public double mutationScore() {
        return mutants.isEmpty() ? 0.0 : 100.0 * detected() / mutants.size();
    }

    /** Mutation score over covered mutants only, which is the figure PIT calls test strength. */
    public double testStrength() {
        long covered = covered();
        return covered == 0 ? 0.0 : 100.0 * detected() / covered;
    }
}
