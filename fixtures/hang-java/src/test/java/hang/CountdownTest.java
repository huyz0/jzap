package hang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CountdownTest {

    @Test
    void countsDownToZero() {
        assertEquals(3L, new Countdown().countdown(3L));
    }

    @Test
    void doubles() {
        assertEquals(8, new Countdown().twice(4));
    }
}
