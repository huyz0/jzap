package crosstalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlphaTest {

    @Test
    void scales() {
        assertEquals(6, Alpha.scale(3));
    }
}
