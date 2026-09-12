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

    /** Statuses that must never be reused from a cache, because they are not reproducible. */
    public boolean isCacheable() {
        return this != TIMED_OUT && this != RUN_ERROR;
    }
}
