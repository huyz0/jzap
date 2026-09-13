package crosstalk;

/** Analysed after {@link Alpha}, and exercising it from the same test. */
public final class Beta {

    /**
     * Called by the test but never asserted on, so every mutant here must survive.
     *
     * <p>This is the mutant that detects the bug: it cannot be killed by any assertion, so if it
     * comes back killed, something other than this code decided the verdict.
     */
    public static int untested(int n) {
        return n + 1;
    }

    /** Asserted on, and reached through {@link Alpha}, so a mutant in Alpha fails this test. */
    public static int viaAlpha(int n) {
        return Alpha.scale(n);
    }
}
