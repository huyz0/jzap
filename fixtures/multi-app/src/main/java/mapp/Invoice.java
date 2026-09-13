package mapp;

import mcore.Rounding;

/** Application code that uses the library module, and has tests of its own. */
public class Invoice {

    private final Rounding rounding = new Rounding();

    public int total(int amount) {
        return rounding.roundUpTo(amount, 5);
    }

    public int surcharge(int amount) {
        return amount * 2;
    }
}
