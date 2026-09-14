package io.github.huyz0.jzap.model;

/** Outcome of analysing a single mutant. */
public enum MutantStatus {
    /** A test failed with the mutant active: the test suite detects this fault. */
    KILLED,
    /** Tests covered the mutated code and all passed: an undetected fault. */
    SURVIVED,
    /** No test executes the mutated code. */
    NO_COVERAGE,
    /** Tests hung with the mutant active. Never cached, because it is not deterministic. */
    TIMED_OUT,
    /** The mutated class failed verification or linkage: not a real fault. */
    NON_VIABLE,
    /** The analysis itself failed. Always a jzap bug or an environment problem. */
    RUN_ERROR;

    public boolean isDetected() {
        return this == KILLED || this == TIMED_OUT;
    }

    /**
     * Whether this outcome says anything about the test suite, and so belongs in the score.
     *
     * <p>{@link #NON_VIABLE} and {@link #RUN_ERROR} do not. A mutant the JVM refused to load was
     * never run against a test, and a mutant whose analysis broke was never judged either: in both
     * cases the tests were not given the chance to detect a fault. Counting such a mutant as
     * undetected blames the suite for jzap's or the environment's problem and drags the score down;
     * counting it as detected, which is what PIT does, credits the suite for a fault it never saw
     * and drags the score up. Both answers are wrong, in opposite directions, so these mutants
     * leave the ratio entirely -- out of the numerator and out of the denominator.
     *
     * <p>This also makes jzap's own score agree with the mutation-testing-elements report it
     * already writes. That schema calls the two {@code CompileError} and {@code RuntimeError} and
     * defines its score over valid mutants only, so before this the two reports from one run could
     * disagree about the same number.
     *
     * <p>They are still counted and still reported; see {@link AnalysisResult#unscored()}. A
     * {@code RUN_ERROR} additionally fails the run, because leaving the score honest is no use if
     * a broken analysis passes quietly.
     */
    public boolean isScored() {
        return this != NON_VIABLE && this != RUN_ERROR;
    }

    /** Statuses that must never be reused from a cache, because they are not reproducible. */
    public boolean isCacheable() {
        return this != TIMED_OUT && this != RUN_ERROR;
    }
}
