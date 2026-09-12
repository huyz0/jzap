package hang;

/**
 * A fixture for the one path that is hard to test any other way: a mutant that never returns.
 *
 * <p>Mutating {@code n - 1} to {@code n + 1} makes the loop run forever, so the analysis JVM
 * has to be killed. Two details are deliberate:
 *
 * <ul>
 *   <li>The counter is a {@code long}. With an {@code int} the mutant escapes after about two
 *       billion iterations when the value wraps negative, so it is killed in a couple of
 *       seconds instead of hanging — which is exactly what the first version of this fixture
 *       did.
 *   <li>The increment is an assignment rather than {@code n--}, because increments that drive a
 *       loop are filtered by default and would produce no mutant here at all.
 * </ul>
 */
public class Countdown {

    public long countdown(long n) {
        long steps = 0;
        while (n > 0) {
            n = n - 1;
            steps = steps + 1;
        }
        return steps;
    }

    public int twice(int value) {
        return value * 2;
    }
}
