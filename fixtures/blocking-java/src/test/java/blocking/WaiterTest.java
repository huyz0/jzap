package blocking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exactly one test, deliberately.
 *
 * <p>The point of this fixture is a mutant whose only observable effect is an unbounded wait. A
 * second test covering a short wait would notice the missing clamp immediately and kill the
 * mutant in milliseconds -- which is a perfectly good outcome for a test suite and useless for
 * exercising the wall-clock backstop.
 */
class WaiterTest {

    @Test
    void clampsALongWait() {
        assertEquals(50L, Waiter.clampedWait(100_000L));
    }
}
