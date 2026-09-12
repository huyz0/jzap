package sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscountTest {

    @Test
    void appliesTenPercent() {
        assertEquals(90, new Discount().applyPercent(100, 10));
    }

    @Test
    void capsDiscountAtFiftyPercent() {
        assertEquals(50, new Discount().applyPercent(100, 90));
    }

    @Test
    void zeroPriceIsFree() {
        // Only the true case is asserted, which is why some mutants in isFree survive.
        assertTrue(new Discount().isFree(0));
    }
}
