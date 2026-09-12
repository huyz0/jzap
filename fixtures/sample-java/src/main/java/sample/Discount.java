package sample;

/**
 * Deliberately simple pricing logic with a deliberately incomplete test suite, so the
 * expected verdict for every mutant can be derived by hand. See jzap-e2e.
 */
public class Discount {

    public int applyPercent(int price, int percent) {
        if (percent > 50) {
            percent = 50;
        }
        return price - (price * percent / 100);
    }

    public boolean isFree(int price) {
        return price == 0;
    }
}
