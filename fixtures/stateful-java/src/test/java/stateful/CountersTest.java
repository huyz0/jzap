package stateful;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountersTest {

    @Test
    void countsUp() {
        // Deliberately tolerant of the running total, because the point of this fixture is the
        // state, not the arithmetic.
        assertTrue(new Counters().count() > 0);
    }

    @Test
    void memoises() {
        assertEquals(new Counters().expensive(21), new Counters().expensive(1000),
                "the second call must return the memoised answer");
    }

    @Test
    void adds() {
        assertEquals(5, new Counters().plain(2, 3));
    }
}
