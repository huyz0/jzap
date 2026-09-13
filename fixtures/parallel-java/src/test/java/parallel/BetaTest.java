package parallel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately slow, so that analysing this fixture is worth more than one analysis JVM.
 *
 * <p>The sleep is the whole point. jzap sizes its worker pool from the measured duration of the
 * tests a mutant needs, and every other fixture in this repository is too quick to justify a
 * second JVM -- so without a slow one, the parallel execution path is never exercised by a test.
 * Sleeping rather than computing keeps it slow without making it sensitive to how fast the
 * machine is.
 */
class BetaTest {

    /** Long enough that a handful of mutants add up to more than one worker's worth of work. */
    private static void slowly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final Beta beta = new Beta();

    @Test
    void combines() {
        slowly();
        assertEquals(7, beta.combine(3, 4));
    }

    @Test
    void scales() {
        slowly();
        assertEquals(12, beta.scale(3, 4));
    }

    @Test
    void comparesAtTheBoundary() {
        slowly();
        assertTrue(beta.atLeast(4, 4));
        assertFalse(beta.atLeast(3, 4));
    }

    @Test
    void adjusts() {
        slowly();
        assertEquals(4, beta.adjust(5));
    }
}
