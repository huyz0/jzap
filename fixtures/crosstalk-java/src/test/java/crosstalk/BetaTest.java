package crosstalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetaTest {

    /**
     * Executes {@code untested} without checking it, and in the same test asserts on a value that
     * comes through {@link Alpha}.
     *
     * <p>One test, deliberately: the mutant in {@code untested} is only reported killed if this
     * test fails, and the only way this test can fail while analysing that mutant is a mutation
     * that is not in {@code untested} at all.
     */
    @Test
    void runsUntestedAndAssertsThroughAlpha() {
        Beta.untested(2);
        assertEquals(4, Beta.viaAlpha(2));
    }
}
