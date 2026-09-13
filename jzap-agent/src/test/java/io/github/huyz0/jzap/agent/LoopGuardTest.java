package io.github.huyz0.jzap.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runaway-loop counter, which is what makes a hung mutant a deterministic verdict.
 *
 * <p>The property that matters is that the same mutant trips at the same iteration every time,
 * on any machine. A wall-clock timeout cannot promise that, and these tests are what stop the
 * counter quietly drifting back towards one.
 */
class LoopGuardTest {

    @AfterEach
    void disarm() {
        LoopGuard.disarm();
    }

    @Test
    void countsIterationsWhileUnderTheLimit() {
        LoopGuard.arm(10);
        for (int i = 0; i < 10; i++) {
            LoopGuard.tick();
        }
        assertEquals(10, LoopGuard.ticks());
    }

    @Test
    void tripsOnTheIterationAfterTheLimit() {
        LoopGuard.arm(3);
        LoopGuard.tick();
        LoopGuard.tick();
        LoopGuard.tick();
        RunawayLoopError error = assertThrows(RunawayLoopError.class, LoopGuard::tick);
        assertEquals(4, error.ticks(), "the tick that tripped is the one past the limit");
        assertEquals(3, error.limit());
        assertTrue(error.getMessage().contains("past the limit of 3"),
                "the message has to say what the limit was, since it is derived per mutant: "
                        + error.getMessage());
    }

    @Test
    void tripsAtTheSameIterationEveryTime() {
        for (int attempt = 0; attempt < 3; attempt++) {
            LoopGuard.arm(5);
            int ticks = 0;
            try {
                while (true) {
                    LoopGuard.tick();
                    ticks++;
                }
            } catch (RunawayLoopError expected) {
                assertEquals(5, ticks, "attempt " + attempt + " must trip where the others did");
            }
        }
    }

    @Test
    void resetsTheCountSoTheNextMutantStartsClean() {
        LoopGuard.arm(3);
        LoopGuard.tick();
        LoopGuard.tick();
        LoopGuard.tick();
        assertThrows(RunawayLoopError.class, LoopGuard::tick);
        assertEquals(0, LoopGuard.ticks(),
                "a mutant that tripped must not leave its count behind for the next one");
    }

    @Test
    void aLimitOfZeroCountsWithoutEverTripping() {
        // What the coverage run uses: it is establishing the baseline, so nothing should trip.
        LoopGuard.arm(0);
        for (int i = 0; i < 10_000; i++) {
            LoopGuard.tick();
        }
        assertEquals(10_000, LoopGuard.ticks());
    }

    @Test
    void aNegativeLimitIsTreatedAsNoLimit() {
        LoopGuard.arm(-1);
        LoopGuard.tick();
        assertEquals(1, LoopGuard.ticks());
    }

    @Test
    void disarmingStopsCountingAndClears() {
        LoopGuard.arm(10);
        LoopGuard.tick();
        LoopGuard.disarm();
        assertEquals(0, LoopGuard.ticks());
        for (int i = 0; i < 1000; i++) {
            LoopGuard.tick();
        }
        assertEquals(1000, LoopGuard.ticks(), "a disarmed guard still counts, it just never trips");
    }
}
