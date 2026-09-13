package blocking;

/**
 * Code a mutant can make block forever without executing a single loop iteration.
 *
 * <p>jzap detects a runaway mutant by counting loop back edges, which gives the same verdict on
 * every machine. Blocking has no back edges to count, so nothing trips: this is precisely the
 * case the wall-clock backstop exists for, and the only case where a verdict does depend on the
 * clock. Negating the clamp below removes the upper bound on the wait.
 */
public final class Waiter {

    /** An absurd wait, so the backstop fires rather than the test merely being slow. */
    private static final long UNBOUNDED_MILLIS = 10 * 60 * 1000L;

    private Waiter() {
    }

    public static long clampedWait(long requestedMillis) {
        long millis = requestedMillis;
        if (millis > 50L) {
            millis = 50L;
        }
        sleep(millis);
        return millis;
    }

    /** Kept separate so the mutant above is the only way to reach an unbounded wait. */
    public static long unboundedWait() {
        sleep(UNBOUNDED_MILLIS);
        return UNBOUNDED_MILLIS;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
