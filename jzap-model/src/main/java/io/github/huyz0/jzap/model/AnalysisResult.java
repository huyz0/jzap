package io.github.huyz0.jzap.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete outcome of a run. This record is the input to every reporter and to the
 * parity harness, so its shape is part of jzap's public contract.
 *
 * @param mutants        every mutant in scope, with its outcome, in stable key order
 * @param timings        phase durations in millis, keyed by phase name, in the order they
 *                       were measured. Order is preserved rather than copied away because the
 *                       JSON report is meant to be byte-stable between runs of the same shape.
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
        // Map.copyOf would drop the iteration order, which the JSON report's determinism
        // depends on.
        timings = timings == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(timings));
        failingBaselineTests = failingBaselineTests == null ? List.of() : List.copyOf(failingBaselineTests);
    }

    public long count(MutantStatus status) {
        return mutants.stream().filter(m -> m.status() == status).count();
    }

    /**
     * Mutants whose outcome is a statement about the test suite, and so the ones a score is a
     * ratio over.
     *
     * <p>Everything except {@code NON_VIABLE}, {@code RUN_ERROR} and mutants not analysed at all;
     * {@link MutantStatus#isScored()} says why those three leave the ratio rather than landing on
     * one side of it.
     */
    public long scored() {
        return mutants.stream().filter(m -> m.status() != null && m.status().isScored()).count();
    }

    /**
     * Mutants excluded from the score because nothing was learned about the tests from them.
     *
     * <p>Reported rather than dropped. A run with a large number here analysed much less than its
     * mutant count suggests, and the score alone would not show that.
     */
    public long unscored() {
        return mutants.size() - scored();
    }

    /** Scored mutants with coverage, i.e. those test strength is a ratio over. */
    public long covered() {
        return mutants.stream()
                .filter(m -> m.status() != null && m.status().isScored())
                .filter(m -> m.status() != MutantStatus.NO_COVERAGE)
                .count();
    }

    public long detected() {
        return mutants.stream().filter(m -> m.status() != null && m.status().isDetected()).count();
    }

    /** Mutation score over every scored mutant, including uncovered ones. */
    public double mutationScore() {
        long scored = scored();
        return scored == 0 ? 0.0 : 100.0 * detected() / scored;
    }

    /** Mutation score over covered mutants only, which is the figure PIT calls test strength. */
    public double testStrength() {
        long covered = covered();
        return covered == 0 ? 0.0 : 100.0 * detected() / covered;
    }
}
