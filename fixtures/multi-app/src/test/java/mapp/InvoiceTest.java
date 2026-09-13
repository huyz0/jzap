package mapp;

import mcore.Rounding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceTest {

    @Test
    void roundsTheTotalUp() {
        assertEquals(15, new Invoice().total(11));
        assertEquals(10, new Invoice().total(10));
    }

    @Test
    void addsASurcharge() {
        assertEquals(8, new Invoice().surcharge(4));
    }

    /** A test in this module that exercises the other module directly. */
    @Test
    void recognisesRoundValues() {
        Rounding rounding = new Rounding();
        assertTrue(rounding.isRound(10, 5));
        assertFalse(rounding.isRound(11, 5));
    }
}
