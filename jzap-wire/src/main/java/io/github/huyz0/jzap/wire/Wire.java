package io.github.huyz0.jzap.wire;

/**
 * Controller/minion protocol constants.
 *
 * <p>Hand-rolled binary rather than JSON on purpose. This code runs on the classpath of the
 * JVM executing the user's tests, where a serialisation library would be one more thing to
 * clash with the project's own dependencies.
 */
public final class Wire {

    private Wire() {
    }

    /** Sized probe array, plus instrumented bytecode for every target class. */
    public static final byte CMD_INIT_COVERAGE = 1;
    /** Discover tests and return their unique ids. */
    public static final byte CMD_LIST_TESTS = 2;
    /** Run one test, returning its outcome, duration and the probes it hit. */
    public static final byte CMD_RUN_TEST_COVERAGE = 3;
    /** Install replacement bytecode for one class. */
    public static final byte CMD_SET_OVERRIDE = 4;
    /** Restore every overridden class to its original bytecode. */
    public static final byte CMD_CLEAR_OVERRIDES = 5;
    /**
     * Run a selection of tests, stopping at the first failure.
     *
     * <p>Carries the schemata mutant index so activating it, running, and resetting are one round
     * trip rather than three. At a millisecond and a half per mutant, three round trips were a
     * fifth of the execution phase.
     */
    public static final byte CMD_RUN_TESTS = 6;
    /** Shut the minion down cleanly. */
    public static final byte CMD_EXIT = 7;

    // 8 was CMD_ACTIVATE_MUTANT, which selected a schemata mutant on its own. CMD_RUN_TESTS
    // carries the index now, so activating, running and resetting cost one round trip rather
    // than three. The number is left unused rather than reassigned: a command byte that once
    // meant something else is worth not reusing.

    /**
     * Get ready to run tests, without discovering them.
     *
     * <p>Discovery of a whole suite is expensive -- 230ms for 200 tests on the bench fixture -- and
     * the execution phase never needs it: it selects tests by the unique ids the coverage phase
     * already found. Before this existed, every analysis JVM paid for a full discovery it then
     * ignored, which was 44% of the execution phase.
     */
    public static final byte CMD_PREPARE_TESTS = 9;

    public static final byte RESP_OK = 20;
    public static final byte RESP_ERROR = 21;

    /** Every selected test passed: the mutant is not detected by them. */
    public static final byte OUTCOME_ALL_PASSED = 0;
    /** A test failed: the mutant is detected. */
    public static final byte OUTCOME_FAILED = 1;
    /** The mutated class could not be used at all, e.g. it failed verification. */
    public static final byte OUTCOME_NON_VIABLE = 2;
    /**
     * The mutated code looped far past what the original needed.
     *
     * <p>Distinct from a wall-clock timeout: this verdict is reached by counting iterations, so
     * it is the same on every machine.
     */
    public static final byte OUTCOME_RUNAWAY = 3;
}
