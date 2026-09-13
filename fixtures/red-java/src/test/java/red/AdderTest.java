package red;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One passing test and one that fails before any mutant is applied.
 *
 * <p>The failing one is the point. A mutant it covers would be reported as killed by a failure
 * that has nothing to do with the mutant, so jzap has to notice and exclude it -- and that
 * detection was silently broken until a test forced the issue.
 */
class AdderTest {

    @Test
    void addsCorrectly() {
        assertEquals(5, new Adder().add(2, 3));
    }

    @Test
    void subtractsIncorrectlyOnPurpose() {
        assertEquals(99, new Adder().subtract(5, 3));
    }
}
