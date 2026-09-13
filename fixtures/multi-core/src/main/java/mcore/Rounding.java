package mcore;

/**
 * A library class with no tests of its own. Everything that exercises it lives in another module,
 * which is the case single-module mutation testing cannot see.
 */
public class Rounding {

    public int roundUpTo(int value, int step) {
        if (step <= 0) {
            return value;
        }
        int remainder = value % step;
        return remainder == 0 ? value : value + (step - remainder);
    }

    public boolean isRound(int value, int step) {
        return step > 0 && value % step == 0;
    }
}
